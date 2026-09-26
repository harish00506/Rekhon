package com.aicfo.feature.chat

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aicfo.core.common.AppError
import com.aicfo.core.common.Err
import com.aicfo.core.common.Result
import com.aicfo.data.repository.ChatRepository
import com.aicfo.domain.engines.chat.ChatIntent
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
 * The assistant screen's state holder (issue 10.5; §19, ARC-004, ARC-005).
 *
 * Why:  this class does no arithmetic at all — not even the rupee conversion the other screens do —
 *       because chat has no numeric input. Its whole job is to carry a question down and a checked
 *       reply back up, and to make the waiting visible so the screen does not look broken while the
 *       pipeline runs.
 * What: the conversation subscription, the typed question, asking, the chips, and the clear.
 * Result: a `StateFlow<ChatUiState>` the screen renders.
 * Changelog: 2026-09-26 — Created for issue 10.5.
 *
 * Input:  [repository]. Output: the view model.
 */
@HiltViewModel
class ChatViewModel
    @Inject
    constructor(
        private val repository: ChatRepository,
    ) : ViewModel() {
        private val _uiState = MutableStateFlow(ChatUiState())

        /** The screen's state. Input: none. Output: `StateFlow<ChatUiState>`. */
        val uiState: StateFlow<ChatUiState> = _uiState.asStateFlow()

        init {
            observeConversation()
        }

        /**
         * Handles one event from the screen.
         * Result: the state moves, or a question runs and the subscription re-emits.
         * Input: [event]. Output: none.
         */
        fun onEvent(event: ChatEvent) {
            when (event) {
                is ChatEvent.Typed -> _uiState.update { it.copy(typed = event.text) }
                ChatEvent.Ask -> ask(_uiState.value.typed)
                is ChatEvent.AskChip -> ask(CHIP_QUESTIONS.getValue(event.intent))
                ChatEvent.Clear -> clear()
            }
        }

        /**
         * Asks one question.
         * Why:    the typed field is cleared **before** the call, so a slow answer cannot be asked
         *         twice by an impatient tap, and `isAsking` is set so the screen can say so.
         * Result: the conversation gains a turn. Input: [question]. Output: none.
         */
        private fun ask(question: String) {
            if (question.isBlank() || _uiState.value.isAsking) return
            _uiState.update { it.copy(typed = "", isAsking = true, errorCode = null) }
            viewModelScope.launch {
                val result = repository.ask(question)
                _uiState.update { state ->
                    state.copy(
                        isAsking = false,
                        errorCode = (result as? Err)?.error?.code,
                        chips = chipsAfter(result),
                    )
                }
            }
        }

        /**
         * Result: what to offer next — the reply's own chips, or the defaults.
         * Input:  [result]. Output: the chips.
         */
        private fun chipsAfter(result: Result<com.aicfo.data.repository.ChatTurn, AppError>): List<ChatIntent> =
            (result as? com.aicfo.core.common.Ok)?.value?.reply?.chips?.takeIf { it.isNotEmpty() }
                ?: ChatUiState.DEFAULT_CHIPS

        /** Forgets the conversation (CHT-004). Input: none. Output: none. */
        private fun clear() {
            viewModelScope.launch {
                val result = repository.clear()
                _uiState.update { it.copy(errorCode = (result as? Err)?.error?.code) }
            }
        }

        /** Subscribes to the conversation; a failure is a banner over the last good list. */
        private fun observeConversation() {
            repository.observeConversation()
                .onEach { turns -> _uiState.update { it.copy(turns = turns) } }
                .catch { failure -> _uiState.update { it.copy(errorCode = failure::class.simpleName) } }
                .launchIn(viewModelScope)
        }

        private companion object {
            /**
             * The words behind each chip.
             * Why:    a chip is a shortcut for typing, so it sends the same text a person would —
             *         which means the chip and the free-text path go through exactly one planner,
             *         and a chip cannot reach a tool that typing could not.
             */
            val CHIP_QUESTIONS =
                mapOf(
                    ChatIntent.SPEND to "where did my money go this month?",
                    ChatIntent.BALANCE to "what is my balance?",
                    ChatIntent.FORECAST to "will i run out of money in the next 90 days?",
                    ChatIntent.BUDGET to "am i over budget?",
                    ChatIntent.GOALS to "what am i saving for?",
                    ChatIntent.AFFORD to "can i afford this?",
                    ChatIntent.HEALTH to "how am i doing financially?",
                    ChatIntent.DEBT to "should i prepay the loan or invest instead?",
                    ChatIntent.BUYLIST to "what is on my buy list?",
                    ChatIntent.VEHICLE to "when is the car service due?",
                )
        }
    }
