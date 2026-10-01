package com.aicfo.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aicfo.core.common.AppError
import com.aicfo.core.common.Clock
import com.aicfo.core.common.Err
import com.aicfo.core.common.Ok
import com.aicfo.core.common.Result
import com.aicfo.core.common.toProfileDate
import com.aicfo.core.datastore.ConsentFeature
import com.aicfo.core.datastore.ConsentState
import com.aicfo.core.datastore.ConsentStore
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * The consents dashboard's state holder (issue 11.3; §23, P-01, DPDP).
 *
 * Why:  one screen that answers three questions the app had no answer for: **what** may it use,
 *       **since when**, and **what happens if I say no now**. The ledger has held the first two
 *       since issue 1.9; this is what finally reads them out. The third is the screen's wording,
 *       and it is wording rather than code because the enforcement already exists at each data
 *       path — a revoked consent stops the flow in the repository that owns it, not here.
 * What: reads the whole ledger, formats both timestamps in the profile's zone, and writes grants
 *       and withdrawals straight through.
 * Result: a control surface the user can audit, and one that cannot claim a state the store did
 *       not accept.
 * Changelog: 2026-10-01 — Created for issue 11.3.
 *
 * **It does no arithmetic and keeps no copy.** Every row is derived from the store's own emission,
 * so a write that fails leaves the screen showing what is still true — the one lie a privacy
 * control must never tell is "off" over a feature that is still running.
 *
 * Input:  [consentStore] — the ledger; [clock] — the profile's zone, for the dates (TIM-001).
 * Output: the view model.
 */
@HiltViewModel
class ConsentsViewModel
    @Inject
    constructor(
        private val consentStore: ConsentStore,
        private val clock: Clock,
    ) : ViewModel() {
        private val _uiState = MutableStateFlow(ConsentsUiState())

        /** The screen's state. Input: none. Output: `StateFlow<ConsentsUiState>`. */
        val uiState: StateFlow<ConsentsUiState> = _uiState.asStateFlow()

        init {
            consentStore
                .observeAll()
                .onEach { result -> _uiState.update { it.applied(result) } }
                .launchIn(viewModelScope)
        }

        /**
         * Handles one event from the screen.
         * Why:    a `when` over a sealed interface, so a new action cannot be added without a
         *         decision about what it writes (ARC-004).
         * Result: the store is told; the row changes only when it agreed.
         * Input:  [event]. Output: none.
         */
        fun onEvent(event: ConsentsEvent) {
            when (event) {
                is ConsentsEvent.Revoked -> write { consentStore.revoke(event.feature) }
                is ConsentsEvent.Granted -> write { consentStore.grant(event.feature) }
                ConsentsEvent.DismissError -> _uiState.update { it.copy(errorCode = null) }
            }
        }

        /**
         * Runs one ledger write and surfaces its failure.
         * Why:    the rows are not touched here on purpose. They come from the store's own flow, so
         *         a successful write re-emits and a failed one does not — which is exactly the
         *         behaviour a privacy switch needs. Flipping the row optimistically would show a
         *         consent as withdrawn while its data path was still running.
         * Result: `Ok` changes nothing here and everything downstream; `Err` sets [errorCode].
         * Input:  [block] — the store call. Output: none (launched on `viewModelScope`).
         */
        private fun write(block: suspend () -> Result<Unit, AppError>) {
            viewModelScope.launch {
                when (val result = block()) {
                    is Ok -> Unit
                    is Err -> _uiState.update { it.copy(errorCode = result.error::class.simpleName) }
                }
            }
        }

        /**
         * Folds one ledger emission into the state.
         * Why:    a read failure must **not** become "nothing is granted". The consents are still in
         *         force; a screen that implied otherwise would invite the user to stop looking.
         * Result: the rows, or the previous rows plus an error.
         * Input:  the receiver; [result] — the ledger's emission. Output: the new state.
         */
        private fun ConsentsUiState.applied(
            result: Result<Map<ConsentFeature, ConsentState>, AppError>,
        ): ConsentsUiState =
            when (result) {
                is Ok -> copy(rows = result.value.toRows(), isLoading = false, errorCode = null)
                is Err -> copy(isLoading = false, errorCode = result.error::class.simpleName)
            }

        /**
         * Turns the ledger into rows, in the enum's order.
         * Why:    **every** feature appears, including the ones with no record: absence is never
         *         consent (issue 1.9), and a feature the user has never answered for is a "no" they
         *         are entitled to see rather than a row that simply is not there.
         * Result: one row per [ConsentFeature]. Input: the receiver. Output: `List<ConsentRow>`.
         */
        private fun Map<ConsentFeature, ConsentState>.toRows(): List<ConsentRow> =
            ConsentFeature.entries.map { feature ->
                val state = this[feature] ?: ConsentState.NOT_GRANTED
                ConsentRow(
                    feature = feature,
                    granted = state.granted,
                    grantedOnIsoDate = state.grantedAtUtcMillis?.let(::isoDay),
                    revokedOnIsoDate = state.revokedAtUtcMillis?.let(::isoDay),
                )
            }

        /**
         * Result: the profile-zone day a UTC instant falls on, ISO (TIM-001, TIM-002).
         * Why:    20:30 UTC is already tomorrow in Kolkata. Formatting in UTC would tell a user
         *         they agreed to something the day before they did — a small wrongness in exactly
         *         the record that exists to be precise.
         * Input:  [utcMillis]. Output: `yyyy-MM-dd`.
         */
        private fun isoDay(utcMillis: Long): String = clock.toProfileDate(utcMillis).toString()
    }
