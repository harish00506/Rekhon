<!--
  Why:  CLAUDE.md §10 — one session file per working session, holding the full reasoning the root
        records only point at.
  What: issue 13.6 — why one enum constant was the urgent part of an unbuilt feature, how the AA
        interface makes the dangerous shapes unavailable, and the three gates that fired.
  Result: a reader can see why reserving a value and building an integration are different
          decisions, and why the exhaustive `when` failures were the right outcome.
  Changelog: 2026-10-09 — Created.
-->

# 2026-10-09 — Account Aggregator (issue 13.6)

Branch `feature/13-6-account-aggregator-integration` off `dev` (`9f5d849`). Version 0.13.4 →
**0.13.5** (versionCode 69). Schema unchanged.

---

## 1 · One line of an unbuilt feature had to ship today

§33's forward-compatibility table promises `transactions.source` includes a **reserved value
`'aa'`** from v1. It does not. The enum had seven values and none of them was `aa`.

What makes this sharp is that **the enum's own documentation already argues the case.** It carries
`RECURRING_AUTO` with the note *"Nothing writes this yet, and it is here anyway"*, and explains:

> `fromStored` drops a value it does not recognise, and the mapper drops the whole row with it:
> adding the constant with its writer would mean an intervening build silently hiding rows a newer
> one had written. That is not hypothetical — omitting `DEMO` did exactly that in issue 3.1.

So the codebase established the principle, applied it to `RECURRING_AUTO`, and did not apply it to
AA — despite §33 naming the value.

**The asymmetry is what matters.** When `DEMO` was missing, a demo profile's transaction list
rendered empty. When an AA build writes `source = 'aa'`, every older build drops **the user's entire
imported bank history**, silently, with no error. And the people affected are on the *older* build,
so they cannot be warned.

A reserved constant costs one line and cannot be wrong. The integration it anticipates is a quarter
of work.

**Those are not the same decision**, and conflating them is exactly how the gap survived to v0.13.4.
So the constant shipped with this issue and the integration did not. Removing it again now **fails
to compile**, which is the strongest kill a mutation can get.

---

## 2 · A stub is a chance to decide the contract while it is still free

AC2 asks for an interface stub. The interesting part of writing one is that nothing depends on it
yet, so the shape can still be chosen — and the shape is where the safety lives.

Four things are **impossible** rather than discouraged:

| Risk | Prevented by |
|---|---|
| Fetching without consent | `statements(consent: ConsentHandle)` — there is no overload without one |
| Rendering AA data without its age | `FetchedStatements.fetchedAtUtcMillis` is non-null and `require`d positive. A nullable "maybe we know when" would be a staleness label nobody renders (P-04) |
| A bank identifier reaching a log | `ConsentHandle` holds **only** an id and an expiry. A test asserts the field list |
| A second import doubling somebody's rent | `StatementLine.externalId` — the bank's own reference. Matching by amount-and-date is how that goes wrong |

`StatementLine` is deliberately **not** a `Transaction`. A statement line is evidence of what a bank
says happened; a transaction is a row in this ledger with a category, a nature and a profile.
Converting one to the other involves dedupe and classification decisions that belong in a
repository, not a network type.

---

## 3 · Two independent off-switches

`ConsentFeature.ACCOUNT_AGGREGATOR` is new and default-off. It is **not redundant** with the AA
framework's own consent artefact, which has its own purpose, scope and expiry: this flag is the
app's gate in front of that, so revoking here stops the app asking **even while an AA consent is
still live**.

No `IS_ENABLED` flag was added, unlike every other Epic 13 issue. The consent *is* the gate, it
defaults to off, and a second switch would be read by nobody.

---

## 4 · Three gates fired, and the third was the most useful

1. **The `TransactionSource` closed set** — raised 7 → 8 deliberately.
2. **`DpdpComplianceDriftTest`** — refused the new consent until it had a row in the DPDP matrix.
   Exactly what it is for: a consent the app can hold but the compliance document does not describe
   is an undocumented data flow.
3. **Kotlin's exhaustive `when`** — four compile failures across `ConsentsScreen`, `SettingsScreen`
   and `TransactionLabels`.

The third is the one worth dwelling on. It would have been easy to read as friction from a
one-constant change. It is the opposite: it forced the consent to become **visible and revocable in
the UI, in four languages**, before the build would pass.

**A consent the user cannot see is not a consent** (P-01). The type system enforced that, and
nothing else in the process would have.

Two further gates fired behind it — an unescaped apostrophe in `strings.xml` broke resource
compilation, and `TranslationCoverageTest` refused English-only strings in a four-language app.

---

## 5 · Verified by driving the app, not by a green build

The project's `/verify` discipline says the observed app closes an issue, not the suite. Since this
issue changed a surface, that mattered:

Dashboard → Settings → "Manage what the app may use" → the consents list now shows
**"Fetch statements from your bank"** as the fifth entry, status **"Never given"**, with an Allow
button and the sentence describing what withdrawal does. No crash during navigation.

---

## 6 · The second promise, recorded and not kept

§33 also says `import_batches` supports statement-grade provenance. **There is no such table.**

When AA ingest lands there is nowhere to record *which fetch a row came from*, so a duplicate or a
partial import cannot be traced to the pull that caused it — and a user who imports twice has no way
to tell the app which copy to keep.

Not built: the AC asks for an ADR and a stub, and a table is a migration with backfill, archive and
restore-drill consequences. Pinned in **issue 13.5's direction** — `AccountAggregatorReadinessTest`
fails when the table is *added*, because at that moment ADR-0074's finding is stale. A second test
refuses a half-built substitute: a `batch_id` column with no table behind it would look like
provenance and hold nothing.

---

## 7 · Flow changed this session

The first Epic 13 issue to change a **surface**: the consents dashboard and settings screen list a
fifth consent, and the transaction source label can render "From your bank". Nothing calls
`AccountAggregatorApi` — the only implementation refuses.

---

## 8 · Code changed this session

| Path | What it does now |
|------|------------------|
| `core/model/.../Transaction.kt` | `TransactionSource.ACCOUNT_AGGREGATOR("aa")` — reserved, with the reasoning |
| `core/datastore/.../ConsentFeature.kt` | `ACCOUNT_AGGREGATOR`, default-off |
| `core/network/.../AccountAggregatorApi.kt` | **New.** The contract, `ConsentHandle`, `FetchedStatements`, `StatementLine` |
| `core/network/.../UnconfiguredAccountAggregatorApi.kt` | **New.** Refuses, non-retryable; the factory returns only this |
| `core/network/src/test/.../AccountAggregatorApiTest.kt` | **New.** 8 tests on the shape |
| `core/database/src/test/.../aa/AccountAggregatorReadinessTest.kt` | **New.** 3 tests pinning the `import_batches` gap |
| `feature/settings/...`, `feature/transactions/...` | The consent and the source label, in 4 languages |
| `docs/compliance/dpdp-2023.md` | The new consent's row |
| `docs/adr/0074-*.md` | The ADR |

---

## 9 · Quiz

1. **Why did one enum constant have to ship before the feature?** Because `fromStored` drops
   unknown values and the mapper drops the row — an older build would silently hide the user's
   whole imported bank history, and those users cannot be warned.
2. **Why can AA data not be held without a timestamp?** `FetchedStatements` requires a positive
   `fetchedAtUtcMillis`, so P-04's staleness label cannot be forgotten.
3. **Why two consents for one feature?** The app's gate sits in front of the AA framework's own, so
   revoking in-app stops the asking even while the framework consent is live.
4. **Why were the exhaustive `when` failures a good outcome?** They forced the consent to become
   visible and revocable in the UI — a consent the user cannot see is not a consent.
5. **Why is `StatementLine` not a `Transaction`?** Dedupe, classification and account assignment are
   repository decisions, not network ones.
