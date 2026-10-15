package com.aicfo.ml.llm

import com.aicfo.core.model.Money
import com.aicfo.domain.engines.chat.ChatIntent
import com.aicfo.domain.engines.chat.FigureKind
import com.aicfo.domain.engines.chat.ToolFigure
import com.aicfo.domain.engines.chat.ToolName
import com.aicfo.domain.engines.chat.ToolResult
import com.aicfo.domain.engines.chat.VerbalisationDraft
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What the model is allowed to be told (ADR-0079; §19.4, P-01, P-03).
 *
 * Why:  this prompt is the **entire** input to the model, so a test over it is a test of exactly
 *       what crosses out of the deterministic part of the app. Two things must hold forever: the
 *       figures arrive already computed and already formatted, so there is nothing left for the
 *       model to work out (P-03); and nothing else arrives at all — no transaction, no merchant,
 *       no account name, no question history (P-01, §19.4).
 * What: the shape of the prompt, the rendering of each figure kind, and the empty cases.
 * Result: a prompt that is instructions plus figures, and the list of values the reply may use.
 * Changelog: 2026-10-10 — Created for ADR-0079.
 */
class VerbalisationPromptTest {
    @Test
    fun `the prompt carries the instruction, the intent and one line per figure`() {
        val verbalisation =
            VerbalisationPrompt.of(
                INSTRUCTION,
                draft(
                    ChatIntent.SPEND,
                    ToolFigure("total", FigureKind.AMOUNT, amount = Money(61_427_00L)),
                    ToolFigure("count", FigureKind.COUNT, number = 23),
                ),
            )!!

        assertTrue(verbalisation.prompt.startsWith(INSTRUCTION))
        assertTrue("Intent: SPEND" in verbalisation.prompt)
        assertTrue("- total: ₹61,427.00" in verbalisation.prompt)
        assertTrue("- count: 23" in verbalisation.prompt)
        assertTrue("the cue is what stops a small model writing a preamble", verbalisation.prompt.endsWith("Sentence:"))
    }

    @Test
    fun `the canonical values are exactly the rendered figures`() {
        val verbalisation =
            VerbalisationPrompt.of(
                INSTRUCTION,
                draft(
                    ChatIntent.FORECAST,
                    ToolFigure("lowest", FigureKind.AMOUNT, amount = Money(1_23_456_78L)),
                    ToolFigure("lowestOn", FigureKind.DATE, isoDate = "2026-11-03"),
                    ToolFigure("horizonDays", FigureKind.COUNT, number = 90),
                ),
            )!!

        assertEquals(listOf("₹1,23,456.78", "2026-11-03", "90"), verbalisation.canonical)
    }

    @Test
    fun `a percentage goes in as a bare number, so the model can put the sign where it reads best`() {
        val share = ToolFigure("share", FigureKind.PERCENT, number = 37)

        val verbalisation = VerbalisationPrompt.of(INSTRUCTION, draft(ChatIntent.HEALTH, share))!!

        assertEquals(listOf("37"), verbalisation.canonical)
    }

    @Test
    fun `a figure with no value of its kind is dropped rather than rendered as null`() {
        // "null" in a prompt is text a model will happily reuse in a sentence.
        val verbalisation =
            VerbalisationPrompt.of(
                INSTRUCTION,
                draft(
                    ChatIntent.BALANCE,
                    ToolFigure("liquid", FigureKind.AMOUNT, amount = Money(5_000_00L)),
                    ToolFigure("broken", FigureKind.AMOUNT, amount = null),
                ),
            )!!

        assertEquals(listOf("₹5,000.00"), verbalisation.canonical)
        assertFalse("null" in verbalisation.prompt)
    }

    @Test
    fun `a draft with nothing to say produces no prompt at all`() {
        val valueless = ToolFigure("total", FigureKind.AMOUNT, amount = null)

        assertNull(VerbalisationPrompt.of(INSTRUCTION, VerbalisationDraft(ChatIntent.SPEND, emptyList())))
        assertNull(VerbalisationPrompt.of(INSTRUCTION, draft(ChatIntent.SPEND, valueless)))
    }

    @Test
    fun `an unrouted intent still names something, so the prompt never says null`() {
        val verbalisation =
            VerbalisationPrompt.of(INSTRUCTION, draft(null, ToolFigure("count", FigureKind.COUNT, number = 2)))!!

        assertTrue("Intent: GENERAL" in verbalisation.prompt)
    }

    @Test
    fun `the prompt holds nothing but the instruction, the intent and the figures (P-01)`() {
        val total = ToolFigure("total", FigureKind.AMOUNT, amount = Money(100L))

        val verbalisation = VerbalisationPrompt.of(INSTRUCTION, draft(ChatIntent.SPEND, total))!!

        val body = verbalisation.prompt.removePrefix(INSTRUCTION).trim().lines().filter { it.isNotBlank() }
        assertEquals(listOf("Intent: SPEND", "Figures:", "- total: ₹1.00", "Sentence:"), body)
    }

    @Test
    fun `the accepted set is the guardrail's own, not the prompt's literal strings`() {
        // ADR-0080. The prompt shows ₹61,427.00; AI-GRD would accept ₹61,427 for the same figure,
        // so the sanitiser must too — the first version did not, and that silently vetoed the
        // neural path for nearly every answer that mentions money.
        val total = ToolFigure("total", FigureKind.AMOUNT, amount = Money(61_427_00L))

        val accepted = VerbalisationPrompt.of(INSTRUCTION, draft(ChatIntent.SPEND, total))!!.accepted

        assertTrue("the canonical rendering", "₹61,427.00" in accepted)
        assertTrue("the whole-rupee short form a person would write", "₹61,427" in accepted)
        assertFalse("a figure rounded to the nearest thousand is a different number", "₹61,000" in accepted)
    }

    @Test
    fun `an exact lakh figure may be said in words, because the app says it that way`() {
        val amount = ToolFigure("target", FigureKind.AMOUNT, amount = Money(1_50_000_00L))

        val accepted = VerbalisationPrompt.of(INSTRUCTION, draft(ChatIntent.GOALS, amount))!!.accepted

        assertTrue("₹1.5lakh" in accepted)
    }

    @Test
    fun `a date may be said the way a person writes it`() {
        val date = ToolFigure("dueOn", FigureKind.DATE, isoDate = "2026-11-03")

        val accepted = VerbalisationPrompt.of(INSTRUCTION, draft(ChatIntent.VEHICLE, date))!!.accepted

        assertTrue("the stored form", "2026-11-03" in accepted)
        assertTrue("the written form", "3nov2026" in accepted)
    }

    private fun draft(
        intent: ChatIntent?,
        vararg figures: ToolFigure,
    ) = VerbalisationDraft(intent, listOf(ToolResult(ToolName.QUERY_SPEND, figures.toList())))

    private companion object {
        const val INSTRUCTION = "Write one sentence."
    }
}
