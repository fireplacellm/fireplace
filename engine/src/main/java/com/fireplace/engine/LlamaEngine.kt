package com.fireplace.engine

/** Thrown from native code instead of crashing the process (ADR 0001, fix #2). */
class EngineException(message: String) : Exception(message)

/** Streaming callback. Return false to stop generation early. */
fun interface TokenListener {
    fun onToken(piece: String): Boolean
}

/**
 * Kotlin binding to the llama.cpp JNI bridge. One [LlamaEngine] owns one loaded
 * model + context + its persistent KV cache; the KV is reused across turns and
 * never wiped (ADR 0001 fix #4).
 *
 * Native methods are deliberately NOT @FastNative - these run for seconds and
 * @FastNative would block the GC safepoint and cause ANRs.
 */
class LlamaEngine private constructor(private var handle: Long) {

    /** Generate a completion for [prompt], streaming each piece to [listener].
     *  Returns the full text. Must be called off the main thread. */
    @Synchronized
    fun generate(prompt: String, maxTokens: Int = 512, listener: TokenListener? = null): String {
        check(handle != 0L) { "engine closed" }
        return nativeGenerate(handle, prompt, maxTokens, listener)
    }

    /** Request the in-flight generation stop (cooperative; safe from any thread). */
    fun cancel() {
        if (handle != 0L) nativeCancel(handle)
    }

    @Synchronized
    fun close() {
        if (handle != 0L) {
            nativeFree(handle)
            handle = 0L
        }
    }

    private external fun nativeGenerate(handle: Long, prompt: String, maxTokens: Int, listener: TokenListener?): String
    private external fun nativeCancel(handle: Long)
    private external fun nativeFree(handle: Long)

    companion object {
        init { System.loadLibrary("fireplace_jni") }

        /** Loads a GGUF model. Throws [EngineException] on an invalid/corrupt file
         *  rather than crashing (native validates metadata before loading). */
        @JvmStatic
        external fun nativeLoad(path: String, nCtx: Int, nThreads: Int, mlock: Boolean): Long

        /** [mlock] pins the model in RAM (MMAP_MLOCK) so a memory-tight phone can't
         *  evict its weights and thrash decode from flash. Pass true only for a model
         *  that comfortably fits; the big MoE must pass false (it streams from flash). */
        fun load(path: String, nCtx: Int = 4096, nThreads: Int = 4, mlock: Boolean = false): LlamaEngine {
            val h = nativeLoad(path, nCtx, nThreads, mlock)
            if (h == 0L) throw EngineException("failed to load model: $path")
            return LlamaEngine(h)
        }
    }
}
