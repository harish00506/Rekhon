# ADR-0003 — The app lock gates the session, not the Keystore key

- **Status:** accepted
- **Date:** 2026-07-26
- **Deciders:** Harish G (solo), implementing issue 2.2
- **SRS refs:** §23.1, §23.2 (SEC-001, SEC-002, SEC-003), §21.2, §21.6, P-01, TIM-001

## Context

Issue 2.2 builds SEC-002: *"Biometric unlock via BiometricPrompt (class 3) with PIN fallback; 5
failed PIN attempts → 30 s lockout doubling."* The issue's own description adds that the lock
"gates access to the encrypted store on cold start and after an idle timeout".

That sentence is the hard part, because SEC-001 says something stronger than what issue 1.6 built:

> **SEC-001** — Database key: 256-bit random, generated on first run, **wrapped by a Keystore key
> requiring user authentication**; never written to disk unwrapped, never leaves the device.

Issue 1.6 implemented all of SEC-001 except the emphasised clause. Its `KeystoreAeadFactory` uses
Tink's `AndroidKeysetManager`, whose master key is TEE-backed but **not** bound to user
authentication. So "the lock gates the encrypted store" could mean two very different things, and
four smaller questions came with it: where the lock screen lives, how the gate is enforced, how a
PIN is stored, and how the audit log gets written while the app is locked.

## Decision

### 1. The lock gates a session flag, and the Keystore key stays as issue 1.6 built it

A `SessionLock` (`:core:crypto`) starts closed and is opened only by a verified PIN or a successful
class-3 biometric. The Hilt provider for `CfoDatabase` asserts it is open before handing the
database to feature code.

**SEC-001's user-authentication clause is deliberately left open.** Closing it would mean
pre-generating the Keystore alias with `setUserAuthenticationRequired(true)` — Tink's Android
integration exposes no such option — and then rotating the key for every existing installation. The
failure mode is severe and irreversible: an auth-bound Keystore key is **permanently invalidated**
when the user removes or changes their device lock screen, and since this app has no server copy,
that would render the database unopenable and the user's entire financial history unrecoverable.
That risk is not worth taking to close a gap on a threat (offline extraction of the DB file) that
SQLCipher + a TEE-backed key already addresses.

**This is an accepted, open deviation from SEC-001, not a completed requirement.** It should be
revisited alongside issue 11.1 (SQLCipher key management / StrongBox), which is where key rotation
gets a real home.

### 2. The lock screen lives in `:app`, not a new `:feature:*` module

SRS §21.2's module list has no `:feature:lock`. The lock is the navigation host's gate rather than a
destination within it, and `:app` is already "DI graph assembly, navigation host, MainActivity".
Adding a module for one screen would have cost a Gradle script, a Robolectric setup and a resource
set to gain nothing. The auth *logic* went to `:core:crypto`, which §21.2 defines as "key
management, Keystore, encryption helpers" and which was an empty placeholder until now.

`CfoPinField` went to `:core:designsystem` because both the lock screen (`:app`) and onboarding's
security step (`:feature:onboarding`) need it, and ARC-001 forbids them depending on each other.

### 3. The gate is a composable **wrapper**, not a `CfoRoute`

`AppLockGate` wraps the whole `CfoNavHost`. A `CfoRoute.Lock` destination would be poppable from the
back stack, skippable by a deep link, and landable-behind by a restored task — three ways to be past
the lock without having unlocked. A wrapper has none of those, and it covers onboarding too, which a
start-destination approach would not.

### 4. The PIN is verified against a Keystore-bound Tink MAC

A 4–6 digit PIN is at most a million candidates; any laptop walks all of them against a stored hash
in seconds, however many rounds it was stretched with. The answer is not a better hash but making
the check impossible to perform off the device: `TinkPinVerifier` stores `salt || HMAC-SHA256(salt ||
pin)` under a key generated **inside** the Android Keystore, which cannot be exported. An attacker
holding the credential file has no oracle to test guesses against; guessing on the device is what
`LockoutPolicy` makes hopeless. Tink primitives only — SEC-003 forbids hand-rolled crypto.

The PIN keyset uses its **own** alias, separate from issue 1.6's database key, so that rotating or
destroying one does not silently take the other with it — and erase-all (SEC-006) destroys the
database key on purpose.

### 5. The audit log is the one thing allowed past the gate, and it says so in the type system

§21.6 sends security events to `audit_log`, and the most important event — a *refused* unlock —
happens while the app is locked. So the audit log genuinely needs the database before the session
opens.

Rather than a comment asking people not to misuse it, there are two Hilt bindings: an
`@AuditDatabase`-qualified one (ungated) that only `provideAuditLogRepository` injects, and the
unqualified `CfoDatabase` (gated) that everything else gets. The exemption is therefore visible at
every injection site and is one string to grep for; a repository wanting ungated access has to name
it and justify it in review.

## Consequences

**Good.** "The lock gates the encrypted store" is enforced by an assertion with a test behind it
rather than by the UI being correct. No new module. Every fail-secure decision sits in a JVM-testable
ViewModel — which matters more than usual here, because this machine has no emulator, so the 21-case
fail-secure matrix in `AppLockViewModelTest` is the only proof that exists.

**Bad — and this is the honest limit of decision 1.** Hilt caches the gated provider per
`@Singleton`, so the check runs the first time feature code asks for the database and not on every
later access. It proves *"no financial data was read before the first unlock"* — real, and the
property that matters on a cold start — but it does **not** re-assert after an idle re-lock. Once
the process has the database open, an idle re-lock hides the UI without closing the file. Closing
that gap properly needs the auth-bound key SEC-001 describes, i.e. decision 1 reversed.

**Also bad.** FR-ONB-001 is still not fully satisfied: this issue inserts the SECURITY step
ADR-0002 reserved, but step 4 (first account) waits on issue 2.5.

**Neutral.** `audit_log` arrives here as database schema **v2**, taking the first real version bump
that had been pencilled in for issue 2.5. `:data:repository` gets its first real class for the same
reason.

## Alternatives considered

**Bind the Tink master key to user authentication now.** Rejected for the data-loss reason in
decision 1: a user who removes their device lock screen would lose everything, with no server copy
and no warning. Revisit in issue 11.1.

**A UI-only lock, with no database guard.** Rejected: "gates access to the encrypted store" would
then be aspirational, and this project has already been bitten once by a gate that could not fail
(the no-op coverage rule, governance audit G-01).

**Give the audit log its own unencrypted store, so no exemption is needed.** Rejected: a security
log outside the encrypted database is a security log an attacker can read and edit, which is worse
than the narrow, typed exemption taken instead.

**A `:feature:lock` module.** Rejected as cost without benefit — see decision 2.
