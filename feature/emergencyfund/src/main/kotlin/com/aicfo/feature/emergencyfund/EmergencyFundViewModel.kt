package com.aicfo.feature.emergencyfund

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aicfo.data.repository.EmergencyFundRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import javax.inject.Inject

/**
 * Drives the emergency-fund screen (issue 7.2; §10.1, ARC-004).
 *
 * Why:  **this class does no arithmetic at all**, and that is the whole design. Every figure the
 *       screen shows — the runway, the target, the multiplier, the top-up — arrives already
 *       computed by `EmergencyFundEngine` through `EmergencyFundRepository` (P-03). A ViewModel that
 *       divided a balance by a spend here would be a second definition of the runway, and the two
 *       would disagree the first time either changed.
 * What: observes the assessment, and opens or closes the evidence drawer.
 * Result: one immutable [EmergencyFundUiState] as a `StateFlow`.
 * Changelog: 2026-09-02 — Created for issue 7.2.
 */
@HiltViewModel
class EmergencyFundViewModel
    @Inject
    constructor(
        private val repository: EmergencyFundRepository,
    ) : ViewModel() {
        private val _uiState = MutableStateFlow(EmergencyFundUiState())
        val uiState: StateFlow<EmergencyFundUiState> = _uiState.asStateFlow()

        init {
            observeFund()
        }

        /**
         * Handles one event.
         * Why:    a `when` over a sealed interface, so adding an event without handling it will not
         *         compile.
         * Result: the state moves. Input: [event]. Output: none.
         */
        fun onEvent(event: EmergencyFundEvent) {
            when (event) {
                EmergencyFundEvent.ToggleEvidence ->
                    _uiState.update { it.copy(isEvidenceOpen = !it.isEvidenceOpen) }
                EmergencyFundEvent.DismissError -> _uiState.update { it.copy(errorCode = null) }
            }
        }

        /**
         * Subscribes to the assessment.
         * Why:    a `catch` rather than a `try`, because the flow outlives any single call: a storage
         *         failure three emissions in must land as an error banner over the last good figures
         *         rather than tearing down the subscription (§21.6, no exceptions across boundaries).
         *
         *         **`isLoading` is cleared on the error path too.** Leaving it set would spin
         *         forever under a message explaining why nothing was coming — the shape of hang a
         *         user reads as the app being broken rather than the data being missing.
         * Result: the state follows the repository. Input: none. Output: none.
         */
        private fun observeFund() {
            repository.observeEmergencyFund()
                .onEach { plan -> _uiState.update { it.copy(plan = plan, isLoading = false, errorCode = null) } }
                .catch { _uiState.update { it.copy(isLoading = false, errorCode = ERROR_STORAGE) } }
                .launchIn(viewModelScope)
        }

        private companion object {
            /** A key, not a message — the wording is `strings.xml`'s (§21.6). */
            const val ERROR_STORAGE = "emergency_fund.storage"
        }
    }
