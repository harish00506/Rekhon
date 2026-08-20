<!--
  Why:  Two questions were being answered nowhere. "Why this approach?" was answered only for
        deviations from the SRS, because that is the sole trigger for an ADR. "Why this library?"
        was answered nowhere at all — docs/Architecture.md lists the pinned stack without reasons,
        so a reader could see that Tink was chosen but not what it was chosen over.
  What: The project-wide decision index. One row per decision, newest last.
  Result: A reader can find every approach and library decision from one page, and follow the link
        when they need the full argument.
  Changelog:
    2026-08-14 — Created. Seeded from the 19 existing ADRs and gradle/libs.versions.toml.
-->

# Decisions — AI Personal CFO

> **This file is an index, not an archive.** When an ADR exists it owns the argument and this page
> carries one line and a link. Restating an ADR here would create a second copy to keep in step, and
> the copy would lose. Only decisions with no ADR carry their full reasoning here.
>
> **Binding rule:** [`CLAUDE.md` §10](CLAUDE.md). Per-session detail: [`docs/sessions/`](docs/sessions/).
> Runtime call paths: [`FLOW.md`](FLOW.md).

**When to add a row**

| You did this | Add a row to |
|---|---|
| Chose an approach that a reader could reasonably have expected to go the other way | **Approach decisions** — plus an ADR if it deviates from the SRS |
| Added, removed or swapped a dependency | **Library decisions** — always, no exceptions |
| Followed the SRS as written | Nothing. The SRS is the record |

---

## Approach decisions

Newest last. "Why" is one line; the ADR holds the argument.

| # | Date | Decision | Why, in one line |
|---|------|----------|------------------|
| [0001](docs/adr/0001-custom-lint-module-and-money-heuristic.md) | 2026-07-25 | Custom lint detectors live in a top-level `:lint` module, and money is detected by *name* | The §21.2 graph has no home for a plain `java-library` that every module must apply; name-based detection catches `Double totalAmount` without a type system that does not exist yet |
| [0002](docs/adr/0002-onboarding-step-order.md) | 2026-07-25 | Onboarding's four steps are not FR-ONB-001's four steps | SMS consent was promoted to its own step rather than buried, because a consent granted in passing is not informed consent (P-01) |
| [0003](docs/adr/0003-app-lock-gate-and-deferred-user-auth-key.md) | 2026-07-26 | The app lock gates a `SessionLock` flag; the Keystore key is untouched | The Hilt provider for `CfoDatabase` refuses to build while locked, so no path reaches data without unlocking — and no key rotation was needed to get there |
| [0004](docs/adr/0004-quick-setup-persists-budgets-and-recurring-rules.md) | 2026-07-27 | Quick setup writes real `budget` and `recurring_rule` rows at schema v3 | Persisting now means 4.4 / 3.7 / 2.5 extend the tables rather than rewrite them |
| [0005](docs/adr/0005-quick-setup-thresholds-deferred-rulebook-loader.md) | 2026-07-27 | Quick-setup thresholds are typed Kotlin defaults, not `ai/` rows — **with named triggers** | The runtime `ai/` loader is a large build with no user-facing requirement yet; injecting the rules means the loader later replaces an argument, not a rewrite |
| [0006](docs/adr/0006-demo-mode-profile-isolation-and-hard-delete.md) | 2026-07-28 | Demo mode is a second `profile_id` in the same database, wiped by hard delete | Every table already carries `profile_id`, so isolation was free; a second SQLCipher file would have meant a second key to manage |
| [0007](docs/adr/0007-account-balances-derived-not-stored.md) | 2026-07-28 | Balances are derived on read, never stored | A stored balance is a second source of truth that drifts; the derivation is one correlated subquery in `AccountDao.observeWithBalances` |
| [0008](docs/adr/0008-transfers-as-linked-legs.md) | 2026-08-02 | A transfer is two rows sharing a `transfer_id`; `type` is derived, never supplied | Two legs keep both account balances correct from the same ledger, with no `transfers` table to fall out of step |
| [0009](docs/adr/0009-splits-as-a-child-table.md) | 2026-08-02 | Split lines are a child table that moves no money; the remainder rule is largest-remainder | Largest-remainder makes the lines sum to the parent exactly; `HALF_EVEN` per line does not (MNY-001) |
| [0010](docs/adr/0010-future-dated-posting.md) | 2026-08-03 | A future transaction is excluded from actuals by its **date**, not its posting stamp | Correctness cannot depend on a worker having run; `posted_at` records what happened, the date decides what counts |
| [0011](docs/adr/0011-back-dating-a-scanned-receipt.md) | 2026-08-06 | ~~Only a scanned receipt may be back-dated~~ | **Superseded by 0012** — the narrow permission was unnecessary once the series could repair itself |
| [0012](docs/adr/0012-back-dating-and-the-repairable-net-worth-series.md) | 2026-08-06 | Any transaction may be back-dated | The net-worth series repairs itself, so the restriction bought nothing and cost the user the ability to enter yesterday's cash |
| [0013](docs/adr/0013-read-sms-play-policy-and-the-gated-inbox.md) | 2026-08-07 | `READ_SMS` ships, behind gates, with the Play-policy risk recorded | The alternative was dropping a specified feature; the risk is written down here rather than discovered at submission |
| [0014](docs/adr/0014-classification-kb-seed-mirror-and-unconsumed-merchant-rules.md) | 2026-08-08 | The classification KB is a typed Kotlin mirror; merchant rules ship without a consumer | The duplication is guarded by `ClassificationKbDriftTest`, which fails the build when the mirror and the KB disagree |
| [0015](docs/adr/0015-stage-1-classification-tiers-and-the-kb-mirror.md) | 2026-08-10 | Stage 1 ships two of its three tiers | The two shipped tiers already meet the ≥ 92% eval gate; the third is a separate issue rather than a rushed third |
| [0016](docs/adr/0016-nature-classification-by-account-type.md) | 2026-08-10 | Nature fires on account **type**; only the override is stored; true spend ships understated | Account type is a fact the user entered, stronger than inference and weaker than an amortisation row the app does not have yet |
| [0017](docs/adr/0017-budget-thresholds-stay-a-typed-mirror.md) | 2026-08-11 | Budget thresholds stay a typed mirror; the `ai/` loader is deferred again, **trigger list narrowed to three** | ADR-0005's first trigger had not actually fired, and the narrowed list makes the next deferral impossible to argue loosely |
| [0018](docs/adr/0018-split-aware-category-spend.md) | 2026-08-11 | Category spend is a `UNION ALL` of unsplit transactions and live split lines | A payment must be counted exactly once — by its lines where it has them — or a split silently double-counts |
| [0019](docs/adr/0019-budget-alert-bands-mint-a-new-rule-row.md) | 2026-08-13 | FR-BUD-004's bands mint `RULE-BUD-ALERT` v1.0 rather than bump `RULE-BUD-PACE` | Bumping a shipped row fires ADR-0017's trigger 3 and forces the whole runtime loader; the split is also right on the merits — pace is arithmetic, alerts are attention |
| [0020](docs/adr/0020-budget-review-keyed-by-month-not-category.md) | 2026-08-15 | `budget_review`'s claim is keyed `(profile, month)`, not `(profile, month, category)` | A review is one card for the whole closed month, not one status per finding — keying it finer would imply a per-finding-acknowledged screen this issue does not build |
| [0021](docs/adr/0021-safe-to-spend-buffer-and-goal-stand-in.md) | 2026-08-16 | Safe-to-Spend takes income from the declared budget, withholds a 5% buffer §5.2 does not name, and stands in for goal contributions until 7.1 | A ledger-driven figure measures the salary calendar rather than the user; the buffer covers the commitments the app was never told about, and it is a labelled line on the card rather than a silent haircut |
| [0022](docs/adr/0022-privacy-blur-masks-text-and-sets-flag-secure.md) | 2026-08-16 | The privacy blur replaces amounts with a fixed-width mask rather than blurring pixels, and sets `FLAG_SECURE` while it is on | `Modifier.blur` is a silent no-op below API 31, does not stop a screenshot or a screen-share, and blurs the navigation along with the figures; a fixed-width mask also refuses to leak the order of magnitude |
| [0023](docs/adr/0023-archive-replaces-and-carries-no-blobs.md) | 2026-08-16 | The export archive serialises the Room entities directly, replaces on import, and carries neither receipt images nor `audit_log` | Hand-written DTOs have one silent failure mode — a forgotten mapper drops user data from every export — while a rename is catchable by a test; replace is the only import a lossless round-trip can be asserted against; a plaintext file must never carry decrypted receipts, and `audit_log` has no `profile_id` to scope by |
| [0024](docs/adr/0024-the-widget-renders-from-glance-state-not-the-database.md) | 2026-08-17 | The home-screen widget renders from Glance's own preference state, written only by `:app`, and carries no Paparazzi baselines | The encrypted database throws while the app is locked (SEC-002) and a home screen is mostly read locked, so the render path cannot touch Room; Safe-to-Spend had no cache to read anyway; and a screenshot test of a plain-Compose mirror would be green against a tree that is not the one shipped |
| [0025](docs/adr/0025-card-alerts-mint-rule-cc-due-and-claim-per-statement-cycle.md) | 2026-08-17 | Card reminders mint `RULE-CC-DUE` v1.0 rather than extend the shipped `RULE-CC-UTIL`, and `card_alert` is claimed per **statement cycle** (`cycle_start_iso_date`), not per month | Extending a shipped row fires ADR-0017's trigger 3 and forces the runtime rules loader; and a card billing on the 25th has a cycle straddling two calendar months, so a month-keyed claim fires twice for one statement |
| [0026](docs/adr/0026-amortisation-schedule-is-derived-not-stored.md) | 2026-08-20 | A loan stores its **terms**; the amortisation schedule is derived on every read, and there is no `loan_amortization_rows` table — contra the one ADR-0016 named | 240 stored rows are a cache of a pure function of five columns: edit the rate and the copy silently disagrees with what produced it, which is exactly what ADR-0007 refused for balances. The engine is exact (`Long` paise, integer bps, a pinned `MathContext`) and bounded (tenure ≤ 600), so a recomputation can never differ from the last one |

---

## Library decisions

Every dependency in [`gradle/libs.versions.toml`](gradle/libs.versions.toml) that someone *chose*.
Transitive pulls and BOM-managed artifacts are not listed — only decisions.

**The catalog is the version; this table is the reason.** Never record a version number here: it
would be a second copy of the catalog, and it would be wrong within a month.

| Library | What it is for | Why it, and not the alternative | Entered at |
|---------|----------------|----------------------------------|------------|
| **Jetpack Compose + Material 3** | All UI | Pinned by SRS §21.3. Views would mean two UI systems for the widget and the app | 1.1 |
| **Room** | Persistence | Pinned by §21.3. Compile-time-checked SQL and a migration story; raw SQLite would hand-roll both | 1.1 |
| **SQLCipher** (`net.zetetic:sqlcipher-android`) | Encrypting that database | P-01 requires the store encrypted at rest; Room's own encryption does not exist | 1.6 |
| **`androidx.sqlite`** | The `SupportSQLiteOpenHelper` seam | How SQLCipher is handed to Room without Room knowing about it | 1.6 |
| **`room-migration`** | Parsing exported schema JSON in **JVM** tests | Lets DB-003 (no destructive migrations) be asserted in a unit test rather than only on a device — and CI has never had a device | 1.7 |
| **`room-paging`** | `PagingSource` straight from a `@Query` | Keeps FR-TXN-007's infinite scroll as one `@Query` instead of a hand-rolled `LIMIT`/`OFFSET` cursor | 3.6 |
| **Paging 3** (`runtime`, `compose`, `testing`) | The transaction list's infinite scroll | First-party, and the only one Room integrates with. `paging-testing` keeps it provable on the JVM | 3.6 |
| **Proto DataStore** + `protobuf-javalite` | Settings and the consent ledger | §21.3 **bans SharedPreferences**; Proto gives a typed schema, which a consent ledger with grant/revoke timestamps needs | 1.9 |
| **`datastore-core-okio`** | DataStore's file storage | Okio's `atomicMove` works on every host OS. The default `File` rename **cannot replace an existing file on Windows** — and this is a Windows machine | 1.9 |
| **Hilt** | Dependency injection | §21.3, and ARC-003 bans service locators. `@HiltWorker` also solves worker injection, which WorkManager's default factory cannot do | 1.1 |
| **WorkManager** | Background jobs | The only scheduler that survives process death and reboot; the daily jobs must not depend on the app being open | 2.6 |
| **`work-testing`** | `TestListenableWorkerBuilder` | Makes each worker's locked-session path (SEC-002) provable on the JVM instead of by hand on a device | 2.6 |
| **Navigation Compose** (typed routes) | The single nav graph | ARC-001 — feature modules never depend on each other, so navigation must be typed routes in `:app` | 1.10 |
| **`androidx.activity.compose`** | `setContent`, and `rememberLauncherForActivityResult` | The permission launcher for `POST_NOTIFICATIONS`. Requesting through the Activity directly would put an Android permission callback inside a feature module | 1.1 (`:app`), 4.5 (`:feature:budgets`) |
| **`androidx.fragment` / `FragmentActivity`** | The host Activity's base class | Forced by AndroidX `BiometricPrompt`, which hosts itself in a fragment. Nothing else about the app is fragment-based | 2.2 |
| **BiometricPrompt** (`biometric-ktx`) | The app lock | §21.3 and SEC-002: class-3 biometric plus PIN fallback. It is also the only API that reaches the TEE-backed sensor | 2.2 |
| **Google Tink** | All cryptography | SEC-003 makes hand-rolled crypto review-blocking. Tink is the sanctioned library; the JCA directly is where nonce-reuse bugs live | 1.6 |
| **ML Kit Text Recognition v2** | Receipt OCR | Runs **on-device**, so P-01 holds. Every cloud OCR would put a receipt image on someone else's server | `:ml:ocr` |
| **kotlinx.serialization** | JSON, incl. typed nav routes and §5.10's export archive | One serializer for network, `ai/` fixtures, navigation and the archive, rather than Moshi/Gson alongside it. Issue 5.4 promoted it from `testImplementation` to `api` in `:core:database` — the entities are the archive format (ADR-0023), so `:data:repository` needs the runtime to build the envelope around them | `:app`, `:core:database`, `:data:repository` (5.4) |
| **Retrofit + OkHttp** | The optional backend | §21.3. Only ever on a consented, non-core path (P-04) | **pinned, not yet consumed** — no module depends on it |
| **Glance** | Home-screen widget | The only way to build a widget in Compose-shaped code; `RemoteViews` by hand is the alternative. Consumed at issue 5.5, four epics after it was pinned — its preference state doubles as the widget's cache (ADR-0024), which is what lets the widget render while the encrypted database is locked | `:widget` (5.5) |
| **glance-appwidget-testing** | Composing the widget on the JVM | Paparazzi cannot render Glance — it emits `RemoteViews` for an `AppWidgetHost`, not a view tree LayoutLib can inflate — so without this the blurred widget would be checked by eye only. Test-only, and the same `glance` version | `:widget` (5.5) |
| **JUnit 4** | Test runner | What Android's instrumentation and Robolectric both expect. JUnit 5 on Android is still a workaround | 1.1 |
| **Turbine** | Asserting `StateFlow` sequences | §21.5 wants the *full* `UiState` sequence including loading and error; collecting into a list by hand loses the ordering guarantees | 1.1 |
| **Robolectric** | Compose UI tests on the JVM | A screen's flow is checked without a device — **CI has never had one**. Named in the sanctioned test stack in `docs/issues/00-issue-workflow.md` | 2.1 |
| **Paparazzi** | Screenshot tests | §21.5 wants light/dark/200%-font screenshots; Paparazzi renders them on the JVM, so the diff runs in CI | 1.8 |
| **MockK** | Mocking | Kotlin-native: handles `final` classes and suspend functions, which Mockito needs plugins for. **Never actually used** — every suite so far uses a hand-written fake or recorder instead, which reads better and catches an unimplemented method. A candidate for deletion from the catalog | **pinned, not yet consumed** — 0 modules, 0 files |
| **Truth** | Assertions | Failure messages that name the actual value. Preferred over bare `assertEquals` where the subject is a collection | 1.1 |
| **Kover** | Coverage | Kotlin-native and understands inline functions; JaCoCo miscounts them, and §4.2's "100% money math" gate must not be measured by a tool that guesses | 1.1 |
| **ktlint + detekt** | Style and complexity | §21.6 names both. ktlint formats, detekt catches complexity — they do not overlap | 1.1 |
| **`lint-api` / `lint-checks`** | The custom detectors in `:lint` | The only way to fail the *build* on `CfoMoneyAsFloatingPoint` and friends. Pinned to AGP + 23 — see the catalog comment | 1.5 |
