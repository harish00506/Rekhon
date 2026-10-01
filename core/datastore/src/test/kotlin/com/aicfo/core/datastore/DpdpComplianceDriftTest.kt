package com.aicfo.core.datastore

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The compliance document is checked, not trusted (issue 11.5; §23, §32, DPDP).
 *
 * Why:  a traceability matrix is the easiest document in a repository to make quietly false. It
 *       claims "the right of erasure is implemented in `EraseRepository`, proven by
 *       `EraseDeviceTest`" — and the day somebody renames or deletes either, the claim stops being
 *       true and nothing anywhere notices. Compliance documents do not fail CI; that is exactly why
 *       they drift, and a privacy claim that has silently become false is worse than no claim.
 *
 *       So the matrix is written to be machine-checkable and this test checks it: every class and
 *       test it names must still exist, and every consent the app can hold must have a row. A fifth
 *       `ConsentFeature` added without a declared purpose fails the build here rather than shipping
 *       with an undocumented data flow — which is DPDP §4's purpose limitation enforced by a test
 *       instead of by someone remembering.
 * What: the file exists, every `ConsentFeature.id` has a row, and every cited source file is real.
 * Result: a compliance claim that cannot rot without the build going red.
 * Changelog: 2026-10-01 — Created for issue 11.5.
 *
 * **This test reads the repository, so it must actually run.** This project has already shipped one
 * drift test that Gradle held up to date forever and one pair held up to date by a stale task, so
 * `theDocumentIsFound` asserts the lookup itself rather than letting a missing file turn every
 * other assertion here into a vacuous pass.
 */
class DpdpComplianceDriftTest {
    private val document: String by lazy { documentFile().readText() }

    @Test
    fun `the document is found, so the rest of this suite is not asserting about an empty string`() {
        // The guard that keeps every other test here honest. Without it, a moved file would make
        // `contains` checks fail loudly — or worse, a refactor to a lenient lookup would make them
        // all pass against "".
        assertTrue("the compliance document is empty or missing", document.length > MIN_LENGTH)
    }

    @Test
    fun `every consent the app can hold has a row, so no data flow is undocumented`() {
        // DPDP §4's purpose limitation, enforced rather than remembered. A consent that reaches a
        // user without a declared purpose is the failure this is here to prevent.
        ConsentFeature.entries.forEach { feature ->
            assertTrue(
                "${feature.id} has no row in the DPDP purpose-limitation table",
                document.contains("`${feature.id}`"),
            )
        }
    }

    @Test
    fun `the document names no consent that does not exist`() {
        // The other direction, and the one that makes the table a map rather than a wish list: a
        // row for a consent that was removed would describe a data flow the app no longer has.
        CONSENT_ROW.findAll(document).map { it.groupValues[1] }.toSet().forEach { id ->
            assertTrue(
                "the document has a row for `$id`, which is not a ConsentFeature",
                ConsentFeature.entries.any { it.id == id },
            )
        }
    }

    @Test
    fun `every class and test the document cites still exists`() {
        // The claim "erasure is implemented in EraseRepository, proven by EraseDeviceTest" is only
        // worth writing if it stops being writable when either disappears. Resolved by file name
        // across the whole repository rather than by path, so moving a class between modules — which
        // is allowed — does not fail this, while deleting or renaming one does.
        val sources = sourceFileNames()
        CITED.forEach { name ->
            assertTrue("the document cites $name, which no longer exists", sources.contains("$name.kt"))
        }
    }

    @Test
    fun `the open obligations are still listed as open`() {
        // The honest half of the document, and the half most likely to be edited away. These four
        // cannot be satisfied by code in this repository, and a matrix that quietly dropped them
        // would read as full compliance.
        listOf("Grievance redressal", "Nomination", "Breach notification", "Children's data")
            .forEach { obligation ->
                assertTrue(
                    "$obligation is no longer listed among the open obligations",
                    document.contains(obligation),
                )
            }
    }

    /**
     * Result: the compliance document. Input: none. Output: [File].
     * Why:    walks up from the test's working directory, which differs per module, rather than
     *         assuming one — the same lookup `ClassificationKbDriftTest` uses. Errors with the path
     *         it searched for, so a move is a readable failure rather than a bare exception.
     * Changelog: 2026-10-01 — Created for issue 11.5.
     */
    private fun documentFile(): File {
        var directory: File? = File("").absoluteFile
        while (directory != null) {
            val candidate = File(directory, DOC_PATH)
            if (candidate.isFile) return candidate
            directory = directory.parentFile
        }
        error("Could not find $DOC_PATH walking up from ${File("").absolutePath}")
    }

    /**
     * Result: the file name of every Kotlin source in the repository. Input: none. Output: a [Set].
     * Why:    by name rather than by path on purpose. The document cites classes, not locations, and
     *         this project moves classes between modules deliberately (issue 11.4 moved three sets
     *         of constants). Citing a path would make the matrix fail on a legitimate refactor and
     *         teach everyone to delete the assertion.
     * Changelog: 2026-10-01 — Created for issue 11.5.
     */
    private fun sourceFileNames(): Set<String> {
        var directory: File? = File("").absoluteFile
        while (directory != null) {
            if (File(directory, "settings.gradle.kts").isFile) {
                return directory
                    .walkTopDown()
                    .onEnter { it.name != "build" && it.name != ".git" }
                    .filter { it.isFile && it.extension == "kt" }
                    .map { it.name }
                    .toSet()
            }
            directory = directory.parentFile
        }
        error("Could not find the repository root walking up from ${File("").absolutePath}")
    }

    private companion object {
        const val DOC_PATH = "docs/compliance/dpdp-2023.md"

        /** Short enough that a stub would fail, long enough not to be brittle about edits. */
        const val MIN_LENGTH = 2_000

        /** A row's consent id, as the purpose table writes it: a backticked snake_case id. */
        val CONSENT_ROW = Regex("""\| `([a-z_]+)` \|""")

        /**
         * The classes and tests the document names as evidence.
         *
         * Why a literal list rather than parsing every backticked token: the document also mentions
         * resource ids, lint rules and method names, and a parser clever enough to tell those apart
         * would be the kind of cleverness that silently stops matching. This list is short, it is
         * reviewed with the document, and a name added to the matrix without being added here is
         * simply unchecked — never falsely checked.
         */
        val CITED =
            listOf(
                "ArchiveRepository",
                "Archive",
                "ArchiveConsentRecordTest",
                "ArchiveRepositoryTest",
                "ArchiveFormatTest",
                "TransactionRepository",
                "AccountRepository",
                "TransactionRepositoryTest",
                "EraseRepository",
                "AndroidSecureEraser",
                "EraseRepositoryTest",
                "AndroidSecureEraserTest",
                "EraseDeviceTest",
                "ConsentsViewModel",
                "ConsentsViewModelTest",
                "ConsentsScreen",
                "SmsRepository",
                "SmsConsentWatcher",
                "SmsRepositoryTest",
                "MarketPriceRepository",
                "BackupRepository",
                "BackupRepositoryTest",
                "UnconfiguredMarketDataApi",
            )
    }
}
