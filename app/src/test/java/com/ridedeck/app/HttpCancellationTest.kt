package com.ridedeck.app

import com.ridedeck.app.data.Http
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.IOException
import java.io.InputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.charset.StandardCharsets
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Loopback-only checks for the cancellable HTTP variants. Nothing here resolves or contacts a
 * public provider; every request goes to a ServerSocket bound to the loopback address.
 */
class HttpCancellationTest {
    @Test
    fun getKeepsStatusBodyAndUserAgent() = runBlocking {
        LoopbackServer().use { server ->
            assertEquals("hello", Http.getCancellable("${server.base}/ok", connectMs = 2_000, readMs = 2_000))
            assertEquals("GET", server.lastMethod)
            assertEquals(Http.USER_AGENT, server.lastUserAgent)
            assertEquals(null, server.lastContentType)
        }
    }

    @Test
    fun postJsonMatchesTheBlockingApi() = runBlocking {
        LoopbackServer().use { server ->
            val url = "${server.base}/echo"
            val body = """{"a":1}"""
            val blocking = Http.postJson(url, body, connectMs = 2_000, readMs = 2_000)
            val cancellable = Http.postJsonCancellable(url, body, connectMs = 2_000, readMs = 2_000)
            assertEquals(blocking, cancellable)
            assertEquals("method=POST;type=application/json;body=$body", cancellable)
            assertEquals("application/json", server.lastContentType)
            assertEquals(Http.USER_AGENT, server.lastUserAgent)
        }
    }

    @Test
    fun postFormMatchesTheBlockingApi() = runBlocking {
        LoopbackServer().use { server ->
            val url = "${server.base}/echo"
            val body = "data=around%3A5000"
            assertEquals(
                Http.postForm(url, body, connectMs = 2_000, readMs = 2_000),
                Http.postFormCancellable(url, body, connectMs = 2_000, readMs = 2_000),
            )
            assertEquals("application/x-www-form-urlencoded", server.lastContentType)
            assertEquals(body, server.lastBody)
        }
    }

    @Test
    fun non2xxAndEmptyOn400MatchTheBlockingApi() {
        LoopbackServer().use { server ->
            val missingBlocking = assertThrows(IllegalStateException::class.java) {
                Http.get("${server.base}/missing", connectMs = 2_000, readMs = 2_000)
            }
            val missing = assertThrows(IllegalStateException::class.java) {
                runBlocking { Http.getCancellable("${server.base}/missing", connectMs = 2_000, readMs = 2_000) }
            }
            assertEquals("HTTP 404", missingBlocking.message)
            assertEquals(missingBlocking.message, missing.message)

            val emptyBlocking = Http.postJson(
                "${server.base}/bad", "{}", connectMs = 2_000, readMs = 2_000, emptyOn400 = true,
            )
            assertEquals(
                emptyBlocking,
                runBlocking {
                    Http.postJsonCancellable(
                        "${server.base}/bad", "{}", connectMs = 2_000, readMs = 2_000, emptyOn400 = true,
                    )
                },
            )
            val badBlocking = assertThrows(IllegalStateException::class.java) {
                Http.postJson("${server.base}/bad", "{}", connectMs = 2_000, readMs = 2_000, emptyOn400 = false)
            }
            val bad = assertThrows(IllegalStateException::class.java) {
                runBlocking {
                    Http.postJsonCancellable(
                        "${server.base}/bad", "{}", connectMs = 2_000, readMs = 2_000, emptyOn400 = false,
                    )
                }
            }
            assertEquals("HTTP 400", badBlocking.message)
            assertEquals(badBlocking.message, bad.message)
        }
    }

    @Test
    fun cancellingASlowResponseIsPromptAndLeavesThePoolUsable() = runBlocking {
        LoopbackServer().use { server ->
            val timeoutMs = 30_000
            val job = launch(Dispatchers.Default) {
                // The server sends headers and one body byte, then stalls. Without cancelling the
                // call itself this can only return when readTimeout (30 s here) expires.
                Http.postFormCancellable("${server.base}/slow", "data=x", connectMs = 2_000, readMs = timeoutMs)
                error("slow response should not complete")
            }
            try {
                assertTrue("slow handler never started", server.slowStarted.await(5, TimeUnit.SECONDS))
                val cancelAt = System.nanoTime()
                job.cancel()
                withTimeout(3_000) { job.join() }
                val cancelMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - cancelAt)
                assertTrue("cancellation took ${cancelMs}ms", cancelMs < 2_000)
                assertTrue(job.isCancelled)

                // A follow-up request must not wait behind the cancelled socket or a leaked worker.
                val followAt = System.nanoTime()
                assertEquals(
                    "hello",
                    withTimeout(3_000) { Http.getCancellable("${server.base}/ok", connectMs = 2_000, readMs = 2_000) },
                )
                val followMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - followAt)
                assertTrue("follow-up took ${followMs}ms", followMs < 2_000)
            } finally {
                server.slowRelease.countDown()
            }
        }
    }

    @Test
    fun saturatedPoolCancelsActiveSocketsAndKeepsFollowUpResponsiveWithoutServerRelease() = runBlocking {
        LoopbackServer(slowLimit = 4).use { server ->
            val jobs = (1..6).map {
                launch(Dispatchers.Default) {
                    Http.postFormCancellable("${server.base}/slow", "data=x", connectMs = 2_000, readMs = 30_000)
                }
            }
            try {
                assertTrue("slow handlers never filled the pool", server.slowStarted.await(5, TimeUnit.SECONDS))
                assertEquals(4, server.slowRequests.get())

                val cancelAt = System.nanoTime()
                jobs.forEach { it.cancel() }
                withTimeout(3_000) { jobs.joinAll() }
                val cancelMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - cancelAt)
                assertTrue("cancellation took ${cancelMs}ms", cancelMs < 2_000)
                assertTrue(jobs.all { it.isCancelled })

                // The server must see the four active sockets close, not just the coroutines finish.
                assertTrue("active sockets did not close", server.slowClientClosed.await(5, TimeUnit.SECONDS))

                // Follow-up succeeds while the slow server is still deliberately holding its latch.
                val followAt = System.nanoTime()
                assertEquals(
                    "hello",
                    withTimeout(3_000) { Http.getCancellable("${server.base}/ok", connectMs = 2_000, readMs = 2_000) },
                )
                val followMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - followAt)
                assertTrue("follow-up took ${followMs}ms", followMs < 2_000)
                assertEquals("cancelled queued calls must not reach the server", 4, server.slowRequests.get())
            } finally {
                server.slowRelease.countDown()
            }
        }
    }

    @Test
    fun cancellingQueuedCallsLeavesNoDeferredSlowWork() = runBlocking {
        LoopbackServer(slowLimit = 4).use { server ->
            val active = (1..4).map {
                launch(Dispatchers.Default) {
                    Http.postFormCancellable("${server.base}/slow", "data=x", connectMs = 2_000, readMs = 30_000)
                }
            }
            try {
                assertTrue(server.slowStarted.await(5, TimeUnit.SECONDS))
                assertEquals(4, server.slowRequests.get())

                // All four dispatcher slots are held, so these two must stay queued.
                val queued = (1..2).map {
                    launch(Dispatchers.Default) {
                        Http.postFormCancellable("${server.base}/slow", "data=x", connectMs = 2_000, readMs = 30_000)
                    }
                }
                Thread.sleep(200)
                assertEquals("queued calls must not connect", 4, server.slowRequests.get())

                queued.forEach { it.cancel() }
                withTimeout(3_000) { queued.joinAll() }
                assertTrue(queued.all { it.isCancelled })

                active.forEach { it.cancel() }
                withTimeout(3_000) { active.joinAll() }
                assertTrue(server.slowClientClosed.await(5, TimeUnit.SECONDS))

                assertEquals(
                    "hello",
                    withTimeout(3_000) { Http.getCancellable("${server.base}/ok", connectMs = 2_000, readMs = 2_000) },
                )
                assertEquals("cancelled queued calls must not run later", 4, server.slowRequests.get())
            } finally {
                server.slowRelease.countDown()
            }
        }
    }

    @Test
    fun completionHandoffPromotesAQueuedRequestWithoutRejection() = runBlocking {
        LoopbackServer(slowLimit = 4).use { server ->
            val active = (1..4).map {
                launch(Dispatchers.Default) {
                    Http.postFormCancellable("${server.base}/slow", "data=x", connectMs = 2_000, readMs = 30_000)
                }
            }
            try {
                assertTrue(server.slowStarted.await(5, TimeUnit.SECONDS))

                // The fifth request is queued behind four running calls. Cancelling those four makes
                // OkHttp promote it while their executor runnables may still be returning; a
                // SynchronousQueue handoff would reject it here.
                var handoff: Result<String>? = null
                val queued = launch(Dispatchers.Default) {
                    handoff = runCatching {
                        Http.getCancellable("${server.base}/ok", connectMs = 2_000, readMs = 5_000)
                    }
                }
                Thread.sleep(200)
                active.forEach { it.cancel() }
                withTimeout(3_000) { active.joinAll() }
                withTimeout(5_000) { queued.join() }

                val outcome = handoff
                assertTrue("queued request failed: ${outcome?.exceptionOrNull()}", outcome?.isSuccess == true)
                assertEquals("hello", outcome?.getOrNull())
                assertTrue(server.slowClientClosed.await(5, TimeUnit.SECONDS))
            } finally {
                server.slowRelease.countDown()
            }
        }
    }

    @Test
    fun cancellationExceptionIsTheOneThatPropagates() = runBlocking {
        LoopbackServer().use { server ->
            var failure: Throwable? = null
            val job = launch(Dispatchers.Default) {
                try {
                    Http.postFormCancellable("${server.base}/slow", "data=x", connectMs = 2_000, readMs = 30_000)
                } catch (e: CancellationException) {
                    failure = e
                    throw e
                }
            }
            try {
                assertTrue(server.slowStarted.await(5, TimeUnit.SECONDS))
                job.cancel()
                withTimeout(3_000) { job.join() }
                assertTrue(job.isCancelled)
                assertTrue(failure is CancellationException)
            } finally {
                server.slowRelease.countDown()
            }
        }
    }
}

/** Minimal HTTP/1.1 loopback server for deterministic cancellation and parity checks. */
private class LoopbackServer(slowLimit: Int = 1) : Closeable {
    private val socket = ServerSocket(0, 50, InetAddress.getLoopbackAddress())
    private val acceptThread = thread(isDaemon = true, name = "loopback-http-accept") { acceptLoop() }

    val base: String = "http://127.0.0.1:${socket.localPort}"
    val slowStarted = CountDownLatch(slowLimit)
    val slowClientClosed = CountDownLatch(slowLimit)
    val slowRequests = AtomicInteger()
    val slowRelease = CountDownLatch(1)

    @Volatile var lastMethod: String? = null
    @Volatile var lastUserAgent: String? = null
    @Volatile var lastContentType: String? = null
    @Volatile var lastBody: String? = null

    private fun acceptLoop() {
        while (!socket.isClosed) {
            val client = try {
                socket.accept()
            } catch (e: Exception) {
                return
            }
            thread(isDaemon = true, name = "loopback-http-client") { handle(client) }
        }
    }

    private fun handle(client: Socket) {
        client.use {
            try {
                val input = client.getInputStream()
                val request = readLine(input) ?: return
                val parts = request.split(' ')
                val method = parts.getOrNull(0).orEmpty()
                val path = parts.getOrNull(1).orEmpty()
                val headers = HashMap<String, String>()
                while (true) {
                    val line = readLine(input)
                    if (line.isNullOrEmpty()) break
                    val colon = line.indexOf(':')
                    if (colon > 0) headers[line.substring(0, colon).trim().lowercase()] = line.substring(colon + 1).trim()
                }
                val body = readBody(input, headers["content-length"]?.toIntOrNull() ?: 0)

                lastMethod = method
                lastUserAgent = headers["user-agent"]
                lastContentType = headers["content-type"]
                lastBody = body

                val output = client.getOutputStream()
                when (path) {
                    "/slow" -> {
                        slowRequests.incrementAndGet()
                        writeHead(output, 200, 64)
                        output.write('x'.code)
                        output.flush()
                        slowStarted.countDown()
                        // Observe the client's own socket close while the response is still unfinished.
                        // This deliberately does not depend on slowRelease, which the tests hold back
                        // to prove the worker/socket, not a server-side release, made the slot reusable.
                        if (awaitClientEof(input)) slowClientClosed.countDown()
                        slowRelease.await(10, TimeUnit.SECONDS)
                    }
                    "/missing" -> respond(output, 404, "nope")
                    "/bad" -> respond(output, 400, "bad")
                    "/echo" -> respond(output, 200, "method=$method;type=${headers["content-type"]};body=$body")
                    else -> respond(output, 200, "hello")
                }
            } catch (e: Exception) {
                // The client disconnecting is the normal path for the cancellation tests.
            }
        }
    }

    private fun awaitClientEof(input: InputStream): Boolean =
        try {
            while (true) {
                if (input.read() < 0) break
            }
            true
        } catch (e: IOException) {
            true
        }

    private fun readBody(input: InputStream, length: Int): String {
        if (length <= 0) return ""
        val bytes = ByteArray(length)
        var read = 0
        while (read < length) {
            val count = input.read(bytes, read, length - read)
            if (count < 0) break
            read += count
        }
        return String(bytes, 0, read, StandardCharsets.UTF_8)
    }

    private fun readLine(input: InputStream): String? {
        val out = ByteArrayOutputStream()
        while (true) {
            val next = input.read()
            if (next < 0) return if (out.size() == 0) null else out.toString("ISO-8859-1")
            if (next == '\n'.code) return out.toString("ISO-8859-1")
            if (next != '\r'.code) out.write(next)
        }
    }

    private fun respond(output: java.io.OutputStream, status: Int, body: String) {
        val bytes = body.toByteArray(StandardCharsets.UTF_8)
        writeHead(output, status, bytes.size)
        output.write(bytes)
        output.flush()
    }

    private fun writeHead(output: java.io.OutputStream, status: Int, length: Int) {
        val reason = if (status in 200..299) "OK" else "Error"
        val head = "HTTP/1.1 $status $reason\r\nContent-Type: text/plain; charset=utf-8\r\n" +
            "Content-Length: $length\r\nConnection: close\r\n\r\n"
        output.write(head.toByteArray(StandardCharsets.US_ASCII))
    }

    override fun close() {
        slowRelease.countDown()
        runCatching { socket.close() }
        acceptThread.join(1_000)
    }
}
