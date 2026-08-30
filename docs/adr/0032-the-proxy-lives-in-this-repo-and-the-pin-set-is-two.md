# ADR-0032 — The §22 proxy lives in this repository, and a pin set of one is not a pin set

**Status:** Accepted · 2026-08-30 · Issue 6.7 (market-data backend proxy)

**Requirements:** §16.1, §22, API-001, API-002, EXT-001, EXT-003, MNY-001, MNY-002, TIM-001,
TIM-002, P-01, P-04, P-08

---

## Context

[ADR-0030](0030-the-market-data-client-ships-unconfigured.md) shipped a market-data client for a
service that did not exist, and said so plainly. Three things it recorded as undelivered:

- 6.5's first acceptance criterion — "prices come through our backend proxy" — **could not be
  demonstrated**, because there was no proxy.
- **TLS and certificate pinning were untested**, and were the only part of `:core:network` that was.
  "The gap is the handshake and nothing else."
- **A real airplane-mode test was structurally unmeetable.** There was nothing to disconnect from.

This issue builds the service. Everything below is either a decision that had to be made to do that,
or something the live vendors taught us that no fixture would have.

## Decision

### 1 · The service is a Gradle module in this repository, not a separate one

`:backend` is pure Kotlin/JVM running Ktor on Netty. It is **outside the §21.2 app graph** — nothing
in the app depends on it and it ships in no APK — but it is inside the build, which buys three things
that a separate repository would have cost:

- It depends on **`:core:model`**, which ARC-002 already keeps Android-free. So `PriceKey`'s charset
  (the EXT-003 control) and paise (MNY-001) have **one definition across both sides of the wire**
  rather than two that drift.
- It inherits the `cfo.kotlin.library` convention plugin, so ktlint, detekt, the custom lint
  detectors (`CfoMoneyAsFloatingPoint` runs on the server too) and an 85% Kover floor apply without
  anyone wiring them. The root `unitTests` task matches by task *name*, so it was picked up with no
  edit at all.
- The wire contract can be a file both suites read (§4 below).

**What was rejected:** a Cloudflare Worker in TypeScript. Cheaper to host and genuinely stateless by
construction, but it would have put the two most important types in the system — an instrument
identifier and a paise amount — in a second language, where nothing keeps them in step. It also
forces pinning against a CDN's leaf certificate, which rotates on the platform's schedule and not
ours; see §3.

### 2 · Four upstreams behind one shape, and an unconfigured vendor is a supported state

`PriceSource` is the seam where each vendor stops being special: `crypto:` (CoinGecko), `gold:`
(a keyed metals provider), `mf:` (AMFI), `fx:` (published FX reference rates).

`gold:` is the only one needing an API key, and **with no key the source is not registered at all**.
`gold:` keys then come back unpriced, which the client already handles by keeping its cached price
and ageing the label (P-04). That mirrors the client shipping unconfigured, and it is strictly better
than the alternative — registering the source with an empty token and earning a `401` on every
request forever, which looks identical from outside and is not.

**A dead vendor costs its own namespace and nothing else.** `PriceCatalogue` isolates each source, so
a metals API that is down does not take the crypto prices with it. And **a source cannot widen the
response**: whatever it returns is intersected with what was asked. The client drops an unasked-for
quote on arrival, so a widening bug would be invisible there and visible only here.

### 3 · Two pins, and both of them exercised

§22.1 wants TLS 1.3 with a pin set of **at least two** — current plus rotation — because a rotation
with one pin bricks every installed copy until the app is updated.

`./gradlew :backend:devTls` mints **two** keypairs, computes both SPKI pins in-process
(`PublicKey.getEncoded()` *is* the X.509 SubjectPublicKeyInfo DER that OkHttp hashes — no openssl,
which matters on a Windows machine), and writes a debug-only trust anchor.

**A pin set whose second member has never been exercised is a rotation plan nobody has tested.** So
the verification restarts the server on the *second* keypair and confirms the app keeps working
**without being rebuilt**. It does. That is the drill, and it is why there are two keys rather than a
comment saying there should be.

### 4 · The handshake is tested without the seam ADR-0030 refused

That ADR rejected an `SSLSocketFactory` seam "in the one file whose job is to prove there are none",
and that objection still stands. Android's `<debug-overrides>` trust anchors close the gap without
touching it: they apply **only** when `android:debuggable` is true, so nothing here can reach a
release build, and no production code changes.

Everything `devTls` writes — keystores, certificates, the network-security config, the debug manifest
— is **gitignored**. A fresh clone has no debug overrides at all and builds exactly as it does today.

**What was rejected:** committing the dev CA certificate. It would widen what every contributor's
debug build trusts, to no benefit.

### 5 · The wire contract is a file, not a description

`contracts/market-prices-v1.json` is read by `:backend`'s suite *and* by `:core:network`'s. Rename a
field on either side and one of the two goes red. The two sides cannot share a type — `:core:network`
is an Android library whose DTO is `internal`, and `:backend` is plain JVM — so neither owns the
shape; the file does. Same idea as `RulebookDriftTest`.

The `:backend` suite additionally asserts the contract's prices are **positive JSON integers with no
decimal point**. That is MNY-001 on the wire, and it is the one property whose violation would be
silent: a `Double` would corrupt every derived figure, and every test would read the same wrong number.

### 6 · The service is stateless in the sense EXT-003 means, and its cache does not change that

What it holds is a public fact about an instrument, keyed by an identifier the vendor published.
There is no device, no profile, no holding and no amount in it, and nothing in it distinguishes one
caller from another. It is a bandwidth optimisation with the lifetime of a process, and without it a
free vendor tier is gone by lunchtime.

**No request logging.** Ktor's `CallLogging` plugin is not installed at all, so there is no logger to
silence; `logback.xml` carries no MDC in its pattern, because a pattern that interpolates the MDC is
one plugin away from printing the query string. And **no error response echoes a requested
identifier** — the bodies are constant strings, chosen over the more helpful
`"unknown key: gold:inr.gram.24k"` deliberately. That last one is testable, so it is tested.

**No `ETag`.** §22.2 mentions conditional caching, but ADR-0030 rejected an OkHttp disk cache on the
client — it would write the device's instrument list to a plaintext file outside SQLCipher — so
nothing will ever send `If-None-Match` back. An ETag nothing validates is decoration.

### 7 · `fx:` is ECB reference rates, and policy rates are not served here at all

The design spec §11 names "rates (RBI)". **RBI publishes no stable machine-readable feed**: its
reference rates live on an ASPX page whose markup is a layout, not an interface, and a parser built
on it breaks the day somebody moves a table. So `fx:` reads a published daily reference rate from a
documented JSON API instead. The two differ by a few paise on the rupee; for valuing a holding that
is immaterial, and this is the record of the substitution rather than a silence about it.

**Policy rates — repo, MCLR — are not served by this endpoint.** A rate is a percentage, which
MNY-002 makes integer basis points, and this endpoint's only money field is `unit_price_minor`,
which is paise. Bending a rate into a price field so it fits would be exactly the quiet type
confusion MNY-001 and MNY-002 exist to prevent. A rate endpoint needs its own shape.

---

## What the live vendors taught us, and no fixture would have

These are recorded because each one produced **a silent, plausible-looking wrong answer**, and each
was found only by pointing the thing at the real internet.

### AMFI's published header understates its own column count

The header names six columns: `Scheme Code;ISIN…;ISIN…;Scheme Name;Net Asset Value;Date`. The file
serves **eight** — `Plan` and `Option` were inserted in the middle and the header was never updated.
A parser indexing NAV at column 4 reads `"Direct Plan"`, makes no number of it, and **silently prices
nothing at all**. The parser now reads NAV and date from the *end* of each row. It was written the
other way first, against a fixture built from the documented header, and every test passed.

### AMFI serves an incomplete certificate chain

`portal.amfiindia.com` omits its intermediate CA. A browser and `curl` do not notice, because the
platform trust store chases the missing certificate through the Authority Information Access
extension; the JDK does not, by default. Every NAV fetch died with `PKIX path building failed`, in
about 300 ms, and every `mf:` key came back unpriced — indistinguishable from "AMFI does not know
that ISIN". `Main.kt` now enables AIA CA-issuer fetching.

**This does not weaken verification**, and the distinction is the whole point: the chain still has to
reach a trusted root, and the only change is that the JVM will go and fetch an intermediate the
server should have sent. The alternatives were bundling AMFI's intermediate — which then expires on
somebody else's schedule — or trusting the host without checking it, which is what SEC-003 exists to
forbid.

### The market-data path had no way in

**No `:feature:*` code ever set a holding's `price_key`.** Issue 6.5 shipped the column, the
migration, the DAO, the repository, the worker, the client, and even the string resources for the
editor field — and no field. So every holding a user could create had `price_key = null`,
`distinctPriceKeys` returned empty, and `MarketPriceRepository.refresh()` returned `Ok(0)` at GATE 2
before a request was ever built. **The whole path was unreachable, and shipping the proxy would not
have changed that.**

It was found by pointing the app at a live proxy and watching nothing happen. Issue 6.7 wires the
field, which is why a UI change appears in an issue whose scope was "a service and its deployment".
The fake repository in `:feature:accounts`' tests was also dropping `priceKey` on the floor, which is
part of why nothing noticed.

A **malformed** key is now refused rather than dropped: saving `null` for a key the user typed would
leave a holding that looks linked to a market and never updates, which is worse than not offering the
field, because there would be no way to tell.

---

## Consequences

### What this buys

The §22 endpoint exists, serves integer paise from four live sources, and was driven from the app
over a **real pinned TLS handshake** — 0.01 BTC priced at ₹74,555.20, 1 ETH at ₹2,34,295.00, both
stamped with the proxy's own `as_of`. ADR-0030's three undelivered items are delivered:

| ADR-0030 said | Now |
|---|---|
| 6.5's AC-1 cannot be demonstrated | demonstrated on a device against a live proxy |
| TLS and pinning are untested — "the gap is the handshake and nothing else" | the handshake runs; a wrong pin blocks the fetch while the cached price stands |
| a real airplane-mode test is structurally unmeetable | run, and run against a killed host as well |

### What is **not** delivered, stated plainly

- **There is no deployed host.** Everything above was proved against a proxy on `localhost:8443`
  with a certificate this repository minted. The public deployment needs a hosting account and a
  domain; the runbook is `backend/README.md` and the steps are ordered, but they have not been run.
- **`gold:` is unproven end to end.** It needs a provider API key nobody has signed up for. The code
  path is tested against a recorded payload; the live call has never been made.
- **The pin-rotation story on a managed platform is advice, not experience.** The runbook says to pin
  the issuing CA's intermediates rather than the leaf, because a platform rotates the leaf on its own
  schedule. That is right, and it has been done here only with certificates we control.
- **The first `mf:` request after a restart takes about 3.5 seconds** — it pulls AMFI's whole file.
  API-001 gives the client five seconds, so on a slow network that first request can time out. The
  client keeps its cached price (P-04) and the next refresh finds a warm index and takes 0.2 s.
  Pre-warming at boot was rejected: it would download the file on every deploy whether anyone wants a
  NAV or not.
- **An intermittent crash was observed and is not attributed here.** On first launch after install,
  `AppLockGate` sometimes renders its content while the session lock still reads locked, and
  `CoreModule.provideDatabase` throws SEC-002. It reproduced twice, then zero times in eight further
  cold installs across both configured and unconfigured builds, and the stack trace contains nothing
  from this issue. It is recorded here because it was seen, not because it is understood.

### Also rejected, each for a recorded reason

| Option | Why not |
|---|---|
| Cloudflare Worker (TypeScript) | puts `PriceKey` and paise in a second language with nothing keeping them in step; forces pinning a CDN leaf |
| `ktor-server-content-negotiation` + `ktor-serialization-kotlinx-json` | the one route serialises with `Json.encodeToString` and `respondText`; fewer moving parts, and the exact bytes are what the contract test asserts |
| `ktor-server-status-pages` | one handler, so its error mapping is a `runCatching` in that handler |
| A second HTTP client for upstream fetches | OkHttp and MockWebServer are already pinned for `:core:network` |
| Deriving a gold price from a tokenised-gold coin | bakes that token's premium into somebody's net worth, silently |
| Deriving 22k gold from the 24k price by a 22/24 ratio | right about metal content, wrong about what a jeweller quotes; a subtly wrong valuation is worse than an absent one |
| Rejecting the whole batch on one malformed id | one bad id would cost the caller every other price in it |
| A `400` for an unknown instrument | an unrecognised instrument is an absent quote; the client already handles that |
| Committing the dev CA certificate | widens what every contributor's debug build trusts, for nothing |
| Pre-warming the AMFI index at boot | 1.5 MB on every deploy to save one request that already degrades correctly |

## References

- SRS §16.1 (cadence), §22 (Market Data API), API-001/API-002, EXT-001/EXT-003
- [ADR-0030](0030-the-market-data-client-ships-unconfigured.md) — the client this serves, and the
  three gaps this closes
- `backend/README.md` — how to run it, and the ordered deployment steps
- `contracts/README.md` — the wire contract both suites read
- `ai/rules/rules-kb.json` — `RULE-PRICE-STALE`, whose `refresh_minutes` the server's cache mirrors
