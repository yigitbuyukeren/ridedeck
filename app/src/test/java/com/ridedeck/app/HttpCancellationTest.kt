package com.ridedeck.app

import com.ridedeck.app.data.Http
import com.ridedeck.app.data.safely
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
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
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

    @Test
    fun safelyRethrowsCancellationInsteadOfReturningFailure() {
        val cancelled = CancellationException("cancelled")
        val thrown = assertThrows(CancellationException::class.java) {
            safely("test") { throw cancelled }
        }
        assertSame(cancelled, thrown)
    }

    @Test
    fun callerTimeoutCancelsAStalledResponseAndClosesItsSocket() {
        LoopbackServer().use { server ->
            val startedAt = System.nanoTime()
            assertThrows(TimeoutCancellationException::class.java) {
                runBlocking {
                    // The server accepts the request and never writes a status line, so only the
                    // caller's timeout can end the call.
                    withTimeout(2_000) {
                        Http.getCancellable("${server.base}/silent", connectMs = 2_000, readMs = 30_000)
                    }
                }
            }
            val elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt)
            assertTrue("timeout took ${elapsedMs}ms", elapsedMs < 6_000)
            assertTrue("stalled request never started", server.silentStarted.await(5, TimeUnit.SECONDS))
            assertTrue("timed-out socket stayed open", server.silentClientClosed.await(5, TimeUnit.SECONDS))
            runBlocking {
                assertEquals(
                    "hello",
                    withTimeout(3_000) { Http.getCancellable("${server.base}/ok", connectMs = 2_000, readMs = 2_000) },
                )
            }
        }
    }

    @Test
    fun readTimeoutFailsWithIOExceptionAndLeavesThePoolUsable() {
        LoopbackServer().use { server ->
            // /slow sends the status line and one body byte, then stalls, so the timeout is a read
            // timeout rather than a connect failure.
            assertThrows(IOException::class.java) {
                runBlocking {
                    Http.postFormCancellable("${server.base}/slow", "data=x", connectMs = 2_000, readMs = 1_000)
                }
            }
            assertTrue("stalled request never started", server.slowStarted.await(5, TimeUnit.SECONDS))
            assertTrue("timed-out socket stayed open", server.slowClientClosed.await(5, TimeUnit.SECONDS))
            runBlocking {
                assertEquals(
                    "hello",
                    withTimeout(3_000) { Http.getCancellable("${server.base}/ok", connectMs = 2_000, readMs = 2_000) },
                )
            }
        }
    }

    @Test
    fun cancellingALargeUploadClosesItsSocketAndLeavesThePoolUsable() = runBlocking {
        LoopbackServer().use { server ->
            // The server reads only the headers and never consumes this body. Cancelling while the
            // request is still being written must abort the socket, not let the upload continue.
            val body = "x".repeat(8 * 1024 * 1024)
            val job = launch(Dispatchers.Default) {
                Http.postFormCancellable("${server.base}/unread", body, connectMs = 2_000, readMs = 30_000)
                error("uploaded request should not complete")
            }
            try {
                assertTrue("upload request never started", server.uploadStarted.await(5, TimeUnit.SECONDS))
                Thread.sleep(200)
                assertTrue("upload completed before cancellation", job.isActive)
                val cancelAt = System.nanoTime()
                job.cancel()
                withTimeout(3_000) { job.join() }
                val cancelMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - cancelAt)
                assertTrue("cancellation took ${cancelMs}ms", cancelMs < 2_000)
                assertTrue(job.isCancelled)

                server.uploadProbe.countDown()
                assertTrue("uploaded socket stayed open", server.uploadClosed.await(5, TimeUnit.SECONDS))
                assertEquals(
                    "hello",
                    withTimeout(3_000) { Http.getCancellable("${server.base}/ok", connectMs = 2_000, readMs = 2_000) },
                )
            } finally {
                server.uploadProbe.countDown()
                job.cancel()
            }
        }
    }

    @Test
    fun emptyOn400ReturnsWithoutReadingTheUnfinishedBody() {
        LoopbackServer().use { server ->
            val startedAt = System.nanoTime()
            val reply = runBlocking {
                // /slow400 announces 64 body bytes but sends one; reading them would block for the
                // full read timeout. emptyOn400 must dispose the response immediately.
                withTimeout(3_000) {
                    Http.postJsonCancellable(
                        "${server.base}/slow400", "{}", connectMs = 2_000, readMs = 30_000, emptyOn400 = true,
                    )
                }
            }
            val elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt)
            assertEquals("{}", reply)
            assertTrue("400 reply took ${elapsedMs}ms", elapsedMs < 2_000)
            assertTrue("400 response never started", server.early400Started.await(5, TimeUnit.SECONDS))
            assertTrue("unread 400 body kept the socket open", server.early400Closed.await(5, TimeUnit.SECONDS))
            runBlocking {
                assertEquals(
                    "hello",
                    withTimeout(3_000) { Http.getCancellable("${server.base}/ok", connectMs = 2_000, readMs = 2_000) },
                )
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
    val silentStarted = CountDownLatch(1)
    val silentClientClosed = CountDownLatch(1)
    val uploadStarted = CountDownLatch(1)
    val uploadProbe = CountDownLatch(1)
    val uploadClosed = CountDownLatch(1)
    val early400Started = CountDownLatch(1)
    val early400Closed = CountDownLatch(1)

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
                // /unread deliberately leaves the body in the socket to apply write backpressure.
                val body = if (path == "/unread") "" else readBody(input, headers["content-length"]?.toIntOrNull() ?: 0)

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
                    "/silent" -> {
                        // Accept the request but never send a status line; only cancellation or the
                        // caller's timeout can finish it.
                        silentStarted.countDown()
                        if (awaitClientEof(input)) silentClientClosed.countDown()
                        slowRelease.await(10, TimeUnit.SECONDS)
                    }
                    "/unread" -> {
                        uploadStarted.countDown()
                        // Start draining only after the test has cancelled the upload. The client
                        // must close the socket instead of finishing the rest of the body.
                        uploadProbe.await(10, TimeUnit.SECONDS)
                        if (awaitClientEof(input)) uploadClosed.countDown()
                    }
                    "/slow400" -> {
                        // A complete 400 has 64 body bytes, but only one is sent before the stall.
                        writeHead(output, 400, 64)
                        output.write('x'.code)
                        output.flush()
                        early400Started.countDown()
                        if (awaitClientEof(input)) early400Closed.countDown()
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
        uploadProbe.countDown()
        runCatching { socket.close() }
        acceptThread.join(1_000)
    }
}
