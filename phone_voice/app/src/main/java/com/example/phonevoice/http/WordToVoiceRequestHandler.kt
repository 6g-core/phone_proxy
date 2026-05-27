package com.example.phonevoice.http

import com.example.phonevoice.config.VoiceEndpointConfig
import org.json.JSONException
import org.json.JSONObject

class WordToVoiceRequestHandler(
    private val dispatcher: SpeechDispatcher,
    private val onAcceptedText: (String) -> Unit = {},
) {
    fun handle(method: String, path: String, body: String?): HttpResult {
        if (path != VoiceEndpointConfig.PATH) {
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

        if (!dispatcher.speak(text)) {
            return error(500, "failed to speak text")
        }

        onAcceptedText(text)
        return HttpResult(
            statusCode = 200,
            body = JSONObject(
                mapOf(
                    "success" to true,
                    "message" to "accepted",
                    "text" to text,
                ),
            ).toString(),
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
}
