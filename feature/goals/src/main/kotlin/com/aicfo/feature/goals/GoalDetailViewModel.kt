package com.aicfo.feature.goals

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aicfo.core.common.Err
import com.aicfo.core.common.Result
import com.aicfo.core.model.Money
import com.aicfo.data.repository.AccountRepository
import com.aicfo.data.repository.GoalContributionRepository
import com.aicfo.data.repository.GoalDraft
import com.aicfo.data.repository.GoalRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Drives one goal's detail screen (issue 7.4; §15, FR-GOAL-002, FR-GOAL-004, ARC-004).
 *
 * Why:  linking is the only way evidence gets into a goal, so this is the class that turns a tap
 *       into a row. It does **no arithmetic**: every figure it shows was computed by `GoalEngine`
 *       and every sum by `GoalDao.observeEvidenced` (P-03).
 * What: observes the goal, its links, the accounts and the picker list; links and unlinks; and
 *       clears the ghost figure.
 * Result: one immutable [GoalDetailUiState] as a `StateFlow`.
 * Changelog: 2026-09-06 — Created for issue 7.4.
 *
 * **Five flows, not one combined flow**, for [GoalsViewModel]'s reason: they fail independently and
 * the goal itself is the half the user needs in order to fix anything. A picker list that will not
 * resolve must not blank the progress figure above it.
 *
 * **The goal id comes from the route**, so this screen survives process death without the list
 * having to still be in memory — the same shape `HoldingsViewModel` uses for its account id.
 */
@HiltViewModel
@Suppress("TooManyFunctions") // Nine, each private and a few lines: the shape GoalsViewModel keeps.
class GoalDetailViewModel
    @Inject
    constructor(
        savedStateHandle: SavedStateHandle,
        private val goals: GoalRepository,
        private val links: GoalContributionRepository,
        accounts: AccountRepository,
    ) : ViewModel() {
        private val goalId: String = savedStateHandle.get<String>(GOAL_ID_KEY).orEmpty()
        private val _uiState = MutableStateFlow(GoalDetailUiState())
        val uiState: StateFlow<GoalDetailUiState> = _uiState.asStateFlow()

        init {
            observeGoal()
            observe(links.observeContributions(goalId)) { state, value -> state.copy(contributions = value) }
            observe(links.observeFundingAccounts(goalId)) { state, value -> state.copy(fundingAccounts = value) }
            observe(links.observeLinkable(goalId)) { state, value -> state.copy(linkable = value) }
            observe(accounts.observeAccounts()) { state, value -> state.copy(accounts = value) }
        }

        /**
         * Handles one event.
         * Why:    a `when` over a sealed interface, so an unhandled event will not compile.
         * Result: the state moves, or a write is started. Input: [event]. Output: none.
         */
        fun onEvent(event: GoalDetailEvent) {
            when (event) {
                GoalDetailEvent.OpenPicker -> _uiState.update { it.copy(isPickerOpen = true) }
                GoalDetailEvent.ClosePicker -> _uiState.update { it.copy(isPickerOpen = false) }
                GoalDetailEvent.OpenAccountPicker -> _uiState.update { it.copy(isAccountPickerOpen = true) }
                GoalDetailEvent.CloseAccountPicker -> _uiState.update { it.copy(isAccountPickerOpen = false) }
                is GoalDetailEvent.Link -> link(event.transactionId)
                is GoalDetailEvent.Unlink -> write { links.unlink(goalId, event.transactionId) }
                is GoalDetailEvent.LinkAccount -> linkAccount(event.accountId, event.countHistory)
                is GoalDetailEvent.UnlinkAccount -> write { links.unlinkAccount(goalId, event.accountId) }
                GoalDetailEvent.ClearGhostProgress -> clearGhostProgress()
                GoalDetailEvent.DismissError -> _uiState.update { it.copy(errorCode = null) }
            }
        }

        /**
         * Subscribes to the goal this screen is about.
         * Why:    from the projected list rather than a lookup of its own, so this screen and the
         *         goals screen can never disagree about a figure. `isLoading` is cleared here and
         *         nowhere else: this is the flow the screen cannot render without.
         * Result: the goal, or null when the id is not in the profile. Input/Output: none.
         */
        private fun observeGoal() {
            goals.observeGoals()
                .onEach { list ->
                    _uiState.update {
                        it.copy(goal = list.firstOrNull { goal -> goal.goalId == goalId }, isLoading = false)
                    }
                }
                .catch { _uiState.update { it.copy(isLoading = false, errorCode = GoalsViewModel.STORAGE_ERROR) } }
                .launchIn(viewModelScope)
        }

        /**
         * Links one movement and closes the picker.
         * Why:    closing on success rather than leaving it open is what makes "link three things"
         *         a deliberate act rather than the default; the picker reopens in one tap.
         * Result: the progress figure above moves. Input: [transactionId]. Output: none.
         */
        private fun link(transactionId: String) {
            write(onSuccess = { it.copy(isPickerOpen = false) }) { links.link(goalId, transactionId) }
        }

        /** Result: the account is dedicated and the chooser closes. Input: [accountId]; [countHistory]. */
        private fun linkAccount(
            accountId: String,
            countHistory: Boolean,
        ) {
            write(onSuccess = { it.copy(isAccountPickerOpen = false) }) {
                links.linkAccount(goalId, accountId, countHistory)
            }
        }

        /**
         * Rewrites the goal with its hand-typed figure set to zero (§15).
         *
         * Why:    the ghost half lives on the row, so clearing it is an ordinary goal edit — which
         *         means it goes through [GoalRepository.save] and inherits its validation rather
         *         than reaching for the DAO. The **declared** half is what is zeroed; the evidenced
         *         half is not this class's to touch, and could not be zeroed anyway without
         *         unlinking the movements that produced it.
         * Result: progress becomes exactly what the ledger backs. Nothing happens when there is no
         *         goal or nothing to clear. Input/Output: none.
         */
        private fun clearGhostProgress() {
            val goal = _uiState.value.goal ?: return
            write {
                goals.save(
                    GoalDraft(
                        name = goal.name,
                        target = goal.target,
                        targetDateIso = goal.targetDateIso,
                        saved = Money.ZERO,
                        plannedMonthly = goal.plannedMonthly,
                    ),
                    id = goal.goalId,
                )
            }
        }

        /**
         * Runs one write, reporting a failure as a code the screen maps to copy.
         * Why:    six events do the same three things — launch, check, report — and writing that out
         *         six times is where one of them quietly loses its error branch.
         * Result: [onSuccess] is applied on success; the error code is set on failure.
         * Input:  [onSuccess] — an optional state change; [block] — the write. Output: none.
         */
        private fun write(
            onSuccess: (GoalDetailUiState) -> GoalDetailUiState = { it },
            block: suspend () -> Result<*, *>,
        ) {
            viewModelScope.launch {
                if (block() is Err) {
                    _uiState.update { it.copy(errorCode = GoalsViewModel.STORAGE_ERROR) }
                } else {
                    _uiState.update(onSuccess)
                }
            }
        }

        /**
         * Subscribes one flow into one slice of the state.
         * Why:    four of the five subscriptions differ only in which field they fill, and repeating
         *         `onEach { … } .catch { … } .launchIn(…)` four times is four chances to forget the
         *         `catch` — a Flow that throws would take the screen down.
         * Result: the slice fills and refills; a failure sets the error code without blanking
         *         anything else. Input: [flow]; [apply]. Output: none.
         */
        private fun <T> observe(
            flow: kotlinx.coroutines.flow.Flow<T>,
            apply: (GoalDetailUiState, T) -> GoalDetailUiState,
        ) {
            flow
                .onEach { value -> _uiState.update { state -> apply(state, value) } }
                .catch { _uiState.update { it.copy(errorCode = GoalsViewModel.STORAGE_ERROR) } }
                .launchIn(viewModelScope)
        }

        /** `MutableStateFlow.update` without the atomicfu dependency the rest of the app avoids. */
        private inline fun MutableStateFlow<GoalDetailUiState>.update(
            change: (GoalDetailUiState) -> GoalDetailUiState,
        ) {
            value = change(value)
        }

        companion object {
            /**
             * The route argument this screen reads.
             *
             * Must match `CfoRoute.GoalDetail`'s property name — typed routes put their properties
             * into the `SavedStateHandle` under their own names (ARC-001).
             */
            const val GOAL_ID_KEY = "goalId"
        }
    }
