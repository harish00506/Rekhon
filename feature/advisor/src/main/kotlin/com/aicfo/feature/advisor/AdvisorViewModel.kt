package com.aicfo.feature.advisor

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aicfo.core.common.Err
import com.aicfo.core.common.Ok
import com.aicfo.core.model.Money
import com.aicfo.data.repository.PurchaseAdvisorRepository
import com.aicfo.domain.engines.purchase.PurchaseRequest
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Drives the Purchase Advisor screen (issue 10.1; §13, ARC-004).
 *
 * Why:  **it computes nothing.** The verdict, every gate, the impact strip and the alternatives all
 *       arrive decided from AI-PA through the repository (P-03). The one piece of arithmetic here
 *       is turning the rupees a person typed into the paise the engine speaks (MNY-001), and it is
 *       deliberately the only one.
 * What: holds the question, asks it, and shows the answer and the history.
 * Result: one immutable [AdvisorUiState] as a `StateFlow`.
 * Changelog: 2026-09-25 — Created for issue 10.1.
 */
@HiltViewModel
class AdvisorViewModel
    @Inject
    constructor(
        private val repository: PurchaseAdvisorRepository,
    ) : ViewModel() {
        private val _uiState = MutableStateFlow(AdvisorUiState())
        val uiState: StateFlow<AdvisorUiState> = _uiState.asStateFlow()

        init {
            observeHistory()
        }

        /**
         * Handles one event.
         * Why:    a `when` over a sealed interface, so adding an event without handling it will not
         *         compile.
         * Result: the state moves. Input: [event]. Output: none.
         */
        fun onEvent(event: AdvisorEvent) {
            when (event) {
                is AdvisorEvent.ItemChanged -> _uiState.update { it.copy(item = event.item) }
                is AdvisorEvent.PriceChanged ->
                    _uiState.update {
                        it.copy(
                            priceRupees = event.rupees.filter(Char::isDigit),
                        )
                    }
                is AdvisorEvent.EmiChanged ->
                    _uiState.update {
                        it.copy(
                            monthlyEmiRupees = event.rupees.filter(Char::isDigit),
                        )
                    }
                is AdvisorEvent.MethodChanged -> _uiState.update { it.copy(method = event.method) }
                is AdvisorEvent.UrgencyChanged -> _uiState.update { it.copy(urgency = event.urgency) }
                AdvisorEvent.Ask -> ask()
                is AdvisorEvent.OpenKept -> open(event.id)
                AdvisorEvent.DismissError -> _uiState.update { it.copy(errorCode = null) }
            }
        }

        /**
         * Asks the advisor.
         * Why:    the answer is stored by the repository before it comes back, so what the screen
         *         shows and what the history holds are the same card (§13.2).
         * Result: the card, or an error banner. Input: none. Output: none.
         */
        private fun ask() {
            val state = _uiState.value
            if (!state.canAsk || state.isAsking) return
            _uiState.update { it.copy(isAsking = true, errorCode = null) }
            viewModelScope.launch {
                when (val result = repository.advise(request(state))) {
                    is Ok -> _uiState.update { it.copy(card = result.value, isAsking = false) }
                    is Err -> _uiState.update { it.copy(isAsking = false, errorCode = result.error.code) }
                }
            }
        }

        /**
         * Opens a verdict given earlier (§13.2).
         * Result: the kept card replaces the one on screen. Input: [id]. Output: none.
         */
        private fun open(id: String) {
            viewModelScope.launch {
                when (val result = repository.find(id)) {
                    is Ok -> result.value?.let { card -> _uiState.update { it.copy(card = card) } }
                    is Err -> _uiState.update { it.copy(errorCode = result.error.code) }
                }
            }
        }

        /**
         * Subscribes to the history.
         * Why:    a `catch` rather than a `try`: a storage failure three emissions in must land as a
         *         banner over the last good list rather than tearing down the subscription.
         * Result: the list follows the store. Input: none. Output: none.
         */
        private fun observeHistory() {
            repository.observeRecent()
                .onEach { kept -> _uiState.update { it.copy(history = kept) } }
                .catch { failure -> _uiState.update { it.copy(errorCode = failure::class.simpleName) } }
                .launchIn(viewModelScope)
        }

        /** Result: the question in the engine's units (MNY-001). Input: [state]. */
        private fun request(state: AdvisorUiState) =
            PurchaseRequest(
                item = state.item.trim(),
                price = rupees(state.priceRupees),
                method = state.method,
                urgency = state.urgency,
                monthlyEmi = state.monthlyEmiRupees.takeIf { it.isNotBlank() }?.let(::rupees),
            )

        /** Result: whole rupees as paise. Input: [typed] — digits only, already filtered. */
        private fun rupees(typed: String): Money = Money((typed.toLongOrNull() ?: 0L) * PAISE_PER_RUPEE)

        private companion object {
            const val PAISE_PER_RUPEE = 100L
        }
    }
