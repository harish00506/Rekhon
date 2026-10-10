package com.aicfo.data.repository

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

/**
 * That **both** profile wipes call every delete the DAO offers (issues 2.4, 5.4; ADR-0006, P-01).
 *
 * Why:  `ArchiveRepository.wipe`'s own documentation says it reuses `DemoDao` because *"a second
 *       wipe written here would be one that drifts from it, and the table it forgot would be a row
 *       the restore silently kept from the old data — a merge nobody asked for, hiding inside a
 *       replace."*
 *
 *       It reuses the DAO. It does **not** reuse the call list, and the call list is the part that
 *       drifts — so the paragraph describes a safety the code never had. By the time
 *       `WipeCoverageTest` was written it had drifted by eleven tables: purchase traces, the
 *       interview answers, the buy list, all four vehicle tables, the cached closes, the chat
 *       history and the appliances. Restoring an archive kept every one of them from whoever owned
 *       the device before, inside an operation the user asked to *replace* their data.
 *
 *       `WipeCoverageTest` in `:core:database` guards the DAO's own surface — that every scoped
 *       table has a delete and a term in the residue count. This guards the two callers, which is
 *       the half that was actually wrong.
 * What: that `DemoModeRepository.exit` and `ArchiveRepository.wipe` each call every `delete*`
 *       function `DemoDao` declares.
 * Result: adding a delete to the DAO without calling it from both wipes fails the build.
 * Changelog: 2026-10-10 — Created while adding `import_batches` (ADR-0078).
 *
 * Reads `Daos.kt` from `:core:database`, declared as a test input in this module's build file —
 * without that the task goes UP-TO-DATE on exactly the edit it watches (issues 7.2, 11.5, 11.7,
 * 13.1, 13.5).
 */
class ProfileWipeCallSitesTest {
    /**
     * Input:  `DemoDao`'s declarations and `DemoModeRepository`'s source.
     * Output: asserts the demo exit calls every one of them.
     *
     * A delete the exit never calls is the residue ADR-0006 forbids: a sample household's rows left
     * under a profile that no longer exists, which no later query can reach to clean up.
     */
    @Test
    fun `the demo exit calls every delete the DAO declares`() {
        assertEquals(
            "DemoDao declares these deletes and DemoModeRepository.exit never calls them, so " +
                "exiting the demo leaves those tables behind",
            emptySet<String>(),
            daoDeletes() - callsIn(DEMO_REPOSITORY_PATH),
        )
    }

    /**
     * Input:  `DemoDao`'s declarations and `ArchiveRepository`'s source.
     * Output: asserts the restore's wipe calls every one of them.
     *
     * This is the half that was wrong. A delete the restore never calls does not merely leave
     * residue — it turns "replace my data with this archive" into "merge this archive into whatever
     * was here", silently, for the tables nobody remembered to list.
     */
    @Test
    fun `the restore wipe calls every delete the DAO declares`() {
        assertEquals(
            "DemoDao declares these deletes and ArchiveRepository.wipe never calls them, so a " +
                "restore keeps the previous profile's rows in those tables",
            emptySet<String>(),
            daoDeletes() - callsIn(ARCHIVE_REPOSITORY_PATH),
        )
    }

    /**
     * Input:  `DemoDao`'s declarations and both call sites.
     * Output: asserts `attachmentFileNames` still has **no caller** — and fails when one appears.
     *
     * An open finding, pinned in the direction issue 13.5 established, because it is a real gap
     * this change could not responsibly close on its way past.
     *
     * The query exists and its own documentation states the contract: *"read before
     * `deleteAttachments`, because after it there is nothing left to say which files on disk
     * belonged to this profile — and an orphaned ciphertext blob is data a 'delete everything' did
     * not delete (P-01)."* **Nothing has ever called it.** Both wipes delete the attachment rows
     * and leave the encrypted receipt images in `filesDir/receipts`, where they stay until a full
     * device erase — which does remove the directory wholesale (`CryptoSecrets.filePaths`), so the
     * leak is bounded to the demo exit and the restore rather than unbounded.
     *
     * It is not fixed here because erasing files is a destructive path that needs its own issue, a
     * collaborator neither repository currently holds for this purpose, and an instrumented test on
     * a real filesystem. **This test fails the moment a caller appears**, which is when ADR-0078's
     * open finding becomes stale and should be deleted.
     */
    @Test
    fun `the orphaned-blob gap is still open, and this fails when it is closed`() {
        // A *call*, not a mention: both files now name the query in a comment explaining why
        // nothing calls it, and matching the bare name would make this test fail on its own
        // documentation.
        val callers =
            listOf(DEMO_REPOSITORY_PATH, ARCHIVE_REPOSITORY_PATH).filter { path ->
                BLOB_LISTING_CALL.containsMatchIn(sourceOf(path))
            }

        assertEquals(
            "`$BLOB_LISTING` now has a caller — the orphaned receipt blobs ADR-0078 recorded as an " +
                "open finding are presumably being erased. Delete that finding and this test",
            emptyList<String>(),
            callers,
        )
    }

    // --- parsing ------------------------------------------------------------------------------------

    /**
     * Every `delete*` function `DemoDao` declares, excluding the profile row itself.
     *
     * Bounded to the interface: the DAO file holds forty of them, and several others declare a
     * `delete*` of their own that these wipes have no business calling.
     */
    private fun daoDeletes(): Set<String> {
        val source = sourceOf(DAOS_PATH)
        val start = source.indexOf(WIPE_DAO)
        check(start >= 0) { "$WIPE_DAO is gone — the wipe this test guards no longer exists" }
        val end = source.indexOf("\ninterface ", start)
        val body = if (end < 0) source.substring(start) else source.substring(start, end)
        return DELETE_FUN.findAll(body).map { it.groupValues[1] }.toSet() - DELETE_PROFILE
    }

    /** The `demo.deleteX(…)` / `deleteX(…)` names a call site mentions. */
    private fun callsIn(path: String): Set<String> =
        DELETE_CALL.findAll(sourceOf(path)).map { it.groupValues[1] }.toSet()

    private fun sourceOf(path: String): String {
        var directory: File? = File("").absoluteFile
        while (directory != null) {
            val candidate = File(directory, path)
            if (candidate.isFile) return candidate.readText()
            directory = directory.parentFile
        }
        error("Could not find $path walking up from ${File("").absolutePath}")
    }

    private companion object {
        const val DAOS_PATH = "core/database/src/main/kotlin/com/aicfo/core/database/dao/Daos.kt"
        const val DEMO_REPOSITORY_PATH =
            "data/repository/src/main/kotlin/com/aicfo/data/repository/DemoModeRepository.kt"
        const val ARCHIVE_REPOSITORY_PATH =
            "data/repository/src/main/kotlin/com/aicfo/data/repository/ArchiveRepository.kt"

        const val WIPE_DAO = "interface DemoDao {"

        /** Called last by both wipes, and not a table — asserted by its own callers, not here. */
        const val DELETE_PROFILE = "deleteProfile"

        /** The listing whose absent caller is the open finding above. */
        const val BLOB_LISTING = "attachmentFileNames"
        val BLOB_LISTING_CALL = Regex("""\.$BLOB_LISTING\(""")

        val DELETE_FUN = Regex("""fun (delete\w+)\(""")
        val DELETE_CALL = Regex("""\.(delete\w+)\(""")
    }
}
