package com.example.phonevoice.http

import com.example.phonevoice.config.VoiceEndpointConfig
import com.example.phonevoice.config.VoiceRuntimeSettings
import org.json.JSONException
import org.json.JSONObject

class WordToVoiceRequestHandler(
    private val dispatcher: SpeechDispatcher,
    private val settingsProvider: () -> VoiceRuntimeSettings = { VoiceRuntimeSettings() },
    private val forwarder: Forwarder = ForwardPostClient(),
    requestPath: String = VoiceEndpointConfig.PATH,
    private val onAcceptedText: (String) -> Unit = {},
) {
    private val requestPath = VoiceEndpointConfig.normalizePath(requestPath)

    fun handle(method: String, path: String, body: String?): HttpResult {
        if (path != requestPath) {
            return error(404, "path not found")
        }

        if (method != "POST") {
            return error(405, "method not allowed")
        }

        if (!dispatcher.isReady()) {
            return error(503, "speech engine not ready")
        }

        val payload = try {
            JSONObject(body.orEmpty())
        } catch (_: JSONException) {
            return error(400, "invalid json body")
        }

        val text = payload.optString("text", "").trim()
        if (text.isEmpty()) {
            return error(400, "text must not be blank")
        }

        if (text.length > VoiceEndpointConfig.MAX_TEXT_LENGTH) {
            return error(400, "text exceeds max length")
        }

        val speechResult = if (dispatcher.speak(text)) {
            SpeechResult(success = true, message = "accepted")
        } else {
            SpeechResult(success = false, message = "failed to speak text")
        }

        if (!speechResult.success) {
            return successOrFailure(
                text = text,
                speechResult = speechResult,
                forwardResult = ForwardResult.skipped("skipped because speech failed"),
            )
        }

        onAcceptedText(text)
        val settings = settingsProvider()
        val forwardUrl = settings.normalizedForwardUrl()
        val forwardResult = if (forwardUrl == null) {
            ForwardResult.skipped("forwardUrl is blank")
        } else {
            forwarder.forward(
                url = forwardUrl,
                timeoutMillis = settings.normalizedForwardTimeoutMillis(),
            )
        }

        return successOrFailure(
            text = text,
            speechResult = speechResult,
            forwardResult = forwardResult,
        )
    }

    private fun error(statusCode: Int, message: String): HttpResult {
        return HttpResult(
            statusCode = statusCode,
            body = JSONObject(
                mapOf(
                    "success" to false,
                    "message" to message,
                ),
            ).toString(),
        )
    }

    private fun successOrFailure(
        text: String,
        speechResult: SpeechResult,
        forwardResult: ForwardResult,
    ): HttpResult {
        val success = speechResult.success && forwardResult.success
        val message = when {
            !speechResult.success -> "speech failed"
            !forwardResult.success -> "forward failed"
            else -> "accepted"
        }

        return HttpResult(
            statusCode = if (success) 200 else 500,
            body = JSONObject()
                .put("success", success)
                .put("message", message)
                .put("text", text)
                .put(
                    "speech",
                    JSONObject()
                        .put("success", speechResult.success)
                        .put("message", speechResult.message),
                )
                .put("forward", forwardResult.toJson())
                .toString(),
        )
    }

    private fun ForwardResult.toJson(): JSONObject {
        val json = JSONObject()
            .put("success", success)
            .put("message", message)
            .put("skipped", skipped)
        if (statusCode != null) {
            json.put("statusCode", statusCode)
        }
        return json
    }

    private data class SpeechResult(
        val success: Boolean,
        val message: String,
    )
}
