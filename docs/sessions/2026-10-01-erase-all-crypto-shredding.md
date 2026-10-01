<!--
  Why:  CLAUDE.md §10 — one file per working session that changes code.
  What: issue 11.4 — erase-all by crypto-shredding, and the two defects only a device run found.
  Result: a reader can see why the keys go before the files, why each module declares its own
          secrets, and why a green test suite was not enough here.
  Changelog: 2026-10-01 — Created.
-->

# 2026-10-01 — Erase everything, and mean it (issue 11.4, ADR-0060)

**Branch:** `feature/11-4-erase-all-crypto-shredding` off `dev` (`d0244d6`)
**Versions:**
- **VERSION** 0.10.10 → **0.10.11**
- **versionCode** 54 → 55
- **Schema** 29 → **29 (unchanged — this issue destroys data, it does not model any)**

---

## 1 · Decisions this session

The full argument for each is in [ADR-0060](../adr/0060-an-erase-destroys-keys-first-and-says-what-it-cannot-reach.md).

- **Destroy the keys, then delete the files.** The data is already encrypted at rest, so the erase
  is a key destruction and the sweep is tidying. Overwriting is both slower and weaker — on flash
  storage wear levelling means an overwrite may never reach the physical blocks.
- **The order is chosen for the interrupted case.** Keys first leaves ciphertext nobody can read;
  files first leaves a live key beside whatever the delete had not reached. So a failed shred
  returns `Err` having deleted **nothing**, and a failed file delete is tolerated and still reports
  success — by then the leftovers are ciphertext with no key in the world, and an error there would
  frighten a user about data already beyond recovery.
- **The deletion is verified, not assumed.** `KeyStore.deleteEntry` can return without throwing on a
  device that kept the key. An erase that reported success while the master key survived is the
  worst defect this feature can have. A Keystore that cannot be *read* answers "the key is still
  there", because "I could not check" must never reach a user as "your data is gone".
- **Each module declares its own secrets, and the factories read the same constants.** The likeliest
  cause of an incomplete erase is a key nobody remembered, and the module that adds the fourth chain
  will not be the one editing the eraser. There is now exactly one copy of each name in the
  codebase, so an alias cannot drift from its shred. A drift test would only detect that.
- **An empty composed inventory is an error**, not a no-op: it means the composition lost a module,
  and `Ok` would be a successful-looking erase that touched not one key.
- **Two gates: a typed word, then the PIN.** The word comes from `strings.xml` and the comparison is
  against the *translated* value, so a Hindi user confirms in Hindi. **No PIN gate on a device with
  no PIN** — SEC-002 makes the lock optional and demanding a secret someone does not have would lock
  them out of erasing their own data. An *unreadable* credential resolves the other way.
- **The view model re-checks the gate the button already rendered**, because a disabled button is not
  a gate — anything that can deliver the event walks past it.
- **The screen says what the erase cannot reach.** A backup the user exported is sealed with *their*
  Argon2id passphrase and went straight to the file picker's URI; no key this app holds opens it.
  Claiming "everything is gone" would be a lie about the copy most likely to still exist. This
  narrows AC1's "wipes backups" and is recorded as a narrowing rather than quietly satisfied.
- **The erase ends the process.** Every handle points at a destroyed key, so the only honest next
  step is to close; navigating back would be a sequence of crashes dressed up as navigation.
- **The audit row is written last**, with no method and no detail. Written first it would be
  destroyed by the erase it describes, and a failed shred would leave a log claiming otherwise.

**What this found.**

1. **A fifth vacuous gate.** The test *named* for the ordering asserted
   `eraser.calls + audit.events.map { "audit" }` — a concatenation, so "audit" was last by
   construction and no reordering could ever fail it. The mutation that recorded the event before
   the shred was caught by a different test entirely. Both fakes now append to one shared log.
2. **A real defect, from a surviving mutation.** `EraseUiState.canErase` compared the typed text
   against the confirmation word — and both start empty, so `"" == ""` matched. A screen that had
   not yet read its own resources would have allowed an erase with nothing typed. Every test in the
   file loaded the word first, so none could see it.
3. **An equivalent mutation, recorded as equivalent.** Removing the `!exists()` arm of
   `deleteRecursivelyIfPresent` changes no behaviour, because `deleteRecursively()` already answers
   `true` for a path that was never there. It stays for legibility, and its doc comment now says
   plainly that no test can kill it — rather than leaving it looking like a gate that passed.
4. **The canary scan was shadowed.** On device, dropping a file from its module's inventory was
   caught by the per-file assertions that ran first, not by the byte scan that is the suite's real
   claim. Reordered so the scan asserts first; it now fails on its own and names the surviving path.
5. **Two defects only the device run found, with the entire suite green.**
   - `files/datastore/` survived. The widget caches **safe-to-spend and net worth in plaintext**
     there so it can draw without opening the database (issue 5.5) — encrypted by nothing, so the
     shred did nothing to it, and named by no module under `:core` or `:data`.
   - **Nine periodic workers survived.** WorkManager keeps its own database in `no_backup/`. Minutes
     later `MarketPriceWorker` would fetch prices, `SmsScanWorker` would read the inbox and
     `WidgetRefreshWorker` would repopulate the widget — each rebuilding a database behind the new
     key and starting to fill it. **The app would be collecting data again about someone who had
     just asked it to stop**, with no screen ever shown to them. That is P-01, not tidiness.
6. **Two assumptions stated at the start were wrong**, which is the argument for stating them:
   "there is one key" (there are three chains, plus a store encrypted by nothing) and "everything
   the app holds is in the inventory".
7. **Three detekt failures were fixed properly rather than trimmed.** `SettingsContent` had reached
   seven parameters → `SettingsActions`, the project's own idiom. `accountsDestinations` had been
   holding the settings destinations all along → `settingsDestinations`. `AppContent` → `DemoBanner`
   extracted.

## 2 · Flow changed this session

One new path — `FLOW.md` §2.23 — and it is the only one in the app whose **order** is a safety
property:

```
SettingsScreen → "Erase everything" → EraseScreen
└─ EraseViewModel        isPinSet (Err ⇒ required) · the translated word · canErase re-checked here
   └─ EraseRepository.eraseEverything()
      ├─ destroyKeys()    3 aliases → verify none survives → 3 keysets      Err ⇒ nothing deleted
      ├─ deleteDataFiles() files · the db + 3 sidecars · the caches, whole   failure tolerated
      └─ record(DATA_ERASED)                                                last, no method, no detail
   └─ WorkManager.cancelAllWork()                                           on success only (P-01)
   ⇣ "Close the app" → finishAndRemoveTask + exitProcess(0)
```

The work list is declared by the modules that own the secrets, and `:app` adds `:widget`'s through
`RepositoryFactory.erase(alsoErase = …)` — a dependency from `:data:repository` on `:widget` would
point the wrong way through the architecture (ARC-001).

## 3 · Code changed this session

| Path | What it does now |
|------|------------------|
| `core/common/SecretInventory.kt` (new) | the erase's work list, declared per module; `plus`, `isEmpty` |
| `core/database/crypto/DatabaseSecrets.kt` (new) | the database chain's names — now the only copies |
| `core/database/crypto/KeystoreAeadFactory.kt`, `CfoDatabaseFactory.kt` | read `DatabaseSecrets` rather than declaring their own |
| `core/crypto/CryptoSecrets.kt` (new) | the PIN and receipt chains' names |
| `core/crypto/KeystoreMacFactory.kt`, `ReceiptImageStore.kt` | read `CryptoSecrets` |
| `core/datastore/DataStoreSecrets.kt` (new) | the plaintext settings file and its `.tmp` sibling |
| `core/datastore/CfoDataStoreFactory.kt` | reads `DataStoreSecrets.FILE_NAME` |
| `core/model/AuditEvent.kt` | `DATA_ERASED` — a code, confirmed against the closed-set test |
| `data/repository/EraseRepository.kt` (new) | the ordering, and the `SecureEraser` seam |
| `data/repository/AndroidSecureEraser.kt` (new) | the platform eraser, verified; the Keystore seam; the composition |
| `data/repository/RepositoryFactory.kt` | `erase(…, alsoErase)` |
| `widget/WidgetSecrets.kt` (new) | the widget's plaintext cached figures — **found on device** |
| `app/work/WorkCancellingEraseRepository.kt` (new) | the schedule stops with the data — **found on device** |
| `app/di/LockModule.kt` | provides the erase repository, wrapped, with the widget's inventory |
| `app/navigation/{CfoRoute,CfoNavHost}.kt` | the `Erase` route; `settingsDestinations` split out of `accountsDestinations` |
| `app/MainActivity.kt` | `endProcessAfterErase`, the activity walk, and `DemoBanner` extracted |
| `feature/settings/{EraseUiState,EraseViewModel,EraseScreen}.kt` (new) | the gates, and a screen that says what it cannot reach |
| `feature/settings/SettingsActions.kt` (new) | the three navigation lambdas, named |
| `feature/settings/SettingsScreen.kt` | the link, and `SettingsActions` in place of three parameters |
| `feature/settings/res/values{,-hi,-kn,-ta}/strings.xml` | 18 strings × 4 languages, including the confirmation word |
| `**/src/test/**` (6 files, new) | 5 + 9 + 11 + 11 + 5 + 3 + 3 tests; 19 mutations |
| `data/repository/src/androidTest/EraseDeviceTest.kt` (new) | the instrumented canary scan |
| `core/model/src/test/AuditEventTest.kt` | the closed set gains `DATA_ERASED` |
| `docs/adr/0060-…`, `DECISIONS.md`, `FLOW.md` §2.23, `CHANGELOG.md`, `docs/memory.md`, `VERSION` | the records |
