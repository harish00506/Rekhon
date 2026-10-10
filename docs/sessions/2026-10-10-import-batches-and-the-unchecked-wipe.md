<!--
  Why:  CLAUDE.md §10 — one session file per session that changes code. Maintenance rather than an
        issue: the open finding ADR-0074 recorded, closed on request.
  What: schema 33's `import_batches`, the refusal that stands in for a foreign key, and the
        sixteen missing deletes that measuring the wipe turned up on the way.
  Result: a reader can see why the table was the small half of this change, and why a doc comment
          describing a safety is not a safety.
  Changelog: 2026-10-10 — Created.
-->

# 2026-10-10 — `import_batches`, and the wipe nobody was checking

Branch `fix/import-batches-gap` off `dev`. Version 0.13.8 → **0.13.9** (versionCode 73).
**Not an issue** — the finding ADR-0074 recorded and deferred, fixed on request.

---

## 1 · Decisions this session

### 1.1 One table, and "statement-grade" is the word that decides its shape

§20.1 lists `import_batches`. §33 promises it *"supports statement-grade provenance"*. Neither was
true: ADR-0074 found the gap while designing AA ingest and could find nowhere to write down which
fetch produced a row.

The table was never the hard part. The shape was, and "statement-grade" is what settles it. A bank
statement is **evidence**: it covers a stated period, it is whole or truncated, and it was true as
at a moment. A row that only recorded "imported" would answer none of that, so it carries:

- the **window** (`window_start_iso_date`, `window_end_iso_date`);
- the **completeness** (`complete`, which is `FetchedStatements.complete`);
- **two** timestamps — `started_at_utc_millis` (when the app ran the import) and
  `fetched_at_utc_millis` (when the data was true at the source).

The two timestamps are the one choice worth defending. They can be days apart — a statement
downloaded this morning may have been generated last week — and P-04's staleness label has to render
the *second*. Folding them into one field would make that label quietly wrong in precisely the case
it exists for.

`line_count` and `accepted_count` are **stored, not derived**. Counting rows in `transactions` would
make "this import skipped five lines" change the month the user deletes one of them. *Evidence that
moves is not evidence.*

### 1.2 The refusal is the foreign key

The schema declares no foreign keys (issue 1.6 chose application-level integrity), so SQLite would
happily store a transaction pointing at a batch that does not exist. `TransactionRepository` refuses
that write with `Validation("importBatchId")`, checked by one joined query — and the join is scoped
by the **account's profile**, not by the batch id alone.

Both halves earn their place. An id-only check would let a row in one profile claim provenance from
another profile's import: on a shared device, one person's transaction citing another person's bank
fetch. With the check in place, `null` on the column means **not imported**, and the state
"imported, origin unknown" is unreachable rather than merely discouraged.

The migration adds the column nullable with no default, for the reason ADR-0077 gave one version
earlier: a `DEFAULT ''` would give every row in the existing history a batch id naming nothing — a
dangling pointer in every transaction, written by a migration, that this very check would then
reject for ever.

### 1.3 It holds no account number, no bank name and no file name

Deliberate, not an oversight. This is the row a diagnostic or a support log would quote, and §21.6
bans personal data from logs; a file name alone can carry the bank and the last four digits of an
account. `source` says *how* the data arrived, which is all a provenance answer needs.

---

## 2 · What measuring the wipe turned up

Adding a profile-scoped table means adding it to the demo/restore wipe. Measuring that first is what
produced the real finding of this session.

`DemoDao`'s own documentation states the rule and records breaking it **twice** — 7.4 found `goal`,
`investment_holding` and `investment_lot` left behind since 7.1 and 6.3; 10.4 found four more. Its
words: *"A count that omits a table is not a weaker assertion; it is a false one."*

Both times a person happened to notice. **Nothing ever checked it.**

| Where | Missing |
|---|---|
| `DemoDao` deletes + `countRowsFor` | `chat_message` (10.5), `appliance`, `appliance_service`, `appliance_consumable` (13.2) |
| `DemoModeRepository.exit` | those four, **plus `attachments`** — never wiped since 3.8 |
| `ArchiveRepository.wipe` | **eleven** — purchase traces and gates, interview answers, the buy list, all four vehicle tables, the cached closes, the chat history, the appliances |

**The `countRowsFor` consequence:** it returned `0` for a profile that still held rows. Not a weaker
guarantee than "no residue" — the opposite of one, reported as success.

**The restore consequence is worse.** `ArchiveRepository.wipe`'s doc comment says it reuses
`DemoDao` because *"a second wipe written here would be one that drifts from it, and the table it
forgot would be a row the restore silently kept from the old data — a merge nobody asked for, hiding
inside a replace."* It reuses the DAO. It does **not** reuse the call list, and the call list is the
part that drifts. For eleven tables, "replace my data with this archive" had been meaning "merge
this archive into whatever was already here".

`chat_message` is the instructive case, because its absence looks defensible until it isn't. CHT-004
keeps conversations **out of backups**, so the archive correctly never carries it — which is exactly
why the wipe must still reach it. A table the archive cannot restore is a table whose *previous
owner's* questions survive a restore.

> **A safeguard documented in prose, with no test asserting it runs, is a comment.** This is the
> fourth "gate that cannot fail" in this project and the third involving the wipe.

### 2.1 The fix is the check; the sixteen deletes are its first output

Both tests derive the table set from the source, because a list somebody maintains is the thing that
went stale three times:

- **`WipeCoverageTest`** (`:core:database`) — every `profile_id`-carrying table has a `DELETE`, has
  a term in `countRowsFor`, and the two sets are identical. The halves fail independently: a delete
  without a count is an unprovable wipe; a count without a delete is a total that can never reach
  zero.
- **`ProfileWipeCallSitesTest`** (`:data:repository`) — both wipes call every `delete*` the DAO
  declares. The half that was actually wrong. It reads `Daos.kt` across a module boundary, so the
  build file declares it as a test input — the sixth guise of the read-at-runtime staleness bug,
  pre-empted rather than discovered.

---

## 3 · Open finding, recorded and not fixed

**`DemoDao.attachmentFileNames` has never had a caller**, though its own comment states the
contract: *"an orphaned ciphertext blob is data a 'delete everything' did not delete (P-01)"*. Both
wipes delete the attachment rows and leave the encrypted receipt images in `filesDir/receipts`.

**Bounded, not permanent:** a full device erase removes that directory wholesale
(`CryptoSecrets.filePaths`), so the orphans survive a demo exit or a restore until then, and are not
reachable through the app.

Not fixed here: erasing files is a destructive path that deserves its own issue, needs a
collaborator neither repository holds for this purpose, and needs an instrumented test on a real
filesystem. Bolting it onto this change is how the previous two rounds of this same defect happened.
Pinned in 13.5's direction instead — the test fails the moment a caller appears, which is when
ADR-0078's finding is stale and should be deleted.

---

## 4 · Mutations

| # | Mutation | Result |
|---|---|---|
| I1 | the `import_batches` table renamed away | red — KSP, the strongest kill |
| I1b | the inverted readiness assertions asked about a table that does not exist | red — proves they are live, not vacuous |
| I2 | `transactions.import_batch_id` renamed away | red — KSP |
| I3 | the batch-existence guard removed from `create` | red — 2 tests |
| I4 | `existsForAccount` ignores the profile and matches on id alone | red — the cross-profile claim test |
| I5 | the archive stops reading the batches | red — the backup restore drill |
| I6 | the restore wipe stops deleting `chat_message` | red — `ProfileWipeCallSitesTest` |
| I7 | the DAO stops deleting `import_batches` | red — `WipeCoverageTest`, 2 tests |

---

## 5 · Flow changed this session

```
ImportBatchRepository.record()
  → import_batches (schema 33)

TransactionDraft.importBatchId
  → TransactionRepository.create()
    → ImportBatchDao.existsForAccount()      ← the refusal that stands in for a foreign key
      → TransactionEntity.importBatchId

ImportBatchRepository.batchFor(txnId)
  → ImportBatchDao.forTransaction()          ← §33's question, asked directly
```

Plus the wipe: `DemoModeRepository.exit` + 5 deletes, `ArchiveRepository.wipe` + 11,
`DemoDao.countRowsFor` + 5 terms.

Nothing calls `record`. AA ingest is still stubbed (ADR-0074), and that is the shape
`TransactionSource.ACCOUNT_AGGREGATOR` already ships in: the history is the part that cannot be
recovered later, so the place to put it has to exist first.

---

## 6 · Code changed this session

| Path | What it does now |
|---|---|
| `core/database/.../entity/Entities.kt` | `ImportBatchEntity`; `TransactionEntity.importBatchId`, declared **last** for append order |
| `core/database/.../migration/Migrations.kt` | `MIGRATION_32_33` — one `CREATE TABLE`, one `ALTER TABLE`, two indices (DB-003 holds) |
| `core/database/.../CfoDatabase.kt` | `VERSION = 33`; the entity and the DAO registered; `schemas/…/33.json` |
| `core/database/.../dao/Daos.kt` | `ImportBatchDao`; the archive read/insert; **5 deletes and 5 count terms** added to `DemoDao` |
| `core/model/.../ImportBatch.kt` | **New.** The domain model, with `skippedCount` and the counts' invariant |
| `data/repository/.../ImportBatchRepository.kt` | **New.** `record`, `observeBatches`, `batchFor` (ARC-003) |
| `data/repository/.../TransactionRepository.kt` | `TransactionDraft.importBatchId`; the guard refusing a batch that does not exist in the account's profile |
| `data/repository/.../RepositoryFactory.kt` | `importBatches(…)` |
| `data/repository/.../Archive.kt` | `CfoArchive.importBatches` (keys 41 → 42) |
| `data/repository/.../ArchiveRepository.kt` | restores batches before transactions; **11 missing deletes** added to `wipe` |
| `data/repository/.../DemoModeRepository.kt` | **5 missing deletes** added to `exit` |
| `data/repository/src/sharedTest/.../DrillFixture.kt` | seeds a **truncated** batch, so the drill proves `complete = false` survives |
| `core/database/src/test/.../WipeCoverageTest.kt` | **New.** 3 tests — the check that was absent |
| `data/repository/src/test/.../ProfileWipeCallSitesTest.kt` | **New.** 3 tests, incl. the orphaned-blob pin |
| `data/repository/src/test/.../ImportBatchRepositoryTest.kt` | **New.** 7 tests |
| `core/database/src/androidTest/.../MigrationRoundTripTest.kt` | `migrate32To33_leavesHistoryUnimportedAndCarriesABatch()` |
| `core/database/src/test/.../aa/AccountAggregatorReadinessTest.kt` | **inverted** (§1.3 of ADR-0078) |
| `core/database/src/test/.../scoping/ProfileScopingTest.kt` | scoped tables 36 → 37 |
| `data/repository/src/test/.../ArchiveFormatTest.kt` | archive keys 41 → 42 |
| `data/repository/build.gradle.kts` | declares `Daos.kt` as a test input (the staleness bug, pre-empted) |
| `docs/adr/0078-*.md` | the decision record, with its own open finding |
| `docs/adr/0074-*.md` | the finding marked kept, text preserved |

`ai/` is unchanged: no threshold moved, so no data row did.
