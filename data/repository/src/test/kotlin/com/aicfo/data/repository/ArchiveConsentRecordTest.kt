package com.aicfo.data.repository

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.aicfo.core.common.AppError
import com.aicfo.core.common.Err
import com.aicfo.core.common.FakeClock
import com.aicfo.core.common.Ok
import com.aicfo.core.common.Result
import com.aicfo.core.common.TestDispatchers
import com.aicfo.core.database.CfoDatabase
import com.aicfo.core.datastore.ConsentFeature
import com.aicfo.core.datastore.ConsentState
import com.aicfo.core.datastore.ConsentStore
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.Instant

/**
 * The consent record travels with the export, and never comes back (issue 11.5; §23, §32, DPDP).
 *
 * Why:  DPDP's right of access is not satisfied by exporting a user's *data* while keeping the
 *       record of what they agreed to. "What has this app been allowed to do with my money, and
 *       since when?" is the question a data principal is entitled to an answer to, in a form they
 *       can keep — and the answer has existed in the ledger since issue 1.9 while reaching no file.
 *       Issue 11.3 showed it on a screen and deliberately left the portable form to this issue.
 *
 *       The other half is the direction, and it matters more. A consent record must be **export-only**:
 *       importing one would re-grant a consent the user may have withdrawn since the file was
 *       written, and a hand-edited archive would become a way to grant consents the user never gave.
 *       Consent is given on the device, by the person, and nowhere else.
 * What: every feature in the record with its state and both timestamps; an import that ignores it.
 * Result: the right of access is portable, and the file can never become a consent mechanism.
 * Changelog: 2026-10-01 — Created for issue 11.5.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class ArchiveConsentRecordTest {
    private val clock = FakeClock(initialMillis = Instant.parse("2026-08-16T06:00:00Z").toEpochMilli())
    private val dispatcher = UnconfinedTestDispatcher()
    private val activeProfileId = MutableStateFlow(PROFILE)
    private val consents = RecordingConsentLedger()
    private lateinit var database: CfoDatabase

    @Before
    fun setUp() {
        database =
            Room
                .inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), CfoDatabase::class.java)
                .allowMainThreadQueries()
                .build()
    }

    @After
    fun tearDown() = database.close()

    @Test
    fun `every consent is in the export, including the ones never answered`() =
        runTest(dispatcher) {
            // "Never asked" is a fact about the user's choices and belongs in the record. An export
            // that listed only the recorded ones would read as a shorter list of permissions than
            // the app actually has — the same defect issue 11.3's own test was caught not catching.
            consents.state[ConsentFeature.SMS_PARSING] =
                ConsentState(granted = true, grantedAtUtcMillis = GRANTED_AT)

            val archive = exported()

            assertEquals(ConsentFeature.entries.size, archive.consents.size)
            assertEquals(
                ConsentFeature.entries.map { it.id }.sorted(),
                archive.consents.map { it.featureId }.sorted(),
            )
        }

    @Test
    fun `a granted consent carries the date it was given`() =
        runTest(dispatcher) {
            consents.state[ConsentFeature.MARKET_DATA] =
                ConsentState(granted = true, grantedAtUtcMillis = GRANTED_AT)

            val row = exported().consents.single { it.featureId == ConsentFeature.MARKET_DATA.id }

            assertTrue(row.granted)
            assertEquals(GRANTED_AT, row.grantedAtUtcMillis)
            assertNull(row.revokedAtUtcMillis)
        }

    @Test
    fun `a withdrawn consent carries both dates, because both are facts about the person`() =
        runTest(dispatcher) {
            // Not two states but three, for the reason ADR-0059 gives: "never given" and "withdrawn
            // on the 4th" are different facts, and a record that collapsed them would misreport a
            // person's own history back to them in the one document meant to be authoritative.
            consents.state[ConsentFeature.CLOUD_BACKUP] =
                ConsentState(granted = false, grantedAtUtcMillis = GRANTED_AT, revokedAtUtcMillis = REVOKED_AT)

            val row = exported().consents.single { it.featureId == ConsentFeature.CLOUD_BACKUP.id }

            assertTrue(!row.granted)
            assertEquals(GRANTED_AT, row.grantedAtUtcMillis)
            assertEquals(REVOKED_AT, row.revokedAtUtcMillis)
        }

    @Test
    fun `a consent never answered says so, with no dates invented`() =
        runTest(dispatcher) {
            val row = exported().consents.single { it.featureId == ConsentFeature.CLOUD_LLM.id }

            assertTrue(!row.granted)
            assertNull("a date must never be invented for a consent nobody gave", row.grantedAtUtcMillis)
            assertNull(row.revokedAtUtcMillis)
        }

    @Test
    fun `an unreadable ledger fails the export rather than writing a file that omits it`() =
        runTest(dispatcher) {
            // The alternative is an export with an empty consent list, which a reader would take as
            // "this app was granted nothing". A document whose purpose is to be authoritative must
            // fail loudly rather than quietly understate what the app was allowed to do.
            consents.failWith = AppError.Storage("IOException")

            assertTrue(repository().export() is Err)
        }

    @Test
    fun `importing an archive never grants or withdraws a consent`() =
        runTest(dispatcher) {
            // The decision that matters most here. A file is not a person: restoring consents would
            // re-grant one the user has since withdrawn, and a hand-edited archive would become a
            // way to grant consents that were never given. Consent is given on the device, only.
            consents.state[ConsentFeature.SMS_PARSING] =
                ConsentState(granted = true, grantedAtUtcMillis = GRANTED_AT)
            val json = repository().export().let { (it as Ok).value }
            consents.state.clear()
            consents.writes.clear()

            repository().import(json)

            assertTrue("an import must not touch the ledger", consents.writes.isEmpty())
            assertTrue("and must not leave a consent granted", consents.state.isEmpty())
        }

    @Test
    fun `an archive written before this issue still imports`() =
        runTest(dispatcher) {
            // There are files on users' phones from every version since 5.4. The field defaults to
            // empty, so an older archive decodes; this asserts it rather than trusting the default.
            val old = """{"archiveVersion":1,"schemaVersion":${CfoDatabase.VERSION},"exportedAtUtcMillis":0}"""

            assertTrue(repository().import(old) is Ok)
        }

    private suspend fun exported(): CfoArchive =
        Json { ignoreUnknownKeys = true }.decodeFromString(
            (repository().export() as Ok).value,
        )

    private fun repository(): ArchiveRepository =
        RepositoryFactory.archive(database, clock, TestDispatchers(dispatcher), activeProfileId, consents)

    private companion object {
        const val PROFILE = "profile-dpdp"
        const val GRANTED_AT = 1_756_684_800_000L
        const val REVOKED_AT = 1_757_289_600_000L
    }
}

/**
 * A consent ledger the test drives directly (issue 11.5).
 * Why:    the export has to read the *whole* ledger, including features with no row at all, so the
 *         fake starts **empty** — the shape a fresh install has, and the shape that caught issue
 *         11.3's vacuous test when its fake seeded every feature instead.
 * Result: `state` is what is recorded; `writes` records any attempt to change it, which the import
 *         test asserts stays empty.
 * Input:  none. Output: the fake.
 * Changelog: 2026-10-01 — Created for issue 11.5.
 */
private class RecordingConsentLedger : ConsentStore {
    val state = mutableMapOf<ConsentFeature, ConsentState>()
    val writes = mutableListOf<String>()
    var failWith: AppError? = null

    override fun observe(feature: ConsentFeature): Flow<Result<ConsentState, AppError>> =
        flowOf(failWith?.let { Err(it) } ?: Ok(state[feature] ?: ConsentState.NOT_GRANTED))

    override fun observeAll(): Flow<Result<Map<ConsentFeature, ConsentState>, AppError>> =
        flowOf(failWith?.let { Err(it) } ?: Ok(state.toMap()))

    override suspend fun grant(feature: ConsentFeature): Result<Unit, AppError> {
        writes += "grant:${feature.id}"
        state[feature] = ConsentState(granted = true, grantedAtUtcMillis = 1L)
        return Ok(Unit)
    }

    override suspend fun revoke(feature: ConsentFeature): Result<Unit, AppError> {
        writes += "revoke:${feature.id}"
        state[feature] = ConsentState(granted = false, revokedAtUtcMillis = 1L)
        return Ok(Unit)
    }
}
