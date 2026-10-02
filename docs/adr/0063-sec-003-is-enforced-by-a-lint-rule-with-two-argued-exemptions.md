<!--
  Why:  CLAUDE.md §5 — any decision or deviation from the SRS needs an ADR.
  What: issue 11.7 — the SEC-003 audit, the lint rule that keeps its conclusion true, and the two
        exemptions that rule has to make.
  Result: a reader can see why resolved types beat identifier names here, why the Keystore and test
          exemptions are narrow, and what a mutation found about the registry.
  Changelog: 2026-10-02 — Created.
-->

# ADR-0063 — SEC-003 is enforced by a lint rule, with two argued exemptions

**Status:** Accepted · **Date:** 2026-10-02 · **Issue:** 11.7 · **SRS:** SEC-003

## Context

SEC-003 says all cryptography goes through Tink or the Android Keystore — no hand-rolled crypto,
review-blocking. It was documented and **unenforced**: nothing in the build checked it, which is the
same systemic gap the governance audit named and that issues 1.5, 7.2, 11.5 and 11.6 have each found
another instance of.

The audit (`docs/security/sec-003-crypto-audit.md`) read every cryptographic call site in production
code. **It found no hand-rolled cryptography.** Ten files touch crypto; every cipher, AEAD and MAC is
Tink's, every key lives in the Keystore, and the one deviation — Argon2id from BouncyCastle, because
Tink has no password-based KDF at all — was already argued in ADR-0039.

So the finding was not a bug. It was that the conclusion would start decaying the day after it was
written, because nothing would notice.

## Decision

### 1 · A lint rule, because this failure mode is silent

`CfoHandRolledCrypto`, severity ERROR, build-blocking in every module via the convention plugins.
The reason the gate has to be at the moment someone types the call, rather than in a later test, is
that hand-rolled crypto **fails silently**: a reused nonce, an unauthenticated mode, a
`MessageDigest` standing in for a KDF — each produces output that looks correct, round-trips
correctly, and passes every test while being broken. There is no test that catches it afterwards.

Flagged: `Cipher`, `Mac`, `MessageDigest`, `Signature`, `SecretKeyFactory`, and the hand-built
`SecretKeySpec` / `IvParameterSpec` / `GCMParameterSpec` / `PBEKeySpec`.

### 2 · Resolved types, not identifier names

The first version matched the receiver's simple name, the way `PiiLoggingDetector` does. Lint's own
`IMPORT_ALIAS` test mode rejected it, and it was right to: Kotlin can write
`import javax.crypto.Cipher as Box`, and a security control that `Box.getInstance(...)` walks
straight through is not a control. So the detector resolves the declaring class and compares
fully-qualified names, via `getApplicableMethodNames` + `getApplicableConstructorTypes`.

The cost is that the JDK must be on lint's analysis classpath — it is, for every module here — and
that the detector's tests need stubs in the real packages. That cost is worth paying for a rule whose
whole value is that it cannot be sidestepped.

### 3 · `KeyGenerator` is allowed only alongside a `KeyGenParameterSpec`

ADR-0057 settled that asking the platform's own Keystore for a key is not hand-rolled cryptography:
nothing implements a cipher, a mode, a padding or a KDF, and the Keystore generates and holds the key
exactly as it does when Tink asks — with one builder flag that moves it into StrongBox. Banning
`KeyGenerator` outright would make `KeystoreMasterKey`, the class that puts the database key in
hardware, unwritable.

But a bare `KeyGenerator.getInstance("AES")` produces an **exportable in-memory key**, which is
precisely the hand-rolled key management SEC-003 exists to prevent, and it would sail through a rule
that allowed the class name. So the exemption is conditional on `KeyGenParameterSpec` appearing in the
file — the Keystore's own configuration type, which cannot be used for anything else and is therefore
a reliable signal.

**File-scoped rather than call-scoped**, deliberately: the spec is built by a helper a few lines from
`generateKey()` in every real example, this project's own included. Tying it to the single call
expression would reject the code it exists to permit.

### 4 · Test sources are exempt

A test may implement a primitive as an **independent oracle** — `BackupCipherTest` checks Tink's
output against a separately computed one, and ADR-0039's Argon2id is verified against OpenSSL's. That
is the kind of verification SEC-003 wants to be possible, not the kind it forbids.

### 5 · The audit's inventory is checked too

The lint rule stops a *banned* primitive. It cannot tell you that a new file has started doing
something sanctioned-but-cryptographic — another Tink keyset, a second Keystore alias, a new
`SecureRandom`. The audit's §2 table claims to list every such site, and that claim decays the same
way every compliance claim does. `Sec003AuditDriftTest` checks it in both directions, and
`docs/security/` was added to the test-input directories so the check is actually **scheduled** —
without that, issue 11.5's finding would have repeated immediately.

### 6 · `SecureRandom` is not flagged

P-08 requires randomness from an injected, seedable source, and every `SecureRandom` in this codebase
is a constructor parameter feeding a salt or passphrase that Tink or Argon2id then consumes. A rule
banning it would fight the rule it serves. `java.security.KeyStore` is unflagged for a related reason:
it stores keys rather than implementing anything, and the erase (issue 11.4) must be able to delete
from it.

## What a mutation found: the registry

Removing the new detector from `CfoIssueRegistry` left **all 25 lint tests green.** Every test passes
`.issues(X.ISSUE)` explicitly and therefore bypasses the registry — which is the only thing that makes
a rule apply to a build, since lint discovers checks through it alone (`Lint-Registry-v2` in the jar
manifest). An unregistered detector compiles, is fully tested, and enforces nothing.

That affected **all five pre-existing rules**, not just this one: MNY-001, ARC-006, TIM-001, the
strings rule and the PII-logging ban could each have been silently unregistered. Two tests now cover
it — every detector is published, and every published issue is `ERROR` rather than `WARNING`, because
a rule demoted to a warning is registered, tested, and no longer fails anything.

This is the same defect shape as 1.5's unenforced rules, 7.2's and 11.5's unscheduled drift tests, and
11.6's untrusted rule file: **the thing that makes a check apply is a separate concern from the check,
and it is never tested unless someone tests it deliberately.**

## Alternatives rejected

| Alternative | Why not |
|---|---|
| Leave SEC-003 to code review | It is review-blocking in the SRS and was never checked. Review does not scale to a rule whose violations look correct. |
| Match identifier names, like the PII rule | `import javax.crypto.Cipher as Box` defeats it. Lint's own `IMPORT_ALIAS` mode catches this. |
| Ban `KeyGenerator` outright | Makes `KeystoreMasterKey` unwritable and contradicts ADR-0057. |
| Allow `KeyGenerator` unconditionally | A bare `getInstance("AES")` yields an exportable in-memory key — the exact thing SEC-003 forbids. |
| Call-scoped Keystore exemption | Rejects the real code: the spec is built in a helper nearby. |
| Flag `SecureRandom` | Fights P-08, which requires randomness to be injected. |
| Flag test sources too | Independent oracles are how ADR-0039's Argon2id is verified at all. |
| A detekt rule instead | detekt has no resolved-type information for JDK classes here; lint does, and lint is where this project's other invariants live. |
| An audit document with no drift test | The conclusion decays the day after it is written, which is the whole reason this issue exists. |

## Consequences

- SEC-003 is enforced at compile time in every module, proven by a seeded `Cipher.getInstance` failing
  a real `lintDebug` — not only the test harness.
- The rule cannot be bypassed by an import alias.
- Adding a crypto-touching file without listing it in the audit fails the build.
- **All six custom lint rules are now protected against being silently unregistered or demoted.**
- A third exemption to this rule should trigger a re-audit; the audit says so.
- ADR-0039's deviation stands: Argon2id remains BouncyCastle's until Tink ships a password-based KDF.
- **Epic 11 is complete** with this issue.
