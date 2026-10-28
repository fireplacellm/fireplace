package com.fireplace.rag

import android.os.Build

/**
 * Ed25519 publisher-signature verification for knowledge packs (ADR 0004).
 *
 * A pack's manifest may carry an Ed25519 signature over a stable payload
 * (id + version + sorted file hashes). We verify it against the EMBEDDED trusted
 * publisher key below, so a signed pack from an untrusted channel can't be
 * swapped for a different (still hash-consistent) one. Verification uses the
 * platform's own Ed25519, available on API 33+ (the 12 GB-class target device);
 * on older APIs it is skipped - the SHA-256 verify-before-use (Pack.verify)
 * still guarantees integrity, so the only thing skipped is publisher authenticity.
 *
 * Unsigned packs (e.g. the user's own on-device data pods) are allowed: there is
 * nothing to authenticate against a publisher, and they never leave the device.
 */
object Signatures {

    // Fireplace publisher public key (32-byte Ed25519, hex). Packs we publish are
    // signed with the matching private key, which never ships in the app.
    private const val TRUSTED_PUBKEY_HEX = "b88aae1ac0e438357b117b1ee256fb26958afe4333c427a11b33bada357e5f57"

    /** DER SubjectPublicKeyInfo prefix for an Ed25519 raw public key. */
    private val DER_PREFIX = byteArrayOf(
        0x30, 0x2a, 0x30, 0x05, 0x06, 0x03, 0x2b, 0x65, 0x70, 0x03, 0x21, 0x00,
    )

    /** Can this device verify Ed25519 (platform support is API 33+)? */
    val supported: Boolean get() = Build.VERSION.SDK_INT >= 33

    /** Verify [sigHex] over [payload] against the trusted publisher key. On a
     *  device without Ed25519 support, returns true (verification skipped). */
    fun verify(payload: ByteArray, sigHex: String): Boolean {
        if (!supported) return true
        return try {
            val sig = hex(sigHex)
            val der = DER_PREFIX + hex(TRUSTED_PUBKEY_HEX)
            val key = java.security.KeyFactory.getInstance("Ed25519")
                .generatePublic(java.security.spec.X509EncodedKeySpec(der))
            java.security.Signature.getInstance("Ed25519").run {
                initVerify(key); update(payload); verify(sig)
            }
        } catch (_: Exception) { false }
    }

    private fun hex(s: String): ByteArray =
        ByteArray(s.length / 2) { ((s[it * 2].digitToInt(16) shl 4) or s[it * 2 + 1].digitToInt(16)).toByte() }
}
