<!--
  Why:  CLAUDE.md §5 — any decision or deviation from the SRS needs an ADR.
  What: issue 12.3 — screenshot coverage for the three critical flows, and the accessibility defect
        the first 200% render found.
  Result: a reader can see why Paparazzi is per-module, why recording must stay manual, and what the
          200% case is actually for.
  Changelog: 2026-10-02 — Created.
-->

# ADR-0066 — Screenshot coverage reaches the critical screens, and recording is never automatic

**Status:** Accepted · **Date:** 2026-10-02 · **Issue:** 12.3 · **SRS:** §21.5

## Context

§21.5 asks for Paparazzi screenshot tests on design-system components and critical flows in light,
dark and 200% font, with diffs gating merges.

Issue 1.8 built the machinery and 5.1 covered the dashboard. The survey found coverage at **two
modules and 11 baselines**: the design-system gallery and the dashboard. `verifyPaparazziDebug` was
already inside `unitTests` and named in CI, so AC2's "diffs block merges" was already true — for what
was covered.

What was not covered: **the three critical flows `CLAUDE.md` §4 actually names** — add a transaction
(≤ 3 taps), onboarding, and the purchase advisor. None had a single render. And the update flow was
not documented anywhere; `recordPaparazziDebug` appears only in old tracker rows.

## Decision

### 1 · Cover the three critical flows, three configurations each

15 new baselines across `:feature:onboarding`, `:feature:transactions` and `:feature:advisor`, taking
the project from 11 to 26. Each screen in light, dark and 200% font, because each catches something
different:

- **Dark** proves the screen is genuinely themed rather than a light render on a dark surface. A
  colour quietly falling back to a default looks fine until the two are side by side.
- **200%** is the accessibility case the Definition of Done asks for, and it is the one that finds
  real bugs — see below.

**There is no emulator in this project.** These renders are the only way anyone sees what the UI looks
like before it ships, which is why the coverage gap mattered more here than it would elsewhere.

### 2 · Paparazzi stays per-module, not in the feature convention plugin

Only the modules that need it apply it. Putting it in the convention plugin would add the renderer's
cost to all thirteen feature modules, including those with no screen worth freezing, and would make
the next module's author inherit a gate they did not choose. The plugin line carries a comment saying
so in each of the three.

### 3 · Tests render the stateless `*Content`, with no wrapper and no padding

Every `*Content` in this app already applies its own `fillMaxWidth().padding(CfoDimens.spaceMd)` and
arrangement. The dashboard's test records what happens otherwise: a wrapping `Column` in the harness
rendered every baseline at **double the real padding** — a picture of a screen the app never draws.
That mistake is now written into the guide so it is not made a fourth time.

The theme is **pinned** (`android:Theme.Material.Light.NoActionBar`) for the same reason the dashboard
pins it: the renderer's platform version decides the pixels, so leaving it floating makes every
baseline change under an unrelated SDK update — a diff nobody caused and nobody can review.

### 4 · Recording is never automatic, and that is the point

`recordPaparazziDebug` makes **any** diff disappear, including one nobody intended. A flag or a CI
step that refreshed baselines on every run would make every screenshot test pass for ever, agreeing
with whatever the UI had become — the same argument ADR-0064 makes about regenerating golden files,
and the sixth instance in this repository of "a gate that cannot fail".

So recording stays a deliberate local command, and the guide states the rules that make it safe:
record only the module you changed, look at the images, and commit baselines **with** the change that
caused them. A baseline diff separated from its cause cannot be reviewed.

## What the first 200% render found

**The purchase advisor's "Urgent" chip broke mid-word across three lines — `Ur` / `ge` / `nt`.** The
payment-method and urgency chips sat in a fixed `Row`, which squeezed the last chip instead of
wrapping it, and at a 200% font setting three chips are wider than a phone.

Fixed with `FlowRow` — the idiom `:feature:budgets` already used for exactly this — and the fix is
visible in the re-recorded baseline. **No unit test could have seen it:** the text is correct, the
semantics are correct, every assertion about the screen still passed. Only a render shows a word
falling apart.

That is the 200%-font case justifying its existence on the first screen it was pointed at.

## Alternatives rejected

| Alternative | Why not |
|---|---|
| Leave coverage at the dashboard | The three flows `CLAUDE.md` §4 calls critical had no render at all, and there is no emulator to see them on. |
| Put Paparazzi in the feature convention plugin | Thirteen modules pay the renderer's cost for the five that need it. |
| Render the `hiltViewModel()` screen | Needs a graph, and freezes DI wiring into a picture. The stateless `*Content` is the thing worth freezing. |
| Wrap the content in a `Column` with padding | Renders a screen the app never draws — the mistake issue 5.1 made and fixed. |
| Let the theme float | Baselines change under an unrelated SDK update: a diff nobody caused. |
| Record baselines in CI, or on a flag | Every screenshot test would pass for ever, agreeing with whatever the UI became. |
| Fix the chip overflow by shrinking the chip | Shrinking until a word falls apart is not a fix; wrapping to a second line is what the user needs. |
| Add locale renders for the new screens too | The dashboard already covers Hindi/Kannada/Tamil (issue 10.8), and 10.8's translation-coverage gate catches a missing string. Three more locales × three screens is nine baselines for little added signal — deferred deliberately. |

## Consequences

- 26 committed baselines across five modules; a visual diff blocks a merge, proven by changing a
  padding value and by deleting a baseline, each of which fails the build.
- The advisor's chip rows wrap at large font instead of breaking a word.
- Adding a screen to the coverage is documented, including the three mistakes already made here.
- **Deferred:** locale renders for the three new screens, and screenshot coverage for the remaining
  non-critical screens. Both are recorded here rather than left as apparent oversights.
