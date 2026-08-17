package com.aicfo.app.widget

import androidx.test.core.app.ApplicationProvider
import com.aicfo.app.FakeAppSettingsStore
import com.aicfo.core.common.AppError
import com.aicfo.core.common.Err
import com.aicfo.core.common.Ok
import com.aicfo.core.common.Result
import com.aicfo.core.datastore.OnboardingProfile
import com.aicfo.core.datastore.SettingsSnapshot
import com.aicfo.core.datastore.SettingsStore
import com.aicfo.core.datastore.ThemeSetting
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Proves the privacy blur reaches the home screen (issue 5.5; carried from 5.3, P-01).
 *
 * Why:  issue 5.3 shipped a blur that hides every amount **inside** the app and left one acceptance
 *       criterion unticked, because the surface most exposed to a stranger — the launcher — had no
 *       module yet. This watcher is what closes that, and these tests are what stop it regressing
 *       into a no-op that nobody notices until someone's balance is on a home screen in a meeting.
 *
 *       Three behaviours here are not obvious from reading the five lines they test:
 *
 *       **The first emission publishes.** Unlike `SmsConsentWatcher`, which fires only on a
 *       granted → revoked edge because purging is destructive, this writes a boolean — and a cold
 *       start is exactly when the widget's stored flag may disagree with settings, because the
 *       process was dead when the user last changed it. Skipping the first emission would be the
 *       bug, not the optimisation.
 *
 *       **An unreadable store publishes `false`.** The blur is a display preference; the security
 *       boundary is the app lock, which fails closed separately (ADR-0022). Masking the widget on a
 *       transient DataStore hiccup would train the user to distrust it.
 *
 *       **Repeats do not republish.** Every publish redraws every placed widget; re-emitting the
 *       same value would redraw for nothing.
 * What: the cold start, the toggle in both directions, the repeat, and the read failure.
 * Result: the widget's mask follows the app's, from whichever screen flips the flag.
 * Changelog: 2026-08-17 — Created for issue 5.5.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class WidgetBlurWatcherTest {
    @Test
    fun `a cold start publishes the stored flag`() =
        runTest {
            // The process was dead when the user last toggled. Nothing else will tell the widget.
            val settings = FakeAppSettingsStore(SettingsSnapshot(privacyBlurEnabled = true))
            val watcher = watch(settings)

            assertEquals(listOf(true), watcher.published)
        }

    @Test
    fun `turning the blur on masks the widget`() =
        runTest {
            val settings = FakeAppSettingsStore()
            val watcher = watch(settings)

            settings.setPrivacyBlurEnabled(true)

            assertEquals(listOf(false, true), watcher.published)
        }

    @Test
    fun `turning it off unmasks again`() =
        runTest {
            val settings = FakeAppSettingsStore(SettingsSnapshot(privacyBlurEnabled = true))
            val watcher = watch(settings)

            settings.setPrivacyBlurEnabled(false)

            assertEquals(listOf(true, false), watcher.published)
        }

    @Test
    fun `writing the same value again does not redraw`() =
        runTest {
            // distinctUntilChanged. Each publish redraws every placed widget, so a settings write
            // that did not change this flag must not reach the launcher.
            val settings = FakeAppSettingsStore()
            val watcher = watch(settings)

            settings.setPrivacyBlurEnabled(false)
            settings.setDemoModeActive(true)

            assertEquals(listOf(false), watcher.published)
        }

    @Test
    fun `an unreadable store leaves the widget unmasked`() =
        runTest {
            // Matches MainViewModel: a read failure is not a request to hide. The security boundary
            // is the app lock, and it fails closed on its own (ADR-0022).
            val watcher = watch(BrokenSettingsStore())

            assertEquals(listOf(false), watcher.published)
        }

    /**
     * Result: a started watcher whose publishes this test can read.
     * Input:  [settings] — the store to watch. Output: the recording watcher.
     */
    private fun TestScope.watch(settings: SettingsStore): RecordingWidgetBlurWatcher =
        RecordingWidgetBlurWatcher(
            settings = settings,
            scope = TestScope(UnconfinedTestDispatcher(testScheduler)),
        ).also { it.start() }
}

/**
 * A [WidgetBlurWatcher] that records instead of drawing (issue 5.5).
 *
 * Why:    writing Glance state needs a placed widget and an `AppWidgetHost`, neither of which
 *         exists on the JVM — so without this seam a test could start the watcher and assert
 *         nothing about what reached the launcher. Overriding the one `open` method keeps the whole
 *         collector, including `distinctUntilChanged` and the read-failure mapping, under test.
 * Result: the ordered list of flags the watcher tried to publish.
 * Changelog: 2026-08-17 — Created for issue 5.5.
 *
 * Input:  [settings], [scope] — as the real watcher. Output: a recording watcher.
 */
private class RecordingWidgetBlurWatcher(
    settings: SettingsStore,
    scope: CoroutineScope,
) : WidgetBlurWatcher(settings, ApplicationProvider.getApplicationContext(), scope) {
    /** Every flag published, in order. The order is asserted, not just the last value. */
    val published: MutableList<Boolean> = mutableListOf()

    override suspend fun publish(blurred: Boolean) {
        published += blurred
    }
}

/**
 * A [SettingsStore] whose reads always fail (issue 5.5).
 *
 * Why:    `FakeAppSettingsStore` cannot model this — it always succeeds, which is the right default
 *         for every other test in this module. The failure path has its own rule (unreadable means
 *         *not* blurred) and that rule needs its own double.
 * Result: an `Err` on every emission.
 * Changelog: 2026-08-17 — Created for issue 5.5.
 */
private class BrokenSettingsStore : SettingsStore {
    private val state = MutableStateFlow<Result<SettingsSnapshot, AppError>>(Err(AppError.Storage("disk")))

    override fun observe(): Flow<Result<SettingsSnapshot, AppError>> = state

    override suspend fun setProfileTimeZone(zoneId: String): Result<Unit, AppError> = Ok(Unit)

    override suspend fun setCurrencyCode(currencyCode: String): Result<Unit, AppError> = Ok(Unit)

    override suspend fun setPrivacyBlurEnabled(enabled: Boolean): Result<Unit, AppError> = Ok(Unit)

    override suspend fun setTheme(theme: ThemeSetting): Result<Unit, AppError> = Ok(Unit)

    override suspend fun completeOnboarding(profile: OnboardingProfile): Result<Unit, AppError> = Ok(Unit)

    override suspend fun setDemoModeActive(active: Boolean): Result<Unit, AppError> = Ok(Unit)

    override suspend fun setSmsScanCursor(smsId: Long): Result<Unit, AppError> = Ok(Unit)
}
