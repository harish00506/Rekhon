package com.aicfo.ml.llm

import android.content.Context
import com.aicfo.core.common.AppError
import com.aicfo.core.common.Err
import com.aicfo.core.common.Ok
import com.aicfo.core.common.Result
import com.aicfo.core.model.MoneyFormatter
import com.aicfo.domain.engines.chat.ChatIntent
import com.aicfo.domain.engines.chat.FigureKind
import com.aicfo.domain.engines.chat.LlmEngine
import com.aicfo.domain.engines.chat.ToolFigure
import com.aicfo.domain.engines.chat.VerbalisationDraft

/**
 * The model the app ships with (issue 10.5; §19, AI-ARC-007, P-03, P-04).
 *
 * Why:  §19's default engine is an on-device compact model, and there is not one in this build —
 *       a Gemma-class model is hundreds of megabytes that cannot live in a repository, and AICore
 *       needs hardware most phones this app targets do not have. What ships instead is this: a
 *       deterministic verbaliser that says the same things from the same tool results. It is not a
 *       stand-in for testing — it **is** the offline path, and it is the reason the assistant works
 *       in airplane mode on a ₹12,000 phone (P-04). ADR-0053 records what a real model would
 *       replace and what it would not.
 * What: one sentence per intent, from `strings.xml`, with every slot filled from a tool figure.
 * Result: text with no number of its own. It still goes through the guardrail, because the point
 *         of AI-ARC-004 is that *nothing* skips it — including this.
 * Changelog: 2026-09-26 — Created for issue 10.5.
 *
 * Input:  [context] — for the string resources only. Output: an [LlmEngine].
 */
class TemplateLlmEngine(
    private val context: Context,
) : LlmEngine {
    override val id: String = ID

    override fun verbalise(draft: VerbalisationDraft): Result<String, AppError> {
        val figures = draft.results.flatMap { it.figures }
        val template = TEMPLATES[draft.intent] ?: return Ok(context.getString(R.string.llm_nothing))
        val slots = template.slots.map { key -> figures.firstOrNull { it.key == key } }
        return if (slots.any { it == null }) {
            // A missing figure is not an excuse to write around it: the honest sentence is the one
            // that claims nothing (P-03). It carries no numbers, so it passes the guardrail as it
            // should, and the screen shows it rather than a half-answer.
            Ok(context.getString(R.string.llm_nothing))
        } else {
            formatted(template, slots.filterNotNull())
        }
    }

    /**
     * Result: the sentence, or `Err` when a figure is not the kind its slot needs.
     * Why:    a template that printed a count where an amount belongs would produce "₹23.00" from
     *         a transaction count — which the guardrail would then block, correctly, leaving the
     *         user with silence and nobody any the wiser. Failing here says which slot was wrong.
     * Input:  [template]; [figures] — in slot order. Output: `Result<String, AppError>`.
     */
    private fun formatted(
        template: Template,
        figures: List<ToolFigure>,
    ): Result<String, AppError> {
        val arguments =
            figures.map { figure ->
                when (figure.kind) {
                    FigureKind.AMOUNT -> figure.amount?.let(MoneyFormatter::format)
                    FigureKind.COUNT, FigureKind.PERCENT -> figure.number
                    FigureKind.DATE -> figure.isoDate
                }
            }
        return if (arguments.any { it == null }) {
            Err(AppError.Validation(FIELD_FIGURE))
        } else {
            // The spread copies an array of one or two elements, which is the price of
            // `getString`'s vararg — and the only alternative is a `when` over slot counts that
            // would have to grow every time a sentence does.
            @Suppress("SpreadOperator")
            Ok(render(template, figures, arguments.toTypedArray<Any?>()))
        }
    }

    /**
     * Result: the sentence, pluralised on the slot that counts things (§21.6's ICU rule).
     * Why:    "1 transactions" is the kind of wrongness that makes everything around it look
     *         careless — and a count in a sentence is exactly what `plurals` exists for.
     * Input:  [template]; [figures] — in slot order; [arguments] — already formatted.
     * Output: [String].
     */
    @Suppress("SpreadOperator") // one or two elements; the price of getString's vararg
    private fun render(
        template: Template,
        figures: List<ToolFigure>,
        arguments: Array<Any?>,
    ): String {
        val quantity = template.quantitySlot?.let { figures[it].number }
        return if (quantity == null) {
            context.getString(template.resource, *arguments)
        } else {
            context.resources.getQuantityString(template.resource, quantity, *arguments)
        }
    }

    /**
     * One intent's sentence and the figure keys that fill it.
     * Input:  [resource] — a string, or a plurals resource when [quantitySlot] is set;
     *         [slots] — the figure keys, in the order the sentence uses them;
     *         [quantitySlot] — which slot counts things, for the plural.
     * Output: an immutable value.
     */
    private data class Template(
        val resource: Int,
        val slots: List<String>,
        val quantitySlot: Int? = null,
    )

    private companion object {
        /** What this engine calls itself in provenance, so a reply can say who wrote it (P-02). */
        const val ID = "template"
        const val FIELD_FIGURE = "chat.figure"

        /**
         * The whole vocabulary. A key that no tool produces means the sentence is never said, which
         * is why the executor's figure keys and these are checked against each other by a test.
         */
        val TEMPLATES =
            mapOf(
                ChatIntent.SPEND to Template(R.plurals.llm_spend, listOf("total", "count"), quantitySlot = 1),
                ChatIntent.BALANCE to Template(R.string.llm_balance, listOf("liquid")),
                ChatIntent.FORECAST to Template(R.string.llm_forecast, listOf("lowest", "lowestOn")),
                ChatIntent.BUDGET to Template(R.string.llm_budget, listOf("spent")),
                ChatIntent.GOALS to Template(R.string.llm_goals, listOf("required")),
                ChatIntent.AFFORD to Template(R.string.llm_afford, listOf("leftAfter")),
                ChatIntent.HEALTH to Template(R.string.llm_health, listOf("score", "scoreMax")),
                ChatIntent.DEBT to Template(R.string.llm_debt, listOf("saved")),
                ChatIntent.BUYLIST to Template(R.plurals.llm_buylist, listOf("count"), quantitySlot = 0),
                ChatIntent.VEHICLE to Template(R.string.llm_vehicle, listOf("cost", "dueOn")),
            )
    }
}
