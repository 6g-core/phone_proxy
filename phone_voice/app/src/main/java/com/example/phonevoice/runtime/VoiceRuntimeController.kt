package com.example.phonevoice.runtime

import com.example.phonevoice.config.VoiceEndpointConfig
import com.example.phonevoice.config.VoiceRuntimeSettings
import com.example.phonevoice.http.SpeechDispatcher
import com.example.phonevoice.state.VoiceServiceStateStore

class VoiceRuntimeController(
    private val stateStore: VoiceServiceStateStore,
    private val dispatcher: SpeechDispatcher,
    private val lanIpProvider: () -> String?,
    private val settingsProvider: () -> VoiceRuntimeSettings = { VoiceRuntimeSettings() },
    private val serverFactory: (VoiceRuntimeSettings) -> EmbeddedServer,
) {
    private var server: EmbeddedServer? = null
    private var activeSettings: VoiceRuntimeSettings? = null

    fun start() {
        if (server != null) {
            val settings = activeSettings ?: settingsProvider()
            stateStore.setRunning(
                lanUrl = VoiceEndpointConfig.buildLanUrl(
                    settings.displayHost(lanIpProvider()),
                ),
                ttsReady = dispatcher.isReady(),
            )
            return
        }

        stateStore.setStarting()
        try {
            val settings = settingsProvider()
            val createdServer = serverFactory(settings)
            createdServer.start()
            server = createdServer
            activeSettings = settings
            stateStore.setRunning(
                lanUrl = VoiceEndpointConfig.buildLanUrl(
                    settings.displayHost(lanIpProvider()),
                ),
                ttsReady = dispatcher.isReady(),
            )
        } catch (t: Throwable) {
            stateStore.setError("Failed to start HTTP server: ${t.message ?: "unknown error"}")
        }
    }

    fun refreshTtsReady() {
        stateStore.setTtsReady(dispatcher.isReady())
    }

    fun stop() {
        server?.stop()
        server = null
        activeSettings = null
        stateStore.setStopped()
    }
}
