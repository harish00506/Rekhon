<!--
  Why:  CLAUDE.md §5 — any decision needs an ADR, and 13.6's AC2 names one explicitly
        ("an ADR + interface stub exist in v1").
  What: issue 13.6 — how Account Aggregator ingest is designed, why one small part of it had to
        ship immediately rather than wait, and the two §33 promises this found unkept.
  Result: a reader can see why a single enum constant was the urgent part of an unbuilt feature,
          and why the interface makes staleness and consent impossible to omit.
  Changelog: 2026-10-09 — Created.
-->

# ADR-0074 — AA is designed, and the reserved `source` value had to ship now

**Status:** Accepted · **Date:** 2026-10-09 · **Issue:** 13.6 · **SRS:** §16, §22, §33, EXT-001 · **Rules:** P-01, P-02, P-04, P-08, MNY-001, TIM-001, TIM-002

## Context

India's Account Aggregator framework is the consented, regulated way an app receives a user's bank
statements. It is also **the one functional gap between this product and every Indian competitor
that syncs an account** — INDmoney, Money View and ET Money all pull history this way, while this
app's only ingest paths are manual entry and on-device SMS parsing.

This issue's acceptance criteria scope it tightly: AA is *designed* via the backend with explicit,
revocable consent; imported transactions are tagged `source = AA`; it degrades to cached data with
a staleness label offline; and **"an ADR + interface stub exist in v1"**.

§33's forward-compatibility table makes two promises on AA's behalf:

> **Account Aggregator (AA)** — `transactions.source` enum includes reserved value `'aa'`;
> `import_batches` supports statement-grade provenance

Both were measured. **Neither was kept.**

## Decision

### 1 · The reserved `source` value ships now, and nothing else does

`TransactionSource` had seven values and none of them was `aa`.

That is not a cosmetic omission, and the reasoning is **already written in that very file**. The
enum carries `RECURRING_AUTO` with the note *"Nothing writes this yet, and it is here anyway"*, and
explains why:

> `fromStored` drops a value it does not recognise, and the mapper drops the whole row with it:
> adding the constant with its writer would mean an intervening build silently hiding rows a newer
> one had written. That is not hypothetical — omitting `DEMO` did exactly that in issue 3.1.

So the codebase established the principle, applied it to `RECURRING_AUTO`, and did not apply it to
AA — despite §33 naming the value explicitly.

**The consequence is worse here than anywhere it has bitten before.** When `DEMO` was missing, a
demo profile's transaction list rendered empty. When an AA build writes `source = 'aa'`, every
older build reading that database drops **the user's entire imported bank history**, silently, with
no error.

A reserved constant costs one line and cannot be wrong. The integration it anticipates is a quarter
of work. Those are not the same decision, and conflating them is how the gap survived to v0.13.4 —
so the constant ships with this issue and the integration does not.

### 2 · The interface makes the dangerous shapes unavailable

AC2 asks for a stub. A stub is a chance to decide the contract while it is still free to change, so
three things are designed to be **impossible** rather than discouraged:

| Risk | How the type prevents it |
|---|---|
| Fetching without consent | `statements(consent: ConsentHandle)` — there is no overload without one |
| Rendering AA data without saying how old it is | `FetchedStatements.fetchedAtUtcMillis` is non-null and `require`d positive. A nullable "maybe we know when" would be a staleness label nobody renders (P-04) |
| A bank identifier leaking into a log or a UI state | `ConsentHandle` holds **only** an id and an expiry — no account number, no bank name, no token (§21.6's logging ban). A test asserts the field list |
| Presenting a truncated window as a whole history | `complete: Boolean`, so a caller can say "there may be more" |
| A duplicate import doubling somebody's rent | `StatementLine.externalId` — the bank's own reference, which deduplication keys on. Matching by amount-and-date is how that goes wrong |

**`StatementLine` is deliberately not a `Transaction`.** A statement line is evidence of what a bank
says happened; a transaction is a row in this user's ledger with a category, a nature and a profile.
Converting one to the other involves decisions — dedupe, classification, which account — that belong
in a repository, not a network type.

### 3 · It goes through the app's own backend, never to an AA directly

Same rule as market data (EXT-001, issue 6.5): the app calls its own proxy, never a third-party
endpoint. An endpoint the app calls directly is one the user cannot audit and the project cannot
pin, and AA traffic is the most sensitive the product will ever carry.

`UnconfiguredAccountAggregatorApi` is the only implementation that ships, mirroring
`UnconfiguredMarketDataApi` exactly: it refuses immediately with `retryable = false`, and **no
client is constructed to reach it** — no OkHttp instance, no socket, no permission. "This build has
no bank connection" is true in the strongest available sense.

`retryable = false` is load-bearing. A missing backend is not transient, and a worker treating it
as retryable would back off and re-run for ever against a host that does not exist.

### 4 · The app's consent sits in front of the framework's

`ConsentFeature.ACCOUNT_AGGREGATOR` is new, default-off like every other (P-01).

It is **not redundant** with the AA framework's own consent artefact. That artefact has its own
purpose, scope and expiry, granted inside the AA flow and revocable there. This flag is the app's
gate in front of it, so revoking in this app stops it asking **even while an AA consent is still
live**. Two independent off-switches for the largest disclosure the product can request.

No `IS_ENABLED` constant was added, unlike the other Epic 13 issues. The consent **is** the gate,
it defaults to off, and a second flag would be a switch nobody reads.

## Consequences

- `TransactionSource` has eight values. The closed-set test was raised deliberately, and a new test
  pins the reservation — removing it now **fails to compile**, which is the strongest kill available.
- `ConsentFeature` has five. `DpdpComplianceDriftTest` fired on the addition and required a row in
  the DPDP matrix before it would pass, which is the gate working as designed.
- The AA client exists and refuses. Nothing calls it yet.

## The second promise, recorded and not kept

§33 also says `import_batches` supports statement-grade provenance. **There is no such table.**

The consequence is concrete: when AA ingest lands there is nowhere to record *which fetch a row came
from*, so a duplicate or a partial import cannot be traced to the pull that caused it — and a user
who imports twice has no way to tell the app which copy to keep.

Not built here, because the AC asks for an ADR and a stub, and a table is a migration with backfill,
archive and restore-drill consequences. It is pinned by `AccountAggregatorReadinessTest` **in the
direction issue 13.5 established**: the test fails when the table is *added*, because at that moment
this paragraph is stale and should be deleted.

A second test refuses a half-built substitute — a `batch_id` column with no batch table behind it
would look like provenance and hold nothing, which is worse than the honest absence.

## What turning this on would need

1. The backend AA proxy (issue 6.7's market-data proxy is the shape, not the endpoint).
2. `import_batches`, per above.
3. A dedupe strategy keyed on `StatementLine.externalId` against rows the user may already have
   entered by hand or from SMS — the same transaction can arrive three ways.
4. A staleness surface: a screen that renders `fetchedAtUtcMillis` rather than holding it.
5. The consent journey, which is a regulated flow with its own UI requirements.

## Alternatives considered

- **Ship the enum value with the integration.** Rejected, §1: that is exactly what left the gap
  open, and the cost of the omission falls on users of an *older* build, who cannot be warned.
- **Call an AA or an FIP directly.** Rejected, §3: EXT-001, and the user could not audit it.
- **Let `StatementLine` be a `Transaction`.** Rejected, §2: it would push dedupe and classification
  into a network type.
- **Add an `AccountAggregatorMode.IS_ENABLED` flag.** Rejected, §4: the consent already defaults to
  off and is the honest gate; a second switch would be read by nobody.
- **Build `import_batches` now.** Rejected: outside an "ADR + stub" AC, and a migration carries
  backfill, archive and drill work that deserves its own issue.
