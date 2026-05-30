package com.example.phonevoice.http

interface Forwarder {
    fun forward(url: String, timeoutMillis: Int, body: String): ForwardResult
}

data class ForwardResult(
    val success: Boolean,
    val message: String,
    val statusCode: Int? = null,
    val skipped: Boolean = false,
) {
    companion object {
        fun skipped(message: String): ForwardResult {
            return ForwardResult(
                success = true,
                message = message,
                skipped = true,
            )
        }
    }
}
