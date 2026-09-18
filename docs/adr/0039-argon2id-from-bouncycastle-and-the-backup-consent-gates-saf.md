# ADR-0039 — Argon2id comes from BouncyCastle; the backup consent gates the file-picker backup

- **Status:** accepted
- **Date:** 2026-09-18
- **Deciders:** Harish G (solo)
- **SRS refs:** SEC-003, SEC-005, §23.3, API-003, P-01, P-08; issue 8.1. Builds on ADR-0023 (the
  plaintext archive stays separate)

## Context

SEC-005 fixes the construction: "AES-256-GCM with a key derived from a user passphrase via Argon2id".
SEC-003 fixes the means: "all crypto via Google Tink / platform APIs — no hand-rolled crypto". Read
together they cannot both be met by Tink alone, because **Tink has no password-based KDF at all** —
not Argon2, not scrypt, not PBKDF2. Android's platform APIs offer PBKDF2 through the JCA, and no
Argon2.

Two further questions had no answer in the SRS:

1. SEC-005 also says "local backup to user-chosen storage (SAF) supported". The system file picker
   can hand back a folder on the phone, a memory card, or a cloud drive, and the app cannot reliably
   tell which. Is writing a backup there an "off-device write" that P-01 gates?
2. What does the file look like, given that backups will outlive the build that wrote them?

## Decision

**1. Argon2id comes from BouncyCastle (`bcprov-jdk18on`), and only Argon2id.** One internal object,
`BackupKdf`, is the only code that imports it; the dependency is `implementation` in `:core:crypto`,
so no other module can reach it. Every cipher operation stays in Tink: `AesGcmJce` over the derived
key, which draws its own nonce, so this code never chooses one.

The derivation is checked against **OpenSSL 3.5's independent Argon2id** at both the test and the
shipping parameters (`BackupCipherTest`), not against BouncyCastle's own output.

**2. The shipping cost is RFC 9106 §4's second recommended option:** 64 MiB, t = 3, p = 4. The first
option (2 GiB) cannot be allocated on most phones. The cost travels in each file's header, so a later
build can raise it and still open every old backup; `open` refuses a header asking for more than
256 MiB, 16 passes or 16 lanes before deriving anything, so a crafted file cannot make a restore
allocate what it likes.

**3. The whole header is the GCM associated data.** Magic, version, cost and salt: changing any of
them fails the tag rather than being silently honoured.

**4. The existing `CLOUD_BACKUP` consent gates the whole backup, including the file-picker path.**
A backup exists to leave the phone, and the picker's destination may be a cloud provider the app
cannot distinguish from a memory card. The consent is checked **before the archive is read**, and a
revocation drops a sealed backup the user has not yet saved. The persisted id stays `cloud_backup`;
the label changes to "Save encrypted backups off this device", which is what it now covers.

**5. The passphrase is consumed, never stored.** The screen holds it only while it is typed, clears
both fields in the same update that starts the seal, and hands a `CharArray` to the repository, which
zero-fills it on every path. The derived key and the plaintext bytes are zeroed after use. A minimum
of 12 characters is enforced on `seal` only — never on `open`, so a policy change cannot lock anyone
out of a backup they already made.

**6. SEC-005's "irrecoverability explicit" is a required acknowledgement**, not a paragraph: the
create button stays disabled until the user ticks "if I forget this passphrase, this backup can never
be opened — not by me, and not by the app". There is no recovery phrase in this issue: a second secret
that also opens the backup is a second thing to lose or leak, and SEC-005 only asks that the
irrecoverability be explicit.

## Consequences

- **A new dependency, and a large one.** `bcprov` is several megabytes before shrinking; R8 keeps only
  the Argon2 classes reached from `BackupKdf`. It also widens the OSV scan surface (SEC-007, issue 11.6).
- **The BouncyCastle family must resolve as one version.** Robolectric brings `bcpkix`/`bcutil` at an
  older release; upgrading only `bcprov` broke `BouncyCastleProvider`'s static setup in fifteen
  dashboard Compose tests. The root `build.gradle.kts` now pins every `org.bouncycastle:*-jdk18on`
  request to the catalog's `bouncycastle` version.
- **SEC-003's wording is stretched, not broken.** No construction is invented here: Argon2id is RFC
  9106, the implementation is a vetted library's, and the cipher is Tink's. Issue 11.7's audit should
  treat `BackupKdf` as the single sanctioned exception and fail on any other `org.bouncycastle`
  import.
- **Argon2id's cost is paid on the device.** Sealing takes a noticeable moment and 64 MiB, so it runs
  on `Dispatchers.Default` and the screen says "this takes a few seconds".
- **Java strings cannot be zeroed.** The text fields hold the passphrase as a `String` while it is
  typed, and that copy lives until garbage collection. It is never persisted or logged; this is the
  floor Compose text input allows.
- **No Unicode normalisation.** The passphrase is encoded UTF-8 as typed. A passphrase with accented
  characters typed on two different keyboards could differ by normalisation form; revisit in 8.2 if
  restore reports it.
- **API-003's manifest** (schema version, created-at) is already inside the ciphertext, because the
  archive carries both. The device name is not added: nothing reads it yet, and it is personal data.
- **`BackupCipher.open` has no production caller until issue 8.2** — restore applies it atomically
  through the archive import. It is tested now because the acceptance criteria ask for the round trip.

## Alternatives considered

- **PBKDF2 through the platform JCA.** Meets SEC-003 to the letter and breaks SEC-005, which names
  Argon2id; PBKDF2 is also not memory-hard, which is the property that makes a stolen backup expensive
  to attack with GPUs.
- **libsodium via Lazysodium.** Native code plus JNA, a second native toolchain in the build, and no
  way to run the KDF in a plain JVM unit test.
- **Hand-written Argon2.** Exactly what SEC-003 forbids.
- **Leaving the file-picker backup ungated, like the plaintext export (ADR-0023).** The export is a
  portability feature whose destination the user chooses to read; a backup's purpose is to be
  somewhere else. The same picker, two different purposes, two different answers.
