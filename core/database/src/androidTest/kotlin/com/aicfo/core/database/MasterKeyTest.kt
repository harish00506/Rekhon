package com.aicfo.core.database

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.aicfo.core.common.getOrNull
import com.aicfo.core.database.entity.ProfileEntity
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.security.KeyStore

/**
 * The master key this app's key path hangs from (issue 11.1; SEC-003, §23).
 *
 * Why:  issue 11.1 changed who creates that key: it used to be Tink, and it is now the platform's
 *       own `KeyGenerator`, asked first so that StrongBox can be requested. That is a small change
 *       with one large risk — **if the spec we generate is not one Tink can use, the app cannot
 *       open its own database**, and no JVM test can tell us, because the Android Keystore exists
 *       only on a device. So this test does the thing no unit test can: it removes the key
 *       entirely, opens the database from nothing, and reads a row back.
 * What: the fresh-install path, the upgrade path, and what the platform actually offers.
 * Result: proof that a key we generated is a key Tink accepts.
 * Changelog: 2026-09-28 — Created for issue 11.1.
 *
 * **On StrongBox:** an emulator does not have it, so the fallback is what runs here. The test
 * reports which path it took rather than asserting one, because asserting StrongBox would fail on
 * every device that does not have it — including CI. ADR-0057 records that the StrongBox branch
 * itself is therefore unproven on hardware.
 */
@RunWith(AndroidJUnit4::class)
class MasterKeyTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    /** Input: none. Output: no database, no passphrase and no master key — a first run. */
    @Before
    fun setUp() {
        deleteDatabaseFiles()
        deleteMasterKey()
    }

    /** Input: none. Output: the database files are removed; the key is left for the next test. */
    @After
    fun tearDown() = deleteDatabaseFiles()

    @Test
    fun a_fresh_install_generates_a_key_Tink_can_actually_use() =
        runTest {
            val database = CfoDatabaseFactory.open(context).getOrNull() ?: error("database failed to open")

            try {
                database.profileDao().upsert(profile)
                assertEquals(profile.displayName, database.profileDao().findById(profile.id)?.displayName)
            } finally {
                database.close()
            }
            assertTrue("no master key was created", keyExists())
        }

    @Test
    fun a_second_open_keeps_the_key_it_already_has() =
        runTest {
            // The upgrade case, which is the one that can destroy data: replacing the key would
            // orphan Tink's keyset, the wrapped passphrase and every row behind it.
            CfoDatabaseFactory.open(context).getOrNull()?.also { it.close() } ?: error("first open failed")
            val first = keyFingerprint()

            val second = CfoDatabaseFactory.open(context).getOrNull()
            second?.close()

            assertNotNull("the second open failed", second)
            assertEquals("the master key was replaced on an ordinary open", first, keyFingerprint())
        }

    @Test
    fun the_device_reports_whether_it_has_StrongBox() {
        // Not an assertion about the hardware — a record of which branch ran, so a failure on a
        // StrongBox phone can be read against a run that had none.
        val advertised =
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.P &&
                context.packageManager.hasSystemFeature(PackageManager.FEATURE_STRONGBOX_KEYSTORE)

        println("StrongBox advertised on this device: $advertised (API ${Build.VERSION.SDK_INT})")
        assertTrue("the key path must work either way", CfoDatabaseFactory.open(context).getOrNull() != null)
    }

    // --- helpers ------------------------------------------------------------------------------

    private val profile =
        ProfileEntity(
            id = "profile-master-key",
            displayName = "Master key",
            timeZoneId = "Asia/Kolkata",
            currencyCode = "INR",
            createdAtUtcMillis = 1_767_225_600_000L,
            updatedAtUtcMillis = 1_767_225_600_000L,
        )

    /** Result: whether the app's master key is in the Keystore. Input: none. Output: [Boolean]. */
    private fun keyExists(): Boolean = keystore().containsAlias(MASTER_KEY_ALIAS)

    /**
     * Result: something that changes if the key is replaced, without exporting it — a Keystore key
     *         cannot be read out, which is the entire point, so its creation date stands in.
     * Input:  none. Output: [String].
     */
    private fun keyFingerprint(): String = keystore().getCreationDate(MASTER_KEY_ALIAS)?.time.toString()

    private fun keystore(): KeyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }

    /**
     * Removes the master key, which is what makes this a *fresh install* rather than a re-open.
     * Result: the alias is gone. Input: none. Output: none.
     */
    private fun deleteMasterKey() {
        keystore().deleteEntry(MASTER_KEY_ALIAS)
        // Tink's keyset is encrypted with that key, so it is now unusable. Leaving it behind would
        // make the next open fail on a stale keyset rather than exercise the fresh-install path.
        context.getSharedPreferences(KEYSET_PREF_FILE, Context.MODE_PRIVATE).edit().clear().commit()
    }

    private fun deleteDatabaseFiles() {
        val base = context.getDatabasePath(CfoDatabase.FILE_NAME)
        listOf(base, File(base.path + "-wal"), File(base.path + "-shm")).forEach { it.delete() }
        File(context.filesDir, "cfo-db-passphrase.bin").delete()
        File(context.filesDir, "cfo-db-passphrase.bin.pending").delete()
    }

    private companion object {
        /** Must match `KeystoreAeadFactory`. A drift here would silently test a different key. */
        const val MASTER_KEY_ALIAS = "cfo_db_master_key"
        const val KEYSET_PREF_FILE = "cfo_db_keyset_prefs"
    }
}
