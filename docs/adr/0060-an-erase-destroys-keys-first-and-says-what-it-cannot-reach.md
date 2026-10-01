<!--
  Why:  CLAUDE.md §5 — any decision or deviation from the SRS needs an ADR.
  What: issue 11.4 — erase-all by crypto-shredding: the ordering, the inventory, the gates, and the
        one thing the erase cannot reach.
  Result: a reader can see why keys go before files, why the secret names live in the modules that
          own them, and why the screen tells the user about their own exported backups.
  Changelog: 2026-10-01 — Created.
-->

# ADR-0060 — An erase destroys keys first, and says what it cannot reach

**Status:** Accepted · **Date:** 2026-10-01 · **Issue:** 11.4 · **SRS:** §23, §34, SEC-003, P-01, P-07

## Context

§34 and SEC-003 ask for an erase-all that is *cryptographically* unrecoverable, requires explicit
confirmation and authentication, records an audit event with no PII, and is proven by an instrumented
test that no readable data remains.

The app's data is already encrypted at rest: a SQLCipher database whose passphrase is wrapped by a
Tink keyset, which is itself wrapped by an Android Keystore master key held in the TEE or StrongBox
(issue 11.1, ADR-0057). Two more independent key chains exist — the PIN credential's MAC key and the
receipt blobs' AEAD key — plus one store that is **not** encrypted at all: the Proto DataStore
settings file, which holds the consent ledger and the profile in plaintext protobuf inside
app-private storage.

## Decision

### 1 · Destroy the keys, then delete the files — in that order

Overwriting files is the folk remedy and it is both slower and weaker: on flash storage an overwrite
does not reliably reach the physical blocks, and a half-finished one leaves readable remnants.
Destroying the Keystore key is stronger and instant — the remaining bytes become random to everyone,
including whoever holds the disk, because there is no second copy of that key anywhere.

The order is the safety property, and it is chosen for the interrupted case. **Keys first** leaves
ciphertext nobody can read. **Files first** leaves a live key beside whatever the delete had not
reached. So `DefaultEraseRepository` shreds, then sweeps, and a failed shred returns `Err` having
deleted nothing: destroying a user's data while leaving it theoretically recoverable is the worst of
both outcomes. A failed *file* delete is tolerated and still reports success, because by then the
leftovers are ciphertext with no key in the world and an error message would frighten a user about
data that is already beyond recovery.

### 2 · The deletion is verified, not assumed

`KeyStore.deleteEntry` can return without throwing on a device that kept the key. An erase that
reported success while the master key survived would be the single worst defect this feature can
have — the user is told their finances are unrecoverable and they are not. So every alias is
re-checked after the deletes, and a survivor is `Err(Crypto("erase.key_survived"))`. A Keystore that
cannot be *read* answers "the key is still there", because "I could not check" must never reach a
user as "your data is gone".

### 3 · Each module declares its own secrets; the eraser knows no names

The likeliest cause of an incomplete erase is not a bug in the deletion but **a key nobody
remembered** — and the module that adds a fourth key chain will not be the one editing the eraser.
So `SecretInventory` (pure Kotlin, `:core:common`) is declared by the module that owns the secret,
next to the code that creates it: `DatabaseSecrets`, `CryptoSecrets`, `DataStoreSecrets`. The
factories were rewired to **read those constants**, so there is exactly one copy of each name in the
codebase and an alias cannot drift apart from its shred. A drift test would only detect that; this
makes it impossible.

An empty composed inventory is an error rather than a no-op: it means the composition lost a module,
and reporting `Ok` would be a successful-looking erase that touched not one key.

### 4 · Two gates: a typed word in the user's own language, then authentication

The confirmation word comes from `strings.xml` and the gate compares against the **translated**
value, so a Hindi user confirms in Hindi (issue 10.8's rule). It is trimmed and compared
case-insensitively: this is a deliberate speed bump against a mis-tap, not a password, and failing a
user over a trailing space would make it feel like one.

The auth factor is the existing PIN verifier (SEC-002). **On a device with no PIN there is no PIN
gate** — the app lock is optional, a user who never set one has no secret to prove, and demanding one
would lock them out of erasing their own data. An *unreadable* credential resolves the other way, to
"a PIN is required": a storage error must not become a way past the gate.

The view model re-checks the gate it already rendered, because a disabled button is not a gate —
anything that can deliver the confirm event walks past it.

### 5 · What the erase cannot reach, said on the screen

A backup the user exported (issue 8.1) is sealed with **their** Argon2id passphrase, not with any key
this app holds, and the sealed bytes go straight to the URI the system file picker returned — they
never touch app-private storage. So destroying the Keystore does nothing to that file and the app has
no handle to delete it.

The screen says so, in as many words, and tells the user to delete those files themselves. Claiming
"everything is gone" would be a lie about the copy most likely to still exist. This is a **narrowing
of AC1's "wipes DB/backups/caches"**, recorded here rather than quietly satisfied: the database,
every key chain, the settings file and all caches are wiped; there is no backup inside app storage to
wipe; an exported backup is outside the app's reach by design and is now disclosed instead.

### 6 · Two things only the device run found

The JVM and instrumented tests were green, the canary scan found nothing, and the erase still left
two things behind. Both came from running it on a real install with demo data loaded:

- **The widget's cached figures.** `:widget` renders without touching the database (issue 5.5) by
  caching a snapshot — which is the user's safe-to-spend and net worth, as `Long` paise, written by
  Glance into a *plaintext* preferences file under `filesDir/datastore/`. No key of ours wraps it, so
  the shred did nothing to it, and no module under `:core` or `:data` knows the file exists. It is
  now declared by `WidgetSecrets`, and because a dependency from `:data:repository` on `:widget`
  would point the wrong way through the architecture (ARC-001), `RepositoryFactory.erase` takes an
  `alsoErase` inventory and `:app` — which depends on both — is where the two meet.
- **Nine periodic workers survived.** WorkManager keeps its own database in `no_backup/`, outside
  everything the inventory covers. Minutes after an erase, `MarketPriceWorker` would fetch prices,
  `SmsScanWorker` would read the inbox and `WidgetRefreshWorker` would repopulate the widget — each
  rebuilding a database behind the new key and starting to fill it. The app would be **collecting
  data again about someone who had just asked it to stop**, with no screen ever shown to them. That
  is a P-01 failure, not untidiness. `WorkCancellingEraseRepository` (in `:app`, for the same
  layering reason) cancels everything after a *successful* erase only — a failed shred means the data
  is still there, so the app is still the app — and a cancel that throws can never turn a successful
  erase into a reported failure.

WorkManager's own rows survive and are left alone: they hold worker class names and UUIDs, and no
worker in this app is given input data, so there is nothing of the user's in them.

### 7 · The erase ends the process

Every handle the process holds — Room, the Tink primitives, DataStore — points at a key that no
longer exists. Navigating back to a dashboard would be a sequence of crashes dressed up as
navigation, so the final screen offers one action: close the app. It calls `finishAndRemoveTask` (so
recents cannot reopen into a dead graph) and exits. The next launch is a fresh install.

### 8 · One audit row, written last

`AuditEvent.DATA_ERASED`, with no method and no detail — `audit_log` has no free-text column, which
is the design (§21.6). It is written **after** the shred: written first it would be destroyed by the
very erase it describes, and if the shred then failed the app would hold a log claiming it had erased
data it still had. In practice the row lands in the `audit_log` of the database re-created behind the
new key, so it is the first row of the app's next life.

## Alternatives rejected

| Alternative | Why not |
|---|---|
| Overwrite files with random bytes | Slower, and unreliable on flash — wear levelling means an overwrite may not reach the physical blocks. Key destruction is both faster and stronger. |
| Delete files, then keys | The interrupted case leaves a live key beside a partly-deleted database: readable finances. |
| `deleteEntry` and trust it | It can return without throwing on a device that kept the key. The verification is the whole claim. |
| One eraser holding every alias string | Two copies of each name, and the second one drifts. The owning module declares it and the factory reads the same constant. |
| A drift test instead of shared constants | Detects the drift after it happens. Sharing the constant prevents it. |
| Biometric re-auth instead of the PIN | `BiometricPrompt` needs an Activity and cannot be driven from a view model; the PIN is the factor SEC-002 guarantees exists whenever the lock is on. Biometric-only devices still have a PIN by SEC-002's own design. |
| A confirmation dialog rather than a page | A dialog is dismissed by a tap outside it and has no room for the paragraph about what cannot be undone. |
| Restart into onboarding instead of exiting | Each handle in the process would fail in turn, in front of a user who was just told it worked. |
| Erase the exported backups too | The app cannot: no persisted URI permission, and the file is sealed with a key it never held. Disclosure is the honest option. |
| Cancel the work inside `SecureEraser` | The workers are `:app`'s, and `:data:repository` cannot see WorkManager without inverting ARC-001. A decorator in `:app` leaves the proven ordering untouched. |
| Delete WorkManager's database too | It is `no_backup/` bookkeeping holding class names and UUIDs; no worker here carries input data. Cancelling the work is the behaviour that matters. |
| Declare the Glance file in `:core:common` | The module that owns a secret declares it, and `filesDir/datastore/` is Glance's — which only `:widget` uses. Declaring it elsewhere would put the name where nobody maintaining the widget would see it. |

## Consequences

- The data on the device is unrecoverable by anyone once `destroyKeys` reports `Ok` — verified, not assumed.
- Adding a key chain without declaring it in its module's `SecretInventory` leaves it undestroyed. The instrumented canary scan catches it; `AndroidSecureEraserTest` catches a module dropped from the composition.
- The erase is not reachable by accident: a typed word, a PIN, and a page the user navigated to.
- A user with exported backups has one manual step left, and is told about it rather than misled.
- The background schedule stops with the data, so the app cannot start collecting again behind the user's back.
- A module that caches figures outside the database must declare them, and `:app` must add its inventory. The device run is the only thing that caught the first instance of this; the JVM suite could not have.
- **Deferred:** a DPDP-shaped export of what was held before erasing it (issue 11.5 owns the DPDP surface), and erase-by-profile rather than erase-everything (the app is single-profile in v1).
