package com.example.phonevoice.tts

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.Voice
import android.util.Log
import com.example.phonevoice.http.SpeechDispatcher
import java.util.Locale
import java.util.UUID

class TtsManager(
    context: Context,
    private val onReadyChanged: (Boolean) -> Unit = {},
) : SpeechDispatcher, TextToSpeech.OnInitListener {
    private val appContext = context.applicationContext
    private var tts: TextToSpeech? = null
    private var preferredChineseLocale: Locale? = null
    private var preferredChineseVoice: Voice? = null
    private var defaultLocale: Locale? = null
    private var defaultVoice: Voice? = null

    @Volatile
    private var ready = false

    fun initialize() {
        if (tts == null) {
            tts = TextToSpeech(appContext, this)
        }
    }

    override fun onInit(status: Int) {
        val engine = tts
        if (engine == null || status != TextToSpeech.SUCCESS) {
            ready = false
            onReadyChanged(false)
            return
        }

        defaultLocale = engine.defaultVoice?.locale ?: Locale.getDefault()
        defaultVoice = engine.voice
        preferredChineseLocale = resolveChineseLocale(engine)
        preferredChineseVoice = resolveChineseVoice(engine, preferredChineseLocale)
        val chineseLocale = preferredChineseLocale
        val chineseVoice = preferredChineseVoice

        ready = if (chineseLocale != null) {
            selectLocale(engine, chineseLocale, chineseVoice)
        } else {
            Log.w(
                TAG,
                "No Chinese TTS locale available. Falling back to default locale=$defaultLocale voice=$defaultVoice",
            )
            defaultLocale?.let { selectLocale(engine, it, defaultVoice) } ?: true
        }
        onReadyChanged(ready)
    }

    override fun isReady(): Boolean = ready

    override fun speak(text: String): Boolean {
        val engine = tts ?: return false
        val configured = configureSpeechProfile(engine, text)
        if (!configured) {
            Log.w(TAG, "Failed to configure TTS profile before speaking text.")
        }
        val result = engine.speak(
            text,
            TextToSpeech.QUEUE_FLUSH,
            null,
            UUID.randomUUID().toString(),
        )
        return result != TextToSpeech.ERROR
    }

    fun shutdown() {
        tts?.stop()
        tts?.shutdown()
        tts = null
        ready = false
        onReadyChanged(false)
    }

    private fun configureSpeechProfile(engine: TextToSpeech, text: String): Boolean {
        val shouldUseChinese = text.any { isChineseCharacter(it) }
        val targetLocale = if (shouldUseChinese) {
            preferredChineseLocale ?: defaultLocale
        } else {
            defaultLocale ?: preferredChineseLocale
        }
        val targetVoice = if (shouldUseChinese) {
            preferredChineseVoice ?: defaultVoice
        } else {
            defaultVoice ?: preferredChineseVoice
        }
        return targetLocale?.let { selectLocale(engine, it, targetVoice) } ?: false
    }

    private fun resolveChineseLocale(engine: TextToSpeech): Locale? {
        val candidates = listOf(
            Locale.forLanguageTag("zh-CN"),
            Locale.SIMPLIFIED_CHINESE,
            Locale.CHINA,
            Locale.CHINESE,
            Locale.forLanguageTag("cmn-CN"),
        )
        return candidates.firstOrNull { locale ->
            val availability = engine.isLanguageAvailable(locale)
            availability != TextToSpeech.LANG_MISSING_DATA &&
                availability != TextToSpeech.LANG_NOT_SUPPORTED
        }
    }

    private fun resolveChineseVoice(engine: TextToSpeech, locale: Locale?): Voice? {
        val targetLanguage = locale?.language ?: return null
        return engine.voices
            ?.sortedBy { it.name }
            ?.firstOrNull { voice ->
                voice.locale.language == targetLanguage &&
                    !voice.isNetworkConnectionRequired &&
                    "notInstalled" !in voice.features
            }
    }

    private fun selectLocale(
        engine: TextToSpeech,
        locale: Locale,
        voice: Voice?,
    ): Boolean {
        val languageResult = engine.setLanguage(locale)
        if (languageResult == TextToSpeech.LANG_MISSING_DATA ||
            languageResult == TextToSpeech.LANG_NOT_SUPPORTED
        ) {
            Log.w(TAG, "TTS locale not supported: $locale")
            return false
        }

        if (voice != null) {
            try {
                engine.voice = voice
            } catch (exc: IllegalArgumentException) {
                Log.w(TAG, "Failed to select TTS voice ${voice.name} for locale $locale", exc)
            }
        }
        return true
    }

    private fun isChineseCharacter(char: Char): Boolean {
        return Character.UnicodeScript.of(char.code) == Character.UnicodeScript.HAN
    }

    companion object {
        private const val TAG = "PhoneVoiceTts"
    }
}
