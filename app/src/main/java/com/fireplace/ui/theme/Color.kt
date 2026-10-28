package com.fireplace.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * Fireplace palette, derived from the brand logo (a glowing hearth in the dark):
 * warm charcoal backgrounds + an ember/amber accent + a gold highlight. Dark is the
 * primary experience (it matches the logo); a warm light palette mirrors it.
 *
 * Structure follows a disciplined design system: one accent used sparingly, a 4-step
 * text hierarchy, two border tones, and tiered surface containers for depth.
 */

// ── Ember accent (from the logo's fire gradients) ──────────────────────
val Ember        = Color(0xFFFF8C2B)   // primary accent (bright ember)
val EmberBright  = Color(0xFFFFA64D)
val EmberDeep    = Color(0xFFC2560F)   // deeper ember, light-mode primary
val Gold         = Color(0xFFFFC24D)   // the flame's gold highlight
val GoldSoft     = Color(0xFFFFE1A8)
val HearthShell  = Color(0xFF16181D)   // the logo's dark shell / adaptive-icon bg

// ── Warm charcoals (dark theme) ────────────────────────────────────────
val Char0        = Color(0xFF0E0D10)   // lowest
val Char1        = Color(0xFF141317)   // background
val Char2        = Color(0xFF1B1A1F)   // surface container
val Char3        = Color(0xFF232128)   // high
val Char4        = Color(0xFF2E2B33)   // highest
val CharLine     = Color(0xFF302D35)   // outline
val CharLineSoft = Color(0xFF26242B)   // outline variant

// Warm off-whites / muted greys (dark-theme text)
val Warm0        = Color(0xFFF4F1EC)   // primary text
val Warm1        = Color(0xFFB7B3AB)   // secondary
val Warm2        = Color(0xFF86827A)   // muted
val Warm3        = Color(0xFF55524C)   // disabled

// ── Warm paper (light theme) ───────────────────────────────────────────
val Paper        = Color(0xFFFBF8F3)   // background
val PaperCard    = Color(0xFFFFFFFF)   // surface
val PaperCont    = Color(0xFFF2ECE3)   // container
val PaperCont2   = Color(0xFFEAE3D8)   // high
val PaperLine    = Color(0xFFE6DFD3)   // outline variant
val PaperLine2   = Color(0xFFD8D0C3)   // outline
val InkDark      = Color(0xFF221E18)   // primary text (warm near-black)
val InkMid       = Color(0xFF5E584F)   // secondary
val InkMuted     = Color(0xFF8C867C)   // muted

// ── Status (shared, tuned per theme) ───────────────────────────────────
val Grounded     = Color(0xFF4FC08D)   // "well grounded" green
val GroundedSoft = Color(0xFFA6E6C9)
val WarnGold     = Color(0xFFE6A42E)   // "partly grounded" amber
val BadEmber     = Color(0xFFF07059)   // error (warm red, not clashing with ember)
