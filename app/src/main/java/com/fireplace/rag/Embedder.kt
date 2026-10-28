package com.fireplace.rag

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import java.io.File
import java.nio.LongBuffer
import kotlin.math.max
import kotlin.math.round
import kotlin.math.sqrt

/**
 * On-device sentence embedder: BERT WordPiece + all-MiniLM-L6-v2 (ONNX,
 * mean-pooled, L2-normalized, 384-dim). Ported 1:1 from pipeline/onnx_embedder.py
 * so query vectors match the int8 passage vectors built into packs - dense
 * retrieval (ADR 0002). Fully local; no network.
 *
 * Model + vocab are loaded from <externalFilesDir>/embedder/{model.onnx,vocab.txt}.
 */
class Embedder private constructor(
    private val env: OrtEnvironment,
    private val session: OrtSession,
    private val tok: WordPiece,
) {
    val dim = 384

    /** Embed text → L2-normalized float vector. */
    @Synchronized
    fun embed(text: String): FloatArray {
        val ids = tok.encode(text)
        val n = ids.size.toLong()
        val idArr = LongArray(ids.size) { ids[it].toLong() }
        val maskArr = LongArray(ids.size) { 1L }
        val zeros = LongArray(ids.size) { 0L }
        val shape = longArrayOf(1, n)
        val tIds = OnnxTensor.createTensor(env, LongBuffer.wrap(idArr), shape)
        val tMask = OnnxTensor.createTensor(env, LongBuffer.wrap(maskArr), shape)
        val tType = OnnxTensor.createTensor(env, LongBuffer.wrap(zeros), shape)
        val feed = HashMap<String, OnnxTensor>()
        feed["input_ids"] = tIds; feed["attention_mask"] = tMask
        if (session.inputNames.contains("token_type_ids")) feed["token_type_ids"] = tType
        try {
            session.run(feed).use { res ->
                @Suppress("UNCHECKED_CAST")
                val out = (res[0].value as Array<Array<FloatArray>>)[0]  // [T, 384]
                val v = FloatArray(dim)
                for (t in out) for (j in 0 until dim) v[j] += t[j]
                val cnt = max(1, out.size)
                var norm = 0f
                for (j in 0 until dim) { v[j] /= cnt; norm += v[j] * v[j] }
                norm = max(1e-9f, sqrt(norm))
                for (j in 0 until dim) v[j] /= norm
                return v
            }
        } finally { tIds.close(); tMask.close(); tType.close() }
    }

    /** Quantize a normalized vector to int8 (matches the pack builder). */
    fun quantize(v: FloatArray): ByteArray =
        ByteArray(v.size) { round(v[it] * 127f).coerceIn(-127f, 127f).toInt().toByte() }

    fun close() { session.close() }

    companion object {
        fun load(dir: File): Embedder? {
            val model = File(dir, "embedder/model.onnx")
            val vocab = File(dir, "embedder/vocab.txt")
            if (!model.exists() || !vocab.exists()) return null
            val env = OrtEnvironment.getEnvironment()
            val opts = OrtSession.SessionOptions().apply { setIntraOpNumThreads(4) }
            val session = env.createSession(model.readBytes(), opts)
            return Embedder(env, session, WordPiece(vocab))
        }
    }
}

/** Uncased BERT WordPiece. Mirrors pipeline/onnx_embedder.py:WordPiece. */
class WordPiece(vocabFile: File) {
    private val vocab = HashMap<String, Int>()
    private val unk: Int; private val cls: Int; private val sep: Int
    private val punct = Regex("([^\\p{L}\\p{N}\\s]|_)")

    init {
        vocabFile.bufferedReader().useLines { lines ->
            lines.forEachIndexed { i, line -> vocab[line] = i }
        }
        unk = vocab["[UNK]"]!!; cls = vocab["[CLS]"]!!; sep = vocab["[SEP]"]!!
    }

    fun encode(text: String, maxLen: Int = 256): IntArray {
        val ids = ArrayList<Int>()
        ids.add(cls)
        for (tok in basic(text)) {
            ids.addAll(wordpiece(tok))
            if (ids.size >= maxLen - 1) break
        }
        while (ids.size > maxLen - 1) ids.removeAt(ids.size - 1)
        ids.add(sep)
        return ids.toIntArray()
    }

    /** Encode a (query, passage) pair as a BERT cross-encoder input:
     *  [CLS] a [SEP] b [SEP], token_type 0 over a and 1 over b. Used by [Reranker]. */
    fun pair(a: String, b: String, maxLen: Int = 256, aMax: Int = 64): Triple<LongArray, LongArray, LongArray> {
        val aIds = ArrayList<Int>()
        for (t in basic(a)) { aIds.addAll(wordpiece(t)); if (aIds.size >= aMax) break }
        while (aIds.size > aMax) aIds.removeAt(aIds.size - 1)
        val budget = maxLen - aIds.size - 3
        val bIds = ArrayList<Int>()
        if (budget > 0) for (t in basic(b)) { if (bIds.size >= budget) break; bIds.addAll(wordpiece(t)) }
        while (bIds.size > budget) bIds.removeAt(bIds.size - 1)
        val ids = ArrayList<Int>(aIds.size + bIds.size + 3)
        ids.add(cls); ids.addAll(aIds); ids.add(sep)
        val split = ids.size
        ids.addAll(bIds); ids.add(sep)
        val input = LongArray(ids.size) { ids[it].toLong() }
        val mask = LongArray(ids.size) { 1L }
        val types = LongArray(ids.size) { if (it < split) 0L else 1L }
        return Triple(input, mask, types)
    }

    private fun basic(text: String): List<String> =
        punct.replace(text.lowercase().trim()) { " ${it.value} " }.split(Regex("\\s+")).filter { it.isNotEmpty() }

    private fun wordpiece(token: String): List<Int> {
        if (token.length > 100) return listOf(unk)
        val out = ArrayList<Int>()
        var start = 0
        while (start < token.length) {
            var end = token.length
            var cur = -1
            while (start < end) {
                val sub = (if (start > 0) "##" else "") + token.substring(start, end)
                val id = vocab[sub]
                if (id != null) { cur = id; break }
                end--
            }
            if (cur < 0) return listOf(unk)
            out.add(cur); start = end
        }
        return out
    }
}
