package com.example.phonevoice.state

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

class VoiceServiceStateStore {
    private val _state = MutableStateFlow(VoiceUiState())
    val state: StateFlow<VoiceUiState> = _state.asStateFlow()

    fun setStarting() {
        _state.update { it.copy(status = VoiceServiceStatus.STARTING, lastError = "") }
    }

    fun setRunning(lanUrl: String, forwardLanUrl: String, ttsReady: Boolean) {
        _state.update {
            it.copy(
                status = VoiceServiceStatus.RUNNING,
                lanUrl = lanUrl,
                forwardLanUrl = forwardLanUrl,
                ttsReady = ttsReady,
                lastError = "",
            )
        }
    }

    fun setTtsReady(ttsReady: Boolean) {
        _state.update { it.copy(ttsReady = ttsReady) }
    }

    fun setLastText(text: String) {
        _state.update { it.copy(lastText = text) }
    }

    fun setError(message: String) {
        _state.update {
            it.copy(
                status = VoiceServiceStatus.ERROR,
                lastError = message,
            )
        }
    }

    fun setStopped() {
        _state.value = VoiceUiState()
    }
}
