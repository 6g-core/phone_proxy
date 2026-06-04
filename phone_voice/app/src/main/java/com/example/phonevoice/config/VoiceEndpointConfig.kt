package com.example.phonevoice.config

object VoiceEndpointConfig {
    const val PORT = 8080
    const val PATH = "/api/v1/word2voice"
    const val FORWARD_PATH = "/api/v1/word2voice_forward"
    const val MAX_TEXT_LENGTH = 1000
    const val DEFAULT_BIND_HOST = "0.0.0.0"

    fun buildLanUrl(
        host: String?,
        port: Int = PORT,
        path: String = PATH,
    ): String {
        if (host.isNullOrBlank()) {
            return "Unavailable"
        }
        return "http://${formatHost(host)}:$port${normalizePath(path)}"
    }

    fun normalizePath(path: String?, defaultPath: String = PATH): String {
        val normalized = path?.trim().orEmpty().ifBlank { defaultPath }
        return if (normalized.startsWith("/")) normalized else "/$normalized"
    }

    private fun formatHost(host: String): String {
        val trimmed = host.trim()
        return if (trimmed.contains(":") && !trimmed.startsWith("[")) {
            "[$trimmed]"
        } else {
            trimmed
        }
    }
}
