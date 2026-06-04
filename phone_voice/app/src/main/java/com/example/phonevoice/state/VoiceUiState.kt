package com.example.phonevoice.state

data class VoiceUiState(
    val status: VoiceServiceStatus = VoiceServiceStatus.STOPPED,
    val lanUrl: String = "Unavailable",
    val forwardLanUrl: String = "Unavailable",
    val ttsReady: Boolean = false,
    val lastText: String = "",
    val lastError: String = "",
)
