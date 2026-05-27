package com.example.phonevoice.http

interface SpeechDispatcher {
    fun isReady(): Boolean
    fun speak(text: String): Boolean
}
