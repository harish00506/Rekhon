# 2026-08-30 — Issue 6.7: the §22 market-data backend proxy

Issue 6.5 shipped a client for a service that did not exist. [ADR-0030](../adr/0030-the-market-data-client-ships-unconfigured.md)
said so, and listed three things it could not deliver: 6.5's first acceptance criterion, a tested TLS
handshake ("the gap is the handshake and nothing else"), and a real airplane-mode case. This session
built the service and closed all three — and found two things about the app that no fixture would
ever have shown.

---

## 1 · Decisions this session

### 1.1 The proxy is a Gradle module in this repository

`:backend` — pure Kotlin/JVM, Ktor on Netty, outside the §21.2 app graph, in no APK. The user chose
Ktor over a Cloudflare Worker; the reason it turned out to matter is that `:backend` can depend on
**`:core:model`**, which ARC-002 already keeps Android-free. So `PriceKey`'s charset (the EXT-003
control) and paise (MNY-001) have **one definition across both sides of the wire**. A TypeScript
worker would have put the two most important types in the system in a second language with nothing
keeping them in step.

It also inherits `cfo.kotlin.library` — ktlint, detekt, the custom lint detectors (so
`CfoMoneyAsFloatingPoint` runs on the server too) and an 85% Kover floor — and the root `unitTests`
task matches by task *name*, so the module was picked up with no edit at all.

→ `DECISIONS.md`, [ADR-0032](../adr/0032-the-proxy-lives-in-this-repo-and-the-pin-set-is-two.md)

### 1.2 The wire contract is a file, not a description

`contracts/market-prices-v1.json` is read by `:backend`'s suite **and** `:core:network`'s, through a
`cfo.contracts.dir` system property each module's build script sets. The two cannot share a type —
`:core:network` is an Android library whose DTO is `internal` — so neither side owns the shape; the
file does. Rename a field on either side and one of the two goes red. Same idea as `RulebookDriftTest`.

The `:backend` suite also asserts the contract's prices are **positive JSON integers with no decimal
point**. That is the one property whose violation would be silent: a `Double` would corrupt every
derived figure and every test would read the same wrong number.

### 1.3 Two pins, and the rotation is actually exercised

§22.1 wants at least two — current plus rotation — because one pin bricks every install on rotation.
`./gradlew :backend:devTls` mints **two** keypairs and computes both SPKI pins in-process
(`PublicKey.getEncoded()` *is* the SubjectPublicKeyInfo DER OkHttp hashes — no openssl, which matters
on Windows).

**A pin set whose second member has never been exercised is a rotation plan nobody has tested**, so
the verification restarts the server on the second keypair and confirms the app keeps working without
being rebuilt. It does.

### 1.4 The handshake is tested without the seam ADR-0030 refused

That ADR rejected an `SSLSocketFactory` seam "in the one file whose job is to prove there are none".
Android's `<debug-overrides>` trust anchors apply **only** when `android:debuggable` is true, so the
gap closes without touching production code. Everything `devTls` writes is gitignored — a fresh clone
has no overrides at all.

### 1.5 An unconfigured vendor is a supported state, and a dead one costs only its namespace

With no `CFO_GOLD_API_KEY` the gold source is **not registered**; `gold:` keys come back unpriced,
which the client already handles by keeping the cached price (P-04). Registering it with an empty
token would earn a `401` forever and look identical from outside. `PriceCatalogue` isolates each
source, and intersects every answer with what was asked — a source cannot widen the response, which
is a bug the client would hide because it drops unasked-for quotes itself.

### 1.6 `fx:` is ECB reference rates; policy rates are not served at all

RBI publishes no stable machine-readable feed — an ASPX page is a layout, not an interface. The
substitution is recorded rather than hidden. **Policy rates are excluded on a type argument**: a rate
is basis points (MNY-002) and this endpoint's only money field is paise. Bending one into the other
is exactly the quiet confusion MNY-001/MNY-002 exist to prevent.

### 1.7 No request logging, by construction rather than by configuration

Ktor's `CallLogging` plugin is **not installed**, so there is no logger to silence; `logback.xml`
carries no MDC in its pattern; and no error body ever echoes a requested identifier — the bodies are
constant strings, chosen over the more helpful `"unknown key: gold:inr.gram.24k"`. That last one is
testable, so it is tested. `ETag` is deliberately omitted: ADR-0030 rejected a client disk cache, so
nothing would ever send `If-None-Match` back.

---

## 2 · What the live vendors taught us

Both of these produced a **silent, plausible-looking wrong answer**, and both were found only by
pointing the thing at the real internet.

### 2.1 AMFI's header understates its own column count

The header names six columns; the file serves **eight** (`Plan` and `Option` were inserted and the
header never updated). A parser indexing NAV at column 4 reads `"Direct Plan"`, makes no number of it,
and **silently prices nothing**. The parser now reads NAV and date from the *end* of each row. It was
written the other way first, against a fixture built from the documented header, and every test
passed. The fixture is now built from the live file.

### 2.2 AMFI serves an incomplete certificate chain

`portal.amfiindia.com` omits its intermediate CA. `curl` and browsers chase it through AIA; the JDK
does not by default. Every NAV fetch died with `PKIX path building failed` in ~300 ms — indistinguishable
from "unknown ISIN". `Main.kt` now enables AIA CA-issuer fetching. **This does not weaken
verification**: the chain still has to reach a trusted root; the only change is that the JVM fetches
an intermediate the server should have sent.

### 2.3 The market-data path had no way in — and this was 6.5's, not 6.7's

**No `:feature:*` code ever set a holding's `price_key`.** Issue 6.5 shipped the column, the
migration, the DAO, the repository, the worker, the client — and even the string resources
`holdings_price_key` / `holdings_price_key_help` — and no field. Every holding had a null key,
`distinctPriceKeys` returned empty, and `refresh()` returned `Ok(0)` at GATE 2 before a request was
built. **The whole path was unreachable, and shipping the proxy would not have changed that.**

Found by pointing the app at a live proxy and watching nothing happen. The field is now wired, which
is why a UI change appears in an issue whose scope was "a service and its deployment". A **malformed**
key is refused rather than dropped: saving `null` would leave a holding that looks linked to a market
and never updates. The fake repository in `:feature:accounts`' tests was also discarding `priceKey`,
which is part of why nothing noticed.

This is the same shape as the governance gaps this project has recorded before — a control that reads
as present and is enforced by nothing.

---

## 3 · Flow changed this session

New — the server side, and the field that makes the client side reachable:

```
HoldingsScreen → HoldingEditorFields.PriceFields
└─ OutlinedTextField(state.priceKey) → HoldingsEvent.PriceKeyChanged
    └─ HoldingEditorState.toDraft()
        ├─ blank        → priceKey = null    "I price this by hand"; a refresh never touches it
        ├─ PriceKey(it) → HoldingDraft(priceKey = …)
        └─ malformed    → null draft → FIELD_HOLDING, nothing written

Main.main()
├─ enableIncompleteChainRecovery()                 AMFI omits its intermediate CA
├─ ServerConfig.fromEnv(System.getenv()).catalogue(OkHttpFetch, Clock(Asia/Kolkata))
│   ├─ CachingPriceSource(CoinGeckoCryptoSource, 15 min)   crypto:
│   ├─ CachingPriceSource(GoldApiSource, 1440 min)         gold:  ← only if a key is set
│   ├─ AmfiNavSource (caches its own 1.5 MB index, 6 h)    mf:
│   └─ CachingPriceSource(FxReferenceRateSource, 1440 min) fx:
└─ embeddedServer(Netty) { marketPrices(catalogue::quote) }

GET /v1/market/prices?ids=…
├─ blank / >100 ids → 400, naming no identifier
├─ invalid ids DROPPED, not fatal
└─ PriceCatalogue.quote → per-namespace, concurrent, each source isolated
    → filter to keys asked for → filter price > 0 → distinctBy(key)
    → Paise.parseRupees(vendor RAW TEXT) → BigDecimal HALF_EVEN → Long
    → 200 {"quotes":[…]} + Cache-Control: public, max-age=900

NetworkModule.provideNetworkConfig()
└─ BuildConfig.MARKET_BASE_URL blank → NetworkConfig.UNCONFIGURED  ← the shipping build
                              set   → NetworkConfig(baseUrl, pins) ← the one line ADR-0030 promised
```

→ `FLOW.md` §3.4 and the new §3.4.1

---

## 4 · Code changed this session

| Path | What it does now |
|---|---|
| `settings.gradle.kts` | **New.** `include(":backend")`, with the note on why it sits outside the §21.2 graph |
| `gradle/libs.versions.toml` | **New.** ktor 3.1.3 + logback 1.5.18, and a comment on the three ktor artifacts deliberately *not* pinned |
| `backend/build.gradle.kts` | **New.** The module, the `cfo.contracts.dir` property, and the `devTls` task |
| `backend/src/main/.../Main.kt` | **New.** Three-statement entry point, the TLS connector, and the AIA fix |
| `backend/src/main/.../ServerConfig.kt` | **New.** Environment → typed config → catalogue. An absent gold key means an unregistered source |
| `backend/src/main/.../PricesRoute.kt` | **New.** The one route. Constant error bodies that name no identifier |
| `backend/src/main/.../Wire.kt` | **New.** `PricesResponse` / `PriceQuote` — the mirror of 6.5's DTO |
| `backend/src/main/.../Paise.kt` | **New.** The only rupee→paise conversion. Takes raw text, never a `Double` |
| `backend/src/main/.../PriceSource.kt` · `PriceCatalogue.kt` · `CachingPriceSource.kt` · `HttpFetch.kt` · `AsOf.kt` | **New.** The seam, the routing and its two guarantees, the TTL cache, the HTTP edge, the date conversion |
| `backend/src/main/.../sources/*.kt` | **New.** CoinGecko, goldapi, AMFI, FX — four upstreams behind one shape |
| `backend/src/main/resources/logback.xml` | **New.** WARN, no MDC, and the note that no `CallLogging` plugin exists to silence |
| `backend/src/test/**` | **New.** 86 tests: route, catalogue, cache, config, the HTTP edge, four vendors against recorded payloads, and the contract |
| `backend/README.md` · `Dockerfile` | **New.** How to run it, the four drills, and the ordered deployment steps |
| `contracts/market-prices-v1.json` · `contracts/README.md` | **New.** The wire shape both suites read |
| `core/network/.../MarketDataApiTest.kt` | + the contract-golden case, so the client is held to the same bytes |
| `core/network/build.gradle.kts` | + the `cfo.contracts.dir` system property |
| `app/build.gradle.kts` | `buildConfig = true`, two `buildConfigField`s defaulting to empty, `versionCode` 27 |
| `app/src/main/.../di/NetworkModule.kt` | `provideNetworkConfig` reads `BuildConfig`; blank still means `UNCONFIGURED` |
| `feature/accounts/.../HoldingsUiState.kt` | + `priceKey` on the editor state, + `PriceKeyChanged` |
| `feature/accounts/.../HoldingsViewModel.kt` | Handles the event, loads the existing key, and parses it in `toDraft` — malformed is refused, blank is hand-priced |
| `feature/accounts/.../HoldingEditorFields.kt` | + the field that makes the whole path reachable |
| `feature/accounts/src/main/res/values/strings.xml` | The 6.5 strings are now referenced; a note says they were not |
| `feature/accounts/src/test/.../FakeInvestmentRepository.kt` | Carries `priceKey` through instead of dropping it |
| `feature/accounts/src/test/.../HoldingsViewModelTest.kt` | + 4 tests: the key reaches the holding, blank stays hand-priced, malformed is refused, editing shows it |
| `.gitignore` | + `backend/local/` and `app/src/debug/` — every artefact `devTls` writes |
| `docs/adr/0032-*.md` · `DECISIONS.md` · `FLOW.md` · `CHANGELOG.md` · `VERSION` | The records |

---

## 5 · Quiz — what a reader should be able to answer

1. **Why can a `Double` in the proxy's JSON never be caught by a test in the app?**
   Because every test would parse the same wrong number. The corruption is silent and total, which is
   why the contract test asserts the literal has no decimal point rather than asserting a value.
2. **Why are there two dev keypairs rather than one?**
   Because §22.1 wants a pin set of at least two, and a second pin that has never been exercised is a
   rotation plan nobody has tested. Drill 3 restarts the server on the second one and the app — not
   rebuilt — keeps working.
3. **Why does the app still work when the proxy is down, and why is that not the same as working
   when the pin is wrong?**
   Both return `Ok(0)` and keep the cached price, which is the point (P-04). They differ in what was
   proved: the first shows the app survives a missing backend, the second shows the pinning is load-
   bearing rather than decorative.
4. **6.5 shipped the column, the DAO, the repository, the worker, the client and the strings. What
   did it not ship, and how would you have found out?**
   The editor field. Only by pointing the app at a working proxy and watching nothing happen — every
   test passed, because the tests' own fake was dropping `priceKey` too.
5. **Why is a malformed price key refused rather than dropped?**
   Dropping it saves `null`, which leaves a holding that looks linked to a market and never updates.
   The user would have no way to tell.
6. **The service caches prices in memory. Why is it still "stateless and holds no user financial
   data"?**
   Because what it holds is a public fact about an instrument, keyed by an identifier the vendor
   published. Nothing in it distinguishes one caller from another.
