package com.ridedeck.app.data

import android.util.Log
import com.ridedeck.app.BuildConfig
import java.net.HttpURLConnection
import java.net.URL
import java.io.File
import java.io.FileOutputStream
import java.io.EOFException
import java.io.IOException
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ThreadFactory
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Dispatcher
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response

/**
 * The one HTTP helper for all public, keyless services. Every request identifies the app and
 * where to reach its maintainers, as the Nominatim and Overpass usage policies require.
 */
object Http {
    const val USER_AGENT = "RideDeck/${BuildConfig.VERSION_NAME} (+https://github.com/yigitbuyukeren/ridedeck)"

    fun get(url: String, connectMs: Int = 10_000, readMs: Int = 12_000): String =
        request(url, null, null, connectMs, readMs, emptyOn400 = false)

    fun postJson(url: String, body: String, connectMs: Int = 10_000, readMs: Int = 12_000, emptyOn400: Boolean = false): String =
        request(url, body, "application/json", connectMs, readMs, emptyOn400)

    fun postForm(url: String, body: String, connectMs: Int = 10_000, readMs: Int = 30_000): String =
        request(url, body, "application/x-www-form-urlencoded", connectMs, readMs, emptyOn400 = false)

    /**
     * Suspending variants for callers that can be cancelled. They send exactly the same request and
     * status handling as the blocking functions, but close the connection as soon as the calling
     * coroutine is cancelled, so a slow server cannot hold the caller until its read timeout.
     */
    suspend fun getCancellable(url: String, connectMs: Int = 10_000, readMs: Int = 12_000): String =
        requestCancellable(url, null, null, connectMs, readMs, emptyOn400 = false)

    suspend fun postJsonCancellable(
        url: String, body: String, connectMs: Int = 10_000, readMs: Int = 12_000, emptyOn400: Boolean = false,
    ): String = requestCancellable(url, body, "application/json", connectMs, readMs, emptyOn400)

    suspend fun postFormCancellable(
        url: String, body: String, connectMs: Int = 10_000, readMs: Int = 30_000,
    ): String = requestCancellable(url, body, "application/x-www-form-urlencoded", connectMs, readMs, emptyOn400 = false)

    /** Download a public model archive without routing binary bytes through a text API. */
    fun download(url: String, target: File, onProgress: (Long, Long) -> Unit = { _, _ -> }) {
        require(URL(url).protocol == "https") { "Downloads must use HTTPS" }
        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = 15_000
            conn.readTimeout = 60_000
            conn.setRequestProperty("User-Agent", USER_AGENT)
            if (conn.responseCode !in 200..299) error("HTTP ${conn.responseCode}")
            val total = conn.contentLengthLong
            var read = 0L
            conn.inputStream.use { input ->
                FileOutputStream(target).use { output ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        output.write(buffer, 0, count)
                        read += count
                        onProgress(read, total)
                    }
                }
            }
            if (total > 0 && read != total) throw EOFException("Incomplete download: received $read of $total bytes")
        } finally {
            conn.disconnect()
        }
    }

    /** [emptyOn400] turns a 400 into "{}": Valhalla uses it for "could not snap to a road", not an outage. */
    private fun request(
        url: String, postBody: String?, contentType: String?, connectMs: Int, readMs: Int, emptyOn400: Boolean,
    ): String {
        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = connectMs
            conn.readTimeout = readMs
            conn.setRequestProperty("User-Agent", USER_AGENT)
            if (postBody != null) {
                conn.requestMethod = "POST"
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", contentType)
                conn.outputStream.use { it.write(postBody.toByteArray()) }
            }
            if (emptyOn400 && conn.responseCode == 400) return "{}"
            if (conn.responseCode !in 200..299) error("HTTP ${conn.responseCode}")
            return conn.inputStream.bufferedReader().use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }

    /**
     * OkHttp is used only here, where cancellation must abort the socket. [Call.cancel] is its
     * mechanism for aborting an in-flight connect, write or read; the slow-read path is measured by
     * the loopback tests. The synchronous HttpURLConnection path above is unchanged for every
     * existing caller. The dispatcher is a fixed pool shared by all cancellable calls, so a tap
     * never creates its own thread or an unbounded pool.
     */
    private suspend fun requestCancellable(
        url: String, postBody: String?, contentType: String?, connectMs: Int, readMs: Int, emptyOn400: Boolean,
    ): String {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", USER_AGENT)
            .apply {
                if (postBody != null) {
                    // Keep the exact header the blocking path sent; OkHttp would otherwise append a charset.
                    contentType?.let { header("Content-Type", it) }
                    post(postBody.toRequestBody(null))
                }
            }
            .build()
        // newBuilder shares the connection pool and bounded dispatcher; only the timeouts differ.
        val client = cancellableClient.newBuilder()
            .connectTimeout(connectMs.toLong(), TimeUnit.MILLISECONDS)
            .readTimeout(readMs.toLong(), TimeUnit.MILLISECONDS)
            .build()
        val call = client.newCall(request)
        return suspendCancellableCoroutine { continuation ->
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    continuation.resumeWithException(e)
                }

                override fun onResponse(call: Call, response: Response) {
                    response.use {
                        try {
                            val result = when {
                                emptyOn400 && it.code == 400 -> "{}"
                                it.code !in 200..299 -> error("HTTP ${it.code}")
                                else -> it.body?.string().orEmpty()
                            }
                            continuation.resume(result)
                        } catch (e: Exception) {
                            continuation.resumeWithException(e)
                        }
                    }
                }
            })
        }
    }

    /**
     * Four running plus four handoff-queued tasks: OkHttp decrements its in-flight count and
     * promotes a queued call before the finishing executor runnable returns, so the queue must
     * absorb up to four such promotions. [Dispatcher.maxRequests] is also four, keeping the
     * executor bound at 4 running + 4 queued; idle core threads retire after 60 s.
     */
    private val cancellableDispatcher = Dispatcher(
        ThreadPoolExecutor(
            4, 4, 60L, TimeUnit.SECONDS, ArrayBlockingQueue(4),
            ThreadFactory { runnable -> Thread(runnable, "ridedeck-http").apply { isDaemon = true } },
        ).apply { allowCoreThreadTimeOut(true) },
    ).apply {
        maxRequests = 4
        maxRequestsPerHost = 4
    }

    private val cancellableClient = OkHttpClient.Builder()
        .dispatcher(cancellableDispatcher)
        .build()
}

/** Like runCatching, but lets coroutine cancellation through and logs real failures. */
internal inline fun <T> safely(tag: String, block: () -> T): Result<T> =
    try {
        Result.success(block())
    } catch (e: kotlinx.coroutines.CancellationException) {
        throw e
    } catch (e: Exception) {
        Log.w("RideDeck", "$tag failed (${e.javaClass.simpleName})")
        Result.failure(e)
    }
