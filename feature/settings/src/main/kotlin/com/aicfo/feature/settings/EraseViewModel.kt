package com.aicfo.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aicfo.core.common.Err
import com.aicfo.core.common.Ok
import com.aicfo.core.crypto.PinVerifier
import com.aicfo.data.repository.EraseRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * The erase-everything screen's brain (issue 11.4; §23, §34, SEC-003, P-01).
 *
 * Why:  the two gates in front of the one irreversible action in this app. §34 asks for explicit
 *       confirmation **and** authentication, and the reason both live here rather than in the
 *       composition is that a disabled button is not a gate: anything that can deliver
 *       [EraseEvent.Confirmed] — a stale recomposition, an accessibility action, a future caller —
 *       would walk straight past it. So the state decides whether the button is enabled and this
 *       class decides again, from the same value, whether to proceed.
 * What: load whether a PIN exists, hold what the user typed, verify, erase, report.
 * Result: an erase that happens exactly once, after a typed word and a correct PIN, or does not
 *       happen at all and says why.
 * Changelog: 2026-10-01 — Created for issue 11.4.
 *
 * Input:  [repository] — the irreversible operation; [pinVerifier] — the auth factor (SEC-002).
 * Output: an observable screen state.
 *
 * The confirmation word is **not** held here. The screen reads it from `strings.xml` and passes it
 * in via [onWordLoaded], so a Hindi user confirms in Hindi and the gate never compares against a
 * hardcoded English literal (10.8's translation coverage made that a rule, not a preference).
 */
@HiltViewModel
class EraseViewModel
    @Inject
    constructor(
        private val repository: EraseRepository,
        private val pinVerifier: PinVerifier,
    ) : ViewModel() {
        private val _uiState = MutableStateFlow(EraseUiState())

        /** The screen's state; replaced wholesale on each change (ARC-004). */
        val uiState: StateFlow<EraseUiState> = _uiState.asStateFlow()

        init {
            viewModelScope.launch {
                // An unreadable credential resolves to "a PIN is required". The opposite default
                // would turn a storage error into a way past the authentication gate.
                val required =
                    when (val answer = pinVerifier.isPinSet()) {
                        is Ok -> answer.value
                        is Err -> true
                    }
                _uiState.update { it.copy(isPinRequired = required) }
            }
        }

        /**
         * Tells the view model the localised confirmation word.
         * Why:    resources need a `Context` and this class must not have one; the screen reads the
         *         string and hands it over. Until it does, [EraseUiState.canErase] is false, which
         *         is the safe direction — a word that has not arrived cannot be matched.
         * Result: the word is in the state and the gate can be satisfied.
         * Input:  [word] — the translated confirmation word. Output: none.
         * Changelog: 2026-10-01 — Created for issue 11.4.
         */
        fun onWordLoaded(word: String) {
            _uiState.update { it.copy(confirmationWord = word) }
        }

        /**
         * Handles one event.
         * Result: a new state, and on [EraseEvent.Confirmed] possibly the end of this installation's
         *         data. Input: [event]. Output: none.
         * Changelog: 2026-10-01 — Created for issue 11.4.
         */
        fun onEvent(event: EraseEvent) {
            when (event) {
                is EraseEvent.ConfirmationTyped -> _uiState.update { it.copy(confirmationText = event.text) }
                is EraseEvent.PinTyped -> _uiState.update { it.copy(pinText = event.pin) }
                EraseEvent.DismissError -> _uiState.update { it.copy(errorCode = null) }
                EraseEvent.Confirmed -> erase()
            }
        }

        /**
         * Verifies, then erases.
         * Why:    re-checks [EraseUiState.canErase] rather than trusting that the button was
         *         disabled, and sets `isErasing` **before** suspending so a second tap arriving in
         *         the same frame finds the gate already closed. Without that, two taps on a slow
         *         device run two erases — harmless here, by luck, and exactly the shape of bug that
         *         is not harmless somewhere else.
         * Result: `isErased` on success; an error code on a wrong PIN or a failed shred; nothing
         *         destroyed in either failure case. Input: none. Output: none.
         * Changelog: 2026-10-01 — Created for issue 11.4.
         */
        private fun erase() {
            val state = _uiState.value
            if (!state.canErase) return
            _uiState.update { it.copy(isErasing = true, errorCode = null) }
            viewModelScope.launch {
                if (!authenticated(state)) {
                    // Deliberately the same message for a wrong PIN as the lock screen gives, and
                    // nothing about whether there was data to erase.
                    _uiState.update { it.copy(isErasing = false, pinText = "", errorCode = ERROR_PIN) }
                    return@launch
                }
                when (val outcome = repository.eraseEverything()) {
                    is Ok -> _uiState.update { it.copy(isErasing = false, isErased = true, pinText = "") }
                    is Err ->
                        _uiState.update {
                            it.copy(
                                isErasing = false,
                                pinText = "",
                                errorCode = outcome.error.code,
                            )
                        }
                }
            }
        }

        /**
         * Whether the user proved who they are.
         * Why:    a device with no PIN has no secret to prove (SEC-002 makes the lock optional), and
         *         demanding one would lock a user out of erasing their own data. Everything else —
         *         a wrong PIN, an unreadable credential, a verifier that errored — is a refusal.
         * Result: `true` only when authentication is not required or succeeded.
         * Input:  [state] — the state the erase was started from. Output: [Boolean].
         * Changelog: 2026-10-01 — Created for issue 11.4.
         */
        private fun authenticated(state: EraseUiState): Boolean {
            if (!state.isPinRequired) return true
            return when (val verdict = pinVerifier.verify(state.pinText)) {
                is Ok -> verdict.value
                is Err -> false
            }
        }

        private companion object {
            /** The screen maps this to "that PIN is not right"; a code, never a sentence. */
            const val ERROR_PIN = "erase.pin"
        }
    }
