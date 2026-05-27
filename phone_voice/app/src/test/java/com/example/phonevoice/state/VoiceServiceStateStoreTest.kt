package com.example.phonevoice.state

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceServiceStateStoreTest {
    @Test
    fun `starts in a stopped state`() {
        val store = VoiceServiceStateStore()

        assertEquals(VoiceServiceStatus.STOPPED, store.state.value.status)
        assertFalse(store.state.value.ttsReady)
    }

    @Test
    fun `updates to running state with last text`() {
        val store = VoiceServiceStateStore()

        store.setStarting()
        store.setRunning(
            lanUrl = "http://192.168.1.23:8080/api/v1/word2voice",
            ttsReady = true,
        )
        store.setLastText("你好，请到门口取快递")

        assertEquals(VoiceServiceStatus.RUNNING, store.state.value.status)
        assertTrue(store.state.value.ttsReady)
        assertEquals("你好，请到门口取快递", store.state.value.lastText)
    }
}
