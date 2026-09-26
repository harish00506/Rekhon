package com.aicfo.feature.chat

import com.aicfo.data.repository.ChatTurn
import com.aicfo.domain.engines.chat.ChatIntent

/**
 * What §19's assistant screen shows (issue 10.5; ARC-004).
 *
 * Why:  one immutable data class per screen as a `StateFlow`, so the screen is a pure function of
 *       it and a test can assert the whole sequence including the moment between asking and
 *       answering — which for this screen is the interesting one.
 * What: the conversation, what is being typed, whether a question is in flight, and the chips.
 * Result: the screen renders this and nothing else.
 * Changelog: 2026-09-26 — Created for issue 10.5.
 *
 * Input:  [turns] — oldest first; [typed] — the question being written; [isAsking] — true while the
 *         pipeline runs, so the screen can say it is thinking rather than looking broken;
 *         [chips] — what to offer (CHT-003); [errorCode] — the field a refusal named, or `null`.
 * Output: an immutable value.
 */
data class ChatUiState(
    val turns: List<ChatTurn> = emptyList(),
    val typed: String = "",
    val isAsking: Boolean = false,
    val chips: List<ChatIntent> = DEFAULT_CHIPS,
    val errorCode: String? = null,
) {
    /** Whether the ask button does anything. */
    val canAsk: Boolean get() = typed.isNotBlank() && !isAsking

    companion object {
        /**
         * The chips a fresh conversation offers.
         * Why:    CHT-003 — chips drive discovery, and a blank box with a cursor is the worst way
         *         to introduce an assistant that can only answer a bounded set of questions.
         */
        val DEFAULT_CHIPS = listOf(ChatIntent.BALANCE, ChatIntent.FORECAST, ChatIntent.HEALTH)
    }
}

/**
 * Everything the user can do here (ARC-004: events flow up a sealed interface).
 * Changelog: 2026-09-26 — Created for issue 10.5.
 */
sealed interface ChatEvent {
    /** The question being typed. */
    data class Typed(val text: String) : ChatEvent

    /** Ask what has been typed. */
    data object Ask : ChatEvent

    /** Ask one of the suggested questions (CHT-003). */
    data class AskChip(val intent: ChatIntent) : ChatEvent

    /** Forget the conversation (CHT-004). */
    data object Clear : ChatEvent
}
