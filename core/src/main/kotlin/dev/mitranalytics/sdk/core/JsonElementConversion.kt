package dev.mitranalytics.sdk.core

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive

/**
 * Converts an ordinary Kotlin value into a `JsonElement` so `metadata`'s
 * public API can stay `Map<String, Any?>` — ergonomic call sites like
 * `metadata = mapOf("plan" to "pro", "amount" to 49.99)` — while still
 * being fully `@Serializable` internally via kotlinx.serialization's own
 * JSON value types, rather than hand-rolling a JSON-value sum type the
 * way the Swift SDK's `MitrJSONValue` had to (Codable has no equivalent
 * of kotlinx.serialization's `JsonElement`).
 */
internal fun Any?.toJsonElement(): JsonElement = when (this) {
    null -> JsonNull
    is JsonElement -> this
    is String -> JsonPrimitive(this)
    is Boolean -> JsonPrimitive(this)
    is Number -> JsonPrimitive(this)
    else -> JsonPrimitive(this.toString())
}
