# mitr-kotlin

Official Kotlin SDK for [Mitr Analytics](../../mitr-website) — privacy-first,
zero-PII event tracking for Android (minSdk 21+).

Two modules, mirroring `@mitr/react-native`'s `core.js`/`index.js` split:

- **`core`** — `MitrAnalyticsCore`, the real logic. Zero Android
  dependency: persistence, networking, and device info are all injected,
  which is what lets this module run under a plain JVM unit test (see
  `core/src/test`) with no emulator, Robolectric, or instrumented-test
  environment required.
- **`android`** — the real entry point Android apps depend on. Wires
  `core` up with `SharedPreferences` persistence, `java.net.HttpURLConnection`
  networking (no OkHttp/Ktor dependency), and real device info from
  `android.os.Build`.

## Quick start

```kotlin
// build.gradle.kts
dependencies {
    implementation(project(":mitr-kotlin:android")) // or the published coordinate, once published
}
```

```kotlin
import dev.mitranalytics.sdk.android.MitrAnalytics
import dev.mitranalytics.sdk.core.MitrAnalyticsOptions

val mitr = MitrAnalytics(
    context = applicationContext,
    options = MitrAnalyticsOptions(siteId = "YOUR_SITE_ID", secretKey = "YOUR_SECRET_KEY"),
)

lifecycleScope.launch {
    mitr.track("signup_completed", metadata = mapOf("plan" to "pro"))
    mitr.identify("user_42") // after login
}
```

`MitrAnalytics(context, options)` is a factory *function*, not a
constructor — Kotlin doesn't need a wrapper class here, it just returns a
`MitrAnalyticsCore` with Android-specific defaults filled in. Get your
Site ID and Secret Key from the Mitr dashboard: **Integration Hub → Add
New App → Android** (a Secret Key is required — Android apps have no
Origin header for the backend to check, so every request is authenticated
by secret key instead, same as the Flutter/React Native/Swift SDKs).

## API

Every method is `suspend` — call from a coroutine (`lifecycleScope.launch`,
a `ViewModel`'s `viewModelScope`, etc.).

- `track(eventType, path?, uid?, metadata?)` — a custom event.
- `pageView(screenName, uid?, metadata?)` — a screen view. Dedupes
  consecutive calls with the same `screenName`.
- `identify(userId)` / `reset()` — tie events to a real user on login,
  revert to anonymous on logout. Hashed (SHA-256, via `MessageDigest`) on
  device before it's ever sent — the backend only ever sees `user_hash`.
  Persists across app restarts via `SharedPreferences` until `reset()`.
- `captureDeepLink(url)` — first-touch `utm_*` attribution. Call this from
  your `Activity`'s intent handling (`onCreate`/`onNewIntent`) — the SDK
  has no framework hook into app lifecycle on its own, same situation as
  the Swift SDK. Never overwrites an already-captured first-touch value.
- `flush()` — force-send whatever's currently queued.

`metadata` is `Map<String, Any?>` — write it like you would in any other
Kotlin code:

```kotlin
mitr.track("purchase_completed", metadata = mapOf(
    "value" to 49.99,
    "currency" to "USD",
    "is_first_purchase" to true,
))
```

## Delivery guarantees

Events are batched (default: flush every 5s or every 10 events, whichever
comes first), persisted to `SharedPreferences`, and retried on failure —
an app kill doesn't lose queued events. Same resilience model as
`mitr_flutter`, `mitr-react-native`, and the Swift SDK, not `mitr-node`'s
in-memory-only one.

## Design notes for contributors

- **The core/android split isn't just for testability** — it's the same
  reasoning as `@mitr/react-native`'s `core.js`/`index.js` split: `core`
  has zero Android dependency, which lets it be verified with a plain
  `./gradlew :core:test` instead of needing an emulator or Robolectric.
  Persistence (`MitrStorage`), networking (`MitrHttpClient`), and even
  device-info reporting (`platformInfoProvider`) are all injected rather
  than imported directly — an earlier draft called `android.os.Build`
  straight from the "Android-free" core module by mistake; fixed before
  it shipped.
- **Thread safety**: `MitrAnalyticsCore` isn't confined to a single
  dispatcher the way an actor would be in Swift — Android apps call into
  it from arbitrary coroutine contexts — so all mutable state (the queue,
  the pending flush job) is guarded by a `kotlinx.coroutines.sync.Mutex`.
- **No native crypto or JSON dependency needed for hashing/serialization
  beyond the JVM/kotlinx standard**: `java.security.MessageDigest`
  provides real SHA-256 (unlike React Native's Hermes, which has no
  WebCrypto), and `kotlinx.serialization`'s `JsonElement` already covers
  what the Swift SDK needed a hand-rolled `MitrJSONValue` enum for.
- **`track()`/`pageView()` directly `await` (suspend on) `flush()`** when
  the batch threshold is hit, not a fire-and-forget `launch {}` — matching
  a real bug this exact mistake caused in the Swift SDK's first draft
  (caught by a test asserting on a mock HTTP client's call count
  immediately after `track()` returned). Kotlin's suspend-function model
  makes this the natural way to write it, but it's worth calling out since
  it's easy to get backwards.

## Testing

```bash
./gradlew :core:test          # plain JVM unit tests, no Android SDK/emulator needed
./gradlew :android:assembleDebug  # verifies the Android module compiles + packages against a real Android SDK
```

`android`'s own Android-specific glue (`SharedPreferencesStorage`,
`HttpUrlConnectionClient`, the `MitrAnalytics()` factory) isn't covered by
an automated test suite — exercising it needs a real device/emulator or
Robolectric, which is out of scope here. It's compiled and packaged
against a real Android SDK (`assembleDebug`) as part of this SDK's own
verification, though, so it's not unverified guesswork — just not
behaviorally tested. `core` covers all the actual logic; the `android`
module is a ~100-line wiring layer, same posture as
`mitr-react-native/src/index.js`.
