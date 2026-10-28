package com.fireplace.rag

import androidx.sqlite.SQLiteConnection
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.util.zip.ZipFile

/** A retrieved passage. [score] is higher-is-better. */
data class Hit(
    val passageId: String,
    val docId: String,
    val title: String,
    val text: String,
    val score: Double,
)

class PackException(message: String) : Exception(message)

/** Cheap catalog metadata read from a pack's manifest only (no verify, no extract). */
data class PackMeta(
    val id: String,
    val title: String,
    val description: String,
    val sizeBytes: Long,
)

/** Packs with at most this many passages are treated as small (pods) and retrieved in full. */
const val SMALL_PACK = 64

/**
 * Opens a verified `.fpack` and exposes BM25 retrieval over its passages.
 * Verify-before-use (ADR 0004): every file's SHA-256 is checked against the
 * manifest BEFORE the SQLite DB is handed to the engine. Mirrors the tested
 * Python reference (corpus/pack_format.py, pipeline/reader.py).
 */
class PackReader private constructor(
    val packId: String,
    private val conn: SQLiteConnection,
    private val sources: Map<String, JSONObject>,
    private val dense: java.nio.ByteBuffer?,  // N*dim int8, row-major - memory-MAPPED
    private val denseIds: List<String>,       // row order
) {
    val hasDense get() = dense != null && denseIds.isNotEmpty()
    private val dim get() = if (denseIds.isEmpty()) 0 else dense!!.capacity() / denseIds.size

    val passageCount: Int by lazy {
        conn.prepare("SELECT count(*) FROM passages").use { if (it.step()) it.getLong(0).toInt() else 0 }
    }
    /** Small packs (pods) are retrieved in full so "summarize"-style queries see every record. */
    val isSmall get() = passageCount in 1..SMALL_PACK

    fun source(docId: String): JSONObject? = sources[docId]

    /** All passages (for small packs/pods). Capped for safety. */
    fun allHits(limit: Int = SMALL_PACK): List<Hit> {
        val out = ArrayList<Hit>()
        val stmt = conn.prepare("SELECT passage_id, doc_id, title, body FROM passages LIMIT ?")
        try {
            stmt.bindLong(1, limit.toLong())
            while (stmt.step()) out.add(Hit(stmt.getText(0), stmt.getText(1), stmt.getText(2), stmt.getText(3), 0.0))
        } finally { stmt.close() }
        return out
    }

    /** Top-k passages by int8 dot product with [queryI8] (same model as the pack).
     *  Reads vectors from the memory-mapped dense buffer (off the Java heap), so a
     *  multi-hundred-MB index for a full corpus scores without OOM. To keep the
     *  per-query scan cheap, each passage row is bulk-copied into a reused array. */
    fun searchDense(queryI8: ByteArray, k: Int = 20): List<Hit> {
        val d = dense ?: return emptyList()
        val dim = dim
        if (dim == 0 || queryI8.size != dim) return emptyList()
        val n = denseIds.size
        val scores = IntArray(n)
        val row = ByteArray(dim)
        val buf = d.duplicate()  // independent position for thread-safety
        var off = 0
        for (i in 0 until n) {
            buf.position(off)
            buf.get(row, 0, dim)
            var s = 0
            for (j in 0 until dim) s += row[j] * queryI8[j]
            scores[i] = s; off += dim
        }
        val top = (0 until n).sortedByDescending { scores[it] }.take(k)
        val pids = top.map { denseIds[it] }
        val rows = fetchPassages(pids)
        return top.mapNotNull { idx ->
            val pid = denseIds[idx]; val r = rows[pid] ?: return@mapNotNull null
            Hit(pid, r.first, r.second, r.third, scores[idx].toDouble())
        }
    }

    private fun fetchPassages(pids: List<String>): Map<String, Triple<String, String, String>> {
        if (pids.isEmpty()) return emptyMap()
        val qs = pids.joinToString(",") { "?" }
        val out = HashMap<String, Triple<String, String, String>>()
        val stmt = conn.prepare("SELECT passage_id, doc_id, title, body FROM passages WHERE passage_id IN ($qs)")
        try {
            pids.forEachIndexed { i, p -> stmt.bindText(i + 1, p) }
            while (stmt.step()) out[stmt.getText(0)] = Triple(stmt.getText(1), stmt.getText(2), stmt.getText(3))
        } finally { stmt.close() }
        return out
    }

    @Synchronized
    fun searchBm25(query: String, k: Int = 20): List<Hit> {
        val terms = contentTerms(query)
        if (terms.isEmpty()) return emptyList()
        // Rare-term CONJUNCTION first, relaxed by dropping the commonest terms, then an
        // OR floor. The old pure-OR query matched every passage containing any one common
        // word ("president OR united OR states OR 2010" → 116k passages), letting topical-
        // title noise ("2010 United States Census") bury the article that actually answers
        // the question. Requiring the rare, discriminative terms together fixes that.
        val ordered = terms.sortedBy { termFreq(it) }        // rarest (most discriminative) first
        val out = LinkedHashMap<String, Hit>()
        var keep = ordered.size
        while (keep >= 1 && out.size < k) {
            for (h in matchQuery(ordered.take(keep).joinToString(" AND ") { "\"$it\"" }, k)) out.putIfAbsent(h.passageId, h)
            keep--
        }
        if (out.size < k) for (h in matchQuery(ordered.joinToString(" OR ") { "\"$it\"" }, k)) out.putIfAbsent(h.passageId, h)
        return out.values.take(k)
    }

    /** One FTS5 MATCH, title weighted 3x body (down from 10x: a strong title match
     *  should help, not dominate, so a passage whose title merely repeats a query
     *  term can't outrank the article that actually answers the question). */
    private fun matchQuery(match: String, k: Int): List<Hit> {
        if (match.isBlank()) return emptyList()
        val out = ArrayList<Hit>()
        val stmt = conn.prepare(
            "SELECT passage_id, doc_id, title, body, bm25(passages, 3.0, 1.0) AS s " +
                "FROM passages WHERE passages MATCH ? ORDER BY s LIMIT ?",
        )
        try {
            stmt.bindText(1, match); stmt.bindLong(2, k.toLong())
            while (stmt.step()) out.add(Hit(stmt.getText(0), stmt.getText(1), stmt.getText(2), stmt.getText(3), -stmt.getDouble(4)))
        } finally { stmt.close() }
        return out
    }

    private val dfCache = HashMap<String, Long>()

    /** Document frequency of a single term - how many passages contain it - used to
     *  order the query's terms rarest-first for the conjunction above. Cached: the same
     *  term recurs across queries and a count over a big FTS table isn't free. */
    private fun termFreq(term: String): Long = dfCache.getOrPut(term) {
        val stmt = conn.prepare("SELECT count(*) FROM passages WHERE passages MATCH ?")
        try { stmt.bindText(1, "\"$term\""); if (stmt.step()) stmt.getLong(0) else 0L } finally { stmt.close() }
    }

    /** Lead passage (#0) of the article whose title exactly equals [phrase]
     *  (case-insensitive), looked up via the FTS title index. The no-model "title
     *  planning" pre-pass: when a query names an article outright ("mona lisa"), this
     *  makes that article's intro a retrieval candidate. Null if no such article. */
    fun leadByTitle(phrase: String): Hit? {
        val p = phrase.trim()
        if (p.length < 3) return null
        val stmt = conn.prepare(
            "SELECT passage_id, doc_id, title, body FROM passages WHERE passages MATCH ? " +
                "ORDER BY length(passage_id), passage_id LIMIT 4",
        )
        return try {
            stmt.bindText(1, "title : \"$p\"")
            var fallback: Hit? = null
            while (stmt.step()) {
                if (!stmt.getText(2).equals(p, ignoreCase = true)) continue
                val h = Hit(stmt.getText(0), stmt.getText(1), stmt.getText(2), stmt.getText(3), 0.0)
                if (h.passageId.endsWith("#0")) return h
                if (fallback == null) fallback = h
            }
            fallback
        } catch (_: Exception) { null } finally { stmt.close() }
    }

    fun close() = conn.close()

    companion object {
        private const val CHUNK = 1 shl 20

        /**
         * Verify + open. [workDir] is where the DB is materialized (app-private).
         *
         * Verify-before-use (ADR 0004) runs in full the first time a pack is seen;
         * the extracted files are then cached and a trust marker (pack size+mtime)
         * is written. On later launches, if the pack file is byte-for-byte the same
         * (size + mtime unchanged) and the cache is intact, we skip the full re-hash
         * and re-extraction - so the app doesn't re-verify a multi-GB pack every
         * time it opens. Any change to the pack invalidates the marker and forces a
         * full re-verify.
         */
        fun open(fpack: File, workDir: File): PackReader {
            val meta = readMeta(fpack)          // cheap: manifest only
            val id = meta.id
            val dir = File(workDir, id).apply { mkdirs() }
            val dbFile = File(dir, "index.sqlite")
            val srcFile = File(dir, "sources.jsonl")
            val denseFile = File(dir, "dense.i8")
            val denseIdFile = File(dir, "dense_ids.txt")
            val marker = File(dir, ".verified")
            val key = "${fpack.length()}:${fpack.lastModified()}"

            val cacheValid = marker.exists() && runCatching { marker.readText() == key }.getOrDefault(false) &&
                dbFile.exists()
            if (!cacheValid) {
                // Full verify-before-use, then (re)materialize the cache.
                verify(fpack)
                dir.listFiles()?.forEach { if (it != dir) it.delete() }
                ZipFile(fpack).use { zip ->
                        zip.getEntry("data/index.sqlite")?.let { e ->
                            zip.getInputStream(e).use { it.copyTo(dbFile.outputStream()) }
                        } ?: throw PackException("pack has no index.sqlite")
                        zip.getEntry("data/sources.jsonl")?.let { e ->
                            zip.getInputStream(e).use { it.copyTo(srcFile.outputStream()) }
                        }
                    val di8 = zip.getEntry("data/dense.i8"); val dids = zip.getEntry("data/dense_ids.txt")
                    if (di8 != null && dids != null) {
                        zip.getInputStream(di8).use { it.copyTo(denseFile.outputStream()) }
                        zip.getInputStream(dids).use { it.copyTo(denseIdFile.outputStream()) }
                    }
                }
                marker.writeText(key)
            }

            // Load from the (now-trusted) extracted cache - no zip, no hashing.
            val sources = HashMap<String, JSONObject>()
            if (srcFile.exists()) srcFile.bufferedReader().forEachLine { line ->
                if (line.isNotBlank()) { val o = JSONObject(line); sources[o.getString("doc_id")] = o }
            }
            var dense: java.nio.ByteBuffer? = null
            var denseIds: List<String> = emptyList()
            if (denseFile.exists() && denseIdFile.exists()) {
                denseIds = denseIdFile.readLines().filter { it.isNotBlank() }
                // Memory-MAP the dense index (can be hundreds of MB for a full
                // corpus) so it never hits the Java heap → no OutOfMemoryError.
                dense = java.io.RandomAccessFile(denseFile, "r").use { raf ->
                    raf.channel.map(java.nio.channels.FileChannel.MapMode.READ_ONLY, 0, denseFile.length())
                }
            }
            // Bundled SQLite (FTS5 enabled). Read-only use; we verified every byte.
            val conn = BundledSQLiteDriver().open(dbFile.absolutePath)
            return PackReader(id, conn, sources, dense, denseIds)
        }

        private fun prettify(id: String): String =
            id.removePrefix("pod-").replace('-', ' ').replace('_', ' ')
                .split(" ").joinToString(" ") { w -> w.replaceFirstChar { it.uppercase() } }.trim()

        /** Read catalog metadata from the manifest only - cheap, used to list
         *  packs (including disabled ones) without extracting or verifying them.
         *  Random-accesses the manifest entry without reading the whole file. */
        fun readMeta(fpack: File): PackMeta {
            ZipFile(fpack).use { zip ->
                val me = zip.getEntry("manifest.json") ?: throw PackException("no manifest")
                return manifestToMeta(JSONObject(zip.getInputStream(me).bufferedReader().readText()), fpack.length())
            }
        }

        private fun manifestToMeta(m: JSONObject, size: Long): PackMeta {
            val id = m.getString("id")
            return PackMeta(
                id = id,
                title = m.optString("title", "").ifBlank { prettify(id) },
                description = m.optString("description", ""),
                sizeBytes = size,
            )
        }

        /** Hash-check every declared file against the manifest. Throws on any mismatch. */
        fun verify(fpack: File): JSONObject {
            ZipFile(fpack).use { zip ->
                val me = zip.getEntry("manifest.json") ?: throw PackException("no manifest")
                val manifest = JSONObject(zip.getInputStream(me).bufferedReader().readText())
                if (manifest.optInt("format_version") != 1)
                    throw PackException("unsupported format_version")
                val files = manifest.getJSONArray("files")
                val declared = HashSet<String>()
                for (i in 0 until files.length()) {
                    val f = files.getJSONObject(i)
                    val path = f.getString("path")
                    if (path.startsWith("/") || path.contains("..") || path.contains("\\"))
                        throw PackException("unsafe path: $path")
                    declared.add(path)
                    val e = zip.getEntry(path) ?: throw PackException("declared file missing: $path")
                    val (bytes, sha) = hashEntry(zip, e)
                    if (bytes != f.getLong("bytes")) throw PackException("size mismatch: $path")
                    if (sha != f.getString("sha256")) throw PackException("sha256 mismatch: $path")
                }
                // No stowaways.
                val names = zip.entries().toList().map { it.name }.filter { it != "manifest.json" && !it.endsWith("/") }
                val undeclared = names.filter { it !in declared }
                if (undeclared.isNotEmpty()) throw PackException("undeclared files: $undeclared")

                // If the pack is SIGNED, the Ed25519 publisher signature must verify
                // against our embedded key (on API 33+). Unsigned packs - e.g. the
                // user's own data pods - are allowed; the hash checks above already
                // guarantee integrity, the signature only adds publisher authenticity.
                val sig = manifest.optString("signature", "")
                if (sig.isNotEmpty()) {
                    val payload = signaturePayload(manifest, files)
                    if (!Signatures.verify(payload, sig)) throw PackException("bad publisher signature")
                }
                return manifest
            }
        }

        /** The stable bytes a pack signature covers - must match corpus/pack_format.py
         *  `_sign_payload`: id + version + sorted "path:sha256:bytes" lines. */
        private fun signaturePayload(manifest: JSONObject, files: org.json.JSONArray): ByteArray {
            val lines = ArrayList<String>()
            lines.add(manifest.getString("id"))
            lines.add(manifest.getString("version"))
            // Sort by PATH (not the full "path:sha:bytes" string) to match Python's
            // _sign_payload exactly - otherwise a ':' vs '.'/'_' tie-break could reorder
            // entries whose paths share a prefix and break verification.
            val rows = ArrayList<Pair<String, String>>()
            for (i in 0 until files.length()) {
                val f = files.getJSONObject(i)
                val path = f.getString("path")
                rows.add(path to "$path:${f.getString("sha256")}:${f.getLong("bytes")}")
            }
            rows.sortBy { it.first }
            lines.addAll(rows.map { it.second })
            return lines.joinToString("\n").toByteArray()
        }

        private fun hashEntry(zip: ZipFile, e: java.util.zip.ZipEntry): Pair<Long, String> {
            val md = MessageDigest.getInstance("SHA-256")
            var total = 0L
            zip.getInputStream(e).use { ins ->
                val buf = ByteArray(CHUNK)
                while (true) {
                    val n = ins.read(buf); if (n < 0) break
                    md.update(buf, 0, n); total += n
                }
            }
            return total to md.digest().joinToString("") { "%02x".format(it) }
        }

        private const val ALNUM = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789"
        // Dropping stopwords is essential: otherwise "how/does/work" match
        // unrelated passages and dominate ranking.
        private val STOP = (
            "the a an of to in on at for and or but is are was were be been being this that these those " +
            "it its as by with from into about over under can could would should may might will shall do " +
            "does did has have had you your i we they he she them his her their our not no yes if then than " +
            "which who whom whose what when where why how my me find good some couple during"
            ).split(" ").toHashSet()

        /** Query → distinct content terms (alphanumeric, non-stopword, >=2 chars),
         *  lowercased. The unit both the conjunction search and term-frequency ordering
         *  work on. */
        fun contentTerms(query: String): List<String> =
            query.split(Regex("\\s+"))
                .map { raw -> raw.filter { it in ALNUM }.lowercase() }
                .filter { it.length >= 2 && it !in STOP }
                .distinct()

        /** Free text → safe FTS5 OR query of quoted, non-stopword terms (operators defused). */
        fun ftsOrQuery(query: String): String =
            contentTerms(query).joinToString(" OR ") { "\"$it\"" }
    }
}
