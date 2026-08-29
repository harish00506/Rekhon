# ADR-0030 — The market-data client ships unconfigured, and revoking consent keeps the cached price

**Status:** Accepted · 2026-08-29 · Issue 6.5 (gold/crypto valuation via market API)

**Requirements:** FR-INV-004, §16.1, §22, API-001, API-002, EXT-001, EXT-003, P-01, P-04, P-08

---

## Context

Issue 6.3 gave a holding a per-unit price and the day it was observed. Issue 6.4 split the portfolio
by asset class. Both read a price the **user typed by hand**, and nothing aged it: a price entered in
July still read as fact in September, with no indication it was two months old.

Closing that needs a market price, and §16.1 says where one comes from: gold and silver end-of-day,
crypto every fifteen minutes, **through our own backend proxy** (§22). EXT-001 forbids the app
talking to a data vendor directly.

**The proxy does not exist, and no issue in the eighty-five-issue backlog builds it.**

That is the whole of the difficulty, and it is not a difficulty that goes away by being restated. So
this ADR records what was built, and — as plainly — what was not.

## Decision

### 1 · The client ships unconfigured, and that is a supported state

`NetworkConfig.UNCONFIGURED` has a blank base URL. `MarketDataFactory.create` branches on that
first and totally: with no host it returns `UnconfiguredMarketDataApi`, and **nothing below that
line runs** — no `OkHttpClient`, no connection pool, no DNS resolver, no socket. The app therefore
does not merely fail to reach a backend; it constructs nothing capable of reaching one.

`NetworkModule` in `:app` is the entire switch. When a proxy exists, its URL and pins go into one
`@Provides` and no other line in the app changes.

**What was rejected:** pointing Retrofit at AMFI or a crypto exchange directly as a stopgap. It
violates EXT-001 outright, sends every user's instrument list to a third party (P-01), and — the
part that matters most — is very hard to unpick once shipped, because by then it works.

### 2 · A configured host cannot be expressed without certificate pins

§22.1 requires TLS 1.3 with pinning. There is no server, so there is no certificate, so no real pin
can be written today. What **can** be guaranteed, and is, is that a base URL cannot be configured
without one: `NetworkConfig`'s `init` refuses a non-blank host that is not `https` or that carries
no pins.

**What was rejected:** shipping a placeholder pin. A pin that matches nothing reads as a control
while protecting nothing, and the next reader would take the requirement for satisfied.

### 3 · Revoking MARKET_DATA stops fetching and keeps the cached price

This is the central argument of this record, because it **deliberately diverges from issue 3.9**,
where revoking SMS consent deletes the pending drafts (`SmsConsentWatcher`).

The two cases look alike and are not:

- An SMS draft is an **inference about the user**, drawn from data they have withdrawn permission to
  read. P-01 requires it to go.
- A gold price is a **public fact about gold**. It says nothing about the user. What is private is
  the *request* — the fact that this device asked about these instruments — and the consent gates
  exactly that: with the toggle closed, `MarketPriceRepository.refresh()` returns before a request
  is built.

Wiping the stored price on revocation would make the user's portfolio value vanish as a side effect
of a privacy toggle. Instead the label simply keeps ageing — "as of 12 Jul · 41 days old" — which is
the truth, and which is more informative than a blank.

**So there is no `MarketDataConsentWatcher`,** and that absence is a decision rather than an
omission.

### 4 · Staleness and refresh cadence are two rulebook numbers, not one

`RULE-PRICE-STALE` (v1.0, rules-kb 1.14.0) carries both:

- `refresh_minutes` — *is it worth a network call?* gold 1440, crypto 15
- `stale_after_days` — *should the user be warned?* gold 3, crypto 1, default 7

One value cannot do both: at fifteen minutes crypto is permanently stale; at one day it never
refreshes. Both live in `ai/rules/` because they are financial thresholds (CLAUDE.md §6), and
`RulebookDriftTest` fails the build if the Kotlin mirror and the JSON disagree.

The comparison lives in the **engine**, not the repository. A `now - fetchedAt > TTL` written in
`:data:repository` would be a financial constant with no drift gate on it.

### 5 · The price columns have a second, narrower writer

`MarketPriceRepository` is its own class, not a method on `InvestmentRepository`. The two own
different columns of one table: this one owns the price columns, that one owns the row — name, class,
lots, lifecycle.

`InvestmentHoldingDao.updatePriceByKey` makes the split structural. It is an `UPDATE` of four named
columns, and it **cannot reach `name` or `asset_class`** — not by convention, but because those
identifiers do not occur in the statement. A refresh landing while the user renames a holding
therefore cannot revert the rename, however the two interleave. A read-modify-`upsert` would have,
and `upsert` uses `REPLACE`.

`distinctPriceKeys` is the same idea pointed outward: its result type cannot hold anything but a
price key, so the request payload is identifier-only by construction (EXT-003) rather than by
review.

### 6 · A null price key is the opt-in switch

A hand-priced holding has no key, is not returned by `distinctPriceKeys`, and is therefore never
overwritten by a refresh. Replacing a number the user typed with one they did not is the worst
outcome available here, and the guard against it is the same query that builds the request.

### 7 · The daily job is the app's first constrained worker

`MarketPriceWorker` carries `NetworkType.CONNECTED`. The other seven workers must not: every one of
them is pure local computation, and gating a net-worth snapshot on connectivity would break the app
in airplane mode, which is the whole of P-04.

It runs **daily**, not quarter-hourly. §16.1 gives crypto fifteen minutes *while the app is open*,
and `refreshNow()` — enqueued once per unlock, inside `AppLockGate` — is where that lives. A
fifteen-minute background job would wake the phone ninety-six times a day to reprice something
nobody is looking at.

It is **scheduled unconditionally**, even for a user who has not opted in, following `SmsScanWorker`:
scheduling on grant and cancelling on revoke puts the consent rule in a second place and orphans a
job on any path that forgets to cancel. A job that finds the gate closed opens no socket.

There is **no "already refreshed this session" flag**. The repository's TTL gate is strictly tighter:
it knows when each instrument was last fetched, across process deaths, which a session flag does not.

## Consequences

### What this buys

A hand-entered price now carries its age on the holdings screen, which is real user value shipped
today with no backend. The refresh path exists, is fully tested, and is inert. The day a proxy is
configured, one `@Provides` changes.

### What is **not** delivered, stated plainly

- **AC-1 of issue 6.5 — "prices come through our backend proxy" — cannot be demonstrated.** There is
  no proxy. It is made structurally true (the client can only ever be pointed at one host, pinned)
  and provably inert. It is not made live.
- **TLS and certificate pinning are untested**, and are the only part of `:core:network` that is.
  MockWebServer serves cleartext and `NetworkConfig` refuses a cleartext host, so the two cannot
  meet; trusting a test certificate would mean a seam for injecting an `SSLSocketFactory`, which is
  a hole in the one file whose job is to prove there are none. The suite drives the production
  converter, mapper and client-construction through an `internal` seam below the handshake, and
  `server.shutdown()` stands in for a dead host. **The gap is the handshake and nothing else.**
- **A real airplane-mode test is structurally unmeetable here.** There is nothing to disconnect
  from. `server.shutdown()` is the honest stand-in and is labelled as such in the test file.
- **Gold and crypto accounts with no holdings are not market-valued.** `accountAsPosition` values
  them at their ledger balance, which is a rupee figure with no gram count behind it; multiplying a
  balance by a gold price is nonsense. The unit of market valuation is the holding, because the
  holding is the thing with a quantity.

### Also rejected, each for a recorded reason

- **`okhttp-logging`** — it is pinned in the catalog and deliberately unused. An interceptor logging
  a request that carries the user's instrument list is a §21.6 violation waiting for someone to
  enable it in a debug build.
- **An OkHttp disk cache** — it would write that same list to a plaintext file outside SQLCipher.
  §22.2 mentions ETag caching; this app's cache is the encrypted price columns, which is both
  private and the thing the UI actually reads.
- **A circuit breaker** — WorkManager backoff plus the TTL gate already bound volume to one call per
  interval.
- **A `price_source` column** — derivable from the key's prefix.
- **An index on `price_key`** — the table holds tens of rows and the existing `(profile_id,
  deleted_at)` index covers the filter. An index would cost a write on every priced row on every
  refresh to speed up a scan of thirty.

## Alternatives considered

| Option | Why not |
|---|---|
| Fetch from AMFI / CoinGecko directly | EXT-001 and P-01 violation; hard to unpick once shipped |
| Ship no network code until a proxy exists | The staleness label is real value today, and the schema change it needs is the irreversible part — better done deliberately than under deadline |
| One TTL for refresh and staleness | Fifteen minutes makes crypto permanently stale; one day never refreshes |
| Wipe prices on consent revocation | A public fact about gold is not an inference about the user; the portfolio value would vanish as a side effect of a privacy toggle |
| Price columns on `InvestmentRepository` | Two writers on one row with `REPLACE` semantics; a refresh would revert a concurrent rename |
| A 15-minute periodic worker | 96 wake-ups a day to reprice what nobody is looking at; WorkManager's floor is 15 minutes anyway |
| Test TLS with a trusted test certificate | Requires an `SSLSocketFactory` seam in the one file whose purpose is to have no seams |

## References

- SRS §16.1 (market data cadence), §22 (Market Data API), API-001/API-002, EXT-001/EXT-003
- [ADR-0013](0013-read-sms-play-policy-and-the-gated-inbox.md) — the unconditional-scheduling precedent
- [ADR-0017](0017-budget-thresholds-stay-a-typed-mirror.md) — never bump a shipped rule row's version
- [ADR-0027](0027-asset-class-is-a-column-on-the-holding.md) — asset class lives on the holding
- [ADR-0029](0029-the-portfolio-is-the-investable-accounts.md) — what the portfolio is
- `ai/rules/rules-kb.json` — `RULE-PRICE-STALE` v1.0
