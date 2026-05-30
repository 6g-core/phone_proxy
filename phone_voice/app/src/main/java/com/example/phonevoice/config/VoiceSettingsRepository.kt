package com.example.phonevoice.config

import android.content.Context

class VoiceSettingsRepository(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(
        PREFS_NAME,
        Context.MODE_PRIVATE,
    )

    fun load(): VoiceRuntimeSettings {
        return VoiceRuntimeSettings(
            listenHost = prefs.getString(KEY_LISTEN_HOST, "").orEmpty(),
            forwardUrl = prefs.getString(KEY_FORWARD_URL, "").orEmpty(),
            forwardTimeoutMillis = prefs.getInt(
                KEY_FORWARD_TIMEOUT_MILLIS,
                VoiceRuntimeSettings.DEFAULT_FORWARD_TIMEOUT_MILLIS,
            ),
        )
    }

    fun save(settings: VoiceRuntimeSettings) {
        prefs.edit()
            .putString(KEY_LISTEN_HOST, settings.listenHost)
            .putString(KEY_FORWARD_URL, settings.forwardUrl)
            .putInt(KEY_FORWARD_TIMEOUT_MILLIS, settings.normalizedForwardTimeoutMillis())
            .apply()
    }

    companion object {
        private const val PREFS_NAME = "voice_runtime_settings"
        private const val KEY_LISTEN_HOST = "listen_host"
        private const val KEY_FORWARD_URL = "forward_url"
        private const val KEY_FORWARD_TIMEOUT_MILLIS = "forward_timeout_millis"
    }
}
