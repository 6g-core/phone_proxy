package com.example.phonevoice.http

import com.example.phonevoice.config.VoiceRuntimeSettings
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
        assertTrue(JSONObject(result.body).getJSONObject("speech").getBoolean("success"))
        assertTrue(JSONObject(result.body).getJSONObject("forward").getBoolean("skipped"))
    }

    @Test
    fun `skips forwarding when forward url is blank`() {
        val dispatcher = FakeSpeechDispatcher(ready = true)
        val forwarder = FakeForwarder()
        val handler = WordToVoiceRequestHandler(
            dispatcher = dispatcher,
            settingsProvider = { VoiceRuntimeSettings(forwardUrl = " ") },
            forwarder = forwarder,
        )

        val result = handler.handle(
            method = "POST",
            path = "/api/v1/word2voice",
            body = """{"text":"门口取快递"}""",
        )

        val payload = JSONObject(result.body)
        assertEquals(200, result.statusCode)
        assertTrue(payload.getBoolean("success"))
        assertTrue(payload.getJSONObject("speech").getBoolean("success"))
        assertTrue(payload.getJSONObject("forward").getBoolean("skipped"))
        assertTrue(forwarder.urls.isEmpty())
    }

    @Test
    fun `returns failure when configured forwarding fails`() {
        val dispatcher = FakeSpeechDispatcher(ready = true)
        val forwarder = FakeForwarder(
            result = ForwardResult(
                success = false,
                message = "HTTP 500 from forwardUrl",
                statusCode = 500,
            ),
        )
        val handler = WordToVoiceRequestHandler(
            dispatcher = dispatcher,
            settingsProvider = {
                VoiceRuntimeSettings(forwardUrl = "http://10.45.0.2:18891/api/v1/exec")
            },
            forwarder = forwarder,
        )

        val result = handler.handle(
            method = "POST",
            path = "/api/v1/word2voice",
            body = """{"text":"门口取快递"}""",
        )

        val payload = JSONObject(result.body)
        assertEquals(500, result.statusCode)
        assertFalse(payload.getBoolean("success"))
        assertEquals("forward failed", payload.getString("message"))
        assertTrue(payload.getJSONObject("speech").getBoolean("success"))
        assertFalse(payload.getJSONObject("forward").getBoolean("success"))
        assertEquals(
            listOf("http://10.45.0.2:18891/api/v1/exec"),
            forwarder.urls,
        )
    }

    @Test
    fun `returns failure when speech dispatch fails`() {
        val dispatcher = FakeSpeechDispatcher(ready = true, speakResult = false)
        val forwarder = FakeForwarder()
        val handler = WordToVoiceRequestHandler(
            dispatcher = dispatcher,
            settingsProvider = {
                VoiceRuntimeSettings(forwardUrl = "http://10.45.0.2:18891/api/v1/exec")
            },
            forwarder = forwarder,
        )

        val result = handler.handle(
            method = "POST",
            path = "/api/v1/word2voice",
            body = """{"text":"门口取快递"}""",
        )

        val payload = JSONObject(result.body)
        assertEquals(500, result.statusCode)
        assertFalse(payload.getBoolean("success"))
        assertEquals("speech failed", payload.getString("message"))
        assertFalse(payload.getJSONObject("speech").getBoolean("success"))
        assertTrue(payload.getJSONObject("forward").getBoolean("skipped"))
        assertTrue(forwarder.urls.isEmpty())
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
        private val speakResult: Boolean = true,
    ) : SpeechDispatcher {
        val spoken = mutableListOf<String>()

        override fun isReady(): Boolean = ready

        override fun speak(text: String): Boolean {
            spoken += text
            return speakResult
        }
    }

    private class FakeForwarder(
        private val result: ForwardResult = ForwardResult(success = true, message = "HTTP 204"),
    ) : Forwarder {
        val urls = mutableListOf<String>()

        override fun forward(url: String): ForwardResult {
            urls += url
            return result
        }
    }
}
