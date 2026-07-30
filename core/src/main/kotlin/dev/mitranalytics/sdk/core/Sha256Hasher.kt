package dev.mitranalytics.sdk.core

import java.security.MessageDigest

/**
 * Zero extra dependency: the JVM (and Android, which shares this API)
 * ships a real SHA-256 implementation via `java.security.MessageDigest` —
 * unlike the React Native SDK (Hermes has no WebCrypto), there's no need
 * to hand-roll the algorithm here.
 */
internal object Sha256Hasher {
    fun hex(input: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(input.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }
}
