package com.jarvis.agent.core

import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL

data class HttpResponse(val code: Int, val body: String)

interface HttpTransport {
    fun post(url: String, headers: Map<String, String>, body: String): HttpResponse
}

/** Plain HttpURLConnection — works the same on the JVM and on Android, no extra deps. */
class UrlHttpTransport(
    private val connectTimeoutMs: Int = 15_000,
    private val readTimeoutMs: Int = 60_000
) : HttpTransport {

    override fun post(url: String, headers: Map<String, String>, body: String): HttpResponse {
        val connection = URL(url).openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "POST"
            connection.connectTimeout = connectTimeoutMs
            connection.readTimeout = readTimeoutMs
            connection.doOutput = true
            for ((k, v) in headers) connection.setRequestProperty(k, v)
            connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }

            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val text = stream?.let {
                BufferedReader(InputStreamReader(it, Charsets.UTF_8)).use { r -> r.readText() }
            }.orEmpty()
            return HttpResponse(code, text)
        } finally {
            connection.disconnect()
        }
    }
}
