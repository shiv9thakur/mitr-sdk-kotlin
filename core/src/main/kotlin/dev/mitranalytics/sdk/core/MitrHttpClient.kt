package dev.mitranalytics.sdk.core

/** Result of a single POST — just enough for MitrAnalyticsCore to decide retry/success. */
public data class MitrHttpResponse(val statusCode: Int)

/**
 * Networking abstraction — lets tests inject a mock instead of hitting a
 * real server. The `android` module's default implementation uses plain
 * `java.net.HttpURLConnection` (available on both the JVM and Android with
 * no extra dependency), not a third-party HTTP client.
 */
public interface MitrHttpClient {
    public suspend fun post(url: String, headers: Map<String, String>, body: String): MitrHttpResponse
}
