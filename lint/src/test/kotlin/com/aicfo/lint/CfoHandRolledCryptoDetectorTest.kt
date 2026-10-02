package com.aicfo.lint

import com.android.tools.lint.checks.infrastructure.TestFiles.java
import com.android.tools.lint.checks.infrastructure.TestFiles.kotlin
import com.android.tools.lint.checks.infrastructure.TestLintTask.lint
import com.android.tools.lint.detector.api.Severity
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * SEC-003 enforced: no cryptography built by hand, and the registry that makes it apply (issue 11.7).
 *
 * Why:  split out of `CfoLintDetectorsTest`, which issue 11.7 pushed past detekt's `LargeClass`
 *       limit. The split is by subject rather than to make a number go down: these cover one rule
 *       and the registry, and the hard part of both is what they **allow** — the platform Keystore
 *       path and a test's independent oracle — rather than what they flag.
 * What: the banned primitives, the two narrow exemptions, and the registry's completeness.
 * Result: SEC-003 is checked at the moment someone types the call, which is the only moment that
 *         works: hand-rolled crypto fails silently and no later test catches it.
 * Changelog: 2026-10-02 — Created for issue 11.7, extracted from `CfoLintDetectorsTest`.
 */
class CfoHandRolledCryptoDetectorTest {
    // --- SEC-003: no hand-rolled cryptography -------------------------------------------------

    /** Input: a raw `Cipher.getInstance`. Output: asserts SEC-003 flags it. */
    @Test
    fun `flags a raw Cipher`() {
        lint()
            .files(
                cipherStub,
                kotlin(
                    "src/main/kotlin/Seal.kt",
                    """
                    package com.aicfo.core.crypto

                    import javax.crypto.Cipher

                    class Seal {
                        fun seal(): Cipher = Cipher.getInstance("AES/GCM/NoPadding")
                    }
                    """,
                ).indented(),
            ).issues(HandRolledCryptoDetector.ISSUE)
            .allowMissingSdk()
            .run()
            .expectErrorCount(1)
            .expectContains("SEC-003")
    }

    /** Input: a hand-built `SecretKeySpec`. Output: asserts key construction is flagged. */
    @Test
    fun `flags a hand-built SecretKeySpec`() {
        lint()
            .files(
                secretKeySpecStub,
                kotlin(
                    "src/main/kotlin/Key.kt",
                    """
                    package com.aicfo.core.crypto

                    import javax.crypto.spec.SecretKeySpec

                    class Key {
                        fun make(bytes: ByteArray) = SecretKeySpec(bytes, "AES")
                    }
                    """,
                ).indented(),
            ).issues(HandRolledCryptoDetector.ISSUE)
            .allowMissingSdk()
            .run()
            .expectErrorCount(1)
            .expectContains("SEC-003")
    }

    /** Input: a `MessageDigest`. Output: asserts rolling a digest is flagged. */
    @Test
    fun `flags a MessageDigest`() {
        lint()
            .files(
                messageDigestStub,
                kotlin(
                    "src/main/kotlin/Hash.kt",
                    """
                    package com.aicfo.core.crypto

                    import java.security.MessageDigest

                    class Hash {
                        fun of(input: ByteArray): ByteArray =
                            MessageDigest.getInstance("SHA-256").digest(input)
                    }
                    """,
                ).indented(),
            ).issues(HandRolledCryptoDetector.ISSUE)
            .allowMissingSdk()
            .run()
            .expectErrorCount(1)
            .expectContains("SEC-003")
    }

    /** Input: a `javax.crypto.Mac`. Output: asserts a hand-built MAC is flagged — Tink has one. */
    @Test
    fun `flags a raw Mac`() {
        lint()
            .files(
                macStub,
                kotlin(
                    "src/main/kotlin/Tag.kt",
                    """
                    package com.aicfo.core.crypto

                    import javax.crypto.Mac

                    class Tag {
                        fun mac(): Mac = Mac.getInstance("HmacSHA256")
                    }
                    """,
                ).indented(),
            ).issues(HandRolledCryptoDetector.ISSUE)
            .allowMissingSdk()
            .run()
            .expectErrorCount(1)
            .expectContains("SEC-003")
    }

    /**
     * Input:  the sanctioned Keystore path — `KeyGenerator` with a `KeyGenParameterSpec`.
     * Output: asserts **no** finding. This is the whole difficulty of the rule: ADR-0057 argues that
     *         asking the platform's own Keystore for a key is not hand-rolled cryptography, and a
     *         detector that banned it would make `KeystoreMasterKey` — the class that puts the
     *         database key in StrongBox — unwritable.
     */
    @Test
    fun `allows the platform Keystore key generator`() {
        lint()
            .files(
                keyGeneratorStub,
                keyGenParameterSpecStub,
                kotlin(
                    "src/main/kotlin/Master.kt",
                    """
                    package com.aicfo.core.database.crypto

                    import android.security.keystore.KeyGenParameterSpec
                    import javax.crypto.KeyGenerator

                    object Master {
                        fun ensure(spec: KeyGenParameterSpec) {
                            KeyGenerator.getInstance("AES", "AndroidKeyStore").apply {
                                init(spec)
                                generateKey()
                            }
                        }
                    }
                    """,
                ).indented(),
            ).issues(HandRolledCryptoDetector.ISSUE)
            .allowMissingSdk()
            .run()
            .expectClean()
    }

    /**
     * Input:  a `KeyGenerator` with **no** `KeyGenParameterSpec` in the file.
     * Output: asserts it IS flagged. The exemption above is narrow on purpose: a bare
     *         `KeyGenerator.getInstance("AES")` produces an exportable in-memory key, which is
     *         exactly the hand-rolled key management SEC-003 exists to prevent, and it would sail
     *         through a rule that allowed the class name outright.
     */
    @Test
    fun `flags a KeyGenerator that is not going through the Keystore`() {
        lint()
            .files(
                keyGeneratorStub,
                kotlin(
                    "src/main/kotlin/Loose.kt",
                    """
                    package com.aicfo.core.crypto

                    import javax.crypto.KeyGenerator

                    object Loose {
                        fun key() = KeyGenerator.getInstance("AES").generateKey()
                    }
                    """,
                ).indented(),
            ).issues(HandRolledCryptoDetector.ISSUE)
            .allowMissingSdk()
            .run()
            .expectErrorCount(1)
            .expectContains("SEC-003")
    }

    /**
     * Input:  an injected `SecureRandom` used for a salt.
     * Output: asserts no finding. P-08 requires randomness to be injected and seedable, and every
     *         `SecureRandom` in this codebase is a constructor parameter feeding a salt or a
     *         passphrase that Tink or Argon2id then uses. Flagging it would fight the rule it serves.
     */
    @Test
    fun `allows an injected SecureRandom for a salt`() {
        lint()
            .files(
                secureRandomStub,
                kotlin(
                    "src/main/kotlin/Salt.kt",
                    """
                    package com.aicfo.core.crypto

                    import java.security.SecureRandom

                    class Salt(private val random: SecureRandom) {
                        fun next(): ByteArray = ByteArray(16).also { random.nextBytes(it) }
                    }
                    """,
                ).indented(),
            ).issues(HandRolledCryptoDetector.ISSUE)
            .allowMissingSdk()
            .run()
            .expectClean()
    }

    /**
     * Input:  Tink's own API.
     * Output: asserts no finding — the sanctioned path must stay writable, or the rule would ban
     *         the thing SEC-003 mandates.
     */
    @Test
    fun `allows Tink`() {
        lint()
            .files(
                tinkStub,
                kotlin(
                    "src/main/kotlin/TinkUse.kt",
                    """
                    package com.aicfo.core.crypto

                    import com.google.crypto.tink.Aead

                    class TinkUse(private val aead: Aead) {
                        fun seal(plain: ByteArray): ByteArray = aead.encrypt(plain, null)
                    }
                    """,
                ).indented(),
            ).issues(HandRolledCryptoDetector.ISSUE)
            .allowMissingSdk()
            .run()
            .expectClean()
    }

    /**
     * Input:  a raw `Cipher` in a **test** source.
     * Output: asserts no finding. A test may legitimately implement a primitive as an independent
     *         oracle — `BackupCipherTest` checks Tink's output against a separately computed one, and
     *         that is exactly the kind of verification SEC-003 wants to be possible.
     */
    @Test
    fun `allows a raw primitive in a test source, as an independent oracle`() {
        lint()
            .files(
                cipherStub,
                kotlin(
                    "src/test/kotlin/OracleTest.kt",
                    """
                    package com.aicfo.core.crypto

                    import javax.crypto.Cipher

                    class OracleTest {
                        fun reference(): Cipher = Cipher.getInstance("AES/GCM/NoPadding")
                    }
                    """,
                ).indented(),
            ).issues(HandRolledCryptoDetector.ISSUE)
            .allowMissingSdk()
            .run()
            .expectClean()
    }

    // --- The registry itself ------------------------------------------------------------------

    /**
     * Every detector in this module is published by the registry.
     *
     * Why:  **found by a mutation that nothing else caught.** Deleting a detector from
     *       [CfoIssueRegistry] left all 25 tests above green, because each one passes
     *       `.issues(X.ISSUE)` explicitly and so bypasses the registry entirely. But the registry is
     *       what makes a rule *apply* to the build: lint discovers checks through it alone (named in
     *       the jar manifest as `Lint-Registry-v2`). An unregistered detector compiles, is tested,
     *       and enforces nothing — which is precisely the documented-but-unenforced failure this
     *       whole module was written to fix, reappearing one level up.
     *
     *       So this asserts the list rather than any behaviour: the count is pinned, so adding a
     *       detector without registering it fails here and says why.
     * Input:  none. Output: asserts all six issues are published, by id.
     */
    @Test
    fun `the registry publishes every detector in this module`() {
        val published = CfoIssueRegistry().issues.map { it.id }.toSet()
        val expected =
            setOf(
                "CfoMoneyAsFloatingPoint",
                "CfoGlobalScope",
                "CfoWallClockInDomain",
                "CfoHardcodedUiString",
                "CfoPiiInLogs",
                "CfoHandRolledCrypto",
            )
        assertEquals(
            "a detector that is not registered enforces nothing, however well it is tested",
            expected,
            published,
        )
    }

    /**
     * Input:  the registry. Output: asserts every published issue is build-blocking.
     * Why:    severity is the other half of "enforced". A rule demoted to WARNING would still be
     *         registered, still be tested, and would no longer fail anything — the same silent
     *         erosion in a different place. Every rule in this module is a `CLAUDE.md` invariant, and
     *         `config/detekt` and the convention plugins turn warnings into failures only for
     *         detekt, not for lint.
     */
    @Test
    fun `every registered issue is an error, not a warning`() {
        CfoIssueRegistry().issues.forEach { issue ->
            assertEquals("${issue.id} must block the build", Severity.ERROR, issue.defaultSeverity)
        }
    }

    /**
     * The stub classpath for the crypto tests.
     *
     * Why:    the detector resolves **declaring classes** rather than identifier names, precisely so
     *         an import alias cannot defeat it — which means the test files have to resolve. These
     *         are Java stubs in the real packages: `javax.crypto`, `javax.crypto.spec` and
     *         `java.security`, plus the two Android and Tink types the exemption tests mention.
     *         Without them the imports are unresolved, lint reports a `LintError`, and the
     *         assertions would be measuring nothing — the vacuous-gate shape this repository keeps
     *         finding.
     */
    private val cipherStub =
        java(
            """
            package javax.crypto;
            public class Cipher {
                public static Cipher getInstance(String transformation) { return new Cipher(); }
            }
            """,
        ).indented()

    private val macStub =
        java(
            """
            package javax.crypto;
            public class Mac {
                public static Mac getInstance(String algorithm) { return new Mac(); }
            }
            """,
        ).indented()

    private val keyGeneratorStub =
        java(
            """
            package javax.crypto;
            public class KeyGenerator {
                public static KeyGenerator getInstance(String algorithm) { return new KeyGenerator(); }
                public static KeyGenerator getInstance(String algorithm, String provider) { return new KeyGenerator(); }
                public void init(Object spec) { }
                public Object generateKey() { return null; }
            }
            """,
        ).indented()

    private val secretKeySpecStub =
        java(
            """
            package javax.crypto.spec;
            public class SecretKeySpec {
                public SecretKeySpec(byte[] key, String algorithm) { }
            }
            """,
        ).indented()

    private val messageDigestStub =
        java(
            """
            package java.security;
            public class MessageDigest {
                public static MessageDigest getInstance(String algorithm) { return new MessageDigest(); }
                public byte[] digest(byte[] input) { return input; }
            }
            """,
        ).indented()

    private val secureRandomStub =
        java(
            """
            package java.security;
            public class SecureRandom {
                public void nextBytes(byte[] bytes) { }
            }
            """,
        ).indented()

    private val keyGenParameterSpecStub =
        java(
            """
            package android.security.keystore;
            public class KeyGenParameterSpec { }
            """,
        ).indented()

    private val tinkStub =
        java(
            """
            package com.google.crypto.tink;
            public interface Aead {
                byte[] encrypt(byte[] plaintext, byte[] associatedData);
            }
            """,
        ).indented()
}
