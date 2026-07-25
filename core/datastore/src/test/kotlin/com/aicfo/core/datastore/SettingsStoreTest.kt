package com.aicfo.core.datastore

import androidx.datastore.core.DataStore
import app.cash.turbine.test
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

/**
 * Tests for the settings store (issue 1.9; §21.3, TIM-001).
 *
 * Why:  one of these settings is not a preference. The profile time zone is what makes "today"
 *       mean the user's today — every day boundary, month rollover and due date resolves through
 *       it (TIM-001), which is why `SystemClock` was built in issue 1.3 to read a zone provider
 *       rather than the device. So "unset" must stay distinguishable from "set to something",
 *       because proto3 cannot tell them apart on its own: an unset string reads as `""`.
 * What: defaults, round-trips, the empty-vs-unset distinction, and that changes are emitted.
 * Result: settings the Clock can be safely wired to in issue 1.10.
 * Changelog: 2026-07-25 — Created for issue 1.9.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SettingsStoreTest {
    @get:Rule
    val folder = TemporaryFolder()

    private val scope = TestScope(UnconfinedTestDispatcher())
    private lateinit var dataStore: DataStore<CfoSettingsProto>
    private lateinit var store: SettingsStore

    private fun open() {
        val file = folder.newFile("settings.pb").also { it.delete() }
        dataStore =
            CfoSettingsStorage.create(file.absolutePath, scope)
        store = DataStoreSettingsStore(dataStore, TestDispatchers(UnconfinedTestDispatcher(scope.testScheduler)))
    }

    /** Input: none. Output: releases the store's scope between tests. */
    @After
    fun tearDown() {
        scope.cancel()
    }

    /**
     * Input:  a fresh store.
     * Output: asserts the defaults — no zone, no currency, blur off, theme follows the system.
     *         A blur that defaulted **on** would hide every figure and look like a broken app;
     *         one that defaulted to a zone would silently misfile transactions.
     */
    @Test
    fun `a fresh store has no zone, no currency and blur off`() =
        scope.runTest {
            open()
            val settings = store.observe().first().getOrNull()!!
            assertNull("unset must stay unset so the Clock can fall back", settings.profileTimeZoneId)
            assertNull(settings.currencyCode)
            assertFalse(settings.privacyBlurEnabled)
            assertEquals(ThemeSetting.SYSTEM, settings.theme)
        }

    /**
     * Input:  each setter in turn.
     * Output: asserts every value round-trips through protobuf unchanged.
     */
    @Test
    fun `every setting round-trips`() =
        scope.runTest {
            open()
            assertWritten(store.setProfileTimeZone("Asia/Kolkata"))
            assertWritten(store.setCurrencyCode("INR"))
            assertWritten(store.setPrivacyBlurEnabled(true))
            assertWritten(store.setTheme(ThemeSetting.DARK))

            val settings = store.observe().first().getOrNull()!!
            assertEquals("Asia/Kolkata", settings.profileTimeZoneId)
            assertEquals("INR", settings.currencyCode)
            assertTrue(settings.privacyBlurEnabled)
            assertEquals(ThemeSetting.DARK, settings.theme)
        }

    /**
     * Input:  a zone that is set and then cleared to the empty string.
     * Output: asserts an empty value reads back as `null`, i.e. "unset". proto3 has no null, so
     *         without this mapping the Clock would be handed `""` as a zone id and throw.
     */
    @Test
    fun `an empty value reads back as unset`() =
        scope.runTest {
            open()
            assertWritten(store.setProfileTimeZone("Europe/London"))
            assertEquals("Europe/London", store.observe().first().getOrNull()!!.profileTimeZoneId)

            assertWritten(store.setProfileTimeZone(""))
            assertNull(store.observe().first().getOrNull()!!.profileTimeZoneId)
        }

    /**
     * Input:  a collector watching while the blur toggle flips.
     * Output: asserts the change is emitted — a settings screen that only reads once would show
     *         stale state after any change made elsewhere.
     */
    @Test
    fun `changes are emitted to an active collector`() =
        scope.runTest {
            open()
            store.observe().test {
                assertFalse(awaitItem().getOrNull()!!.privacyBlurEnabled)

                assertWritten(store.setPrivacyBlurEnabled(true))
                assertTrue(awaitItem().getOrNull()!!.privacyBlurEnabled)

                cancelAndIgnoreRemainingEvents()
            }
        }

    /**
     * Input:  settings and a consent written to the same file.
     * Output: asserts they coexist — they share one store so a single atomic write keeps them
     *         consistent, and this proves one does not clobber the other.
     */
    @Test
    fun `settings and consents share the file without clobbering each other`() =
        scope.runTest {
            open()
            val consents =
                DataStoreConsentStore(
                    dataStore,
                    com.aicfo.core.common.FakeClock(),
                    TestDispatchers(UnconfinedTestDispatcher(scope.testScheduler)),
                )

            assertWritten(store.setCurrencyCode("INR"))
            assertWritten(consents.grant(ConsentFeature.MARKET_DATA))
            assertWritten(store.setTheme(ThemeSetting.LIGHT))

            assertEquals("INR", store.observe().first().getOrNull()!!.currencyCode)
            assertEquals(ThemeSetting.LIGHT, store.observe().first().getOrNull()!!.theme)
            assertTrue(consents.observe(ConsentFeature.MARKET_DATA).first().getOrNull()!!.granted)
        }
}
