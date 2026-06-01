package com.example.phonevoice.http

import android.util.Log
import com.example.phonevoice.config.VoiceEndpointConfig
import com.example.phonevoice.config.VoiceRuntimeSettings
import java.util.concurrent.ExecutionException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.ThreadFactory
import java.util.concurrent.TimeUnit
import org.json.JSONException
import org.json.JSONObject

class WordToVoiceRequestHandler(
    private val dispatcher: SpeechDispatcher,
    private val settingsProvider: () -> VoiceRuntimeSettings = { VoiceRuntimeSettings() },
    private val forwarder: Forwarder = ForwardPostClient(),
    requestPath: String = VoiceEndpointConfig.PATH,
    private val onAcceptedText: (String) -> Unit = {},
    private val logger: (String) -> Unit = ::logInfo,
    private val forwardExecutor: ExecutorService = DEFAULT_FORWARD_EXECUTOR,
) {
    private val requestPath = VoiceEndpointConfig.normalizePath(requestPath)

    fun handle(method: String, path: String, body: String?): HttpResult {
        val requestStartedAt = System.nanoTime()
        logger(
            "received HTTP request method=$method path=$path bodyBytes=${body.orEmpty().toByteArray().size}",
        )

        if (path != requestPath) {
            logger("rejected HTTP request status=404 message=path not found elapsedMs=${elapsedMillis(requestStartedAt)}")
            return error(404, "path not found")
        }

        if (method != "POST") {
            logger("rejected HTTP request status=405 message=method not allowed elapsedMs=${elapsedMillis(requestStartedAt)}")
            return error(405, "method not allowed")
        }

        if (!dispatcher.isReady()) {
            logger(
                "rejected HTTP request status=503 message=speech engine not ready " +
                    "elapsedMs=${elapsedMillis(requestStartedAt)}",
            )
            return error(503, "speech engine not ready")
        }

        val payload = try {
            JSONObject(body.orEmpty())
        } catch (_: JSONException) {
            logger(
                "failed to parse request status=400 message=invalid json body " +
                    "elapsedMs=${elapsedMillis(requestStartedAt)}",
            )
            return error(400, "invalid json body")
        }

        val text = payload.optString("text", "").trim()
        if (text.isEmpty()) {
            logger(
                "rejected parsed request status=400 message=text must not be blank " +
                    "elapsedMs=${elapsedMillis(requestStartedAt)}",
            )
            return error(400, "text must not be blank")
        }

        if (text.length > VoiceEndpointConfig.MAX_TEXT_LENGTH) {
            logger(
                "rejected parsed request status=400 message=text exceeds max length " +
                    "textChars=${text.length} elapsedMs=${elapsedMillis(requestStartedAt)}",
            )
            return error(400, "text exceeds max length")
        }

        val armBody = payload.optJSONObject("arm_body")
            ?: run {
                logger(
                    "rejected parsed request status=400 message=arm_body must be a json object " +
                        "elapsedMs=${elapsedMillis(requestStartedAt)}",
                )
                return error(400, "arm_body must be a json object")
            }

        val settings = settingsProvider()
        val forwardUrl = settings.normalizedForwardUrl()
        val forwardTimeoutMillis = settings.normalizedForwardTimeoutMillis()
        val forwardBody = armBody.toString()
        logger(
            "parsed request textChars=${text.length} armBodyBytes=${forwardBody.toByteArray().size} " +
                "forwardEnabled=${forwardUrl != null} elapsedMs=${elapsedMillis(requestStartedAt)}",
        )

        val forwardFuture = startForward(
            url = forwardUrl,
            timeoutMillis = forwardTimeoutMillis,
            body = forwardBody,
            requestStartedAt = requestStartedAt,
        )

        logger("starting speech textChars=${text.length} elapsedMs=${elapsedMillis(requestStartedAt)}")
        val speechStartedAt = System.nanoTime()
        val speechResult = if (dispatcher.speak(text)) {
            SpeechResult(
                success = true,
                message = "accepted",
                elapsedMillis = elapsedMillis(speechStartedAt),
            )
        } else {
            SpeechResult(
                success = false,
                message = "failed to speak text",
                elapsedMillis = elapsedMillis(speechStartedAt),
            )
        }
        logger(
            "speech result success=${speechResult.success} message=\"${speechResult.message}\" " +
                "durationMs=${speechResult.elapsedMillis} elapsedMs=${elapsedMillis(requestStartedAt)}",
        )

        if (speechResult.success) {
            onAcceptedText(text)
        }

        val forwardResult = awaitForward(forwardFuture, requestStartedAt)

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
                        .put("message", speechResult.message)
                        .put("elapsedMs", speechResult.elapsedMillis),
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

    private fun startForward(
        url: String?,
        timeoutMillis: Int,
        body: String,
        requestStartedAt: Long,
    ): Future<ForwardResult>? {
        if (url == null) {
            logger("starting forward skipped message=\"forwardUrl is blank\" elapsedMs=${elapsedMillis(requestStartedAt)}")
            val result = ForwardResult.skipped("forwardUrl is blank")
            logger(
                "forward result success=${result.success} skipped=${result.skipped} " +
                    "message=\"${result.message}\" durationMs=0 elapsedMs=${elapsedMillis(requestStartedAt)}",
            )
            return null
        }

        return forwardExecutor.submit<ForwardResult> {
            val forwardStartedAt = System.nanoTime()
            logger(
                "starting forward url=$url timeoutMs=$timeoutMillis bodyBytes=${body.toByteArray().size} " +
                    "elapsedMs=${elapsedMillis(requestStartedAt)}",
            )
            try {
                val result = forwarder.forward(
                    url = url,
                    timeoutMillis = timeoutMillis,
                    body = body,
                )
                loggerForwardResult(result, forwardStartedAt, requestStartedAt)
                result
            } catch (exc: Exception) {
                val result = ForwardResult(
                    success = false,
                    message = exc.message ?: exc.javaClass.simpleName,
                )
                loggerForwardResult(result, forwardStartedAt, requestStartedAt)
                result
            }
        }
    }

    private fun awaitForward(
        future: Future<ForwardResult>?,
        requestStartedAt: Long,
    ): ForwardResult {
        if (future == null) {
            return ForwardResult.skipped("forwardUrl is blank")
        }

        return try {
            future.get()
        } catch (exc: InterruptedException) {
            Thread.currentThread().interrupt()
            val result = ForwardResult(
                success = false,
                message = exc.message ?: exc.javaClass.simpleName,
            )
            loggerForwardResult(result, requestStartedAt, requestStartedAt)
            result
        } catch (exc: ExecutionException) {
            val cause = exc.cause ?: exc
            val result = ForwardResult(
                success = false,
                message = cause.message ?: cause.javaClass.simpleName,
            )
            loggerForwardResult(result, requestStartedAt, requestStartedAt)
            result
        }
    }

    private fun loggerForwardResult(
        result: ForwardResult,
        forwardStartedAt: Long,
        requestStartedAt: Long,
    ) {
        val statusCode = result.statusCode?.toString() ?: "none"
        logger(
            "forward result success=${result.success} skipped=${result.skipped} " +
                "statusCode=$statusCode message=\"${result.message}\" " +
                "durationMs=${elapsedMillis(forwardStartedAt)} elapsedMs=${elapsedMillis(requestStartedAt)}",
        )
    }

    private data class SpeechResult(
        val success: Boolean,
        val message: String,
        val elapsedMillis: Long,
    )
}

private const val TAG = "PhoneVoiceHttp"

private val DEFAULT_FORWARD_EXECUTOR: ExecutorService = Executors.newCachedThreadPool(
    ThreadFactory { runnable ->
        Thread(runnable, "PhoneVoiceForward").apply {
            isDaemon = true
        }
    },
)

private fun logInfo(message: String) {
    try {
        Log.i(TAG, message)
    } catch (_: RuntimeException) {
        // Local unit tests run without Android's logging implementation.
    }
}

private fun elapsedMillis(startedAt: Long): Long {
    return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt)
}
