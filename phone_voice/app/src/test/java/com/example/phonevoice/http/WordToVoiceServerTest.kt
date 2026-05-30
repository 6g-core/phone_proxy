package com.example.phonevoice.http

import fi.iki.elonen.NanoHTTPD
import java.io.ByteArrayOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WordToVoiceServerTest {
    @Test
    fun `maps a successful handler result to a 200 response`() {
        val handler = WordToVoiceRequestHandler(
            dispatcher = object : SpeechDispatcher {
                override fun isReady(): Boolean = true
                override fun speak(text: String): Boolean = true
            },
        )
        val server = WordToVoiceServer(handler)

        val response = server.serveBody(
            method = "POST",
            path = "/api/v1/word2voice",
            body = """{"text":"门口取快递","arm_body":{"target":"yellow bottle"}}""",
        )

        assertEquals(NanoHTTPD.Response.Status.OK, response.status)

        val output = ByteArrayOutputStream()
        response.data.transferTo(output)
        assertTrue(output.toString().contains("\"success\":true"))
    }

    @Test
    fun `maps a wrong method to a 405 response`() {
        val handler = WordToVoiceRequestHandler(
            dispatcher = object : SpeechDispatcher {
                override fun isReady(): Boolean = true
                override fun speak(text: String): Boolean = true
            },
        )
        val server = WordToVoiceServer(handler)

        val response = server.serveBody(
            method = "GET",
            path = "/api/v1/word2voice",
            body = null,
        )

        assertEquals(NanoHTTPD.Response.Status.METHOD_NOT_ALLOWED, response.status)
    }

    @Test
    fun `defaults json requests without charset to utf 8`() {
        val server = WordToVoiceServer(
            handler = WordToVoiceRequestHandler(
                dispatcher = object : SpeechDispatcher {
                    override fun isReady(): Boolean = true
                    override fun speak(text: String): Boolean = true
                },
            ),
        )

        assertEquals(
            "application/json; charset=UTF-8",
            server.normalizeContentTypeHeader("application/json"),
        )
    }

    @Test
    fun `keeps explicit charset intact`() {
        val server = WordToVoiceServer(
            handler = WordToVoiceRequestHandler(
                dispatcher = object : SpeechDispatcher {
                    override fun isReady(): Boolean = true
                    override fun speak(text: String): Boolean = true
                },
            ),
        )

        assertEquals(
            "application/json; charset=UTF-8",
            server.normalizeContentTypeHeader("application/json; charset=UTF-8"),
        )
    }

    @Test
    fun `stores configured listen address`() {
        val server = WordToVoiceServer(
            handler = WordToVoiceRequestHandler(
                dispatcher = object : SpeechDispatcher {
                    override fun isReady(): Boolean = true
                    override fun speak(text: String): Boolean = true
                },
            ),
            listenHost = "10.45.0.10",
            listenPort = 18080,
        )

        assertEquals("10.45.0.10", server.listenHost)
        assertEquals(18080, server.listenPort)
    }
}
