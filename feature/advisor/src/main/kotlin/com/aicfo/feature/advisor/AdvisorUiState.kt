package com.aicfo.feature.advisor

import com.aicfo.data.repository.BuyListEntry
import com.aicfo.data.repository.BuyListStatus
import com.aicfo.data.repository.KeptVerdict
import com.aicfo.domain.engines.purchase.InterviewAnswer
import com.aicfo.domain.engines.purchase.PaymentMethod
import com.aicfo.domain.engines.purchase.PurchaseVerdictCard
import com.aicfo.domain.engines.purchase.Urgency

/**
 * Everything the Purchase Advisor screen draws (issue 10.1; §13.2, ARC-004).
 *
 * Why:  one immutable state as a `StateFlow`, so the screen is a function of it and nothing else.
 *       The figures are **the card's**, never recomputed here: a ViewModel that worked out a
 *       remaining balance would be a second definition of it, and the two would disagree the first
 *       time either changed (P-03).
 * What: what the user has typed, the card that came back, the history, and the two transient
 *       states — asking, and an error to show.
 * Result: a screen with no logic of its own.
 * Changelog: 2026-09-25 — Created for issue 10.1.
 *
 * Input:  [item] / [priceRupees] / [method] / [urgency] / [monthlyEmiRupees] — the question, as
 *         typed; [card] — the verdict and its trace, `null` before the first answer; [history] —
 *         past verdicts, newest first; [isAsking]; [errorCode] — an `AppError` key for the banner,
 *         never a message composed here (§21.6).
 * Output: an immutable value.
 */
data class AdvisorUiState(
    val item: String = "",
    val priceRupees: String = "",
    val method: PaymentMethod = PaymentMethod.CASH,
    val urgency: Urgency = Urgency.ROUTINE,
    val monthlyEmiRupees: String = "",
    val card: PurchaseVerdictCard? = null,
    val history: List<KeptVerdict> = emptyList(),
    val buyList: List<BuyListEntry> = emptyList(),
    val wishName: String = "",
    val wishPriceRupees: String = "",
    val isAsking: Boolean = false,
    val errorCode: String? = null,
) {
    /** Whether a wish can be added: it needs a name and a price, like any other question. */
    val canAddWish: Boolean
        get() = wishName.isNotBlank() && wishPriceRupees.toLongOrNull()?.let { it > 0 } == true

    /** Whether the question is complete enough to ask. An EMI needs its instalment (§13.1 step 3). */
    val canAsk: Boolean
        get() =
            item.isNotBlank() &&
                priceRupees.toLongOrNull()?.let { it > 0 } == true &&
                (method != PaymentMethod.EMI || monthlyEmiRupees.toLongOrNull()?.let { it > 0 } == true)
}

/**
 * What the user can do on this screen.
 *
 * Why:  a sealed interface so a `when` in the ViewModel cannot silently ignore a new event
 *       (ARC-004). Nothing here moves money: the advisor recommends, the user decides (P-07).
 * Changelog: 2026-09-25 — Created for issue 10.1.
 */
sealed interface AdvisorEvent {
    /** The item's name changed. */
    data class ItemChanged(val item: String) : AdvisorEvent

    /** The price changed, in whole rupees as typed. */
    data class PriceChanged(val rupees: String) : AdvisorEvent

    /** The monthly instalment changed, in whole rupees as typed. */
    data class EmiChanged(val rupees: String) : AdvisorEvent

    /** How it would be paid for. */
    data class MethodChanged(val method: PaymentMethod) : AdvisorEvent

    /** How much it can wait. */
    data class UrgencyChanged(val urgency: Urgency) : AdvisorEvent

    /** Ask the advisor. */
    data object Ask : AdvisorEvent

    /** Open a past verdict again (§13.2 — "revisit why a past decision was made"). */
    data class OpenKept(val id: String) : AdvisorEvent

    /** Dismiss the error banner. */
    data object DismissError : AdvisorEvent

    /**
     * Anything the buy list does (§13.3).
     * Why:  a marker, so the ViewModel can hand every buy-list event to one place instead of
     *       growing a `when` that does two jobs.
     */
    sealed interface BuyList : AdvisorEvent

    /** The new wish's name changed. */
    data class WishNameChanged(val name: String) : BuyList

    /** The new wish's price changed, in whole rupees as typed. */
    data class WishPriceChanged(val rupees: String) : BuyList

    /** Put the wish on the list (§13.3) rather than buying it now. */
    data object AddWish : BuyList

    /** Answer the next interview question for a wish. */
    data class AnswerWish(val itemId: String, val answer: InterviewAnswer) : BuyList

    /** Ask the advisor what it says about a wish today (issue 10.1's re-evaluation). */
    data class AdviseWish(val itemId: String) : BuyList

    /** Move a wish — the user's decision, never the app's (§13.3, P-07). */
    data class MoveWish(val itemId: String, val status: BuyListStatus) : BuyList
}
