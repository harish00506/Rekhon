package com.aicfo.ml.llm

import com.aicfo.core.common.AppError
import com.aicfo.core.common.Err
import com.aicfo.core.common.Ok
import com.aicfo.core.common.Result
import com.aicfo.core.model.Money
import com.aicfo.domain.engines.chat.ChatIntent
import com.aicfo.domain.engines.chat.FigureKind
import com.aicfo.domain.engines.chat.ToolFigure
import com.aicfo.domain.engines.chat.ToolName
import com.aicfo.domain.engines.chat.ToolResult
import com.aicfo.domain.engines.chat.VerbalisationDraft
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What a neural model is not allowed to get away with (ADR-0079; AI-ARC-004, P-03).
 *
 * Why:  every case here was chosen because a small instruction model actually does it: it rounds a
 *       figure, it adds a second number it worked out itself, it writes three sentences when asked
 *       for one, it wraps the answer in markdown, it echoes the cue back. The guardrail catches the
 *       figure violations and would pass the rest, so this is where the rest is caught — and a
 *       rejection is cheap, because the template verbaliser then answers instead.
 * What: the digit rule, the shape rules, and the decimal point that nearly broke all of them.
 * Result: either one clean sentence whose every digit was supplied, or an error.
 * Changelog: 2026-10-10 — Created for ADR-0079.
 */
class DraftSanitiserTest {
    @Test
    fun `a clean sentence with the figures it was given passes`() {
        val text = DraftSanitiser.sanitise("You spent ₹61,427.00 across 23 transactions.", spend()).expectOk()

        assertEquals("You spent ₹61,427.00 across 23 transactions.", text)
    }

    @Test
    fun `an amount's decimal point does not end the sentence`() {
        // The regression this test exists for: splitting on the first full stop truncated the
        // sentence inside ₹61,427.00, which then failed the figure check — silently disabling the
        // neural path for every answer that mentions money, which is nearly all of them.
        val text = DraftSanitiser.sanitise("Your balance is ₹61,427.00 right now.", balance()).expectOk()

        assertEquals("Your balance is ₹61,427.00 right now.", text)
    }

    @Test
    fun `a rounded figure is a different claim, and is refused`() {
        val error = DraftSanitiser.sanitise("You spent about ₹61,000 across 23 transactions.", spend()).expectErr()

        assertEquals("chat.draft.figure", (error as AppError.Validation).field)
    }

    @Test
    fun `a number the model worked out itself is refused`() {
        val reply = "You spent ₹61,427.00 across 23 transactions, about 5 a week."

        val error = DraftSanitiser.sanitise(reply, spend()).expectErr()

        assertEquals("chat.draft.figure", (error as AppError.Validation).field)
    }

    @Test
    fun `a supplied value inside a longer invented number is still a violation`() {
        val error = DraftSanitiser.sanitise("You made 230 payments of ₹61,427.00.", spend()).expectErr()

        assertEquals("chat.draft.figure", (error as AppError.Validation).field)
    }

    @Test
    fun `only the first sentence survives`() {
        val text =
            DraftSanitiser
                .sanitise("You spent ₹61,427.00 across 23 transactions. Would you like a breakdown?", spend())
                .expectOk()

        assertEquals("You spent ₹61,427.00 across 23 transactions.", text)
    }

    @Test
    fun `markdown, quotes and the echoed cue are stripped`() {
        val text =
            DraftSanitiser
                .sanitise("Sentence: \"**You spent ₹61,427.00 across 23 transactions.**\"", spend())
                .expectOk()

        assertEquals("You spent ₹61,427.00 across 23 transactions.", text)
    }

    @Test
    fun `the whole-rupee short form is accepted, because the guardrail accepts it`() {
        // ADR-0080's reason for existing. A model told "₹61,427.00" very often writes "₹61,427",
        // and AI-GRD would have passed that — so refusing it here was a veto nobody could see.
        val text = DraftSanitiser.sanitise("You spent ₹61,427 across 23 transactions.", spend()).expectOk()

        assertEquals("You spent ₹61,427 across 23 transactions.", text)
    }

    @Test
    fun `a stray space inside a figure is a habit, not a different number`() {
        val text = DraftSanitiser.sanitise("You spent ₹ 61,427.00 across 23 transactions.", spend()).expectOk()

        assertEquals("You spent ₹ 61,427.00 across 23 transactions.", text)
    }

    @Test
    fun `western grouping is not a rendering this app produces`() {
        // ₹61,427.00 is Indian grouping. "₹61,427.00" and "₹61427.00" are the same claim to a
        // reader, but the allowlist is the formatter's own output and a model that regroups an
        // amount is a model that may regroup a lakh figure wrongly.
        val error = DraftSanitiser.sanitise("You spent ₹61427.00 across 23 transactions.", spend()).expectErr()

        assertEquals("chat.draft.figure", (error as AppError.Validation).field)
    }

    @Test
    fun `a reply that says none of the figures is not an answer`() {
        val error = DraftSanitiser.sanitise("I had a look at your spending for you.", spend()).expectErr()

        assertEquals("chat.draft.figures", (error as AppError.Validation).field)
    }

    @Test
    fun `the figure list read back is not an answer, however correct its figures are`() {
        // Found by the frozen set (VE-S07), not by review: every figure is right, so every figure
        // check passes and the user is shown the prompt's own scaffolding.
        val error = DraftSanitiser.sanitise("- total: ₹61,427.00\n- count: 23", spend()).expectErr()

        assertEquals("chat.draft.shape", (error as AppError.Validation).field)
    }

    @Test
    fun `a reply that reads the prompt back is not an answer either`() {
        val error = DraftSanitiser.sanitise("Intent: SPEND Figures: total ₹61,427.00", spend()).expectErr()

        assertEquals("chat.draft.shape", (error as AppError.Validation).field)
    }

    @Test
    fun `the figure list on one line is caught by its keys`() {
        // VE-S09: the list-lines rule cannot see this, and every figure in it is correct.
        val error = DraftSanitiser.sanitise("total: ₹61,427.00, count: 23", spend()).expectErr()

        assertEquals("chat.draft.shape", (error as AppError.Validation).field)
    }

    @Test
    fun `a stray label is stripped rather than refused`() {
        // VE-S10: the sentence under the label is right, and refusing costs the user a good answer
        // over a habit.
        val text = DraftSanitiser.sanitise("Answer: You spent ₹61,427.00 across 23 transactions.", spend()).expectOk()

        assertEquals("You spent ₹61,427.00 across 23 transactions.", text)
    }

    @Test
    fun `a terse sentence may open with a figure`() {
        // VE-R08: an earlier rule required a letter first, which refused this — ADR-0080's own
        // mistake, made again. The keys and the scaffold are what mark a non-sentence, not the
        // first character.
        val text = DraftSanitiser.sanitise("23 transactions, ₹61,427.00 spent.", spend()).expectOk()

        assertEquals("23 transactions, ₹61,427.00 spent.", text)
    }

    @Test
    fun `a misstated amount is reported as a figure fault, not as silence`() {
        // VE-D02's lesson: "about ₹2 lakh" for ₹1.5 lakh states no *permitted* figure, so the
        // weaker rule used to fire first and call a misstated amount "said nothing".
        val goals =
            verbalisation(ChatIntent.GOALS, ToolFigure("required", FigureKind.AMOUNT, amount = Money(1_50_000_00L)))

        val error = DraftSanitiser.sanitise("Your goals need about ₹2 lakh a month.", goals).expectErr()

        assertEquals("chat.draft.figure", (error as AppError.Validation).field)
    }

    @Test
    fun `an empty reply is an error, not an empty sentence`() {
        val error = DraftSanitiser.sanitise("   \n  ", spend()).expectErr()

        assertEquals("chat.draft.empty", (error as AppError.Validation).field)
    }

    @Test
    fun `an essay is refused on length before anything else`() {
        val essay = "You spent ₹61,427.00 and " + "the market moves in cycles and ".repeat(20)

        val error = DraftSanitiser.sanitise(essay, spend()).expectErr()

        assertEquals("chat.draft.length", (error as AppError.Validation).field)
    }

    @Test
    fun `a sentence with no figures at all is fine when none were supplied`() {
        val text = DraftSanitiser.sanitise("I could not find anything to answer that with.", nothing()).expectOk()

        assertEquals("I could not find anything to answer that with.", text)
    }

    @Test
    fun `a reply with no terminator is kept whole rather than dropped`() {
        val text = DraftSanitiser.sanitise("Your balance is ₹61,427.00", balance()).expectOk()

        assertEquals("Your balance is ₹61,427.00", text)
    }

    private fun Result<String, AppError>.expectOk(): String {
        assertTrue("expected Ok but was $this", this is Ok)
        return (this as Ok).value
    }

    private fun Result<String, AppError>.expectErr(): AppError {
        assertTrue("expected Err but was $this", this is Err)
        return (this as Err).error
    }

    /**
     * The spend draft every case is judged against: ₹61,427.00 over 23 transactions.
     * Why: built through the real [VerbalisationPrompt] rather than from hand-written strings, so
     *      these tests exercise the guardrail's own rendering set (ADR-0080) and not a copy of it.
     */
    private fun spend(): Verbalisation =
        verbalisation(
            ChatIntent.SPEND,
            ToolFigure("total", FigureKind.AMOUNT, amount = Money(61_427_00L)),
            ToolFigure("count", FigureKind.COUNT, number = 23),
        )

    /** One amount, for the cases about a decimal point. */
    private fun balance(): Verbalisation =
        verbalisation(ChatIntent.BALANCE, ToolFigure("liquid", FigureKind.AMOUNT, amount = Money(61_427_00L)))

    /** No figures at all — the "I could not find anything" case. */
    private fun nothing(): Verbalisation = Verbalisation(prompt = "", canonical = emptyList(), accepted = emptySet())

    private fun verbalisation(
        intent: ChatIntent,
        vararg figures: ToolFigure,
    ): Verbalisation =
        VerbalisationPrompt.of(
            "Write one sentence.",
            VerbalisationDraft(intent, listOf(ToolResult(ToolName.QUERY_SPEND, figures.toList()))),
        )!!
}
