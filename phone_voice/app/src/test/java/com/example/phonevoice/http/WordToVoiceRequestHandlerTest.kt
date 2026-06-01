package com.example.phonevoice.http

import com.example.phonevoice.config.VoiceEndpointConfig
import com.example.phonevoice.config.VoiceRuntimeSettings
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class WordToVoiceRequestHandlerTest {
    @Test
    fun `accepts a valid post request and forwards arm body`() {
        val dispatcher = FakeSpeechDispatcher(ready = true)
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
            body = """{"text":"你好，请到门口取快递","arm_body":{"target":"yellow bottle"}}""",
        )

        assertEquals(200, result.statusCode)
        assertEquals(listOf("你好，请到门口取快递"), dispatcher.spoken)
        assertEquals(listOf("""{"target":"yellow bottle"}"""), forwarder.bodies)
        assertTrue(JSONObject(result.body).getBoolean("success"))
        assertTrue(JSONObject(result.body).getJSONObject("speech").getBoolean("success"))
        assertTrue(JSONObject(result.body).getJSONObject("forward").getBoolean("success"))
    }

    @Test
    fun `reports accepted text callback after successful speech`() {
        val dispatcher = FakeSpeechDispatcher(ready = true)
        val acceptedTexts = mutableListOf<String>()
        val handler = WordToVoiceRequestHandler(
            dispatcher = dispatcher,
            onAcceptedText = { acceptedTexts += it },
        )

        val result = handler.handle(
            method = "POST",
            path = "/api/v1/word2voice",
            body = """{"text":"请到门口取快递","arm_body":{"target":"yellow bottle"}}""",
        )

        assertEquals(200, result.statusCode)
        assertEquals(listOf("请到门口取快递"), acceptedTexts)
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
            body = """{"text":"门口取快递","arm_body":{"target":"yellow bottle"}}""",
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
            body = """{"text":"门口取快递","arm_body":{"target":"yellow bottle"}}""",
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
    fun `uses configured forwarding timeout`() {
        val dispatcher = FakeSpeechDispatcher(ready = true)
        val forwarder = FakeForwarder()
        val handler = WordToVoiceRequestHandler(
            dispatcher = dispatcher,
            settingsProvider = {
                VoiceRuntimeSettings(
                    forwardUrl = "http://10.45.0.2:18891/api/v1/exec",
                    forwardTimeoutMillis = 10000,
                )
            },
            forwarder = forwarder,
        )

        handler.handle(
            method = "POST",
            path = "/api/v1/word2voice",
            body = """{"text":"门口取快递","arm_body":{"target":"yellow bottle"}}""",
        )

        assertEquals(listOf(10000), forwarder.timeouts)
    }

    @Test
    fun `logs request parsing speech and forwarding timings`() {
        val dispatcher = FakeSpeechDispatcher(ready = true)
        val forwarder = FakeForwarder()
        val logs = Collections.synchronizedList(mutableListOf<String>())
        val handler = WordToVoiceRequestHandler(
            dispatcher = dispatcher,
            settingsProvider = {
                VoiceRuntimeSettings(forwardUrl = "http://10.45.0.2:18891/api/v1/exec")
            },
            forwarder = forwarder,
            logger = { logs += it },
        )

        val result = handler.handle(
            method = "POST",
            path = "/api/v1/word2voice",
            body = """{"text":"门口取快递","arm_body":{"target":"yellow bottle"}}""",
        )

        val joinedLogs = logs.joinToString("\n")
        assertEquals(200, result.statusCode)
        assertTrue(joinedLogs.contains("received HTTP request"))
        assertTrue(joinedLogs.contains("parsed request"))
        assertTrue(joinedLogs.contains("starting speech"))
        assertTrue(joinedLogs.contains("speech result"))
        assertTrue(joinedLogs.contains("starting forward"))
        assertTrue(joinedLogs.contains("forward result"))
        assertTrue(joinedLogs.contains("elapsedMs="))
    }

    @Test
    fun `starts forwarding before speech finishes`() {
        val speechStarted = CountDownLatch(1)
        val releaseSpeech = CountDownLatch(1)
        val forwardStarted = CountDownLatch(1)
        val dispatcher = BlockingSpeechDispatcher(
            speechStarted = speechStarted,
            releaseSpeech = releaseSpeech,
        )
        val forwarder = FakeForwarder(onForward = { forwardStarted.countDown() })
        val forwardExecutor = Executors.newSingleThreadExecutor()
        val requestExecutor = Executors.newSingleThreadExecutor()
        val handler = WordToVoiceRequestHandler(
            dispatcher = dispatcher,
            settingsProvider = {
                VoiceRuntimeSettings(forwardUrl = "http://10.45.0.2:18891/api/v1/exec")
            },
            forwarder = forwarder,
            forwardExecutor = forwardExecutor,
        )

        val resultFuture = requestExecutor.submit<HttpResult> {
            handler.handle(
                method = "POST",
                path = "/api/v1/word2voice",
                body = """{"text":"门口取快递","arm_body":{"target":"yellow bottle"}}""",
            )
        }

        try {
            assertTrue(speechStarted.await(1, TimeUnit.SECONDS))
            assertTrue(forwardStarted.await(1, TimeUnit.SECONDS))
            assertFalse(resultFuture.isDone)

            releaseSpeech.countDown()
            assertEquals(200, resultFuture.get(2, TimeUnit.SECONDS).statusCode)
        } finally {
            releaseSpeech.countDown()
            requestExecutor.shutdownNow()
            forwardExecutor.shutdownNow()
        }
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
            body = """{"text":"门口取快递","arm_body":{"target":"yellow bottle"}}""",
        )

        val payload = JSONObject(result.body)
        assertEquals(500, result.statusCode)
        assertFalse(payload.getBoolean("success"))
        assertEquals("speech failed", payload.getString("message"))
        assertFalse(payload.getJSONObject("speech").getBoolean("success"))
        assertFalse(payload.getJSONObject("forward").getBoolean("skipped"))
        assertEquals(listOf("http://10.45.0.2:18891/api/v1/exec"), forwarder.urls)
    }

    @Test
    fun `rejects invalid json body`() {
        val dispatcher = FakeSpeechDispatcher(ready = true)
        val handler = WordToVoiceRequestHandler(dispatcher)

        val result = handler.handle(
            method = "POST",
            path = "/api/v1/word2voice",
            body = """{"text":"missing quote}""",
        )

        assertEquals(400, result.statusCode)
        assertEquals("invalid json body", JSONObject(result.body).getString("message"))
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
    fun `rejects text larger than the endpoint limit`() {
        val dispatcher = FakeSpeechDispatcher(ready = true)
        val handler = WordToVoiceRequestHandler(dispatcher)
        val tooLongText = "a".repeat(VoiceEndpointConfig.MAX_TEXT_LENGTH + 1)

        val result = handler.handle(
            method = "POST",
            path = "/api/v1/word2voice",
            body = JSONObject()
                .put("text", tooLongText)
                .put("arm_body", JSONObject().put("target", "yellow bottle"))
                .toString(),
        )

        assertEquals(400, result.statusCode)
        assertEquals("text exceeds max length", JSONObject(result.body).getString("message"))
    }

    @Test
    fun `returns service unavailable when speech dispatcher is not ready`() {
        val dispatcher = FakeSpeechDispatcher(ready = false)
        val handler = WordToVoiceRequestHandler(dispatcher)

        val result = handler.handle(
            method = "POST",
            path = "/api/v1/word2voice",
            body = """{"text":"门口取快递","arm_body":{"target":"yellow bottle"}}""",
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
            body = """{"text":"门口取快递","arm_body":{"target":"yellow bottle"}}""",
        )

        assertEquals(404, result.statusCode)
    }

    @Test
    fun `accepts the configured listen path`() {
        val dispatcher = FakeSpeechDispatcher(ready = true)
        val handler = WordToVoiceRequestHandler(
            dispatcher = dispatcher,
            requestPath = "/custom/word2voice",
        )

        val result = handler.handle(
            method = "POST",
            path = "/custom/word2voice",
            body = """{"text":"门口取快递","arm_body":{"target":"yellow bottle"}}""",
        )

        assertEquals(200, result.statusCode)
        assertEquals(listOf("门口取快递"), dispatcher.spoken)
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

    private class BlockingSpeechDispatcher(
        private val speechStarted: CountDownLatch,
        private val releaseSpeech: CountDownLatch,
    ) : SpeechDispatcher {
        override fun isReady(): Boolean = true

        override fun speak(text: String): Boolean {
            speechStarted.countDown()
            releaseSpeech.await(2, TimeUnit.SECONDS)
            return true
        }
    }

    private class FakeForwarder(
        private val result: ForwardResult = ForwardResult(success = true, message = "HTTP 204"),
        private val onForward: () -> Unit = {},
    ) : Forwarder {
        val urls = mutableListOf<String>()

        val timeouts = mutableListOf<Int>()

        val bodies = mutableListOf<String>()

        override fun forward(url: String, timeoutMillis: Int, body: String): ForwardResult {
            onForward()
            urls += url
            timeouts += timeoutMillis
            bodies += body
            return result
        }
    }
}
