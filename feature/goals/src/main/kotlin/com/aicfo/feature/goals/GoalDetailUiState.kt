package com.aicfo.feature.goals

import androidx.compose.runtime.Immutable
import com.aicfo.core.model.Account
import com.aicfo.core.model.Money
import com.aicfo.core.model.Transaction
import com.aicfo.data.repository.GoalContribution
import com.aicfo.data.repository.GoalFundingAccount
import com.aicfo.domain.engines.goals.GoalProjection

/**
 * Everything one goal's detail screen draws, as one immutable value (issue 7.4; ARC-004).
 *
 * Why:  §15 says progress is transaction-evidenced and that manual claims must be **visually
 *       distinct**. That is a screen of its own: the goals list already carries three competing
 *       "monthly" figures (7.1's required, the user's planned, 7.3's allocated), and hanging a
 *       fourth section off each card is how the 7.3 session's §2b.2 contradiction happened.
 * What: the goal, what evidences it, what could evidence it, and the two pickers.
 * Result: what `GoalDetailScreen` renders.
 * Changelog: 2026-09-06 — Created for issue 7.4.
 *
 * @property goal the projected goal, or **null** until the first emission or when the id is not in
 *   the profile — a goal deleted on the list while this screen is open is not an error, it is a
 *   screen with nothing left to show.
 * @property contributions the movements linked to it, newest link first.
 * @property fundingAccounts the accounts dedicated to it (FR-GOAL-002).
 * @property linkable what the picker offers: recent movements not already linked, each transfer
 *   once. Read only while [isPickerOpen], but held here so the list is warm when it opens.
 * @property accounts every account the profile has, for the dedication chooser.
 * @property isPickerOpen whether the transaction picker is showing.
 * @property isAccountPickerOpen whether the funding-account chooser is showing.
 * @property isLoading true until the first emission, so "no contributions yet" and "not read yet"
 *   are not drawn the same way — the distinction `TransactionsUiState.isEmpty` has had to grow four
 *   times.
 * @property errorCode a dotted key, or null. A key rather than a message, because the domain must
 *   not decide wording.
 */
@Immutable
data class GoalDetailUiState(
    val goal: GoalProjection? = null,
    val contributions: List<GoalContribution> = emptyList(),
    val fundingAccounts: List<GoalFundingAccount> = emptyList(),
    val linkable: List<Transaction> = emptyList(),
    val accounts: List<Account> = emptyList(),
    val isPickerOpen: Boolean = false,
    val isAccountPickerOpen: Boolean = false,
    val isLoading: Boolean = true,
    val errorCode: String? = null,
) {
    /**
     * Whether the goal has any progress the ledger does not back.
     *
     * Why:    the one question §15 asks this screen — ghost progress is what has to be *visually
     *         distinct*. Asked of the projection rather than recomputed here, because the engine
     *         already split the figure and a screen that subtracted it again would be a second
     *         definition of the same thing (P-03).
     * Result: true when there is a declared half to mark. Input: none. Output: [Boolean].
     */
    val hasGhostProgress: Boolean get() = (goal?.savedDeclared ?: Money.ZERO) > Money.ZERO

    /** Whether to draw "nothing linked yet" rather than an empty list under a heading. */
    val hasNoEvidence: Boolean
        get() = contributions.isEmpty() && fundingAccounts.isEmpty() && !isLoading

    /** The accounts not already dedicated, so the chooser never offers a no-op. */
    val dedicatableAccounts: List<Account>
        get() {
            val dedicated = fundingAccounts.map { it.account.id }.toSet()
            return accounts.filterNot { it.id in dedicated }
        }
}

/**
 * What the detail screen can ask for (issue 7.4; ARC-004).
 *
 * Why:  events up via a sealed interface, so adding one without handling it will not compile.
 * Result: the argument `GoalDetailViewModel.onEvent` takes.
 * Changelog: 2026-09-06 — Created for issue 7.4.
 */
sealed interface GoalDetailEvent {
    /** Show the picker of movements that could fund this goal. */
    data object OpenPicker : GoalDetailEvent

    /** Hide it again without linking anything. */
    data object ClosePicker : GoalDetailEvent

    /** Show the chooser of accounts that could be dedicated to this goal. */
    data object OpenAccountPicker : GoalDetailEvent

    /** Hide it again. */
    data object CloseAccountPicker : GoalDetailEvent

    /** Link one movement (FR-GOAL-004). */
    data class Link(val transactionId: String) : GoalDetailEvent

    /** Take one movement back off the goal; the progress reverses exactly. */
    data class Unlink(val transactionId: String) : GoalDetailEvent

    /**
     * Dedicate one account (FR-GOAL-002).
     *
     * @property countHistory false to count only from today, true to count everything the account
     *   has ever held. The user's answer, stored as a date, never assumed.
     */
    data class LinkAccount(val accountId: String, val countHistory: Boolean) : GoalDetailEvent

    /** Release one account. */
    data class UnlinkAccount(val accountId: String) : GoalDetailEvent

    /**
     * Drop the hand-typed figure, leaving only what the ledger backs (§15).
     *
     * Why:    the trap this screen exists to defuse. A user who typed ₹50,000 and then links
     *         ₹50,000 of real movements is now shown ₹1,00,000 — both halves are true and the total
     *         is nonsense. One tap resolves it in the honest direction.
     */
    data object ClearGhostProgress : GoalDetailEvent

    /** Dismiss a storage error. */
    data object DismissError : GoalDetailEvent
}
