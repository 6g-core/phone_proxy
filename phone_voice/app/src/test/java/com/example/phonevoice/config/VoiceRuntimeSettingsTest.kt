package com.example.phonevoice.config

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class VoiceRuntimeSettingsTest {
    @Test
    fun `uses default LAN host when listen host is blank`() {
        val settings = VoiceRuntimeSettings(listenHost = "   ", forwardUrl = "")

        assertEquals("192.168.1.23", settings.displayHost(defaultHost = "192.168.1.23"))
        assertNull(settings.normalizedListenHost())
    }

    @Test
    fun `uses configured listen host before default LAN host`() {
        val settings = VoiceRuntimeSettings(listenHost = " 10.45.0.10 ", forwardUrl = "")

        assertEquals("10.45.0.10", settings.displayHost(defaultHost = "192.168.1.23"))
        assertEquals("10.45.0.10", settings.normalizedListenHost())
    }

    @Test
    fun `normalizes optional forward url`() {
        val settings = VoiceRuntimeSettings(
            listenHost = "",
            forwardUrl = " http://10.45.0.2:18891/api/v1/exec ",
        )

        assertEquals("http://10.45.0.2:18891/api/v1/exec", settings.normalizedForwardUrl())
    }

    @Test
    fun `treats blank forward url as disabled`() {
        val settings = VoiceRuntimeSettings(listenHost = "", forwardUrl = " ")

        assertFalse(settings.forwardingEnabled())
        assertNull(settings.normalizedForwardUrl())
    }
}
