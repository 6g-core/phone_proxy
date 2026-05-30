package com.example.phonevoice.http

import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

class ForwardPostClient : Forwarder {
    override fun forward(url: String, timeoutMillis: Int, body: String): ForwardResult {
        var connection: HttpURLConnection? = null
        return try {
            val normalizedTimeout = timeoutMillis.takeIf { it > 0 } ?: 3000
            connection = URL(url).openConnection() as HttpURLConnection
            connection.requestMethod = "POST"
            connection.connectTimeout = normalizedTimeout
            connection.readTimeout = normalizedTimeout
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application")

            val requestBody = body.toByteArray(Charsets.UTF_8)
            connection.setFixedLengthStreamingMode(requestBody.size)
            connection.outputStream.use { output ->
                output.write(requestBody)
            }

            val statusCode = connection.responseCode
            if (statusCode in 200..299) {
                ForwardResult(
                    success = true,
                    message = "HTTP $statusCode",
                    statusCode = statusCode,
                )
            } else {
                ForwardResult(
                    success = false,
                    message = "HTTP $statusCode from forwardUrl",
                    statusCode = statusCode,
                )
            }
        } catch (exc: IOException) {
            ForwardResult(
                success = false,
                message = exc.message ?: exc.javaClass.simpleName,
            )
        } catch (exc: IllegalArgumentException) {
            ForwardResult(
                success = false,
                message = exc.message ?: exc.javaClass.simpleName,
            )
        } finally {
            connection?.disconnect()
        }
    }
}
