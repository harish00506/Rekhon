<!--
  Why:  CLAUDE.md §5 — a decision record for schema 33 and for the wipe-coverage defect that
        building it uncovered.
  What: §33's second AA promise kept, and the "no residue" guarantee turned from a claim into a
        checked one.
  Result: a reader can see why one table's arrival exposed sixteen missing deletes, and why the
          check matters more than the deletes.
  Changelog: 2026-10-10 — Created.
-->

# ADR-0078 — `import_batches` exists, and the wipe is checked rather than trusted

**Status:** Accepted · **Date:** 2026-10-10 · **Closes the finding in:** [ADR-0074](0074-aa-is-designed-and-the-reserved-source-value-had-to-ship-now.md) · **SRS:** §20.1, §33, §16 · **Rules:** P-01, P-03, P-04, MNY-001, TIM-001, TIM-002, DB-003, ARC-003, ARC-005

## Context

§20.1 lists `import_batches` among the system tables. §33's forward-compatibility table promises it
supports *statement-grade provenance*. Issue 13.6 measured both of §33's AA promises, kept the one
that could not wait — the reserved `source = 'aa'` value — and recorded this one as **not built**,
because that issue's acceptance criteria asked for an ADR and an interface stub.

The consequence ADR-0074 named: when AA ingest lands there is nowhere to record *which fetch a row
came from*, so a duplicate or partial import cannot be traced to the pull that caused it, and a user
who imports the same statement twice has no way to tell the app which copy to keep.

## Decision

### 1 · One table, and "statement-grade" is the demanding word

`import_batches` (schema 33) holds one row per ingest run, and `transactions.import_batch_id` points
at it — nullable, because almost nothing is imported.

A provenance record that only said *"imported"* would answer none of the questions the word
"statement-grade" implies. A bank statement is **evidence**: it covers a stated period, it is either
whole or truncated, and it was true as at a moment. So the row carries the window
(`window_start_iso_date`, `window_end_iso_date`), the completeness (`complete`, from
`FetchedStatements.complete`), and **two** timestamps that are deliberately not one:

- `started_at_utc_millis` — when the app ran the import;
- `fetched_at_utc_millis` — when the data was true at the source.

They can be days apart, and P-04's staleness label has to render the second. Folding them into one
would make the label quietly wrong in exactly the case it exists for.

It also stores `line_count` and `accepted_count` rather than counting rows in `transactions`. The
difference is the answer to *"why does my import look short"*, and a derived count would change the
moment the user deletes a row — **evidence that moves is not evidence**.

### 2 · It holds no account number, no bank name and no file name

This is the row a diagnostic or a support log would quote, and §21.6 bans personal data from logs. A
file name alone can carry the bank and the last four digits of an account. `source` says *how* the
data arrived, which is everything a provenance answer needs and nothing a log should not see (P-01).

### 3 · The refusal is the foreign key

The schema declares no foreign keys — issue 1.6 chose application-level integrity — so nothing in
SQLite stops a transaction pointing at a batch that does not exist. `TransactionRepository` refuses
such a write with `Validation("importBatchId")`, checked by a single joined query scoped to the
account's profile.

**Both halves of that scoping matter.** An id-only check would let a row in one profile claim
provenance from another profile's import — on a shared device, one person's transaction citing
another person's bank fetch. So `null` on the column means **not imported**, and never "imported,
origin unknown": that second state is unreachable by construction.

The migration adds the column nullable with **no default**, for the reason
[ADR-0077](0077-the-gst-figure-is-stored-and-null-is-not-zero.md) gives one version earlier: a
`DEFAULT ''` would hand every row in the user's existing history a batch id naming nothing — a
dangling pointer in every transaction, written by a migration, that this very check would then
reject for ever.

### 4 · Building it uncovered a larger defect: sixteen missing deletes

Adding a profile-scoped table means adding it to the demo/restore wipe. Measuring that first turned
up the real finding.

`DemoDao`'s own documentation states the rule and records breaking it **twice** — issue 7.4 found
`goal`, `investment_holding` and `investment_lot` left behind since 7.1 and 6.3; issue 10.4 found
four more. Its words: *"A count that omits a table is not a weaker assertion; it is a false one."*

Both times a person happened to look. Nothing ever checked it. So:

| Where | What was missing |
|---|---|
| `DemoDao` — deletes and `countRowsFor` | `chat_message` (10.5) and the three `appliance*` tables (13.2) |
| `DemoModeRepository.exit` | those four, plus `attachments` — never wiped since 3.8 |
| `ArchiveRepository.wipe` | **eleven**: the purchase traces, interview answers, buy list, all four vehicle tables, the cached closes, the chat history and the appliances |

The `countRowsFor` consequence is the sharp one: it returned `0` for a profile that still held rows.
That is not a weaker guarantee than "no residue" — **it is the opposite of one, reported as
success**.

The restore consequence is sharper still. `ArchiveRepository.wipe`'s doc comment says it reuses
`DemoDao` because *"a second wipe written here would be one that drifts from it, and the table it
forgot would be a row the restore silently kept from the old data — a merge nobody asked for, hiding
inside a replace."* It reuses the DAO. **It does not reuse the call list**, and the call list is the
part that drifts. The paragraph described a safety the code never had, and by now "replace my data
with this archive" meant "merge this archive into whatever was here" for eleven tables.

### 5 · The fix is the check; the sixteen deletes are its first output

Two tests, derived from the source rather than from a list somebody maintains — because a list
somebody maintains is precisely the thing that went stale three times:

- **`WipeCoverageTest`** (`:core:database`) — every table carrying a `profile_id` has a `DELETE` in
  `DemoDao`, has a term in `countRowsFor`, and those two sets are identical. The halves fail
  independently: a delete without a count is an unprovable wipe; a count without a delete is a
  residue total that can never reach zero.
- **`ProfileWipeCallSitesTest`** (`:data:repository`) — both wipes call every `delete*` the DAO
  declares. This guards the half that was actually wrong.

`chat_message` is the instructive case. CHT-004 keeps conversations **out of backups**, so the
archive correctly never carries it — which is exactly why the wipe must still reach it. A table the
archive cannot restore is a table whose previous owner's questions survive a restore.

## Open finding, recorded and not fixed

**`DemoDao.attachmentFileNames` has never had a caller.** Its own documentation states the contract:
*"read before `deleteAttachments`, because after it there is nothing left to say which files on disk
belonged to this profile — and an orphaned ciphertext blob is data a 'delete everything' did not
delete (P-01)."*

Both wipes now delete the attachment rows and leave the encrypted receipt images in
`filesDir/receipts`. The leak is **bounded**: a full device erase removes that directory wholesale
(`CryptoSecrets.filePaths`), so the orphans persist from a demo exit or a restore until then — they
are not permanent, and they are not reachable through the app.

Not fixed here because erasing files is a destructive path that deserves its own issue, needs a
collaborator neither repository currently holds for this purpose, and needs an instrumented test on
a real filesystem. Pinned **in the direction issue 13.5 established**: `ProfileWipeCallSitesTest`
fails the moment a caller appears, which is when this section is stale and should be deleted.

This is the fourth "gate that cannot fail" in the project's history and the third to involve the
wipe specifically. The pattern is consistent enough to state plainly: **a safeguard documented in
prose, with no test asserting it runs, is a comment.**

## Consequences

- Schema **33**; `MIGRATION_32_33` is one `CREATE TABLE` and one `ALTER TABLE … ADD COLUMN`, so
  DB-003 holds. The round-trip test asserts migrated history comes back `NULL`.
- The archive gains `importBatches` (keys 41 → 42) and restores them **before** the transactions
  that name them. Without that, a restore would recreate the dangling pointer §3 refuses to create.
- The restore drill's fixture seeds a **truncated** batch, so the round trip proves `complete =
  false` survives rather than merely that the column compiles.
- Profile-scoped tables: 36 → **37**.
- `AccountAggregatorReadinessTest` was **inverted rather than deleted**, like ADR-0077's pin: it
  used to fail when the table was added, and now fails if either the table or the column is removed.
  Both halves are asserted, so neither can go alone.
- **Nothing calls `record` yet.** AA ingest is stubbed (ADR-0074) and no file import exists. That is
  the same shape `TransactionSource.ACCOUNT_AGGREGATOR` shipped in, for the same reason: the history
  is the part that cannot be recovered later, so the place to put it has to exist first.

## Alternatives considered

- **A `DEFAULT ''` on the new column.** Rejected, §3: a dangling pointer in every historical row.
- **Real foreign keys for this one table.** Rejected: issue 1.6 chose application-level integrity
  for the whole schema, and one table with `ON DELETE` semantics nothing else has would be a rule a
  reader has to discover. The refusal in §3 is testable and says why in its error.
- **Derive `accepted_count` from `transactions`.** Rejected, §1: evidence that moves is not evidence.
- **Put the tax/provenance on `attachments` instead.** Rejected for the reason ADR-0077 gives: a
  transaction can be imported with no image at all.
- **Add the sixteen deletes and no test.** Rejected, §5 — that is what the previous two rounds did,
  and it is why there was a third.
- **Fix the orphaned blobs here too.** Rejected: a destructive file path bolted onto an unrelated
  change, with no instrumented coverage. Recorded and pinned instead.
