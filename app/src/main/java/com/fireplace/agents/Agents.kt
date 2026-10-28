package com.fireplace.agents

/**
 * Declarative specialized agents.
 *
 * An [Agent] is a persona/config - data, NOT code. Each one shapes three things
 * without touching the inference pipeline:
 *   1. persona      - an expert-identity + grounding-rule sentence that the
 *                     ViewModel weaves into the grounded ([com.fireplace.rag.Prompt.SYSTEM])
 *                     or chat ([com.fireplace.rag.Prompt.buildChat]) system prompt.
 *   2. data scope   - which knowledge packs this agent may draw on, via
 *                     [scopeGlobs] (matched with [Agent.matchesScope]).
 *   3. suggestions  - starter prompts shown on the empty screen.
 * Plus a domain [safetyLine] (shown in the UI and appended to the persona) and a
 * [groundByDefault] hint that picks RESEARCH vs CHAT mode when the agent is chosen.
 *
 * Adding a specialist is therefore a data edit here - no new model, prompt or
 * retrieval code. The ViewModel owns the actual prompt assembly.
 */
data class Agent(
    val id: String,                 // stable id, e.g. "research", "health"
    val name: String,               // display name, e.g. "Research"
    val emoji: String,              // e.g. "🔬"
    val tagline: String,            // one line shown under the name in the picker
    val persona: String,            // expert-identity + grounding-rule woven into the system prompt
    val scopeGlobs: List<String>,   // pack/pod id globs this agent may use; "*" matches all
    val suggestions: List<String>,  // 3-4 starter prompts shown on the empty screen
    val safetyLine: String? = null, // domain safety disclaimer; shown in UI + appended to persona
    val groundByDefault: Boolean = true, // true → default to RESEARCH (cited) mode when picked
) {
    /** True if a pack/pod id is in this agent's data scope (any glob matches). */
    fun matchesScope(packId: String): Boolean = scopeGlobs.any { globMatches(it, packId) }

    private companion object {
        /** Simple glob: "*" matches everything; otherwise escape the literal
         *  parts and turn each `*` into `.*`; no `*` means an exact match. */
        fun globMatches(glob: String, value: String): Boolean {
            if (glob == "*") return true
            if (!glob.contains('*')) return glob == value
            val re = glob.split('*').joinToString(".*") { Regex.escape(it) }
            return Regex(re).matches(value)
        }
    }
}

/** The built-in agent personas. Order is the picker order; [default] is first. */
object AgentCatalog {
    val all: List<Agent> = listOf(
        Agent(
            id = "research",
            name = "General Research",
            emoji = "🔬",
            tagline = "Grounded answers from your knowledge packs",
            persona =
                "You are an impartial research assistant. Answer strictly from the installed " +
                "knowledge packs and cite every fact. If the packs don't cover something, say so " +
                "plainly and flag any name, date or number you are unsure of.",
            // Scoped to the broad Wikipedia pack so general answers aren't skewed by the
            // focused packs (e.g. arXiv titles matching "season"). Topic depth lives in the
            // dedicated agents below. Falls back to all packs if Wikipedia isn't installed.
            scopeGlobs = listOf("wikipedia"),
            suggestions = listOf(
                "Settle a debate: are viruses alive?",
                "Trace how a raindrop becomes tap water",
                "Why did bronze come before iron?",
                "What actually happens during a solar eclipse?",
            ),
            safetyLine = null,
            groundByDefault = true,
        ),
        Agent(
            id = "firstaid",
            name = "First Aid",
            emoji = "⛑️",
            tagline = "Emergency & first-aid guidance, offline",
            persona =
                "You are a calm first-aid guide. Ground every step strictly in the installed " +
                "first-aid pack and cite it. Give clear, ordered steps. Always tell the user to " +
                "call emergency services for anything serious - you supplement, never replace, " +
                "professional care.",
            // Scoped to the first-aid pack so emergency steps aren't diluted by general Wikipedia.
            scopeGlobs = listOf("first-aid*"),
            suggestions = listOf(
                "Someone is choking - what do I do right now?",
                "How do I treat a severe bleeding wound?",
                "Signs of a stroke and what to do",
                "How do I perform CPR on an adult?",
            ),
            safetyLine = "Not a substitute for professional medical help - call emergency services in a real emergency.",
            groundByDefault = true,
        ),
        Agent(
            id = "crypto",
            name = "Crypto Research",
            emoji = "🪙",
            tagline = "Learn crypto & web3 from your packs",
            persona =
                "You are a neutral crypto and web3 explainer. Ground answers in the installed " +
                "crypto knowledge pack, define terms plainly, and cite. Explain mechanisms and " +
                "trade-offs; never give buy or sell calls.",
            // Scoped to the crypto pack (falls back to all packs only if it isn't installed).
            scopeGlobs = listOf("crypto*"),
            suggestions = listOf(
                "Explain a rollup like I run a corner shop",
                "Where does the 'gas' I pay actually go?",
                "What actually is decentralized finance (DeFi)?",
                "What breaks when a stablecoin loses its peg?",
            ),
            safetyLine = "Educational only - not financial advice. Keys and funds are never shared with the model.",
            groundByDefault = true,
        ),
        Agent(
            id = "travel",
            name = "Travel Guide",
            emoji = "🧭",
            tagline = "Offline destination guides",
            persona =
                "You are an offline travel guide. Ground every suggestion in the installed travel " +
                "pack and cite it - districts, sights, getting around, local tips. If the pack " +
                "doesn't cover a place, say so plainly.",
            // Scoped to the travel pack so place guides aren't diluted by general Wikipedia.
            scopeGlobs = listOf("travel*"),
            suggestions = listOf(
                "What should I see in Kyoto?",
                "How do I get around Istanbul?",
                "A first-timer's day in Lisbon",
                "Neighborhoods to stay in in Mexico City",
            ),
            safetyLine = null,
            groundByDefault = true,
        ),
        Agent(
            id = "papers",
            name = "Research Papers",
            emoji = "📄",
            tagline = "AI/ML papers, grounded & cited",
            persona =
                "You are a research-paper explainer. Ground answers strictly in the installed " +
                "arXiv paper pack and cite each claim. Summarize methods and findings faithfully; " +
                "if the papers don't cover something, say so rather than guessing.",
            // Scoped to the arXiv pack so paper answers aren't diluted by general Wikipedia.
            scopeGlobs = listOf("arxiv*"),
            suggestions = listOf(
                "What is the transformer attention mechanism?",
                "How does contrastive learning work?",
                "Explain diffusion models for image generation",
                "What problem does batch normalization solve?",
            ),
            safetyLine = null,
            groundByDefault = true,
        ),
    )

    /** The general Research agent - used as the fallback everywhere. */
    val default: Agent = all.first()

    /** Look up by id; returns [default] if nothing matches. */
    fun byId(id: String): Agent = all.firstOrNull { it.id == id } ?: default
}
