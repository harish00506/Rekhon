package com.aicfo.data.repository

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.aicfo.core.common.AppError
import com.aicfo.core.common.Err
import com.aicfo.core.common.FakeClock
import com.aicfo.core.common.Ok
import com.aicfo.core.common.Result
import com.aicfo.core.common.TestDispatchers
import com.aicfo.core.crypto.BackupCipherFactory
import com.aicfo.core.database.CfoDatabase
import com.aicfo.core.database.CfoDatabaseFactory
import com.aicfo.core.datastore.ConsentFeature
import com.aicfo.core.datastore.ConsentState
import com.aicfo.core.datastore.ConsentStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * The backup restore drill on a device — **the release-gate step** (issue 8.3; §21.5, §34.4 DRL-001).
 *
 * Why:  the JVM drill (`BackupRestoreDrillTest`) runs on every build but on unencrypted in-memory
 *       Room with the JVM's crypto providers. A release ships SQLCipher and Android's, and the drill
 *       that gates it must run on those: seed every table in the **encrypted** database, seal a
 *       backup at the shipping Argon2id cost, destroy the database file **and its key** — a clean
 *       instance, exactly what a new phone has — open a fresh one, restore, and compare every table
 *       read straight from SQLite.
 * What: row parity (counts and contents) across a real SQLCipher rebuild, with every table seeded.
 * Result: a release cannot be promoted to `main` while a backup made by it would not restore
 *       (`docs/issues/00-issue-workflow.md`, "Release gate").
 * Changelog: 2026-09-19 — Created for issue 8.3.
 *
 * Runs in this module's own test package, so the database it creates and destroys is the test's,
 * never the installed app's.
 */
@RunWith(AndroidJUnit4::class)
class BackupRestoreDrillDeviceTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val clock = FakeClock(initialMillis = DrillFixture.NOW)
    private val dispatchers = TestDispatchers(Dispatchers.Default)
    private lateinit var database: CfoDatabase

    /** Input: none. Output: a clean encrypted database. */
    @Before
    fun setUp() {
        deleteDatabaseFiles()
        database = open()
    }

    /** Input: none. Output: closed and removed. */
    @After
    fun tearDown() {
        database.close()
        deleteDatabaseFiles()
    }

    /**
     * Input:  every table seeded in the encrypted database.
     * Output: asserts the fixture reached every table, then that after backup → destroy database
     *         and key → fresh database → restore, every table holds the same rows, byte for byte.
     */
    @Test
    fun drillEveryTableSurvivesBackupAndRestoreOntoACleanEncryptedInstance() =
        runTest {
            DrillFixture.seed(database, PROFILE)
            val before = ProfileSnapshot.of(database, PROFILE)
            assertEquals("every table must be seeded", emptySet<String>(), before.emptyTables)
            assertEquals("no table may escape the drill", emptySet<String>(), before.unscoped)

            val sealed = backups().create(PASSPHRASE.toCharArray()).expectOk()

            database.close()
            deleteDatabaseFiles()
            database = open()
            assertEquals("the clean instance must start empty", 0, database.demoDao().countRowsFor(PROFILE))

            backups().restore(sealed, PASSPHRASE.toCharArray()).expectOk()

            val after = ProfileSnapshot.of(database, PROFILE)
            assertEquals("row counts per table", before.rowCounts, after.rowCounts)
            assertEquals("row contents per table", before.tables, after.tables)
        }

    private fun open(): CfoDatabase = CfoDatabaseFactory.open(context).expectOk()

    private fun backups(): BackupRepository =
        RepositoryFactory.backup(
            archive = RepositoryFactory.archive(database, clock, dispatchers, flowOf(PROFILE)),
            cipher = BackupCipherFactory.create(),
            consents = GrantedConsent,
            audit = RepositoryFactory.auditLog(database, clock, dispatchers),
            dispatchers = dispatchers,
        )

    /** Removes the database, its journals and its wrapped passphrase — a fresh install's state. */
    private fun deleteDatabaseFiles() {
        val base = context.getDatabasePath(CfoDatabase.FILE_NAME)
        listOf(base, File(base.path + "-wal"), File(base.path + "-shm")).forEach { it.delete() }
        File(context.filesDir, "cfo-db-passphrase.bin").delete()
    }

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
