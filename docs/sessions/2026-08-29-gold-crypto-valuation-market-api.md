# Session — 2026-08-29 — Issue 6.5: gold/crypto valuation via market API

**Branch:** `feature/6-5-gold-crypto-valuation-via-market-api`
**VERSION:** 0.6.4 → **0.6.5** · versionCode 25 → 26 · schema 18 → **19** · rulebook 1.13.0 → **1.14.0**
**Shipped in three commits**, because the schema migration and the four-module rulebook bump each
deserved a review with nothing else in it.

---

## 1 · Decisions this session

### 1.1 The client ships unconfigured, and the gap is recorded rather than papered over

§16.1 wants gold end-of-day and crypto every fifteen minutes; §22 says both come through **our own
backend proxy**; EXT-001 forbids talking to a vendor directly. **The proxy does not exist and no
issue in the backlog builds it.**

So AC-1 of this issue **cannot be demonstrated**, and that is stated in
[ADR-0030](../adr/0030-the-market-data-client-ships-unconfigured.md), in the CHANGELOG and in the
tracker rather than being written around. What was built instead is a client that is structurally
incapable of talking to anything else and provably inert: `NetworkConfig.UNCONFIGURED` has a blank
base URL, and `MarketDataFactory.create` branches on that first, so **no `OkHttpClient` is ever
constructed** — no pool, no DNS, no socket.

The alternative considered and rejected was pointing Retrofit at AMFI or CoinGecko as a stopgap. It
is an EXT-001 and P-01 violation outright, and — the part that decided it — it is very hard to unpick
once shipped, because by then it works.

### 1.2 Revoking MARKET_DATA keeps the cached price — diverging from issue 3.9 on purpose

Issue 3.9 deletes pending SMS drafts when consent is revoked (`SmsConsentWatcher`). This issue
deliberately does **not** do the equivalent, and there is no `MarketDataConsentWatcher`.

An SMS draft is an *inference about the user* drawn from data they withdrew permission to read, so
P-01 requires it to go. A gold price is a *public fact about gold* — it says nothing about them. What
is private is the **request**, and that is exactly what the consent gates. Wiping the price would
make a portfolio value vanish as a side effect of a privacy toggle; instead the label keeps ageing
("as of 12 Jul · 41 days old"), which is the truth and more informative than a blank.

### 1.3 Two rulebook thresholds, not one

`RULE-PRICE-STALE` v1.0 carries `refresh_minutes` (gold 1440, crypto 15) and `stale_after_days`
(gold 3, crypto 1, default 7). One value cannot do both: at fifteen minutes crypto is permanently
stale; at one day it never refreshes. Both live in `ai/rules/` (CLAUDE.md §6), and the comparison
lives in the **engine** — a `now - fetchedAt > TTL` written in `:data:repository` would be a
financial constant with no drift gate on it.

**The four-module landmine was handled in one commit.** `_meta.version` is pinned by `budget`,
`card`, `investment` and `safetospend`, each with its own drift test; moving one without the others
turns three unrelated modules red. All four went to 1.14.0 together.

### 1.4 The price columns get a second, narrower writer

`MarketPriceRepository` owns the price columns; `InvestmentRepository` owns the row.
`updatePriceByKey` is an `UPDATE` of four named columns and **cannot reach `name` or `asset_class`**
— those identifiers do not occur in the statement — so a refresh landing during a rename cannot
revert it. A read-modify-`upsert` would have, since `upsert` uses `REPLACE`.

`distinctPriceKeys` is the same idea pointed outward: its result cannot hold anything but a price
key, so the EXT-003 payload guarantee is structural rather than a review item.

### 1.5 The factory had to be split to be testable, and the remaining gap is named

`NetworkConfig` refuses a cleartext host; MockWebServer serves cleartext. The two cannot meet, so
`create()` could not be driven from a test. Rather than add an `SSLSocketFactory` seam — a hole in
the one file whose purpose is to prove there are none — the Retrofit binding moved into an `internal
retrofitApi(baseUrl, client)` the suite calls directly, and `client(config)` became `internal` so a
test can assert a config really becomes a pinned, five-second-bounded, cacheless client.

**What that leaves untested is the TLS handshake and pinning, and nothing else.** Every other line
on the path is the shipping one. Recorded in ADR-0030 under "what is not delivered".

### 1.6 Daily, not quarter-hourly; and the first constrained worker

`MarketPriceWorker` is the eighth worker and the first to carry `Constraints(NetworkType.CONNECTED)`.
The other seven must not: they are pure local computation, and gating a net-worth snapshot on
connectivity would break airplane mode, which is the whole of P-04.

It runs daily. §16.1's fifteen-minute crypto cadence is *while the app is open*, which `refreshNow()`
covers — enqueued once per unlock from inside `AppLockGate`. A quarter-hourly background job would
wake the phone ninety-six times a day to reprice what nobody is looking at, and WorkManager's floor
is fifteen minutes regardless. **No "already refreshed this session" flag**: the repository's TTL gate
is strictly tighter, because it knows each instrument's last fetch across process deaths.

---

## 2 · Flow changed this session

New section **FLOW.md §3.2** — the price refresh, the only path in the app that can open a socket:

```
CfoApplication.onCreate() → MarketPriceWorker.schedule(context)
  → enqueueUniquePeriodicWork("market-price-refresh", KEEP, 1 day, Constraints(CONNECTED))

MainActivity.onCreate() → AppLockGate { LaunchedEffect(Unit) } → MarketPriceWorker.refreshNow(context)
  → enqueueUniqueWork("market-price-refresh-now", KEEP, one-time, same constraint)

MarketPriceWorker.doWork()
  → sessionLock.isUnlocked.value == false → Result.retry()          (SEC-002, before injection)
  → repository.get().refresh()
      → GATE 1  consents.observe(MARKET_DATA).first()  not granted → Ok(0)   (P-01, no request built)
      → GATE 2  holdingDao.distinctPriceKeys(profile)  empty       → Ok(0)   (EXT-003)
      → GATE 3  holdingDao.forProfile(profile) → engine.priceFreshness(...) → nothing due → Ok(0)
      → api.quotes(keys ∩ due) → MarketDataFactory.create(UNCONFIGURED) → no client constructed
      → holdingDao.updatePriceByKey(profile, key, paise, asOf, fetchedAt)
```

Extended on the read path (already in §2.3): `RoomInvestmentRepository.observeForAccount` now emits
`PricedHolding(performance, freshness)`, and `HoldingsScreen.PriceAge` renders the verdict beneath
each value. The label and the refresh decision come from the same engine call, so they cannot
disagree.

---

## 3 · Code changed this session

### Commit 1 — schema, model, rulebook, engine (PR 1)

| Path | What it does now |
|---|---|
| `core/model/PriceKey.kt` | **New.** Value class; `[a-z0-9._:-]{1,64}`. The charset **is** the EXT-003 control |
| `core/model/Investment.kt` | `InvestmentHolding` gains `priceKey`, `priceFetchedAtUtcMillis`; a fetch stamp implies a price |
| `core/database/entity/Entities.kt` | `price_key` TEXT, `price_fetched_at_utc_millis` INTEGER, both nullable, no index |
| `core/database/migration/Migrations.kt` | `MIGRATION_18_19` — two `ALTER TABLE ADD COLUMN`; fixed `VERSION_18`'s off-by-one doc |
| `core/database/CfoDatabase.kt` | `VERSION = 19` + version log |
| `core/database/schemas/…/19.json` | Exported and committed |
| `ai/rules/rules-kb.json` | `RULE-PRICE-STALE` v1.0; `_meta.version` → 1.14.0 |
| `domain/engines/investment/PriceFreshnessRules.kt` | **New.** Typed mirror of both thresholds |
| `domain/engines/investment/PriceFreshness.kt` | **New.** `PriceVerdict`, `PriceFreshnessInput`, the arithmetic; `today` is an argument, never read (TIM-001) |
| `domain/engines/investment/InvestmentEngine.kt` | Fourth operation `priceFreshness` — total over its input |
| `data/repository/PricedHolding.kt` | **New.** `(performance, freshness)`; freshness never null |
| `data/repository/InvestmentRepository.kt` | Emits `PricedHolding`; `saveHolding` clears the fetch stamp when the price changed |
| `feature/accounts/HoldingsScreen.kt` | `PriceAge` composable; finally uses the dead `holdings_priced_on` string |

### Commit 2 — `:core:network` and the fetch path (PR 2)

| Path | What it does now |
|---|---|
| `core/network/build.gradle.kts` | Retrofit, OkHttp, kotlinx.serialization; a comment recording that `okhttp-logging` is pinned and deliberately unused |
| `core/network/src/main/AndroidManifest.xml` | **New.** The only `INTERNET` permission in thirty-five modules |
| `core/network/MarketDataApi.kt` | **New.** `quotes(Set<PriceKey>)` — a parameter type that cannot hold a rupee amount |
| `core/network/NetworkConfig.kt` | **New.** Refuses cleartext; refuses a configured host with no pins; `UNCONFIGURED` |
| `core/network/MarketDataFactory.kt` | **New.** The only expression in the repo producing an `OkHttpClient`; unreachable unconfigured |
| `core/network/RetrofitMarketDataApi.kt` | **New.** Maps DTO → domain; three refusals; no exception escapes |
| `core/network/UnconfiguredMarketDataApi.kt` | **New.** `Err(Network(retryable = false))`, instantly |
| `core/database/dao/Daos.kt` | `distinctPriceKeys` (cannot return anything else) and `updatePriceByKey` (cannot reach `name`) |
| `data/repository/MarketPriceRepository.kt` | **New.** Three gates, then one `UPDATE` per instrument |
| `app/di/NetworkModule.kt` | **New.** The whole network object graph in one short file |

### Commit 3 — the worker, the wiring and the record (PR 3)

| Path | What it does now |
|---|---|
| `app/work/MarketPriceWorker.kt` | **New.** Eighth worker, first with a constraint; `schedule` + `refreshNow` |
| `app/CfoApplication.kt` | Schedules it unconditionally, alongside the other seven |
| `app/MainActivity.kt` | `LaunchedEffect` inside `AppLockGate` → `refreshNow` — once per unlock (API-002) |
| `docs/adr/0030-…md` | **New.** Seven decisions, and what is not delivered |
| `DECISIONS.md` / `FLOW.md` / `CHANGELOG.md` / `VERSION` | Index row, §3.2, the 0.6.5 entry, 0.6.5 |

---

## 4 · Verification

| Gate | Result |
|---|---|
| `ktlintCheck detekt` | **OK** |
| `lintDebug` (five custom detectors) | **OK** |
| `unitTests` | **OK** — whole suite |
| `koverVerify` | **OK** — engine ≥ 85%, money math 100% |
| `:core:database:connectedDebugAndroidTest` | **OK** — 23 tests, 0 failures, including `migrate18To19_keepsHandTypedPricesAndAddsSomewhereToRecordAFetch` |
| `:app:installDebug` + launch on `CfoTest` | **OK** — no `FATAL EXCEPTION`, no Hilt `MissingBinding`; the new `NetworkModule` graph assembles |
| `dumpsys jobscheduler` | **OK** — of the app's nine scheduled jobs, **exactly one carries `CONNECTIVITY`**. The ADR-0030 §7 claim, observed on the device |
| `ai/**/*.json` parse | **OK** |

### Gates broken on purpose (ADR-0005)

| Break | Observed |
|---|---|
| Consent gate replaced with `if (false)` | 2 tests red (`with consent revoked nothing is asked`, `an unreadable consent store is not a grant`) — the fake api **throws** when called, so the gate is proved, not described |
| TTL gate: dropped `&& freshness.value.refreshDue` | 1 test red (`a price fetched a minute ago is not fetched again`) |

Both restored and re-run green.

### A bug the pre-merge gate found — a stale screenshot baseline, and the gate that never ran

`verifyPaparazziDebug` failed on `DashboardScreenshotTest.empty_light`, by 1.15%. **Not from 6.5.**
The recorded PNG was last written by issue 5.4 (`c644dd4`); the Settings screen (`60b26c8`) then
added a **Settings** button to the dashboard and reworded two empty-state lines, and the baseline was
never re-recorded. It shipped to `dev` and sat there.

It shipped because **nothing local ran the check**. Paparazzi compares against its PNGs only when
`verifyPaparazziDebug` is in the task graph; a plain `testDebugUnitTest` renders and asserts nothing.
That task lived only in `/pre-merge` — a checklist a human runs — and in `ci.yml`, which has never
executed on a GitHub runner. So every gate a developer is told to run stayed green while the recorded
image no longer showed the app.

**Both halves fixed:**

1. The baseline was re-recorded and visually checked — the new image has the Settings button and the
   current copy. Exactly one PNG changed, so no other screen had drifted.
2. `verifyPaparazziDebug` was added to the root `unitTests` task, by name-match alongside
   `testDebugUnitTest` and `test` — the same mechanism, and for the same reason, that task already
   existed for. Paparazzi reuses the test task, so this enables verification rather than running the
   suite twice.

**The new gate was broken on purpose** (ADR-0005): the stale PNG was restored, `./gradlew unitTests`
was run, and it failed on `empty_light` — where before it passed. Restored and green.

This is the third instance of the pattern audit G-01 named: a gate that is documented, believed, and
executed by nothing. Screenshot tests are the DoD's evidence for dark mode and 200% font (§4.2), so a
baseline nothing compares against is a vacuous gate in a different costume.

### Honest caveats — recorded, not worked around

1. **AC-1 cannot be demonstrated.** There is no proxy. The client is made structurally correct and
   provably inert; it is not made live.
2. **A real airplane-mode test is structurally unmeetable here** — there is nothing to disconnect
   from. `server.shutdown()` is the stand-in and is labelled as such in the test file.
3. **TLS and pinning are untested**, for the reason in §1.5 above. The handshake is the entire gap.
4. **A live fetch could not be exercised on the device.** What was observed instead: the app launches
   with the new graph, the constrained job reaches the platform, and the staleness label is covered
   by Compose tests in `HoldingsScreenTest`.

---

## 4.5 · Post-merge follow-ups

### Issue 6.7 filed — the missing proxy

`6.7 Market-data backend proxy (§22)` now exists in the backlog (86 issues), depending on 6.5. It
carries the API contract the client already parses, the pinning and statelessness requirements, and
an explicit scope note: **the deliverable is a service and its deployment, not Android code** — this
repository is Android-only and has no server module, so the issue's first decision is where the
service lives and who hosts it. Until it lands, 6.5's AC-1 stays undemonstrable.

### A generator that destroyed completion records — the same half-applied guard, again

Adding 6.7 meant regenerating the backlog, and `gen_issue_docs.py` **blanked the completion record of
28 finished issues in one command** — the ticked acceptance criteria and the evidence under each one.
Caught by reading the diff before committing, and reverted.

The cause is written in the script's own comment: issue 2.5 hit this exact hazard, and the guard it
added — "never overwrite a file that has recorded progress" — was applied **only to trackers**. Issue
files get hand-annotated the same way and were left exposed. A guard applied to half of what it names
reads as protection while protecting half.

The fix is now content-based rather than marker-based, because a first attempt keyed on
`**Status:** Todo` still clobbered six files that carried hand-written tables under an untouched
status line. The generator renders the file, compares, and preserves anything that differs — 25 issue
files and 42 trackers are now reported as preserved on every run. The cost is that a deliberate edit
to a record no longer propagates silently; you delete the file to regenerate, exactly as trackers
have worked since 2.5.

### `CfoSmokeTest` — improved, and honestly **not** root-caused

The instrumented failure reported earlier was real and reproduces reliably: clean install passes,
a second run without `pm clear` fails. Two defensible fixes went in — the demo-seeding waits got
their own 60s budget (the failing run died on a 20s budget at 21.3s, close enough that it would have
gone on failing intermittently anyway), and the onboarding precondition is now asserted with a
message naming the cause instead of timing out cryptically.

**Neither fixed it.** The precondition does not fire on the failing run, and 60s is no more enough
than 20s was. Four hypotheses were killed by evidence — a write on the render path, the demo's own
seeded rules (`name = null`, and `observeDecidedNames` filters `name IS NOT NULL`), duplicated seed
rows (ids are deterministic and upserted), and timing. Going further needs a diagnostic from inside
an instrumented build, because the app's own privacy design blocks every external route:
`uiautomator dump` returns an empty hierarchy on a secure window, and the database is SQLCipher.

**Measured, after ten controlled runs: it fails ~60% of the time.** Ten consecutive cold-install
runs in one fixed configuration, unchanged code — **six failed** (runs 2, 3, 5, 7, 9, 10), always
this test, always the same signature. The other test in the class passed 10/10, so the app boots and
opens its database every time; it is specifically the recurring proposal that does not appear.

Two earlier explanations recorded during the session were wrong: state left by a manual launch, and
then a clean-versus-cold-install discriminator. The measurement retires both — the runs that looked
like a pattern were the 40%.

Also ruled out by evidence: **issue 6.5's `MarketPriceWorker` is not the cause.** Disabling its
scheduling still reproduces the failure, so the eighth worker is not necessary for it.

The live suspicion, untested: `observeRecurringProposals` combines two Room Flows, and a cold first
launch has eight workers competing for one freshly created SQLCipher database. A first emission
pairing seeded candidates with a pre-seed decided-names list would render; the reverse ordering
would not. That is the shape of a race that flips with load.

Both wrong stories are corrected in the test's KDoc rather than quietly deleted — a confident wrong
lead costs the next reader more than no lead at all. **It is an open, flaky defect, and a single
green run of this test does not mean anything.**

---

## 5 · Quiz

1. Why does revoking MARKET_DATA keep the price when revoking SMS consent deletes the drafts?
2. What stops a background refresh from reverting a rename the user made a moment earlier — and why
   is it not a lock?
3. `refresh_minutes` and `stale_after_days` both describe "how old is too old". Why are there two?
4. Why is `MarketPriceWorker` the only worker in the app allowed to carry a constraint?
5. `MarketDataFactory.create` was split so a test could reach the Retrofit binding. What exactly does
   that leave untested, and why was the alternative worse?

<details><summary>Answers</summary>

1. An SMS draft is an inference about the user drawn from data they withdrew permission to read; a
   gold price is a public fact about gold. The private thing is the *request*, which the consent
   stops. Deleting the price would make a portfolio value vanish as a side effect of a privacy
   toggle.
2. `updatePriceByKey` is an `UPDATE` of four named columns; `name` and `asset_class` do not occur in
   the statement, so no interleaving can write them. A lock would be a promise; this is arithmetic.
3. They answer different questions — *is it worth a network call* versus *should the user be warned*.
   At fifteen minutes crypto is permanently stale; at one day it never refreshes.
4. Because it is the only one whose work is impossible without a network. The other seven are pure
   local computation, and constraining them would break airplane mode (P-04).
5. Only the TLS handshake and certificate pinning. The alternative was an `SSLSocketFactory`
   injection seam in the one file whose job is to prove the app can reach exactly one host.
</details>
