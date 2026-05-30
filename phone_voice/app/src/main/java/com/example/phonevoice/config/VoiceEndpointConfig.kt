package com.example.phonevoice.config

object VoiceEndpointConfig {
    const val PORT = 8080
    const val PATH = "/api/v1/word2voice"
    const val MAX_TEXT_LENGTH = 1000
    const val DEFAULT_BIND_HOST = "0.0.0.0"

    fun buildLanUrl(host: String?): String {
        if (host.isNullOrBlank()) {
            return "Unavailable"
        }
        return "http://$host:$PORT$PATH"
    }
}
