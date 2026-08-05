<!--
  Why:  No concrete visual design existed yet (issue 1.8 "design system" is still Todo). This
        proposes a Material 3 token set — colours, fonts, typography — so UI work has a starting
        point instead of ad-hoc values. It is a PROPOSAL, to be reconciled with SRS §24.
  What: Design — colour & theme, fonts, typography.
  Result: A reader has concrete, token-based visual defaults and the rule that nothing is hardcoded.
  Changelog:
    2026-07-18 — Created (proposed M3 tokens; reconcile with SRS §24 at issue 1.8).
    2026-07-25 — Issue 1.8 implemented these tokens in :core:designsystem. This file is now
                 the rationale; the code is the source of truth (theme/Color.kt, Type.kt,
                 CfoDimens.kt). Contrast of every pair is asserted by ColorContrastTest.
-->

# AI Personal CFO — Design

> **Status: proposal.** No visual design is fixed yet — this is a starting point to be
> reconciled with **SRS §24** and materialised as **theme tokens** in
> [issue 1.8](issues/1.8-design-system-m3-theme-tokens-compose-charts.md). **Every value here is
> a token; nothing is hardcoded in a composable (§21.6).** Material 3, dark-mode and
> 200%-font ready. Accessibility is verified by the a11y + Paparazzi scans in issue 1.8, not
> asserted here.

## Colour & theme

Material 3 **semantic roles generated from one seed** by
[Material Theme Builder](https://m3.material.io/theme-builder) — do **not** hand-author the
full tonal palette. Pick the seed; the tool derives primary/secondary/tertiary + surfaces +
`onX` pairs for light and dark to meet contrast.

- **Seed:** `#00696E` — a deep trust-teal (money + calm + trustworthy; distinct from bank-blue).

Finance-specific roles used across the app (indicative light/dark — regenerate from the seed):

| Token | Light | Dark | Use |
|-------|-------|------|-----|
| `primary` / `onPrimary` | `#00696E` / `#FFFFFF` | `#4FD8DE` / `#00363A` | Key actions, active states |
| `background` / `onBackground` | `#F5FBFA` / `#191C1C` | `#191C1C` / `#E0E3E2` | App canvas |
| `surface` / `surfaceVariant` | `#F5FBFA` / `#DAE5E3` | `#191C1C` / `#3F4948` | Cards, sheets |
| `positive` / `onPositive` | `#146C2E` / `#FFFFFF` | `#7DDC97` / `#00390F` | Credit / income / gain |
| `negative` / `onNegative` | `#BA1A1A` / `#FFFFFF` | `#FFB4AB` / `#690005` | Debit / expense / loss |
| `warning` / `onWarning` | `#8A5000` / `#FFFFFF` | `#FFB86B` / `#4A2800` | Budget 80%+, cash crunch |

> **P-02 — never signal debit/credit by colour alone.** Always pair colour with a sign (`+`/`−`)
> and a label, so the meaning survives colour-blindness, greyscale, and privacy-blur.

## Fonts

- **Base family: Roboto / Roboto Flex** — it ships on every Android device, so **no font is
  bundled** (native platform over a dependency). This is the M3 default.
- **Amounts use tabular (monospaced) figures** (`fontFeatureSettings = "tnum"` / Roboto Flex
  `MONO` axis) so `₹` columns and running balances align vertically.
- *Inter* is noted only as an optional future brand upgrade — **not a dependency in v1.** Adding
  it needs an [ADR](adr/).

## Typography

Standard **Material 3 type scale** (Roboto), plus one app-specific **`amount`** style.

| Role | Size (sp) | Notes |
|------|-----------|-------|
| Display L / M / S | 57 / 45 / 36 | Hero figures (e.g. net worth on an empty dashboard) |
| Headline L / M / S | 32 / 28 / 24 | Screen titles |
| Title L / M / S | 22 / 16 / 14 | Card and section headers |
| Body L / M / S | 16 / 14 / 12 | Running text, list rows |
| Label L / M / S | 14 / 12 / 11 | Buttons, chips, captions |
| **`amount`** | inherits (Title/Body) | **tabular figures**, Indian grouping `₹1,23,456.78` |

- **Indian number formatting** (lakh/crore grouping, `₹1,23,456.78`) is applied wherever a
  `Money` value is shown; formatting is a UI concern — the value stays `Long` paise (MNY-001).
- Sizes scale with the system font setting; the design must hold at **200% font** (issue 1.8).
