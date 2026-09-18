# ADR-0040 — Restore goes through the archive import, asks no consent, and refuses another profile's data

- **Status:** accepted
- **Date:** 2026-09-18
- **Deciders:** Harish G (solo)
- **SRS refs:** SEC-005, §23.3, F6, P-01, P-07, MNY-001; issue 8.2. Builds on ADR-0023 (the archive
  replaces, never merges) and ADR-0039 (the backup format)

## Context

Issue 8.1 made the encrypted backup and left `BackupCipher.open` without a caller. Issue 8.2 must
rebuild a fresh device from it: "integrity/version is checked before apply", "a wrong passphrase
fails safely with no partial write", "`Money` values are exact after restore".

Three questions had no answer in the SRS:

1. There is already one path that replaces a profile from an archive — `ArchiveRepository.import`
   (issue 5.4). Does restore use it, or get its own?
2. Does restoring need the `CLOUD_BACKUP` consent that making a backup needs?
3. Where does restore live, given that a fresh install starts in onboarding?

Building it exposed a fourth. The import wipes the **active** profile (`local`) and inserts whatever
profile rows the file carries. A backup taken inside the demo carries `demo` rows. Restored into the
real profile, it deleted everything under `local`, wrote the data under `demo`, reported success —
and the app showed nothing. Issue 5.4's plaintext import had the same hole; no test had ever
imported one profile's archive into another.

## Decision

**1. Restore = `BackupCipher.open` then `ArchiveRepository.import`, and nothing else.** The import
already parses and checks the schema **before** it opens a transaction, and wipes and inserts
inside one — the atomicity the criterion asks for. The order of refusals is cheapest first: header
format and KDF bounds, then the GCM tag (integrity and passphrase at once), then parse, schema and
profile. Every refusal happens before a row is deleted. A second restore path would be a second
wipe order to keep in step with `DemoDao`, which ADR-0023 already refused once.

**2. No consent.** P-01 gates data leaving the device. A restore brings data in, from a file the user
picked. It is audited instead (`AuditEvent.BACKUP_RESTORED`), because after erase-all it is the only
operation that removes everything.

**3. The archive import refuses an archive whose profile row is not the active profile**
(`Validation("archive.profile")`). This is in the shared import, so it fixes 5.4's plaintext import
as well, and the dashboard maps the new code to its own message. An archive with no profile row is
still accepted: it holds no other profile's data.

**4. Restore lives in Settings, below the backup.** It is two deliberate steps (P-07): pick a file,
then type the passphrase and press "Replace everything with this backup", with the warning next to
the button rather than in a dialog. A wrong passphrase keeps the file staged so the user can retype
it without going back to the picker. **No passphrase length floor on restore**, for the reason
ADR-0039 gives for `open`.

## Consequences

- **On a new phone the user completes onboarding before they can restore.** The optional steps can be
  skipped. Putting restore on onboarding's first screen would mean completing onboarding on the
  user's behalf from inside a restore, with profile and settings the archive does not carry. That is
  a bigger change than this issue, and it is written down here rather than assumed.
- **What the archive does not carry does not come back:** the settings seeds (income and the rest),
  the consents, the app lock and its PIN, and receipt images (ADR-0023). Consents not coming back is
  correct: they are per-device decisions (P-01). The rest is a known gap for a later issue.
- **The widget's cached figures are stale until its next refresh.** It renders from its own Glance
  state (ADR-0024), not the database.
- **A picked file is read only up to 50 MB** (§22's blob ceiling). The picker offers any file, and an
  unbounded read of a picked video would crash with an out-of-memory error before the header check
  ever ran. The security review for this issue found that.
- **The decrypted archive exists briefly as a `String`,** because the import takes JSON text. The byte
  copy is zeroed once imported, but the `String` cannot be. The floor is the same as ADR-0039's for
  the passphrase.
- **`:data:repository` now has an instrumented source set** (`BackupRestoreDeviceTest`). It restores
  onto a freshly created SQLCipher database with its key file deleted, at the shipping Argon2id cost.

## Alternatives considered

- **A restore path of its own.** A second wipe and insert order, with its own chance to forget a table:
  the failure `CfoArchive`'s history already shows (ADR-0036).
- **Re-keying the restored rows to the active profile.** This would let a demo backup "restore" into
  the real profile, writing the sample household's invented finances into the user's own data. That
  is exactly what ADR-0006 exists to prevent.
- **Gating restore behind `CLOUD_BACKUP`.** That consent is about leaving the device. Requiring it
  to bring data in would teach users that consents are hoops to jump through, not decisions.
