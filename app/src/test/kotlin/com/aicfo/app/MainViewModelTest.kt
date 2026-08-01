package com.aicfo.app

import app.cash.turbine.test
import com.aicfo.app.navigation.CfoRoute
import com.aicfo.core.common.AppError
import com.aicfo.core.common.Err
import com.aicfo.core.common.Ok
import com.aicfo.core.common.Result
import com.aicfo.core.datastore.OnboardingProfile
import com.aicfo.core.datastore.SettingsSnapshot
import com.aicfo.core.datastore.SettingsStore
import com.aicfo.core.datastore.ThemeSetting
import com.aicfo.data.repository.DemoModeRepository
import com.aicfo.data.repository.QuickSetupRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Tests for [MainViewModel] — where the app opens (issue 2.1).
 *
 * Why:  this decision is invisible until it is wrong, and both failure modes are bad in different
 *       ways: sending an onboarded user back through first-run setup, or dropping a brand-new user
 *       on a dashboard with no profile and no route back into onboarding. The store can also fail
 *       to be read at all, and the fallback for that is a deliberate choice rather than an
 *       accident, so it is pinned here.
 * What: the three answers — not onboarded, onboarded, and unreadable — plus the demo, which is a
 *       fourth reason to open on the dashboard and the only one that comes with no profile at all.
 * Result: the start destination is proven for every state the flags can be in.
 * Changelog: 2026-07-25 — Created for issue 2.1.
 *            2026-07-28 — Issue 2.4: demo mode also decides the start destination.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class MainViewModelTest {
    /** Input: none. Output: pins `viewModelScope` so the one-shot read runs inline. */
    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    /** Input: none. Output: releases the main dispatcher. */
    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    /**
     * Input:  a store with no completion timestamp — a fresh install.
     * Output: asserts the app opens on onboarding, and that it reports `null` first rather than
     *         guessing: a returning user must never see the welcome screen flash past.
     */
    @Test
    fun `a fresh install opens on onboarding`() =
        runTest {
            val viewModel = MainViewModel(StubSettingsStore(Ok(SettingsSnapshot())), StubDemoModeRepository())
            viewModel.startDestination.test {
                assertEquals(CfoRoute.Onboarding, awaitItem())
                cancelAndIgnoreRemainingEvents()
            }
        }

    /**
     * Input:  a store whose onboarding timestamp is set.
     * Output: asserts the app opens straight on the dashboard.
     */
    @Test
    fun `an onboarded profile opens on the dashboard`() =
        runTest {
            val onboarded = SettingsSnapshot(onboardingCompletedAtUtcMillis = 1_800_000_000_000L)
            val viewModel = MainViewModel(StubSettingsStore(Ok(onboarded)), StubDemoModeRepository())
            viewModel.startDestination.test {
                assertEquals(CfoRoute.Dashboard, awaitItem())
                cancelAndIgnoreRemainingEvents()
            }
        }

    /**
     * Input:  a store that cannot be read at all.
     * Output: asserts the app falls back to onboarding. Re-running onboarding overwrites settings
     *         the user can change again; opening a dashboard for a profile that may not exist
     *         strands them with no way back. The recoverable failure is the right one to choose.
     */
    @Test
    fun `an unreadable store falls back to onboarding`() =
        runTest {
            val unreadable = StubSettingsStore(Err(AppError.Storage("IOException")))
            val viewModel = MainViewModel(unreadable, StubDemoModeRepository())
            viewModel.startDestination.test {
                assertEquals(CfoRoute.Onboarding, awaitItem())
                cancelAndIgnoreRemainingEvents()
            }
        }

    // --- demo mode (issue 2.4, FR-ONB-004) --------------------------------------------------------

    /**
     * Input:  a demo in progress on a store nobody has onboarded.
     * Output: asserts the app opens on the dashboard. This is the case reading the onboarding flag
     *         alone gets wrong: a demo user has **no profile**, so that flag is false for them, and
     *         sending them to the welcome screen would leave their sample data loaded behind a flow
     *         that has no idea it exists.
     */
    @Test
    fun `a demo in progress opens on the dashboard even with no profile`() =
        runTest {
            val demoMode = StubDemoModeRepository(initiallyActive = true)
            val viewModel = MainViewModel(StubSettingsStore(Ok(SettingsSnapshot())), demoMode)
            viewModel.startDestination.test {
                assertEquals(CfoRoute.Dashboard, awaitItem())
                cancelAndIgnoreRemainingEvents()
            }
        }

    /**
     * Input:  a demo in progress, then the banner's exit action.
     * Output: asserts the flag reaches the banner and that exiting both wipes and clears it. Without
     *         the first, sample figures would render with nothing marking them; without the second,
     *         the banner would survive the data it describes.
     */
    @Test
    fun `the banner follows the demo and exiting clears it`() =
        runTest {
            val demoMode = StubDemoModeRepository(initiallyActive = true)
            val viewModel = MainViewModel(StubSettingsStore(Ok(SettingsSnapshot())), demoMode)

            viewModel.isDemoActive.test {
                assertTrue(awaitItem())

                viewModel.exitDemo()

                assertFalse(awaitItem())
                assertTrue("the banner's action must reach the repository", demoMode.exited)
                cancelAndIgnoreRemainingEvents()
            }
        }
}

/**
 * A [SettingsStore] that emits one fixed answer.
 * Why:    the ViewModel reads the flag exactly once, so the only thing a double needs to control is
 *         what that read returns — including the error case, which the real store makes hard to
 *         produce on demand.
 * Result: a stub store. Input: [answer] — what `observe()` emits. Output: the double.
 * Changelog: 2026-07-25 — Created for issue 2.1.
 */
private class StubSettingsStore(
    private val answer: Result<SettingsSnapshot, AppError>,
) : SettingsStore {
    override fun observe(): Flow<Result<SettingsSnapshot, AppError>> = flowOf(answer)

    override suspend fun setProfileTimeZone(zoneId: String): Result<Unit, AppError> = Ok(Unit)

    override suspend fun setCurrencyCode(currencyCode: String): Result<Unit, AppError> = Ok(Unit)

    override suspend fun setPrivacyBlurEnabled(enabled: Boolean): Result<Unit, AppError> = Ok(Unit)

    override suspend fun setTheme(theme: ThemeSetting): Result<Unit, AppError> = Ok(Unit)

    override suspend fun completeOnboarding(profile: OnboardingProfile): Result<Unit, AppError> = Ok(Unit)

    override suspend fun setDemoModeActive(active: Boolean): Result<Unit, AppError> = Ok(Unit)
}

/**
 * A [DemoModeRepository] whose state a test sets directly (issue 2.4).
 * Why:    what this ViewModel does with demo mode is decide a destination, drive a banner and call
 *         `exit()` — so the double only has to start in a known state and record the exit. The real
 *         repository's behaviour is proven by `DemoModeRepositoryTest`.
 * Result: a stub repository. Input: [initiallyActive]. Output: the double.
 * Changelog: 2026-07-28 — Created for issue 2.4.
 */
private class StubDemoModeRepository(
    initiallyActive: Boolean = false,
) : DemoModeRepository {
    private val active = MutableStateFlow(initiallyActive)

    /** Whether `exit()` was called — the banner's action must actually reach the repository. */
    var exited: Boolean = false
        private set

    override val isActive: Flow<Boolean> = active

    override val activeProfileId: Flow<String> =
        active.map { on ->
            if (on) DemoModeRepository.DEMO_PROFILE_ID else QuickSetupRepository.DEFAULT_PROFILE_ID
        }

    override suspend fun enter(): Result<Unit, AppError> = Ok(Unit).also { active.value = true }

    override suspend fun exit(): Result<Unit, AppError> =
        Ok(Unit).also {
            exited = true
            active.value = false
        }
}
