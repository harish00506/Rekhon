<!--
  Why:  CLAUDE.md §10 — one file per working session that changes code.
  What: issue 8.2 — restoring the encrypted backup onto a fresh device (SEC-005, F6).
  Result: a reader can see why restore reuses the archive import, why it asks no consent, and the
          latent cross-profile bug it exposed in 5.4's import.
  Changelog: 2026-09-18 — Created.
-->

# 2026-09-18 — Restore on a fresh device (issue 8.2, ADR-0040)

**Branch:** `feature/8-2-restore-on-fresh-device` off `dev` (`4bf87c5`) · **VERSION** 0.8.1 → **0.8.2** ·
**versionCode** 34 → 35 · **Schema** 22, unchanged

---

## 1 · Decisions this session

### 1a · Restore is `open` then the existing import

`BackupRepository.restore` = `BackupCipher.open` (header format and KDF bounds, then the GCM tag, which
checks integrity and the passphrase at once) followed by `ArchiveRepository.import`. The import
already parses and checks the schema **before** one transaction wipes and inserts. That covers
"integrity/version is checked before apply" and "no partial write", with no second wipe order to
keep in step.

### 1b · The import now refuses another profile's archive

Writing the demo case exposed it: the import wipes the **active** profile and inserts whatever
profile the file carries. A backup taken in the demo, restored into the real profile, wiped `local`,
wrote under `demo` and reported success over an empty app. Issue 5.4's plaintext import had the same
hole. `decode` now returns `Validation("archive.profile")` for an archive whose profile row is not
the active one, before anything is deleted. Re-keying the rows was rejected: it would write the
sample household into the user's own data (ADR-0006).

### 1c · No consent for restore

P-01 gates data leaving the device; a restore brings it in. It is audited (`BACKUP_RESTORED`) instead.

### 1d · Where it lives, and what a new phone has to do first

In Settings, below the backup: pick → passphrase → "Replace everything with this backup", with the
warning beside the button. On a new phone the user completes onboarding first; the optional steps
can be skipped. Restoring from onboarding's first screen would mean finishing onboarding on the
user's behalf with settings the archive doesn't carry, which is recorded in ADR-0040 as a gap.

### 1e · A wrong passphrase keeps the file

`RestoreStatus.Picked(bytes, failure)`, so the user can retype the passphrase without going back to
the picker. There's no length floor on the restore passphrase (ADR-0039's rule for `open`).

### 1f · Backup and restore moved out of the ViewModel

`BackupActions` now holds both halves. `SettingsViewModel` was already at detekt's function budget
after 8.1, and restore would have pushed it over. The 8.1 logic moved unchanged, and its tests still
drive it through the ViewModel.

### 1g · The picked file is read with a bound

Security review finding: the picker offers any file, and `readBytes()` on a picked video would crash
the app with an out-of-memory error. `readAtMost(50 MB)`, §22's blob ceiling. `InputStream.readNBytes`
is API 33 and minSdk is 26, so it's a small loop.

---

## 2 · Flow changed this session

`FLOW.md` §2.06 gains the restore path; §2.05's `decode` gains the profile check.

```
OpenDocument → readAtMost(50 MB) → RestoreFilePicked(bytes) → Picked      NOTHING TOUCHED
  → ConfirmRestore → BackupActions.confirmRestore()
    → BackupRepository.restore(bytes, passphrase)          no consent
      ├─ BackupCipher.open            format · KDF bounds · GCM tag      passphrase zeroed
      ├─ ArchiveRepository.import     parse · schema · PROFILE → one transaction
      └─ audit BACKUP_RESTORED        only on Ok
  ⇣ Restored(rows) | Picked(bytes, failure)
```

---

## 3 · Code changed this session

| Path | What it does now |
|------|------------------|
| `data/repository/.../BackupRepository.kt` | `restore(sealed, passphrase)` |
| `data/repository/.../ArchiveRepository.kt` | `decode` refuses another profile's archive (`archive.profile`) |
| `core/model/.../AuditEvent.kt` (+ test) | `BACKUP_RESTORED` |
| `data/repository/src/test/.../BackupRestoreTest.kt` | **New.** 11 Robolectric tests: round trip to the paisa, replace-not-merge, every refusal leaving the DB untouched, clearing, audit, no consent |
| `data/repository/src/test/.../ArchiveRepositoryTest.kt` | + the cross-profile refusal |
| `data/repository/src/androidTest/.../BackupRestoreDeviceTest.kt` | **New.** Backup → delete DB + key file → fresh SQLCipher DB → restore; wrong passphrase writes nothing |
| `data/repository/build.gradle.kts` | AndroidX runner + androidTest deps (first instrumented source set in the module) |
| `feature/settings/.../BackupActions.kt` | **New.** Backup (moved from the ViewModel) and restore handling |
| `feature/settings/.../RestoreSection.kt` | **New.** The restore card |
| `feature/settings/.../BackupSection.kt` | `BackupFileHost` gains the open picker and a content slot; `readAtMost` |
| `feature/settings/.../SettingsUiState.kt`, `SettingsViewModel.kt`, `SettingsScreen.kt` | Restore state/events; routing; the card |
| `feature/settings/res/values/strings.xml` | Restore copy, ICU plural for the row count |
| `feature/settings/src/test/...` | +5 ViewModel, +6 Compose, **new** `ReadAtMostTest` (6) |
| `feature/dashboard/.../ArchiveSection.kt`, `strings.xml` | Copy for `archive.profile` on the plaintext import |
| `docs/adr/0040-…`, `DECISIONS.md`, `FLOW.md` | The decision, its index row, the flow |

---

## 4 · Quiz

**Outcome: not yet taken.** The answers in italics are the author's. Record the developer's own pass
here, fails included.

1. In what order are a restore's checks made, and why that order? *(Header format and KDF bounds, then
   the GCM tag, then parse, schema and profile, cheapest first. Every one runs before the transaction
   that deletes anything.)*
2. What did a demo backup do to the real profile before this issue, and why is re-keying the rows the
   wrong fix? *(It wiped `local`, wrote the rows under `demo` and reported success. Re-keying would
   write fabricated finances into real data, which ADR-0006 forbids.)*
3. Why does restore ask for no consent when backup does? *(P-01 governs data leaving the device.)*
4. Why can't `readBytes()` be used on the picked file? *(The picker offers any file, so it's unbounded
   memory; the OOM would come before the header check.)*
