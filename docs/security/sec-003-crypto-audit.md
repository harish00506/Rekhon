<!--
  Why:  issue 11.7 — SEC-003 asks for an audit confirming all cryptography goes through
        Tink/Keystore, with findings resolved and a sign-off recorded.
  What: every cryptographic call site in production code, with a verdict for each; the one
        documented deviation; and what now stops this going stale.
  Result: a reader can check every line here against the code, and `Sec003AuditDriftTest` fails the
          build when a file starts touching cryptography without being listed.
  Changelog: 2026-10-02 — Created for issue 11.7.
-->

# SEC-003 — no-hand-rolled-crypto audit

**Scope:** every `src/main` source in the repository. **Date:** 2026-10-02. **Issue:** 11.7.
**Verdict: no hand-rolled cryptography.** One documented deviation from "Tink only", argued in
[ADR-0039](../adr/0039-argon2id-from-bouncycastle-and-the-backup-consent-gates-saf.md) and
unchanged by this audit.

This is an engineering record, not a formal third-party assessment.

---

## 1 · Method

Three passes, in this order, because each would miss what the others catch:

1. **Imports** — every `javax.crypto.*`, `java.security.*`, `android.security.keystore.*`,
   `com.google.crypto.tink.*` and `org.bouncycastle.*` import in a production source.
2. **Fully-qualified and aliased uses** — a grep for `Cipher.getInstance`, `MessageDigest`,
   `javax.crypto.Mac`, `SecretKeySpec`, `IvParameterSpec`, `GCMParameterSpec`, `PBEKeySpec`, in case
   something was used without an import.
3. **Call-site reading** — each hit opened and judged, rather than counted.

Pass 2 returned **nothing**. There is no `Cipher`, `MessageDigest`, `javax.crypto.Mac`,
`SecretKeySpec` or parameter-spec construction anywhere in production code.

## 2 · Every cryptographic call site, and its verdict

| File | What it touches | Verdict |
|---|---|---|
| `core/crypto/KeystoreMacFactory.kt` | Tink `AndroidKeysetManager` + `Mac`, Keystore master key, injected `SecureRandom` for the PIN salt | **Tink.** The MAC is Tink's; the salt source is injected (P-08) |
| `core/crypto/PinVerifier.kt` | injected `SecureRandom`, `GeneralSecurityException` | **No crypto of its own.** Tags via the Tink `Mac` above |
| `core/crypto/ReceiptImageStore.kt` | Tink `AndroidKeysetManager` + `Aead` | **Tink** |
| `core/crypto/BackupCipher.kt` | Tink `AesGcmJce`; **BouncyCastle `Argon2BytesGenerator`**; injected `SecureRandom` for the salt | **Tink for the cipher; the KDF is the documented deviation** — see §3 |
| `core/database/crypto/KeystoreAeadFactory.kt` | Tink `AndroidKeysetManager` + `Aead` | **Tink** |
| `core/database/crypto/KeystoreMasterKey.kt` | `javax.crypto.KeyGenerator`, `KeyGenParameterSpec`, `KeyProperties`, `KeyStore` | **Platform Keystore, sanctioned.** See §4 |
| `core/database/crypto/SqlCipherPassphraseManager.kt` | injected `SecureRandom` for the passphrase | **No crypto of its own.** Wrapping is Tink's `Aead` |
| `core/database/CfoDatabaseFactory.kt` | constructs `SecureRandom()` to inject | **Injection site, not a crypto operation** |
| `data/repository/AndroidSecureEraser.kt` | `java.security.KeyStore` — `deleteEntry`, `containsAlias` | **Key *destruction*, not construction** (issue 11.4) |
| `lint/HandRolledCryptoDetector.kt` | the type names, as strings | **The rule itself.** Touches no crypto |

**`SecureRandom` is used in four places and is injected in every one** — a constructor parameter
feeding a salt or a passphrase that Tink or Argon2id then consumes. That is what P-08 asks for
(randomness from an injected, seedable source), so it is not flagged by the new lint rule: a rule
banning it would fight the rule it serves.

## 3 · The one deviation: Argon2id comes from BouncyCastle

SEC-005 mandates "AES-256-GCM with a key derived from a user passphrase via Argon2id". SEC-003
mandates Tink or platform APIs. **Both cannot be met at once**: Tink has no password-based KDF at
all, and Android's JCA offers PBKDF2 but not Argon2.

Resolved in [ADR-0039](../adr/0039-argon2id-from-bouncycastle-and-the-backup-consent-gates-saf.md),
and this audit re-reads it rather than re-deciding it:

- BouncyCastle supplies **only** Argon2id, behind one internal object, so no other module can reach it.
- Every cipher operation stays in Tink (`AesGcmJce`, which draws its own nonce).
- The derivation is checked against **OpenSSL 3.5's independent Argon2id**, so the output is verified
  against something that is not this codebase.
- No construction is invented: Argon2id is RFC 9106 and the implementation is a maintained library's.

**SEC-003's wording is stretched, not broken**, and the stretch is on the record in two places now.

## 4 · Why `KeyGenerator` in `KeystoreMasterKey` is not a finding

It asks the platform's own Keystore for a key, with `setIsStrongBoxBacked` where the device has the
hardware ([ADR-0057](../adr/0057-the-database-key-lives-in-the-strongest-place-the-phone-has-and-rotates-without-losing-the-database.md)).
Nothing there implements a cipher, a mode, a padding or a key derivation — the Keystore generates and
holds the key exactly as it does when Tink asks it to, and the only difference is one builder flag
that moves the key into better hardware. Tink has no StrongBox option of its own, which is why the
key is created at the alias before Tink looks for it.

The new lint rule encodes exactly this distinction: `KeyGenerator` is allowed **only** in a file that
also mentions `KeyGenParameterSpec`. A bare `KeyGenerator.getInstance("AES")` produces an exportable
in-memory key and is flagged, because that is the hand-rolled key management SEC-003 exists to stop.

## 5 · Findings and sign-off

| # | Finding | Resolution |
|---|---|---|
| 1 | **No hand-rolled cryptography found.** No raw cipher, digest, MAC or key spec in production code | Nothing to fix |
| 2 | SEC-003 was **documented but unenforced** — nothing checked it, so the audit's conclusion would have decayed the day after it was written | `CfoHandRolledCrypto` added, severity ERROR, build-blocking in every module. Proven to fail a real `lintDebug` on a seeded `Cipher.getInstance` |
| 3 | A mutation found that **a detector could be removed from `CfoIssueRegistry` with all 25 lint tests still green** — every test passes `.issues(X.ISSUE)` explicitly and so bypasses the registry, which is the only thing that makes a rule apply to the build. This affected all five pre-existing rules, not just the new one | Two registry tests added: every detector is published, and every published issue is `ERROR` rather than `WARNING` |
| 4 | Argon2id is BouncyCastle's, not Tink's | Pre-existing, argued in ADR-0039, re-read here. Not a new finding |

**Sign-off.** Audited by the implementer against the method in §1, reviewed via the
`security-review` skill's checklist, and recorded here. The tests that keep each conclusion true are
named above; the inventory in §2 is checked by `Sec003AuditDriftTest`, which fails the build when a
production file starts touching cryptography without appearing in this table.

Re-audit when: a new crypto library is added, Tink gains a password-based KDF (then ADR-0039's
deviation should close), or `CfoHandRolledCrypto` acquires a third exemption.
