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
-->

# AI Personal CFO — Project Memory

> **Update this on every shipped issue and whenever you switch what you're working on.** Keep it
> short. This is the *project progress* log — distinct from the agent's own memory dir at
> `~/.claude/.../memory/`. Detail lives in [`../CHANGELOG.md`](../CHANGELOG.md) and the per-issue
> trackers in [`issues/`](issues/); the static roadmap is [`phase.md`](phase.md).

## Current state

- **Version:** `0.9.6` (see [`../VERSION`](../VERSION)) · **Phase:** 2–3. **Schema is v24** (9.6's `notification_log`).
- **Epics 1–8 are done; Epic 9 is open** — 9.1 (stream classification), 9.2 (the cash-flow
  forecast), 9.3 (seasonality), 9.4 (the health score), 9.5 (the insight orchestrator and its
  feed) and 9.6 (the notification policy) shipped; **9.7 is next**. Epic 9 (AI core engines, incl.
  the 9.2 forecast) is still the one the goals work kept waiting on.
- **Currently working file:** none. Issues **8.1–8.3 and 9.1–9.6 are merged to `dev`**
  ([8.1 tracker](issues/8.1-e2ee-backup-argon2id-aes-256-gcm-tracker.md), ADR-0039;
  [8.2 tracker](issues/8.2-restore-on-fresh-device-tracker.md), ADR-0040;
  [8.3 tracker](issues/8.3-backup-restore-drill-tracker.md), ADR-0041;
  [9.1 tracker](issues/9.1-fixed-variable-nature-engine-ai-cls-stage-2-tracker.md), ADR-0042;
  [9.2 tracker](issues/9.2-cash-flow-forecast-ai-fct-tracker.md), ADR-0043;
  [9.3 tracker](issues/9.3-seasonality-ai-seas-tracker.md), ADR-0044;
  [9.4 tracker](issues/9.4-financial-health-score-ai-fhs-tracker.md), ADR-0045;
  [9.5 tracker](issues/9.5-insight-orchestrator-feed-tracker.md), ADR-0046;
  [9.6 tracker](issues/9.6-notification-engine-policy-tracker.md), ADR-0047).
- **`origin/dev` is still at `6afa5f0`** — local `dev` is well ahead (7.4, 7.5, the card APR field,
  the FOO/goals agreement, 8.1 and their records),
  and **the push is blocked, not skipped**: this machine has no GitHub credentials (no helper, no
  `gh`, no token, and the one SSH key is for another host). Pushing needs an authenticated shell.
- **This machine builds with Temurin JDK 21** (`~/.jdks/temurin-21`) and the SDK at `~/Android/Sdk`;
  `local.properties` points there. Gradle stays at **8.13**: JDK 25 would need Gradle ≥ 9.1, AGP 8.x
  stops working at Gradle 9.6, and Hilt 2.56.2's transforms are ambiguous under Gradle 9. Emulator:
  **`CfoTest`** (API 36, Google APIs x86_64).
- **Check `git log dev` against `VERSION` before starting an issue**, not just the issue tracker.
  `dev` was two issues behind once and nobody noticed.
- **The forecast exists now (9.2), but the goals still use the observed P50 surplus** (ADR-0035,
  ADR-0037). Switching `SurplusRepository` to `ForecastRepository` is ADR-0043's recorded follow-up.

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
