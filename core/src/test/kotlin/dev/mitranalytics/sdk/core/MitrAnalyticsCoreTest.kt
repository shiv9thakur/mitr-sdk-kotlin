package dev.mitranalytics.sdk.core

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.security.MessageDigest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

private val testJson = Json { ignoreUnknownKeys = true }

private data class Call(val url: String, val headers: Map<String, String>, val body: String)

private class MockHttpClient(private val statuses: MutableList<Int?>) : MitrHttpClient {
    private val mutex = Mutex()
    val calls = mutableListOf<Call>()

    override suspend fun post(url: String, headers: Map<String, String>, body: String): MitrHttpResponse {
        mutex.withLock { calls.add(Call(url, headers, body)) }
        val status = if (statuses.isEmpty()) 200 else statuses.removeAt(0)
            ?: throw RuntimeException("simulated network failure")
        return MitrHttpResponse(status)
    }
}

private fun expectedHash(raw: String, scope: String): String {
    val digest = MessageDigest.getInstance("SHA-256").digest("$raw:$scope".toByteArray(Charsets.UTF_8))
    return digest.joinToString("") { "%02x".format(it) }
}

private fun decodeEvents(body: String): List<Map<String, Any?>> {
    val payload = testJson.decodeFromString<Map<String, List<Map<String, kotlinx.serialization.json.JsonElement>>>>(body)
    return payload["events"]!!.map { event ->
        event.mapValues { (_, v) -> jsonElementToAny(v) }
    }
}

private fun jsonElementToAny(element: kotlinx.serialization.json.JsonElement): Any? = when (element) {
    is kotlinx.serialization.json.JsonNull -> null
    is kotlinx.serialization.json.JsonPrimitive -> element.content
    is kotlinx.serialization.json.JsonObject -> element.mapValues { (_, v) -> jsonElementToAny(v) }
    is kotlinx.serialization.json.JsonArray -> element.map { jsonElementToAny(it) }
}

class MitrAnalyticsCoreTest {

    private fun newCore(
        http: MitrHttpClient,
        storage: MitrStorage = InMemoryStorage(),
        maxBatchSize: Int = 10,
        flushIntervalMs: Long = 0,
        workspaceId: String? = null,
    ) = MitrAnalyticsCore(
        options = MitrAnalyticsOptions(
            siteId = "s1", secretKey = "k1", workspaceId = workspaceId,
            maxBatchSize = maxBatchSize, flushIntervalMs = flushIntervalMs,
        ),
        storage = storage,
        httpClient = http,
    )

    @Test
    fun `track batches events and flushes once maxBatchSize is reached`() = runTest {
        val http = MockHttpClient(mutableListOf(200))
        val mitr = newCore(http, maxBatchSize = 2)

        mitr.track("a")
        assertEquals(0, http.calls.size, "should not flush before batch size is hit")

        mitr.track("b")
        assertEquals(1, http.calls.size)
        val events = decodeEvents(http.calls[0].body)
        assertEquals(2, events.size)
        assertEquals("k1", http.calls[0].headers["X-Mitr-Secret-Key"])
        assertEquals("s1", events[0]["sid"])
    }

    @Test
    fun `identify hashes the userId with sha256 scoped to siteId`() = runTest {
        val http = MockHttpClient(mutableListOf(200))
        val mitr = newCore(http)

        mitr.identify("raw_user_id_42")
        mitr.track("login")
        mitr.flush()

        val events = decodeEvents(http.calls[0].body)
        assertEquals(expectedHash("raw_user_id_42", "s1"), events[0]["uid"])
    }

    @Test
    fun `per-call uid overrides the identify-set default`() = runTest {
        val http = MockHttpClient(mutableListOf(200))
        val mitr = newCore(http)

        mitr.identify("default_user")
        mitr.track("purchase", uid = "override_user")
        mitr.flush()

        val events = decodeEvents(http.calls[0].body)
        assertEquals(expectedHash("override_user", "s1"), events[0]["uid"])
    }

    @Test
    fun `anonymous events send no uid`() = runTest {
        val http = MockHttpClient(mutableListOf(200))
        val mitr = newCore(http)

        mitr.track("page_load")
        mitr.flush()

        val events = decodeEvents(http.calls[0].body)
        assertNull(events[0]["uid"])
    }

    @Test
    fun `a failed flush requeues the batch for the next attempt`() = runTest {
        val http = MockHttpClient(mutableListOf(null, 200))
        val mitr = newCore(http)

        mitr.track("a")
        mitr.flush()
        assertEquals(1, http.calls.size)

        mitr.flush()
        assertEquals(2, http.calls.size)
        val events = decodeEvents(http.calls[1].body)
        assertEquals("a", events[0]["t"])
    }

    @Test
    fun `pageView dedupes consecutive calls with the same screen name`() = runTest {
        val http = MockHttpClient(mutableListOf(200))
        val mitr = newCore(http)

        mitr.pageView("Home")
        mitr.pageView("Home")
        mitr.pageView("Profile")
        mitr.flush()

        val events = decodeEvents(http.calls[0].body)
        assertEquals(2, events.size)
        assertEquals("Home", events[0]["p"])
        assertEquals("Profile", events[1]["p"])
    }

    @Test
    fun `first-touch deep link UTM is captured once and attached to later events`() = runTest {
        val http = MockHttpClient(mutableListOf(200))
        val mitr = newCore(http)

        mitr.captureDeepLink("myapp://home?utm_source=newsletter&utm_campaign=launch")
        mitr.track("app_opened")
        mitr.flush()

        val events = decodeEvents(http.calls[0].body)
        @Suppress("UNCHECKED_CAST")
        val utm = events[0]["utm"] as Map<String, Any?>
        assertEquals("newsletter", utm["source"])
        assertEquals("launch", utm["campaign"])
    }

    @Test
    fun `a second deep link does not override the first-touch UTM`() = runTest {
        val http = MockHttpClient(mutableListOf(200))
        val mitr = newCore(http)

        mitr.captureDeepLink("myapp://home?utm_source=first")
        mitr.captureDeepLink("myapp://home?utm_source=second")
        mitr.track("app_opened")
        mitr.flush()

        val events = decodeEvents(http.calls[0].body)
        @Suppress("UNCHECKED_CAST")
        val utm = events[0]["utm"] as Map<String, Any?>
        assertEquals("first", utm["source"])
    }

    @Test
    fun `queued events survive across instances sharing the same storage`() = runTest {
        val storage = InMemoryStorage()
        val failingHttp = MockHttpClient(mutableListOf(null))
        val first = newCore(failingHttp, storage = storage)

        first.track("unsent_event")
        first.flush() // fails, requeues, persists
        assertEquals(1, failingHttp.calls.size)

        val succeedingHttp = MockHttpClient(mutableListOf(200))
        val second = newCore(succeedingHttp, storage = storage)
        second.flush()

        assertEquals(1, succeedingHttp.calls.size)
        val events = decodeEvents(succeedingHttp.calls[0].body)
        assertEquals("unsent_event", events[0]["t"])
    }

    @Test
    fun `metadata merges with sdk_version and caller wins on key collision`() = runTest {
        val http = MockHttpClient(mutableListOf(200))
        val mitr = newCore(http)

        mitr.track("signup_completed", metadata = mapOf("plan" to "pro", "sdk_version" to "custom"))
        mitr.flush()

        val events = decodeEvents(http.calls[0].body)
        @Suppress("UNCHECKED_CAST")
        val meta = events[0]["meta"] as Map<String, Any?>
        assertEquals("pro", meta["plan"])
        assertEquals("custom", meta["sdk_version"])
    }

    @Test
    fun `workspaceId scopes the identity hash instead of siteId when set`() = runTest {
        val http = MockHttpClient(mutableListOf(200))
        val mitr = newCore(http, workspaceId = "ws_shared")

        mitr.identify("raw_user_id_42")
        mitr.track("login")
        mitr.flush()

        val events = decodeEvents(http.calls[0].body)
        assertEquals(expectedHash("raw_user_id_42", "ws_shared"), events[0]["uid"])
    }
}
