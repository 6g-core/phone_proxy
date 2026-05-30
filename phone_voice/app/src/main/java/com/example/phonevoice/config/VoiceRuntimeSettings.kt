package com.example.phonevoice.config

data class VoiceRuntimeSettings(
    val listenHost: String = "",
    val forwardUrl: String = "",
    val forwardTimeoutMillis: Int = DEFAULT_FORWARD_TIMEOUT_MILLIS,
) {
    fun normalizedListenHost(): String? = listenHost.trim().ifBlank { null }

    fun normalizedForwardUrl(): String? = forwardUrl.trim().ifBlank { null }

    fun forwardingEnabled(): Boolean = normalizedForwardUrl() != null

    fun normalizedForwardTimeoutMillis(): Int {
        return forwardTimeoutMillis.takeIf { it > 0 } ?: DEFAULT_FORWARD_TIMEOUT_MILLIS
    }

    fun displayHost(defaultHost: String?): String? {
        return normalizedListenHost() ?: defaultHost
    }

    companion object {
        const val DEFAULT_FORWARD_TIMEOUT_MILLIS = 3000

        fun parseForwardTimeoutMillis(value: String): Int {
            return value.trim()
                .toIntOrNull()
                ?.takeIf { it > 0 }
                ?: DEFAULT_FORWARD_TIMEOUT_MILLIS
        }
    }
}
