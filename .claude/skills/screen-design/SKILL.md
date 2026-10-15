---
name: screen-design
description: Design or review an AI Personal CFO screen in the v2 design language — one answer per screen, a meaning line under every figure, the work one tap away, pace drawn not described, one primary action, trust visible in the chrome. Use when adding or changing any :feature:* screen or :core:designsystem component, when a screen feels crowded or unreadable, when choosing what a screen leads with, or when the user says "design", "redesign", "UI", "layout", "screen", "make it friendlier", "tokens", "spacing".
---

# Skill: Screen design (v2)

The visual spec is [`docs/design/design-language-v2.html`](../../../docs/design/design-language-v2.html)
— six screens on demo data, the token table, and the seven moves that define v2. **Open it before
designing anything.** This file is how to apply it; that file is what it looks like.

v2 is **not a repaint**. The Material 3 trust-teal seed, the semantic roles and Roboto all stay. What
changed is hierarchy, restraint and where the evidence lives.

## The seven moves (apply in this order)

1. **One answer per screen.** Decide the single figure the screen exists to deliver, and give it the
   largest type on the screen. Everything else is subordinate or on another tab. If you cannot name
   that one figure, the screen is not designed yet.
2. **A meaning line under every figure.** Built from the same engine result, never written freehand:
   `₹18,420.00` → "₹1,180 a day until 31 October, after rent, EMIs and your ₹15,000 emergency
   floor". Data above, answer below.
3. **The work is one tap.** An inline disclosure on the card — inputs, the rule id **and version**,
   the engine and when it ran. Never a separate evidence screen; v1 had one and nobody reached it.
4. **Pace is drawn.** One bar carries spent, the even-pace marker and the overshoot in the warning
   tone. The projection stays in words, because that is the part a person acts on.
5. **One primary action.** Filled button for it; text for everything else. Chips at 44dp minimum, no
   dropdown where chips fit.
6. **Trust in the chrome.** `offline · all local` in the status line, consent rows showing grant and
   revoke dates, the erase stating what it cannot reach.
7. **Calm surfaces.** Two surface levels, one hairline instead of shadows, 20dp card / 16dp button /
   999dp chip, amounts in tabular figures larger than anything near them.

## Navigation (added after the prototype got this wrong twice)

- **A link hangs off the thing it explains.** The pace bar opens budgets; an account row opens that
  account; an insight opens the screen that proves it. **Never a shelf of chips** — a row of eight
  equally weighted links is the failure v2 exists to fix, and it is easy to rebuild by accident.
- **One mechanism.** Everything navigational is a route; actions are actions. Two mechanisms mean
  reachability cannot be checked, and an unreachable screen is invisible until a user looks for it.
- **A stack per tab.** `account → holdings → back` returns to the account. A tab switch keeps the
  other tab's depth.
- **Every screen reachable within three taps of a tab**, and reachable at all: a screen only the
  developer can find is a screen nobody uses.
- **A screen that does not exist yet says so** where it is linked, rather than being hidden. One
  list drives the tag; there is never a second list to drift.

## Non-negotiables (these outrank any design preference)

- **Colour never signals alone** (P-02). Every semantic colour is paired with a sign (`+` / `−`) or a
  word, so meaning survives greyscale, colour-blindness and the privacy blur.
- **No figure without provenance within one tap** (P-02, AI-ARC-003).
- **Every user-visible string is in `strings.xml`** with ICU plurals — the meaning lines too, because
  they ship in Hindi, Kannada and Tamil. `CfoHardcodedUiString` fails the build in `:feature:*`.
- **Money through `MoneyFormatter`** — Indian grouping, tabular figures, never a hand-rolled format,
  never a `Double` (MNY-001).
- **No fixed heights.** The design must hold at 200% font and in dark mode; both are screenshot-tested.
- **Amounts respect the privacy blur**, including in the widget.
- **Demo data in every mock and screenshot.** Never a real balance, merchant or transaction.

## How to build it

1. Read the spec page, then the screen's `UiState` (ARC-004: one immutable data class as
   `StateFlow`, events up via a sealed interface).
2. Decide the one answer, and which engine result supplies both the figure and its meaning line.
3. Reach for the existing components before adding one: `CfoAmountText`, `CfoProportionSegment`,
   `PrivacyBlur`, `Charts`, `Components.kt`, `CfoDimens`. A new component needs a reason the
   existing ones cannot cover.
4. Take tokens from `CfoTheme` only — `Color.kt`, `Type.kt`, `CfoDimens.kt`. A literal colour or dp
   in a composable is a review failure.
5. Follow the `compose-ui` skill for the state/event plumbing and the Paparazzi test; this skill only
   decides what the screen says and how it is ordered.
6. Record the screenshot baselines in light, dark and 200% font (`verifyPaparazziDebug` is in
   `unitTests`, so a stale baseline fails the build).

## Changing a token

Edit `Color.kt` / `Type.kt` / `CfoDimens.kt` **and** the token table in the spec page in the same
commit, then re-record the affected Paparazzi baselines. Two copies of a token drift; this project
has found that failure mode five times in other places.

A change to the seed, the font family, or any rule in "Non-negotiables" needs an **ADR** — it is a
deviation from `docs/Design.md` and SRS §24, not a preference.

## Reviewing a screen (the checklist)

- [ ] Can you name the one answer this screen delivers, without reading the code?
- [ ] Does every figure have a meaning line, and does that line come from the same result?
- [ ] Is the work reachable in one tap, with the rule id and version in it?
- [ ] Exactly one filled button?
- [ ] Does every semantic colour carry a sign or a word?
- [ ] Every string in `strings.xml`, every colour and dimension from a token?
- [ ] Does it survive 200% font, dark mode, and the privacy blur?
- [ ] Do the Paparazzi baselines match what you intended, rather than what was there before?

## Definition of done

The screen leads with one answer; every figure explains itself and can show its working; tokens and
strings are externalised; light, dark and 200%-font baselines are recorded; and the `compose-ui`
skill's state/event rules hold.
