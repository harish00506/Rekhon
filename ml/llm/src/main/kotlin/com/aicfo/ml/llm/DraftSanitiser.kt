package com.aicfo.ml.llm

import com.aicfo.core.common.AppError
import com.aicfo.core.common.Err
import com.aicfo.core.common.Ok
import com.aicfo.core.common.Result
import com.aicfo.domain.engines.guardrail.FigureRenderings

/**
 * The check between a neural model and the guardrail (ADR-0079, ADR-0080; AI-ARC-004, P-03).
 *
 * Why:  AI-GRD is the gate that matters and it runs on every reply either way. This exists because
 *       of what a small instruction model does *besides* being wrong about numbers: it writes a
 *       preamble, it answers in three paragraphs, it wraps the sentence in markdown, it repeats the
 *       prompt back. None of that is a figure violation, so the guardrail would pass it, and the
 *       user would read something that does not sound like this app. So the model's output is held
 *       to the shape the template verbaliser already produces — one short sentence.
 *
 *       **On figures it is exactly as strict as the guardrail and no stricter** (ADR-0080). The
 *       first version compared against the literal strings the prompt showed, which refused
 *       "₹61,427" for an amount AI-GRD would have accepted — so the neural path was being vetoed
 *       by a rule nobody could see, for nearly every answer that mentions money. The accepted set
 *       now comes from [FigureRenderings], the guardrail's own definition of the same figure
 *       written differently.
 * What: strips the decoration, keeps the first sentence, and refuses any digit that is not part of
 *       a rendering the app itself produces for a figure the tools returned.
 * Result: the sentence, or `Err` — and an `Err` means the caller answers from the template
 *         instead, which is a worse sentence and not a wrong one.
 * Changelog: 2026-10-10 — Created for ADR-0079.
 *            2026-10-10 — ADR-0080: judges figures by [FigureRenderings] rather than by the
 *            prompt's literal strings, after the frozen set measured the false-refusal rate.
 */
internal object DraftSanitiser {
    /**
     * Cleans one reply and holds it to its figures.
     * Why:    the digit rule is the load-bearing one: every number in the text has to be inside a
     *         rendering of a figure a tool returned, so "₹61,427.00", "₹61,427" and "₹61,427.00"
     *         with a stray space are all the same claim, while "about ₹61,000" is not — a rounded
     *         figure at that granularity is a different number that no engine produced (GRD-002).
     *         Checking it by deleting the accepted renderings and looking for leftover digits is
     *         the version of this rule a reviewer can hold in their head.
     * Result: `Ok(sentence)`; `Err(AppError.Validation)` when the reply is empty, too long, states
     *         none of the figures, or contains a digit nothing accounts for.
     * Input:  [raw] — exactly what the model returned; [verbalisation] — the prompt that produced
     *         it, carrying the canonical values and the accepted renderings.
     * Output: `Result<String, AppError>`.
     */
    fun sanitise(
        raw: String,
        verbalisation: Verbalisation,
    ): Result<String, AppError> {
        val sentence = firstSentence(undecorated(raw))
        val comparable = FigureRenderings.normalise(sentence)
        val accepted = verbalisation.accepted + verbalisation.canonical.map(FigureRenderings::normalise)
        return when {
            sentence.isEmpty() -> Err(AppError.Validation(FIELD_EMPTY))
            sentence.length > MAX_CHARS -> Err(AppError.Validation(FIELD_LENGTH))
            isNotProse(raw, sentence, verbalisation.keys) -> Err(AppError.Validation(FIELD_SHAPE))
            // The digit check comes before the figure check, and the frozen set is why: "about
            // ₹2 lakh" for ₹1.5 lakh states no *permitted* figure, so the weaker rule fired first
            // and reported "said nothing" for what is actually a misstated amount. Two refusals
            // with the same effect and very different meanings — the log and the eval both need the
            // precise one (VE-D02, VE-D08).
            hasUnbackedDigit(comparable, accepted) -> Err(AppError.Validation(FIELD_FIGURE))
            statesNoFigure(comparable, accepted) -> Err(AppError.Validation(FIELD_FIGURES))
            else -> Ok(sentence)
        }
    }

    /**
     * Result: true when the reply is not a sentence — a list, or the prompt read back.
     * Why:    **the frozen set found this and nothing else would have** (VE-S07, VE-S08). A model
     *         handed `- total: ₹61,427.00` often answers with the list it was given. Every figure
     *         in it is correct, so the digit check passes, the guardrail passes, and the user is
     *         shown the app's own prompt scaffolding as though it were an answer. It is not a
     *         figure failure at all, which is exactly why it needed its own rule.
     * Result: `true` for two or more list lines in the raw reply, a sentence carrying the prompt's
     *         own scaffold words, or one that reads a figure **key** back at the user.
     * Input:  [raw] — before whitespace was collapsed, so line starts are still visible;
     *         [sentence] — the cleaned candidate; [keys] — the figure names the prompt used.
     * Output: [Boolean].
     * Changelog: 2026-10-10 — Created for ADR-0080, from VE-S07/VE-S08.
     *            2026-10-10 — VE-S09/VE-R08: the key-echo rule replaces "must begin with a
     *            letter". The list-line rule cannot see `total: ₹61,427.00, count: 23` on one
     *            line, and the letter rule refused `23 transactions, ₹61,427.00 spent.` — a terse
     *            sentence that is perfectly true, which is this ADR's own mistake made again.
     */
    private fun isNotProse(
        raw: String,
        sentence: String,
        keys: List<String>,
    ): Boolean {
        val listLines = raw.lines().count { line -> line.trimStart().startsWith("- ") }
        val echoesScaffold = SCAFFOLD.any { it in sentence }
        val echoesKey = keys.any { key -> "$key:" in sentence || "${key.lowercase()}:" in sentence.lowercase() }
        return listLines >= LIST_LINES || echoesScaffold || echoesKey
    }

    /**
     * Result: true when the reply mentions none of the figures it was given.
     * Why:    a model that answers "I had a look at your spending for you" has not answered. It is
     *         not a figure violation — there is nothing wrong in it — so it has to be caught here
     *         or the user gets a sentence with no information in it.
     * Input:  [comparable] — the normalised sentence; [accepted]. Output: [Boolean].
     */
    private fun statesNoFigure(
        comparable: String,
        accepted: Set<String>,
    ): Boolean = accepted.isNotEmpty() && accepted.none { it in comparable }

    /**
     * Result: the reply without markdown, quotes, the echoed cue, or runs of whitespace.
     * Why:    these are the model's habits rather than its meaning, and a sentence that arrives
     *         wrapped in asterisks would otherwise reach the screen that way.
     * Input:  [raw]. Output: [String].
     */
    private fun undecorated(raw: String): String =
        raw
            .replace(CODE_FENCE, " ")
            .replace(CUE, "")
            .filterNot { it in DECORATION }
            .replace(WHITESPACE, " ")
            .trim()
            .trim('"', '\'')
            .trim()

    /**
     * Result: the text up to and including its first sentence-ending mark, or all of it when there
     *         is none.
     * Why:    the prompt asks for one sentence; keeping the first one makes that true rather than
     *         hoping. Anything after it is the model continuing to talk, which is exactly what the
     *         template verbaliser never does.
     *
     *         **A full stop between two digits is not the end of a sentence.** Every amount this
     *         app says carries one — ₹61,427.00 — so the naive version of this function truncated
     *         the sentence inside the figure, which then failed the figure check and silently
     *         disabled the neural path for every amount. Found by test, not in the wild.
     * Input:  [text] — already undecorated. Output: [String].
     */
    private fun firstSentence(text: String): String {
        val end =
            text.indices.firstOrNull { index ->
                text[index] in TERMINATORS && !(index + 1 < text.length && text[index + 1].isDigit())
            }
        return if (end == null) text else text.substring(0, end + 1).trim()
    }

    /**
     * Result: true when the text holds a digit that no accepted rendering accounts for.
     * Why:    this is the rule that makes the model unable to state a figure of its own. The
     *         renderings are removed longest-first, so a short one is never deleted out of the
     *         middle of a longer one and left to look like a violation.
     * Input:  [comparable] — the normalised sentence; [accepted] — normalised renderings.
     * Output: [Boolean].
     */
    private fun hasUnbackedDigit(
        comparable: String,
        accepted: Set<String>,
    ): Boolean =
        accepted
            .sortedByDescending { it.length }
            .fold(comparable) { remaining, rendering -> remaining.replace(rendering, " ") }
            .any { it.isDigit() }

    private const val MAX_CHARS = 320
    private const val FIELD_EMPTY = "chat.draft.empty"
    private const val FIELD_LENGTH = "chat.draft.length"
    private const val FIELD_FIGURES = "chat.draft.figures"
    private const val FIELD_FIGURE = "chat.draft.figure"
    private const val FIELD_SHAPE = "chat.draft.shape"

    /** Two of them is a list; one leading dash can be a dash. */
    private const val LIST_LINES = 2

    /** The prompt's own words. A reply containing them is reading the prompt back. */
    private val SCAFFOLD = listOf("Intent:", "Figures:")

    private const val DECORATION = "*_`#"
    private const val TERMINATORS = ".!?"
    private val CODE_FENCE = Regex("```[a-zA-Z]*")

    /**
     * A single short label at the very start — "Sentence:", "Answer:", "Reply:", "Note:".
     * It is stripped rather than refused (VE-S10): the sentence underneath it is usually right, and
     * refusing would cost the user a good answer over a habit.
     */
    private val CUE = Regex("^\\s*[A-Za-z]{2,12}:\\s*")
    private val WHITESPACE = Regex("\\s+")
}
