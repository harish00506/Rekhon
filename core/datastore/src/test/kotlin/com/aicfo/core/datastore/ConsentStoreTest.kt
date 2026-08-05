package com.aicfo.core.datastore

import androidx.datastore.core.DataStore
import app.cash.turbine.test
import com.aicfo.core.common.Err
import com.aicfo.core.common.FakeClock
import com.aicfo.core.common.TestDispatchers
import com.aicfo.core.common.getOrNull
import com.aicfo.core.datastore.proto.CfoSettingsProto
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.time.Duration

/**
 * Tests for the consent ledger (P-01) — the rule the whole product is sold on.
 *
 * Why:  a consent bug is not a normal bug. Defaulting the wrong way means the app does something
 *       with a user's financial data that they never agreed to, and neither they nor anyone else
 *       would notice. So the cases that matter most here are the boring ones: what an *absent*
 *       record means, what an *unknown* feature means, and whether a revocation actually sticks.
 * What: defaults, grant/revoke round-trips with fixed timestamps, re-granting, the full dashboard
 *       view, and the corrupt-file path.
 * Result: "absence is never consent" is a tested property, not a comment.
 * Changelog: 2026-07-25 — Created for issue 1.9.
 *
 * Runs entirely on the JVM: `DataStoreFactory` over a temp file needs no Android, which is what
 * lets these run on a machine with no emulator.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ConsentStoreTest {
    @get:Rule
    val folder = TemporaryFolder()

    private val clock = FakeClock()
    private val scope = TestScope(UnconfinedTestDispatcher())
    private lateinit var dataStore: DataStore<CfoSettingsProto>
    private lateinit var store: ConsentStore

    private fun open(file: File = folder.newFile("consents.pb")) {
        dataStore =
            CfoSettingsStorage.create(file.also { it.delete() }.absolutePath, scope)
        store = DataStoreConsentStore(dataStore, clock, TestDispatchers(UnconfinedTestDispatcher(scope.testScheduler)))
    }

    /** Input: none. Output: releases the store's scope between tests. */
    @After
    fun tearDown() {
        scope.cancel()
    }

    // --- absence is never consent ------------------------------------------------------------

    /**
     * Input:  a brand-new store.
     * Output: asserts **every** feature reads as not granted. This is the single most important
     *         assertion in the module: a fresh install must do nothing with the user's data.
     */
    @Test
    fun `a fresh store grants nothing`() =
        scope.runTest {
            open()
            ConsentFeature.entries.forEach { feature ->
                val state = store.observe(feature).first().getOrNull()
                assertEquals("$feature must default to not granted", ConsentState.NOT_GRANTED, state)
            }
        }

    /**
     * Input:  a store where one feature was granted.
     * Output: asserts the *other* features are unaffected — consent is per feature (P-01), so
     *         agreeing to SMS parsing must never imply agreeing to a cloud model.
     */
    @Test
    fun `granting one feature does not grant another`() =
        scope.runTest {
            open()
            assertWritten(store.grant(ConsentFeature.SMS_PARSING))

            assertTrue(store.observe(ConsentFeature.SMS_PARSING).first().getOrNull()!!.granted)
            assertFalse(store.observe(ConsentFeature.CLOUD_LLM).first().getOrNull()!!.granted)
            assertFalse(store.observe(ConsentFeature.MARKET_DATA).first().getOrNull()!!.granted)
        }

    // --- grant, revoke, re-grant ----------------------------------------------------------------

    /**
     * Input:  a grant at a fixed instant.
     * Output: asserts the state and its timestamp, taken from the injected clock (TIM-001) — the
     *         app must be able to answer "since when?".
     */
    @Test
    fun `granting records the time it was agreed`() =
        scope.runTest {
            open()
            assertWritten(store.grant(ConsentFeature.MARKET_DATA))

            val state = store.observe(ConsentFeature.MARKET_DATA).first().getOrNull()!!
            assertTrue(state.granted)
            assertEquals(clock.nowUtcMillis(), state.grantedAtUtcMillis)
            assertNull("nothing has been revoked yet", state.revokedAtUtcMillis)
        }

    /**
     * Input:  a grant, then a revocation an hour later.
     * Output: asserts the feature is off **and** both timestamps survive. Deleting the record
     *         would lose the audit trail P-01 depends on.
     */
    @Test
    fun `revoking turns the feature off and keeps the history`() =
        scope.runTest {
            open()
            assertWritten(store.grant(ConsentFeature.CLOUD_BACKUP))
            val grantedAt = clock.nowUtcMillis()
            clock.advanceBy(Duration.ofHours(1))
            assertWritten(store.revoke(ConsentFeature.CLOUD_BACKUP))

            val state = store.observe(ConsentFeature.CLOUD_BACKUP).first().getOrNull()!!
            assertFalse("a revoked feature must not run", state.granted)
            assertEquals(grantedAt, state.grantedAtUtcMillis)
            assertEquals(clock.nowUtcMillis(), state.revokedAtUtcMillis)
        }

    /**
     * Input:  grant, revoke, then grant again.
     * Output: asserts the new grant is clean — granted, with a fresh timestamp and no stale
     *         revocation hanging off it, which would read as "granted and revoked at once".
     */
    @Test
    fun `re-granting starts a fresh decision`() =
        scope.runTest {
            open()
            assertWritten(store.grant(ConsentFeature.CLOUD_LLM))
            clock.advanceBy(Duration.ofHours(1))
            assertWritten(store.revoke(ConsentFeature.CLOUD_LLM))
            clock.advanceBy(Duration.ofDays(30))
            assertWritten(store.grant(ConsentFeature.CLOUD_LLM))

            val state = store.observe(ConsentFeature.CLOUD_LLM).first().getOrNull()!!
            assertTrue(state.granted)
            assertEquals(clock.nowUtcMillis(), state.grantedAtUtcMillis)
            assertNull("the previous revocation must not linger on a new grant", state.revokedAtUtcMillis)
        }

    // --- the signal reaches collectors -----------------------------------------------------------

    /**
     * Input:  a collector watching a feature while it is granted and then revoked.
     * Output: asserts both changes are **emitted**. A consent read once at startup cannot be
     *         revoked — the feature would keep running until the app restarted, which is not what
     *         a user means when they turn something off.
     */
    @Test
    fun `revocation is emitted to an active collector`() =
        scope.runTest {
            open()
            store.observe(ConsentFeature.SMS_PARSING).test {
                assertFalse(awaitItem().getOrNull()!!.granted)

                assertWritten(store.grant(ConsentFeature.SMS_PARSING))
                assertTrue(awaitItem().getOrNull()!!.granted)

                assertWritten(store.revoke(ConsentFeature.SMS_PARSING))
                assertFalse("the collector must see the revocation", awaitItem().getOrNull()!!.granted)

                cancelAndIgnoreRemainingEvents()
            }
        }

    /**
     * Input:  a store with one granted feature.
     * Output: asserts the dashboard view lists **every** known feature, so the consents screen
     *         cannot silently omit one it forgot to query — an unlisted consent looks like a
     *         feature with no gate at all.
     */
    @Test
    fun `observeAll covers every known feature`() =
        scope.runTest {
            open()
            assertWritten(store.grant(ConsentFeature.MARKET_DATA))

            val all = store.observeAll().first().getOrNull()!!
            assertEquals(ConsentFeature.entries.toSet(), all.keys)
            assertTrue(all[ConsentFeature.MARKET_DATA]!!.granted)
            assertFalse(all[ConsentFeature.SMS_PARSING]!!.granted)
        }

    // --- failure -----------------------------------------------------------------------------------

    /**
     * Input:  a file that is not valid protobuf.
     * Output: asserts `Err(Storage)` rather than a defaults-shaped store. Silently resetting would
     *         discard every consent decision the user made, and they would never be told.
     */
    @Test
    fun `a corrupt file surfaces as a storage error, not as defaults`() =
        scope.runTest {
            val corrupt = folder.newFile("corrupt.pb")
            corrupt.writeBytes(byteArrayOf(9, 9, 9, 9, 9, 9, 9, 9))
            dataStore =
                CfoSettingsStorage.create(corrupt.absolutePath, scope)
            store =
                DataStoreConsentStore(
                    dataStore,
                    clock,
                    TestDispatchers(UnconfinedTestDispatcher(scope.testScheduler)),
                )

            val result = store.observe(ConsentFeature.SMS_PARSING).first()
            assertTrue("a corrupt store must not read as a valid empty one", result is Err)
            assertEquals("storage", (result as Err).error.code)
        }
}
