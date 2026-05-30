package com.example.phonevoice.http

import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ForwardPostClientTest {
    @Test
    fun `posts empty json body with application content type`() {
        val latch = CountDownLatch(1)
        var method = ""
        var contentType = ""
        var body = ""
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/api/v1/exec") { exchange ->
            method = exchange.requestMethod
            contentType = exchange.requestHeaders.getFirst("Content-Type")
            body = exchange.requestBody.reader().readText()
            exchange.sendResponseHeaders(204, -1)
            exchange.close()
            latch.countDown()
        }

        try {
            server.start()
            val port = server.address.port
            val client = ForwardPostClient()

            val result = client.forward("http://127.0.0.1:$port/api/v1/exec")

            assertTrue(result.success)
            assertTrue(latch.await(2, TimeUnit.SECONDS))
            assertEquals("POST", method)
            assertEquals("application", contentType)
            assertEquals("{}", body)
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun `reports non 2xx response as forwarding failure`() {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/api/v1/exec") { exchange ->
            exchange.sendResponseHeaders(500, -1)
            exchange.close()
        }

        try {
            server.start()
            val port = server.address.port
            val client = ForwardPostClient()

            val result = client.forward("http://127.0.0.1:$port/api/v1/exec")

            assertEquals(false, result.success)
            assertEquals(500, result.statusCode)
        } finally {
            server.stop(0)
        }
    }
}
