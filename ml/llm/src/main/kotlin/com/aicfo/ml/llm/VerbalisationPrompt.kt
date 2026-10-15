package com.aicfo.ml.llm

import com.aicfo.core.model.MoneyFormatter
import com.aicfo.domain.engines.chat.ChatEvidence
import com.aicfo.domain.engines.chat.FigureKind
import com.aicfo.domain.engines.chat.ToolFigure
import com.aicfo.domain.engines.chat.VerbalisationDraft
import com.aicfo.domain.engines.guardrail.FigureRenderings

/**
 * What the model is sent, and what it is allowed to say back (ADR-0079, ADR-0080; §19.4, P-01, P-03).
 *
 * Why:  this prompt is the **entire** input to the model, so a test over it is a test of exactly
 *       what crosses out of the deterministic part of the app. Two things must hold forever: the
 *       figures arrive already computed and already formatted, so there is nothing left for the
 *       model to work out (P-03); and nothing else arrives at all — no transaction, no merchant, no
 *       account name, no question history (P-01, §19.4).
 * What: the instruction (from `ai/chat/system-prompt.md`, mirrored into this module's assets), the
 *       intent, and one line per figure — name and already-rendered value.
 * Result: the prompt, the canonical values the prompt showed, and **every rendering the app itself
 *         would accept for them** ([FigureRenderings], ADR-0080). [DraftSanitiser] holds the reply
 *         to that set, and AI-GRD checks it again afterwards.
 * Changelog: 2026-10-10 — Created for ADR-0079.
 *            2026-10-10 — ADR-0080: carries the accepted renderings as well as the canonical ones,
 *            so the sanitiser is no stricter than the guardrail that follows it.
 */
internal object VerbalisationPrompt {
    /**
     * Builds the prompt for one draft.
     * Why:    a draft with no usable figure has nothing for a model to say, and asking anyway is
     *         how a model is invited to invent one. That case returns `null` and the caller falls
     *         back to the template's "I could not find anything" sentence.
     * Result: a [Verbalisation], or `null` when the draft carries no figure with a value.
     * Input:  [instruction] — the verbaliser prompt text; [draft] — the intent and tool results,
     *         and nothing else (P-01).
     * Output: [Verbalisation]?
     */
    fun of(
        instruction: String,
        draft: VerbalisationDraft,
    ): Verbalisation? {
        val figures = draft.results.flatMap { it.figures }
        val rendered = figures.mapNotNull(::rendered)
        if (rendered.isEmpty()) return null
        val lines = rendered.joinToString("\n") { (key, value) -> "- $key: $value" }
        val prompt =
            buildString {
                append(instruction.trim())
                append("\n\nIntent: ")
                append(draft.intent?.name ?: UNKNOWN_INTENT)
                append("\nFigures:\n")
                append(lines)
                append("\n\nSentence:")
            }
        return Verbalisation(
            prompt = prompt,
            canonical = rendered.map { (_, value) -> value },
            accepted = FigureRenderings.of(ChatEvidence.of(figures)),
            keys = rendered.map { (key, _) -> key },
        )
    }

    /**
     * Renders one figure the way the prompt asks the model to repeat it.
     * Why:    MNY-001's paise are not a thing anyone says out loud, and the Indian grouping is the
     *         app's own (₹1,23,456.78) — so the formatting happens here, deterministically, and the
     *         model only copies. A figure whose value is missing for its kind is dropped rather
     *         than rendered as "null", which is the sort of text a model will happily reuse.
     *
     *         This is the **canonical** rendering, not the only permitted one: the model may also
     *         write any other form the app itself would use for the same figure, which is
     *         [FigureRenderings]' business and not this function's.
     * Result: `key to value`, or `null` when the figure has no value of its kind.
     * Input:  [figure] — one tool figure. Output: `Pair<String, String>?`.
     */
    private fun rendered(figure: ToolFigure): Pair<String, String>? {
        val value =
            when (figure.kind) {
                FigureKind.AMOUNT -> figure.amount?.let(MoneyFormatter::format)
                FigureKind.COUNT, FigureKind.PERCENT -> figure.number?.toString()
                FigureKind.DATE -> figure.isoDate
            }
        return value?.let { figure.key to it }
    }

    /** What the prompt calls an intent the router could not name. */
    private const val UNKNOWN_INTENT = "GENERAL"
}

/**
 * One prompt, the values it showed, and every rendering of them the app would accept.
 * Input:  [prompt] — the whole of the model's input; [canonical] — the figure values as the prompt
 *         wrote them; [accepted] — those and every other rendering the app itself produces for the
 *         same figures, normalised (ADR-0080); [keys] — the figure names the prompt used, which a
 *         reply must not read back (VE-S09).
 * Output: an immutable value.
 * Changelog: 2026-10-10 — Created for ADR-0079; `accepted` added by ADR-0080, `keys` by VE-S09.
 */
internal data class Verbalisation(
    val prompt: String,
    val canonical: List<String>,
    val accepted: Set<String>,
    val keys: List<String> = emptyList(),
)
