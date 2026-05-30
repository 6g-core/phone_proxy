package com.example.phonevoice.config

import java.net.URI
import java.util.Locale

data class VoiceRuntimeSettings(
    val listenUrl: String = "",
    val forwardUrl: String = "",
    val forwardTimeoutMillis: Int = DEFAULT_FORWARD_TIMEOUT_MILLIS,
) {
    fun normalizedListenHost(): String? = normalizedListenEndpoint()?.host

    fun normalizedListenPort(): Int = normalizedListenEndpoint()?.port ?: VoiceEndpointConfig.PORT

    fun normalizedListenPath(): String = normalizedListenEndpoint()?.path ?: VoiceEndpointConfig.PATH

    fun normalizedListenUrl(): String? = normalizedListenEndpoint()?.toUrl()

    fun normalizedForwardUrl(): String? = forwardUrl.trim().ifBlank { null }

    fun forwardingEnabled(): Boolean = normalizedForwardUrl() != null

    fun normalizedForwardTimeoutMillis(): Int {
        return forwardTimeoutMillis.takeIf { it > 0 } ?: DEFAULT_FORWARD_TIMEOUT_MILLIS
    }

    fun displayUrl(defaultHost: String?): String {
        return normalizedListenUrl() ?: VoiceEndpointConfig.buildLanUrl(defaultHost)
    }

    private fun normalizedListenEndpoint(): VoiceListenEndpoint? {
        val value = listenUrl.trim()
        if (value.isBlank()) {
            return null
        }

        val uri = runCatching { URI(value) }.getOrNull() ?: return null
        if (uri.scheme?.lowercase(Locale.US) != "http") {
            return null
        }

        val host = uri.host?.trim()?.ifBlank { null } ?: return null
        val port = uri.port.takeIf { it > 0 } ?: VoiceEndpointConfig.PORT
        val path = VoiceEndpointConfig.normalizePath(uri.path)
        return VoiceListenEndpoint(host = host, port = port, path = path)
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

private data class VoiceListenEndpoint(
    val host: String,
    val port: Int,
    val path: String,
) {
    fun toUrl(): String = VoiceEndpointConfig.buildLanUrl(
        host = host,
        port = port,
        path = path,
    )
}
