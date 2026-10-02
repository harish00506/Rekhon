<!--
  Why:  CLAUDE.md §10 — one file per working session that changes code.
  What: issue 11.7 — the SEC-003 audit, the lint rule that keeps its conclusion true, and the
        registry hole a mutation found.
  Result: a reader can see why an audit that finds nothing still needed code, why resolved types
          beat names for a security rule, and what was wrong with all six lint rules.
  Changelog: 2026-10-02 — Created.
-->

# 2026-10-02 — Nobody gets to invent cryptography here (issue 11.7, ADR-0063)

**Branch:** `feature/11-7-no-hand-rolled-crypto-tink-audit` off `dev` (`cfb5f81`)
**Versions:**
- **VERSION** 0.10.13 → **0.10.14**
- **versionCode** 57 → 58
- **Schema** 29 → **29 (unchanged — this issue ships no runtime code at all)**

**Epic 11 completes with this issue.**

---

## 1 · Decisions this session

The full argument for each is in [ADR-0063](../adr/0063-sec-003-is-enforced-by-a-lint-rule-with-two-argued-exemptions.md).

- **The audit found no hand-rolled cryptography**, and that moved the work rather than ending it.
  Ten production files touch crypto; every cipher, AEAD and MAC is Tink's, every key is in the
  Keystore. The finding was that SEC-003 was **true and unenforced**, so the conclusion would start
  decaying the day after it was written.
- **A lint rule, because this failure mode is silent.** A reused nonce, an unauthenticated mode, a
  `MessageDigest` standing in for a KDF — each encrypts, decrypts, round-trips and passes every test
  while being breakable. No later test catches that, so the gate has to be where the code is typed.
- **Resolved types, not identifier names.** The first version matched the receiver's simple name, as
  `PiiLoggingDetector` does, and lint's own `IMPORT_ALIAS` mode rejected it: `import
  javax.crypto.Cipher as Box` would walk straight through. A security control that can be renamed
  past is not a control.
- **`KeyGenerator` only alongside a `KeyGenParameterSpec`.** ADR-0057 settled that the platform
  holding a key is not hand-rolled crypto, and banning it would make `KeystoreMasterKey` unwritable —
  but a bare `getInstance("AES")` yields an exportable in-memory key, which is the thing SEC-003
  exists to stop. File-scoped, because the spec is built in a helper a few lines away in every real
  example including ours.
- **Test sources exempt.** An independent oracle is how ADR-0039's Argon2id is verified against
  OpenSSL at all; that is what SEC-003 wants to be possible.
- **`SecureRandom` not flagged.** P-08 requires injected, seedable randomness and all four uses here
  are injected. A rule banning it would fight the rule it serves. `KeyStore` likewise — it stores
  keys rather than implementing anything, and the erase must delete from it.
- **The audit's own inventory is drift-tested**, and `docs/security/` was added to the declared
  test-input directories so the check is *scheduled* — inheriting issue 11.5's fix rather than
  repeating its bug.
- **Argon2id stays BouncyCastle's.** Re-read, not re-decided: Tink has no password-based KDF at all,
  SEC-005 mandates Argon2id, and the two cannot both be satisfied by Tink. ADR-0039 stands.

**What this found.**

1. **A detector could be silently switched off.** A mutation removing the new rule from
   `CfoIssueRegistry` left **all 25 lint tests green**, because every test passes `.issues(X.ISSUE)`
   and bypasses the registry — which is the only thing that makes a rule apply to a build. An
   unregistered detector compiles, is fully tested, and enforces nothing.

   **This affected all five pre-existing rules**: MNY-001, ARC-006, TIM-001, the strings rule and the
   PII-logging ban could each have been dropped unnoticed. Two tests now pin it — every detector is
   published, and every published issue is ERROR rather than WARNING, because a rule demoted to a
   warning is registered, tested, and fails nothing.

   This is the same shape as 1.5's unenforced rules, 7.2's and 11.5's unscheduled drift tests and
   11.6's untrusted rule file: **what makes a check apply is a separate concern from the check, and is
   never tested unless somebody tests it deliberately.**
2. **Three of the first detector tests were measuring nothing** — unresolved stubs meant lint reported
   a `LintError` rather than running the rule. Fixed with Java stubs in the real packages, which the
   resolved-type approach requires anyway.
3. **The rule was proven against the real build**, not just the harness: a seeded `Cipher.getInstance`
   in `:core:crypto` makes `lintDebug` fail, with the rule's own explanation printed, and the build
   goes green again when it is removed.
4. **One build was killed by the OS** (exit 137) when Gradle and the emulator together exhausted
   memory. Not a code failure, and recorded rather than quietly re-run.
5. **detekt's `LargeClass` was fixed by splitting, not suppressing** — the crypto and registry tests
   became `CfoHandRolledCryptoDetectorTest`, which is better organisation independent of the limit.

## 2 · Flow changed this session

A build-time path with no runtime chain, which is the point — `FLOW.md` §2.26:

```
any module's :lintDebug → CfoIssueRegistry → CfoHandRolledCrypto (ERROR)
  ├─ resolves the DECLARING class, so an import alias cannot slip past
  ├─ exempt: KeyGenerator IF the file mentions KeyGenParameterSpec   (ADR-0057)
  └─ exempt: /src/test/, /src/androidTest/, /src/sharedTest/          (independent oracles)

:core:common:test → Sec003AuditDriftTest     the audit's inventory, both directions
:lint:test        → the registry itself      all six issues published, every one at ERROR
```

## 3 · Code changed this session

| Path | What it does now |
|------|------------------|
| `docs/security/sec-003-crypto-audit.md` (new) | every crypto call site with a verdict, the one deviation, and the sign-off |
| `lint/HandRolledCryptoDetector.kt` (new) | SEC-003 enforced on resolved types, with two argued exemptions |
| `lint/CfoIssueRegistry.kt` | publishes the sixth rule |
| `lint/src/test/CfoHandRolledCryptoDetectorTest.kt` (new) | 11 tests — 9 for the rule, 2 for the registry; 8 mutations |
| `lint/src/test/CfoLintDetectorsTest.kt` | the crypto tests split out; back under detekt's class-size limit |
| `core/common/src/test/Sec003AuditDriftTest.kt` (new) | 4 tests keeping the audit's inventory honest |
| `build-logic/ProjectExtensions.kt` | `docs/security/` declared as a test input, so the above is scheduled |
| `docs/adr/0063-…`, `DECISIONS.md`, `FLOW.md` §2.26, `CHANGELOG.md`, `docs/memory.md`, `VERSION` | the records |
