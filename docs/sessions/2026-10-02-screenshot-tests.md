<!--
  Why:  CLAUDE.md §10 — one file per working session that changes code.
  What: issue 12.3 — screenshot coverage for the three critical flows, and the accessibility defect
        the first 200% render found.
  Result: a reader can see why the gap mattered, why recording stays manual, and what a render
          catches that no unit test can.
  Changelog: 2026-10-02 — Created.
-->

# 2026-10-02 — Seeing the screens that matter most (issue 12.3, ADR-0066)

**Branch:** `feature/12-3-paparazzi-screenshot-tests` off `dev` (`72cda98`)
**Versions:**
- **VERSION** 0.12.2 → **0.12.3**
- **versionCode** 60 → 61
- **Schema** 29 → **29 (unchanged)**

---

## 1 · Decisions this session

The full argument for each is in [ADR-0066](../adr/0066-screenshot-coverage-reaches-the-critical-screens-and-recording-is-never-automatic.md).

- **Cover the three critical flows** `CLAUDE.md` §4 names — add-transaction, onboarding, the purchase
  advisor. The survey found coverage at two modules and 11 baselines, and **none of the three had a
  single render**. 26 baselines now.
- **Light, dark and 200% each**, because each catches something different. Dark proves a screen is
  genuinely themed rather than a light render on a dark surface; 200% is the accessibility case, and it
  is the one that finds real bugs.
- **Paparazzi stays per-module**, not in the feature convention plugin: thirteen feature modules should
  not pay the renderer's cost for the five that need it.
- **Render the stateless `*Content`, with no wrapper and no padding.** Every `*Content` already applies
  its own; wrapping renders a screen the app never draws — the mistake issue 5.1 made, which put every
  dashboard baseline at double the real padding. Now written into the guide.
- **Pin the theme**, or the renderer's platform version makes baselines move under an unrelated SDK
  update — a diff nobody caused and nobody can review.
- **Recording stays manual.** `recordPaparazziDebug` makes *any* diff disappear, including one nobody
  intended. A flag or CI step that refreshed baselines would make every screenshot test pass for ever,
  agreeing with whatever the UI had become — the same argument ADR-0064 makes about golden files, and
  the sixth "gate that cannot fail" in this repository.

**What this found.**

1. **A real accessibility defect, on the very first render taken.** The purchase advisor's "Urgent"
   chip broke mid-word across three lines — `Ur` / `ge` / `nt` — because the payment-method and urgency
   chips sat in a fixed `Row` that squeezed the last one instead of wrapping. Fixed with `FlowRow`, the
   idiom `:feature:budgets` already used.

   **No unit test could have caught it.** The text was correct, the semantics were correct, every
   existing assertion on that screen passed. Only a picture shows a word falling apart.
2. **A comment that had never been checked.** `OnboardingContent` says it is scrollable because "at a
   200% font setting the quick-setup step is taller than a phone" — written in issue 2.1, with nothing
   rendering it until now. The render confirms it: Back, Next and "Skip for now" are all reachable.
3. **The survey corrected the scope.** AC2 ("diffs block merges") was **already satisfied** —
   `verifyPaparazziDebug` has been inside `unitTests` and named in CI since 1.8 — and the 200% scale was
   already right at `2.0f`. The real gaps were the three uncovered flows and the undocumented update
   flow, which is less than the issue text implies and worth saying.
4. **One mutation had to be thrown away rather than counted.** My first attempt to prove the gate
   reverted the `FlowRow` fix, but ktlintFormat had already removed the now-unused `Row` import, so it
   failed to compile rather than failing the diff. Replaced with two that do compile — a padding change
   and a deleted baseline — both of which fail the build.

## 2 · Flow changed this session

`FLOW.md` §2.29 — the only path in this project that produces a picture of the app:

```
./gradlew verifyPaparazziDebug      inside unitTests ⇒ a visual diff BLOCKS a merge
├─ :core:designsystem    3   ├─ :feature:onboarding    5  ← new
├─ :feature:dashboard    8   ├─ :feature:transactions  5  ← new
                             └─ :feature:advisor       5  ← new

each renders the STATELESS *Content — no wrapper, no padding, theme pinned
light · dark · fontScale = 2.0f

recordPaparazziDebug is deliberately MANUAL
   ⇣ it makes ANY diff disappear, so a flag would make every test agree with whatever the UI became
```

## 3 · Code changed this session

| Path | What it does now |
|------|------------------|
| `feature/advisor/.../AdvisorScreen.kt` | both chip rows wrap with `FlowRow` instead of squeezing a chip until its word breaks |
| `feature/advisor/src/test/.../AdvisorScreenshotTest.kt` (new) | 5 baselines — three verdicts, light/dark/200% |
| `feature/onboarding/src/test/.../OnboardingScreenshotTest.kt` (new) | 5 baselines — the pledge and quick setup |
| `feature/transactions/src/test/.../AddTransactionScreenshotTest.kt` (new) | 5 baselines — empty and filled |
| 3 × `feature/*/build.gradle.kts` | Paparazzi applied per module, with the reason |
| 15 × `*/src/test/snapshots/images/*.png` (new) | the committed baselines, each one opened and inspected |
| `docs/testing/screenshot-tests.md` (new) | what is covered, how to re-record safely, and what this has caught |
| `docs/adr/0066-…`, `DECISIONS.md`, `FLOW.md` §2.29, `CHANGELOG.md`, `docs/memory.md`, `VERSION` | the records |
