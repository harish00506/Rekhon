package com.aicfo.feature.dashboard

import com.aicfo.core.common.AppError
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The export/import flow's ordering (issue 5.4; §5.10, P-07).
 *
 * Why:  `ArchiveRepositoryTest` proves the round trip against real SQLite. What it cannot prove is
 *       the thing this feature could most damagingly get wrong: **picking a file must not import
 *       it**. Import replaces every row on the device and cannot be undone, so the gap between "the
 *       user chose a file" and "the app destroyed their data" is the whole safety of the feature —
 *       and it is one line in a `when` away from disappearing.
 *
 *       So these tests are about call counts and ordering, not about JSON.
 * What: the confirmation gate, cancellation, the success and failure states, and the export
 *       hand-off to the screen.
 * Result: a refactor that wires `ImportPicked` straight to the repository fails the build.
 * Changelog: 2026-08-16 — Created for issue 5.4.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DashboardArchiveTest {
    private val budget = FakeQuickSetupRepository()
    private val netWorth = FakeNetWorthRepository()
    private val transactions = FakeTransactionRepository()
    private val budgets = FakeBudgetRepository()
    private val safeToSpend = FakeSafeToSpendRepository()
    private val archives = FakeArchiveRepository()

    private fun viewModel() = DashboardViewModel(budget, netWorth, transactions, budgets, safeToSpend, archives)

    /** `viewModelScope` runs on `Dispatchers.Main`, which has no factory on a plain JVM. */
    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    /** Input: none. Output: restores the global Main dispatcher so tests stay isolated. */
    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    // --- the gate ---------------------------------------------------------------------------------

    /**
     * The assertion this file exists for.
     * Input:  a picked archive.
     * Output: asserts the repository was **not** called, and the confirmation is showing.
     *
     * Why:    the destructive operation must be reachable only from an explicit second tap. Wiring
     *         `ImportPicked` straight to the repository would look identical on screen right up to
     *         the moment somebody's data was gone.
     */
    @Test
    fun `picking a file does not import it`() =
        runTest {
            val viewModel = viewModel()

            viewModel.onEvent(DashboardEvent.ImportPicked(ARCHIVE))

            assertEquals("picking a file must not import it", 0, archives.importCount)
            assertTrue(viewModel.uiState.value.archive is ArchiveUiState.PendingImport)
        }

    /**
     * Input:  a picked archive, then a cancellation.
     * Output: asserts nothing was imported and the screen is back at rest.
     */
    @Test
    fun `cancelling an import changes nothing`() =
        runTest {
            val viewModel = viewModel()
            viewModel.onEvent(DashboardEvent.ImportPicked(ARCHIVE))

            viewModel.onEvent(DashboardEvent.ImportCancelled)

            assertEquals(0, archives.importCount)
            assertEquals(ArchiveUiState.Idle, viewModel.uiState.value.archive)
        }

    /**
     * Input:  a picked archive, then a confirmation.
     * Output: asserts it was imported **once**, with the file that was picked.
     */
    @Test
    fun `confirming imports the picked archive exactly once`() =
        runTest {
            val viewModel = viewModel()
            viewModel.onEvent(DashboardEvent.ImportPicked(ARCHIVE))

            viewModel.onEvent(DashboardEvent.ImportConfirmed)

            assertEquals(1, archives.importCount)
            assertEquals(ARCHIVE, archives.importedJson)
            assertEquals(ArchiveUiState.Imported(rows = 42, exportedAtUtcMillis = 1L), viewModel.uiState.value.archive)
        }

    /**
     * Input:  a confirmation with nothing pending — a stray event, or a second tap on a dialog that
     *         has already been answered.
     * Output: asserts nothing is imported.
     *
     * Why:    the guard is `as? PendingImport ?: return`, and without it a re-delivered event after
     *         a configuration change would replay the destruction against whatever was last picked.
     */
    @Test
    fun `confirming with nothing pending imports nothing`() =
        runTest {
            val viewModel = viewModel()

            viewModel.onEvent(DashboardEvent.ImportConfirmed)

            assertEquals(0, archives.importCount)
        }

    /**
     * Input:  an archive the repository refuses for a specific reason.
     * Output: asserts the **reason** reaches the screen, not just the fact of failure.
     *
     * Why:    this test used to assert `is Failed` and nothing more, and it passed for a build in
     *         which every refusal showed the generic "something went wrong" — because
     *         `AppError.Validation.code` is the constant "validation", so the two archive-specific
     *         messages were unreachable. A device run found it. Asserting the code is what would
     *         have found it here, and is what stops it coming back.
     */
    @Test
    fun `a refused archive surfaces the reason it was refused`() =
        runTest {
            archives.failImport(field = "archive.schemaVersion")
            val viewModel = viewModel()
            viewModel.onEvent(DashboardEvent.ImportPicked(ARCHIVE))

            viewModel.onEvent(DashboardEvent.ImportConfirmed)

            assertEquals(ArchiveUiState.Failed("archive.schemaVersion"), viewModel.uiState.value.archive)
        }

    /**
     * Input:  a file that is not an archive at all.
     * Output: asserts the *other* archive code arrives — the user is told they picked the wrong
     *         file, which is a different problem with a different remedy from a version mismatch.
     */
    @Test
    fun `an unreadable file is reported as the wrong file`() =
        runTest {
            archives.failImport(field = "archive.unreadable")
            val viewModel = viewModel()
            viewModel.onEvent(DashboardEvent.ImportPicked(ARCHIVE))

            viewModel.onEvent(DashboardEvent.ImportConfirmed)

            assertEquals(ArchiveUiState.Failed("archive.unreadable"), viewModel.uiState.value.archive)
        }

    // --- export ------------------------------------------------------------------------------------

    /**
     * Input:  an export request.
     * Output: asserts the JSON comes back as state for the **screen** to write.
     *
     * Why:    writing needs a `Uri` and a `ContentResolver`, which are Android types a ViewModel must
     *         not hold (ARC-004). The hand-off is the design, so it is asserted rather than assumed.
     */
    @Test
    fun `export hands the archive to the screen to write`() =
        runTest {
            val viewModel = viewModel()

            viewModel.onEvent(DashboardEvent.ExportRequested)

            assertEquals(
                ArchiveUiState.ReadyToWrite(FakeArchiveRepository.ARCHIVE_JSON),
                viewModel.uiState.value.archive,
            )
        }

    /**
     * Input:  the screen reporting a successful write.
     * Output: asserts the user is told.
     */
    @Test
    fun `a written export is confirmed to the user`() =
        runTest {
            val viewModel = viewModel()

            viewModel.onEvent(DashboardEvent.ExportWritten(written = true))

            assertEquals(ArchiveUiState.Exported, viewModel.uiState.value.archive)
        }

    /**
     * Input:  the screen reporting a failed write.
     * Output: asserts that failure is distinguishable from an export that never ran — the user
     *         picked a location the app could not write to, and needs to pick another.
     */
    @Test
    fun `a failed write is reported, not swallowed`() =
        runTest {
            val viewModel = viewModel()

            viewModel.onEvent(DashboardEvent.ExportWritten(written = false))

            assertEquals(ArchiveUiState.Failed("archive.writeFailed"), viewModel.uiState.value.archive)
        }

    /**
     * Input:  a repository that cannot read the database.
     * Output: asserts the export failure reaches the screen rather than opening a file picker for a
     *         file that does not exist.
     */
    @Test
    fun `a failed export never reaches the file picker`() =
        runTest {
            archives.failExport()
            val viewModel = viewModel()

            viewModel.onEvent(DashboardEvent.ExportRequested)

            assertEquals(ArchiveUiState.Failed(AppError.Storage("disk").code), viewModel.uiState.value.archive)
        }

    private companion object {
        /** Stands in for a picked file; its contents are `ArchiveRepositoryTest`'s concern. */
        const val ARCHIVE = "{\"archiveVersion\":1,\"schemaVersion\":15}"
    }
}
