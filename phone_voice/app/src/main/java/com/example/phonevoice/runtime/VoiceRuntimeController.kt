package com.example.phonevoice.runtime

import com.example.phonevoice.config.VoiceEndpointConfig
import com.example.phonevoice.http.SpeechDispatcher
import com.example.phonevoice.state.VoiceServiceStateStore

class VoiceRuntimeController(
    private val stateStore: VoiceServiceStateStore,
    private val dispatcher: SpeechDispatcher,
    private val lanIpProvider: () -> String?,
    private val serverFactory: () -> EmbeddedServer,
) {
    private var server: EmbeddedServer? = null

    fun start() {
        if (server != null) {
            stateStore.setRunning(
                lanUrl = VoiceEndpointConfig.buildLanUrl(lanIpProvider()),
                ttsReady = dispatcher.isReady(),
            )
            return
        }

        stateStore.setStarting()
        try {
            val createdServer = serverFactory()
            createdServer.start()
            server = createdServer
            stateStore.setRunning(
                lanUrl = VoiceEndpointConfig.buildLanUrl(lanIpProvider()),
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
        stateStore.setStopped()
    }
}
