package com.aicfo.feature.settings

import com.aicfo.core.common.AppError
import com.aicfo.core.common.Err
import com.aicfo.core.common.FakeClock
import com.aicfo.core.common.Ok
import com.aicfo.core.common.Result
import com.aicfo.core.datastore.ConsentFeature
import com.aicfo.core.datastore.ConsentState
import com.aicfo.core.datastore.ConsentStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.Instant
import java.time.ZoneId

/**
 * The consents dashboard's state (issue 11.3; §23, P-01, DPDP).
 *
 * Why:  P-01's promise is not "we ask first" — it is **explicit, per-feature and revocable**, and a
 *       promise the user cannot inspect is a promise they have to take on trust. The ledger has
 *       recorded *when* each consent was granted and withdrawn since issue 1.9, and until now
 *       nothing showed it: the settings screen rendered a switch per feature and dropped both
 *       timestamps on the floor. This is the screen that answers "what did I agree to, and when?"
 * What: every feature listed, the dates in the **profile's** zone, the revoke reaching the store,
 *       and a failed write that does not pretend to have succeeded.
 * Result: a control surface a user can audit, not just operate.
 * Changelog: 2026-10-01 — Created for issue 11.3.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ConsentsViewModelTest {
    private val dispatcher = UnconfinedTestDispatcher()
    private val consents = RecordingConsentStore()

    /** 2026-03-14T20:30Z is already the **15th** in Kolkata — the point of using a zone at all. */
    private val clock = FakeClock(Instant.parse("2026-03-14T20:30:00Z").toEpochMilli(), ZoneId.of("Asia/Kolkata"))

    /** Input: none. Output: the main dispatcher is the test one. */
    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    /** Input: none. Output: the main dispatcher is restored. */
    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `every consent is listed, even the ones nobody has answered for`() {
        // Absence is never consent (issue 1.9). A feature missing from the ledger must appear as a
        // "no" the user can see, not as a row that is simply not there.
        val state = viewModel().uiState.value

        assertEquals(ConsentFeature.entries.size, state.rows.size)
        assertEquals(ConsentFeature.entries, state.rows.map { it.feature })
        assertTrue("an unanswered consent must read as not granted", state.rows.none { it.granted })
    }

    @Test
    fun `a granted consent says when, in the profile's own zone`() {
        consents.set(
            ConsentFeature.MARKET_DATA,
            ConsentState(granted = true, grantedAtUtcMillis = clock.nowUtcMillis()),
        )

        val row = viewModel().uiState.value.rows.first { it.feature == ConsentFeature.MARKET_DATA }

        assertTrue(row.granted)
        // 20:30 UTC on the 14th is 02:00 on the 15th in Kolkata. Formatting in UTC would tell the
        // user they agreed to something a day before they did.
        assertEquals("2026-03-15", row.grantedOnIsoDate)
        assertNull(row.revokedOnIsoDate)
    }

    @Test
    fun `a withdrawn consent still shows both dates`() {
        // The ledger keeps the record rather than deleting it precisely so the withdrawal is
        // auditable — "I turned this off, and here is when". Showing only the latest state would
        // throw away the half that matters for a DPDP request.
        consents.set(
            ConsentFeature.SMS_PARSING,
            ConsentState(
                granted = false,
                grantedAtUtcMillis = Instant.parse("2026-01-02T05:00:00Z").toEpochMilli(),
                revokedAtUtcMillis = Instant.parse("2026-02-03T19:00:00Z").toEpochMilli(),
            ),
        )

        val row = viewModel().uiState.value.rows.first { it.feature == ConsentFeature.SMS_PARSING }

        assertFalse(row.granted)
        assertEquals("2026-01-02", row.grantedOnIsoDate)
        assertEquals("2026-02-04", row.revokedOnIsoDate)
    }

    @Test
    fun `revoking reaches the store`() =
        runTest(dispatcher) {
            consents.set(ConsentFeature.CLOUD_BACKUP, ConsentState(granted = true, grantedAtUtcMillis = 1L))
            val model = viewModel()

            model.onEvent(ConsentsEvent.Revoked(ConsentFeature.CLOUD_BACKUP))

            assertEquals(listOf(ConsentFeature.CLOUD_BACKUP), consents.revoked)
            assertFalse(model.uiState.value.rows.first { it.feature == ConsentFeature.CLOUD_BACKUP }.granted)
        }

    @Test
    fun `granting from here reaches the store too`() =
        runTest(dispatcher) {
            // The dashboard is the control surface, not a one-way door: a user who withdrew a
            // consent by mistake must be able to give it again without hunting for another screen.
            val model = viewModel()

            model.onEvent(ConsentsEvent.Granted(ConsentFeature.MARKET_DATA))

            assertEquals(listOf(ConsentFeature.MARKET_DATA), consents.granted)
        }

    @Test
    fun `a failed revoke surfaces, and the row does not claim to be off`() =
        runTest(dispatcher) {
            // The lie this must never tell: a switch that says a feature is off while its data path
            // is still running. For a privacy control that is the whole risk.
            consents.set(ConsentFeature.SMS_PARSING, ConsentState(granted = true, grantedAtUtcMillis = 1L))
            consents.failWrites = AppError.Storage("IOException")
            val model = viewModel()

            model.onEvent(ConsentsEvent.Revoked(ConsentFeature.SMS_PARSING))

            assertNotNull("a failed write must be visible", model.uiState.value.errorCode)
            assertTrue(
                "the row must still read as granted, because it is",
                model.uiState.value.rows.first { it.feature == ConsentFeature.SMS_PARSING }.granted,
            )
        }

    @Test
    fun `a ledger that cannot be read is an error, not an empty list of permissions`() {
        // Falling back to "nothing is granted" would be comforting and wrong: the consents are
        // still in force, and a screen that implies otherwise invites the user to stop looking.
        consents.failReads = AppError.Storage("IOException")

        val state = viewModel().uiState.value

        assertNotNull(state.errorCode)
    }

    private fun viewModel() = ConsentsViewModel(consents, clock)
}

/**
 * A consent ledger a test can set, break and inspect.
 * Why:    the dashboard's job is to report the ledger faithfully, so the fake has to be able to
 *         hold states no happy path produces — a withdrawn consent with both timestamps, a store
 *         that fails its reads, a store that fails its writes.
 * Result: every branch of the screen is reachable.
 * Changelog: 2026-10-01 — Created for issue 11.3.
 */
private class RecordingConsentStore : ConsentStore {
    /**
     * **Starts empty, like a real fresh install's ledger.** It was seeded with every feature at
     * first, and that made "every consent is listed" vacuous: an implementation that listed only
     * the recorded entries passed, because every entry was recorded. A mutation found it.
     */
    private val states = MutableStateFlow(emptyMap<ConsentFeature, ConsentState>())

    val granted = mutableListOf<ConsentFeature>()
    val revoked = mutableListOf<ConsentFeature>()
    var failReads: AppError? = null
    var failWrites: AppError? = null

    fun set(
        feature: ConsentFeature,
        state: ConsentState,
    ) {
        states.value = states.value + (feature to state)
    }

    override fun observe(feature: ConsentFeature): Flow<Result<ConsentState, AppError>> =
        states.map { all -> failReads?.let { Err(it) } ?: Ok(all[feature] ?: ConsentState.NOT_GRANTED) }

    override fun observeAll(): Flow<Result<Map<ConsentFeature, ConsentState>, AppError>> =
        states.map { all -> failReads?.let { Err(it) } ?: Ok(all) }

    override suspend fun grant(feature: ConsentFeature): Result<Unit, AppError> =
        failWrites?.let { Err(it) } ?: Ok(Unit).also {
            granted += feature
            set(feature, ConsentState(granted = true, grantedAtUtcMillis = 1L))
        }

    override suspend fun revoke(feature: ConsentFeature): Result<Unit, AppError> =
        failWrites?.let { Err(it) } ?: Ok(Unit).also {
            revoked += feature
            val previous = states.value[feature] ?: ConsentState.NOT_GRANTED
            set(feature, previous.copy(granted = false, revokedAtUtcMillis = 2L))
        }
}
