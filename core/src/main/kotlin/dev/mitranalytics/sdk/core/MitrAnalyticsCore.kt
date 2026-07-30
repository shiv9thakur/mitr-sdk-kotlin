package dev.mitranalytics.sdk.core

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement

/**
 * Mitr Analytics — Kotlin core. Has zero Android dependency by design:
 * persistence and networking are both injected via [MitrStorage] /
 * [MitrHttpClient] instead of importing `SharedPreferences` or an HTTP
 * client directly. That's what lets this class run under a plain JVM unit
 * test (see `MitrAnalyticsCoreTest`) with no Robolectric/instrumented-test
 * environment required.
 *
 * Real Android apps should use `dev.mitranalytics.sdk.android`'s
 * `MitrAnalytics(context, options)` factory function, which wires this up
 * with `SharedPreferences` + `HttpURLConnection` automatically — see that
 * module.
 *
 * All mutable state (the queue, the pending flush job) is confined behind
 * a [Mutex] rather than assumed single-threaded, since Android apps call
 * into this from arbitrary coroutine dispatchers.
 */
public class MitrAnalyticsCore(
    private val options: MitrAnalyticsOptions,
    private val storage: MitrStorage = InMemoryStorage(),
    private val httpClient: MitrHttpClient,
    /**
     * Supplies `{"platform": ..., "os_version": ...}`-style entries merged
     * into every event's metadata. Injected, not read from `android.os.Build`
     * directly, to keep this module free of any Android dependency — the
     * `android` module's factory overrides this with real device info.
     */
    private val platformInfoProvider: () -> Map<String, String> = { mapOf("platform" to "jvm") },
) {
    private val mutex = Mutex()
    private val queue = mutableListOf<MitrEvent>()
    private var flushJob: Job? = null
    private var lastTrackedScreen: String? = null
    private val scope = CoroutineScope(SupervisorJob())
    private val json = Json { ignoreUnknownKeys = true }

    private companion object {
        const val USER_ID_KEY = "mitr_user_id"
        const val UTM_KEY = "mitr_utm"
        const val QUEUE_KEY = "mitr_event_queue"
        // Hard cap on the persisted queue so an app that's offline (or has
        // a wrong siteId/secretKey) indefinitely can't grow storage
        // without bound — oldest events are dropped first. Mirrors mitr-js.
        const val MAX_QUEUE_SIZE = 200
        const val SDK_VERSION = "1.0.0"
    }

    init {
        storage.getString(QUEUE_KEY)?.let { raw ->
            runCatching { json.decodeFromString<List<MitrEvent>>(raw) }
                .onSuccess { queue.addAll(it) }
        }
    }

    private fun log(vararg items: Any?) {
        if (options.debug) println("[Mitr] " + items.joinToString(" "))
    }

    // MARK: - Public API

    /**
     * Records a custom event.
     * @param path defaults to [eventType] if omitted — matches the other SDKs' convention for non-navigation events.
     * @param uid overrides the identify()'d user for this one call. Most callers never need this.
     * @param metadata merged over {platform, os_version, sdk_version} — pass matching keys to override those.
     */
    public suspend fun track(
        eventType: String,
        path: String? = null,
        uid: String? = null,
        metadata: Map<String, Any?> = emptyMap(),
    ) {
        if (eventType.isEmpty()) {
            log("track(eventType) requires a non-empty eventType.")
            return
        }
        enqueue(buildEvent(eventType, path ?: eventType, uid, metadata))
    }

    /** Records a screen view. Dedupes consecutive calls with the same [screenName]. */
    public suspend fun pageView(
        screenName: String,
        uid: String? = null,
        metadata: Map<String, Any?> = emptyMap(),
    ) {
        if (screenName.isEmpty()) {
            log("pageView(screenName) requires a non-empty screenName.")
            return
        }
        mutex.withLock {
            if (screenName == lastTrackedScreen) return
            lastTrackedScreen = screenName
        }
        enqueue(buildEvent("screen_view", screenName, uid, metadata))
    }

    /**
     * Ties future events to a real user instead of leaving them anonymous.
     * Hashed (SHA-256, salted with the site id or workspace id) before
     * it's ever sent — the backend only ever sees `user_hash`. Persists
     * across app restarts until [reset].
     */
    public suspend fun identify(userId: String) {
        if (userId.isEmpty()) return
        storage.setString(USER_ID_KEY, userId)
        log("identified as", userId)
    }

    /** Clears the identified user (e.g. on logout) — subsequent events revert to anonymous. */
    public suspend fun reset() {
        storage.remove(USER_ID_KEY)
    }

    /**
     * Captures first-touch `utm_*` query params from a deep link. Call
     * this from your `Activity`'s intent handling (`onCreate`/`onNewIntent`)
     * — the SDK has no framework hook into app lifecycle on its own.
     * Never overwrites an already-captured first-touch value.
     */
    public suspend fun captureDeepLink(url: String) {
        if (storage.getString(UTM_KEY) != null) return
        val utm = extractUtm(url) ?: return
        storage.setString(UTM_KEY, json.encodeToString(utm))
    }

    /**
     * Sends everything currently queued (up to maxBatchSize). On failure
     * the batch is put back at the front of the queue so nothing is lost
     * — the next flush (timer, next event, or a later manual call)
     * retries it.
     */
    public suspend fun flush() {
        val batch = mutex.withLock {
            flushJob?.cancel()
            flushJob = null
            if (queue.isEmpty()) return
            val taken = queue.take(minOf(options.maxBatchSize, queue.size))
            repeat(taken.size) { queue.removeAt(0) }
            persistQueueLocked()
            taken
        }

        try {
            val response = httpClient.post(
                url = options.baseUrl.trimEnd('/') + "/event",
                headers = mapOf(
                    "Content-Type" to "application/json",
                    "X-Mitr-Secret-Key" to options.secretKey,
                ),
                body = json.encodeToString(MitrBatchPayload(batch)),
            )
            if (response.statusCode !in 200..299) {
                throw IllegalStateException("HTTP ${response.statusCode}")
            }
            log("flushed", batch.size, "event(s)")
            mutex.withLock { if (queue.isNotEmpty()) scheduleFlushLocked() }
        } catch (e: Exception) {
            log("failed to send, will retry:", e.message)
            mutex.withLock {
                queue.addAll(0, batch)
                persistQueueLocked()
                scheduleFlushLocked()
            }
        }
    }

    // MARK: - Internals

    private suspend fun buildEvent(
        type: String, path: String, explicitUid: String?, metadata: Map<String, Any?>,
    ): MitrEvent {
        val rawUid = explicitUid ?: storage.getString(USER_ID_KEY)
        val uid = rawUid?.let { Sha256Hasher.hex(it + ":" + (options.workspaceId ?: options.siteId)) }

        // Caller-provided metadata wins on key collision with the defaults
        // below — same precedence as mitr-js's Object.assign(defaults, metadata).
        val meta = linkedMapOf<String, JsonElement>("sdk_version" to SDK_VERSION.toJsonElement())
        platformInfoProvider().forEach { (key, value) -> meta[key] = value.toJsonElement() }
        metadata.forEach { (key, value) -> meta[key] = value.toJsonElement() }

        return MitrEvent(
            sid = options.siteId, uid = uid, p = path, t = type, ref = null,
            utm = persistedUtm(), meta = meta,
        )
    }

    private suspend fun enqueue(event: MitrEvent) {
        val shouldFlushNow = mutex.withLock {
            queue.add(event)
            persistQueueLocked()
            log("queued event", event.t)
            queue.size >= options.maxBatchSize
        }
        // flush() takes the mutex itself, so it must run outside the lock
        // above — but it's directly awaited here (not launched as a
        // separate coroutine), so track()/pageView() only return once
        // this flush has genuinely completed, matching the other SDKs'
        // `await this.flush()` in the same spot.
        if (shouldFlushNow) {
            flush()
        } else {
            mutex.withLock { scheduleFlushLocked() }
        }
    }

    /** Must be called while holding [mutex]. */
    private fun scheduleFlushLocked() {
        if (flushJob != null || options.flushIntervalMs <= 0) return
        flushJob = scope.launch {
            delay(options.flushIntervalMs)
            flush()
        }
    }

    /** Must be called while holding [mutex]. */
    private fun persistQueueLocked() {
        if (queue.size > MAX_QUEUE_SIZE) {
            val excess = queue.size - MAX_QUEUE_SIZE
            repeat(excess) { queue.removeAt(0) }
        }
        storage.setString(QUEUE_KEY, json.encodeToString(queue.toList()))
    }

    private fun persistedUtm(): Map<String, String>? {
        val raw = storage.getString(UTM_KEY) ?: return null
        return runCatching { json.decodeFromString<Map<String, String>>(raw) }.getOrNull()
    }

    private fun extractUtm(url: String): Map<String, String>? {
        val queryIndex = url.indexOf('?')
        if (queryIndex == -1) return null
        val query = url.substring(queryIndex + 1).substringBefore('#')
        val utm = mutableMapOf<String, String>()
        for (pair in query.split('&')) {
            if (pair.isEmpty()) continue
            val parts = pair.split('=', limit = 2)
            val rawKey = parts.getOrNull(0) ?: continue
            val rawValue = parts.getOrNull(1) ?: continue
            if (!rawKey.startsWith("utm_")) continue
            val key = java.net.URLDecoder.decode(rawKey, "UTF-8").removePrefix("utm_")
            val value = java.net.URLDecoder.decode(rawValue.replace("+", " "), "UTF-8")
            if (value.isNotEmpty()) utm[key] = value
        }
        return utm.ifEmpty { null }
    }
}
