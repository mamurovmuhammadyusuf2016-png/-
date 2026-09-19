package com.jarvis.agent.core

import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL

data class HttpResponse(val code: Int, val body: String)

interface HttpTransport {
    fun post(url: String, headers: Map<String, String>, body: String): HttpResponse

    /** Only used to list available models; transports in tests can ignore it. */
    fun get(url: String, headers: Map<String, String>): HttpResponse = HttpResponse(0, "")
}

/** Plain HttpURLConnection — works the same on the JVM and on Android, no extra deps. */
class UrlHttpTransport(
    private val connectTimeoutMs: Int = 15_000,
    private val readTimeoutMs: Int = 20_000
) : HttpTransport {

    override fun post(url: String, headers: Map<String, String>, body: String): HttpResponse =
        request(url, "POST", headers, body)

    override fun get(url: String, headers: Map<String, String>): HttpResponse =
        request(url, "GET", headers, null)

    private fun request(
        url: String,
        method: String,
        headers: Map<String, String>,
        body: String?
    ): HttpResponse {
        val connection = URL(url).openConnection() as HttpURLConnection
        try {
            connection.requestMethod = method
            connection.connectTimeout = connectTimeoutMs
            connection.readTimeout = readTimeoutMs
            for ((k, v) in headers) connection.setRequestProperty(k, v)
            if (body != null) {
                connection.doOutput = true
                connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            }

            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val text = stream?.let {
                BufferedReader(InputStreamReader(it, Charsets.UTF_8)).use { r -> r.readText() }
            }.orEmpty()
            // No disconnect(): that drops the socket from the keep-alive pool and makes
            // every later call re-pay the TLS handshake.
            return HttpResponse(code, text)
        } catch (e: Exception) {
            connection.disconnect()
            throw e
        }
    }
}
