package com.aicfo.app.work

import com.aicfo.core.common.AppError
import com.aicfo.core.common.Err
import com.aicfo.core.common.Ok
import com.aicfo.core.common.Result
import com.aicfo.data.repository.EraseRepository
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The scheduled work stops with the data (issue 11.4; §34, P-01).
 *
 * Why:  **found by running the erase on a device.** Nine periodic workers survive it — WorkManager
 *       keeps its own database outside everything the inventory covers — so a few minutes after a
 *       user erased everything, `MarketPriceWorker` wakes up and fetches prices, `SmsScanWorker`
 *       reads the inbox, and `WidgetRefreshWorker` re-populates the widget. Each would rebuild a
 *       database behind a new key and start filling it: the app would be *collecting data again*
 *       about a person who just asked it to stop, with no screen ever shown to them.
 *
 *       That is a privacy defect (P-01), not a tidiness one, so it is tested like one: the cancel
 *       must happen, it must happen **only** when the erase actually succeeded, and it must not be
 *       able to turn a successful erase into a reported failure.
 * What: the ordering, the failure path, and a cancel that itself fails.
 * Result: an erase that also stops the app from starting over behind the user's back.
 * Changelog: 2026-10-01 — Created for issue 11.4.
 */
class WorkCancellingEraseRepositoryTest {
    private val log = mutableListOf<String>()

    @Test
    fun `the work is cancelled after a successful erase`() =
        runTest {
            val outcome = repository(erase = Ok(Unit)).eraseEverything()

            assertEquals(Ok(Unit), outcome)
            // After, not before: work cancelled first and then a failed shred would leave a live
            // app with its background schedule silently gone.
            assertEquals(listOf("erase", "cancel"), log)
        }

    @Test
    fun `a failed erase leaves the schedule alone`() =
        runTest {
            // The data is still there, so the app is still the app. Cancelling its workers would
            // quietly stop budget alerts and price refreshes for a user who still has data and was
            // just told the erase did not work.
            val outcome = repository(erase = Err(AppError.Crypto("erase.key_survived"))).eraseEverything()

            assertTrue(outcome is Err)
            assertEquals(listOf("erase"), log)
        }

    @Test
    fun `a cancel that throws does not turn a successful erase into a failure`() =
        runTest {
            // The keys are already destroyed. Reporting an error now would tell a user whose data is
            // genuinely unrecoverable that the erase failed — the worst possible lie in both
            // directions at once.
            val outcome = repository(erase = Ok(Unit), cancelThrows = true).eraseEverything()

            assertEquals(Ok(Unit), outcome)
            assertEquals(listOf("erase", "cancel"), log)
        }

    private fun repository(
        erase: Result<Unit, AppError>,
        cancelThrows: Boolean = false,
    ): EraseRepository =
        WorkCancellingEraseRepository(
            delegate =
                object : EraseRepository {
                    override suspend fun eraseEverything(): Result<Unit, AppError> {
                        log += "erase"
                        return erase
                    }
                },
            cancelAllWork = {
                log += "cancel"
                check(!cancelThrows) { "WorkManager is not initialised" }
            },
        )
}
