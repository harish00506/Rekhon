package com.aicfo.lint

import com.android.tools.lint.detector.api.Category
import com.android.tools.lint.detector.api.Detector
import com.android.tools.lint.detector.api.Implementation
import com.android.tools.lint.detector.api.Issue
import com.android.tools.lint.detector.api.JavaContext
import com.android.tools.lint.detector.api.Scope
import com.android.tools.lint.detector.api.Severity
import com.android.tools.lint.detector.api.SourceCodeScanner
import com.intellij.psi.PsiMethod
import org.jetbrains.uast.UCallExpression

/**
 * Fails the build when cryptography is built by hand instead of asked of Tink (SEC-003).
 *
 * Why:  SEC-003 says all cryptography goes through Tink or the Android Keystore, and until this
 *       detector nothing checked. That is the same documented-but-unenforced gap the governance
 *       audit named as its systemic finding, and it matters more here than in most places: the
 *       failures of hand-rolled crypto are **silent**. A reused nonce, an unauthenticated mode, a
 *       `MessageDigest` standing in for a key derivation — each produces output that looks perfectly
 *       correct, passes every test, and is broken. No later test catches that, which is why the gate
 *       has to be at the moment someone types the call.
 *
 *       Issue 11.7's audit found the codebase already clean. This exists so it stays that way.
 * What: flags construction of `Cipher`, `Mac`, `MessageDigest`, `SecretKeySpec`, `IvParameterSpec`,
 *       `GCMParameterSpec`, `PBEKeySpec` and `SecretKeyFactory` in production sources.
 * Result: `Cipher.getInstance("AES/GCM/NoPadding")` fails lint; `aead.encrypt(...)` does not.
 * Changelog: 2026-10-02 — Created for issue 11.7 (SEC-003).
 *
 * **Resolved types, not identifier names.** The first version of this matched the receiver's simple
 * name, the way [PiiLoggingDetector] does — and lint's own `IMPORT_ALIAS` test mode rejected it,
 * correctly: Kotlin can write `import javax.crypto.Cipher as Box`, and a security control that
 * `Box.getInstance(...)` walks straight through is not a control. So this resolves the declaring
 * class and compares fully-qualified names. The cost is that the JDK has to be on lint's analysis
 * classpath, which it is for every module here.
 *
 * **Two exemptions, both narrow, both argued.**
 *
 * 1. `KeyGenerator` **only when the file also mentions `KeyGenParameterSpec`.** ADR-0057 settled
 *    that asking the platform's own Keystore for a key is not hand-rolled cryptography — nothing
 *    implements a cipher, a mode, a padding or a KDF; the Keystore generates and holds the key
 *    exactly as it does when Tink asks, with one builder flag that moves it into StrongBox. Banning
 *    it outright would make `KeystoreMasterKey` unwritable. But a bare
 *    `KeyGenerator.getInstance("AES")` with no spec produces an exportable in-memory key, which is
 *    precisely the hand-rolled key management SEC-003 exists to prevent — so the exemption is
 *    conditional, not a class-name allowlist.
 * 2. **Test sources.** A test may implement a primitive as an *independent oracle* —
 *    `BackupCipherTest` checks Tink's output against a separately computed one — and that is the
 *    kind of verification SEC-003 wants to be possible, not the kind it forbids.
 */
class HandRolledCryptoDetector :
    Detector(),
    SourceCodeScanner {
    /**
     * Input:  none.
     * Output: the method names worth resolving — every banned primitive is reached through one of
     *         these factories, so this is the cheap pre-filter before the type check.
     */
    override fun getApplicableMethodNames(): List<String> = listOf("getInstance")

    /**
     * Input:  none. Output: the constructors to intercept, by fully-qualified type.
     * Why:    the key and parameter specs are built with `new`, not a factory, so they need the
     *         constructor hook rather than the method one.
     */
    override fun getApplicableConstructorTypes(): List<String> = BANNED_CONSTRUCTORS

    /**
     * Reports `X.getInstance(...)` on a banned primitive.
     * Input:  [context]; [node] — the call; [method] — the resolved factory. Output: none (reports).
     * Changelog: 2026-10-02 — Created for issue 11.7.
     */
    override fun visitMethodCall(
        context: JavaContext,
        node: UCallExpression,
        method: PsiMethod,
    ) {
        val owner = method.containingClass?.qualifiedName ?: return
        if (owner !in BANNED_FACTORIES) return
        if (owner == KEY_GENERATOR && isKeystoreBacked(context)) return
        report(context, node, owner)
    }

    /**
     * Reports `SecretKeySpec(...)` and the other hand-built specs.
     * Input:  [context]; [node] — the constructor call; [constructor] — the resolved constructor.
     * Output: none (reports).
     * Changelog: 2026-10-02 — Created for issue 11.7.
     */
    override fun visitConstructor(
        context: JavaContext,
        node: UCallExpression,
        constructor: PsiMethod,
    ) {
        val owner = constructor.containingClass?.qualifiedName ?: return
        report(context, node, owner)
    }

    /**
     * Raises the finding, unless the file is a test.
     * Why:    both entry points need the same test-source exemption and the same message, and a
     *         security rule whose message differs by call shape would read as two rules.
     * Result: one reported issue naming the primitive. Input: [context]; [node]; [owner] — the
     *         fully-qualified banned type. Output: none.
     * Changelog: 2026-10-02 — Created for issue 11.7.
     */
    private fun report(
        context: JavaContext,
        node: UCallExpression,
        owner: String,
    ) {
        if (isTestSource(context)) return
        context.report(
            ISSUE,
            node,
            context.getLocation(node),
            "SEC-003: `${owner.substringAfterLast('.')}` builds cryptography by hand. Use Tink — " +
                "`Aead` for encryption, the Keystore-backed `Mac` for tags, `BackupCipher` for the " +
                "backup — or the Android Keystore for key storage. Hand-rolled crypto fails " +
                "silently: a reused nonce or an unauthenticated mode produces output that passes " +
                "every test and is broken.",
        )
    }

    /**
     * Whether this file generates a key *into the platform Keystore*.
     * Why:    the one exemption that needs more than a name. `KeyGenParameterSpec` is the Android
     *         Keystore's own configuration type and cannot be used for anything else, so its
     *         presence in the file is a reliable, cheap signal that the key is created in hardware
     *         rather than in memory.
     * Result: `true` when the file mentions `KeyGenParameterSpec`.
     * Input:  [context] — the file being analysed. Output: [Boolean].
     * Changelog: 2026-10-02 — Created for issue 11.7.
     *
     * File-scoped rather than call-scoped on purpose: the spec is built by a helper a few lines away
     * from `generateKey()` in every real example, this project's own `KeystoreMasterKey` included.
     * Tying it to the single call expression would reject the code it exists to permit.
     */
    private fun isKeystoreBacked(context: JavaContext): Boolean =
        context.uastFile?.sourcePsi?.text?.contains(KEYSTORE_SPEC) == true

    /**
     * Whether the file under analysis is a test source.
     * Why:    see the class note — a test may implement a primitive as an independent oracle.
     * Result: `true` for a path under a test source set.
     * Input:  [context]. Output: [Boolean].
     * Changelog: 2026-10-02 — Created for issue 11.7.
     */
    private fun isTestSource(context: JavaContext): Boolean {
        val path = context.file.invariantSeparatorsPath
        return TEST_SOURCE_MARKERS.any { it in path }
    }

    companion object {
        /** The platform Keystore's key generator — banned only without a `KeyGenParameterSpec`. */
        private const val KEY_GENERATOR = "javax.crypto.KeyGenerator"

        /**
         * Primitives reached through a `getInstance` factory, fully qualified.
         *
         * Deliberately **absent**: `java.security.SecureRandom`. P-08 requires randomness to be
         * injected and seedable, and every `SecureRandom` in this codebase is a constructor
         * parameter feeding a salt or passphrase that Tink or Argon2id then consumes — flagging it
         * would fight the rule it serves. `java.security.KeyStore` is absent for a related reason:
         * it stores keys rather than implementing anything, and the erase (issue 11.4) has to be
         * able to delete from it.
         */
        private val BANNED_FACTORIES =
            setOf(
                "javax.crypto.Cipher",
                "javax.crypto.Mac",
                "javax.crypto.SecretKeyFactory",
                "java.security.MessageDigest",
                "java.security.Signature",
                KEY_GENERATOR,
            )

        /** Specs and keys built with a constructor rather than a factory, fully qualified. */
        private val BANNED_CONSTRUCTORS =
            listOf(
                "javax.crypto.spec.SecretKeySpec",
                "javax.crypto.spec.IvParameterSpec",
                "javax.crypto.spec.GCMParameterSpec",
                "javax.crypto.spec.PBEKeySpec",
            )

        /** Android's Keystore configuration type; its presence means the key lives in hardware. */
        private const val KEYSTORE_SPEC = "KeyGenParameterSpec"

        /** Path fragments that mark a test source set. */
        private val TEST_SOURCE_MARKERS = setOf("/src/test/", "/src/androidTest/", "/src/sharedTest/")

        /** The build-blocking issue, registered by [CfoIssueRegistry]. */
        @JvmField
        val ISSUE: Issue =
            Issue.create(
                id = "CfoHandRolledCrypto",
                briefDescription = "Cryptography built by hand instead of via Tink (SEC-003)",
                explanation =
                    """
                    SEC-003 requires every cryptographic operation to go through Google Tink or the \
                    Android Keystore. Hand-rolled cryptography fails silently — a reused nonce, an \
                    unauthenticated mode, or a digest standing in for a key derivation produces \
                    output that looks correct and passes every test while being broken. No later \
                    test catches that, so the check has to be here.

                    Use Tink's `Aead` for encryption, the Keystore-backed `Mac` for tags, and \
                    `BackupCipher` for the encrypted backup. Store keys in the Android Keystore.

                    Generating a key *into* the Keystore with a `KeyGenParameterSpec` is allowed and \
                    is not hand-rolled cryptography (ADR-0057): the platform creates and holds the \
                    key, exactly as it does when Tink asks it to. A `KeyGenerator` with no such spec \
                    is not allowed, because the key it makes is exportable and lives in memory.

                    Test sources are exempt: implementing a primitive as an independent oracle to \
                    check Tink's output against is good practice, not a violation.
                    """.trimIndent(),
                category = Category.SECURITY,
                priority = 10,
                severity = Severity.ERROR,
                implementation =
                    Implementation(HandRolledCryptoDetector::class.java, Scope.JAVA_FILE_SCOPE),
            )
    }
}
