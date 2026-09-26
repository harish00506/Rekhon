package com.aicfo.ml.llm

import androidx.test.core.app.ApplicationProvider
import com.aicfo.core.common.Err
import com.aicfo.core.common.Ok
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
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * What the shipped verbaliser must get right (issue 10.5; §19, P-03, P-04).
 *
 * Why:  this is the app's default model, so it is the one that actually speaks to users today. The
 *       ways it can be wrong are narrow and specific: it can state a figure no tool gave it, it can
 *       put a count where an amount belongs, or it can write around a missing figure and produce a
 *       half-answer that reads like a whole one.
 * What: every intent's sentence, the missing-figure case, the wrong-kind case, and determinism.
 * Result: words over figures, and nothing else.
 * Changelog: 2026-09-26 — Created for issue 10.5.
 */
@RunWith(RobolectricTestRunner::class)
class TemplateLlmEngineTest {
    private val engine = TemplateLlmEngine(ApplicationProvider.getApplicationContext())

    @Test
    fun `it names itself, so a reply can say who wrote it`() {
        assertEquals("template", engine.id)
    }

    @Test
    fun `a spend answer uses the tool's own total and count`() {
        val text =
            engine.verbalise(
                draft(
                    ChatIntent.SPEND,
                    ToolFigure("total", FigureKind.AMOUNT, amount = Money(61_427_00L)),
                    ToolFigure("count", FigureKind.COUNT, number = 23),
                ),
            ).expectOk()

        assertEquals("You spent ₹61,427.00 in that period, across 23 transactions.", text)
    }

    @Test
    fun `a missing figure produces the sentence that claims nothing`() {
        // Writing around the gap would produce a half-answer that reads like a whole one.
        val text =
            engine.verbalise(draft(ChatIntent.SPEND, ToolFigure("total", FigureKind.AMOUNT, amount = Money(1L))))
                .expectOk()

        assertEquals("I could not find anything to answer that with yet.", text)
    }

    @Test
    fun `an intent with no template says so rather than guessing`() {
        assertEquals(
            "I could not find anything to answer that with yet.",
            engine.verbalise(VerbalisationDraft(intent = null, results = emptyList())).expectOk(),
        )
    }

    @Test
    fun `a figure of the wrong kind is refused, not printed`() {
        // A count in an amount slot would print "₹23.00" from 23 transactions — which the guardrail
        // would block, leaving the user with silence and nobody any the wiser about why.
        val result =
            engine.verbalise(
                draft(
                    ChatIntent.SPEND,
                    ToolFigure("total", FigureKind.AMOUNT, number = 23),
                    ToolFigure("count", FigureKind.COUNT, number = 23),
                ),
            )

        assertTrue("a wrong-kind figure was printed", result is Err)
    }

    @Test
    fun `every intent the registry routes to has a sentence`() {
        // A route with no template is a question the assistant can plan and then not answer.
        ChatIntent.entries.forEach { intent ->
            val text = engine.verbalise(VerbalisationDraft(intent, emptyList())).expectOk()

            assertTrue("$intent has no sentence at all", text.isNotBlank())
        }
    }

    @Test
    fun `the same figures produce the same words every time`() {
        val first = engine.verbalise(draft(ChatIntent.HEALTH, ToolFigure("score", FigureKind.COUNT, number = 988)))
        val second = engine.verbalise(draft(ChatIntent.HEALTH, ToolFigure("score", FigureKind.COUNT, number = 988)))

        assertEquals(first.expectOk(), second.expectOk())
        assertEquals("Your financial health score is 988 out of 1000.", first.expectOk())
    }

    private fun draft(
        intent: ChatIntent,
        vararg figures: ToolFigure,
    ) = VerbalisationDraft(
        intent = intent,
        results = listOf(ToolResult(ToolName.QUERY_SPEND, figures.toList())),
    )

    private fun <T> com.aicfo.core.common.Result<T, com.aicfo.core.common.AppError>.expectOk(): T =
        when (this) {
            is Ok -> value
            is Err -> throw AssertionError("expected Ok, got $error")
        }
}
