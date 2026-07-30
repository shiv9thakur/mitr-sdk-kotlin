package dev.mitranalytics.sdk.android

import android.content.Context
import dev.mitranalytics.sdk.core.MitrStorage

/** `SharedPreferences`-backed persistence — the real default for Android apps. */
internal class SharedPreferencesStorage(context: Context) : MitrStorage {
    private val prefs = context.getSharedPreferences("mitr_analytics_prefs", Context.MODE_PRIVATE)

    override fun getString(key: String): String? = prefs.getString(key, null)

    override fun setString(key: String, value: String) {
        // apply() is already async/fire-and-forget — no extra dispatcher hop needed.
        prefs.edit().putString(key, value).apply()
    }

    override fun remove(key: String) {
        prefs.edit().remove(key).apply()
    }
}
