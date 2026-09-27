package com.aicfo.feature.market

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aicfo.data.repository.MarketSignalRepository
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
 * The Opportunity screen's state holder (issue 10.7; §30, ARC-004, ARC-005).
 *
 * Why:  it does no arithmetic at all — **deliberately**, on the screen where the temptation would
 *       be greatest. Every number shown was computed by AI-MKT from cached closes, and this class
 *       only carries them up. There is nothing to type here and nothing to submit, because §30's
 *       screen recommends and the user decides (P-07).
 * What: subscribes to the assessments.
 * Result: a `StateFlow<OpportunityUiState>` the screen renders.
 * Changelog: 2026-09-27 — Created for issue 10.7.
 *
 * Input:  [repository]. Output: the view model.
 */
@HiltViewModel
class OpportunityViewModel
    @Inject
    constructor(
        repository: MarketSignalRepository,
    ) : ViewModel() {
        private val _uiState = MutableStateFlow(OpportunityUiState())

        /** The screen's state. Input: none. Output: `StateFlow<OpportunityUiState>`. */
        val uiState: StateFlow<OpportunityUiState> = _uiState.asStateFlow()

        init {
            repository.observeOpportunities()
                .onEach { views -> _uiState.update { it.copy(views = views, isLoaded = true) } }
                .catch { failure -> _uiState.update { it.copy(errorCode = failure::class.simpleName) } }
                .launchIn(viewModelScope)
        }
    }
