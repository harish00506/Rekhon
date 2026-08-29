# ADR-0022 — The privacy blur masks text rather than blurring pixels, and sets FLAG_SECURE while it is on

- **Status:** accepted
- **Date:** 2026-08-16
- **Deciders:** Harish G
- **SRS refs:** §23; FR-PRIV-*, P-01, P-02; issue 5.3, and issue 11.2 which this deliberately stops short of

## Context

FR-PRIV-* asks for "a one-tap privacy blur that hides all amounts on screen (and in the
widget/screenshots) for shoulder-surfing and screen-sharing safety". The word *blur* names a visual
effect, and Compose has one — `Modifier.blur` — so the obvious reading is: render the screen, blur
it, done. Three things make that the wrong implementation.

**It does not exist below API 31.** `Modifier.blur` is backed by `RenderEffect` and degrades to a
**silent no-op** on older devices. A privacy feature whose failure mode is "looks like it worked,
did nothing" is worse than no feature: the user has been told their figures are hidden.

**It does not stop a capture.** A blurred surface is still the surface that a screenshot, a screen
recording or a shared video call records. Half of what the requirement names — screen-sharing — is
untouched by any amount of pixel blurring.

**It makes the app unusable.** Blur is a property of a region, not of a word. Blurring the whole
screen hides the labels, the category names and the navigation along with the amounts; blurring only
the amounts needs a per-amount effect, which is the same forty call sites as masking, at a much
higher rendering cost, and still leaves a legible smear on large figures.

Separately, there is a scope question. §5.3's criteria say the blur is "combined with FLAG_SECURE
(11.2)". Issue 11.2 is Todo and unbuilt, so the criterion cannot be met by depending on it.

## Decision

**The blur replaces the text.** A `LocalPrivacyBlur` composition local carries the flag; the two
functions that render money read it and return `₹•••••••` instead of the figure. The mask is
**fixed-width** — every amount masks to the same string regardless of magnitude — and keeps its sign
and currency symbol.

**5.3 sets `FLAG_SECURE` while the blur is on**, from the same flag that drives the mask, via a
`DisposableEffect` that clears it on the way out. Issue 11.2 keeps the policy questions: whether the
flag should be permanent, which screens are exempt, and what the recents thumbnail should show.

**Editable amount fields are exempt** — the add-transaction amount, the account editor's opening
balance, the budget editor, the transaction filter's bounds.

## Consequences

- **Positive:** works on every supported API level, defeats screenshots and screen-sharing, and
  leaves the app fully navigable while blurred — labels, dates, categories and the rule citations
  stay readable, so the user can still see *what* the screen is telling them, only not how much.
- **Positive:** testable as an assertion rather than an eyeball. `DashboardPrivacyBlurTest` sweeps
  every rendered string for a rupee-plus-digit and fails naming what escaped, which a pixel blur
  could never support.
- **Negative / cost:** it is not automatic. Every new read-only amount must go through
  `CfoAmountText` or `maskedAmount(...)`; a future screen that reaches for `MoneyFormatter.format`
  in a composable leaks. Mitigated by the whole-screen sweep on the dashboard, but that test only
  covers the dashboard — the other screens rely on the convention holding.
- **Negative / cost:** the masked value carries no information at all, so a user cannot tell a large
  balance from a small one at a glance. That is the point, and it is the reason the mask is
  fixed-width rather than digit-shaped.
- **Follow-ups:** issue 5.5's Glance widget must read the same setting — 5.3 cannot honour that
  criterion because `:widget` is still a `ModulePlaceholder`. Issue 11.2 supersedes the FLAG_SECURE
  decision here. A lint rule banning `MoneyFormatter.format` inside a `@Composable` in `:feature:*`
  would turn the convention into a gate; not built, and worth its own issue.

## Alternatives considered

- **`Modifier.blur` over the content** — rejected: API-31+, silent no-op below it, does not stop a
  capture, and blurs the navigation along with the figures.
- **Masking inside `MoneyFormatter`** — rejected: it lives in `:core:model`, is not a composable, and
  cannot see a composition local; giving it global mutable state would make every formatting call
  order-dependent and would reach the notification and export paths that need real figures.
- **Replacing amounts in the ViewModel** — rejected: every `UiState` would carry pre-formatted
  strings instead of `Money`, which loses the type MNY-001 exists to protect and makes screenshot
  fixtures unable to render a real amount.
- **A settings-screen toggle** — rejected for this issue: there is no `:feature:settings`, and three
  taps is not "one tap" when someone has just sat down beside you.
- **Pulling all of 11.2 forward** — rejected: the recents guard and the always-on policy are a whole
  issue's worth of decisions, and duplicating them here would mean deciding them twice.

## Compliance with golden rules

- **P-01:** this *is* a privacy control. Nothing leaves the device; the flag is stored in the
  existing Proto DataStore settings (`privacy_blur_enabled`, reserved since issue 1.9).
- **P-02:** the sign survives the mask, so direction is never conveyed by colour alone — the rule
  `CfoAmountText` already documents. The rule citations and labels stay on screen while blurred, so
  the card still shows *what* it is claiming.
- **P-03:** no figure is computed or altered; the engines are untouched, and the mask is applied at
  the render boundary only.
- **P-04:** no network on this path; the setting is local.
- **Accessibility (§21.6):** a masked amount announces "Amount hidden" rather than reading the figure
  aloud, and the toggle reports its state through `stateDescription` rather than a changed icon
  alone.
- **Fail-open, deliberately:** an unreadable settings store leaves amounts **visible**. The blur is a
  display preference, not a security boundary — that is the app lock (SEC-002), which fails closed.
