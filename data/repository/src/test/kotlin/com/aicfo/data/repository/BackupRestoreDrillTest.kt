package com.aicfo.data.repository

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.aicfo.core.common.AppError
import com.aicfo.core.common.Err
import com.aicfo.core.common.FakeClock
import com.aicfo.core.common.Ok
import com.aicfo.core.common.Result
import com.aicfo.core.common.TestDispatchers
import com.aicfo.core.crypto.BackupCipherFactory
import com.aicfo.core.database.CfoDatabase
import com.aicfo.core.datastore.ConsentFeature
import com.aicfo.core.datastore.ConsentState
import com.aicfo.core.datastore.ConsentStore
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The backup restore drill, on the JVM (issue 8.3; §21.5, §34.4 DRL-001).
 *
 * Why:  "an untested backup is a hope, not a plan" (DRL-001). Backups rot silently — a table added
 *       to the schema and forgotten by the archive, a column renamed, a restore that quietly drops a
 *       tombstone — and none of it shows until the day a phone is lost. This drill seals a backup
 *       from a database with a row in **every** table, restores it into a **separate, clean**
 *       database, and compares the two table by table, read straight from SQLite.
 *
 *       It runs in `unitTests`, which CI runs on every pull request into `dev`, `stage` and `main` —
 *       so a regression fails the build long before a release is cut. Its instrumented twin,
 *       `BackupRestoreDrillDeviceTest`, repeats it on real SQLCipher and is the release-gate step
 *       (`docs/issues/00-issue-workflow.md`, "Release gate").
 * What: the fixture fills every table; row parity after the round trip; the drill is not vacuous —
 *       a restore missing one table's rows is caught; and no table escapes the drill unscoped.
 * Result: backups proven restorable on every build.
 * Changelog: 2026-09-19 — Created for issue 8.3.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class BackupRestoreDrillTest {
    private lateinit var source: CfoDatabase
    private lateinit var clean: CfoDatabase

    private val dispatcher = UnconfinedTestDispatcher()
    private val dispatchers = TestDispatchers(dispatcher)
    private val clock = FakeClock(initialMillis = DrillFixture.NOW)

    /** Input: none. Output: two independent in-memory databases — the old phone and the new one. */
    @Before
    fun setUp() {
        source = inMemory()
        clean = inMemory()
    }

    /** Input: none. Output: both closed. */
    @After
    fun tearDown() {
        source.close()
        clean.close()
    }

    @Test
    fun `the fixture fills every table the schema has — a table left empty would pass for nothing`() =
        runTest(dispatcher) {
            DrillFixture.seed(source, PROFILE)

            val snapshot = ProfileSnapshot.of(source, PROFILE)

            assertEquals(
                "every table must be seeded; empty: ${snapshot.emptyTables}",
                emptySet<String>(),
                snapshot.emptyTables,
            )
            assertEquals(
                "a table no drill can scope to a profile has escaped the backup's reach",
                emptySet<String>(),
                snapshot.unscoped,
            )
        }

    @Test
    fun `a backup restored onto a clean database holds exactly the rows it was taken from`() =
        runTest(dispatcher) {
            DrillFixture.seed(source, PROFILE)
            val before = ProfileSnapshot.of(source, PROFILE)

            val sealed = backups(source).create(PASSPHRASE.toCharArray()).expectOk()
            assertEquals("the clean instance must start empty", 0, clean.demoDao().countRowsFor(PROFILE))
            backups(clean).restore(sealed, PASSPHRASE.toCharArray()).expectOk()

            val after = ProfileSnapshot.of(clean, PROFILE)
            assertEquals("row counts per table", before.rowCounts, after.rowCounts)
            assertEquals("row contents per table", before.tables, after.tables)
        }

    @Test
    fun `the drill notices a restore that lost one table`() =
        runTest(dispatcher) {
            DrillFixture.seed(source, PROFILE)
            val sealed = backups(source).create(PASSPHRASE.toCharArray()).expectOk()
            backups(clean).restore(sealed, PASSPHRASE.toCharArray()).expectOk()

            clean.demoDao().deleteGoals(PROFILE)

            val before = ProfileSnapshot.of(source, PROFILE)
            val after = ProfileSnapshot.of(clean, PROFILE)
            assertNotEquals(before.tables, after.tables)
            assertTrue("the lost table is the one named", "goal" in after.emptyTables)
        }

    @Test
    fun `the drill notices a single changed paisa`() =
        runTest(dispatcher) {
            DrillFixture.seed(source, PROFILE)
            val sealed = backups(source).create(PASSPHRASE.toCharArray()).expectOk()
            backups(clean).restore(sealed, PASSPHRASE.toCharArray()).expectOk()

            clean.openHelper.writableDatabase.execSQL(
                "UPDATE transactions SET amount_minor = amount_minor + 1 WHERE id = ?",
                arrayOf(DrillFixture.RICH_TXN),
            )

            assertNotEquals(ProfileSnapshot.of(source, PROFILE).tables, ProfileSnapshot.of(clean, PROFILE).tables)
        }

    // --- the stack ---------------------------------------------------------------------------------

    private fun inMemory(): CfoDatabase =
        Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), CfoDatabase::class.java)
            .allowMainThreadQueries()
            .build()

    private fun backups(database: CfoDatabase): BackupRepository =
        RepositoryFactory.backup(
            archive = RepositoryFactory.archive(database, clock, dispatchers, flowOf(PROFILE)),
            cipher = BackupCipherFactory.create(),
            consents = GrantedConsent,
            audit = RepositoryFactory.auditLog(database, clock, dispatchers),
            dispatchers = dispatchers,
        )

    private fun <T> Result<T, AppError>.expectOk(): T =
        when (this) {
            is Ok -> value
            is Err -> throw AssertionError("expected Ok, got $error")
        }

    /** The backup consent, granted: the drill is about restorability, not the gate. */
    private object GrantedConsent : ConsentStore {
        override fun observe(feature: ConsentFeature): Flow<Result<ConsentState, AppError>> =
            flowOf(Ok(ConsentState(granted = true)))

        override fun observeAll(): Flow<Result<Map<ConsentFeature, ConsentState>, AppError>> = emptyFlow()

        override suspend fun grant(feature: ConsentFeature): Result<Unit, AppError> = Ok(Unit)

        override suspend fun revoke(feature: ConsentFeature): Result<Unit, AppError> = Ok(Unit)
    }

    private companion object {
        const val PROFILE = "local"
        const val PASSPHRASE = "correct horse battery staple"
    }
}
