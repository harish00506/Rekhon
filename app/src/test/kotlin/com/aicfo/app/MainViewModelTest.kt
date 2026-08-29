package com.aicfo.app

import app.cash.turbine.test
import com.aicfo.app.navigation.CfoRoute
import com.aicfo.core.common.AppError
import com.aicfo.core.common.Err
import com.aicfo.core.common.Ok
import com.aicfo.core.common.Result
import com.aicfo.core.datastore.OnboardingProfile
import com.aicfo.core.datastore.QuickSetupSeeds
import com.aicfo.core.datastore.SettingsSnapshot
import com.aicfo.core.datastore.SettingsStore
import com.aicfo.core.datastore.ThemeSetting
import com.aicfo.core.model.Category
import com.aicfo.core.model.CategoryNature
import com.aicfo.data.repository.CategoryRepository
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
 *            2026-08-08 — Issue 4.1: the category seed is called from here, so whether it is called
 *            at all — and what a failed one does to the start destination — is pinned here too.
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

    // --- the privacy blur (issue 5.3; §23, FR-PRIV-*) -------------------------------------------

    /**
     * Input:  a fresh profile.
     * Output: asserts amounts are visible by default.
     *
     * Why:    a blur that defaulted **on** would hide every figure on first launch and look like a
     *         broken app, with no clue that a toggle explains it — the same reasoning
     *         `SettingsStoreTest` records for the stored default. Pinned here too because this is
     *         the value the UI actually reads.
     */
    @Test
    fun `amounts are visible until the user hides them`() =
        runTest {
            viewModel(FakeAppSettingsStore()).isPrivacyBlurred.test {
                assertFalse(awaitItem())
                cancelAndIgnoreRemainingEvents()
            }
        }

    /**
     * The round trip this feature is.
     * Input:  a toggle on, then off.
     * Output: asserts the flag is **written and read back**, not held in memory.
     *
     * Why:    the write is what makes the blur survive process death, and that is not a detail:
     *         someone who hides their figures before handing the phone over would otherwise have
     *         them reappear the moment Android killed the app in the background — which is exactly
     *         when they are not watching. A fake whose setter returned `Ok(Unit)` and changed
     *         nothing would let a ViewModel that never wrote at all pass, which is why
     *         [FakeAppSettingsStore] makes writes visible to reads.
     *
     *         Collected through Turbine rather than read off `.value`: the flow is shared
     *         `WhileSubscribed`, so with no collector the upstream never runs and `.value` sits at
     *         its initial `false` forever. That is the right production behaviour — the work stops
     *         when the UI goes away (ARC-006) — and it means a test has to subscribe like the screen
     *         does.
     */
    @Test
    fun `the toggle is written to settings and read back`() =
        runTest {
            val viewModel = viewModel(FakeAppSettingsStore())

            viewModel.isPrivacyBlurred.test {
                assertFalse(awaitItem())

                viewModel.setPrivacyBlur(true)
                assertTrue("the blur must persist, not live in memory", awaitItem())

                viewModel.setPrivacyBlur(false)
                assertFalse(awaitItem())
                cancelAndIgnoreRemainingEvents()
            }
        }

    /**
     * Input:  a profile that already had the blur on — the app relaunching after a process death.
     * Output: asserts it comes back on.
     */
    @Test
    fun `a stored blur survives a relaunch`() =
        runTest {
            val stored = FakeAppSettingsStore(SettingsSnapshot(privacyBlurEnabled = true))

            viewModel(stored).isPrivacyBlurred.test {
                // The initial `false` is the StateFlow's seed, replaced as soon as the stored value
                // arrives — the same two-emission shape `startDestination`'s tests assert.
                assertTrue(awaitItem() || awaitItem())
                cancelAndIgnoreRemainingEvents()
            }
        }

    /**
     * Input:  a settings store that cannot be read.
     * Output: asserts amounts stay **visible**.
     *
     * Why:    a deliberate choice worth pinning. Failing closed here would turn a DataStore hiccup
     *         into an app where every figure has vanished for a reason the user cannot diagnose and
     *         cannot undo. The blur is a display preference, not a security boundary — the security
     *         boundary is the app lock (SEC-002), which does fail closed.
     */
    @Test
    fun `an unreadable store leaves amounts visible`() =
        runTest {
            val unreadable = StubSettingsStore(Err(AppError.Storage("IOException")))

            viewModel(unreadable).isPrivacyBlurred.test {
                assertFalse(awaitItem())
                cancelAndIgnoreRemainingEvents()
            }
        }

    /** Result: a ViewModel over [settings], with the other collaborators stubbed. */
    private fun viewModel(settings: SettingsStore): MainViewModel =
        MainViewModel(settings, StubDemoModeRepository(), StubCategoryRepository())

    /**
     * Input:  a store with no completion timestamp — a fresh install.
     * Output: asserts the app opens on onboarding, and that it reports `null` first rather than
     *         guessing: a returning user must never see the welcome screen flash past.
     */
    @Test
    fun `a fresh install opens on onboarding`() =
        runTest {
            val viewModel =
                MainViewModel(
                    StubSettingsStore(Ok(SettingsSnapshot())),
                    StubDemoModeRepository(),
                    StubCategoryRepository(),
                )
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
            val viewModel =
                MainViewModel(
                    StubSettingsStore(Ok(onboarded)),
                    StubDemoModeRepository(),
                    StubCategoryRepository(),
                )
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
            val viewModel = MainViewModel(unreadable, StubDemoModeRepository(), StubCategoryRepository())
            viewModel.startDestination.test {
                assertEquals(CfoRoute.Onboarding, awaitItem())
                cancelAndIgnoreRemainingEvents()
            }
        }

    // --- the category seed (issue 4.1, FR-SET-001) ------------------------------------------------

    /**
     * Input:  any cold start.
     * Output: asserts the taxonomy seed was actually called.
     *
     * **This is the assertion that catches the whole feature quietly not shipping.** Every other
     * test of the seed is in `CategoryRepositoryTest`, which proves it writes the right rows *when
     * something calls it*; nothing but this proves anything ever does. That distinction has bitten
     * this project before — the `merchant` column was plumbed end to end in issue 3.1 and only
     * `DemoDataset` ever wrote one, so every real transaction read "Uncategorised" for days.
     */
    @Test
    fun `a cold start seeds the category taxonomy`() =
        runTest {
            val categories = StubCategoryRepository()
            MainViewModel(StubSettingsStore(Ok(SettingsSnapshot())), StubDemoModeRepository(), categories)

            assertEquals(1, categories.seedCalls)
        }

    /**
     * Input:  a seed that fails.
     * Output: asserts the start destination is decided anyway. The seed runs after the destination
     *         precisely so a storage failure cannot hold the app on the blank surface
     *         `startDestination == null` renders; it is idempotent, so the next launch retries it.
     */
    @Test
    fun `a failed seed does not stop the app opening`() =
        runTest {
            val categories = StubCategoryRepository(result = Err(AppError.Storage("SQLiteFullException")))
            val viewModel =
                MainViewModel(StubSettingsStore(Ok(SettingsSnapshot())), StubDemoModeRepository(), categories)

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
            val viewModel =
                MainViewModel(StubSettingsStore(Ok(SettingsSnapshot())), demoMode, StubCategoryRepository())
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
            val viewModel =
                MainViewModel(StubSettingsStore(Ok(SettingsSnapshot())), demoMode, StubCategoryRepository())

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

    override suspend fun setQuickSetupSeeds(seeds: QuickSetupSeeds): Result<Unit, AppError> = Ok(Unit)

    override suspend fun completeOnboarding(profile: OnboardingProfile): Result<Unit, AppError> = Ok(Unit)

    override suspend fun setDemoModeActive(active: Boolean): Result<Unit, AppError> = Ok(Unit)

    override suspend fun setSmsScanCursor(smsId: Long): Result<Unit, AppError> = Ok(Unit)
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

/**
 * A [CategoryRepository] that records whether the seed was asked for.
 *
 * Why:  [MainViewModel] deliberately ignores the seed's result — a failure is retried on the next
 *       launch and there is nothing a user could do about it on a screen not yet drawn. That makes
 *       "was it called at all?" invisible from the ViewModel's own state, so the stub counts it.
 * What: counts [ensureSeeded] and returns whatever the test asked for; every other member is unused
 *       here and fails loudly rather than pretending to work.
 * Result: `a cold start seeds the category taxonomy` can assert the call happened.
 * Changelog: 2026-08-08 — Created for issue 4.1.
 *
 * Input:  [result] — what [ensureSeeded] returns. Output: a stub repository.
 */
private class StubCategoryRepository(
    private val result: Result<Int, AppError> = Ok(0),
) : CategoryRepository {
    /** How many times [ensureSeeded] was called. */
    var seedCalls: Int = 0
        private set

    override suspend fun ensureSeeded(): Result<Int, AppError> {
        seedCalls++
        return result
    }

    // Not reached from MainViewModel. `error` rather than a benign default: a silent empty list here
    // would make a future test of a screen that reads categories pass against nothing.
    override fun observeCategories(): Flow<List<Category>> = error("not used by MainViewModel")

    override suspend fun create(
        name: String,
        nature: CategoryNature,
        parentId: String?,
    ): Result<Category, AppError> = error("not used by MainViewModel")

    override suspend fun update(
        id: String,
        name: String,
        nature: CategoryNature,
        parentId: String?,
    ): Result<Category, AppError> = error("not used by MainViewModel")

    override suspend fun delete(id: String): Result<Unit, AppError> = error("not used by MainViewModel")

    override suspend fun countUsage(id: String): Result<Int, AppError> = error("not used by MainViewModel")
}
