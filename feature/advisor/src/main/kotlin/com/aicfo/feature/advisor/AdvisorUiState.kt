package com.aicfo.feature.advisor

import com.aicfo.data.repository.KeptVerdict
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
    val isAsking: Boolean = false,
    val errorCode: String? = null,
) {
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
}
