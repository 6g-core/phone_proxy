package com.example.phonevoice.config

data class VoiceRuntimeSettings(
    val listenHost: String = "",
    val forwardUrl: String = "",
) {
    fun normalizedListenHost(): String? = listenHost.trim().ifBlank { null }

    fun normalizedForwardUrl(): String? = forwardUrl.trim().ifBlank { null }

    fun forwardingEnabled(): Boolean = normalizedForwardUrl() != null

    fun displayHost(defaultHost: String?): String? {
        return normalizedListenHost() ?: defaultHost
    }
}
