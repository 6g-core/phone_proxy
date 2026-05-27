package com.example.phonevoice.config

object VoiceEndpointConfig {
    const val PORT = 8080
    const val PATH = "/api/v1/word2voice"
    const val MAX_TEXT_LENGTH = 1000

    fun buildLanUrl(ipAddress: String?): String {
        if (ipAddress.isNullOrBlank()) {
            return "Unavailable"
        }
        return "http://$ipAddress:$PORT$PATH"
    }
}
