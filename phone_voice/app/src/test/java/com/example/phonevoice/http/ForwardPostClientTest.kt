package com.example.phonevoice.http

import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.ServerSocket
import java.net.SocketTimeoutException
import java.nio.charset.StandardCharsets
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ForwardPostClientTest {
    @Test
    fun `posts empty json body with application content type`() {
        val methodRef = AtomicReference("")
        val contentTypeRef = AtomicReference("")
        val bodyRef = AtomicReference("")
        val server = TestHttpServer { request, output ->
            methodRef.set(request.method)
            contentTypeRef.set(request.headers["content-type"].orEmpty())
            bodyRef.set(request.body)
            writeResponse(output, statusCode = 204)
        }

        try {
            val client = ForwardPostClient()

            val result = client.forward(
                url = "http://127.0.0.1:${server.port}/api/v1/exec",
                timeoutMillis = 3000,
            )

            assertTrue(result.success)
            assertTrue(server.awaitRequest())
            assertEquals("POST", methodRef.get())
            assertEquals("application", contentTypeRef.get())
            assertEquals("{}", bodyRef.get())
        } finally {
            server.close()
        }
    }

    @Test
    fun `reports non 2xx response as forwarding failure`() {
        val server = TestHttpServer { _, output ->
            writeResponse(output, statusCode = 500)
        }

        try {
            val client = ForwardPostClient()

            val result = client.forward(
                url = "http://127.0.0.1:${server.port}/api/v1/exec",
                timeoutMillis = 3000,
            )

            assertFalse(result.success)
            assertEquals(500, result.statusCode)
        } finally {
            server.close()
        }
    }

    @Test
    fun `uses configured timeout for slow forwarding responses`() {
        val server = TestHttpServer { _, output ->
            Thread.sleep(500)
            writeResponse(output, statusCode = 204)
        }

        try {
            val client = ForwardPostClient()

            val result = client.forward(
                url = "http://127.0.0.1:${server.port}/api/v1/exec",
                timeoutMillis = 100,
            )

            assertFalse(result.success)
        } finally {
            server.close()
        }
    }

    private data class CapturedRequest(
        val method: String,
        val headers: Map<String, String>,
        val body: String,
    )

    private class TestHttpServer(
        private val handler: (CapturedRequest, OutputStream) -> Unit,
    ) : AutoCloseable {
        private val latch = CountDownLatch(1)
        private val serverSocket = ServerSocket(0)
        private val worker = Thread {
            try {
                serverSocket.soTimeout = 5000
                serverSocket.accept().use { socket ->
                    socket.soTimeout = 5000
                    val request = readRequest(socket)
                    handler(request, socket.getOutputStream())
                    socket.getOutputStream().flush()
                    latch.countDown()
                }
            } catch (_: SocketTimeoutException) {
            }
        }.apply {
            isDaemon = true
            start()
        }

        val port: Int = serverSocket.localPort

        fun awaitRequest(timeoutSeconds: Long = 2): Boolean {
            return latch.await(timeoutSeconds, TimeUnit.SECONDS)
        }

        override fun close() {
            runCatching { serverSocket.close() }
            runCatching { worker.join(1000) }
        }

        private fun readRequest(socket: java.net.Socket): CapturedRequest {
            val reader = BufferedReader(InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8))
            val requestLine = reader.readLine().orEmpty()
            val method = requestLine.substringBefore(' ', "")
            val headers = linkedMapOf<String, String>()
            while (true) {
                val line = reader.readLine() ?: break
                if (line.isBlank()) {
                    break
                }
                val separator = line.indexOf(':')
                if (separator > 0) {
                    val key = line.substring(0, separator).trim().lowercase()
                    val value = line.substring(separator + 1).trim()
                    headers[key] = value
                }
            }

            val contentLength = headers["content-length"]?.toIntOrNull() ?: 0
            val body = if (contentLength > 0) {
                val buffer = CharArray(contentLength)
                var total = 0
                while (total < contentLength) {
                    val read = reader.read(buffer, total, contentLength - total)
                    if (read < 0) {
                        break
                    }
                    total += read
                }
                String(buffer, 0, total)
            } else {
                ""
            }

            return CapturedRequest(
                method = method,
                headers = headers,
                body = body,
            )
        }
    }

    private fun writeResponse(
        output: OutputStream,
        statusCode: Int,
        body: String = "",
    ) {
        val reason = when (statusCode) {
            204 -> "No Content"
            500 -> "Internal Server Error"
            else -> "OK"
        }
        val payload = body.toByteArray(StandardCharsets.UTF_8)
        val header = buildString {
            append("HTTP/1.1 $statusCode $reason\r\n")
            append("Connection: close\r\n")
            append("Content-Length: ${payload.size}\r\n")
            append("\r\n")
        }.toByteArray(StandardCharsets.UTF_8)
        output.write(header)
        if (payload.isNotEmpty()) {
            output.write(payload)
        }
    }
}
