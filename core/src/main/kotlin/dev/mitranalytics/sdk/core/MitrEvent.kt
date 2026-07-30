package dev.mitranalytics.sdk.core

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/**
 * Wire format matching the backend's compact event schema
 * (sid/uid/p/t/ref/utm/meta — see mitr-backend/app/schemas/events.py).
 */
@Serializable
internal data class MitrEvent(
    val sid: String,
    val uid: String? = null,
    val p: String,
    val t: String,
    val ref: String? = null,
    val utm: Map<String, String>? = null,
    val meta: Map<String, JsonElement>,
)

@Serializable
internal data class MitrBatchPayload(val events: List<MitrEvent>)
