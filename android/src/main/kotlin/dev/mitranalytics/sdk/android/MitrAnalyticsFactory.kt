package dev.mitranalytics.sdk.android

import android.content.Context
import android.os.Build
import dev.mitranalytics.sdk.core.MitrAnalyticsCore
import dev.mitranalytics.sdk.core.MitrAnalyticsOptions

/**
 * Real entry point for Android apps — wires `MitrAnalyticsCore` (which has
 * zero Android dependency by itself, see that class's doc comment) up with
 * `SharedPreferences` persistence, `HttpURLConnection` networking, and
 * real device info, mirroring how `@mitr/react-native`'s `src/index.js`
 * wires its platform-agnostic core to real `react-native` APIs.
 *
 * A top-level factory *function* (lowercase-looking usage,
 * `MitrAnalytics(context, options)`) rather than a class, since Kotlin
 * doesn't need a class here — this only ever constructs and returns a
 * [MitrAnalyticsCore] with Android-specific defaults filled in.
 *
 * ```kotlin
 * val mitr = MitrAnalytics(context, MitrAnalyticsOptions(siteId = "...", secretKey = "..."))
 * mitr.track("signup_completed", metadata = mapOf("plan" to "pro"))
 * ```
 */
public fun MitrAnalytics(context: Context, options: MitrAnalyticsOptions): MitrAnalyticsCore =
    MitrAnalyticsCore(
        options = options,
        storage = SharedPreferencesStorage(context.applicationContext),
        httpClient = HttpUrlConnectionClient(),
        platformInfoProvider = {
            mapOf(
                "platform" to "android",
                "os_version" to Build.VERSION.RELEASE.orEmpty(),
                "device" to "${Build.MANUFACTURER} ${Build.MODEL}".trim(),
            )
        },
    )
