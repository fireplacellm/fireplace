# Design: data pods + specialized agents

Fireplace is **fully offline / on-device**. This document specifies how the app
brings a user's own third-party data onto the device as private, citable "pods",
and how specialized agents reason over packs + pods. Everything here runs locally
- no cloud, no chain, no TEE, no remote sync.

## 1. Data pods - the pod data model

A pod is a `.fpack` (the same verified format as a knowledge pack, see
[ADR 0004](adr/0004-pack-format.md)) with a structured internal model:

- **Scope** = `{source}.{category}[.{subcategory}]`, e.g. `strava.activities`,
  `health.sleep`, `notes.entries`. Supports `*` / `prefix.*` for grants. The scope
  is the unit of consent and the unit shown in the audit log.
- **Records** are flat and typed - `id`, `timestamp`, a few typed fields, and ≥1
  natural-language field (title/body) - not free text, and content-addressed so a
  re-import is idempotent.
- **Provenance** (recorded in the manifest + per pod): scope, collected-at, size,
  producer. Gives the model source/scope/freshness for citation for free.
- **Reserved `system.*` scopes** are hidden from agents and grants.

## 2. Connectors - declarative, import-based

A **connector** is a declarative manifest: display metadata, its `{source}.{category}`
scope, how the data is acquired, and (for file imports) the file types to offer.
Acquisition is **import-based** (never scraping - there's no browser on-device):

- **Acquire (on-device):** official **export archives** (Google Takeout, Strava,
  Spotify/Instagram/LinkedIn `.zip`) via the Android file picker / SAF, plus native
  sources (**Health Connect**, files, notes).
- **Normalize:** a pure `raw → Record[]` step, content-addressed for idempotent
  re-import/dedup, with an honest `success|partial|degraded` result.
- **Passage descriptor** - one declarative rule per scope drives BOTH the FTS5
  index and the dense-vector table:
  ```
  passages:  { fields: [...], template: "On {date}: {activity}, {distance} ..." }
  metadata:  { date, source, type }         # filter columns
  title/uri: for citation
  ```
- **Catalog:** a bundled, versioned, SHA-256'd connector catalog so normalizers can
  be updated independently of the app.

## 3. Consent + privacy

Single-user, but still consent-logged:

- Every import is gated by a **plain-language consent sheet**: which scope,
  access mode (single-use / continuous), retention, time-range. The owner can
  narrow scope before allowing it.
- **Always-on local audit log** of what read, imported, or deleted which scope.
- **Android Keystore** for per-scope keys (derive-don't-store via HKDF of a master
  Keystore key), so "forget this" is real erasure and pods are encrypted at rest.
- Import UX: scope chips, a preview of records before they land, and delete-any-time.

## 4. Specialized agents

Each agent is a **declarative persona (config, not code)**:
```
{ id, persona, data_scope: [pack/pod globs], suggestions,
  modes: [insight|chat], safety_line }
```
Three knobs: **persona** (expert identity + grounding rule + domain safety line),
**data scope** (least-privilege packs/pods - the consent surface), and **mode**:

- **insight** - a one-shot structured summary over the user's own records:
  "reference the actual numbers, no generic advice", cited.
- **chat** - interactive, grounded, cited.
- **proactive** (later) - a nightly on-device distillation pass that turns raw
  records into memory cards.

**Grounding gate from day one:** every claim cites a source id, or it is flagged -
this is what stops a small local model fabricating numbers.

**Agent catalog:** research (knowledge packs), health (sleep/activity - "not medical
advice"), fitness (workouts), crypto/trading (educational only; keys never enter the
model context).

**4-tier memory** (local SQLite + vectors): Tier0 raw records → Tier1 signals →
Tier2 distilled memory (with revision chains) → Tier3 profile/goals. A nightly
on-device background job distills raw → memory.

## 5. Build order (incremental, each shippable)

1. **Modes** - Research vs Chat. *(done)*
2. **Chat history + persistence** (local SQLite). *(done)*
3. **Onboarding / setup**: intro carousel, model picker, pack/pod picker. *(in progress)*
4. **Connectors** → import archives (Strava/Takeout/etc.) + Health Connect into
   scope-structured pods. *(done)*
5. **Consent sheet + audit log** *(done)* **+ Keystore per-scope encryption** *(in progress)*.
6. **Specialized agents** (insight mode first): research, health, fitness, crypto,
   trading. *(done)*
7. **4-tier memory + nightly distillation** (proactive cards).
8. **Grounding/evaluator gate** hardened throughout.
