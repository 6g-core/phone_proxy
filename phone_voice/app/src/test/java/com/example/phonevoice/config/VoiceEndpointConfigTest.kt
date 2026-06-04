package com.example.phonevoice.config

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceEndpointConfigTest {
    @Test
    fun `uses the approved port and endpoint path`() {
        assertEquals(8080, VoiceEndpointConfig.PORT)
        assertEquals("/api/v1/word2voice", VoiceEndpointConfig.PATH)
        assertEquals("/api/v1/word2voice_forward", VoiceEndpointConfig.FORWARD_PATH)
    }

    @Test
    fun `builds a LAN url for display`() {
        val url = VoiceEndpointConfig.buildLanUrl("192.168.1.23")
        assertTrue(url.startsWith("http://192.168.1.23:8080"))
        assertTrue(url.endsWith("/api/v1/word2voice"))
    }

    @Test
    fun `normalizes a path with a custom default`() {
        assertEquals(
            "/api/v1/word2voice_forward",
            VoiceEndpointConfig.normalizePath("", VoiceEndpointConfig.FORWARD_PATH),
        )
    }
}
