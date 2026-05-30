package com.example.phonevoice.config

import android.content.Context

class VoiceSettingsRepository(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(
        PREFS_NAME,
        Context.MODE_PRIVATE,
    )

    fun load(): VoiceRuntimeSettings {
        return VoiceRuntimeSettings(
            listenUrl = readListenUrl(),
            forwardUrl = prefs.getString(KEY_FORWARD_URL, "").orEmpty(),
            forwardTimeoutMillis = prefs.getInt(
                KEY_FORWARD_TIMEOUT_MILLIS,
                VoiceRuntimeSettings.DEFAULT_FORWARD_TIMEOUT_MILLIS,
            ),
        )
    }

    fun save(settings: VoiceRuntimeSettings) {
        prefs.edit()
            .putString(KEY_LISTEN_URL, settings.listenUrl)
            .remove(KEY_LISTEN_HOST)
            .putString(KEY_FORWARD_URL, settings.forwardUrl)
            .putInt(KEY_FORWARD_TIMEOUT_MILLIS, settings.normalizedForwardTimeoutMillis())
            .apply()
    }

    private fun readListenUrl(): String {
        val configuredUrl = prefs.getString(KEY_LISTEN_URL, "").orEmpty()
        if (configuredUrl.isNotBlank()) {
            return configuredUrl
        }

        val legacyHost = prefs.getString(KEY_LISTEN_HOST, "").orEmpty().trim()
        return if (legacyHost.isBlank()) {
            ""
        } else {
            VoiceEndpointConfig.buildLanUrl(legacyHost)
        }
    }

    companion object {
        private const val PREFS_NAME = "voice_runtime_settings"
        private const val KEY_LISTEN_URL = "listen_url"
        private const val KEY_LISTEN_HOST = "listen_host"
        private const val KEY_FORWARD_URL = "forward_url"
        private const val KEY_FORWARD_TIMEOUT_MILLIS = "forward_timeout_millis"
    }
}
