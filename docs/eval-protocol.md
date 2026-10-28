# Evaluation protocol

We measure Fireplace against the bounty's bar - ">50% as good as internet search + a frontier model" - and we hold ourselves to an honest standard: no self-grading, and no tuning on our own test set.

## Question sets

- **`eval/questions/dev.jsonl`** - development set. We look at these, tune on them, and iterate. Growing toward ~60 questions spanning the bounty's distribution: travel, places, first-aid, crypto, and non-recall tasks (explanation, comparison, synthesis, reasoning).
- **`eval/questions/heldout.jsonl`** - held-out set. **We never tune on it.** It is sealed: `eval/questions/heldout.seal` is its SHA-256 at seal time, committed to git. `harness.check_seal` refuses to score the held-out set unless the file still matches the seal. So our held-out numbers cannot be the result of quietly editing questions after seeing how we do. Changing the set requires an explicit `--seal`, visible in git history.

## Baseline

Each question also gets a reference answer from a **frontier model with live web search** (the thing we are claiming to be >50% as good as). Reference answers are generated once, offline from the app, and stored with the question.

## Grading

- A **blind, pairwise** judge compares Fireplace's answer to the reference, position-swapped to cancel order bias, scoring 0..1 as "fraction of the reference's quality" on a published rubric (accuracy, completeness, grounding, usefulness).
- **Known bias we disclose:** if the judge is from the same model family as the reference, that is a conflict; we report the judge model and, where feasible, cross-check a sample with a second family. We do not claim a human or Vitalik verdict unless one is actually given.
- Every answer must carry citations; the judge penalizes uncited or unsupported claims, and we separately report the **citation-verification pass rate** (ADR 0005 / CitationVerifier).

## Reported numbers

Headline numbers are computed **on the real 12 GB device**, not a desktop or emulator, and always include both quality and speed:

| Metric | What |
|---|---|
| mean_score (dev / held-out) | fraction of frontier+web baseline |
| pass_bar_0.5 | mean_score ≥ 0.5 |
| latency p50 / p90 | time to full answer, on device |
| time-to-first-token | on device |
| tokens/sec, draft-acceptance | on device (speculative decoding) |
| citation pass rate | fraction of claims verified against a source |

Scores are easy to inflate by (a) tuning on the test set, (b) desktop/GPU grading passed off as phone performance, (c) scorers that cap the baseline. We avoid all three and say so.

## Reproducing

`python -m harness --set dev` / `--set heldout` with the real system plugged in as a `SystemUnderTest` (the CI run uses stubs to exercise the harness itself). Grading inputs, outputs, and the rubric are committed so a third party can recompute every number.
