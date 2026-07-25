package com.aicfo.app.di

import com.aicfo.core.common.AppError
import com.aicfo.core.common.Err
import com.aicfo.core.common.Ok
import com.aicfo.core.common.Result
import com.aicfo.core.datastore.SettingsSnapshot
import com.aicfo.core.datastore.SettingsStore
import com.aicfo.core.datastore.ThemeSetting
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.ZoneId

/**
 * Tests the bridge between the profile setting and the [com.aicfo.core.common.Clock] (TIM-001).
 *
 * Why:  this small class decides what "today" means for the entire app. If it returns the wrong
 *       zone, every day boundary, month rollover and due date shifts — and nothing would look
 *       broken, which is what makes it dangerous. If it *throws*, every engine crashes at once,
 *       because they all call `Clock.zone()` inside ordinary maths. So the tests here are mostly
 *       about the failure paths: unset, unreadable, and unparseable.
 * What: the default before the setting is known, the update after it arrives, and three ways the
 *       stored value can be unusable.
 * Result: the wiring that makes issue 1.3's `Clock` correct in production.
 * Changelog: 2026-07-25 — Created for issue 1.10.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ProfileZoneProviderTest {
    private val kolkata = ZoneId.of("Asia/Kolkata")
    private val london = ZoneId.of("Europe/London")
    private val settings = MutableStateFlow<Result<SettingsSnapshot, AppError>>(Ok(SettingsSnapshot()))

    private fun provider(scope: TestScope) =
        ProfileZoneProvider(
            settingsStore = { FakeSettingsStore(settings) },
            scope = scope,
            systemZone = { london },
        )

    /**
     * Input:  a provider that has not been started.
     * Output: asserts the device zone. Startup reads disk asynchronously, so there is always a
     *         window before the setting is known — it must be a sensible zone, not a crash.
     */
    @Test
    fun `falls back to the device zone before the setting arrives`() =
        runTest {
            val scope = TestScope(UnconfinedTestDispatcher(testScheduler))
            assertEquals(london, provider(scope)())
        }

    /**
     * Input:  a stored zone of `Asia/Kolkata`.
     * Output: asserts the provider switches to it once the collector runs — the whole point of
     *         the wiring.
     */
    @Test
    fun `adopts the stored profile zone`() =
        runTest {
            val scope = TestScope(UnconfinedTestDispatcher(testScheduler))
            val provider = provider(scope)
            provider.start()
            settings.value = Ok(SettingsSnapshot(profileTimeZoneId = "Asia/Kolkata"))

            assertEquals(kolkata, provider())
        }

    /**
     * Input:  the user changing their zone while the app runs.
     * Output: asserts the change is picked up. Reading the setting once at startup would leave a
     *         travelling user's dates wrong until they restarted the app.
     */
    @Test
    fun `follows a later change to the setting`() =
        runTest {
            val scope = TestScope(UnconfinedTestDispatcher(testScheduler))
            val provider = provider(scope)
            provider.start()

            settings.value = Ok(SettingsSnapshot(profileTimeZoneId = "Asia/Kolkata"))
            assertEquals(kolkata, provider())

            settings.value = Ok(SettingsSnapshot(profileTimeZoneId = "America/New_York"))
            assertEquals(ZoneId.of("America/New_York"), provider())
        }

    /**
     * Input:  a stored zone id this JVM does not recognise — a corrupt value, or one written by a
     *         newer build.
     * Output: asserts the device zone, **not** an exception. `ZoneId.of` throws on unknown ids, and
     *         an exception out of `Clock.zone()` would crash every engine in the app at once.
     */
    @Test
    fun `an unparseable zone falls back instead of throwing`() =
        runTest {
            val scope = TestScope(UnconfinedTestDispatcher(testScheduler))
            val provider = provider(scope)
            provider.start()

            settings.value = Ok(SettingsSnapshot(profileTimeZoneId = "Mars/Olympus_Mons"))
            assertEquals(london, provider())
        }

    /**
     * Input:  settings that have never been set.
     * Output: asserts the device zone — `null` means the user has not chosen one yet, which is the
     *         normal state before onboarding (issue 2.1).
     */
    @Test
    fun `an unset zone falls back`() =
        runTest {
            val scope = TestScope(UnconfinedTestDispatcher(testScheduler))
            val provider = provider(scope)
            provider.start()

            settings.value = Ok(SettingsSnapshot(profileTimeZoneId = null, theme = ThemeSetting.DARK))
            assertEquals(london, provider())
        }

    /**
     * Input:  a bad zone id, then a good one.
     * Output: asserts the good one is still applied — i.e. the collector **survived** the bad
     *         value. Without this, the three fallback tests above could all pass while the
     *         collector was dead: a crashed collector leaves the field at its initial device-zone
     *         value, which is exactly what they assert. Found by deliberately removing the
     *         fallback and noticing those tests stayed green.
     */
    @Test
    fun `the collector survives a bad value and still applies the next good one`() =
        runTest {
            val scope = TestScope(UnconfinedTestDispatcher(testScheduler))
            val provider = provider(scope)
            provider.start()

            settings.value = Ok(SettingsSnapshot(profileTimeZoneId = "Mars/Olympus_Mons"))
            assertEquals(london, provider())

            settings.value = Ok(SettingsSnapshot(profileTimeZoneId = "Asia/Kolkata"))
            assertEquals("a bad value must not kill the collector", kolkata, provider())
        }

    /**
     * Input:  a store that cannot be read.
     * Output: asserts the device zone. A storage failure must degrade the app's dates, not stop it
     *         from starting.
     */
    @Test
    fun `an unreadable store falls back`() =
        runTest {
            val scope = TestScope(UnconfinedTestDispatcher(testScheduler))
            val provider = provider(scope)
            provider.start()

            settings.value = Err(AppError.Storage("IOException"))
            assertEquals(london, provider())
        }
}

/**
 * A [SettingsStore] driven by a flow the test controls.
 * Why:    only `observe()` matters here; the setters would be noise.
 * Changelog: 2026-07-25 — Created for issue 1.10.
 */
private class FakeSettingsStore(
    private val flow: Flow<Result<SettingsSnapshot, AppError>>,
) : SettingsStore {
    override fun observe(): Flow<Result<SettingsSnapshot, AppError>> = flow

    override suspend fun setProfileTimeZone(zoneId: String): Result<Unit, AppError> = Ok(Unit)

    override suspend fun setCurrencyCode(currencyCode: String): Result<Unit, AppError> = Ok(Unit)

    override suspend fun setPrivacyBlurEnabled(enabled: Boolean): Result<Unit, AppError> = Ok(Unit)

    override suspend fun setTheme(theme: ThemeSetting): Result<Unit, AppError> = Ok(Unit)
}
