<!--
  Why:  issue 12.3 — AC3 asks for the update flow to be documented, and AC1/AC2 for coverage of the
        design system and the critical screens with diffs gating merges.
  What: what is covered, how a diff blocks a merge, and how to re-record a baseline safely.
  Result: someone changing a screen knows what to run and what to look at before committing.
  Changelog: 2026-10-02 — Created for issue 12.3.
-->

# Screenshot tests

**This project has no emulator in CI.** Paparazzi renders Compose on the JVM, which is the only
reason any visual coverage exists here at all — and it means these renders are the **only** way anyone
sees what the UI actually looks like before it ships.

Verify everything with `./gradlew verifyPaparazziDebug`. It is part of `unitTests`, so a visual diff
**blocks a merge** like any other failing test, and it is a named step in CI.

---

## 1 · What is covered

| Module | Baselines | What it renders |
|---|---|---|
| `:core:designsystem` | 3 | every component and both charts, as one gallery — light, dark, 200% |
| `:feature:dashboard` | 8 | loaded, empty, blurred — light, dark, 200%, and Hindi/Kannada/Tamil |
| `:feature:onboarding` | 5 | the welcome step (the privacy pledge) and quick setup — light, dark, 200% |
| `:feature:transactions` | 5 | add-transaction empty and filled — light, dark, 200% |
| `:feature:advisor` | 5 | the purchase advisor's three verdicts — light, dark, 200% |

**26 committed baselines.** The three feature modules besides the dashboard were added by issue 12.3,
which is what `CLAUDE.md` §4's "critical flows" — add a transaction, onboarding, the purchase advisor —
actually means in pictures.

Every screen gets **light, dark and 200% font**, because each catches something different:

- **Dark** proves the screen is genuinely themed rather than a light render on a dark surface. A colour
  silently falling back to a default looks fine until you see the two side by side.
- **200%** is the accessibility case the Definition of Done asks for, and it is the one that finds real
  bugs — see §4.

## 2 · Re-recording a baseline

When you change a screen on purpose, its baseline is now wrong and the build is red. Re-record:

```bash
./gradlew :feature:advisor:recordPaparazziDebug     # the module you changed
./gradlew verifyPaparazziDebug                      # confirm everything else still matches
```

Then **look at the images** before committing:

```bash
git diff --stat -- '*/snapshots/*'   # which baselines moved
git status -- '*/snapshots/*'
```

### The rule that matters

**A re-recorded baseline is not a passing test — it is a new claim about what the app looks like.**
`recordPaparazziDebug` makes any diff disappear, including one you did not intend, so recording to go
green is the screenshot equivalent of deleting a failing assertion.

- Open the changed PNGs and look at them. If you cannot say *why* each pixel moved, do not commit it.
- Re-record **only the module you changed.** A run across all five sweeps up unrelated drift.
- Commit the baselines **with the code change that caused them**, never in a follow-up commit. A
  baseline diff separated from its cause cannot be reviewed.
- If a baseline changes and you did not touch that screen, that is a finding — a shared token or a
  component moved. Investigate before recording.

### Why recording is not automatic

Same argument as the golden-file harness (ADR-0064): a flag that refreshed baselines on every run would
make every screenshot test pass for ever, agreeing with whatever the UI had become. The diff **is** the
review.

## 3 · Adding a screen

1. Apply the plugin in that module: `alias(libs.plugins.paparazzi)`. It is deliberately **not** in the
   feature convention plugin — only modules that need it pay the build cost.
2. Write the test against the **stateless** `*Content` composable, never the `hiltViewModel()` one.
3. **Do not wrap it in a `Column` or add padding.** Every `*Content` in this app already applies its
   own; wrapping renders a screen the app never draws. The dashboard's test records the day that
   happened — every baseline was at double the real padding.
4. Pin the theme (`android:Theme.Material.Light.NoActionBar`). The renderer's platform version decides
   the pixels, so leaving it floating makes baselines change under an unrelated SDK update — a diff
   nobody caused and nobody can review.
5. Cover light, dark and `fontScale = 2.0f`.
6. `recordPaparazziDebug`, **look at the images**, and commit them with the test.

## 4 · What this has actually caught

- **Issue 12.3, the advisor at 200% font:** the "Urgent" chip broke mid-word across three lines —
  `Ur` / `ge` / `nt` — because the chips sat in a fixed `Row` that squeezed the last one instead of
  wrapping. Fixed with `FlowRow` (the same idiom `:feature:budgets` already used), and the fix is
  visible in the re-recorded baseline. No unit test could have seen it.
- **Issue 5.1, the dashboard harness:** a wrapping `Column` in the test meant every baseline rendered
  at double the real padding — a picture of a screen the app never drew. Found by review.
- **Issue 10.8:** the Hindi, Kannada and Tamil dashboards are only ever seen here.
