package com.example.phonevoice.http

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WordToVoiceRequestHandlerTest {
    @Test
    fun `accepts a valid post request and forwards the text`() {
        val dispatcher = FakeSpeechDispatcher(ready = true)
        val handler = WordToVoiceRequestHandler(dispatcher)

        val result = handler.handle(
            method = "POST",
            path = "/api/v1/word2voice",
            body = """{"text":"你好，请到门口取快递"}""",
        )

        assertEquals(200, result.statusCode)
        assertEquals(listOf("你好，请到门口取快递"), dispatcher.spoken)
        assertTrue(JSONObject(result.body).getBoolean("success"))
    }

    @Test
    fun `rejects a blank text payload`() {
        val dispatcher = FakeSpeechDispatcher(ready = true)
        val handler = WordToVoiceRequestHandler(dispatcher)

        val result = handler.handle(
            method = "POST",
            path = "/api/v1/word2voice",
            body = """{"text":"   "}""",
        )

        assertEquals(400, result.statusCode)
        assertEquals("text must not be blank", JSONObject(result.body).getString("message"))
    }

    @Test
    fun `returns service unavailable when speech dispatcher is not ready`() {
        val dispatcher = FakeSpeechDispatcher(ready = false)
        val handler = WordToVoiceRequestHandler(dispatcher)

        val result = handler.handle(
            method = "POST",
            path = "/api/v1/word2voice",
            body = """{"text":"门口取快递"}""",
        )

        assertEquals(503, result.statusCode)
        assertEquals("speech engine not ready", JSONObject(result.body).getString("message"))
    }

    @Test
    fun `rejects unsupported methods`() {
        val dispatcher = FakeSpeechDispatcher(ready = true)
        val handler = WordToVoiceRequestHandler(dispatcher)

        val result = handler.handle(
            method = "GET",
            path = "/api/v1/word2voice",
            body = null,
        )

        assertEquals(405, result.statusCode)
    }

    @Test
    fun `rejects unsupported paths`() {
        val dispatcher = FakeSpeechDispatcher(ready = true)
        val handler = WordToVoiceRequestHandler(dispatcher)

        val result = handler.handle(
            method = "POST",
            path = "/wrong",
            body = """{"text":"门口取快递"}""",
        )

        assertEquals(404, result.statusCode)
    }

    private class FakeSpeechDispatcher(
        private val ready: Boolean,
    ) : SpeechDispatcher {
        val spoken = mutableListOf<String>()

        override fun isReady(): Boolean = ready

        override fun speak(text: String): Boolean {
            spoken += text
            return true
        }
    }
}
