package com.aicfo.data.repository

import com.aicfo.core.common.AppError
import com.aicfo.core.common.Err
import com.aicfo.core.common.Ok
import com.aicfo.core.common.Result
import com.aicfo.core.common.TestDispatchers
import com.aicfo.core.model.AuditEvent
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Erasing everything, in the only order that is safe to be interrupted (issue 11.4; §23, §34, SEC-003).
 *
 * Why:  "erase my data" has one failure mode that matters, and it is not a file left behind — it is
 *       a **readable** file left behind. The data is encrypted at rest, so destroying the Keystore
 *       key is what makes it unrecoverable; deleting the files is tidying up afterwards. If the
 *       process dies in the middle, the order decides what survives: keys first leaves ciphertext
 *       nobody can read, files first leaves a live key and whatever the delete had not reached yet.
 *
 *       So these tests are about **sequence and honesty**: the shred before the sweep, a failure to
 *       shred reported rather than swallowed, a failure to delete tolerated rather than fatal, and
 *       an audit record that says it happened without saying anything about whom.
 * What: the ordering, both failure paths, and the audit event.
 * Result: an erase that is irreversible by design and truthful about whether it worked.
 * Changelog: 2026-10-01 — Created for issue 11.4.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class EraseRepositoryTest {
    private val dispatcher = UnconfinedTestDispatcher()

    /**
     * One log, shared. An earlier version of this suite gave each fake its own list and asserted
     * `eraser.calls + audit.events.map { "audit" }` — which puts "audit" last by construction and
     * so could not fail however the implementation was reordered. A mutation that recorded the
     * event *before* the shred survived it. Everything appends here instead, in real order.
     */
    private val log = mutableListOf<String>()
    private val eraser = RecordingEraser(log)
    private val audit = RecordingAuditLog(log)

    @Test
    fun `the keys are destroyed before a single file is deleted`() =
        runTest(dispatcher) {
            // The whole design in one assertion. Reverse these and an interrupted erase leaves a
            // live key beside a partly-deleted database — which is to say, readable finances.
            repository().eraseEverything()

            assertEquals(listOf("keys", "files", "audit"), log)
        }

    @Test
    fun `a shred that fails is an error, and nothing is deleted after it`() =
        runTest(dispatcher) {
            // If the key survives, deleting files is worse than useless: it destroys the user's data
            // while leaving it theoretically recoverable, and reports success for both.
            eraser.failKeys = AppError.Crypto("KeyStoreException")

            val outcome = repository().eraseEverything()

            assertTrue("a failed shred must not report success", outcome is Err)
            assertEquals(listOf("keys"), log)
            assertTrue("nothing may be recorded as erased", audit.events.isEmpty())
        }

    @Test
    fun `a file that will not delete does not fail the erase, because the key is already gone`() =
        runTest(dispatcher) {
            // The opposite trade. Once the key is destroyed the leftovers are ciphertext with no
            // key in the world, so refusing to finish would turn a successful erase into a scary
            // error message about data that is already unreadable.
            eraser.failFiles = AppError.Storage("IOException")

            val outcome = repository().eraseEverything()

            assertTrue(outcome is Ok)
            assertEquals(listOf("keys", "files", "audit"), log)
        }

    @Test
    fun `the erase is recorded, and the record says nothing about the person`() =
        runTest(dispatcher) {
            repository().eraseEverything()

            assertEquals(listOf(AuditEvent.DATA_ERASED), audit.events)
            // No method, because none applies — and no free text, because `AuditLogEntity` has no
            // column anyone could put a name or a balance in. The absence is the design (P-01).
            assertTrue("an erase record names no factor", audit.methods.all { it == null })
        }

    @Test
    fun `the record is written after the shred, never before`() =
        runTest(dispatcher) {
            // Written first, it would be destroyed by the very erase it describes — and if the shred
            // then failed, the app would hold a log saying it had erased data it still has.
            repository().eraseEverything()

            assertEquals(listOf("keys", "files", "audit"), log)
        }

    private fun repository(): EraseRepository =
        DefaultEraseRepository(
            eraser = eraser,
            auditLog = audit,
            dispatchers = TestDispatchers(dispatcher),
        )
}

/**
 * An eraser that appends to the shared log as it is called (issue 11.4).
 * Why:    the ordering is the subject of this suite and it is invisible in the result — both calls
 *         return `Ok`. Appending to a log both fakes share is the only way to assert "before".
 * Result: "keys" and "files" land in [log] in the order they happened.
 * Changelog: 2026-10-01 — Created for issue 11.4.
 *
 * Input:  [log] — the shared sequence. Output: the fake.
 */
private class RecordingEraser(
    private val log: MutableList<String>,
) : SecureEraser {
    var failKeys: AppError? = null
    var failFiles: AppError? = null

    override suspend fun destroyKeys(): Result<Unit, AppError> {
        log += "keys"
        return failKeys?.let { Err(it) } ?: Ok(Unit)
    }

    override suspend fun deleteDataFiles(): Result<Unit, AppError> {
        log += "files"
        return failFiles?.let { Err(it) } ?: Ok(Unit)
    }
}

/**
 * An audit log that keeps what it was told, so the test can check what it was *not* told.
 * Input:  [log] — the shared sequence, so "recorded after the shred" is checkable.
 * Changelog: 2026-10-01 — Created for issue 11.4.
 */
private class RecordingAuditLog(
    private val log: MutableList<String>,
) : AuditLogRepository {
    val events = mutableListOf<AuditEvent>()
    val methods = mutableListOf<com.aicfo.core.model.AuditMethod?>()

    override suspend fun record(
        event: AuditEvent,
        method: com.aicfo.core.model.AuditMethod?,
    ): Result<Unit, AppError> =
        Ok(Unit).also {
            log += "audit"
            events += event
            methods += method
        }

    override fun observeRecent(limit: Int) = kotlinx.coroutines.flow.flowOf(emptyList<AuditEntry>())

    override suspend fun countSince(
        event: AuditEvent,
        sinceUtcMillis: Long,
    ): Result<Int, AppError> = Ok(events.count { it == event })
}
