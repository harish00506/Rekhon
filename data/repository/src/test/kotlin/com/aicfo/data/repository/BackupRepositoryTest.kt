package com.aicfo.data.repository

import com.aicfo.core.common.AppError
import com.aicfo.core.common.Err
import com.aicfo.core.common.Ok
import com.aicfo.core.common.Result
import com.aicfo.core.common.TestDispatchers
import com.aicfo.core.crypto.BackupCipher
import com.aicfo.core.crypto.BackupCipherFactory
import com.aicfo.core.datastore.ConsentFeature
import com.aicfo.core.datastore.ConsentState
import com.aicfo.core.datastore.ConsentStore
import com.aicfo.core.model.AuditEvent
import com.aicfo.core.model.AuditMethod
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The encrypted backup, from the consent gate to the sealed bytes (issue 8.1; SEC-005, P-01).
 *
 * Why:  the cipher's own test proves the cryptography. What only this layer can get wrong is the
 *       **order** — consent checked before the archive is read, the passphrase cleared whatever
 *       happens, the audit row written only for a backup that exists — and a mistake in any of them
 *       is silent: the user still gets a file.
 * What: the consent gate (absent, revoked, unreadable), the round trip through the real cipher,
 *       passphrase clearing on every path, the failures passed through, and the audit event.
 * Result: P-01's "off-device writes are consent-gated" and SEC-005's "passphrase never stored",
 *       asserted on the JVM.
 * Changelog: 2026-09-18 — Created for issue 8.1.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class BackupRepositoryTest {
    private val consents = FakeBackupConsents()
    private val archive = FakeArchive()
    private val audit = RecordingAudit()
    private val cipher = BackupCipherFactory.create()

    // --- the consent gate (P-01) ------------------------------------------------------------------

    @Test
    fun `no backup is made without the backup consent, and the archive is never read`() =
        runTest {
            val passphrase = PASSPHRASE.toCharArray()

            val result = repository().create(passphrase)

            assertEquals(Err(AppError.Validation("backup.consent")), result)
            assertEquals("the archive must not even be read for a refused backup", 0, archive.exports)
            assertTrue("nothing to audit", audit.events.isEmpty())
            assertTrue("the passphrase is cleared on the refusal path too", passphrase.all { it == '\u0000' })
        }

    @Test
    fun `an unreadable consent ledger reads as not granted`() =
        runTest {
            consents.failWith = AppError.Storage("disk")

            assertEquals(Err(AppError.Validation("backup.consent")), repository().create(PASSPHRASE.toCharArray()))
            assertEquals(0, archive.exports)
        }

    @Test
    fun `a revoked consent stops the next backup`() =
        runTest {
            consents.granted.value = true
            val repository = repository()
            assertTrue(repository.create(PASSPHRASE.toCharArray()) is Ok)

            consents.granted.value = false

            assertEquals(Err(AppError.Validation("backup.consent")), repository.create(PASSPHRASE.toCharArray()))
            assertEquals("only the granted backup read the archive", 1, archive.exports)
        }

    // --- the backup itself ------------------------------------------------------------------------

    @Test
    fun `a granted backup opens with the passphrase to exactly the exported archive`() =
        runTest {
            consents.granted.value = true

            val sealed = (repository().create(PASSPHRASE.toCharArray()) as Ok).value

            val opened = (cipher.open(sealed, PASSPHRASE.toCharArray()) as Ok).value
            assertEquals(FakeArchive.JSON, opened.decodeToString())
        }

    @Test
    fun `the passphrase is cleared once the backup is sealed`() =
        runTest {
            consents.granted.value = true
            val passphrase = PASSPHRASE.toCharArray()

            repository().create(passphrase)

            assertTrue("SEC-005: the passphrase must not outlive the call", passphrase.all { it == '\u0000' })
        }

    @Test
    fun `the sealed bytes carry none of the archive in the clear`() =
        runTest {
            consents.granted.value = true

            val sealed = (repository().create(PASSPHRASE.toCharArray()) as Ok).value

            assertTrue(!sealed.decodeToString().contains("balanceMinor"))
        }

    @Test
    fun `a successful backup is recorded in the audit log, with no method`() =
        runTest {
            consents.granted.value = true

            repository().create(PASSPHRASE.toCharArray())

            assertEquals(listOf(AuditEvent.BACKUP_CREATED to null), audit.events)
        }

    @Test
    fun `a failed audit write does not undo a backup that was made`() =
        runTest {
            consents.granted.value = true
            audit.failWith = AppError.Storage("disk")

            assertTrue(repository().create(PASSPHRASE.toCharArray()) is Ok)
        }

    // --- failures passed through ------------------------------------------------------------------

    @Test
    fun `an export failure is returned as it is, with nothing sealed or audited`() =
        runTest {
            consents.granted.value = true
            archive.failWith = AppError.Storage("SQLiteException")
            val passphrase = PASSPHRASE.toCharArray()

            assertEquals(Err(AppError.Storage("SQLiteException")), repository().create(passphrase))
            assertTrue(audit.events.isEmpty())
            assertTrue(passphrase.all { it == '\u0000' })
        }

    @Test
    fun `a passphrase the cipher refuses is returned as the cipher's validation error`() =
        runTest {
            consents.granted.value = true

            assertEquals(Err(AppError.Validation("backup.passphrase")), repository().create("too short".toCharArray()))
            assertTrue(audit.events.isEmpty())
        }

    @Test
    fun `a cipher failure is returned and nothing is audited`() =
        runTest {
            consents.granted.value = true
            val failing =
                object : BackupCipher {
                    override fun seal(
                        plaintext: ByteArray,
                        passphrase: CharArray,
                    ): Result<ByteArray, AppError> = Err(AppError.Crypto("backup.seal"))

                    override fun open(
                        sealed: ByteArray,
                        passphrase: CharArray,
                    ): Result<ByteArray, AppError> = error("not reached")
                }

            assertEquals(Err(AppError.Crypto("backup.seal")), repository(failing).create(PASSPHRASE.toCharArray()))
            assertTrue(audit.events.isEmpty())
        }

    // --- helpers ----------------------------------------------------------------------------------

    private fun repository(backupCipher: BackupCipher = cipher): BackupRepository =
        RepositoryFactory.backup(
            archive = archive,
            cipher = backupCipher,
            consents = consents,
            audit = audit,
            dispatchers = TestDispatchers(UnconfinedTestDispatcher()),
        )

    private class FakeArchive : ArchiveRepository {
        var exports = 0
        var failWith: AppError? = null

        override suspend fun export(): Result<String, AppError> {
            failWith?.let { return Err(it) }
            exports++
            return Ok(JSON)
        }

        override suspend fun import(json: String): Result<ImportSummary, AppError> = error("a backup never imports")

        companion object {
            const val JSON = """{"archiveVersion":1,"schemaVersion":22,"accounts":[{"balanceMinor":123456}]}"""
        }
    }

    private class FakeBackupConsents : ConsentStore {
        val granted = MutableStateFlow(false)
        var failWith: AppError? = null

        override fun observe(feature: ConsentFeature): Flow<Result<ConsentState, AppError>> {
            failWith?.let { return flowOf(Err(it)) }
            return granted.map { Ok(ConsentState(granted = it && feature == ConsentFeature.CLOUD_BACKUP)) }
        }

        override fun observeAll(): Flow<Result<Map<ConsentFeature, ConsentState>, AppError>> = emptyFlow()

        override suspend fun grant(feature: ConsentFeature): Result<Unit, AppError> = Ok(Unit)

        override suspend fun revoke(feature: ConsentFeature): Result<Unit, AppError> = Ok(Unit)
    }

    private class RecordingAudit : AuditLogRepository {
        val events = mutableListOf<Pair<AuditEvent, AuditMethod?>>()
        var failWith: AppError? = null

        override suspend fun record(
            event: AuditEvent,
            method: AuditMethod?,
        ): Result<Unit, AppError> {
            failWith?.let { return Err(it) }
            events += event to method
            return Ok(Unit)
        }

        override fun observeRecent(limit: Int): Flow<List<AuditEntry>> = emptyFlow()

        override suspend fun countSince(
            event: AuditEvent,
            sinceUtcMillis: Long,
        ): Result<Int, AppError> = Ok(0)
    }

    private companion object {
        const val PASSPHRASE = "correct horse battery staple"
    }
}
