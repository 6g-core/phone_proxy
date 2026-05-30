package com.example.phonevoice.http

import com.example.phonevoice.config.VoiceEndpointConfig
import com.example.phonevoice.runtime.EmbeddedServer
import fi.iki.elonen.NanoHTTPD
import java.io.IOException
import java.util.Locale

class WordToVoiceServer(
    private val handler: WordToVoiceRequestHandler,
    listenHost: String? = null,
) : NanoHTTPD(
    normalizeListenHost(listenHost) ?: VoiceEndpointConfig.DEFAULT_BIND_HOST,
    VoiceEndpointConfig.PORT,
),
    EmbeddedServer {
    val listenHost: String? = normalizeListenHost(listenHost)

    override fun start() {
        try {
            super.start(SOCKET_READ_TIMEOUT, false)
        } catch (exc: IOException) {
            val hostLabel = listenHost ?: VoiceEndpointConfig.DEFAULT_BIND_HOST
            throw IllegalStateException("Unable to bind $hostLabel:${VoiceEndpointConfig.PORT}", exc)
        }
    }

    override fun stop() {
        super.stop()
    }

    override fun serve(session: IHTTPSession): Response {
        val body = readBody(session)
        return serveBody(
            method = session.method.name,
            path = session.uri,
            body = body,
        )
    }

    fun serveBody(method: String, path: String, body: String?): Response {
        val result = handler.handle(method = method, path = path, body = body)
        return newFixedLengthResponse(
            statusFrom(result.statusCode),
            "application/json; charset=utf-8",
            result.body,
        )
    }

    private fun readBody(session: IHTTPSession): String? {
        if (session.method != Method.POST) {
            return null
        }
        return try {
            normalizeJsonCharset(session)
            val files = mutableMapOf<String, String>()
            session.parseBody(files)
            files["postData"]
        } catch (_: IOException) {
            null
        } catch (_: ResponseException) {
            null
        }
    }

    internal fun normalizeContentTypeHeader(contentType: String?): String? {
        if (contentType == null) {
            return null
        }

        val normalized = contentType.lowercase(Locale.US)
        val isJsonPayload = normalized.startsWith("application/json") || normalized.contains("+json")
        val hasCharset = CHARSET_REGEX.containsMatchIn(contentType)
        if (!isJsonPayload || hasCharset) {
            return contentType
        }

        return "$contentType; charset=UTF-8"
    }

    private fun normalizeJsonCharset(session: IHTTPSession) {
        val headers = session.headers
        val contentType = headers["content-type"] ?: return
        headers["content-type"] = normalizeContentTypeHeader(contentType) ?: contentType
    }

    private fun statusFrom(code: Int): Response.IStatus {
        return when (code) {
            200 -> Response.Status.OK
            400 -> Response.Status.BAD_REQUEST
            404 -> Response.Status.NOT_FOUND
            405 -> Response.Status.METHOD_NOT_ALLOWED
            500 -> Response.Status.INTERNAL_ERROR
            503 -> Response.Status.SERVICE_UNAVAILABLE
            else -> Response.Status.INTERNAL_ERROR
        }
    }

    companion object {
        private val CHARSET_REGEX = Regex("(^|;)\\s*charset\\s*=", RegexOption.IGNORE_CASE)
    }
}

private fun normalizeListenHost(host: String?): String? = host?.trim()?.ifBlank { null }
