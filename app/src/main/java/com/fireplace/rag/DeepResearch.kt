package com.fireplace.rag

/**
 * Deep-research prompting + planning. The pipeline (orchestrated in ChatViewModel) is:
 *
 *   broad retrieve → retrieval-INFORMED outline → per-section retrieve into one global
 *   numbered source pool → draft each section OVER THE REAL numbered sources (emit GAP
 *   if unsupported) → assemble (direct-answer lead + sections + reconcile + gaps).
 *
 * Why it's better than a blind decompose→summarize: the outline is grounded in what the
 * packs actually contain (so facets are answerable), and every section is written over
 * real cited sources (not re-summarized "perspectives"), so claims stay grounded. The
 * section prompts share ONE source block, so the engine's KV prefix cache is reused and
 * only each heading is re-prefilled - the key to keeping k passes tractable on-device.
 */
object DeepResearch {

    /** Plan 2–4 sections by question complexity (conjunctions / length). */
    fun sectionCount(question: String): Int {
        val q = question.lowercase()
        val conj = Regex("\\b(and|vs|versus|compare|compared|difference|differences|both|benefits|risks|pros|cons)\\b")
            .findAll(q).count()
        val words = question.trim().split(Regex("\\s+")).size
        return when {
            conj >= 2 || words > 22 -> 4
            conj >= 1 || words > 12 -> 3
            else -> 2
        }
    }

    private fun wrap(sys: String, user: String, family: String): String =
        if (family == "gemma")
            "<start_of_turn>user\n$sys\n\n$user<end_of_turn>\n<start_of_turn>model\n"
        else
            "<|im_start|>system\n$sys /no_think<|im_end|>\n<|im_start|>user\n$user<|im_end|>\n<|im_start|>assistant\n"

    /** Phase 1 - a retrieval-informed outline. The retrieved [titles] tell the planner
     *  what the library actually covers, so the sections are answerable offline. */
    fun outline(question: String, titles: List<String>, k: Int, family: String): String {
        val sys = "You plan a structured research answer from an offline library. Output only the outline."
        val topics = titles.distinct().take(16).joinToString("\n") { "- $it" }
        val user = "Question: $question\n\nSource topics available in the library:\n$topics\n\n" +
            "Produce an outline of $k sections that together answer the question AND are each supported " +
            "by the topics above. One section per line, EXACTLY in the form:\nHEADING | search query\n" +
            "No numbering, no preamble, no commentary."
        return wrap(sys, user, family)
    }

    /** Parse "HEADING | query" lines into (heading, query) pairs. Rejects template
     *  echoes (a weak model often copies the literal words "HEADING"/"search query").
     *  Returns empty if nothing usable, so the caller can fall back to real topics. */
    fun parseOutline(text: String, question: String, max: Int): List<Pair<String, String>> {
        val out = ArrayList<Pair<String, String>>()
        for (raw in Prompt.stripThink(text).lines()) {
            val line = raw.trim().trimStart('-', '*', '•', ' ').trim()
            if ("|" !in line) continue
            val parts = line.split("|", limit = 2)
            val h = parts[0].trim().trim('*', '#', ' ').take(80)
            val q = parts.getOrNull(1)?.trim()?.take(120) ?: continue
            if (h.length <= 2 || q.length <= 2) continue
            if (isTemplateEcho(h) || isTemplateEcho(q)) continue
            out.add(h to q)
            if (out.size >= max) break
        }
        return out
    }

    private fun isTemplateEcho(s: String): Boolean {
        val l = s.lowercase().trim()
        return l == "heading" || l == "search query" || l == "query" || l.startsWith("heading ") ||
            l.contains("one per line") || l.contains("outline of")
    }

    /** Deterministic fallback outline: research the top distinct source topics the broad
     *  retrieval already found. Grounded by construction, needs no model-format compliance
     *  - so deep research still works with a weak model that can't follow the outline format. */
    fun sectionsFromTitles(titles: List<String>, k: Int, question: String): List<Pair<String, String>> {
        val distinct = titles.map { it.trim() }.filter { it.length in 3..60 }.distinct().take(k)
        return if (distinct.isEmpty()) listOf("Overview" to question)
        else distinct.map { it to "$question $it" }
    }

    /** Phase 3 - draft ONE section over the shared numbered [sourceBlock]. The block is
     *  identical across sections, so only [heading] + the output change (KV prefix reuse). */
    fun section(sourceBlock: String, heading: String, persona: String, safety: String?, family: String): String {
        val base = "Write ONE section of a research answer using ONLY the numbered sources. After each fact, " +
            "cite its source number in brackets like [3]. Use concrete specifics - names, numbers, dates, steps. " +
            "Two or three tight paragraphs, no heading, no preamble. If the sources do not support this section, " +
            "reply with EXACTLY: GAP: <what is missing>"
        val sys = buildString {
            if (persona.isNotBlank()) { append(persona.trim()); append(" ") }
            append(base)
            if (!safety.isNullOrBlank()) { append(" "); append(safety.trim()) }
        }
        return wrap(sys, "Sources:\n$sourceBlock\n\nSection to write: $heading", family)
    }

    /** Phase 4 - assemble the section drafts into one coherent, cited answer. */
    fun assembly(question: String, sections: List<Pair<String, String>>, persona: String, family: String): String {
        val sys = buildString {
            if (persona.isNotBlank()) { append(persona.trim()); append(" ") }
            append("Assemble ONE coherent research answer from the section drafts below. Begin with a 2–3 sentence " +
                "direct answer, then present each section under a '## Heading'. Preserve every [n] citation exactly; " +
                "never invent sources or renumber. If drafts conflict, add a short '## Reconciling the sources'. " +
                "For any draft that is a 'GAP:' line, omit that section and instead list it under a final " +
                "'## What the sources don't cover'. Don't repeat the question.")
        }
        val drafts = sections.joinToString("\n\n") { (h, d) -> "### $h\n$d" }
        return wrap(sys, "Question: $question\n\nSection drafts:\n$drafts", family)
    }
}
