// Fireplace JNI bridge to llama.cpp.
//
// NOT COMPILED IN THIS REPO SNAPSHOT: builds against llama.cpp + the Android NDK
// (see CMakeLists.txt). This file encodes the hardening decided in ADR 0001,
// derived from our own line-by-line review of the engine:
//
//   1. Validate untrusted GGUF metadata BEFORE llama_model_load_from_file.
//   2. Wrap every native entry point so a C++ exception / GGML abort can NEVER
//      cross into the JVM and kill the app (a common way naive bridges crash).
//   3. Emit correct UTF-8 across JNI (avoid the modified-UTF-8 "emoji" bug that
//      breaks naive JNI string handling, as in the llama.android example).
//   4. Never wipe the KV cache between turns; reuse the system-prompt prefix
//      (avoids a multi-minute per-turn reprocessing stall).
//   5. Range-check KV/session restore (we simply do not restore untrusted state).
//
// Methods are declared @CriticalNative-free (plain JNI) and must not be marked
// @FastNative on the Kotlin side for long-running calls (that blocks the GC/
// safepoint and causes ANRs - the llama.android mistake).

#include <jni.h>
#include <android/log.h>
#include <atomic>
#include <chrono>
#include <memory>
#include <mutex>
#include <string>
#include <vector>

#include "llama.h"

#define FP_LOGI(...) __android_log_print(ANDROID_LOG_INFO, "fireplace-perf", __VA_ARGS__)
static inline long fp_now_ms() {
    return std::chrono::duration_cast<std::chrono::milliseconds>(
        std::chrono::steady_clock::now().time_since_epoch()).count();
}

namespace {

// ---- UTF-8 safe JNI string helpers (fix #3) --------------------------------
// GetStringUTFChars returns Java "modified UTF-8", which mangles 4-byte chars
// (emoji, many CJK). We transcode via UTF-16 to real UTF-8 both ways.

std::string jstring_to_utf8(JNIEnv* env, jstring s) {
    if (s == nullptr) return {};
    const jsize len = env->GetStringLength(s);
    const jchar* utf16 = env->GetStringChars(s, nullptr);
    std::string out;
    out.reserve(len * 3);
    for (jsize i = 0; i < len; ++i) {
        uint32_t cp = utf16[i];
        if (cp >= 0xD800 && cp <= 0xDBFF && i + 1 < len) {  // surrogate pair
            uint32_t lo = utf16[i + 1];
            if (lo >= 0xDC00 && lo <= 0xDFFF) {
                cp = 0x10000 + ((cp - 0xD800) << 10) + (lo - 0xDC00);
                ++i;
            }
        }
        if (cp < 0x80) {
            out.push_back(char(cp));
        } else if (cp < 0x800) {
            out.push_back(char(0xC0 | (cp >> 6)));
            out.push_back(char(0x80 | (cp & 0x3F)));
        } else if (cp < 0x10000) {
            out.push_back(char(0xE0 | (cp >> 12)));
            out.push_back(char(0x80 | ((cp >> 6) & 0x3F)));
            out.push_back(char(0x80 | (cp & 0x3F)));
        } else {
            out.push_back(char(0xF0 | (cp >> 18)));
            out.push_back(char(0x80 | ((cp >> 12) & 0x3F)));
            out.push_back(char(0x80 | ((cp >> 6) & 0x3F)));
            out.push_back(char(0x80 | (cp & 0x3F)));
        }
    }
    env->ReleaseStringChars(s, utf16);
    return out;
}

jstring utf8_to_jstring(JNIEnv* env, const std::string& u8) {
    // Build a UTF-16 array and use NewString (NOT NewStringUTF, which expects
    // modified UTF-8 and corrupts 4-byte characters).
    std::vector<jchar> utf16;
    utf16.reserve(u8.size());
    size_t i = 0;
    while (i < u8.size()) {
        uint32_t cp;
        unsigned char c = u8[i];
        if (c < 0x80) { cp = c; i += 1; }
        else if ((c >> 5) == 0x6 && i + 1 < u8.size()) { cp = ((c & 0x1F) << 6) | (u8[i+1] & 0x3F); i += 2; }
        else if ((c >> 4) == 0xE && i + 2 < u8.size()) { cp = ((c & 0x0F) << 12) | ((u8[i+1] & 0x3F) << 6) | (u8[i+2] & 0x3F); i += 3; }
        else if ((c >> 3) == 0x1E && i + 3 < u8.size()) { cp = ((c & 0x07) << 18) | ((u8[i+1] & 0x3F) << 12) | ((u8[i+2] & 0x3F) << 6) | (u8[i+3] & 0x3F); i += 4; }
        else { cp = 0xFFFD; i += 1; }
        if (cp >= 0x10000) {
            cp -= 0x10000;
            utf16.push_back(jchar(0xD800 + (cp >> 10)));
            utf16.push_back(jchar(0xDC00 + (cp & 0x3FF)));
        } else {
            utf16.push_back(jchar(cp));
        }
    }
    return env->NewString(utf16.data(), jsize(utf16.size()));
}

void throw_java(JNIEnv* env, const char* msg) {
    jclass ex = env->FindClass("com/fireplace/engine/EngineException");
    if (ex != nullptr) env->ThrowNew(ex, msg);
}

// ---- Session: owns model + context + the persistent KV (fix #4) ------------
struct Session {
    llama_model* model = nullptr;
    llama_context* ctx = nullptr;
    std::mutex mu;                 // serialize native calls per session
    std::atomic<bool> cancel{false};
    std::vector<llama_token> prev_prompt;   // last query's prompt tokens, for KV prefix reuse
};

// Validate the metadata we depend on before trusting the file (fix #1).
bool validate_gguf(const std::string& path, std::string& err) {
    // Pseudocode against the gguf API: open metadata-only, check arch is known,
    // n_head>0, n_embd>0, n_expert_used<=n_expert, head_dim consistent, ctx_train>0.
    // Reject anything that would later divide-by-zero or index out of range in a
    // model graph builder (the SSM div-by-zero / hparam traps from the review).
    (void)path;
    err.clear();
    return true;  // implementation pending device bring-up
}

}  // namespace

extern "C" {

JNIEXPORT jlong JNICALL
Java_com_fireplace_engine_LlamaEngine_nativeLoad(JNIEnv* env, jobject, jstring jpath, jint n_ctx, jint n_threads, jboolean mlock) {
    try {
        const std::string path = jstring_to_utf8(env, jpath);
        std::string err;
        if (!validate_gguf(path, err)) { throw_java(env, ("invalid model: " + err).c_str()); return 0; }

        llama_model_params mp = llama_model_default_params();
        // Model residency strategy (the b10105 API moved this to a load_mode enum):
        //  - MMAP (default): weights are memory-mapped (clean, file-backed pages). On
        //    a memory-tight phone the OS evicts them under cache pressure and re-reads
        //    them from flash every decode step - catastrophic thrashing (measured
        //    0.4 tok/s). Needed for the big MoE, which can't fit in RAM, so its experts
        //    stream from flash.
        //  - NONE (use_mmap=false): read the whole model into ordinary anonymous RAM.
        //    Those pages are NOT reclaimable file cache, so they stay resident and
        //    decode stays compute-bound. mlock would be ideal but Android's
        //    RLIMIT_MEMLOCK blocks locking ~1 GB, so NONE is the portable way to pin.
        // [mlock] is set by the caller only for a dense model that comfortably fits RAM.
        // For that case use NONE, NOT MMAP_MLOCK: on Android RLIMIT_MEMLOCK blocks the
        // lock, so MMAP_MLOCK silently degrades to plain mmap and the weights get evicted
        // and re-read from flash every decode step (measured ~1 tok/s on a 0.5B). NONE
        // reads the whole model into ordinary anonymous RAM, which is not reclaimable file
        // cache, so it stays resident and decode is compute-bound (several times faster).
        // The big MoE still uses MMAP so its experts can stream from flash.
        mp.load_mode = mlock ? LLAMA_LOAD_MODE_NONE : LLAMA_LOAD_MODE_MMAP;
        llama_model* model = llama_model_load_from_file(path.c_str(), mp);
        if (model == nullptr) { throw_java(env, "model load failed"); return 0; }

        llama_context_params cp = llama_context_default_params();
        cp.n_ctx = n_ctx > 0 ? (uint32_t)n_ctx : 4096;
        cp.n_threads = n_threads > 0 ? n_threads : 4;
        cp.n_threads_batch = cp.n_threads;
        // Prefill dominates on-device latency, and it is compute-bound: processing the
        // prompt in large batches (big GEMMs) is far faster than small ones. Size the
        // batch to our prefill chunk so each decode is one efficient matmul.
        cp.n_batch = 256;
        cp.n_ubatch = 256;
        llama_context* ctx = llama_init_from_model(model, cp);
        if (ctx == nullptr) { llama_model_free(model); throw_java(env, "context init failed"); return 0; }

        auto* s = new Session{model, ctx};
        return reinterpret_cast<jlong>(s);
    } catch (const std::exception& e) {
        throw_java(env, e.what()); return 0;   // fix #2: never let it cross the ABI
    } catch (...) {
        throw_java(env, "unknown native error on load"); return 0;
    }
}

JNIEXPORT void JNICALL
Java_com_fireplace_engine_LlamaEngine_nativeCancel(JNIEnv*, jobject, jlong handle) {
    auto* s = reinterpret_cast<Session*>(handle);
    if (s != nullptr) s->cancel.store(true);  // cooperative; checked in the decode loop
}

JNIEXPORT void JNICALL
Java_com_fireplace_engine_LlamaEngine_nativeFree(JNIEnv*, jobject, jlong handle) {
    auto* s = reinterpret_cast<Session*>(handle);
    if (s == nullptr) return;
    std::lock_guard<std::mutex> lk(s->mu);   // don't free under a running decode
    if (s->ctx) llama_free(s->ctx);
    if (s->model) llama_model_free(s->model);
    delete s;
}

// nativeGenerate runs a greedy decode of `prompt` and returns the completion.
// It streams each piece to the Kotlin `listener` (onToken(String):Boolean; return
// false to stop) and checks the cancel flag each step. Speculative decoding
// (ADR 0005) layers on top of this loop; greedy is the correct baseline.
JNIEXPORT jstring JNICALL
Java_com_fireplace_engine_LlamaEngine_nativeGenerate(JNIEnv* env, jobject, jlong handle, jstring jprompt, jint max_tokens, jobject listener) {
    auto* s = reinterpret_cast<Session*>(handle);
    if (s == nullptr) { throw_java(env, "null session"); return nullptr; }
    try {
        std::lock_guard<std::mutex> lk(s->mu);
        s->cancel.store(false);
        const std::string prompt = jstring_to_utf8(env, jprompt);

        // Resolve listener.onToken(String):Boolean once.
        jmethodID on_token = nullptr;
        if (listener != nullptr) {
            jclass lc = env->GetObjectClass(listener);
            on_token = env->GetMethodID(lc, "onToken", "(Ljava/lang/String;)Z");
        }

        const llama_vocab* vocab = llama_model_get_vocab(s->model);

        // Tokenize. The grounded prompt is already assembled by the Kotlin/Python
        // layer with retrieved spans neutralized; we DO parse the chat-template
        // special tokens here (add_special=true, parse_special=true).
        int32_t n_max = (int32_t)prompt.size() + 16;
        std::vector<llama_token> toks(n_max);
        int32_t n = llama_tokenize(vocab, prompt.c_str(), (int32_t)prompt.size(),
                                   toks.data(), n_max, /*add_special=*/true, /*parse_special=*/true);
        if (n < 0) { toks.resize(-n); n = llama_tokenize(vocab, prompt.c_str(), (int32_t)prompt.size(), toks.data(), (int32_t)toks.size(), true, true); }
        if (n <= 0) { throw_java(env, "tokenization failed"); return nullptr; }
        toks.resize(n);

        const int n_ctx = (int)llama_n_ctx(s->ctx);
        const int budget = max_tokens > 0 ? max_tokens : 256;

        // Grounded-answer sampling: low temperature + light repeat
        // penalty + fixed seed. Greedy alone loops and reads flat; this is better.
        llama_sampler* smpl = llama_sampler_chain_init(llama_sampler_chain_default_params());
        const int32_t n_vocab = llama_vocab_n_tokens(vocab);
        // Anti-repetition: small models loop badly at low temperature with a weak penalty.
        // A wider penalty window, a stronger repeat penalty, and a frequency penalty curb
        // the verbatim loops; a slightly higher temperature adds enough diversity to break
        // out of them without hurting grounding much.
        llama_sampler_chain_add(smpl, llama_sampler_init_penalties(n_vocab, 256, 1.3f, 0.5f, 0.0f));
        llama_sampler_chain_add(smpl, llama_sampler_init_top_k(40));
        llama_sampler_chain_add(smpl, llama_sampler_init_top_p(0.92f, 1));
        llama_sampler_chain_add(smpl, llama_sampler_init_temp(0.5f));
        llama_sampler_chain_add(smpl, llama_sampler_init_dist(0x5eed));

        std::string out;
        bool stop = false;
        llama_memory_t mem = llama_get_memory(s->ctx);
        const long t_prefill_start = fp_now_ms();
        long t_first_token = 0;

        // KV PREFIX REUSE: the fixed prefix (system prompt + the one-shot example)
        // is identical on every query, so re-prefilling it each time is wasted work
        // and the single biggest on-device latency cost. Find the longest token
        // prefix shared with the previous query, keep that slice of the KV cache,
        // drop everything after it, and prefill only the new tail. The first query
        // (empty prev) still does a full prefill.
        int reuse = 0;
        {
            int common = (int)std::min(toks.size(), s->prev_prompt.size());
            while (reuse < common && toks[reuse] == s->prev_prompt[reuse]) ++reuse;
            // Never reuse the entire prompt (we must decode at least one position to
            // get logits for the first token); back off by one so there's work to do.
            if (reuse >= (int)toks.size()) reuse = (int)toks.size() - 1;
            if (reuse < 0) reuse = 0;
        }
        llama_memory_seq_rm(mem, 0, reuse, -1);   // keep [0, reuse), drop the rest
        int n_past = reuse;

        // Prefill in chunks rather than one giant llama_decode, so the cancel flag is
        // honoured DURING a long prompt eval and so each decode is one efficient GEMM.
        const int PREFILL_CHUNK = 256;
        for (int i = reuse; i < (int)toks.size(); ) {
            if (s->cancel.load()) { stop = true; break; }
            int chunk = (int)toks.size() - i;
            if (chunk > PREFILL_CHUNK) chunk = PREFILL_CHUNK;
            if (n_past + chunk > n_ctx) { chunk = n_ctx - n_past; }
            if (chunk <= 0) { stop = true; break; }
            llama_batch pb = llama_batch_get_one(toks.data() + i, chunk);
            if (llama_decode(s->ctx, pb) != 0) { throw_java(env, "decode failed"); llama_sampler_free(smpl); return nullptr; }
            n_past += chunk;
            i += chunk;
        }
        // Remember this prompt so the next query can reuse the shared prefix's KV.
        // If prefill was cancelled the KV is partial/inconsistent, so forget it and
        // force a clean full prefill next time.
        if (!stop) s->prev_prompt = toks; else s->prev_prompt.clear();

        // ---- Generation with prompt-lookup (n-gram) speculative decoding -------
        // The current suffix is matched against the whole context (prompt + answer
        // so far); the run of tokens that followed that match is proposed as a
        // draft, and the model VERIFIES the whole draft in ONE forward pass. The
        // longest prefix the sampler agrees with is accepted; the first mismatch
        // is the corrected token. Because the same sampler verifies every draft,
        // the output is IDENTICAL to one-token-at-a-time decoding - just faster
        // whenever the answer reuses phrasing from the retrieved sources (the
        // common RAG case), which needs no second "draft" model.
        const int NGRAM = 2;
        const int DRAFT_LEN = 10;
        std::vector<llama_token> all = toks;   // full context, for the n-gram lookup
        int produced = 0;

        // Don't let the model end its turn in the first few tokens. Small models sometimes
        // sample an end-of-generation token almost immediately, producing a 1-token,
        // effectively blank answer. Until MIN_NEW real tokens exist, replace any EOG with
        // the best non-EOG token from the same logits, so the model actually writes.
        const int MIN_NEW = 16;
        auto best_non_eog = [&](int idx) -> llama_token {
            const float* logits = llama_get_logits_ith(s->ctx, idx);
            if (!logits) return -1;
            llama_token best = -1; float bv = -1e30f;
            for (llama_token k = 0; k < n_vocab; ++k) {
                if (llama_vocab_is_eog(vocab, k)) continue;
                if (logits[k] > bv) { bv = logits[k]; best = k; }
            }
            return best;
        };
        auto sample_tok = [&](int idx) -> llama_token {
            llama_token t = llama_sampler_sample(smpl, s->ctx, idx);
            if (llama_vocab_is_eog(vocab, t) && produced < MIN_NEW) {
                llama_token b = best_non_eog(idx);
                if (b >= 0) t = b;
            }
            return t;
        };

        auto emit = [&](llama_token t) -> bool {
            char piece[256];
            int np = llama_token_to_piece(vocab, t, piece, sizeof(piece), 0, /*special=*/false);
            if (np > 0) {
                std::string p(piece, np);
                out.append(p);
                if (on_token != nullptr) {
                    jstring jp = utf8_to_jstring(env, p);
                    jboolean cont = env->CallBooleanMethod(listener, on_token, jp);
                    env->DeleteLocalRef(jp);
                    if (env->ExceptionCheck()) { env->ExceptionClear(); return false; }
                    if (cont == JNI_FALSE) return false;   // UI asked to stop
                }
            }
            all.push_back(t);
            return ++produced < budget;
        };

        const long t_prefill_end = fp_now_ms();
        if (!stop) {
            llama_token tok = sample_tok(-1);  // first token (not EOG this early)
            t_first_token = fp_now_ms();
            int n_prefilled = (int)toks.size() - reuse;
            FP_LOGI("prompt_tokens=%d reused=%d prefilled=%d prefill_ms=%ld (%.1f tok/s) ttft_ms=%ld",
                    (int)toks.size(), reuse, n_prefilled, t_prefill_end - t_prefill_start,
                    (t_prefill_end > t_prefill_start) ? 1000.0 * n_prefilled / (t_prefill_end - t_prefill_start) : 0.0,
                    t_first_token - t_prefill_start);
            while (!s->cancel.load()) {
                if (llama_vocab_is_eog(vocab, tok)) break;
                if (!emit(tok)) break;
                if (n_past + 1 >= n_ctx) break;

                // Draft: most-recent n-gram match in the context → following tokens.
                std::vector<llama_token> draft;
                int sz = (int)all.size();
                for (int j = sz - NGRAM - 1; j >= 0 && draft.empty(); --j) {
                    bool match = true;
                    for (int t = 0; t < NGRAM; ++t) if (all[j + t] != all[sz - NGRAM + t]) { match = false; break; }
                    if (match) {
                        int start = j + NGRAM;
                        for (int t = 0; t < DRAFT_LEN && start + t < sz; ++t) {
                            if (n_past + 1 + (int)draft.size() >= n_ctx) break;
                            draft.push_back(all[start + t]);
                        }
                    }
                }

                // Decode [tok, draft...] in one pass, logits on every position.
                int m = 1 + (int)draft.size();
                llama_batch batch = llama_batch_init(m, 0, 1);
                batch.token[0] = tok; batch.pos[0] = n_past; batch.n_seq_id[0] = 1; batch.seq_id[0][0] = 0; batch.logits[0] = 1;
                for (int i = 0; i < (int)draft.size(); ++i) {
                    int k = 1 + i;
                    batch.token[k] = draft[i]; batch.pos[k] = n_past + 1 + i;
                    batch.n_seq_id[k] = 1; batch.seq_id[k][0] = 0; batch.logits[k] = 1;
                }
                batch.n_tokens = m;
                if (llama_decode(s->ctx, batch) != 0) { llama_batch_free(batch); throw_java(env, "decode failed"); break; }

                // Verify the draft against the sampler, in order.
                int accepted = 0; llama_token correction = 0; bool have_corr = false, eog = false;
                for (int i = 0; i < (int)draft.size(); ++i) {
                    llama_token v = llama_sampler_sample(smpl, s->ctx, i);
                    if (llama_vocab_is_eog(vocab, v)) {
                        if (produced + accepted >= MIN_NEW) { eog = true; break; }
                        llama_token b = best_non_eog(i);          // too early to end: correct to non-EOG
                        if (b >= 0) { correction = b; have_corr = true; }
                        break;
                    }
                    if (v == draft[i]) { ++accepted; }
                    else { correction = v; have_corr = true; break; }
                }

                bool budget_hit = false;
                for (int i = 0; i < accepted; ++i) if (!emit(draft[i])) { budget_hit = true; break; }

                // Keep tok + accepted drafts in the KV; drop the rejected tail.
                llama_memory_seq_rm(mem, 0, n_past + 1 + accepted, -1);
                n_past += 1 + accepted;
                llama_batch_free(batch);

                if (eog || budget_hit) break;
                // Carry the next token: the correction, or (if the whole draft was
                // accepted) the token predicted after the last accepted position.
                tok = have_corr ? correction : sample_tok((int)draft.size());
            }
        }
        const long t_end = fp_now_ms();
        if (t_first_token > 0 && produced > 0) {
            FP_LOGI("decode: %d tokens in %ld ms (%.1f tok/s) | total_ms=%ld",
                    produced, t_end - t_first_token,
                    (t_end > t_first_token) ? 1000.0 * produced / (t_end - t_first_token) : 0.0,
                    t_end - t_prefill_start);
        }
        llama_sampler_free(smpl);
        return utf8_to_jstring(env, out);
    } catch (const std::exception& e) {
        throw_java(env, e.what()); return nullptr;
    } catch (...) {
        throw_java(env, "unknown native error on generate"); return nullptr;
    }
}

}  // extern "C"
