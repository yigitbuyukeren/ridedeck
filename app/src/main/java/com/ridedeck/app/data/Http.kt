package com.ridedeck.app.data

import android.util.Log
import com.ridedeck.app.BuildConfig
import java.net.HttpURLConnection
import java.net.URL
import java.io.File
import java.io.FileOutputStream
import java.io.EOFException

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
