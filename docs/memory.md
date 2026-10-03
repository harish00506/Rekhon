<!--
  Why:  A single, cheap-to-update place that says where the project actually is — what's done,
        what's in flight, what's next — so any session (human or agent) can resume without
        re-deriving state from git and 85 issue files.
  What: Living progress tracker for the codebase.
  Result: A reader knows the current version, completed work, the file in progress, and next up.
  Changelog:
    2026-07-18 — Created. Baseline: Epic 0 (blueprint) done; no Kotlin code yet.
    2026-09-17 — Issue 7.5 merged to dev; Epic 7 complete.
    2026-09-18 — Issue 8.1 (E2EE backup) merged to dev; Epic 8 opened at 0.8.0.
    2026-09-18 — Issue 8.2 (restore on fresh device) merged to dev.
    2026-09-19 — Issue 8.3 (restore drill) merged to dev; Epic 8 complete.
    2026-09-19 — Issue 9.1 (AI-CLS Stage 2) merged to dev; Epic 9 opened at 0.9.0.
    2026-09-19 — Issue 9.2 (AI-FCT forecast) merged to dev.
    2026-09-19 — Issue 9.3 (AI-SEAS seasonality) merged to dev.
    2026-09-20 — Issue 9.4 (AI-FHS health score) merged to dev.
    2026-09-20 — Issue 9.5 (AI-ORCH insight orchestrator + feed) merged to dev; schema 23.
    2026-09-21 — Issue 9.6 (AI-NTF notification policy) merged to dev; schema 24.
    2026-09-23 — Issue 9.7 (AI-GRD numeric guardrail) merged to dev; Epic 9 complete.
    2026-09-25 — Issue 10.1 (AI-PA Purchase Advisor + trace card) merged to dev; schema 25; Epic 10 opened at 0.10.0.
    2026-09-26 — Issue 10.2 (AI-PA-INT buy list + adaptive interview) merged to dev; schema 26.
    2026-09-26 — Issue 10.3 (AI-SIM what-if simulators) merged to dev; no schema change.
    2026-09-26 — Issue 10.4 (AI-VEH vehicle maintenance) merged to dev; schema 27.
    2026-09-27 — Issue 10.5 (AI-CHAT assistant + tool registry) merged to dev; schema 28.
    2026-09-27 — Issue 10.6 (the frozen guardrail eval) merged to dev; no schema change.
    2026-09-27 — Issue 10.7 (AI-MKT opportunity score) merged to dev; schema 29.
    2026-09-28 — Issue 10.8 (Hindi, Kannada and Tamil) merged to dev; schema 29 unchanged.
    2026-09-28 — Issue 11.1 (StrongBox + a rotation that re-keys the file) merged to dev; schema 29 unchanged.
    2026-09-28 — Issue 11.2 (FLAG_SECURE always on) merged to dev; no schema change.
    2026-10-01 — Issue 11.3 (the consents dashboard) merged to dev; no schema change.
-->

# AI Personal CFO — Project Memory

> **Update this on every shipped issue and whenever you switch what you're working on.** Keep it
> short. This is the *project progress* log — distinct from the agent's own memory dir at
> `~/.claude/.../memory/`. Detail lives in [`../CHANGELOG.md`](../CHANGELOG.md) and the per-issue
> trackers in [`issues/`](issues/); the static roadmap is [`phase.md`](phase.md).

## Current state

- **Version:** `0.13.0` (see [`../VERSION`](../VERSION)) · **Phase:** 2–4. **Schema is v30** (13.1's `household` + `profile.household_id`; v29 was 10.7's `market_close`; 10.8 and 11.1 add no tables — a language tag in settings, and a second key slot beside the wrapped passphrase).
- **Epics 1–8 are done; Epic 9 is open** — 9.1 (stream classification), 9.2 (the cash-flow
  forecast), 9.3 (seasonality), 9.4 (the health score), 9.5 (the insight orchestrator and its
  feed), 9.6 (the notification policy) and 9.7 (the numeric guardrail) shipped — **Epic 9 is
  complete**. **Epic 10 is open**: 10.1 (the Purchase Advisor), 10.2 (the buy list),
  10.3 (the what-if simulators), 10.4 (vehicle maintenance), 10.5 (the chat assistant),
  10.6 (the frozen guardrail eval), 10.7 (the opportunity score) and 10.8 (Hindi, Kannada and
  Tamil) shipped — **Epic 10 is complete**. The app now ships in four languages; the three
  translations are machine-authored and **await a native review** (ADR-0056).
  **Epic 11 is open**: 11.1 (the database key — StrongBox, and a rotation that re-keys the file),
  11.2 (the screen-capture guard), 11.3 (the consents dashboard), 11.4 (the crypto-shredding erase)
  11.5 (DPDP alignment), 11.6 (R8 + OSV scanning) and 11.7 (the no-hand-rolled-crypto audit)
  shipped — **Epic 11 is complete**. **Epic 12 is complete too**: 12.1 (the golden-file/property
  harness), 12.2 (versioned AI-eval datasets that report their scores), 12.3 (screenshot coverage for
  the critical screens), 12.4 (the offline E2E gate) and 12.5 (the release train).
  **Epic 13 is open**: 13.1 (household mode's foundation) shipped — the schema supports
  household → profiles, scoping is enforced by a test over every `@Query`, the aggregation views are
  specified, and `HouseholdMode.IS_ENABLED` is **false** (ADR-0069). Note `0.11.x` is deliberately unused — Epic 11
  shipped as `0.10.8`–`0.10.14` by drift, recorded in `CHANGELOG.md` rather than renumbered.
  Deliberately
  unfinished: the key can be rotated but nothing offers it yet, `FLAG_SECURE` does not reach the
  home-screen widget, the erase cannot reach a backup the user exported (it says so), the consent
  record is the latest grant/withdraw pair rather than an append-only history, and **four DPDP
  obligations are the publisher's, not the code's** — grievance contact, nomination, breach notice
  and children's data, all listed as open in `docs/compliance/dpdp-2023.md` —
  ADR-0057/0058/0059/0060/0061/0062/0063. Two more: a release stack trace now needs `mapping.txt`
  and nothing archives it yet (11.6), and Argon2id stays BouncyCastle's until Tink ships a
  password-based KDF (ADR-0039, re-read by 11.7's audit).
- **Currently working file:** none. Issues **8.1–8.3, 9.1–9.7, 10.1–10.8, 11.1–11.7 and 12.1–12.5 are merged to `dev`**
  ([8.1 tracker](issues/8.1-e2ee-backup-argon2id-aes-256-gcm-tracker.md), ADR-0039;
  [8.2 tracker](issues/8.2-restore-on-fresh-device-tracker.md), ADR-0040;
  [8.3 tracker](issues/8.3-backup-restore-drill-tracker.md), ADR-0041;
  [9.1 tracker](issues/9.1-fixed-variable-nature-engine-ai-cls-stage-2-tracker.md), ADR-0042;
  [9.2 tracker](issues/9.2-cash-flow-forecast-ai-fct-tracker.md), ADR-0043;
  [9.3 tracker](issues/9.3-seasonality-ai-seas-tracker.md), ADR-0044;
  [9.4 tracker](issues/9.4-financial-health-score-ai-fhs-tracker.md), ADR-0045;
  [9.5 tracker](issues/9.5-insight-orchestrator-feed-tracker.md), ADR-0046;
  [9.6 tracker](issues/9.6-notification-engine-policy-tracker.md), ADR-0047;
  [9.7 tracker](issues/9.7-guardrail-ai-arc-004-tracker.md), ADR-0048;
  [10.1 tracker](issues/10.1-purchase-advisor-ai-pa-trace-card-tracker.md), ADR-0049;
  [10.2 tracker](issues/10.2-buy-list-adaptive-interview-ai-pa-int-tracker.md), ADR-0050;
  [10.3 tracker](issues/10.3-simulators-prepay-vs-invest-payoff-tracker.md), ADR-0051;
  [10.4 tracker](issues/10.4-vehicle-maintenance-prediction-ai-veh-tracker.md), ADR-0052;
  [10.5 tracker](issues/10.5-chat-assistant-on-device-llm-tool-registry-tracker.md), ADR-0053;
  [10.6 tracker](issues/10.6-chat-guardrail-eval-tracker.md), ADR-0054;
  [10.7 tracker](issues/10.7-market-signal-engine-ai-mkt-opportunity-screen-tracker.md), ADR-0055).
- **`origin/dev` is current again** — `23acb26` (issue 10.1), pushed 2026-09-25. Everything from
  7.4 through 10.1 that had been stranded locally is on the remote. Earlier sessions recorded the
  push as blocked for want of credentials; it works now, so check `git log origin/dev..dev` rather
  than assuming either state.
- **This machine builds with Temurin JDK 21** (`~/.jdks/temurin-21`) and the SDK at `~/Android/Sdk`;
  `local.properties` points there. Gradle stays at **8.13**: JDK 25 would need Gradle ≥ 9.1, AGP 8.x
  stops working at Gradle 9.6, and Hilt 2.56.2's transforms are ambiguous under Gradle 9. Emulator:
  **`CfoTest`** (API 36, Google APIs x86_64).
- **Check `git log dev` against `VERSION` before starting an issue**, not just the issue tracker.
  `dev` was two issues behind once and nobody noticed.
- **The forecast exists now (9.2), but the goals still use the observed P50 surplus** (ADR-0035,
  ADR-0037). Switching `SurplusRepository` to `ForecastRepository` is ADR-0043's recorded follow-up.

### What 10.7 changed that a future issue must know

- **`market_close` is the app's own price history**, appended by `MarketPriceRepository.refresh()`.
  One row per instrument per day, unique. Never write two rows for one day, and never backfill it
  with numbers the device did not observe without deciding that deliberately (ADR-0055).
- **A signal AI-MKT cannot evaluate is reported, not scored zero** — and `possibleScore` shrinks.
  Any new signal must follow that, or the score silently starts meaning something else.
- **The percentile is a mid-rank.** Strictly-below put a flat market in the bottom decile. If you
  touch `MarketMath.percentile`, the flat-series test is the one that matters.
- **The hit rate is walk-forward and withheld below 20 samples.** Both are KB numbers. A rate
  computed over the whole series would measure a machine that can see the future.
- **The tranche gates are other engines' verdicts** (AI-STS, AI-EMF, AI-FCT) and AI-MKT re-derives
  none of them. Add a gate by adding a published figure, not a calculation.
- **`market_status` and `opportunity_check` are still unserved chat tools.** Wiring them is a chat
  question — how a verdict reads in a sentence — not a market one.
- **An engine that reads `series.last()` needs an empty-series path.** This one crashed without it,
  on a holding the app had never priced, and a repository test found it rather than the engine suite.

### What 10.6 changed that a future issue must know

- **`ai/eval/guardrail-eval.json` is frozen.** Add a case with a **new id**; never edit an existing
  row. A duplicate id fails the harness, and ids are how a failure is discussed in a commit.
- **`fabricated_blocked_pct` is 100 and is not negotiable.** `honest_answered_pct` is the one with
  room, and lowering it has to be argued in the commit that does it.
- **Any number a sentence contains is a claim.** Two bugs of this shape in two days — the health
  score's "out of 1000" and the forecast's "90 days" — were both constants in string resources with
  no engine behind them. If a template needs a number, the tool publishes it. There are none left;
  a one-line script over `strings.xml` will tell you if that changes.
- **The eval runs through `ChatEngine.compose`, not AI-GRD directly.** What is measured is what
  reaches a user, which includes the chat layer dropping a blocked reply and its figures.
- **Issue 12.2 owns the other §21.5 datasets** (categorisation ≥ 92%, receipts ≥ 95%, forecast
  backtests). They need real labelled data; inventing it would measure the invention.

### What 10.5 changed that a future issue must know

- **The model can never be the source of a figure, and that is structural.** `ChatEngine.compose`
  builds AI-GRD's allowlist from the tool results and nothing else. Do not add a path that shows a
  draft without composing it, and do not widen the allowlist to "things the screen also knows".
- **`ChatToolExecutor` is the complete list of what chat may see.** Adding a capability means adding
  an executor there — a reviewable act. Nine of the fifteen registered tools deliberately fail.
- **`VerbalisationDraft` is §19.4's context pack.** When cloud assist is built, **that type is the
  whole payload**. Keep it that way; do not pass the question or the ledger into it.
- **Routing lives in `ai/skills/tool-registry.json` (1.1), not in a `when`.** Teaching a new phrase
  is a JSON edit plus the mirror, held together by a drift test — and the module declares the file
  as a test input, without which the gate silently does not run.
- **The out-of-scope check runs before the intent match.** A test pins the order; do not "optimise"
  it away.
- **`chat_message` is excluded from the archive and has no tombstone** (CHT-004). Both exemptions
  are argued in `ProfileSnapshot.EXCLUDED` and `MigrationSafetyTest`'s map. A new profile-scoped
  table still needs the opposite: a tombstone, a wipe entry and a residue count.
- **`:ml:llm` is an Android library on purpose** — its sentences are string resources, so 10.8's
  Hindi pass reaches the assistant's own words.
- **10.6 (the guardrail eval) now has something to evaluate**: feed it drafts and the tool results
  they were built from.

### What 10.4 changed that a future issue must know

- **A drift test is vacuous until its knowledge base is a declared test input.** The module's
  `build.gradle.kts` names `ai/knowledge/vehicle-maintenance-kb.json` as an input to `Test`; without
  that line Gradle leaves the task `UP-TO-DATE` when only the JSON changes, and two deliberate
  drifts passed. **Every module that mirrors a file in `ai/` needs the same line** — check it when
  you add one.
- **Readings are rows, one per vehicle per day, unique.** A correction replaces; a duplicate would
  weight the median slope towards that day. Do not add a "current odometer" column.
- **The KB has cadences, not premiums.** A renewal enters the forecast only with the price the user
  recorded. Do not seed a default premium.
- **`ItemSource.VEHICLE_PREDICTION` exists** and every `when` over `ItemSource` must handle it — the
  compiler enforces that a new source has words before it can ship.
- **Do not re-add a horizon filter to `ForecastRepository.vehicleItems`.** The engine builds only the
  days inside its own window; a second filter is a second definition, and the first draft's was
  provably unreachable.
- **The demo wipe and `countRowsFor` now cover all eight of Epic 10's tables.** Any new
  profile-scoped table must be added to both, or the residue check silently lies.
- **RULE-20-4-10 is still unconsumed.** It belongs to AI-PA and needs a `kind` on `PurchaseRequest`.

### What 10.3 changed that a future issue must know

- **`:domain:engines:simulator` deliberately depends on `:domain:engines:loan`** — the one sanctioned
  exception to ADR-0046's "an engine imports no other engine", because EMI and amortisation are the
  loan engine's to own. A test pins a single debt against `LoanEngine.schedule`; **if it goes red,
  two definitions of a month's interest have appeared** — fix the definition, not the test.
- **`SimulatorMath.settle` is the one place the rounding rule lives:** a residue under **1% of a
  payment** clears with it. Both simulators call it. Skipping it costs a phantom extra month.
- **`SimulatorRepository` has no write methods, by design (P-07).** Do not add one; a simulation is
  a question, and the screen promises in words that nothing moved.
- **A card with no APR, statement balance or minimum due is excluded from the payoff plan** (P-03).
  If a future issue records card APRs by default, those cards start appearing — that is intended.
- **`PayoffPlan.order` is the order debts are *cleared*, not the order they are targeted.** Under
  avalanche a small debt can clear from its own minimum first.
- **Nothing was minted.** RULE-PREPAY-VS-INVEST and RULE-PAYOFF-ORDER only decide what is *shown*;
  their thresholds are the user's own inputs.

### What 10.2 changed that a future issue must know

- **AI-PA-INT is a second engine in `:domain:engines:purchase`.** It weighs a purchase the same way
  AI-PA does; adding a question means adding to the bank **and** to `BuyListLabels`, which will not
  compile until both are done.
- **Schema is 26** — `wishlist_item` and `interview_answer`, the latter unique on
  `(profile, item, question)`, so changing an answer replaces it. Each row keeps the points it was
  worth at the time (AI-ARC-006), while the live score is recomputed from today's rules.
- **The app never removes a wish.** A low score changes what is said, not the list; a test asserts
  it at the repository. Keep it that way (§13.3, P-07).
- **`target_price_minor` and `last_interviewed_at_utc_millis` are stored but unused** — they are
  where the deferred buy-timing watch and the thirty-day re-ask will read from.
- ~~**10.3's simulators should reuse `PurchaseRequest`**~~ — they did not, and should not: a
  what-if is about a debt, not a purchase. AI-SIM takes its own inputs (ADR-0051).
- **rules-kb is 1.23.0**; fourteen `*Rules.kt` mirrors restate it. The **vehicle KB is 1.1** (`VehicleKnowledge.BUNDLED`), the **tool registry is 1.1** (`ToolRegistry.BUNDLED`) and the **signal library is 1.1** (`MarketKnowledge.BUNDLED`).

### What 10.1 changed that a future issue must know

- **AI-PA reads signals, never other engines.** To add a gate, add a field to `PurchaseSignals` and
  map it in `StoredPurchaseAdvisorRepository.signals()`; do not import another engine module into
  `:domain:engines:purchase` (ADR-0046's rule, kept here).
- **Schema is 25** — `purchase_trace` and `purchase_trace_gate`, both with tombstones, both in the
  archive, the demo wipe and the restore drill. The card's own citations are a column, because
  RULE-COOL-OFF belongs to no gate.
- **The timing gate has no signal yet.** `cheaperMonth` is always `null` until AI-SEAS's factors are
  wired; the gate itself is built and tested.
- **10.2's buy list should reuse `PurchaseRequest` and the category path** — the budget gate already
  reads `request.categoryId`, and the screen has no category picker yet.
- **rules-kb is 1.21.0**; eleven `*Rules.kt` mirrors restate it.

### What 9.7 changed that a future issue must know

- **`core:model.NumericGuardrail` is gone.** Every figure in user-facing text goes through the
  injected `GuardrailEngine` (`:domain:engines:guardrail`, AI-GRD). A new sender builds a
  `GuardrailEvidence` from the engine values behind its words and shows nothing unless the verdict
  is `Pass`.
- **Verification is an allowlist of renderings**, so adding a way to write a figure (FX, a new date
  format) is a change to `AllowedRenderings` **and** `RULE-GRD-TRANSFORMS`, never a loosened
  comparison.
- **Epic 10's chat inherits the ladder**: the engine answers `Regenerate` with the offending spans
  and `Refuse` with the verified ones. The words for both — the prompt and the user-facing fallback
  (GRD-005) — are the chat layer's, in `strings.xml`.
- **rules-kb is 1.20.0**; ten `*Rules.kt` mirrors restate it.

### What 9.6 changed that a future issue must know

- **Every notification goes through `NotificationRepository.decide` — ask before you claim.** A new
  sender builds a `NotificationCandidate(key, kind)`, posts only `plan.deliverable`, and claims its
  own row only for those. Claiming first would lose every alert the policy holds.
- **Schema is 24** — `notification_log`, one row per key per profile, no tombstone (argued in
  `MigrationSafetyTest` like `insight`). A delivery is recorded *before* posting.
- **All seven §17.1 channels exist** (`CfoNotifications.channels()`); a new `NotificationKind` needs
  a channel or `CfoNotificationsTest` fails.
- **The weekly digest is not built** — folded messages stay in the feed and are re-offered. Per-type
  in-app switches, the learned quiet window, notification actions and the transaction trigger are
  ADR-0047's deferrals.
- **rules-kb is 1.19.0**; nine `*Rules.kt` mirrors restate it.

### What 9.5 changed that a future issue must know

- **Schema is 23** — the `insight` table. It is exempt from the soft-delete invariant, argued in
  `MigrationSafetyTest`: a tombstone would hold the fingerprint's unique slot for ever, so the card
  could never be raised again. A dismissal is a `status` plus `suppressed_until_iso_date`.
- **AI-ORCH ranks; it never computes.** To raise a new kind of insight, add a signal type and a
  collector in `:domain:engines:insight` and map it in `InsightSignals` — do not import another
  engine's module into it.
- **The feed is written, not derived on read.** `InsightRepository.refresh()` runs the pipeline;
  the screen reads rows. 9.6's notification engine should read the same rows rather than recompute.
- **The debounced transaction trigger and the weekly deep job are 9.6's** to build with its
  scheduler — do not add a second debounce.
- **rules-kb is 1.18.0**; eight `*Rules.kt` mirrors restate it.

### What 9.4 changed that a future issue must know

- **AI-FHS is live but has no memory.** The score is recomputed on every read; §14's weekly
  cadence, the movement with its cause list and the what-if slider all wait on a snapshots table.
- **A pillar with no data is "—" and re-weighted.** Never score a missing pillar as zero; the
  Protection pillar has no signal at all in v1.0.
- **rules-kb is 1.17.0**; seven `*Rules.kt` mirrors restate it. `RULE-FHS-SIGNALS` deliberately does
  **not** restate the utilisation and savings tops — they are read from RULE-CC-UTIL and
  RULE-SAVE-RATE, and a drift test asserts they stay absent.
- **`HealthSignals` owns the join rules** (EMI counted once, statement not live utilisation,
  liability payments are spending). Add a signal there, not in the engine.

### What 9.3 changed that a future issue must know

- **The calendar KB's one mirror is in `:domain:engines:seasonality`** (`SeasonalityPriors`,
  `SeasonalityRules` = SEAS-INDEX). The budget engine depends on it; do not add a second copy.
- **AI-FCT is 1.1:** `ForecastDay.seasonal`, `CashFlowForecast.seasonalAdjustment/seasonalMonths`.
  Its evidence carries AI-SEAS's when an adjustment applies.
- **Category names match the KB exactly** (case aside) — the demo's "Dining Out" gets no prior.
- **Seasonal factors below ×1 are real** (the lookback's season divided out) and are shown as savings.

### What 9.2 changed that a future issue must know

- **`ForecastRepository.observeForecast()`** — 90 days, P10/P50/P90, crunch days, components. Its
  scheduled items are confirmed rules + FIXED streams + future-dated rows; its everyday spend is
  liquid outflow minus those. **Anything scheduled elsewhere must also be removed from the pool.**
- **The backtest coverage gate sits at 70.3% against 70%.** A model change that lowers it fails the
  build — that is the gate working; do not relax it.
- **rules-kb is 1.16.0**; six `*Rules.kt` mirrors restate it. `LIQUID_ACCOUNT_TYPES` is the one
  liquid definition (emergency fund and forecast).
- **The crunch alert waits for 9.6.**

### What 9.1 changed that a future issue must know

- **`StreamRepository.observeStreams()` is 9.2's and 9.4's input** — `fixedLoad`,
  `semiFixedExpected`, `variableBudgetable`, and a verdict per stream with provenance
  (`AI-CLS.stream` 1.0). Streams are Stage-1 categories, **plus one `recurring:<merchant>` stream per
  confirmed detected recurring rule**.
- **A confirmed detected recurring rule stores no `categoryId`** (issue 3.7 keys it by merchant).
  Anything joining obligations to categories must match the merchant too — found only on the device.
- **FIXED by score needs n ≥ 3 closed months**, so the demo (two closed months) shows Fixed ₹0.00
  until a recurring series is confirmed. That is correct, not a bug.
- **`classification-kb.json` is 1.4.** Three mirrors restate it (`ClassificationRules`,
  `NatureRules`, `CategorySeed`), plus `StreamRules`. `NatureKbDriftTest` reads the **first**
  `"order"` key in the file, so never add another `"order"` array above `nature_classification`.
- **Pins have no store or UI yet** (ADR-0042) — the engine precedence is ready.

### What 8.3 changed that a future issue must know

- **A new table fails the restore drill until it is seeded** in
  `data/repository/src/sharedTest/.../DrillFixture.kt`. That is the point — it is the fourth edit a
  new table needs (entity, archive, demo wipe, residue count, **and now the drill fixture**).
- **`./gradlew restoreDrill` is a release gate** before every `dev → stage` and `stage → main`
  (needs a device). CI's `restore-drill` job runs it on an emulator but **only blocks once branch
  protection lists "Backup restore drill (release gate)" as required** — never verified from here.
- **DRL-001's twice-yearly in-app drill is deferred** (ADR-0041): build it when `backups_log`, the
  insight feed and a re-openable backup exist.

### What 8.2 changed that a future issue must know

- **Restore = `BackupCipher.open` → `ArchiveRepository.import`**, no second path. Settings holds it;
  **on a new phone onboarding comes first** (skippable) — restoring from onboarding is an open gap
  (ADR-0040), as are the settings seeds, app lock and receipt images, which the archive never carried.
- **The archive import now refuses another profile's archive (`archive.profile`)** — a demo backup used
  to wipe the real profile and "succeed" over an empty app. Any backup made for testing must be made
  **outside the demo** or it cannot be restored into the real profile.
- **`:data:repository` has an instrumented source set now** (`BackupRestoreDeviceTest`), with the
  AndroidX runner configured — the module no longer crashes with 0 tests under `connectedDebugAndroidTest`.
- **`CfoSmokeTest` needs a clean install**; after any manual device run, `pm clear` before the suite.
- **Emulator keyboard trap:** the first `input text` into a field can open Gboard's "Try out your
  stylus" sheet and swallow the text. Cancel it (bottom-centre) and type again.
- **Onboarding step 4 still says "this build has no settings screen"** — untrue since FR-SET-001.
  Pre-existing copy, not fixed in 8.2.

### What 8.1 changed that a future issue must know

- **The backup format is `"CFOB" | v1 | memKiB | iters | lanes | saltLen | salt` (31 B, the GCM AAD)
  then Tink's `nonce | ct | tag`**, sealed in `:core:crypto`'s `BackupCipher`. **`open` has no
  production caller yet — 8.2 wires it**, and must apply the plaintext through
  `ArchiveRepository.import` (already atomic) rather than a second restore path.
  Errors: `Crypto("backup.open")` (wrong passphrase *or* tamper, deliberately one code),
  `Validation("backup.format" | "backup.version")`.
- **Argon2id comes from BouncyCastle, the only sanctioned non-Tink crypto** (ADR-0039). Issue 11.7's
  audit should allow `BackupKdf` and fail any other `org.bouncycastle` import.
- **BouncyCastle must resolve as one family.** Root `build.gradle.kts` aligns every
  `org.bouncycastle:*-jdk18on` (bar `:lint`) to the catalog version — Robolectric's older
  `bcpkix`/`bcutil` broke 15 dashboard tests when only `bcprov` moved, and forcing it into `:lint`
  broke lint's own tests. The shared convention config also excludes
  `META-INF/versions/9/OSGI-INF/MANIFEST.MF` (bcprov + jspecify both ship it).
- **The whole backup sits behind `CLOUD_BACKUP`** (label: "Save encrypted backups off this device"),
  including the file-picker path — unlike the plaintext export.
- **The device-made file can be opened without the app**: OpenSSL 3.5's `kdf … ARGON2ID` plus
  Python's `cryptography` `AESGCM` — the recipe is in the 8.1 tracker. Useful for 8.2/8.3 fixtures.
- **`connectedDebugAndroidTest` crashes (0 tests) on every module with no androidTest sources** —
  pre-existing, not a regression; only `:app`, `:core:database` and `:feature:onboarding` have tests.
  Run with `--max-workers=4` on this machine or D8 can OOM on the androidTest dex merge.

### What 7.5 changed that a future issue must know

- **The card editor had no APR field** — `credit_card.apr_bps` sat in the schema from 6.1 with
  nothing in the UI to write it, found by following 7.5's own "add the rate" button on the device.
  **Fixed the same day (0.7.6)**: the field exists, the button is back, and a partial card section is
  now a validation error rather than a silent drop. The 0.3.6 lesson again: a plumbed field is not
  evidence anything produces a value for it.
- **AI-FOO reads `financial-order-of-operations.json`**, the first thing to read that file, through a
  typed mirror and a drift test that declares the file as a test input. **Stage citations are
  `FOO.<STAGE_ID>`** at the file's version. No rulebook row minted; rulebook still **1.15.0**.
- **`RULE-EMERG-FIRST`'s number still has exactly one mirror** (`QuickSetupRules`); 7.3 and 7.5 both
  take it as an input. The next engine that needs it should build the runtime loader (ADR-0017 trigger 2).
- **AI-FOO is the base; 7.3's goal waterfall splits what it leaves** (ADR-0038, 0.7.7). They used to
  disagree past the emergency gate. The surplus derivation lives in `SurplusRepository` so neither
  owns it. **`GoalWaterfall.monthlySurplus` means what the goals may have** — the month's own figure
  is `grossSurplus`, and `claimedBeforeGoals` says what the earlier stages took.
- **The dashboard's populated fixture now includes a ranking**, so the privacy-blur test and the five
  Paparazzi baselines cover the next-best-rupee card. Any change to the card's copy re-records them.
- **Automating the device:** `adb shell input keyevent 111` (ESC) does **not** dismiss the numeric
  keyboard, and taps then land on its keys. Use Back (`keyevent 4`) and check
  `dumpsys input_method | grep mInputShown` before tapping a field.

### What 7.4 changed that a future issue must know

- **`goal.saved_minor` is now the *declared* half of progress, not the whole of it.** The total is
  `saved_minor + the sum of linked movements`, and `GoalProjection` carries both halves
  (ADR-0036). **Anything that writes `saved_minor` must write the declared half only** — the goal
  editor was about to fold the evidenced half into it on every edit, and nothing in 7.1's code had
  changed. That is the second time in this feature that adding a second measurement made an existing
  sentence wrong retroactively; 7.3's `goals_shortfall` was the first.
- **Every rule naming `AI-GOAL` now has a reader.** `RULE-HORIZON` (7.1), `RULE-EMERG-FIRST` (7.3),
  `RULE-PAY-FIRST` (7.4). The rulebook is still at `_meta.version` **1.15.0** — three goal issues in
  a row minted nothing.
- **A gate can be a tautology and still be green.** 7.4's first golden assertion derived its
  expectation with the same subtraction the engine performs, so editing a record moved input and
  expectation together. Caught only by deliberately breaking it. **When adding a golden assertion,
  state the expectation in the file; never compute it the way the code does.**
- **`CfoArchive` dropped every goal for two issues.** Its own doc comment argues that holding Room
  entities means "a new column is in the archive the moment it is in the table" — true of a column,
  **false of a table**. `rowCount()` had likewise never counted eight of its lists, and the demo wipe
  never reached `goal`, `investment_holding` or `investment_lot`. **A new table means four edits, not
  one: the entity, the archive, the demo wipe and the residue count.**

- **0.3.6 fixed two FR-TXN-001 fields the add screen never captured: merchant and time of day.**
  Merchant was the notable one — the column (schema v1), the draft (3.1), the row's title fallback
  and 3.5's detail sheet all supported it, and **only `DemoDataset` ever wrote one**, so every row on
  a real profile read "Uncategorised". **A field being plumbed end-to-end is not evidence anything
  can produce a value for it**; grep the write paths, not the type.
- **`ZonedDateTime` resolves a DST-gap time forward by the gap's length, not to the first valid
  instant.** 00:30 on Chile's 2026-09-06 becomes 01:30 local (04:30Z) — *not* the 04:00Z
  `Clock.startOfDay` gives for the same day, which asks for 00:00. Both are "resolve forward"; the
  results differ because the requested local times do. A test was written asserting the wrong one.
- **3.5 shipped provenance on screen** ([tracker](issues/3.5-transaction-source-tracking-tracker.md)):
  a source label per row, a detail bottom sheet on tap, and a source filter chip row. **No schema
  change** — `transactions.source` has been right since 3.1; nothing showed it. The reconciliation
  adjustment now says "Balance adjustment" instead of reading as an anonymous "Uncategorised".
- **"Nothing on screen" and "nothing exists" are different claims, and only the second may say so.**
  `TransactionsUiState.isEmpty` has now grown a clause four times — loading, a failed read, a
  scheduled-only profile (3.4), and a filter matching nothing (3.5). Each was found by a test, not
  by reading. Any future state that empties the lists without emptying the profile needs a fifth.
- **Distrust the generated acceptance criteria wherever they are more specific than the SRS section
  they cite** — that is now four for four (3.1 wrong FR id, 3.3 non-existent API, 3.4 an invented
  WorkManager clause, 3.5 an FR id belonging to a different subsystem plus two requirements for work
  that must not be done). They describe an implementation the author guessed at, not the requirement.
- **AI-ARC-003 is about engine results, not stored rows.** It mandates provenance on what an
  *engine* computes (`engineId`, `engineVersion`, `confidence`, `evidence`). No engine writes a
  transaction, so it does not reach the `transactions` table — 3.7's recurring rules are the first
  thing that could want a rule id there.
- **The account-balance queries were wrong and nobody noticed for eight days.** `observeWithBalances`
  and `findWithBalance` summed every live transaction whenever it happened, while `balancesForNetWorth`
  bounded on `booked_on_iso_date <= today` — so the accounts screen and net worth would have shown
  two different figures the moment 3.4 landed. **`balancesForNetWorth`'s own doc comment predicted it
  by name.** A comment that names a future bug does not prevent it; going back to the sibling query
  does. Found by asserting the balance *the accounts screen renders*, not the query under edit.
- **Never gate an amount on a background job** (3.4, [ADR-0010](adr/0010-future-dated-posting.md)).
  A future-dated row is excluded from actuals by its **date**; `posted_at_utc_millis` and
  `ScheduledTransactionWorker` only *record* the rollover. A worker can be deferred by Doze, by a
  powered-off device, or by the app being locked (SEC-002) — a date cannot.
- **Lint catches API-level bugs no test can.** `LocalDate.EPOCH` is API 34 and minSdk is 26; it
  compiled, passed 890 JVM tests, and would have crashed on a phone. `lintDebug` is not optional.
- **The emulator's date is movable, so day-rollover features can actually be verified:** `adb root`,
  `settings put global auto_time 0`, `adb shell date MMDDhhmmYYYY.ss`; restore `auto_time 1` after.
- **Epic 2 is done.** Onboarding, the app lock, accounts, net worth and reconciliation all ship.
- **The `transactions` table finally has a real writer** (3.1's `TransactionRepository`), so
  reconciliation is no longer the only non-demo thing that writes one. **Nothing writes a balance** —
  inserting the row *is* the balance update (DB-001, ADR-0007).
- **`FR-TXN-004` in the backlog was wrong for 3.1** — that id is *split transactions* (issue 3.3).
  The ≤ 3-tap rule is **FR-TXN-002**. Fixed at source in `scripts/gen_issue_docs.py`. Worth
  distrusting the generated FR ids on other issues too.
- **Regenerating issue docs overwrites hand-completed ones.** `gen_issue_docs.py` rewrote 2.7's
  finished file (Files Changed, Verification); it was restored with `git checkout`. Check
  `git diff docs/issues/` after every run and restore any shipped issue it touched. **It blanked four
  at once during 3.4** (2.7, 3.1, 3.2, 3.3) — note that most of the ~150 files it reports as modified
  are CRLF-only noise, so read `git diff --stat` rather than `git status` to find the real ones.
  **Five during 3.5**, up from four — it grows by one with every issue shipped.
- **Model a stored column as a closed set only after grepping what the app actually writes to it.**
  `TransactionSource` first modelled the SRS's list plus `reconciliation` and missed `"demo"`, which
  `DemoDataset` has written since issue 2.4 — so the mapper's forward-compatible `mapNotNull` silently
  dropped **every** sample transaction, and the new list rendered empty on a demo profile whose
  balances plainly came from those rows. The build was green throughout; only running the app caught it.
- **Screens that take text input need `imePadding()`.** The app is edge-to-edge, so the keyboard
  overlays rather than resizes: without it a scrollable form thinks it has the full screen, has
  nothing to scroll, and its Save button is stranded behind the keypad.
- **Verify a migration by upgrading, never by installing fresh.** Issue 3.2's real risk was the
  5 → 6 backfill, and a clean install exercises none of it. The check that means something: build the
  *previous* commit's APK, install it, put real data in, then `adb install -r` the new one over it.
  A destructive migration shows as a blank app; a missing backfill shows as every salary credit
  typed `expense`. **Use a short worktree path** (`git worktree add /c/<short> dev`): the scratchpad
  path breaks Windows' 260-char limit on the Paparazzi snapshot filenames, on both checkout *and*
  removal — cleanup needs `Remove-Item -LiteralPath '\\?\C:\<short>' -Recurse -Force` after
  `git worktree remove` deregisters it.
- **`ALTER TABLE … ADD COLUMN` cannot carry a `CHECK` constraint, and can only add `NOT NULL` with a
  `DEFAULT`.** So (a) a `NOT NULL` column needs `@ColumnInfo(defaultValue = …)` on the entity too or
  Room's schema validation fails at open time on every upgraded install, (b) the default is a
  placeholder the migration must then `UPDATE` into real values, and (c) any §20.2 `CHECK` has to be
  re-expressed as a test. See [ADR-0008](adr/0008-transfers-as-linked-legs.md).
- **A new required entity column with no Kotlin default is a feature, not a nuisance.** `transactions.type`
  deliberately has none, so the compiler lists every write site — `writeAdjustment`, `DemoDataset`,
  the encrypted-DB test — and each has to state its own value rather than silently inheriting a wrong one.
- **Run `./gradlew unitTests`. Never `testDebugUnitTest`.** The latter is an Android *variant* task,
  so it skips the pure-Kotlin modules (`:core:model`, `:core:common`, `:domain:engines:*`) **and
  never reached `:lint` at all** — whose fourteen tests are the only thing checking the five custom
  detectors that make MNY-001, TIM-001, ARC-006, the PII-logging ban and the hardcoded-string ban
  fail the build. **Those tests had never run in CI.** Proven by disabling `MoneyDoubleDetector`
  outright: `testDebugUnitTest` stayed green, `unitTests` failed. Issue 2.6 added the root aggregate
  and pointed CI, CLAUDE.md, the workflow and the templates at it. `koverVerify` does pull the
  pure-Kotlin modules in transitively, so CI was covering those — `:lint` was the real hole.
- **Count tests from `unitTests` only, after clearing stale results.** The count is **573** at
  v0.2.6, across 21 modules. `build-logic:convention`'s 5 are a separate composite CI runs on its
  own and are not in that figure. Earlier numbers in this file drifted because whatever happened to
  be on disk got counted.
- **The app now has background work.** `NetWorthSnapshotWorker` (daily, WorkManager) is the first.
  Two things it establishes for every worker after it: the gated `CfoDatabase` **throws** while the
  app is locked (SEC-002), so a worker must check `SessionLock` first and inject its repository
  through a `Provider`; and `CfoApplication` is a `Configuration.Provider`, which means the manifest
  must keep removing `androidx.work.WorkManagerInitializer` (lint enforces it).
- **FR-ONB-001 is finally satisfied.** Its fourth step — "add first account with opening balance" —
  landed in 2.5, three ADR-0002 updates after that record first deferred it. The ADR is now closed. Onboarding is six
  steps, and **the skip action is no longer `isLast`**: quick setup used to be last, so Skip and
  Finish were the same thing; anything inserted after `ACCOUNT` must keep using
  `OnboardingStep.isSkippable` instead.
- **`gen_issue_docs.py` used to destroy every tracker on every run**, and 2.5 found it by running
  it: fourteen completed verification logs blanked to "not started" in one command, recoverable only
  because they were committed. It now writes a tracker **only when one does not already exist**.
  If you need to reset one, delete the file first.
- **Count tests correctly.** Earlier figures here (2.3's "536") were inflated: summing every
  `**/build/test-results/**/*.xml` picks up `testReleaseUnitTest` output as well as
  `testDebugUnitTest`, counting each test twice and mixing in stale results from previous runs.
  Exclude `testReleaseUnitTest` when counting.
- **The emulator gate is OPEN — this changed with 2.4.** `adb` *is* installed
  (`~/AppData/Local/Android/Sdk/platform-tools`) and an AVD named `CfoTest` *does* exist; the earlier
  claim here was stale. The app has now been built, installed and driven on a device for the first
  time: onboarding → demo → banner → exit, and the same flow again in airplane mode. **Run
  `emulator -avd CfoTest` and use the gate — do not log it as blocked.** Instrumented tests: 9/9 as of
  2.5, but **scope the command** — `connectedDebugAndroidTest` project-wide instruments every module and burns
  ~5 min each on the many with no `androidTest` sources; only `:core:database` and
  `:feature:onboarding` have any.
- **Two long-unproven paths are now proven** (side effect of 2.4's emulator run): the **v1→v2 and
  v2→v3 Room migrations** ran against real SQLite and preserved their rows, and **SEC-002's Keystore
  PIN round trip** executed on a real TEE. Both had previously only ever been exercised by JVM
  stand-ins.
- **Next up after 3.1: 3.2 transfers, 3.5 source tracking, 3.6 the real list.** **Issue 3.4
  (future-dated) is already accounted for** — net worth's as-of query bounds by
  `booked_on_iso_date`, so a scheduled payment will not be subtracted from today's figure. **3.1's
  recent list does not bound that way** (it is a plain 30-day window), so 3.4 must revisit
  `observeRecent` when future dates become possible. `transactions.source` now has values
  `reconciliation` (2.7) and `demo` (2.4) that a list must render distinguishably (P-02 — on the
  adjustment row the source *is* the rule that fired, and `note` is deliberately null); **3.5 owns
  surfacing them, 3.1 only made them parseable.** 3.1 left `writeAdjustment` in `AccountRepository`
  rather than absorbing it — it writes under the *account's* profile inside reconciliation's own
  transaction, which the create path does not need.
- **`TransactionDao.softDelete` lacks the `AND deleted_at_utc_millis IS NULL` guard** that
  `AccountDao.softDelete` has, so a double-delete returns 1 both times and would report success
  twice. Harmless today (3.1 is create-only and nothing calls it), but 3.6's delete-with-undo must
  fix it or it will lie to the user.
- **Do not `combine` two Room `Flow`s in a repository — use `@Relation`.** `combine` calls `yield()`
  internally and `UnconfinedTestDispatcher` refuses it, so 3.3's first `observeRecent` killed **20**
  repository tests on the dispatcher rather than on anything about the data. Room's `@Relation`
  (`TransactionWithSplits`) is one `@Transaction` query that invalidates on either table — simpler,
  and it made the failure disappear rather than be worked around. Caveat: **`@Relation` cannot carry
  a `WHERE`**, so soft-deleted children arrive from the DAO and are filtered in the mapper.
- **Pick one sign convention per layer and convert at exactly one point.** 3.3's running remainder
  read as *double* the amount because it compared a signed parent against unsigned lines. The fix was
  to make the whole editor unsigned and apply the parent's sign only in `toSplitDraftOrNull` — the
  store stays signed, the UI stays unsigned, and one function is the border.
- **A duplicated field label is an accessibility defect, not a test nuisance.** A Compose count
  assertion caught two inputs both labelled "Amount"; a screen reader would announce them
  identically. Renaming to "Line amount" fixed the test *and* the app.
- **detekt's structural limits are a design signal worth obeying literally.** 3.3 hit LongMethod,
  TooManyFunctions twice and CyclomaticComplexMethod; each was answered with a real extraction
  (`SplitEditor.kt`, `SplitDrafts.kt`, a nested `SplitEvent` + its own reducer), never a suppression.
  The seams it forced are the same ones the feature actually has.
- **`account.current_balance_minor` is no longer a lie.** 2.7 built DB-001's integrity job
  (`BalanceIntegrityWorker`, daily). Nothing *reads* the column yet — every balance is still derived
  — so the value is the invariant, not a screen; it is the precondition for switching the read path
  onto the cache if the per-account subquery ever gets expensive
  ([ADR-0007](adr/0007-account-balances-derived-not-stored.md), now with a 2.7 update section).
- **This project has a `Dialog`-shaped hole in its test setup.** 2.7's reconcile UI began as an
  `AlertDialog` — the app's first — and **all four rendered tests hung for 60 s each**: a `Dialog`
  opens its own window and Robolectric never drives it to idle. Rebuilt as an inline `CfoCard`
  panel, which was the better design anyway (a modal with a field, five lines of copy and two
  buttons is 2.5's clipping defect waiting to happen at 200% font). **Prefer inline surfaces; any
  future modal needs an instrumented test and a tracker note saying so.**
- **`adb shell cmd jobscheduler run -f` does not work for WorkManager jobs here** — the ids in
  `dumpsys jobscheduler` are renumbered on every reschedule, so the id is stale by the time the
  command runs. What does work, and is how 2.7 proved `RETRY → SUCCESS` on hardware: **move the
  device clock forward a day** (`adb root`, `adb shell date MMDDhhmmYYYY.ss`), force-stop, relaunch;
  restore with `settings put global auto_time 1`.
- **Still the largest gap:** CI has never run — there is no git remote, so every green is a local
  green on one Windows machine. **Second:** nothing in the app loads `ai/` yet, so the first engine's
  thresholds are Kotlin constants guarded by a drift test rather than rulebook rows — a deliberate,
  recorded deferral of CLAUDE.md §6
  ([ADR-0005](adr/0005-quick-setup-thresholds-deferred-rulebook-loader.md)) with a named trigger.
- **The emulator gate has now found something two issues running.** 2.5: a squeezed Delete button.
  2.6: the dashboard showed the *stored* daily snapshot, so deleting an account left net worth
  unchanged — every unit test agreed with the code because they all asserted the stored figure, which
  was correct. Unlike 2.5's case this one **could** be turned into a test, and was. **Drive the app;
  green tests are not the same as a working screen.**
- **A gate that could not be made to bite, recorded as such.** 2.5 found a layout defect on the
  device (a `Row` squeezed the Delete button to 10px) and could not reproduce it in Robolectric at
  any screen width — its text measurement is a stub. The regression test is kept as a smoke check
  and is **explicitly not claimed as a gate**, in the test and in the tracker. Prefer that over a
  green test nobody has seen fail.
- **Practice worth keeping:** 2.3, 2.4 and 2.5 all made every new gate fail on purpose before trusting
  it (2.4: a one-digit seed change to red the golden dataset test; one hard delete swapped for a soft
  delete to red the residue test). This project has shipped a vacuous gate before — audit G-01, a
  `koverVerify` green at 0% coverage — so "the gate passed" is not evidence until the gate has been
  seen to fail. **2.4 found another one:** `OnboardingFlowInstrumentedTest` had not compiled since
  2.3, because `androidTest` is only compiled when a device is attached. Compile the androidTest
  source set on every issue, device or no device.

## Completed

- **Epic 13 — issue 13.1 (v0.13.0, 2026-10-03):** household mode's foundation (§27, §33;
  [ADR-0069](adr/0069-household-is-a-row-above-the-profile-and-aggregation-composes-scoped-reads.md)),
  and **Epic 13 opens with it.** Schema **v30** adds a `household` table and `profile.household_id` —
  a row *above* the profile, holding a name and a date, no `profile_id` and **no money**. Household
  figures are **N per-profile engine runs summed in Kotlin**, never one query without the
  `profile_id` clause: a bug can then produce a wrong total but can never leak one profile's rows
  into another's view. `HouseholdAggregation` refuses an empty household, a blank profile id and a
  profile counted twice. The flag is **off**.
  **Scoping became a test.** `ProfileScopingTest` reads `Daos.kt` and requires every `@Query` on one
  of the 33 profile-scoped tables to be profile-filtered, id-keyed, or marked `// DEVICE-WIDE:` with
  a reason; one query earns it (`deleteAllPending` — SMS consent is device-wide) and the count is
  pinned. **The 14 id-keyed queries are the recorded precondition for the flag**: they are safe only
  because one profile exists today.
  **The read-at-runtime staleness bug came back a fourth time** — and for the first time in a test
  that reads *source*. Most of what the scoping test checks lives in comments, which compile to
  identical bytecode, so deleting the `// DEVICE-WIDE:` marker left the task **UP-TO-DATE and the
  build green in 1s**; `--rerun-tasks` then failed two assertions. `configureOwnSourceAsTestInput()`
  declares each module's own `src/main` as a test input. **If a test reads a file at runtime, declare
  the file.** Issues 7.2, 11.5, 11.7 and now 13.1.
  **Two more gates were checking less than they claimed.** The archive's format test listed **14 of
  38 keys**, so `goals`, `vehicles` and `marketCloses` were never checked — it now reads the
  serializer's descriptor, with the key count pinned so a removal is still deliberate. And the
  device-wide *reason* check measured "everything after the marker", which swept up the KDoc below
  it: a marker gutted to `// DEVICE-WIDE: on purpose.` passed until a mutation showed it.
  **The restore drill refused the new table**, which is what `Archive.kt`'s own comment warns about
  — `goal` silently dropped out of every export for two issues. `household` is in the archive, read
  *through* the profile since it has no `profile_id`.

- **Epic 12 — issue 12.5 (v0.12.5, 2026-10-03):** the release train and its gate (§21.6, §26;
  [ADR-0068](adr/0068-the-release-train-checks-four-files-and-found-two-drifts-already-in-the-history.md)),
  and **Epic 12 closes with it.** `verifyReleaseMetadata` checks `VERSION`, `versionCode`,
  `CHANGELOG.md` and a reconstructed `docs/releases.md` ledger agree; `scripts/release.py` performs the
  bump and deliberately writes neither the release notes nor a tag. **The gate compares the issue id in
  an entry's title against the version's minor** — the obvious heading-based rule was written first and,
  measured against this repository's own history, called the known-drifted releases *consistent*.
  **Reconstructing the history found two drifts nobody had noticed:** Epic 11's seven issues shipped as
  `0.10.8`–`0.10.14` when the rule says an epic starts with a minor bump, and **ten versions reused the
  previous `versionCode`** (0.3.10, 0.3.11 and 0.4.0 all at 18), which Play rejects after a release is
  cut. Both left in place with the reasons written down — renumbering would make the changelog disagree
  with the commits it documents, and nothing was ever uploaded. AC1's gates were already in place from
  issues 1.1–12.4; `docs/releases-process.md` now tabulates all ten in one page.

- **Epic 12 — issue 12.4 (v0.12.4, 2026-10-02):** the offline end-to-end gate (§21.5, P-04;
  [ADR-0067](adr/0067-airplane-mode-is-turned-on-by-a-test-and-a-check-that-cannot-fail-was-removed.md)).
  **Until this issue nothing in the repository had ever turned airplane mode on** — P-04 lived in three
  source comments and in trackers where a human toggled a setting and remembered to. `OfflineEndToEndTest`
  now toggles it through `executeShellCommand` (shell holds `WRITE_SECURE_SETTINGS`; the app is never
  granted it), drives launch → demo → dashboard figures → transactions, and **restores the radio in
  `@After` even on failure**. `ArchiveRoundTripDeviceTest` round-trips §5.10's archive on **real
  SQLCipher** — seed, export, delete the database *and its key*, reopen, import, every row back to the
  paise — plus the failure that matters more: an incompatible archive is **refused and changes nothing**.
  `offlineSmoke` puts `:app:connectedDebugAndroidTest` in CI for the first time.
  **What measuring changed:** a mutation showed the UI flow passes with the radio *on* (the demo path
  never touches the network), so the gate's strength is the toggle plus its assertions — proven by
  making the toggle a no-op and watching it fail. A socket-reachability check was then built to
  strengthen it and **deleted**, because on the CI emulator it can never fail: that image has no route
  to the internet even with the radio on. A check that always passes is worse than none.

- **Epic 12 — issue 12.3 (v0.12.3, 2026-10-02):** screenshot coverage reaches the three critical flows
  `CLAUDE.md` §4 names — add-transaction, onboarding, the purchase advisor — in light/dark/200%, taking
  the project from 11 to **26 committed baselines**
  ([ADR-0066](adr/0066-screenshot-coverage-reaches-the-critical-screens-and-recording-is-never-automatic.md)).
  Paparazzi stays **per-module** rather than in the feature convention plugin; tests render the
  **stateless `*Content`** with no wrapper and no padding (issue 5.1's harness rendered every baseline
  at double the real padding — a screen the app never draws); the theme is **pinned** or baselines
  shift under an unrelated SDK update. **Recording stays manual** — `recordPaparazziDebug` makes any
  diff disappear, so a flag or CI step that refreshed baselines would make every screenshot test agree
  with whatever the UI became (the sixth "gate that cannot fail" argument in this repo).
  **The first 200% render found a real accessibility defect:** the advisor's "Urgent" chip broke
  mid-word as `Ur`/`ge`/`nt`, because a fixed `Row` squeezed it instead of wrapping. Fixed with
  `FlowRow`. No unit test could have seen it — the text and semantics were both correct. Gate proven by
  changing a padding value and by deleting a baseline, each of which fails the build. **Deferred:**
  locale renders for the three new screens.

- **Epic 12 — issue 12.2 (v0.12.2, 2026-10-02):** the frozen AI-eval datasets are now **versioned** and
  every run **reports what it measured** (§21.5, §8, §18.1;
  [ADR-0065](adr/0065-eval-datasets-are-versioned-and-every-run-reports-what-it-measured.md)). Each set
  declares `# dataset-version:` in its header — a set without one fails, because an accuracy figure
  means nothing if the set behind it may have changed, and because relabelling an awkward case is the
  cheapest way to fix a failing gate and the marker is what makes that diff and the number tell the same
  story. `EvalReport` prints score, counts, floor and revision: the share is **truncated never rounded**
  (91.9% must not print as 92% beside a 92% floor), the floor is **inclusive** (§21.5 says "at least"),
  and `0/0` is refused. The forecast backtest deliberately keeps its own bps reporter — a median error is
  not a share of correct cases. `aiEval` names the gate in CI, additional to `unitTests`.
  **What the survey corrected:** I first reported that a set could be gutted to lift a score; wrong —
  all four runners already guard dataset size, confirmed by gutting one and watching it fail. The real
  gaps were versioning, reporting and naming. **What reporting found immediately:** the forecast's mean
  band coverage at 7027 bps against a 7000 bps bound — 0.27% of margin. Also deleted a per-runner
  helper that reported an empty set as 100%. No threshold was changed.

- **Epic 12 — issue 12.1 (v0.12.1, 2026-10-02):** the shared engine test harness (§21.5, P-08), in
  `:core:common`'s test fixtures — no new module, no new dependency, test-only so ARC-002 is untouched
  ([ADR-0064](adr/0064-the-golden-harness-refuses-to-be-vacuous-and-never-rewrites-its-own-fixture.md)).
  Twenty-four engines had each written their own golden-file reader, which is twenty-four chances to get
  wrong the one thing a test cannot check about itself: that the fixture was read at all. The harness is
  biased one way throughout — **a missing resource, an empty fixture, an unparseable line and an absent
  `required` key are each an error, never an empty result** — and two accessor decisions are deliberate:
  `longOrNull` on an absent key is `null` *never* `0`, and `boolean` refuses anything but `true`/`false`
  because `toBoolean()` maps `ture` to false. `SeededCases` gives each case its own `Random` so
  `count = index + 1` reproduces a failure, and names the seed; `assertDeterministic` runs the subject
  **twice**. **`GoldenSnapshot.propose` never writes the fixture** — a flag that rewrote expectations in
  place could be left on in CI and would make every golden test self-approve for ever. Found while
  writing it: splitting on the `===` substring broke on the harness's own sample fixture, whose prose
  mentions the marker. `:domain:engines:card` adopts it (41 lines of reader deleted, 15 records still
  asserted); the other 23 are **deliberately not** migrated in bulk — their formats differ and a sweep
  would risk 23 working gates for no gain.

- **Epic 11 — issue 11.7 (v0.10.14, 2026-10-02):** the no-hand-rolled-crypto audit (SEC-003), and
  **Epic 11 closes with it**. The audit read every cryptographic call site in production code and
  found **none hand-rolled** — every cipher, AEAD and MAC is Tink's, every key is in the Keystore, and
  the one deviation (Argon2id from BouncyCastle, because Tink has no password-based KDF) was already
  argued in ADR-0039. The finding was therefore not a bug but that the conclusion would decay
  unchecked, so `CfoHandRolledCrypto` now enforces it at ERROR in every module
  ([ADR-0063](adr/0063-sec-003-is-enforced-by-a-lint-rule-with-two-argued-exemptions.md)). It matches
  **resolved types rather than identifier names**, because `import javax.crypto.Cipher as Box` would
  defeat a name-based security rule — lint's own `IMPORT_ALIAS` mode caught that. Two argued
  exemptions: `KeyGenerator` only alongside a `KeyGenParameterSpec`, and test sources (independent
  oracles). **The mutation finding:** a detector could be dropped from `CfoIssueRegistry` with all 25
  lint tests green, because every test passes `.issues(X.ISSUE)` and bypasses the registry — the only
  thing that makes a rule apply. That exposed all five pre-existing rules too; two registry tests now
  pin publication and ERROR severity. Same shape as 1.5, 7.2, 11.5 and 11.6: **what makes a check
  apply is a separate concern from the check, and is never tested unless someone tests it.**

- **Epic 11 — issue 11.6 (v0.10.13, 2026-10-02):** release hardening and supply-chain scanning
  (§21.3, §21.6, SEC-007). R8 minification plus resource shrinking (84.5 → 66.6 MB), with
  `Log.v/d/i/w`, `isLoggable` and `println` stripped — and **the strip verified by parsing the
  shipped DEX** rather than asserted, because a rule file is not evidence about a binary
  ([ADR-0062](adr/0062-the-release-is-minified-and-the-log-strip-is-read-out-of-the-shipped-dex.md)).
  `Log.e`/`wtf` are deliberately kept. OSV scanning over the **resolved** release-runtime classpath
  (278 coordinates) blocks on HIGH and above, with an allowlist whose entries **expire** — and
  "could not reach OSV" exits non-zero rather than passing, so the gate cannot be green by being
  blind. **No new third-party dependency.** **The find:** a clean install of the minified release
  opened on the lock screen with no PIN set — a total lockout — because protobuf-javalite resolves
  its generated classes reflectively, R8 renamed them, the settings read failed, and the app lock's
  fail-secure default did exactly what it promises. No crash, nothing in logcat, and nothing in
  5,100 JVM tests could see it: they all run on unminified code. **The release APK is installed on a
  device every time now, and that is why.**

- **Epic 11 — issue 11.5 (v0.10.12, 2026-10-01):** DPDP Act 2023 alignment (§23, §32). The export now
  carries the **consent record** — one row per declared consent, including the ones never answered,
  with both timestamps — because the right of access covers what the app was *allowed* to do and not
  only what it holds. **It is never imported:** a file is not a person, so restoring would re-grant a
  withdrawn consent and a hand-edited archive would become a consent mechanism
  ([ADR-0061](adr/0061-dpdp-alignment-is-a-checked-document-and-the-consent-record-is-export-only.md)).
  An unreadable ledger fails the export rather than writing a file that reads as "granted nothing".
  `docs/compliance/dpdp-2023.md` maps each obligation to its code and test, and
  `DpdpComplianceDriftTest` keeps it honest — a consent without a declared purpose, or a renamed
  class the matrix cites, fails the build. Four obligations are listed as **open**, being the
  publisher's rather than the code's. **The real find:** writing that gate exposed that **29 drift
  tests across the project could not fail** — a file read at runtime is not a declared task input, so
  Gradle skipped each one on exactly the edit it existed to catch. Issue 7.2 had found this and fixed
  it for a single file, leaving nine others and every Android-library module exposed;
  `configureCheckedDataAsTestInput` now declares the `ai/` and `docs/compliance/` directories from
  both convention plugins.

- **Epic 11 — issue 11.4 (v0.10.11, 2026-10-01):** erase-all by crypto-shredding (§23, §34,
  SEC-003). **Keys before files**, because the order decides what survives an interruption: keys
  first leaves ciphertext nobody can read, files first leaves a live key beside a half-deleted
  database. A failed shred deletes **nothing**; a failed file delete is tolerated; the deletion is
  **verified** rather than assumed, because `KeyStore.deleteEntry` can return without removing the
  key ([ADR-0060](adr/0060-an-erase-destroys-keys-first-and-says-what-it-cannot-reach.md)). Every
  secret is declared by the module that owns it (`SecretInventory`, and the factories rewired to read
  the same constants), so no alias has a second copy to drift from. Two gates: a word typed in the
  user's own language, then the PIN — absent on a device that has none, *required* when the
  credential cannot be read. The screen says plainly that it cannot reach a backup the user exported.
  An instrumented **canary** test writes a unique string into a real encrypted database, a real
  receipt and the settings file, erases, then reads every byte the app still owns looking for it. A
  mutation caught the one real gap: both confirmation strings start empty, so `"" == ""` would have
  let an untouched screen erase.

- **Epic 7 — issue 7.4 (v0.7.4, 2026-09-06):** linked contributions (§15, FR-GOAL-002,
  FR-GOAL-004). Goal progress split into an **evidenced** half derived from the user's own ledger and
  a **declared** half they typed, shown as visually distinct and clearable in one tap. Schema **v22**
  — `goal_contribution` and `goal_funding_account`, neither holding an amount, both summed at query
  time by one `UNION ALL` that dedupes per goal ([ADR-0036](adr/0036-progress-is-evidenced-and-declared-and-the-two-never-merge.md)).
  `RULE-PAY-FIRST` consumed at last, mirroring nothing. Also repaired three things it was built on
  top of: `goal` had been missing from `CfoArchive` since 7.1, `rowCount()` had never counted eight
  of its lists, and the demo wipe reached neither the goal family nor the investment tables.

- **Epic 0 — Foundations & AI blueprint (v0.1.0):**
  - AI subsystem files the app loads at runtime ([`../ai/`](../ai/)) — layered pipeline,
    orchestrator, rulebook + order-of-operations, chat tool registry, LLM prompt + guardrail,
    knowledge bases.
  - Agent/dev config: [`../CLAUDE.md`](../CLAUDE.md), project skills, slash commands, CI, PR
    template, ENGINE/ADR templates.
  - Planning layer: [design spec + CSV](superpowers/specs/2026-07-17-ai-personal-cfo-design.md)
    (13 epics, 85 issues) and the full [`issues/`](issues/) backlog + trackers.
  - `/run` and `/verify` commands; `VERSION` + `CHANGELOG.md`.
- **Project docs (this set, 2026-07-18):** `PRD.md`, `Architecture.md`, `Rules.md`, `phase.md`,
  `Design.md`, `memory.md`.
- **Epic 1 — Foundation & Core Platform (v0.1.0, 2026-07-25):** issues 1.1–1.10 — the multi-module
  skeleton and its ARC-002 guard, `Money`/`Clock`/`Result`, five custom lint rules, encrypted Room
  over SQLCipher plus the migration harness, the M3 design system, Proto DataStore settings and the
  consent ledger, and the app shell with a typed nav graph. Full account of what is and is not
  proven: [`handoff_epic_completed/epic-1-foundation-handoff.md`](handoff_epic_completed/epic-1-foundation-handoff.md).
- **Epic 2 — issue 2.1 (v0.2.1, 2026-07-25):** the 4-step first-run onboarding. First screen that
  writes; closes the "nothing sets the profile time zone" seam from Epic 1 and gives the consent
  ledger its first caller.
- **Epic 3 — issues 3.1, 3.2, 3.3 (v0.3.1 → v0.3.3, 2026-08-02):** the transactions table got its
  first real writer and then its two hard shapes. **3.1** — add a transaction in ≤ 3 taps
  (FR-TXN-002, *not* FR-TXN-004 as the backlog claimed) behind a global FAB, no schema change.
  **3.2** — a transfer is **one logical record rendered from two linked legs**, schema **v6**
  (`transactions.type`, `transfer_id`) with the app's first backfilling migration
  ([ADR-0008](adr/0008-transfers-as-linked-legs.md)). **3.3** — splits across N category lines,
  schema **v7** (`transaction_splits`), where the parent holds the money and the lines hold only the
  categories, so **no balance code was written at all**
  ([ADR-0009](adr/0009-splits-as-a-child-table.md)). The two ADRs answer the same structural question
  in opposite directions, on purpose: transfer legs both move money, split lines move none. Still
  create-and-delete only — **there is no edit path anywhere** (issue 3.6).
- **Epic 2 — issue 2.7 (v0.2.7, 2026-08-02):** account reconciliation (FR-ACC-006) — **the last
  issue in Epic 2**. A balance the app got wrong is corrected by *adding* an adjustment transaction
  (`source = "reconciliation"`), never by editing history; a zero delta writes nothing at all. The
  screen previews the delta, the repository re-derives it inside its own transaction and decides
  (P-03). Also **DB-001's integrity job** — `BalanceIntegrityWorker`, the app's second background
  work, closing ADR-0007's open *"nothing notices"* consequence. **No schema change** (v5 stands):
  the first Epic 2 issue needing no migration. The emulator gate found a real defect for the third
  issue running — an untouched form promising an adjustment it could not make.
- **Epic 2 — issue 2.6 (v0.2.6, 2026-08-02):** net worth (FR-ACC-005). The project's **second
  engine** (`:domain:engines:networth`, pure Kotlin) and its **first background work** — a daily
  WorkManager snapshot that backfills missed days, at schema **v5** (`net_worth_snapshot`,
  `account.include_in_networth`). Classification is by account type, never by the sign of the
  balance. The dashboard's hardcoded ₹4,82,350.00 is gone; Safe-to-Spend is the last placeholder.
- **Epic 2 — issue 2.5 (v0.2.5, 2026-08-01):** accounts CRUD (FR-ACC-001, FR-ACC-007). All eleven
  SRS account types behind an `AccountType` enum (the old six included a `wallet` the SRS never had,
  and the demo was writing a `card` nothing would match); balances **derived** from transactions
  rather than stored ([ADR-0007](adr/0007-account-balances-derived-not-stored.md), DB-001); archive
  kept distinct from soft delete; schema **v4** and the first migration that alters an existing
  table. Also the app's **first typed route with an argument**, and **FR-ONB-001's last step**, which
  closes [ADR-0002](adr/0002-onboarding-step-order.md).
- **Epic 2 — issue 2.4 (v0.2.4, 2026-07-28):** demo mode (FR-ONB-004). A deterministic, seeded
  three-month sample dataset under an isolated `demo` profile, labelled by one banner above the nav
  graph and erased by hard delete on the way out
  ([ADR-0006](adr/0006-demo-mode-profile-isolation-and-hard-delete.md)). Needed **no schema change**.
  Also: the project's **first emulator run**, and the repair of an instrumented test that had been
  uncompilable since 2.3.
- **Epic 2 — issue 2.3 (v0.2.3, 2026-07-27):** the quick-setup seeds (FR-ONB-002). The project's
  **first engine** (`:domain:engines:quicksetup`, pure Kotlin), its **first `EngineProvenance`**
  (AI-ARC-003), its **first `profile` row**, and schema **v3** (`budget`, `recurring_rule`). The
  dashboard's hardcoded spending split is gone, replaced by the user's real budget. Two recorded
  deviations: [ADR-0004](adr/0004-quick-setup-persists-budgets-and-recurring-rules.md) (schemas
  defined ahead of the issues that own them) and
  [ADR-0005](adr/0005-quick-setup-thresholds-deferred-rulebook-loader.md) (§6 rulebook loader
  deferred, guarded by a drift test).
- **Epic 2 — issue 2.2 (v0.2.2, 2026-07-26):** the biometric/PIN app lock (SEC-002). First security
  perimeter in the app: a session gate the database provider asserts on, a Keystore-bound PIN, the
  escalating lockout, and `audit_log` as schema **v2** — the project's first real migration and its
  first `:data:repository` class. SEC-001's user-auth key clause is deliberately still open
  ([ADR-0003](adr/0003-app-lock-gate-and-deferred-user-auth-key.md)).

## How to update

When you finish an issue: bump [`../VERSION`](../VERSION) + [`../CHANGELOG.md`](../CHANGELOG.md),
update the issue's tracker, then edit the three lines under **Current state** above and add a
bullet under **Completed**. That's it.
