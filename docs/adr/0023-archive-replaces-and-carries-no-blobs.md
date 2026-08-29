# ADR-0023 — The export archive is the schema, replaces on import, and carries no receipts or audit log

- **Status:** accepted
- **Date:** 2026-08-16
- **Deciders:** Harish G
- **SRS refs:** §5.10, §34; P-01, P-07, DB-003; issue 5.4, and Epic 8 which this deliberately is not

## Context

§5.10 asks for "a full local JSON export/import archive" — the user-owned copy of their own data.
The design spec calls it "the user-owned backup; there is no cloud copy by default". Four questions
have to be answered before that can be code, and each has a defensible wrong answer.

**What shape is the file?** The archive is a lossless dump of fourteen tables and 156 columns. The
conventional answer is a parallel set of DTOs with mappers both ways, keeping the on-disk format
independent of the database.

**What does import do to what is already there?** "Restore my backup" implies replacement; "don't
lose my data" implies a merge.

**Do receipts travel?** Attachment rows point at AES-GCM blobs in app-private storage. A truly
lossless archive would carry the images.

**Does `audit_log` travel?** The design spec's data-model bullet lists it among portable tables.

## Decision

**The Room entities *are* the archive format.** They carry `@Serializable`; there are no DTOs and no
mappers. `ArchiveFormatTest` pins the resulting field names so a rename cannot change the format silently.

**Import replaces**, inside one `withTransaction`, after the file has been parsed and version-checked.
The screen confirms first, in words that say what will happen ("everything currently on this device
is replaced") rather than "are you sure?".

**Receipts do not travel.** Attachment *rows* are exported; the image blobs stay encrypted on the
device.

**`audit_log` does not travel.** The schema forces it: that table has no `profile_id`.

**One schema version is accepted** — the build's own. An archive from any other is refused by name,
before anything is deleted.

## Consequences

- **Positive (format):** a column added to a table is in the archive the moment it is in the table.
  The DTO alternative has exactly one failure mode and it is silent — somebody adds a column, forgets
  the mapper, and every export from then on drops that data with no test able to see it. That is user
  data lost by omission, which is the worst class of bug this feature could have.
- **Negative / cost (format):** a Kotlin **property rename** now changes the on-disk format, because
  kotlinx serialises by property name. This is a real cost and it is deliberate: the archive is a
  contract with files already on users' phones, and it should take a red build to change it. The
  round-trip test in `ArchiveRepositoryTest` seeds every table and every nullable column so a dropped
  table fails loudly, and `ArchiveFormatTest` pins the envelope and the field names of the rows a
  user is most likely to care about, so a rename is a red build rather than an unreadable backup.
- **Negative / cost (format):** `:core:database` gains kotlinx.serialization in `main`, where it was
  previously test-only. Small, and it is already the project's one serializer (`DECISIONS.md`).
- **Positive (replace):** losslessness becomes *testable* — export, wipe, import, compare row for
  row. Under a merge there is nothing to assert.
- **Negative / cost (replace):** it is the only operation in the app that destroys data the user did
  not individually select. Mitigated by three things: the confirmation, the parse-and-check happening
  **before** the wipe, and the whole thing running in one transaction so a failure rolls back.
- **Negative / cost (receipts):** the archive is not byte-for-byte everything. A restore on a *new*
  device leaves attachment rows whose `file_name` points at a blob that is not there — the
  transaction keeps its record of having had a receipt, and the image is gone. Stated in the DAO, in
  `Archive.kt`, and here.
- **Positive (receipts):** a plaintext file the user may email to themselves or drop in cloud storage
  never contains a decrypted receipt. Epic 8's encrypted backup is the right carrier for blobs.
- **Follow-ups:** issue 8.1 can encrypt this same archive rather than inventing a second format, and
  should carry the blobs that this one cannot. A `schemaVersion` migration path becomes real the
  first time the schema moves after an archive exists in the wild.

## Alternatives considered

- **DTOs with hand-written mappers** — rejected: ~1,500 lines whose only failure mode is silent data
  loss, in exchange for insulating against a rename that a test can catch directly.
- **Merge on import (upsert by id)** — rejected: rows the user deleted after taking the archive come
  back, and "lossless" stops being checkable.
- **Import only into an empty profile** — rejected: safest and useless. The actual use case is
  restoring onto a device that already has the app set up.
- **Base64 the receipt images inline** — rejected: decrypts every receipt into an unencrypted file,
  and turns a ~1 MB archive into tens of MB that must be held in memory to parse.
- **Skip attachment rows entirely** — rejected: the user silently loses the record that a transaction
  ever had a receipt.
- **Export `audit_log` anyway, unscoped** — rejected: it would mean either erasing security events on
  import or duplicating them, and a security log is a record of what happened on *this device*, not
  financial data to port.
- **Reusing each table's own `upsertAll` for the restore** — rejected on inspection:
  `BudgetAlertDao` and `BudgetReviewDao` use `OnConflictStrategy.IGNORE`, correctly, because their
  rows are once-per-band and once-per-month claims. A restore through them would silently drop rows.
  `ArchiveDao` owns `REPLACE` inserts for exactly this reason.

## Compliance with golden rules

- **P-01:** entirely local. The repository returns a `String`; the screen writes it wherever the
  user's own file picker points. There is no network path on this feature and there must never be
  one. The unencrypted format is the *point* of portability — and the reason receipts stay behind.
- **P-07:** the app proposes and the user decides. The destructive half is unreachable without an
  explicit second tap, asserted in `DashboardArchiveTest` rather than assumed.
- **P-03:** nothing is computed. The archive copies stored values; `Money` stays `Long` paise, dates
  stay ISO strings and instants stay epoch millis (MNY-001, TIM-001/002) — the entities' own
  representation, never reformatted.
- **P-08:** reads are ordered by primary key, so two exports of unchanged data differ only by their
  timestamp — asserted.
- **DB-003:** no migration and no schema change; `@Serializable` adds no column.
- **ARC-005:** `RoomArchiveRepository` is the only DAO toucher; nothing above it sees a Room entity,
  because `export` returns text and `import` takes text.
