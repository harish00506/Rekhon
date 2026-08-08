package com.aicfo.data.repository

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.aicfo.core.common.AppError
import com.aicfo.core.common.Err
import com.aicfo.core.common.FakeClock
import com.aicfo.core.common.FakeIdGenerator
import com.aicfo.core.common.Ok
import com.aicfo.core.common.Result
import com.aicfo.core.common.TestDispatchers
import com.aicfo.core.common.getOrNull
import com.aicfo.core.database.CfoDatabase
import com.aicfo.core.datastore.ConsentFeature
import com.aicfo.core.datastore.ConsentState
import com.aicfo.core.datastore.ConsentStore
import com.aicfo.core.model.AccountType
import com.aicfo.core.model.Money
import com.aicfo.core.model.SmsMessage
import com.aicfo.core.model.TransactionSource
import com.aicfo.data.sms.SmsInboxReader
import com.aicfo.domain.engines.sms.SmsDirection
import com.aicfo.domain.engines.sms.SmsEngineFactory
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.LocalDate
import java.time.ZoneId

/**
 * Tests for [SmsRepository] — the gate, the cursor and the decisions (issue 3.9; §18, §23, P-01).
 *
 * Why:  the parser's judgement is proven in its own module against fifty frozen fixtures. What is
 *       proven here is everything that module cannot see, and the first of them is the one the whole
 *       issue turns on: **with the consent off, the inbox is never touched**. That is asserted with
 *       a reader that throws if it is called at all, rather than by checking that the result was
 *       empty — an empty result is what a reader that *was* called and found nothing also produces,
 *       and the difference between those two is the entire privacy claim (P-01).
 *
 *       After that: that a scan writes no transaction (P-07), that the cursor advances only over
 *       messages actually processed, that a dismissal survives a re-scan, and that revoking the
 *       consent takes the pending inferences with it while leaving the ledger alone.
 * What: the gate, a scan end to end, the cursor, both decisions, the duplicate mirror, revocation.
 * Result: the storage half of issue 3.9 is proven against a real SQL engine.
 * Changelog: 2026-08-07 — Created for issue 3.9.
 *
 * Unencrypted in-memory Room, deliberately — the same reasoning as the suites beside it: what is
 * under test is this class's behaviour, not SQLCipher's.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class SmsRepositoryTest {
    private lateinit var database: CfoDatabase
    private lateinit var transactions: TransactionRepository
    private lateinit var accounts: AccountRepository
    private lateinit var repository: SmsRepository
    private val reader = FakeSmsInboxReader()
    private val consents = FakeConsentStore()
    private val settings = FakeSettingsStore()
    private val clock = FakeClock(initialMillis = NOW_MILLIS, initialZone = ZoneId.of("Asia/Kolkata"))
    private val activeProfileId = MutableStateFlow(PROFILE)

    /** Input: none. Output: a fresh database and a repository over it, consent and permission on. */
    @Before
    fun setUp() {
        database =
            Room.inMemoryDatabaseBuilder(
                ApplicationProvider.getApplicationContext(),
                CfoDatabase::class.java,
            ).allowMainThreadQueries().build()
        val dispatchers = TestDispatchers(UnconfinedTestDispatcher())
        val ids = FakeIdGenerator()
        transactions = RepositoryFactory.transactions(database, clock, ids, dispatchers, activeProfileId)
        accounts = RepositoryFactory.accounts(database, clock, ids, dispatchers, activeProfileId)
        consents.set(ConsentFeature.SMS_PARSING, granted = true)
        repository =
            RepositoryFactory.sms(
                database = database,
                transactions = transactions,
                reader = reader,
                engine = SmsEngineFactory.create(),
                consents = consents,
                settings = settings,
                clock = clock,
                ids = ids,
                dispatchers = dispatchers,
                activeProfileId = activeProfileId,
            )
    }

    /** Input: none. Output: closes the database between tests. */
    @After
    fun tearDown() {
        database.close()
    }

    // --- the gate (P-01) ------------------------------------------------------------------------

    @Test
    fun `with the consent off the inbox is never touched`() =
        runTest {
            consents.set(ConsentFeature.SMS_PARSING, granted = false)
            reader.messages = listOf(debitAlert(id = 1L))

            val scanned = repository.scan()

            // The reader throws if called. Asserting "zero drafts" instead would pass just as well
            // for a reader that *was* called and returned nothing — which is not the claim P-01
            // makes. This is the difference between "the feature is off" and "the feature found
            // nothing".
            assertEquals("a scan without consent is a no-op, not a failure", Ok(0), scanned)
            assertEquals("the inbox must not be read at all", 0, reader.callCount)
        }

    @Test
    fun `with the OS permission missing the inbox is never touched`() =
        runTest {
            // The mirror case, and it is not the same one: a user who granted the in-app consent and
            // then revoked READ_SMS from Settings has consented but cannot be read.
            reader.canRead = false
            reader.messages = listOf(debitAlert(id = 1L))

            assertEquals(Ok(0), repository.scan())
            assertEquals("the inbox must not be read at all", 0, reader.callCount)
        }

    @Test
    fun `the two permissions are reported apart`() =
        runTest {
            reader.canRead = false

            val access = repository.observeAccess().first()

            // The screen has to ask for the right thing: a missing consent is a switch to offer, a
            // missing permission is a system dialog to request.
            assertTrue(access.consentGranted)
            assertFalse(access.permissionGranted)
            assertFalse(access.canScan)
        }

    @Test
    fun `drafts are not read from disk while the consent is off`() =
        runTest {
            reader.messages = listOf(debitAlert(id = 1L))
            repository.scan()
            assertEquals(1, repository.observePending().first().size)

            consents.set(ConsentFeature.SMS_PARSING, granted = false)

            // A screen left open across a revocation empties without a reload.
            assertEquals(emptyList<SmsDraft>(), repository.observePending().first())
        }

    // --- the scan (P-07) ------------------------------------------------------------------------

    @Test
    fun `a scan records a proposal and writes no transaction`() =
        runTest {
            reader.messages = listOf(debitAlert(id = 71L))

            assertEquals(Ok(1), repository.scan())

            val draft = repository.observePending().first().single()
            assertEquals(Money(125_000L), draft.amount)
            assertEquals(SmsDirection.DEBIT, draft.direction)
            assertEquals("SWIGGY", draft.counterparty)
            assertEquals("VM-HDFCBK", draft.sender)
            assertEquals("P-07: a scan proposes; nothing is written", emptyList<Any>(), liveTransactions())
        }

    @Test
    fun `a message that is not a transaction records nothing`() =
        runTest {
            reader.messages = listOf(alert(id = 71L, body = "Your OTP for a txn of Rs 5,000 on A/c XX4521 is 448210."))

            assertEquals(Ok(0), repository.scan())
            assertEquals(emptyList<SmsDraft>(), repository.observePending().first())
        }

    @Test
    fun `the booking date comes from the profile zone, not from UTC`() =
        runTest {
            // 2026-08-07T20:00Z is already 2026-08-08 in Asia/Kolkata. A repository that formatted
            // the instant in UTC would file this purchase in the wrong day — and therefore, at a
            // month boundary, against the wrong budget.
            reader.messages = listOf(debitAlert(id = 71L, receivedAtUtcMillis = LATE_EVENING_UTC_MILLIS))

            repository.scan()

            assertEquals(LocalDate.of(2026, 8, 8), repository.observePending().first().single().bookedOn)
        }

    @Test
    fun `a low-confidence draft is flagged for the screen`() =
        runTest {
            // Two spendable figures: which one is "the" transaction is a guess, so the parser scores
            // it below the rulebook's floor and the repository resolves that into a flag.
            reader.messages =
                listOf(
                    alert(
                        id = 71L,
                        body = "Rs.500.00 debited from A/c XX4521 to ACME. Fee Rs.10.00 applied. Bal Rs.4,490.00",
                    ),
                )

            repository.scan()

            assertTrue(repository.observePending().first().single().isLowConfidence)
        }

    // --- the cursor -----------------------------------------------------------------------------

    @Test
    fun `the cursor advances to the last message processed`() =
        runTest {
            reader.messages = listOf(debitAlert(id = 71L), debitAlert(id = 72L), debitAlert(id = 73L))

            repository.scan()

            assertEquals(listOf(73L), settings.smsCursorWrites)
        }

    @Test
    fun `the next scan resumes from the cursor`() =
        runTest {
            reader.messages = listOf(debitAlert(id = 71L))
            repository.scan()

            reader.messages = emptyList()
            repository.scan()

            assertEquals("the second read must start after the first batch", 71L, reader.lastAfterId)
        }

    @Test
    fun `a failed read leaves the cursor where it was`() =
        runTest {
            reader.failWith = AppError.Unexpected("SecurityException")

            val result = repository.scan()

            assertTrue(result is Err)
            // The messages it never handled must be picked up next time rather than skipped for ever.
            assertEquals(emptyList<Long>(), settings.smsCursorWrites)
        }

    @Test
    fun `an empty batch does not move the cursor`() =
        runTest {
            assertEquals(Ok(0), repository.scan())
            assertEquals(emptyList<Long>(), settings.smsCursorWrites)
        }

    // --- decisions ------------------------------------------------------------------------------

    @Test
    fun `accepting a draft writes a transaction tagged sms and links it`() =
        runTest {
            reader.messages = listOf(debitAlert(id = 71L))
            repository.scan()
            val draft = repository.observePending().first().single()
            val accountId = anAccount()

            val created =
                repository.accept(
                    draft.id,
                    TransactionDraft(accountId = accountId, amount = Money(-125_000L), merchant = draft.counterparty),
                ).getOrNull()!!

            // FR-TXN-009: the provenance is stamped by the repository, never taken from the screen.
            assertEquals(TransactionSource.SMS, created.source)
            assertEquals(Money(-125_000L), created.amount)
            assertEquals(
                "an accepted draft leaves the review list",
                emptyList<SmsDraft>(),
                repository.observePending().first(),
            )
        }

    @Test
    fun `a failed ledger write leaves the draft pending`() =
        runTest {
            reader.messages = listOf(debitAlert(id = 71L))
            repository.scan()
            val draft = repository.observePending().first().single()

            // An account id that does not exist: the ledger write refuses it.
            val created = repository.accept(draft.id, TransactionDraft(accountId = "nope", amount = Money(-125_000L)))

            assertTrue(created is Err)
            // A draft marked accepted with no transaction behind it would vanish from the review
            // screen taking the alert with it, and the user would never learn the save had failed.
            assertEquals(1, repository.observePending().first().size)
        }

    @Test
    fun `accepting an unknown draft is not found`() =
        runTest {
            val result = repository.accept("missing", TransactionDraft(accountId = anAccount(), amount = Money(-1L)))

            assertEquals(Err(AppError.NotFound), result)
        }

    @Test
    fun `a dismissal survives a re-scan`() =
        runTest {
            reader.messages = listOf(debitAlert(id = 71L))
            repository.scan()
            repository.dismiss(repository.observePending().first().single().id)

            // The same alert comes back — an overlapping batch after a crash, or a reset cursor.
            settings.setSmsScanCursor(0L)
            repository.scan()

            assertEquals(
                "a message the user said no to must never be proposed again",
                emptyList<SmsDraft>(),
                repository.observePending().first(),
            )
        }

    @Test
    fun `dismissing an unknown draft is not found`() =
        runTest {
            assertEquals(Err(AppError.NotFound), repository.dismiss("missing"))
        }

    // --- the duplicate mirror (FR-OCR-006) -------------------------------------------------------

    @Test
    fun `an alert matching a scanned receipt is offered as a merge`() =
        runTest {
            val accountId = anAccount()
            transactions.create(
                TransactionDraft(
                    accountId = accountId,
                    amount = Money(-125_000L),
                    bookedOn = LocalDate.of(2026, 8, 7),
                    source = TransactionSource.OCR,
                ),
            )

            val candidates = repository.findDuplicates(Money(125_000L), LocalDate.of(2026, 8, 7)).getOrNull()!!

            // The receipt photographed at the till and the alert the bank sent are one purchase.
            assertEquals(1, candidates.size)
        }

    @Test
    fun `another alert is not a duplicate of this one`() =
        runTest {
            val accountId = anAccount()
            transactions.create(
                TransactionDraft(
                    accountId = accountId,
                    amount = Money(-125_000L),
                    bookedOn = LocalDate.of(2026, 8, 7),
                    source = TransactionSource.SMS,
                ),
            )

            val candidates = repository.findDuplicates(Money(125_000L), LocalDate.of(2026, 8, 7)).getOrNull()!!

            // Offering to merge two alerts would quietly delete a real second purchase.
            assertEquals(emptyList<Any>(), candidates)
        }

    // --- revocation (P-01) -----------------------------------------------------------------------

    @Test
    fun `revoking the consent erases pending drafts and resets the cursor`() =
        runTest {
            reader.messages = listOf(debitAlert(id = 71L))
            repository.scan()

            repository.onConsentRevoked()

            consents.set(ConsentFeature.SMS_PARSING, granted = true)
            assertEquals(
                "revocable must mean the inference goes, not that a switch flipped",
                emptyList<SmsDraft>(),
                repository.observePending().first(),
            )
            assertEquals(0L, settings.smsCursorWrites.last())
        }

    @Test
    fun `revoking the consent erases drafts under every profile, not just the active one`() =
        runTest {
            reader.messages = listOf(debitAlert(id = 71L))
            repository.scan()
            // The user switches to the demo and revokes from there. The consent is device-wide, so a
            // revocation scoped to whichever profile happened to be showing would keep every draft
            // drawn from their real inbox — the outcome "revocable" exists to prevent.
            activeProfileId.value = "demo"

            repository.onConsentRevoked()

            activeProfileId.value = PROFILE
            consents.set(ConsentFeature.SMS_PARSING, granted = true)
            assertEquals(emptyList<SmsDraft>(), repository.observePending().first())
        }

    @Test
    fun `revoking the consent leaves the ledger untouched`() =
        runTest {
            reader.messages = listOf(debitAlert(id = 71L))
            repository.scan()
            val draft = repository.observePending().first().single()
            repository.accept(draft.id, TransactionDraft(accountId = anAccount(), amount = Money(-125_000L)))

            repository.onConsentRevoked()

            // An accepted draft became a transaction the user deliberately saved. Deleting it — or
            // its provenance — because they turned the feature off would be losing their data.
            assertEquals(1, liveTransactions().size)
            assertEquals(TransactionSource.SMS, liveTransactions().single().source)
        }

    // --- helpers ----------------------------------------------------------------------------------

    /** Result: the live rows for this profile. Input: none. Output: the transactions. */
    private suspend fun liveTransactions() = transactions.liveTransactions()

    /** Result: a savings account to book into. Input: none. Output: its id. */
    private suspend fun anAccount(): String =
        accounts.create(
            AccountDraft(
                name = "HDFC Savings",
                type = AccountType.BANK,
                openingBalance = Money(1_000_000L),
                currencyCode = "INR",
            ),
        ).getOrNull()!!.id

    /** Result: a canonical UPI debit alert. Input: [id], [receivedAtUtcMillis]. Output: [SmsMessage]. */
    private fun debitAlert(
        id: Long,
        receivedAtUtcMillis: Long = NOW_MILLIS,
    ): SmsMessage =
        alert(
            id = id,
            body = "Rs.1,250.00 debited from A/c XX4521 to SWIGGY. Ref 522100123456. Avl Bal Rs.45,320.10",
            receivedAtUtcMillis = receivedAtUtcMillis,
        )

    /** Result: one message. Input: [id], [body], [receivedAtUtcMillis]. Output: [SmsMessage]. */
    private fun alert(
        id: Long,
        body: String,
        receivedAtUtcMillis: Long = NOW_MILLIS,
    ): SmsMessage = SmsMessage(id = id, sender = "VM-HDFCBK", body = body, receivedAtUtcMillis = receivedAtUtcMillis)

    private companion object {
        const val PROFILE = "p1"

        /** 2026-08-07T06:00Z — mid-morning in Asia/Kolkata, so the zone conversion is unambiguous. */
        const val NOW_MILLIS = 1_786_082_400_000L

        /** 2026-08-07T20:00Z — already the 8th in Asia/Kolkata; see the zone test. */
        const val LATE_EVENING_UTC_MILLIS = 1_786_132_800_000L
    }
}

/**
 * An inbox that records what it was asked and refuses to be read without permission (issue 3.9).
 *
 * Why:    the consent test is the reason this class exists in this shape. It **throws** when
 *         [readSince] is called while [canRead] is false, so a repository that queried first and
 *         checked afterwards fails loudly rather than passing on an empty result. A fake that
 *         quietly returned nothing would let exactly the bug this feature must not have — touching
 *         the user's messages before checking whether it may — go undetected.
 * Result: a reader a test can drive and assert against.
 * Changelog: 2026-08-07 — Created for issue 3.9.
 */
private class FakeSmsInboxReader : SmsInboxReader {
    var messages: List<SmsMessage> = emptyList()
    var canRead: Boolean = true
    var failWith: AppError? = null
    var callCount: Int = 0
        private set
    var lastAfterId: Long? = null
        private set

    override fun canRead(): Boolean = canRead

    override suspend fun readSince(
        afterId: Long,
        limit: Int,
    ): Result<List<SmsMessage>, AppError> {
        check(canRead) { "readSince was called without the READ_SMS permission — the gate leaked" }
        callCount++
        lastAfterId = afterId
        failWith?.let { return Err(it) }
        return Ok(messages.filter { it.id > afterId }.sortedBy { it.id }.take(limit))
    }
}

/**
 * A consent ledger a test can flip (issue 3.9).
 * Why:    the real [ConsentStore] is DataStore-backed and the suites here already avoid standing one
 *         up; what these tests need is the ability to revoke mid-flow and see the repository react.
 * Result: a [ConsentStore] over a `MutableStateFlow`.
 * Changelog: 2026-08-07 — Created for issue 3.9.
 */
private class FakeConsentStore : ConsentStore {
    private val state = MutableStateFlow<Map<ConsentFeature, ConsentState>>(emptyMap())

    /** Result: sets one feature's state. Input: [feature], [granted]. Output: none. */
    fun set(
        feature: ConsentFeature,
        granted: Boolean,
    ) {
        state.value = state.value + (feature to ConsentState(granted = granted))
    }

    override fun observe(feature: ConsentFeature): Flow<Result<ConsentState, AppError>> =
        state.map { Ok(it[feature] ?: ConsentState.NOT_GRANTED) }

    override fun observeAll(): Flow<Result<Map<ConsentFeature, ConsentState>, AppError>> =
        state.map { current -> Ok(ConsentFeature.entries.associateWith { current[it] ?: ConsentState.NOT_GRANTED }) }

    override suspend fun grant(feature: ConsentFeature): Result<Unit, AppError> = Ok(Unit).also { set(feature, true) }

    override suspend fun revoke(feature: ConsentFeature): Result<Unit, AppError> = Ok(Unit).also { set(feature, false) }
}
