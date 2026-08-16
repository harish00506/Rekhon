# Session — 2026-08-16 — Issue 5.4, Export/import JSON archive

**Branch:** `feature/5-4-export-import-json-archive` → `dev` · **VERSION:** 0.5.3 → 0.5.4
**Issue:** [5.4](../issues/5.4-export-import-json-archive.md) · **SRS:** §5.10, §34, P-01

Until this, there was no way to get data out of this app at all. Everything lived in one SQLCipher
database, and if the phone died it was gone. A privacy-first app that will not hand the user their
own data back is asking for more trust than it earns.

---

## 1 · Decisions this session

### 1.1 The Room entities *are* the archive format

Recorded as **[ADR-0023](../adr/0023-archive-replaces-and-carries-no-blobs.md)**. The plan approved
before coding said the opposite — hand-written DTOs with mappers — and I changed it, so the reasoning
matters.

The plan's justification was that DTOs insulate the file format from a `@ColumnInfo` rename. That is
**wrong**: kotlinx.serialization keys on Kotlin *property* names, not column names, so DTOs move the
rename risk rather than removing it. What they do add is fourteen types and twenty-eight mappers with
exactly one failure mode, and it is silent — somebody adds a column, forgets the mapper, and every
export from then on drops that data with no existing test able to notice. That is user data lost by
omission, in a file kept precisely for the day everything else fails.

Serialising the entities makes that unrepresentable: a new column is in the archive the moment it is
in the table. The cost — a property rename changes the format — is real and now **deliberate**,
pinned by `ArchiveFormatTest` so it takes a red build rather than an IDE refactor.

All 156 columns across the fourteen tables are `String`/`Long`/`Int`/`Boolean` and their nullables,
so there is nothing a serialiser needs help with.

### 1.2 Import replaces, and the ordering is the safety

"Restore my backup" means the device ends up in the state the archive describes. A merge would
resurrect rows the user deleted after taking it, and would make "lossless" impossible to assert.

The dangerous part is not the deletion, it is the *order*. `decode` — parse plus `schemaVersion`
check — runs **outside and before** the transaction. The failure this prevents is a wipe followed by
a parse error, which is unrecoverable. Asserted directly: after a refused import the row count is
unchanged.

And picking a file does not import it. `ImportPicked` only opens the confirmation;
`DashboardArchiveTest` asserts the repository was never called, because wiring those two together
would look identical on screen right up to the moment somebody's data was gone.

### 1.3 The wipe reuses `DemoDao`, and its doc comment had to change

`DemoDao` already deletes every profile-scoped table in FK-safe order, guarded by `countRowsFor`.
Writing a second wipe beside it would be one that drifts, and the table it forgot would be a row the
"replace" silently kept from the old data — a merge hiding inside a replace.

But that DAO's doc comment said its safety came from *"the only id ever handed to it is the demo
one"*. Import hands it the real profile id, so that sentence became false the moment this landed. It
is rewritten in the same commit: the safety rests on the parameterisation and on `countRowsFor`,
which is what it should always have rested on — the caller being the demo was a fact about the day it
was written, not a mechanism.

### 1.4 The restore does not go through each table's own writer

Found by reading, not by a failure. `BudgetAlertDao` and `BudgetReviewDao` use
`OnConflictStrategy.IGNORE` — correctly, because their rows are once-per-band and once-per-month
*claims*. A restore routed through them would silently drop rows whenever a claim already existed,
which is precisely the data loss an archive exists to prevent. `ArchiveDao` owns `REPLACE` inserts.

### 1.5 Receipts and `audit_log` stay behind

Attachment **rows** are exported; the encrypted images are not. A plaintext archive the user may
email themselves must never carry decrypted receipts (P-01), and Epic 8's encrypted backup is the
right carrier. A restore on a *new* device leaves rows pointing at absent blobs — stated in the DAO,
in `Archive.kt`, and in the ADR rather than discovered.

`audit_log` is excluded because the schema forces it: no `profile_id`, so it cannot be scoped to the
exported profile, and a replacing import would either erase security events or duplicate them. The
design spec's data-model bullet lists it among portable tables; the schema it describes cannot
support that.

### 1.6 Two bugs, and how each was caught

**(a) Every refusal showed the wrong message — found by running it.** `AppError.Validation.code` is
the constant `"validation"`, so mapping it straight through meant the two archive-specific messages
("that isn't a backup", "that backup is from another version") were **unreachable**; every refusal
got the generic line. The ViewModel test asserted only `is Failed`, so it passed happily.

Fixed with `AppError.archiveCode()` — the *field* for a validation failure — and the test now asserts
the code, which is what would have caught it. Re-verified on device: the specific message appears.

**(b) The file pickers broke every screenshot — found by a test.**
`rememberLauncherForActivityResult` needs an `ActivityResultRegistryOwner`, which exists only under a
real Activity. Putting it in the stateless `DashboardContent` made the whole screen unrenderable in
Paparazzi, breaking the property that composable's own doc comment claims. The launchers moved to
`ArchiveHost`, called from the stateful `DashboardScreen`; the body takes a plain lambda, defaulted
so previews and screenshots still render.

### 1.7 The round trip only covers what its fixture populates

The most likely bug in this issue was a silently dropped table or column, and a round-trip test is
only as good as its seed data. So `ArchiveRepositoryTest` seeds **every one of the fourteen tables
and every nullable column** — a merchant, a note, a `deleted_at` tombstone — and a separate test
asserts the fixture is non-empty for every table, because otherwise the round trip would prove that
zero rows survive being copied.

Watched go red against one table removed from the export.

---

## 2 · Flow changed this session

New **[FLOW.md §2.05](../../FLOW.md)** — export, import, and the three orderings that carry the risk:
parse before delete, pick is not import, one transaction.

---

## 3 · Code changed this session

| Path | What it does now |
|------|------------------|
| `core/database/entity/Entities.kt` | 14 profile-scoped entities carry `@Serializable`; `AuditLogEntity` deliberately does not, with the reason |
| `core/database/dao/Daos.kt` | **New `ArchiveDao`** — one `SELECT *` and one `REPLACE` insert per table; `DemoDao`'s now-false safety comment rewritten |
| `core/database/CfoDatabase.kt` · `build.gradle.kts` | The accessor; the serialization plugin, and kotlinx json promoted from test to `api` |
| `data/repository/Archive.kt` | **New** — `CfoArchive` envelope (archiveVersion, schemaVersion, exportedAt) + `ImportSummary` |
| `data/repository/ArchiveRepository.kt` | **New** — export serialises, import parses-then-checks-then-replaces in one transaction |
| `data/repository/RepositoryFactory.kt` · `app/di/RepositoryModule.kt` | Factory and binding |
| `data/repository/src/test/ArchiveRepositoryTest.kt` | **New** — round trip over every table, empty, 2,000 rows, version gate, demo isolation, tombstones, determinism |
| `data/repository/src/test/ArchiveFormatTest.kt` | **New** — pins the on-disk field names and integer paise |
| `feature/dashboard/ArchiveSection.kt` | **New** — the buttons, the confirmation, the messages, and `ArchiveHost` which owns the pickers |
| `feature/dashboard/DashboardUiState.kt` | `ArchiveUiState` sealed hierarchy + five events |
| `feature/dashboard/DashboardViewModel.kt` | Export/import handling, the confirmation gate, `archiveCode()` |
| `feature/dashboard/DashboardScreen.kt` · `strings.xml` | Wired below the actions; 13 new strings |
| `feature/dashboard/src/test/{DashboardArchiveTest,FakeArchiveRepository}.kt` | **New** — the ordering assertions |
| `docs/adr/0023-*.md` · `DECISIONS.md` · `FLOW.md` | The decision, its index row, the new paths |
| `VERSION` · `app/build.gradle.kts` · `CHANGELOG.md` | 0.5.4, `versionCode` 21 |

---

## 4 · Verification

Full log in the [tracker](../issues/5.4-export-import-json-archive-tracker.md#verification-log).
Headlines:

- `ktlintCheck detekt lintDebug unitTests koverVerify verifyPaparazziDebug` — all OK
- `:app:connectedDebugAndroidTest` — **7/7** from a clean install
- **Emulator, airplane mode:** exported the demo to a 65 KB file, pulled it off with `adb` and
  confirmed the envelope, **99 rows**, **no `audit_log`**, **no image bytes**, and `amountMinor` as
  integer paise. Added a ₹7,777 transaction, imported, watched it vanish and the message read
  "Imported 99 rows." Imported a doctored `schemaVersion` and confirmed it was refused **with the
  demo data intact** — first with the wrong (generic) message, which is how §1.6(a) was found, then
  with the right one after the fix.
