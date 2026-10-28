# Demo queries - examples that show grounding beats a bare small model

These are good queries to show on camera. The point of Fireplace is not that a 0.5-1.5B
model is smart on its own (it isn't). It's that **retrieving from the packs, citing, and
verifying** turns a small model into a trustworthy research tool. The queries below are
chosen so a bare small model (no retrieval) gets them vague, wrong, or hallucinated, while
Fireplace answers from a real source with the exact text shown.

Tip for the recording: toggle **Chat** mode (no retrieval) vs **Research** mode on the same
question to show the difference live.

**Verified on-device** (0.5B, 5 GB phone): photosynthesis, "why did bronze come before iron?",
"what happens during a solar eclipse?", Mexico City neighborhoods, and decentralized finance all
returned well-grounded, cited answers. The rest are strong picks in the same vein; give any you
plan to feature a quick dry run first, since a specific figure only shows if that pack contains it.

---

## 1. Specific numbers (a bare small model hallucinates the figure)

| Query | Agent | A bare small model | Fireplace (verified on the 0.5B) |
|---|---|---|---|
| **What is photosynthesis?** | General | Gives a textbook sentence, no real figures | Writes the mechanism AND cites specifics from the source: *"about 48-60% of GPP is consumed in plant respiration, with about 32-35% remaining as net primary production (NPP) [3]"* - numbers a bare small model does not have |
| **Why did bronze come before iron?** | General | Vague dates, often conflates the two ages | Explains the switch and cites the Bronze Age / Iron Age passages (shown verbatim in Sources) |
| **What is the CPR compression rate and depth for an adult?** | First Aid | May state a wrong rate (a safety risk) | Cites the rate and depth from the first-aid pack |

**Why it lands on camera:** the figures appear in the answer AND in the "Sources" block below
it, verbatim. Any number the sources don't contain is flagged by the verifier, so the badge
only reads "well grounded" when the specifics are real.

---

## 2. Safety-critical steps (where a wrong answer is dangerous)

- **Someone is choking - what do I do right now?** (First Aid)
- **How do I treat a severe bleeding wound?** (First Aid)
- **Signs of a stroke and what to do** (First Aid)

A bare small model can invent plausible-but-wrong first-aid steps. Fireplace grounds every step
in the first-aid pack and shows the source, so the steps are the real ones, and it tells you to
call emergency services. This is the strongest "why grounding matters" moment.

---

## 3. Niche / technical knowledge (outside a small model's parameters)

- **What is the transformer attention mechanism?** (Research Papers) - cites an arXiv paper, not a hand-wave.
- **How does contrastive learning work?** (Research Papers)
- **What actually is decentralized finance (DeFi)?** (Crypto) - cites the DeFi article.
- **What breaks when a stablecoin loses its peg?** (Crypto) - mechanism, cited.

A bare small model is shaky here and cannot cite. Fireplace retrieves the actual passage and
every claim carries a `[n]` you can tap to read the source text.

---

## 4. Explanation & synthesis (not just fact lookup)

- **Why did bronze come before iron?** (General)
- **Trace how a raindrop becomes tap water** (General)
- **What actually happens during a solar eclipse?** (General) - a bare model often mixes up solar vs lunar; Fireplace cites the Solar eclipse article and gets the Moon-between-Earth-and-Sun geometry right.
- **Settle a debate: are viruses alive?** (General)

---

## 5. Deep Research mode (the multi-section showcase)

Turn on the **Deep** toggle and ask a comparison:

- **Compare proof of work and proof of stake** (Crypto)
- **How do bronze and iron metallurgy differ, and why did the switch happen?** (General)

Fireplace plans sections, researches each from the pack, and assembles one structured, cited
answer with a "what the sources don't cover" note. This is the clearest way to show depth.

---

## What to point out while recording

1. The **instant cited preview** (~2 s) before the model even starts writing.
2. The **"Well grounded - % verified in sources"** badge.
3. The **Sources** section with the **exact passage** each claim came from.
4. The **Continue exploring** follow-ups.
5. **Airplane mode on the whole time** - say it out loud; it is the headline.

Note: exact answer wording varies by model and run. The 1.5B writes fuller prose than the 0.5B;
on a 12 GB phone the 3B / Gemma-2 2B models are both faster and more capable.
