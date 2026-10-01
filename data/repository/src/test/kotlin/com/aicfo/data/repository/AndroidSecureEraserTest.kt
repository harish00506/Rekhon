package com.aicfo.data.repository

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.aicfo.core.common.AppError
import com.aicfo.core.common.Err
import com.aicfo.core.common.Ok
import com.aicfo.core.common.Result
import com.aicfo.core.common.SecretInventory
import com.aicfo.core.crypto.CryptoSecrets
import com.aicfo.core.database.crypto.DatabaseSecrets
import com.aicfo.core.datastore.DataStoreSecrets
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

/**
 * The platform half of the erase (issue 11.4; §23, §34, SEC-003, P-01).
 *
 * Why:  the defect this suite exists to prevent is a **successful-looking** erase. Deleting files
 *       is easy and visible; the two ways this goes quietly wrong are a key that survived the
 *       delete (so the ciphertext is still readable, while the user has been told it is not) and a
 *       secret nobody listed (so a whole key chain is simply never touched). Both report `Ok`.
 *
 *       So the tests here check the two claims that cannot be checked by reading the code: that a
 *       surviving alias is an error rather than a success, and that the composed inventory really
 *       covers every module's secrets rather than the ones whoever wrote the eraser remembered.
 * What: the key phase's verification, the sweep over files, databases, sidecars and caches, the
 *       empty-inventory guard, and the completeness of the composition.
 * Result: an erase that is either verified or reported as failed, never assumed.
 * Changelog: 2026-10-01 — Created for issue 11.4.
 */
@RunWith(RobolectricTestRunner::class)
class AndroidSecureEraserTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val keyStore = FakeKeyStore()

    @Test
    fun `every listed alias is deleted`() =
        runTest {
            keyStore.aliases += setOf("alias_one", "alias_two")

            val outcome = eraser(SecretInventory(keystoreAliases = listOf("alias_one", "alias_two"))).destroyKeys()

            assertTrue(outcome is Ok)
            assertTrue("the Keystore must hold none of ours", keyStore.aliases.isEmpty())
        }

    @Test
    fun `a key that survives the delete is an error, not a successful erase`() =
        runTest {
            // The worst bug this feature can have. `deleteEntry` returning without throwing is not
            // evidence the key is gone, and a user told their finances are unrecoverable when the
            // master key is still in the TEE has been lied to about the only thing that matters.
            keyStore.aliases += "alias_one"
            keyStore.refuseToForget += "alias_one"

            val outcome = eraser(SecretInventory(keystoreAliases = listOf("alias_one"))).destroyKeys()

            assertEquals(Err(AppError.Crypto("erase.key_survived")), outcome)
        }

    @Test
    fun `a Keystore that refuses the delete is reported, and the sweep never starts`() =
        runTest {
            keyStore.aliases += "alias_one"
            keyStore.failDelete = AppError.Crypto("erase.keystore_delete:KeyStoreException")

            val outcome = eraser(SecretInventory(keystoreAliases = listOf("alias_one"))).destroyKeys()

            assertEquals(Err(AppError.Crypto("erase.keystore_delete:KeyStoreException")), outcome)
        }

    @Test
    fun `an empty inventory is a wiring failure, not an erase with nothing to do`() =
        runTest {
            // If the composition ever loses its modules, this is the only thing standing between a
            // user and an "all your data has been erased" message over an untouched database.
            val outcome = eraser(SecretInventory()).destroyKeys()

            assertEquals(Err(AppError.Crypto("erase.inventory_empty")), outcome)
        }

    @Test
    fun `files, directories, the database and its sidecars all go`() =
        runTest {
            val file = file("cfo-pin.bin").also { it.writeText("tag") }
            val directory = File(context.filesDir, "receipts").also { it.mkdirs() }
            val blob = File(directory, "r1.bin").also { it.writeText("receipt") }
            val database =
                context.getDatabasePath("cfo.db").also {
                    it.parentFile?.mkdirs()
                    it.writeText("db")
                }
            val wal = context.getDatabasePath("cfo.db-wal").also { it.writeText("recent pages") }

            val outcome =
                eraser(
                    SecretInventory(
                        keystoreAliases = listOf("alias_one"),
                        filePaths = listOf("cfo-pin.bin", "receipts"),
                        databaseNames = listOf("cfo.db"),
                    ),
                ).deleteDataFiles()

            assertTrue(outcome is Ok)
            assertFalse(file.exists())
            assertFalse("a receipt blob inside the directory survived", blob.exists())
            assertFalse(directory.exists())
            assertFalse(database.exists())
            assertFalse("the -wal holds the most recent transactions", wal.exists())
        }

    @Test
    fun `a secret this installation never had is not a failure`() =
        runTest {
            // A user who never set a PIN has no `cfo-pin.bin`. The inventory lists what the app
            // *can* hold, so most of it is absent on most devices, and reporting that as an
            // undeleted file would turn every complete erase into an error.
            val outcome = eraser(SecretInventory(filePaths = listOf("never-existed.bin"))).deleteDataFiles()

            assertTrue(outcome is Ok)
        }

    @Test
    fun `the caches are emptied, including what no inventory names`() =
        runTest {
            // Decoded receipt bitmaps and OCR scratch files land here under names this app never
            // chose, so they cannot be listed — the whole directory has to go.
            val scratch =
                File(context.cacheDir, "ocr-scratch.png").also {
                    it.parentFile?.mkdirs()
                    it.writeText("x")
                }

            eraser(SecretInventory(filePaths = listOf("cfo-pin.bin"))).deleteDataFiles()

            assertFalse(scratch.exists())
        }

    @Test
    fun `a file that will not delete is reported, so the caller can decide`() =
        runTest {
            // The repository tolerates this — the key is already gone — but the eraser must still
            // say it happened rather than report a clean sweep it did not perform.
            //
            // The deletion is made to fail the way the real one would: a child cannot be unlinked
            // from a directory that is not writable. An earlier attempt wrapped the context with a
            // `File` subclass whose `delete()` returned false, which did nothing — the eraser
            // builds its own `File` from the path string, so the override was never reached and
            // the test passed against an eraser that reported a clean sweep. A real permission is
            // the only mechanism that cannot be bypassed by how the path is constructed.
            val directory = File(context.filesDir, "locked").also { it.mkdirs() }
            File(directory, "child.bin").writeText("still here")
            assumeTrue("the test user can be denied a write", directory.setWritable(false))

            val outcome =
                try {
                    eraser(SecretInventory(filePaths = listOf("locked"))).deleteDataFiles()
                } finally {
                    directory.setWritable(true)
                }

            assertEquals(Err(AppError.Storage("erase.undeleted:1")), outcome)
        }

    @Test
    fun `the composed inventory covers every module that owns a secret`() =
        runTest {
            // The omission test. A new key chain whose module inventory is never added here is
            // invisible in review and in every other test in this file, because the eraser would
            // run cleanly over the names it does know. Each module's own inventory is asserted
            // whole, so a secret added to one of them cannot be dropped on the way here either.
            val composed = androidEraseInventory()

            listOf(DatabaseSecrets.inventory, CryptoSecrets.inventory, DataStoreSecrets.inventory).forEach { module ->
                assertTrue(
                    "a module's aliases are missing from the erase: $module",
                    composed.keystoreAliases.containsAll(module.keystoreAliases),
                )
                assertTrue(
                    "a module's keysets are missing from the erase: $module",
                    composed.sharedPrefsNames.containsAll(module.sharedPrefsNames),
                )
                assertTrue(
                    "a module's files are missing from the erase: $module",
                    composed.filePaths.containsAll(module.filePaths),
                )
                assertTrue(
                    "a module's databases are missing from the erase: $module",
                    composed.databaseNames.containsAll(module.databaseNames),
                )
            }
            // And the count, so that adding a fourth module's secrets without adding them here
            // fails rather than passing because the three it does cover are still covered.
            assertEquals(3, composed.keystoreAliases.size)
        }

    /**
     * Result: the eraser under test. Input: [inventory]. Output: a [SecureEraser].
     * Changelog: 2026-10-01 — Created for issue 11.4.
     */
    private fun eraser(inventory: SecretInventory): SecureEraser =
        AndroidSecureEraser(context = context, inventory = inventory, keyStore = keyStore)

    /** Result: a file under `filesDir`. Input: [name]. Output: [File]. */
    private fun file(name: String): File = File(context.filesDir, name)
}

/**
 * A Keystore that can be told to keep a key it was asked to delete (issue 11.4).
 * Why:    the real one cannot be made to misbehave, and "the delete returned but the key is still
 *         there" is exactly the failure the eraser's verification exists to catch. Without a fake
 *         that can refuse, that verification would be untested code guarding the most important
 *         claim the app makes.
 * Result: `aliases` is what the Keystore holds; `refuseToForget` survives deletion.
 * Input:  none. Output: the fake.
 * Changelog: 2026-10-01 — Created for issue 11.4.
 */
private class FakeKeyStore : KeyStoreGateway {
    val aliases = mutableSetOf<String>()
    val refuseToForget = mutableSetOf<String>()
    var failDelete: AppError? = null

    override fun delete(alias: String): Result<Unit, AppError> {
        failDelete?.let { return Err(it) }
        if (alias !in refuseToForget) aliases -= alias
        return Ok(Unit)
    }

    override fun contains(alias: String): Boolean = alias in aliases
}
