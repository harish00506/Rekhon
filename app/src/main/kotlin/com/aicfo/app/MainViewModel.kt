package com.aicfo.app

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aicfo.app.navigation.CfoRoute
import com.aicfo.core.common.getOrNull
import com.aicfo.core.datastore.SettingsStore
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Decides where the app opens (issue 2.1; ARC-004).
 *
 * Why:  a new install must land on onboarding, and every launch after it on the dashboard — and the
 *       answer lives on disk, so it cannot be known synchronously. The flag is read **once**, not
 *       collected: a `NavHost`'s start destination is fixed when the graph is built, so an
 *       observing version would rebuild the whole graph the instant onboarding completed and throw
 *       away the back stack mid-navigation.
 * What: exposes [startDestination], `null` until the answer is known.
 * Result: the app opens on the right screen without ever flashing the wrong one.
 * Changelog: 2026-07-25 — Created for issue 2.1.
 *
 * Input:  [settingsStore] — holds the onboarding-completion flag.
 * Output: an observable start destination.
 */
@HiltViewModel
class MainViewModel
    @Inject
    constructor(
        settingsStore: SettingsStore,
    ) : ViewModel() {
        private val _startDestination = MutableStateFlow<CfoRoute?>(null)

        /**
         * Where the navigation graph should start.
         * Result: `null` while the stored flag is being read — the UI shows a blank themed surface
         *         rather than guessing, because guessing means a returning user watching the
         *         welcome screen appear and vanish.
         */
        val startDestination: StateFlow<CfoRoute?> = _startDestination.asStateFlow()

        init {
            viewModelScope.launch {
                // A read failure falls back to onboarding: re-running it overwrites settings the
                // user can change again, whereas opening a dashboard for a profile that may not
                // exist strands them with no way back into first-run setup.
                val onboarded = settingsStore.observe().first().getOrNull()?.isOnboarded == true
                _startDestination.value = if (onboarded) CfoRoute.Dashboard else CfoRoute.Onboarding
            }
        }
    }
