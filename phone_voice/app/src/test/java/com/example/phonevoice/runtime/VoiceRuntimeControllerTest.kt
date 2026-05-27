package com.example.phonevoice.runtime

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
            serverFactory = { FakeEmbeddedServer() },
        )

        controller.start()

        assertEquals(VoiceServiceStatus.RUNNING, store.state.value.status)
        assertEquals("http://192.168.1.23:8080/api/v1/word2voice", store.state.value.lanUrl)
        assertTrue(store.state.value.ttsReady)
    }

    @Test
    fun `start transitions to error when server startup throws`() {
        val store = VoiceServiceStateStore()
        val controller = VoiceRuntimeController(
            stateStore = store,
            dispatcher = FakeSpeechDispatcher(ready = true),
            lanIpProvider = { "192.168.1.23" },
            serverFactory = { ThrowingEmbeddedServer() },
        )

        controller.start()

        assertEquals(VoiceServiceStatus.ERROR, store.state.value.status)
        assertTrue(store.state.value.lastError.contains("Failed to start HTTP server"))
    }

    @Test
    fun `stop resets the service state`() {
        val store = VoiceServiceStateStore()
        val server = FakeEmbeddedServer()
        val controller = VoiceRuntimeController(
            stateStore = store,
            dispatcher = FakeSpeechDispatcher(ready = false),
            lanIpProvider = { "192.168.1.23" },
            serverFactory = { server },
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
