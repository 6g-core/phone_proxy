package com.example.phonevoice.http

import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

class ForwardPostClient(
    private val connectTimeoutMillis: Int = 3000,
    private val readTimeoutMillis: Int = 3000,
) : Forwarder {
    override fun forward(url: String): ForwardResult {
        var connection: HttpURLConnection? = null
        return try {
            connection = URL(url).openConnection() as HttpURLConnection
            connection.requestMethod = "POST"
            connection.connectTimeout = connectTimeoutMillis
            connection.readTimeout = readTimeoutMillis
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application")

            val body = "{}".toByteArray(Charsets.UTF_8)
            connection.setFixedLengthStreamingMode(body.size)
            connection.outputStream.use { output ->
                output.write(body)
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
