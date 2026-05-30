package com.example.phonevoice.config

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class VoiceRuntimeSettingsTest {
    @Test
    fun `uses default LAN url when listen url is blank`() {
        val settings = VoiceRuntimeSettings(listenUrl = "   ", forwardUrl = "")

        assertEquals(
            "http://192.168.1.23:8080/api/v1/word2voice",
            settings.displayUrl(defaultHost = "192.168.1.23"),
        )
        assertNull(settings.normalizedListenHost())
    }

    @Test
    fun `uses configured listen url before default LAN url`() {
        val settings = VoiceRuntimeSettings(
            listenUrl = " http://10.45.0.10:18080/custom/word2voice ",
            forwardUrl = "",
        )

        assertEquals(
            "http://10.45.0.10:18080/custom/word2voice",
            settings.displayUrl(defaultHost = "192.168.1.23"),
        )
        assertEquals("10.45.0.10", settings.normalizedListenHost())
        assertEquals(18080, settings.normalizedListenPort())
        assertEquals("/custom/word2voice", settings.normalizedListenPath())
    }

    @Test
    fun `fills default listen port and path when configured url omits them`() {
        val settings = VoiceRuntimeSettings(listenUrl = "http://10.45.0.10")

        assertEquals(
            "http://10.45.0.10:8080/api/v1/word2voice",
            settings.displayUrl(defaultHost = "192.168.1.23"),
        )
        assertEquals(8080, settings.normalizedListenPort())
        assertEquals("/api/v1/word2voice", settings.normalizedListenPath())
    }

    @Test
    fun `normalizes optional forward url`() {
        val settings = VoiceRuntimeSettings(
            listenUrl = "",
            forwardUrl = " http://10.45.0.2:18891/api/v1/exec ",
        )

        assertEquals("http://10.45.0.2:18891/api/v1/exec", settings.normalizedForwardUrl())
    }

    @Test
    fun `treats blank forward url as disabled`() {
        val settings = VoiceRuntimeSettings(listenUrl = "", forwardUrl = " ")

        assertFalse(settings.forwardingEnabled())
        assertNull(settings.normalizedForwardUrl())
    }

    @Test
    fun `uses the default forwarding timeout`() {
        val settings = VoiceRuntimeSettings()

        assertEquals(3000, settings.normalizedForwardTimeoutMillis())
    }

    @Test
    fun `uses a configured positive forwarding timeout`() {
        val settings = VoiceRuntimeSettings(forwardTimeoutMillis = 10000)

        assertEquals(10000, settings.normalizedForwardTimeoutMillis())
    }

    @Test
    fun `parses invalid forwarding timeout as the default`() {
        assertEquals(3000, VoiceRuntimeSettings.parseForwardTimeoutMillis(""))
        assertEquals(3000, VoiceRuntimeSettings.parseForwardTimeoutMillis("abc"))
        assertEquals(3000, VoiceRuntimeSettings.parseForwardTimeoutMillis("-1"))
    }
}
