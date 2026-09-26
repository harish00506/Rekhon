package com.aicfo.feature.advisor

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aicfo.core.common.Err
import com.aicfo.core.common.Ok
import com.aicfo.core.model.Money
import com.aicfo.data.repository.SimulatorRepository
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
 * Drives the simulators screen (issue 10.3; §36, §40.2, ARC-004, P-03).
 *
 * Why:  **it computes nothing.** Every interest total, month count and breakeven arrives from
 *       AI-SIM through the repository. The only arithmetic here is turning what a person typed —
 *       rupees and whole percents — into the paise and basis points the engines speak (MNY-001/002).
 * What: holds the question, asks it, and shows the answer.
 * Result: one immutable [SimulatorsUiState] as a `StateFlow`.
 * Changelog: 2026-09-26 — Created for issue 10.3.
 */
@HiltViewModel
class SimulatorsViewModel
    @Inject
    constructor(
        private val repository: SimulatorRepository,
    ) : ViewModel() {
        private val _uiState = MutableStateFlow(SimulatorsUiState())
        val uiState: StateFlow<SimulatorsUiState> = _uiState.asStateFlow()

        init {
            observeDebts()
        }

        /**
         * Handles one event.
         * Result: the state moves. Input: [event]. Output: none.
         */
        fun onEvent(event: SimulatorsEvent) {
            when (event) {
                is SimulatorsEvent.LoanSelected -> _uiState.update { it.copy(selectedAccountId = event.accountId) }
                is SimulatorsEvent.LumpSumChanged -> _uiState.update { it.copy(lumpSumRupees = digits(event.rupees)) }
                is SimulatorsEvent.ExpectedReturnChanged ->
                    _uiState.update { it.copy(expectedReturnPercent = digits(event.percent)) }
                is SimulatorsEvent.TaxChanged -> _uiState.update { it.copy(taxPercent = digits(event.percent)) }
                is SimulatorsEvent.ExtraMonthlyChanged ->
                    _uiState.update { it.copy(extraMonthlyRupees = digits(event.rupees)) }
                SimulatorsEvent.SimulatePrepay -> simulatePrepay()
                SimulatorsEvent.SimulatePayoff -> simulatePayoff()
                SimulatorsEvent.DismissError -> _uiState.update { it.copy(errorCode = null) }
            }
        }

        /** Asks §36's question about the chosen loan. */
        private fun simulatePrepay() {
            val state = _uiState.value
            val accountId = state.selectedAccountId ?: return
            if (!state.canSimulatePrepay || state.isSimulating) return
            _uiState.update { it.copy(isSimulating = true, errorCode = null) }
            viewModelScope.launch {
                val result =
                    repository.prepayVsInvest(
                        accountId = accountId,
                        lumpSum = rupees(state.lumpSumRupees),
                        expectedReturnBps = percentToBps(state.expectedReturnPercent),
                        taxOnReturnsBps = percentToBps(state.taxPercent),
                    )
                _uiState.update {
                    when (result) {
                        is Ok -> it.copy(prepay = result.value, isSimulating = false)
                        is Err -> it.copy(isSimulating = false, errorCode = result.error.code)
                    }
                }
            }
        }

        /** Asks §40.2's question about everything owed. */
        private fun simulatePayoff() {
            val state = _uiState.value
            if (!state.canSimulatePayoff || state.isSimulating) return
            _uiState.update { it.copy(isSimulating = true, errorCode = null) }
            viewModelScope.launch {
                val result = repository.payoff(rupees(state.extraMonthlyRupees))
                _uiState.update {
                    when (result) {
                        is Ok -> it.copy(payoff = result.value, isSimulating = false)
                        is Err -> it.copy(isSimulating = false, errorCode = result.error.code)
                    }
                }
            }
        }

        /** Subscribes to the household's debts; a failure is a banner over the last good list. */
        private fun observeDebts() {
            repository.observeDebts()
                .onEach { debts ->
                    _uiState.update { state ->
                        state.copy(
                            debts = debts,
                            selectedAccountId = state.selectedAccountId ?: debts.firstOrNull { it.isLoan }?.accountId,
                        )
                    }
                }
                .catch { failure -> _uiState.update { it.copy(errorCode = failure::class.simpleName) } }
                .launchIn(viewModelScope)
        }
    }

/** Result: the digits of what was typed — a field holds a number or nothing. */
private fun digits(typed: String): String = typed.filter(Char::isDigit)

/** Result: whole rupees as paise (MNY-001). */
private fun rupees(typed: String): Money = Money((typed.toLongOrNull() ?: 0L) * PAISE_PER_RUPEE)

/** Result: whole percent as basis points (MNY-002) — 12 becomes 1,200. */
private fun percentToBps(typed: String): Int = ((typed.toIntOrNull() ?: 0) * BPS_PER_PERCENT)

private const val PAISE_PER_RUPEE = 100L
private const val BPS_PER_PERCENT = 100
