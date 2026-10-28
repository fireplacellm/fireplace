# ADR 0006 - Personal data pods

Status: accepted · 2026-10-21

## Decision

Let the user import their own data - health/fitness from **Health Connect**, or any **CSV/JSON export** (Strava, Google Fit, Apple Health, a workout log) - and turn it into a private **data pod**: a `.fpack` built **on the device**. A pod flows through the exact same verify → retrieve → ground → cite → verify pipeline as a knowledge pack, so the user can ask "how far did I run this week?" or "has my sleep improved?" and get an answer grounded in, and cited to, their own records.

## Why

- It is Fireplace's unique capability: none of the bounty field does personal-data import for offline, cited insights.
- It reuses everything we already built - because a pod **is** a `.fpack`, no new retrieval/grounding/citation code is needed.
- It stays within the offline guarantee: **Health Connect is fully on-device** and file import is local, so reading them makes **no network request**. Pods never leave the phone.

## How it works

- `pods/PodBuilder.kt` builds the `.fpack` on device: each record → one title-prefixed FTS5 passage → signed manifest (hashes) → zip. Marked `personal: true`.
- `pods/HealthImporter.kt` reads Health Connect (exercise, steps, sleep) over a date range into records. Runtime health permissions; nothing read without consent.
- `pods/FileImporter.kt` parses CSV/JSON; fitness-aware (date/type/distance/duration/calories/HR) with a generic field:value fallback so any export works.
- The pod lands in the packs dir and is loaded like any pack; `PackReader` verifies its hashes before use.

## Bounty scope

Data pods are **additive and optional** - the core offline research app is unchanged and remains the submission's focus (Vitalik asked for a research/lookup tool, not a personal-insights app). Pods are our product differentiator and the long-term moat, surfaced without compromising the core.

## Privacy

- Health permissions are health-scoped, **not** network - the offline gate (ADR 0003) still passes.
- Pods are device-local, excluded from backup (ADR 0003 extraction rules), and never shared anywhere by the app.
