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
import com.aicfo.core.crypto.CryptoSecrets
import com.aicfo.core.crypto.KeystoreMacFactory
import com.aicfo.core.crypto.ReceiptImageStoreFactory
import com.aicfo.core.database.CfoDatabase
import com.aicfo.core.database.CfoDatabaseFactory
import com.aicfo.core.database.crypto.DatabaseSecrets
import com.aicfo.core.database.entity.AccountEntity
import com.aicfo.core.database.entity.ProfileEntity
import com.aicfo.core.database.entity.TransactionEntity
import com.aicfo.core.datastore.DataStoreSecrets
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.security.KeyStore

/**
 * Nothing readable is left (issue 11.4; §34, SEC-003, §21.5).
 *
 * Why:  the acceptance criterion asks for an **instrumented** test that verifies no readable data
 *       remains, and the reason it has to be instrumented is that every JVM test of this feature
 *       runs against fakes: a fake Keystore, an in-memory Room, a temporary directory. None of them
 *       can tell you whether `KeyStore.deleteEntry` on a real device actually removed a TEE-backed
 *       key, or whether SQLCipher left a page of plaintext in a `-wal` nobody deleted.
 *
 *       So this test writes a **canary** — a string and an amount that appear nowhere else — into a
 *       real encrypted database, a real receipt blob and a real settings file, runs the real erase,
 *       and then reads every byte the app still owns looking for them. That is a stronger claim
 *       than "the files we listed are gone": it would fail for a secret nobody thought to list,
 *       which is the whole failure mode [com.aicfo.core.common.SecretInventory] exists to prevent.
 * What: write the canary → erase → every alias gone, every file gone, the canary nowhere, and a
 *       brand-new database opens empty under a brand-new key.
 * Result: "cryptographically unrecoverable" demonstrated on hardware rather than asserted about it.
 * Changelog: 2026-10-01 — Created for issue 11.4.
 *
 * Runs in this module's own test package, so the data it creates and erases is the test's, never
 * the installed app's.
 */
@RunWith(AndroidJUnit4::class)
class EraseDeviceTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val clock = FakeClock(initialMillis = CREATED)
    private val dispatchers = TestDispatchers(Dispatchers.Default)

    /** Input: none. Output: an installation holding the canary in every store the app has. */
    @Before
    fun setUp() =
        runTest {
            val database = CfoDatabaseFactory.open(context).expectOk()
            database.profileDao().upsert(
                ProfileEntity(
                    id = PROFILE,
                    // The canary. A real display name, so it is stored the way real data is.
                    displayName = CANARY,
                    timeZoneId = "Asia/Kolkata",
                    currencyCode = "INR",
                    createdAtUtcMillis = CREATED,
                    updatedAtUtcMillis = CREATED,
                ),
            )
            database.accountDao().upsert(
                AccountEntity(
                    id = "account-1",
                    profileId = PROFILE,
                    name = CANARY,
                    type = "bank",
                    openingBalanceMinor = CANARY_AMOUNT,
                    currentBalanceMinor = CANARY_AMOUNT,
                    currencyCode = "INR",
                    createdAtUtcMillis = CREATED,
                    updatedAtUtcMillis = CREATED,
                ),
            )
            database.transactionDao().upsert(
                TransactionEntity(
                    id = "txn-1",
                    profileId = PROFILE,
                    accountId = "account-1",
                    amountMinor = CANARY_AMOUNT,
                    currencyCode = "INR",
                    occurredAtUtcMillis = CREATED,
                    bookedOnIsoDate = "2026-09-01",
                    source = "manual",
                    type = "expense",
                    createdAtUtcMillis = CREATED,
                    updatedAtUtcMillis = CREATED,
                ),
            )
            // Checkpointed so the pages reach the file rather than sitting in a WAL this test's own
            // close would have flushed — the erase must cope with either, and leaving it unflushed
            // would quietly make the canary scan weaker instead of stronger.
            database.close()

            // A real receipt blob, through the real Keystore-backed AEAD.
            ReceiptImageStoreFactory.create(context).write("canary-receipt", CANARY.toByteArray()).expectOk()
            // A real PIN credential, through the real Keystore-backed MAC.
            KeystoreMacFactory.createVerifier(context).setPin("1234").expectOk()
            // A real settings file. Plaintext protobuf, which is exactly why it is in the inventory.
            File(context.filesDir, DataStoreSecrets.FILE_NAME).writeText(CANARY)
        }

    @Test
    fun theEraseLeavesNoKeyNoFileAndNoCanary() =
        runTest {
            // Everything the canary was written through exists at this point; if it did not, the
            // assertions below would pass over an installation that never had any data.
            assertTrue("the test wrote no database", databaseFile().exists())
            assertTrue("the test set no PIN", File(context.filesDir, CryptoSecrets.PIN_CREDENTIAL_FILE).exists())
            assertTrue(
                "the Keystore holds no key to destroy",
                keyStore().containsAlias(DatabaseSecrets.MASTER_KEY_ALIAS),
            )

            val outcome = RepositoryFactory.erase(context, NoOpAuditLog, dispatchers).eraseEverything()

            assertEquals(Ok(Unit), outcome)

            // 1. Every key, gone from the real Keystore. This is what makes the erase cryptographic:
            //    whatever ciphertext survives on this disk has no key anywhere in the world.
            listOf(
                DatabaseSecrets.MASTER_KEY_ALIAS,
                CryptoSecrets.PIN_MASTER_KEY_ALIAS,
                CryptoSecrets.RECEIPT_MASTER_KEY_ALIAS,
            ).forEach { alias ->
                assertFalse("the Keystore still holds $alias", keyStore().containsAlias(alias))
            }

            // 2. The canary appears in no byte the app still owns. **Asserted before the
            //    file-by-file checks below**, and deliberately: those name the files the inventory
            //    already knows about, so letting them fail first would shadow the only assertion
            //    that can catch a secret nobody listed — and that is the failure this whole design
            //    exists to prevent. Proven non-vacuous on the emulator by dropping `cfo_settings.pb`
            //    from its module's inventory, which this line catches by itself.
            val found = searchForCanary()
            assertTrue("the canary survived in: $found", found.isEmpty())

            // 3. And the named files, as a diagnostic: when the scan above fails, these say which
            //    store it was, which a byte offset in a path never would.
            listOf(
                databaseFile(),
                File(databaseFile().path + "-wal"),
                File(databaseFile().path + "-shm"),
                File(context.filesDir, DatabaseSecrets.PASSPHRASE_FILE),
                File(context.filesDir, CryptoSecrets.PIN_CREDENTIAL_FILE),
                File(context.filesDir, CryptoSecrets.RECEIPT_DIRECTORY),
                File(context.filesDir, DataStoreSecrets.FILE_NAME),
            ).forEach { file ->
                assertFalse("${file.name} survived the erase", file.exists())
            }
        }

    @Test
    fun aNewDatabaseOpensEmptyUnderANewKey() =
        runTest {
            RepositoryFactory.erase(context, NoOpAuditLog, dispatchers).eraseEverything().expectOk()

            // The other half of "unrecoverable": the app has to be usable again. A fresh open
            // generates a new master key and a new passphrase, and the old file — if the sweep had
            // left it — would not open under either.
            val database = CfoDatabaseFactory.open(context).expectOk()
            try {
                // The profile row the canary was written as. Absent, because this is a new file
                // under a new key and not the old one reopened.
                assertEquals(null, database.profileDao().findById(PROFILE))
                assertTrue(keyStore().containsAlias(DatabaseSecrets.MASTER_KEY_ALIAS))
            } finally {
                database.close()
            }
        }

    // --- helpers ----------------------------------------------------------------------------------

    /**
     * Every byte under the app's own storage that still holds the canary.
     * Why:    the test's real assertion. A directory walk rather than a list of names, because the
     *         point is to find what no list contains — a cache file, a journal, a `.tmp` left by an
     *         interrupted write, a secret a future module forgot to declare.
     * Result: the paths that matched, empty when the erase was complete. Both the string and the
     *         amount are searched: the amount catches a numeric column written without the name.
     * Input:  none. Output: a list of paths.
     * Changelog: 2026-10-01 — Created for issue 11.4.
     */
    private fun searchForCanary(): List<String> {
        val needles = listOf(CANARY.toByteArray(), CANARY_AMOUNT.toString().toByteArray())
        return listOfNotNull(context.filesDir, context.cacheDir, databaseFile().parentFile, context.noBackupFilesDir)
            .flatMap { root -> root.walkTopDown().filter { it.isFile }.toList() }
            .filter { file -> file.readBytes().let { bytes -> needles.any { bytes.containsSequence(it) } } }
            .map { it.path }
    }

    /** Result: the app's database file. Input: none. Output: [File]. */
    private fun databaseFile(): File = context.getDatabasePath(CfoDatabase.FILE_NAME)

    /** Result: the loaded real Keystore. Input: none. Output: [KeyStore]. */
    private fun keyStore(): KeyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }

    private fun <T> Result<T, AppError>.expectOk(): T =
        when (this) {
            is Ok -> value
            is Err -> throw AssertionError("expected Ok, got $error")
        }

    private companion object {
        /** A string that appears nowhere else in this app, so a match is proof and not a coincidence. */
        const val CANARY = "ERASE-CANARY-7Q4XJ"

        /** And an amount, in paise, for the same reason — ₹9,87,654.32. */
        const val CANARY_AMOUNT = 98_765_432L

        const val PROFILE = "profile-erase"
        const val CREATED = 1_756_684_800_000L
    }
}

/**
 * Whether this byte array contains [needle].
 * Why:    the canary scan reads raw files — an encrypted database, a protobuf, a cache blob — so
 *         `String(bytes).contains(...)` would depend on the bytes being valid UTF-8 and would
 *         silently stop finding things in the files most worth searching.
 * Result: `true` when the sequence appears. Input: the receiver; [needle]. Output: [Boolean].
 * Changelog: 2026-10-01 — Created for issue 11.4.
 */
private fun ByteArray.containsSequence(needle: ByteArray): Boolean {
    if (needle.isEmpty() || needle.size > size) return false
    return (0..size - needle.size).any { start ->
        needle.indices.all { this[start + it] == needle[it] }
    }
}

/**
 * An audit log that records nothing (issue 11.4).
 * Why:    this test is about what is left on the disk, and the real audit log would need the
 *         database the erase is about to destroy. The repository's own JVM test covers the record.
 * Changelog: 2026-10-01 — Created for issue 11.4.
 */
private object NoOpAuditLog : AuditLogRepository {
    override suspend fun record(
        event: com.aicfo.core.model.AuditEvent,
        method: com.aicfo.core.model.AuditMethod?,
    ): Result<Unit, AppError> = Ok(Unit)

    override fun observeRecent(limit: Int) = kotlinx.coroutines.flow.flowOf(emptyList<AuditEntry>())

    override suspend fun countSince(
        event: com.aicfo.core.model.AuditEvent,
        sinceUtcMillis: Long,
    ): Result<Int, AppError> = Ok(0)
}
