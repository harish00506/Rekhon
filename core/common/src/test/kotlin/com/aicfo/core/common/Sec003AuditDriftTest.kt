package com.aicfo.core.common

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The crypto audit's inventory is checked against the repository (issue 11.7; SEC-003).
 *
 * Why:  `CfoHandRolledCrypto` stops a *banned* primitive being used. It cannot tell you that a new
 *       file has started doing something sanctioned-but-cryptographic — a new Tink keyset, another
 *       Keystore alias, a second `SecureRandom` — and the audit document's §2 table claims to list
 *       every such site. That claim is exactly the kind that decays: somebody adds a keyset in six
 *       months, the table silently becomes incomplete, and the next reader trusts it.
 *
 *       So the table is checked. Every production file that touches cryptography must appear in it,
 *       and every file it names must still exist.
 * What: the file inventory in both directions, and the sign-off section still being present.
 * Result: a security audit that cannot quietly go out of date.
 * Changelog: 2026-10-02 — Created for issue 11.7.
 *
 * **Lives in `:core:common` because it needs a pure-JVM test task and no Android.** It reads the
 * repository rather than this module, which is the same shape as `ClassificationKbDriftTest` and
 * `DpdpComplianceDriftTest` — and, like them, it only runs because issue 11.5 declared the document
 * directories as test inputs. `docs/security/` was added to that list for this test; without it
 * Gradle would hold the task up to date on an edit to the audit and this suite would never see it.
 */
class Sec003AuditDriftTest {
    private val repositoryRoot: File by lazy { findRepositoryRoot() }
    private val audit: String by lazy { File(repositoryRoot, AUDIT_PATH).readText() }

    @Test
    fun `the audit document is found, so the rest of this suite is not asserting about an empty string`() {
        assertTrue("the audit document is empty or missing", audit.length > MIN_LENGTH)
    }

    @Test
    fun `every production file that touches cryptography is listed in the audit`() {
        // The direction that matters. A new Tink keyset or Keystore alias that nobody added to the
        // table makes the audit an incomplete account of the app's cryptography — which is worse
        // than no audit, because it reads as complete.
        val missing = cryptoTouchingFiles().filterNot { audit.contains(it.name) }

        assertEquals(
            "these production files touch cryptography but are not in docs/security/sec-003-crypto-audit.md: " +
                missing.joinToString { it.path },
            emptyList<String>(),
            missing.map { it.path },
        )
    }

    @Test
    fun `the audit names no file that has been deleted or renamed`() {
        // The other direction: a row for a file that no longer exists describes cryptography the app
        // no longer has, and sends the next reader looking for it.
        val present = cryptoTouchingFiles().map { it.name }.toSet()
        val claimed = AUDITED_FILE.findAll(audit).map { it.groupValues[1] }.toSet()
        val stale = claimed - present

        assertEquals(
            "the audit lists files that no longer touch crypto (or no longer exist): $stale",
            emptySet<String>(),
            stale,
        )
    }

    @Test
    fun `the sign-off and the deviation are still recorded`() {
        // The two sections most likely to be edited away, and the two that make this a security
        // record rather than a file list: who signed it off, and the one place SEC-003 is stretched.
        listOf("Sign-off", "ADR-0039", "Argon2id", "no hand-rolled cryptography").forEach { required ->
            assertTrue("the audit no longer mentions \"$required\"", audit.contains(required))
        }
    }

    /**
     * Every production Kotlin file that touches cryptography.
     * Why:    one definition of "touches cryptography", used by both directions of the check so they
     *         cannot disagree. Import-based rather than call-based: an import is what a reviewer
     *         greps for, and a file that imports a crypto type but no longer uses one still belongs
     *         in the audit until the import goes.
     * Result: the files, sorted. `src/main` only — a test may use a primitive as an independent
     *         oracle, which the audit discusses rather than lists.
     * Input:  none. Output: a list of [File].
     * Changelog: 2026-10-02 — Created for issue 11.7.
     */
    private fun cryptoTouchingFiles(): List<File> =
        repositoryRoot
            .walkTopDown()
            .onEnter { it.name != "build" && it.name != ".git" && it.name != ".gradle" }
            .filter { it.isFile && it.extension == "kt" && "/src/main/" in it.invariantSeparatorsPath }
            .filter { file -> CRYPTO_MARKERS.any { marker -> file.readText().contains(marker) } }
            .sortedBy { it.path }
            .toList()

    /**
     * Result: the repository root. Input: none. Output: [File].
     * Why:    the test's working directory is the module's, which differs per module; walking up to
     *         `settings.gradle.kts` is how the other drift tests here locate the repository.
     * Changelog: 2026-10-02 — Created for issue 11.7.
     */
    private fun findRepositoryRoot(): File {
        var directory: File? = File("").absoluteFile
        while (directory != null) {
            if (File(directory, "settings.gradle.kts").isFile) return directory
            directory = directory.parentFile
        }
        error("Could not find the repository root walking up from ${File("").absolutePath}")
    }

    private companion object {
        const val AUDIT_PATH = "docs/security/sec-003-crypto-audit.md"

        /** Short enough that a stub fails, long enough not to be brittle about edits. */
        const val MIN_LENGTH = 2_000

        /**
         * Imports that mean a file touches cryptography.
         *
         * `java.security.GeneralSecurityException` is **excluded** on purpose: it is an exception
         * type, caught in places that do no cryptography at all (`AppError` maps it to a code), and
         * including it would pull unrelated files into a security audit and train people to pad the
         * table. The markers here are the ones that mean a key, a cipher or a random source.
         */
        val CRYPTO_MARKERS =
            listOf(
                "javax.crypto",
                "java.security.KeyStore",
                "java.security.SecureRandom",
                "java.security.MessageDigest",
                "android.security.keystore",
                "com.google.crypto.tink",
                "org.bouncycastle",
            )

        /** A file name in the audit's inventory table, e.g. `` `core/crypto/BackupCipher.kt` ``. */
        val AUDITED_FILE = Regex("""\| `[^`]*?([A-Za-z0-9_]+\.kt)` \|""")
    }
}
