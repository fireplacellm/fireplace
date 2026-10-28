package com.fireplace.rag

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import java.io.File
import java.nio.LongBuffer

/**
 * Cross-encoder reranker (ADR 0002). A small BERT cross-encoder scores each
 * (query, passage) pair for true relevance, so the single most on-topic passage
 * leads the context the model reads and the instant extractive preview. It only
 * reorders the already-retrieved candidates - it adds precision, not recall.
 *
 * Retrieval fuses BM25 and dense, which each rank by their own proxy (keyword
 * overlap, vector similarity); the cross-encoder reads the query and passage
 * together, so it separates "mentions the words" from "answers the question"
 * (e.g. it lifts "Canberra" above "Australia" for "capital of Australia").
 *
 * Model + vocab load from <externalFilesDir>/reranker/{model.onnx,vocab.txt}.
 * When the model is absent the retriever falls back to a lexical reorder, so the
 * pipeline still runs. Fully local; no network.
 */
class Reranker private constructor(
    private val env: OrtEnvironment,
    private val session: OrtSession,
    private val tok: WordPiece,
) {
    private val hasTypeIds = session.inputNames.contains("token_type_ids")

    /** Reorder [hits] by cross-encoder relevance to [query]; return the top [topM]. */
    @Synchronized
    fun rerank(query: String, hits: List<Hit>, topM: Int): List<Hit> {
        if (hits.size <= 1) return hits.take(topM)
        val scored = hits.map { h -> h to score(query, "${h.title}. ${h.text}") }
        return scored.sortedByDescending { it.second }.map { it.first }.take(topM)
    }

    private fun score(query: String, passage: String): Float {
        val (ids, mask, types) = tok.pair(query, passage)
        val shape = longArrayOf(1, ids.size.toLong())
        val tIds = OnnxTensor.createTensor(env, LongBuffer.wrap(ids), shape)
        val tMask = OnnxTensor.createTensor(env, LongBuffer.wrap(mask), shape)
        val tType = OnnxTensor.createTensor(env, LongBuffer.wrap(types), shape)
        val feed = HashMap<String, OnnxTensor>()
        feed["input_ids"] = tIds; feed["attention_mask"] = tMask
        if (hasTypeIds) feed["token_type_ids"] = tType
        try {
            session.run(feed).use { res ->
                // logits: shape [1, 1] - a single relevance score (higher = more relevant).
                @Suppress("UNCHECKED_CAST")
                val out = res[0].value as Array<FloatArray>
                return out[0][0]
            }
        } finally { tIds.close(); tMask.close(); tType.close() }
    }

    fun close() { session.close() }

    companion object {
        fun load(baseDir: File): Reranker? {
            val model = File(baseDir, "reranker/model.onnx")
            val vocab = File(baseDir, "reranker/vocab.txt")
            if (!model.exists() || !vocab.exists()) return null
            return try {
                val env = OrtEnvironment.getEnvironment()
                val opts = OrtSession.SessionOptions().apply { setIntraOpNumThreads(4) }
                Reranker(env, env.createSession(model.readBytes(), opts), WordPiece(vocab))
            } catch (_: Exception) { null }
        }
    }
}
