package com.aicfo.feature.advisor

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aicfo.core.common.Err
import com.aicfo.core.common.Ok
import com.aicfo.core.model.Money
import com.aicfo.data.repository.BuyListRepository
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
        private val buyList: BuyListRepository,
    ) : ViewModel() {
        private val _uiState = MutableStateFlow(AdvisorUiState())
        val uiState: StateFlow<AdvisorUiState> = _uiState.asStateFlow()

        init {
            observe()
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
                is AdvisorEvent.BuyList -> onBuyList(event)
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
         * Everything §13.3's list can do.
         * Why:    its own dispatch, so this class does not grow one `when` that handles both the
         *         advisor's question and the list's.
         * Result: the list moves. Input: [event]. Output: none.
         */
        private fun onBuyList(event: AdvisorEvent.BuyList) {
            when (event) {
                is AdvisorEvent.WishNameChanged -> _uiState.update { it.copy(wishName = event.name) }
                is AdvisorEvent.WishPriceChanged ->
                    _uiState.update { it.copy(wishPriceRupees = event.rupees.filter(Char::isDigit)) }
                AdvisorEvent.AddWish -> addWish()
                is AdvisorEvent.AnswerWish -> answerWish(event)
                is AdvisorEvent.AdviseWish -> adviseWish(event.itemId)
                is AdvisorEvent.MoveWish -> moveWish(event)
            }
        }

        /**
         * Puts a wish on the list instead of buying it (§13.3).
         * Result: the list gains it, parked. Input: none. Output: none.
         */
        private fun addWish() {
            val state = _uiState.value
            if (!state.canAddWish) return
            viewModelScope.launch {
                when (val result = buyList.add(state.wishName.trim(), rupees(state.wishPriceRupees))) {
                    is Ok -> _uiState.update { it.copy(wishName = "", wishPriceRupees = "") }
                    is Err -> _uiState.update { it.copy(errorCode = result.error.code) }
                }
            }
        }

        /**
         * Records one interview answer.
         * Why:    one at a time, as the screen asks it — the repository re-scores and the list
         *         re-emits, so nothing here recomputes a score (P-03).
         * Result: the list moves. Input: [event]. Output: none.
         */
        private fun answerWish(event: AdvisorEvent.AnswerWish) {
            viewModelScope.launch {
                val result = buyList.answer(event.itemId, event.answer)
                if (result is Err) _uiState.update { it.copy(errorCode = result.error.code) }
            }
        }

        /** Asks the advisor about a wish, and shows the card it gives back (issue 10.1). */
        private fun adviseWish(itemId: String) {
            _uiState.update { it.copy(isAsking = true, errorCode = null) }
            viewModelScope.launch {
                when (val result = buyList.advise(itemId)) {
                    is Ok -> _uiState.update { it.copy(card = result.value, isAsking = false) }
                    is Err -> _uiState.update { it.copy(isAsking = false, errorCode = result.error.code) }
                }
            }
        }

        /** Moves a wish to another status — removing is the user's tap, never the app's (§13.3). */
        private fun moveWish(event: AdvisorEvent.MoveWish) {
            viewModelScope.launch {
                val result = buyList.setStatus(event.itemId, event.status)
                if (result is Err) _uiState.update { it.copy(errorCode = result.error.code) }
            }
        }

        /**
         * Subscribes to the history and to the buy list.
         * Why:    one method for both, because they fail the same way: a `catch` rather than a
         *         `try`, so a storage failure three emissions in lands as a banner over the last
         *         good data instead of tearing the subscription down.
         * Result: the screen follows the store. Input: none. Output: none.
         */
        private fun observe() {
            repository.observeRecent()
                .onEach { kept -> _uiState.update { it.copy(history = kept) } }
                .catch { failure -> _uiState.update { it.copy(errorCode = failure::class.simpleName) } }
                .launchIn(viewModelScope)
            buyList.observeList()
                .onEach { wishes -> _uiState.update { it.copy(buyList = wishes) } }
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
    }

/**
 * Result: whole rupees as paise (MNY-001) — the one conversion this screen performs.
 * A top-level function rather than a method: it holds no state, and the ViewModel is at detekt's
 * limit for a class that now drives two features at once.
 * Input:  [typed] — digits only, already filtered. Output: [Money].
 */
private fun rupees(typed: String): Money = Money((typed.toLongOrNull() ?: 0L) * PAISE_PER_RUPEE)

private const val PAISE_PER_RUPEE = 100L
