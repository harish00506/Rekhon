package com.aicfo.app

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aicfo.core.common.getOrNull
import com.aicfo.core.datastore.LanguageSetting
import com.aicfo.core.datastore.SettingsStore
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

/**
 * Which language the app speaks, read before anything else (issue 10.8; SRS §3.5, SEC-002).
 *
 * Why:  the language has to be known **above the app lock**, because the lock screen is the first
 *       thing a user sees and asking for a PIN in a language they do not read is a poor way to
 *       start. That is why this is its own state holder rather than a field on [MainViewModel]:
 *       `MainViewModel` seeds categories in its `init`, which opens the encrypted database, and
 *       composing it before the PIN is entered would open the store the lock exists to protect.
 *       This one injects the settings store and nothing else — settings are not financial data.
 * What: the stored [LanguageSetting], as a `StateFlow`.
 * Result: `LocalisedContent` can wrap the whole app, lock screen included.
 * Changelog: 2026-09-28 — Created for issue 10.8.
 *
 * Input:  [settingsStore]. Output: the view model.
 */
@HiltViewModel
class AppLanguageViewModel
    @Inject
    constructor(
        settingsStore: SettingsStore,
    ) : ViewModel() {
        /**
         * The language the user chose.
         * Why:    collected rather than read once — it is a choice made *inside* the app, so a
         *         value read at construction would be the one thing that never changed. A read
         *         failure falls back to following the phone, which is what a fresh install does.
         * Result: `StateFlow<LanguageSetting>`. Input: none. Output: the flow.
         */
        val language: StateFlow<LanguageSetting> =
            settingsStore.observe()
                .map { it.getOrNull()?.language ?: LanguageSetting.SYSTEM }
                .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS), LanguageSetting.SYSTEM)

        private companion object {
            /** The same five seconds every other collector in this app uses (ARC-006). */
            const val STOP_TIMEOUT_MILLIS = 5_000L
        }
    }
