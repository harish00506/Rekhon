package com.aicfo.domain.engines.chat

import com.aicfo.domain.engines.guardrail.GuardrailEvidence
import java.time.LocalDate
import java.time.format.DateTimeParseException

/**
 * The one mapping from tool figures to what the guardrail will accept (ADR-0080; AI-ARC-004, P-03).
 *
 * Why:  two layers now need to know which figures a reply is allowed to contain, and they must not
 *       each decide for themselves. `IntentRoutingChatEngine` needs it to build AI-GRD's allowlist;
 *       `:ml:llm`'s sanitiser needs it to judge a neural model's draft **by the same standard the
 *       guardrail will apply a moment later**. When those two disagreed, the stricter one won
 *       silently: a model writing "₹61,427" for an amount the guardrail would have accepted was
 *       refused before the guardrail ever saw it, and the neural path quietly stopped being used
 *       for every answer that mentions money.
 * What: the kind split, preserved — a count of 12 and ₹12 are different claims (GRD-002).
 * Result: the evidence both layers read.
 * Changelog: 2026-10-10 — Extracted from `IntentRoutingChatEngine.evidenceOf` for ADR-0080.
 */
object ChatEvidence {
    /**
     * Turns tool figures into guardrail evidence.
     * Why:    a figure carries its kind because the guardrail checks a claim against values **of
     *         that kind**; collapsing them would let a model state a count as an amount.
     * Result: the evidence. A figure with no value for its kind contributes nothing — it is not a
     *         zero, and a zero here would be a figure the reply was allowed to state.
     * Input:  [figures] — what the tools returned.
     * Output: [GuardrailEvidence].
     */
    fun of(figures: List<ToolFigure>): GuardrailEvidence =
        GuardrailEvidence(
            amounts = figures.mapNotNull { if (it.kind == FigureKind.AMOUNT) it.amount else null },
            percents = figures.mapNotNull { if (it.kind == FigureKind.PERCENT) it.number else null },
            counts = figures.mapNotNull { if (it.kind == FigureKind.COUNT) it.number else null },
            dates = figures.mapNotNull { if (it.kind == FigureKind.DATE) it.isoDate?.let(::parseDate) else null },
        )

    /** Result: an ISO date, or `null` when it is not one. Input: [isoDate]. Output: `LocalDate?`. */
    private fun parseDate(isoDate: String): LocalDate? =
        try {
            LocalDate.parse(isoDate)
        } catch (_: DateTimeParseException) {
            null
        }
}
