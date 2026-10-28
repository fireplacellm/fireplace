package com.fireplace.rag

/**
 * Retrieval, grounding and citation verification - the production Kotlin port of
 * the tested Python reference (pipeline/retriever.py, answerer.py, citations.py).
 * Behaviour matches the reference so parity is checkable.
 */

private val TOK = Regex("[a-z0-9]+")
private val STOP = (
    "the a an of to in on at for and or but is are was were be been being this that these those " +
    "it its as by with from into about over under can could would should may might will shall do " +
    "does did has have had you your i we they he she them his her their our not no yes if then than " +
    "which who whom whose what when where why how my me find good some couple"
    ).split(" ").toHashSet()

private fun toks(s: String): Set<String> = TOK.findAll(s.lowercase()).map { it.value }.toHashSet()
private fun contentToks(s: String): List<String> =
    TOK.findAll(s.lowercase()).map { it.value }.filter { it !in STOP && it.length > 2 }.toList()

// ---------- Retrieval (hybrid-ready: BM25 + lexical rerank + budget) ----------

data class RetrievalConfig(
    val bm25K: Int = 20, val denseK: Int = 20, val rerankM: Int = 6, val budgetChars: Int = 2000,
    // How many fused candidates the cross-encoder reranker reorders (precision pass).
    val rerankPool: Int = 18,
    // Above this pack size, skip the brute-force dense scan (needs an ANN index).
    val maxDense: Int = 120_000,
    // At most this many passages from one document, so a single long article can't
    // fill every slot - more distinct sources give the model more to synthesize
    // (the "diversity cap" both reference apps use).
    val maxPerDoc: Int = 2,
)

/** Hybrid retriever: BM25 ∪ dense (if the pack + embedder support it) → RRF →
 *  rerank → token budget (ADR 0002). Falls back to BM25-only without an embedder. */
class Retriever(
    private val readers: List<PackReader>,
    private val embedder: Embedder? = null,
    private val cfg: RetrievalConfig = RetrievalConfig(),
    private val reranker: Reranker? = null,
) {
    fun retrieve(query: String): List<Hit> {
        val byId = LinkedHashMap<String, Hit>()
        val bm25Ranked = ArrayList<String>()
        val denseRanked = ArrayList<String>()
        val titleRanked = ArrayList<String>()
        val qI8 = embedder?.let { it.quantize(it.embed(query)) }

        // Title-planning pre-pass (no model): if a query phrase names an article outright
        // ("mona lisa", "black hole"), seed that article's lead passage so an exact-entity
        // question can't miss its own page no matter how BM25/dense happen to rank it.
        for (h in titlePlan(query)) { byId.putIfAbsent(h.passageId, h); titleRanked.add(h.passageId) }

        for (r in readers) {
            for (h in r.searchBm25(query, cfg.bm25K)) { byId.putIfAbsent(h.passageId, h); bm25Ranked.add(h.passageId) }
            // Dense is a brute-force int8 scan - fine for medium packs, but a whole-
            // corpus scan (hundreds of thousands of rows) is too slow without an ANN
            // index, so above MAX_DENSE we rely on the indexed BM25 only.
            if (qI8 != null && r.hasDense && r.passageCount <= cfg.maxDense)
                for (h in r.searchDense(qI8, cfg.denseK)) { byId.putIfAbsent(h.passageId, h); denseRanked.add(h.passageId) }
        }

        // Weighted reciprocal-rank fusion: a named-article lead outranks everything, dense
        // outranks lexical (dense measured much stronger on entity/temporal questions like
        // "who was president in 2010"), plus a small lead-passage boost inside fuse().
        val lists = ArrayList<Pair<List<String>, Double>>()
        if (titleRanked.isNotEmpty()) lists.add(titleRanked as List<String> to 1.3)
        lists.add(bm25Ranked as List<String> to 0.6)
        if (denseRanked.isNotEmpty()) lists.add(denseRanked as List<String> to 1.0)
        val fused = fuse(lists).mapNotNull { byId[it] }

        // Precision pass: the cross-encoder reads query + passage together and reorders the
        // top candidates so the most on-topic passage leads (it lifts "Canberra" over
        // "Australia" for "capital of Australia"). Lexical overlap is the fallback when no
        // reranker model is loaded, so this is no longer dead code either way.
        val pool = fused.take(cfg.rerankPool)
        val reordered = reranker?.rerank(query, pool, pool.size) ?: rerank(query, pool, pool.size)
        // Keep the context diverse: at most maxPerDoc passages from any one document,
        // so the model sees several distinct sources (more to synthesize) instead of
        // several slices of the same article.
        val diverse = capPerDoc(reordered, cfg.maxPerDoc)
        return applyBudget(diverse.take(cfg.rerankM))
    }

    /** A few exact-title article leads named by contiguous query phrases (longest first),
     *  bounded so it stays cheap. Reuses the FTS title index, no model. */
    private fun titlePlan(query: String): List<Hit> {
        val words = query.lowercase().split(Regex("\\s+"))
            .map { w -> w.filter { it.isLetterOrDigit() } }.filter { it.length >= 2 }
        if (words.size < 2) return emptyList()
        val out = LinkedHashMap<String, Hit>()
        var probes = 0
        for (n in intArrayOf(4, 3, 2)) {
            var i = 0
            while (i + n <= words.size && probes < 12) {
                probes++
                val phrase = words.subList(i, i + n).joinToString(" ")
                for (r in readers) r.leadByTitle(phrase)?.let { out.putIfAbsent(it.passageId, it) }
                i++
            }
        }
        return out.values.toList()
    }

    /** Keep at most [perDoc] passages per document, preserving rank order. */
    private fun capPerDoc(hits: List<Hit>, perDoc: Int): List<Hit> {
        if (perDoc <= 0) return hits
        val counts = HashMap<String, Int>()
        val out = ArrayList<Hit>(hits.size)
        for (h in hits) {
            val n = counts.getOrDefault(h.docId, 0)
            if (n >= perDoc) continue
            counts[h.docId] = n + 1
            out.add(h)
        }
        return out
    }

    /** Weighted reciprocal-rank fusion: score(id) = Σ wᵢ/(k+rank), plus a small lead-
     *  passage boost. A higher weight on the dense list lets a semantic match win on
     *  questions where the answer article shares no keyword with the query. */
    private fun fuse(lists: List<Pair<List<String>, Double>>, k: Int = 60, leadBoost: Double = 0.004): List<String> {
        val scores = HashMap<String, Double>()
        for ((list, w) in lists) list.forEachIndexed { rank, id -> scores[id] = (scores[id] ?: 0.0) + w / (k + rank) }
        for (id in scores.keys.toList()) if (id.endsWith("#0") || id.endsWith("#1")) scores[id] = (scores[id] ?: 0.0) + leadBoost
        return scores.entries.sortedByDescending { it.value }.map { it.key }
    }

    private fun rerank(query: String, hits: List<Hit>, topM: Int): List<Hit> {
        val q = toks(query)
        if (q.isEmpty()) return hits.take(topM)
        return hits.sortedByDescending { h ->
            val p = toks(h.text); val inter = q.intersect(p).size; inter.toDouble() / (q union p).size
        }.take(topM)
    }

    private fun applyBudget(hits: List<Hit>): List<Hit> {
        val out = ArrayList<Hit>(); var used = 0
        for (h in hits) {
            if (used + h.text.length > cfg.budgetChars && out.isNotEmpty()) break
            out.add(h); used += h.text.length
        }
        return out
    }
}

// ---------- Routing ----------

object Router {
    private val GREET = setOf(
        "hi", "hello", "hey", "yo", "sup", "thanks", "thank you", "ok", "okay", "cool", "nice",
        "bye", "goodbye", "good morning", "good afternoon", "good evening", "good night",
    )
    private val STARTERS = listOf(
        "who ", "what ", "when ", "where ", "which ", "why ", "how ", "whom ", "whose ",
        "is ", "are ", "was ", "were ", "does ", "do ", "did ", "can ", "could ",
        "list ", "name ", "define ", "explain ", "tell me about", "compare ", "difference between",
    )

    /** Best-effort: does this look like a factual/lookup question worth grounding in the
     *  packs, rather than chit-chat? Used to auto-ground factual questions even in Chat
     *  mode, so a small model can't answer them from memory with confident fake facts.
     *  Heuristic (no model call): a question mark, or a question/lookup starter, that
     *  isn't a bare greeting. */
    fun looksFactual(q: String): Boolean {
        val s = q.trim().lowercase()
        if (s.length < 4) return false
        if (s in GREET || GREET.any { s == it || s.startsWith("$it ") || s.startsWith("$it,") || s.startsWith("$it!") }) return false
        if (s.endsWith("?")) return true
        return STARTERS.any { s.startsWith(it) }
    }
}

// ---------- Grounding ----------

object Prompt {
    // Kept deliberately short: every token here is re-processed on each query's
    // prefill, which dominates on-device latency. Keeps quote-first grounding +
    // honest abstention; drops verbose guidance a short prompt doesn't need.
    const val SYSTEM =
        "You are Fireplace, an offline research assistant. Answer only from the numbered sources, " +
        "using their own wording where possible - never invent facts. Address every part of the " +
        "question in the first few lines, then elaborate in two or three full paragraphs, using the " +
        "specific names, numbers and dates from the sources and explaining the how and why they give. " +
        "Cite every fact in brackets like [1]. If the sources don't cover something, say so plainly " +
        "instead of guessing. No preamble, no restating the question, no 'Sources' list of your own."

    private val CONTROLISH = Regex("<\\|.*?\\|>|</?(?:s|think|tool_call|im_start|im_end|start_of_turn|end_of_turn|eos|bos)>", RegexOption.IGNORE_CASE)
    private val THINK = Regex("<think>.*?</think>", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE))

    /** Defang control-token sequences in retrieved text (ADR 0002). */
    fun neutralize(text: String): String =
        CONTROLISH.replace(text) { it.value.replace("<", "< ").replace(">", " >") }

    /** Remove any Qwen <think>…</think> reasoning block from an answer. */
    fun stripThink(text: String): String =
        THINK.replace(text, "").replace("<think>", "").replace("</think>", "")

    private val WORD = Regex("[a-z0-9]+")
    private val STOP = setOf("the","a","an","of","to","in","is","are","was","were","and","or","for",
        "on","at","by","with","as","that","this","it","its","from","be","what","why","how","when",
        "which","who","does","do","did","can","will","about","into","than","then","they","their")

    /**
     * An instant, grounded, cited answer built ONLY from the retrieved passages - no
     * model. Shown the moment retrieval finishes (~2 s) so the user reads a correct
     * cited answer while the model prefills behind it, then the streamed synthesis
     * replaces it. Also the fallback if generation times out. (Extractive-first,
     * abstractive-refine.)
     */
    fun extractivePreview(question: String, hits: List<Hit>, maxSentences: Int = 4): String {
        if (hits.isEmpty()) return ""
        val q = WORD.findAll(question.lowercase()).map { it.value }.filter { it.length > 2 && it !in STOP }.toHashSet()
        val picks = ArrayList<String>()
        for ((i, h) in hits.withIndex()) {
            if (picks.size >= maxSentences) break
            val best = splitSentences(neutralize(h.text))
                .filter { contentToks(it).isNotEmpty() }
                .maxByOrNull { s -> WORD.findAll(s.lowercase()).count { it.value in q } }
                ?: continue
            picks.add("${best.trim()} [${i + 1}]")
        }
        return picks.joinToString(" ")
    }

    private const val CHAT_SYSTEM =
        "You are Fireplace, a private AI assistant that runs fully offline on the user's phone. " +
        "Chat naturally and helpfully. You can answer casually here; in Research mode you answer " +
        "questions grounded in the user's offline knowledge packs, with citations. Keep answers concise."

    /** Weave an agent persona + safety line around a base system instruction.
     *  The grounding rules in [base] always stay; the persona only adds identity
     *  and domain, and the safety line is appended so it can't be dropped. */
    private fun withPersona(base: String, persona: String, safety: String?): String = buildString {
        if (persona.isNotBlank()) { append(persona.trim()); append(" ") }
        append(base)
        if (!safety.isNullOrBlank()) { append(" "); append(safety.trim()) }
    }

    /** Casual chat prompt (Normal mode): no forced grounding, no citations. */
    fun buildChat(question: String, history: String = "", persona: String = "", safety: String? = null, family: String = "qwen"): String {
        val sys = withPersona(CHAT_SYSTEM, persona, safety)
        return if (family == "gemma") buildString {
            append("<start_of_turn>user\n").append(sys).append("\n\n").append(question).append("<end_of_turn>\n<start_of_turn>model\n")
        } else buildString {
            append("<|im_start|>system\n").append(sys).append(" /no_think<|im_end|>\n")
            if (history.isNotBlank()) append(history)
            append("<|im_start|>user\n").append(question).append("<|im_end|>\n<|im_start|>assistant\n")
        }
    }

    /** Model family inferred from the GGUF file name - picks the chat template. */
    fun family(fileName: String): String = if (fileName.contains("gemma", ignoreCase = true)) "gemma" else "qwen"

    private const val EX_SRC =
        "[1] (Eiffel Tower) A wrought-iron lattice tower in Paris, France, built for the 1889 World's Fair; at 330 m it was the world's tallest structure until 1930."
    private const val EX_ANS =
        "The Eiffel Tower is a 330 m wrought-iron lattice tower in Paris, France, built for the 1889 World's Fair; it was the world's tallest structure until 1930 [1]."
    // Restated next to the question - small models follow the instruction CLOSEST to
    // the question, since the system prompt gets buried under the sources.
    private const val SHAPE =
        "\n\n(Answer every part of this in the first few lines, then elaborate in 2-3 full paragraphs " +
        "of detail with the reasons, specifics and comparisons the sources give - about 150-300 words. " +
        "Cite inline with [n]. No preamble, don't repeat yourself.)"

    /** Grounded, cited prompt in the right chat template for the model [family].
     *  A one-shot example teaches the "direct answer + inline [n]" shape (small
     *  models need it). An optional agent [persona]+[safety] specializes without
     *  weakening the grounding rules. */
    fun build(question: String, hits: List<Hit>, persona: String = "", safety: String? = null, family: String = "qwen"): String {
        val sys = withPersona(SYSTEM, persona, safety)
        val sources = buildString { hits.forEachIndexed { i, h -> append("[").append(i + 1).append("] (").append(h.title).append(") ").append(neutralize(h.text)).append("\n") } }
        return if (family == "gemma") buildString {
            // Gemma 2 has no system role; fold it into the first user turn. No /no_think.
            append("<start_of_turn>user\n").append(sys).append("\n\nSources:\n").append(EX_SRC)
            append("\n\nQuestion: Tell me about the Eiffel Tower.<end_of_turn>\n")
            append("<start_of_turn>model\n").append(EX_ANS).append("<end_of_turn>\n")
            append("<start_of_turn>user\nSources:\n").append(sources)
            append("\nQuestion: ").append(question).append(SHAPE).append("<end_of_turn>\n<start_of_turn>model\n")
        } else buildString {
            append("<|im_start|>system\n").append(sys).append(" /no_think<|im_end|>\n")
            append("<|im_start|>user\nSources:\n").append(EX_SRC).append("\n\n")
            append("Question: Tell me about the Eiffel Tower.<|im_end|>\n")
            append("<|im_start|>assistant\n").append(EX_ANS).append("<|im_end|>\n")
            append("<|im_start|>user\nSources:\n").append(sources)
            append("\nQuestion: ").append(question).append(SHAPE).append("<|im_end|>\n<|im_start|>assistant\n")
        }
    }

    private val CITE_MARK = Regex("\\[\\d+]")

    /** Clean a finished answer: drop reasoning, remove repeated content (small
     *  models loop at length, re-emitting the same sentence with a new [n]), and
     *  trim a trailing half-sentence left by hitting the token cap. */
    fun tidy(text: String): String {
        val stripped = stripThink(text).trim()
        // De-dup globally on the citation-STRIPPED sentence (a looped block like
        // "…drives all life on Earth [4]" / "… [5]" collapses to one), while
        // KEEPING the paragraph breaks the model wrote so the answer stays readable.
        val seen = HashSet<String>()
        val paragraphs = ArrayList<String>()
        for (para in stripped.split(Regex("\\n\\s*\\n"))) {
            val kept = ArrayList<String>()
            for (s in splitSentences(para)) {
                val key = CITE_MARK.replace(s, "").trim().lowercase()
                if (key.length > 8 && key in seen) continue
                if (key.length > 8) seen.add(key)
                kept.add(s)
            }
            if (kept.isNotEmpty()) paragraphs.add(kept.joinToString(" ").trim())
        }
        var t = paragraphs.joinToString("\n\n").trim()
        // If generation was cut mid-sentence, trim back to the last terminator.
        if (t.isNotEmpty() && t.last() !in charArrayOf('.', '!', '?', '"', ')', ']')) {
            val cut = t.lastIndexOfAny(charArrayOf('.', '!', '?'))
            if (cut > 40) t = t.substring(0, cut + 1)
        }
        return t
    }

    /** True once the recent output has started repeating verbatim (runaway loop),
     *  so streaming can stop early instead of burning the whole token budget. */
    fun isLooping(s: String): Boolean {
        val n = s.length
        if (n < 180) return false
        // Catch a repeating trailing segment at several window sizes, so phrase-level loops
        // (not just one exact 56-char run) are caught early instead of filling the budget.
        for (w in intArrayOf(32, 48, 72)) {
            if (n < w * 3) continue
            val tail = s.substring(n - w)
            var count = 0; var i = s.indexOf(tail)
            while (i >= 0) { count++; if (count >= 3) return true; i = s.indexOf(tail, i + 1) }
        }
        return false
    }

    private fun splitSentences(text: String): List<String> =
        text.trim().split(Regex("(?<=[.!?])\\s+")).filter { it.isNotBlank() }
}

// ---------- Citation verification (with grouping - ported from citations.py) ----------

data class ClaimCheck(val sentence: String, val cited: List<Int>, val supported: Boolean, val coverage: Double, val numbersOk: Boolean = true)

class CitationReport(val checks: List<ClaimCheck>) {
    private val subst get() = checks.filter { contentToks(it.sentence).isNotEmpty() }
    val total get() = subst.size
    val supported get() = subst.count { it.supported }
    val uncited get() = subst.count { it.cited.isEmpty() }
    /** Fraction of substantive claims that carry a citation marker - the honest
     *  "% cited". ~1.0 whenever the model cites every sentence. */
    val citedRate: Double get() = subst.let { if (it.isEmpty()) 1.0 else it.count { c -> c.cited.isNotEmpty() }.toDouble() / it.size }
    /** Fraction of substantive claims whose text is verifiably grounded in the
     *  cited source(s). Drives the grounding label. */
    val passRate: Double get() = subst.let { if (it.isEmpty()) 1.0 else it.count { c -> c.supported }.toDouble() / it.size }
}

object Citations {
    private val CITE = Regex("\\[(\\d+)]")
    // A standalone figure in a claim (ignores the [n] citation markers' digits,
    // which are matched+removed before number-checking).
    private val NUM = Regex("\\b\\d[\\d,.]*\\b")

    fun splitSentences(text: String): List<String> =
        text.trim().split(Regex("(?<=[.!?])\\s+")).filter { it.isNotBlank() }

    /** Attach trailing citations to the sentences they cover (ADR 0005). */
    private fun group(answer: String): List<Pair<String, MutableList<Int>>> {
        val items = ArrayList<Pair<String, MutableList<Int>>>()
        for (sent in splitSentences(answer)) {
            val cites = CITE.findAll(sent).map { it.groupValues[1].toInt() }.toMutableList()
            val content = CITE.replace(sent, "").trim()
            if (contentToks(content).isEmpty()) {
                if (cites.isNotEmpty() && items.isNotEmpty()) items.last().second.addAll(cites)
                continue
            }
            items.add(content to cites)
        }
        // Backward fill: a run of uncited claims is covered by the next citation.
        var covering: List<Int> = emptyList()
        for (i in items.indices.reversed()) {
            if (items[i].second.isNotEmpty()) covering = items[i].second.toList()
            else if (covering.isNotEmpty()) items[i].second.addAll(covering)
        }
        return items
    }

    /** Normalize a figure so "1,000" == "1000" and a trailing unit/punctuation
     *  (e.g. "23.5°", "50%.") doesn't defeat an otherwise-exact number match. */
    private fun normNum(s: String): String = s.replace(",", "").trim('.', ',')

    fun verify(answer: String, hits: List<Hit>, minCoverage: Double = 0.34): CitationReport {
        val checks = ArrayList<ClaimCheck>()
        // Index passage tokens by the document they came from. A long article is
        // split across several retrieved passages, so a claim cited to one of
        // them is really grounded in the whole document - count every sibling
        // passage of a cited source's doc, not just the one passage.
        val byDoc = HashMap<String, MutableSet<String>>()
        for (h in hits) byDoc.getOrPut(h.docId) { HashSet() }.addAll(contentToks(h.text))
        // Numbers are the most-fabricated and most-checkable facts, so require any
        // figure in a cited claim to actually appear in the cited source(s).
        val numByDoc = HashMap<String, MutableSet<String>>()
        for (h in hits) numByDoc.getOrPut(h.docId) { HashSet() }.addAll(NUM.findAll(h.text).map { normNum(it.value) })
        // Union over every retrieved passage - the fallback when a (small) model
        // cites a source number that doesn't exist. The claim is still grounded in
        // the retrieved corpus; a numbering slip shouldn't score it as fabricated.
        val allToks = HashSet<String>().apply { byDoc.values.forEach { addAll(it) } }
        val allNums = HashSet<String>().apply { numByDoc.values.forEach { addAll(it) } }
        for ((content, cited) in group(answer)) {
            val claimToks = contentToks(content)
            if (cited.isEmpty()) { checks.add(ClaimCheck(content, cited, false, 0.0)); continue }
            val inRange = cited.filter { it in 1..hits.size }
            val src: Set<String>; val srcNums: Set<String>
            if (inRange.isEmpty()) {
                src = allToks; srcNums = allNums        // misnumbered citation → verify vs all sources
            } else {
                val s = HashSet<String>(); val sn = HashSet<String>()
                for (n in inRange) { s.addAll(byDoc[hits[n - 1].docId] ?: emptySet()); sn.addAll(numByDoc[hits[n - 1].docId] ?: emptySet()) }
                src = s; srcNums = sn
            }
            val covered = claimToks.count { it in src }
            val coverage = if (claimToks.isEmpty()) 1.0 else covered.toDouble() / claimToks.size
            // A figure the sources don't contain is an unverified number → not supported.
            val numbersOk = NUM.findAll(content).all { normNum(it.value) in srcNums }
            checks.add(ClaimCheck(content, cited, coverage >= minCoverage && numbersOk, coverage, numbersOk))
        }
        return CitationReport(checks)
    }

    /**
     * Attach a [n] citation to each substantive sentence that doesn't already have
     * one, choosing the retrieved source with the best word overlap (above a floor).
     * Small models routinely write correct, source-grounded prose but forget to cite;
     * restoring the inline markers makes the answer transparently grounded - every
     * claim shows where it came from - and lets [verify] score it fairly. Sentences
     * that match no source are left uncited (and will read as unsupported, honestly).
     */
    fun attribute(answer: String, hits: List<Hit>): String {
        if (hits.isEmpty() || answer.isBlank()) return answer
        val srcToks = hits.map { (contentToks(it.title) + contentToks(it.text)).toHashSet() }
        return answer.split(Regex("\\n\\s*\\n")).joinToString("\n\n") { para ->
            splitSentences(para).joinToString(" ") { sent ->
                val st = contentToks(sent)
                if (CITE.containsMatchIn(sent) || st.size < 3) sent
                else {
                    var bestI = -1; var best = 0.0
                    srcToks.forEachIndexed { i, s ->
                        val score = st.count { it in s }.toDouble() / st.size
                        if (score > best) { best = score; bestI = i }
                    }
                    if (bestI >= 0 && best >= 0.34) "$sent [${bestI + 1}]" else sent
                }
            }
        }
    }

    /**
     * Mark the answer so uncertainty is visible instead of hidden (the small-model
     * trust edge): append an inline flag to any sentence that cites a source but
     * whose claim the source doesn't actually support, and, when grounding is weak
     * overall, prepend an honest caution. Mechanical - not a prompt asking the model
     * to behave (prompt-only abstention is unreliable for small models).
     */
    fun annotate(answer: String, report: CitationReport): String {
        // Flag only the highest-confidence problem so the mark stays trustworthy: a
        // cited claim containing a number the source doesn't contain - almost always a
        // fabrication, and the most damaging error. Paraphrases (which token overlap
        // can't judge) are left alone; weak grounding overall is handled by the banner.
        val flagged = report.checks
            .filter { contentToks(it.sentence).isNotEmpty() && it.cited.isNotEmpty() && !it.numbersOk }
            .map { it.sentence }.toHashSet()
        var out = answer
        if (flagged.isNotEmpty()) {
            val sb = StringBuilder()
            for (sent in splitSentences(answer)) {
                sb.append(sent)
                val content = CITE.replace(sent, "").trim()
                if (content.isNotEmpty() && content in flagged) sb.append(" ⚠️")
                sb.append(' ')
            }
            out = sb.toString().trim()
        }
        if (report.total > 0 && report.passRate < 0.34)
            out = "_⚠️ The sources only partly support this answer - treat the flagged parts with caution._\n\n$out"
        return out
    }
}
