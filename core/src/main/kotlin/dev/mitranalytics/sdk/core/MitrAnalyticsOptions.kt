package dev.mitranalytics.sdk.core

public data class MitrAnalyticsOptions(
    val siteId: String,
    val secretKey: String,
    val workspaceId: String? = null,
    val baseUrl: String = "https://api.mitranalytics.dev/api/v1",
    val debug: Boolean = false,
    val maxBatchSize: Int = 10,
    /** Set to 0 to disable the background timer (flush only on batch-size or manual flush()). */
    val flushIntervalMs: Long = 5000,
)
