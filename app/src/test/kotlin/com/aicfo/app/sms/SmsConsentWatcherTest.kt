package com.aicfo.app.sms

import com.aicfo.core.common.AppError
import com.aicfo.core.common.Err
import com.aicfo.core.common.Ok
import com.aicfo.core.common.Result
import com.aicfo.core.datastore.ConsentFeature
import com.aicfo.core.datastore.ConsentState
import com.aicfo.core.datastore.ConsentStore
import com.aicfo.core.model.Money
import com.aicfo.core.model.Transaction
import com.aicfo.data.repository.SmsAccess
import com.aicfo.data.repository.SmsDraft
import com.aicfo.data.repository.SmsRepository
import com.aicfo.data.repository.TransactionDraft
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate
import javax.inject.Provider

/**
 * Proves revoking the SMS consent actually erases what was inferred (issue 3.9; P-01).
 *
 * Why:  `SmsRepository.onConsentRevoked()` shipped with no caller, which made "revocable" a
 *       half-promise — reading stopped, but the drafts already parsed stayed on disk. This watcher
 *       is what closes that, and these tests are what stop it silently regressing into a no-op.
 *
 *       Three of the five are about **not** purging, and they matter more than the one that does.
 *       A purge on the wrong emission would delete the user's pending drafts because a DataStore
 *       read hiccuped, or would issue a delete on every cold start of an app whose owner has never
 *       opted in — before unlock, where the database provider throws by design (SEC-002).
 * What: the granted → revoked transition, and the four emissions that must not fire it.
 * Result: the gap ADR-0013 recorded is closed and stays closed.
 * Changelog: 2026-08-07 — Created for issue 3.9.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SmsConsentWatcherTest {
    private val consents = FakeConsentStore()
    private val repository = RecordingSmsRepository()

    @Test
    fun `revoking a granted consent erases the pending drafts`() =
        runTest {
            consents.set(granted = true)
            watch()

            consents.set(granted = false)

            assertEquals("revocable must mean the inference goes", 1, repository.revocations)
        }

    @Test
    fun `a consent that was never granted purges nothing`() =
        runTest {
            consents.set(granted = false)

            watch()

            // The overwhelming majority of launches. A delete here would run on every cold start,
            // and would run before the app is unlocked (SEC-002).
            assertEquals(0, repository.revocations)
        }

    @Test
    fun `starting up with the consent already on purges nothing`() =
        runTest {
            consents.set(granted = true)

            watch()

            // The first emission records the starting point; it is not a transition.
            assertEquals(0, repository.revocations)
        }

    @Test
    fun `an unreadable ledger is not a revocation`() =
        runTest {
            consents.set(granted = true)
            watch()

            consents.fail()

            // A transient read failure that read as "revoked" would delete the user's pending
            // drafts. Everywhere else in this feature a failure reads as "not granted"; here it
            // must read as "no news".
            assertEquals(0, repository.revocations)
        }

    @Test
    fun `re-granting after a revocation arms it again`() =
        runTest {
            consents.set(granted = true)
            watch()

            consents.set(granted = false)
            consents.set(granted = true)
            consents.set(granted = false)

            assertEquals(2, repository.revocations)
        }

    /** Result: a started watcher on this test's scheduler. Input: none. Output: none. */
    private fun TestScope.watch() {
        SmsConsentWatcher(
            consents = consents,
            repository = Provider { repository },
            scope = TestScope(UnconfinedTestDispatcher(testScheduler)),
        ).start()
    }
}

/**
 * A consent ledger a test can flip, and break (issue 3.9).
 * Why:    the watcher's whole job is reacting to *transitions*, so the fake has to be able to emit a
 *         failure as well as a value — the case where a naive implementation deletes data.
 * Result: a [ConsentStore] over a `MutableStateFlow`.
 * Changelog: 2026-08-07 — Created for issue 3.9.
 */
private class FakeConsentStore : ConsentStore {
    private val state = MutableStateFlow<Result<ConsentState, AppError>>(Ok(ConsentState.NOT_GRANTED))

    /** Result: emits a consent decision. Input: [granted]. Output: none. */
    fun set(granted: Boolean) {
        state.value = Ok(ConsentState(granted = granted))
    }

    /** Result: emits a read failure. Input: none. Output: none. */
    fun fail() {
        state.value = Err(AppError.Storage("FakeConsentStore"))
    }

    override fun observe(feature: ConsentFeature): Flow<Result<ConsentState, AppError>> = state

    override fun observeAll(): Flow<Result<Map<ConsentFeature, ConsentState>, AppError>> =
        state.map { current ->
            when (current) {
                is Ok -> Ok(ConsentFeature.entries.associateWith { current.value })
                is Err -> current
            }
        }

    override suspend fun grant(feature: ConsentFeature): Result<Unit, AppError> = Ok(Unit).also { set(true) }

    override suspend fun revoke(feature: ConsentFeature): Result<Unit, AppError> = Ok(Unit).also { set(false) }
}

/**
 * An [SmsRepository] that counts revocations and refuses everything else (issue 3.9).
 * Why:    the watcher must call exactly one method. Throwing from the rest means a watcher that
 *         started scanning, or reading drafts, on a consent change fails loudly rather than
 *         quietly doing something nobody asked for on a privacy path.
 * Result: a counter.
 * Changelog: 2026-08-07 — Created for issue 3.9.
 */
private class RecordingSmsRepository : SmsRepository {
    var revocations: Int = 0
        private set

    override suspend fun onConsentRevoked(): Result<Unit, AppError> {
        revocations++
        return Ok(Unit)
    }

    override fun observeAccess(): Flow<SmsAccess> = unexpected()

    override fun observePending(): Flow<List<SmsDraft>> = unexpected()

    override suspend fun scan(): Result<Int, AppError> = unexpected()

    override suspend fun accept(
        draftId: String,
        draft: TransactionDraft,
    ): Result<Transaction, AppError> = unexpected()

    override suspend fun dismiss(draftId: String): Result<Unit, AppError> = unexpected()

    override suspend fun findDuplicates(
        amount: Money,
        bookedOn: LocalDate,
    ): Result<List<Transaction>, AppError> = unexpected()

    /** Result: never returns. Input: none. Output: [Nothing]. */
    private fun unexpected(): Nothing = error("SmsConsentWatcher must only ever call onConsentRevoked()")
}
