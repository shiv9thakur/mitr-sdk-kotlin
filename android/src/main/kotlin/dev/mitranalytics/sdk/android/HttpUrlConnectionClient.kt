package dev.mitranalytics.sdk.android

import dev.mitranalytics.sdk.core.MitrHttpClient
import dev.mitranalytics.sdk.core.MitrHttpResponse
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

/**
 * Plain `java.net.HttpURLConnection` — no OkHttp/Ktor dependency, matching
 * the suite's zero/low-dependency principle. `HttpURLConnection` is
 * blocking, so every call is wrapped in `Dispatchers.IO`.
 */
internal class HttpUrlConnectionClient : MitrHttpClient {
    override suspend fun post(url: String, headers: Map<String, String>, body: String): MitrHttpResponse =
        withContext(Dispatchers.IO) {
            val connection = URL(url).openConnection() as HttpURLConnection
            try {
                connection.requestMethod = "POST"
                connection.doOutput = true
                connection.connectTimeout = 10_000
                connection.readTimeout = 10_000
                headers.forEach { (key, value) -> connection.setRequestProperty(key, value) }

                connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }

                val status = connection.responseCode
                // Drain the response body so the connection can be reused
                // by the platform's connection pool — HttpURLConnection
                // otherwise sometimes forces a fresh TCP connection per
                // request. We don't need the body itself, just the status.
                runCatching {
                    (if (status in 200..299) connection.inputStream else connection.errorStream)
                        ?.use { it.readBytes() }
                }

                MitrHttpResponse(statusCode = status)
            } finally {
                connection.disconnect()
            }
        }
}
