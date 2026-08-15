package com.aicfo.app

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aicfo.app.navigation.CfoRoute
import com.aicfo.core.common.getOrNull
import com.aicfo.core.datastore.SettingsStore
import com.aicfo.data.repository.CategoryRepository
import com.aicfo.data.repository.DemoModeRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Decides where the app opens, and owns the demo banner (issue 2.1, 2.4; ARC-004).
 *
 * Why:  a new install must land on onboarding, and every launch after it on the dashboard — and the
 *       answer lives on disk, so it cannot be known synchronously. The flag is read **once**, not
 *       collected: a `NavHost`'s start destination is fixed when the graph is built, so an
 *       observing version would rebuild the whole graph the instant onboarding completed and throw
 *       away the back stack mid-navigation.
 *
 *       Demo mode is the second thing that decides it (issue 2.4), and for the reason FR-ONB-004
 *       makes explicit: a demo user has **no profile**. Reading only the onboarding flag would send
 *       someone who backgrounded the app mid-demo back to the welcome screen, with their sample data
 *       still sitting in the database and no banner to explain it.
 * What: exposes [startDestination], [isDemoActive], and the way out of the demo; and seeds the
 *       category taxonomy on the way past.
 * Result: the app opens on the right screen without ever flashing the wrong one, and never shows
 *       fabricated figures without saying so.
 * Changelog: 2026-07-25 — Created for issue 2.1.
 *            2026-07-28 — Issue 2.4: demo mode decides the start destination and drives the banner.
 *            2026-08-08 — Issue 4.1: calls `CategoryRepository.ensureSeeded` at cold start.
 *
 * **Why the category seed is called from here, of all places.** A profile with no categories cannot
 * use FR-TXN-002's "amount → category suggestion → save", and the three obvious seeding sites all
 * miss a path: `OnboardingWriter` only touches DataStore, `QuickSetupRepository.applySeeds` returns
 * early for a user who skipped quick setup, and seeding from the editor would leave the add screen
 * empty for anyone who never opens it. This is the one place that runs on every cold start whatever
 * the user did, so one idempotent call here covers all three — and the profiles that were onboarded
 * before issue 4.1 existed. It is a startup side effect in a navigation ViewModel, which is not
 * tidy; the alternative was four call sites that each have to remember.
 *
 * **Its result is deliberately not surfaced**, for the same reason [exitDemo]'s is not: the seed is
 * idempotent, so a failed write is retried on the next launch, and there is no action a user could
 * take about it on a screen that has not been drawn yet.
 *
 * Input:  [settingsStore] — holds the onboarding-completion flag; [demoMode] — the demo flag and
 *         the wipe; [categories] — the taxonomy store, for the seed.
 * Output: an observable start destination and demo state.
 */
@HiltViewModel
class MainViewModel
    @Inject
    constructor(
        settingsStore: SettingsStore,
        private val demoMode: DemoModeRepository,
        private val categories: CategoryRepository,
    ) : ViewModel() {
        private val _startDestination = MutableStateFlow<CfoRoute?>(null)

        /**
         * Where the navigation graph should start.
         * Result: `null` while the stored flags are being read — the UI shows a blank themed surface
         *         rather than guessing, because guessing means a returning user watching the
         *         welcome screen appear and vanish.
         */
        val startDestination: StateFlow<CfoRoute?> = _startDestination.asStateFlow()

        /**
         * Whether the app is showing the sample dataset (issue 2.4, FR-ONB-004).
         *
         * Why:    collected rather than read once, unlike [startDestination]: the demo can be
         *         entered and left while the app is open, and the banner has to appear and disappear
         *         with it. `WhileSubscribed` so the collector stops with the UI (ARC-006).
         * Result: `true` for exactly as long as the demo data is loaded.
         */
        val isDemoActive: StateFlow<Boolean> =
            demoMode.isActive.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS), false)

        init {
            viewModelScope.launch {
                // A read failure falls back to onboarding: re-running it overwrites settings the
                // user can change again, whereas opening a dashboard for a profile that may not
                // exist strands them with no way back into first-run setup.
                val onboarded = settingsStore.observe().first().getOrNull()?.isOnboarded == true
                // Demo first: someone who left the app mid-demo has no profile, so the onboarding
                // flag is false for them and reading it alone would send them back to the welcome
                // screen with their sample data still loaded.
                val inDemo = demoMode.isActive.first()
                _startDestination.value =
                    if (onboarded || inDemo) CfoRoute.Dashboard else CfoRoute.Onboarding
                // Issue 4.1: after the destination, never before it — seeding is not what the user is
                // waiting for, and a slow or failed write must not hold the app on a blank surface.
                categories.ensureSeeded()
            }
        }

        /**
         * Leaves the demo and erases its data (issue 2.4; FR-ONB-004).
         * Why:    the wipe is the repository's job; what belongs here is only that the UI has one
         *         entry point for it. The result is deliberately **not** surfaced as an error state:
         *         the banner and the navigation are driven by the flag, which is cleared first, so a
         *         failed wipe leaves orphan rows that the next entry replaces rather than a user
         *         stuck inside a demo they asked to leave.
         * Result: `isDemoActive` goes false and the demo rows are gone.
         * Input:  none. Output: none (launches on `viewModelScope`).
         */
        fun exitDemo() {
            viewModelScope.launch { demoMode.exit() }
        }

        private companion object {
            /** Long enough to survive a configuration change without restarting the collector. */
            const val STOP_TIMEOUT_MILLIS = 5_000L
        }
    }
