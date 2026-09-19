<!--
  Why:  CLAUDE.md §10 — one file per working session that changes code.
  What: issue 9.1 — AI-CLS Stage 2, stream classification (§8.2); opens Epic 9.
  Result: a reader can see what a stream is, why the score is exact, what §8.2 asks for that this
          does not yet do, and the two times a gate caught a mistake of mine.
  Changelog: 2026-09-19 — Created.
-->

# 2026-09-19 — AI-CLS Stage 2: fixed / semi-fixed / variable streams (issue 9.1, ADR-0042)

**Branch:** `feature/9-1-fixed-variable-nature-engine-ai-cls-stage-2` off `dev` (`22a903e`) ·
**VERSION** 0.8.3 → **0.9.1** (Epic 9 opens at 0.9.0) · **versionCode** 36 → 37 · **Schema** 22,
unchanged · **classification-kb.json** 1.3 → **1.4** · new engine `AI-CLS.stream` 1.0

---

## 1 · Decisions this session

The argument for each is in ADR-0042; the short form:

- **A stream is a Stage-1 category.** It's the unit the user chose and corrects. Split payments
  count by their lines (ADR-0018).
- **Nature stays AI-CLS-N's.** "Finalise nature" is read as "don't add a second writer". A stream's
  class is about recurrence, not about what the money became.
- **cv is over months with activity**, as §8.2's `n` is.
- **Exact `BigDecimal`, and the class is decided on the exact score.** Rounding each term to bps
  first moved a stream by 0.1 bp at a threshold.
- **Degenerate cadence** (fewer than two gaps, or a median gap of 0 days) **scores the worst**.
  Day-lock wraps over 31 days.
- **Known obligations match at transaction level:** a confirmed rule's merchant becomes its own
  FIXED stream; a rule-named category is flagged whole (see 2c). EMIs and premia have no link yet.
- **Pins are an engine input; there is no store and no UI.** The precedence is tested.
- **No `monthly_profile` table.** The totals are derived on read.
- **KB rows `CLS-STR-001..004`** carry versions and confidences, **in a `steps` array**. `order` was
  the first choice (see 2b).

## 2 · Where a gate caught me

**2a · The golden file didn't guard the `n ≥ 3` rule, although its header said it did.** Removing
the check from the engine left the golden test green: the two-month subscription record could never
reach 0.75 anyway, because two payments leave one gap and the worst cadence. Only the behaviour test
went red. I added a record that scores a perfect 1.0 in two months (payments on the 1st and 2nd),
corrected the header, and confirmed the same mutation now fails the golden test too.

**2b · `NatureKbDriftTest` failed when I added `stream_classification.order`.** That test reads
the **first** `"order"` key in the file, and mine now came first. I renamed my key to `steps` rather
than loosening someone else's gate.

**2c · On the device, the known-obligation override never fired.** The demo's dashboard read
**Fixed ₹0.00**. Part of that is honest: the demo has two closed months, and FIXED by score needs
three. But after confirming its "Landlord −₹28,000" recurring series, Fixed was *still* ₹0.00. A
detected rule is keyed by merchant and stores no `categoryId`, and my join was category-only. The
repository test passed only because it built its rule with a category. The fix follows §8.2's
wording ("*transactions* linked to recurring rules"): payments to a confirmed rule's merchant form
their own FIXED stream. I added a failing test shaped exactly like the device data (a detected rule,
no category, a trailing space in the merchant) first. After the fix the device reads **Fixed
₹28,000.00 · Semi-fixed ₹45,368.50** (₹73,368.50 before, less the rent), citing CLS-STR-002.

Two expectations I wrote by hand were also wrong: the class totals, and the side of a threshold a
score falls on. They were caught before the first run by computing the values in 50-digit Python
`decimal`.

## 3 · Flow changed this session

New `FLOW.md` §2.07:

```
DashboardViewModel.observeStreams()
└─ StreamRepository.observeStreams()     6 closed months; split-aware ledger rows + recurring rules
    └─ StreamEngine.classify()           pin → obligation → cold start → §8.2 score (exact)
  ⇣ uiState.streamProfile → StreamLoadSection ("Fixed · Semi-fixed · Flexible", estimate, rules)
```

## 4 · Code changed this session

| Path | What it does now |
|------|------------------|
| `domain/engines/stream/**` | **New module.** `StreamEngine` + types, `StreamRules` (KB mirror), `DefaultStreamEngine`, `ENGINE.md`; golden (16 records, oracle-generated), behaviour (20), drift (7) |
| `ai/knowledge/classification-kb.json` | v1.4: `CLS-STR-001..004` in `steps`, `window_months`, `day_lock_window_days`, `cold_start_min_months` |
| `ClassificationRules.kt`, `NatureRules.kt`, `core/model/Category.kt` | `KB_VERSION` 1.4 (no mirrored row changed) |
| `ai/orchestrator/engine-registry.yaml` | `AI-CLS.stream` 1.0; registry 1.1 |
| `data/repository/.../StreamRepository.kt` (+ test, 9) | The ledger → streams join; factory entry; module dependency |
| `app/.../di/RepositoryModule.kt` | Provides `StreamEngine` and `StreamRepository` |
| `feature/dashboard/.../StreamLoadSection.kt` (+ test, 5), `DashboardUiState/ViewModel/Screen`, `strings.xml` | The "Every month, typically" line |
| `feature/dashboard/src/test/**` | `FakeStreamRepository` (real engine), fixture, VM tests (+2), 5 re-recorded baselines |
| `settings.gradle.kts` | the module |
| `docs/adr/0042-…`, `DECISIONS.md`, `FLOW.md` | The decision, its index row, §2.07 |

## 5 · Quiz

**Outcome: not yet taken.** The answers in italics are the author's. Record the developer's own pass
here, fails included.

1. A stream scores 7 125.85 bps. It shows as 7 126. With the FIXED floor at 7 126, what is it, and
   why? *(SEMI_FIXED: the class is decided on the exact score.)*
2. Why can't two monthly payments ever be scored FIXED at the shipped weights, even without the
   n ≥ 3 rule? *(One gap means the worst cadence, so the score is at most 0.65.)*
3. Why is an unknown stream VARIABLE and not FIXED? *(FIXED would reserve money for an unproven
   commitment.)*
4. Why does the stream engine not set nature? *(AI-CLS-N is its only writer, per ADR-0016 and
   ADR-0042.)*
