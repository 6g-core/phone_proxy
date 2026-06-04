package com.example.phonevoice.runtime

import com.example.phonevoice.config.VoiceRuntimeSettings
import com.example.phonevoice.http.SpeechDispatcher
import com.example.phonevoice.state.VoiceServiceStateStore
import com.example.phonevoice.state.VoiceServiceStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceRuntimeControllerTest {
    @Test
    fun `start sets the service to running and publishes the LAN url`() {
        val store = VoiceServiceStateStore()
        val controller = VoiceRuntimeController(
            stateStore = store,
            dispatcher = FakeSpeechDispatcher(ready = true),
            lanIpProvider = { "192.168.1.23" },
            serverFactory = { _ -> FakeEmbeddedServer() },
        )

        controller.start()

        assertEquals(VoiceServiceStatus.RUNNING, store.state.value.status)
        assertEquals("http://192.168.1.23:8080/api/v1/word2voice", store.state.value.lanUrl)
        assertEquals(
            "http://192.168.1.23:8080/api/v1/word2voice_forward",
            store.state.value.forwardLanUrl,
        )
        assertTrue(store.state.value.ttsReady)
    }

    @Test
    fun `start transitions to error when server startup throws`() {
        val store = VoiceServiceStateStore()
        val controller = VoiceRuntimeController(
            stateStore = store,
            dispatcher = FakeSpeechDispatcher(ready = true),
            lanIpProvider = { "192.168.1.23" },
            serverFactory = { _ -> ThrowingEmbeddedServer() },
        )

        controller.start()

        assertEquals(VoiceServiceStatus.ERROR, store.state.value.status)
        assertTrue(store.state.value.lastError.contains("Failed to start HTTP server"))
    }

    @Test
    fun `configured listen url overrides the LAN url display`() {
        val store = VoiceServiceStateStore()
        var settingsSeenByServer: VoiceRuntimeSettings? = null
        val controller = VoiceRuntimeController(
            stateStore = store,
            dispatcher = FakeSpeechDispatcher(ready = true),
            lanIpProvider = { "192.168.1.23" },
            settingsProvider = {
                VoiceRuntimeSettings(
                    listenUrl = "http://10.45.0.10:18080/custom/word2voice",
                    speechForwardUrl = "http://10.45.0.10:18080/custom/word2voice_forward",
                    forwardUrl = "http://10.45.0.2:18891/api/v1/exec",
                )
            },
            serverFactory = { settings ->
                settingsSeenByServer = settings
                FakeEmbeddedServer()
            },
        )

        controller.start()

        assertEquals("http://10.45.0.10:18080/custom/word2voice", store.state.value.lanUrl)
        assertEquals(
            "http://10.45.0.10:18080/custom/word2voice_forward",
            store.state.value.forwardLanUrl,
        )
        assertEquals("http://10.45.0.10:18080/custom/word2voice", settingsSeenByServer?.listenUrl)
        assertEquals(
            "http://10.45.0.10:18080/custom/word2voice_forward",
            settingsSeenByServer?.speechForwardUrl,
        )
        assertEquals(
            "http://10.45.0.2:18891/api/v1/exec",
            settingsSeenByServer?.forwardUrl,
        )
    }

    @Test
    fun `stop resets the service state`() {
        val store = VoiceServiceStateStore()
        val server = FakeEmbeddedServer()
        val controller = VoiceRuntimeController(
            stateStore = store,
            dispatcher = FakeSpeechDispatcher(ready = false),
            lanIpProvider = { "192.168.1.23" },
            serverFactory = { _ -> server },
        )

        controller.start()
        controller.stop()

        assertEquals(VoiceServiceStatus.STOPPED, store.state.value.status)
        assertEquals(1, server.stopCount)
    }

    private class FakeSpeechDispatcher(
        private val ready: Boolean,
    ) : SpeechDispatcher {
        override fun isReady(): Boolean = ready
        override fun speak(text: String): Boolean = true
    }

    private class FakeEmbeddedServer : EmbeddedServer {
        var stopCount = 0

        override fun start() = Unit

        override fun stop() {
            stopCount += 1
        }
    }

    private class ThrowingEmbeddedServer : EmbeddedServer {
        override fun start() {
            error("boom")
        }

        override fun stop() = Unit
    }
}
