package dev.mitranalytics.sdk.core

/**
 * Persistence abstraction — lets tests inject an in-memory double instead
 * of a real platform store. Synchronous by design: `SharedPreferences`
 * reads are themselves synchronous, and writes via `apply()` are already
 * fire-and-forget, so no suspend/async wrapper is needed here.
 */
public interface MitrStorage {
    public fun getString(key: String): String?
    public fun setString(key: String, value: String)
    public fun remove(key: String)
}

/**
 * Default storage for the core module — has no persistence at all beyond
 * the process's lifetime. This is the same honest trade-off `@mitr/node`
 * makes, for the same reason: `core` has zero Android dependency, so it
 * can't default to `SharedPreferences` (not on this module's classpath).
 * Real Android apps should use `dev.mitranalytics.sdk.android`'s
 * `MitrAnalytics(context, options)` factory, which wires a
 * `SharedPreferences`-backed storage in automatically.
 */
public class InMemoryStorage : MitrStorage {
    private val store = mutableMapOf<String, String>()

    override fun getString(key: String): String? = store[key]
    override fun setString(key: String, value: String) { store[key] = value }
    override fun remove(key: String) { store.remove(key) }
}
