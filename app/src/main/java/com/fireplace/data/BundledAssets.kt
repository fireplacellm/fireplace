package com.fireplace.data

import android.content.Context
import java.io.File

/**
 * Installs the knowledge base that ships inside the APK on first run, so Research
 * works offline out of the box - no download, no hosting. We bundle a curated
 * Wikipedia starter pack (~11k core articles, with its dense vectors) plus the
 * semantic-search embedder, and copy them into the app's files dir once. The full
 * 146k-article pack remains an optional upgrade download later.
 *
 * Copying is idempotent and gated by a pref, so a user who deletes the starter
 * pack doesn't get it silently reinstalled.
 */
object BundledAssets {
    private const val PREF = "fp_app"
    private const val KEY = "bundled_v7_installed"   // bumped: now also ships the reranker
    private const val ASSET_PACK = "starter/wikipedia.fpack"
    private const val ASSET_ONNX = "starter/embedder/model.onnx"
    private const val ASSET_VOCAB = "starter/embedder/vocab.txt"
    private const val ASSET_RR_ONNX = "starter/reranker/model.onnx"
    private const val ASSET_RR_VOCAB = "starter/reranker/vocab.txt"

    /** True if the APK actually carries the bundled starter assets. */
    fun available(ctx: Context): Boolean = try {
        ctx.assets.list("starter")?.contains("wikipedia.fpack") == true
    } catch (_: Exception) { false }

    private fun hasAsset(ctx: Context, path: String): Boolean = try {
        ctx.assets.list(path.substringBeforeLast('/'))?.contains(path.substringAfterLast('/')) == true
    } catch (_: Exception) { false }

    // Every .fpack bundled in the APK (any file under assets/starter/ ending .fpack).
    // Wikipedia is the base; first-aid and crypto are focused topical packs.

    /** Copy bundled packs + embedder into the files dir, once. Safe to call every launch. */
    fun installIfNeeded(ctx: Context) {
        val prefs = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)
        if (prefs.getBoolean(KEY, false)) return
        if (!available(ctx)) return
        val base = ctx.getExternalFilesDir(null) ?: return
        try {
            val packs = File(base, "packs").apply { mkdirs() }
            // Copy every .fpack shipped under assets/starter/.
            (ctx.assets.list("starter") ?: emptyArray()).filter { it.endsWith(".fpack") }.forEach { name ->
                copyAsset(ctx, "starter/$name", File(packs, name))
            }
            val emb = File(base, "embedder").apply { mkdirs() }
            copyAsset(ctx, ASSET_ONNX, File(emb, "model.onnx"))
            copyAsset(ctx, ASSET_VOCAB, File(emb, "vocab.txt"))
            // Cross-encoder reranker (optional at runtime, but bundled so it's there).
            if (hasAsset(ctx, ASSET_RR_ONNX)) {
                val rr = File(base, "reranker").apply { mkdirs() }
                copyAsset(ctx, ASSET_RR_ONNX, File(rr, "model.onnx"))
                copyAsset(ctx, ASSET_RR_VOCAB, File(rr, "vocab.txt"))
            }
            prefs.edit().putBoolean(KEY, true).apply()
        } catch (_: Exception) {
            // Non-fatal: the app still runs; the user can download a pack instead.
        }
    }

    // Overwrites unconditionally: this runs once per bundled version (pref-gated),
    // so a version bump replaces the previous starter pack with the new one.
    private fun copyAsset(ctx: Context, asset: String, dest: File) {
        val tmp = File(dest.parentFile, dest.name + ".part")
        ctx.assets.open(asset).use { ins -> tmp.outputStream().buffered().use { ins.copyTo(it) } }
        dest.delete()
        if (!tmp.renameTo(dest)) { tmp.copyTo(dest, overwrite = true); tmp.delete() }
    }
}
