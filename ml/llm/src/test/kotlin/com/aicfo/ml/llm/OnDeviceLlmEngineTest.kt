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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The neural verbaliser's contract, with the weights replaced by a fake (ADR-0079; §19, P-03).
 *
 * Why:  the model itself cannot be tested — it is half a gigabyte of someone else's arithmetic and
 *       it needs a device. What *can* be tested is every decision made around it, and those are the
 *       decisions that matter: that it is never asked to speak about nothing, that its reply is
 *       checked before it is returned, and that a failure is an ordinary answer rather than a crash.
 * What: the happy path, the no-figures path, a runtime failure, and a hallucinated figure.
 * Result: words that passed the sanitiser, or an `Err` that sends the caller to the template.
 * Changelog: 2026-10-10 — Created for ADR-0079.
 */
class OnDeviceLlmEngineTest {
    private val runtime = FakeRuntime()
    private val engine = OnDeviceLlmEngine(runtime, { "Write one sentence." }, "on-device:test")

    @Test
    fun `it names itself, so a reply can say which model wrote it`() {
        assertEquals("on-device:test", engine.id)
    }

    @Test
    fun `a sentence that repeats the figures it was given is returned`() {
        runtime.reply = Ok("You spent ₹61,427.00 across 23 transactions.")

        val text = engine.verbalise(spendDraft()).expectOk()

        assertEquals("You spent ₹61,427.00 across 23 transactions.", text)
    }

    @Test
    fun `the model is handed the formatted figures and nothing else`() {
        runtime.reply = Ok("You spent ₹61,427.00 across 23 transactions.")

        engine.verbalise(spendDraft())

        assertTrue("- total: ₹61,427.00" in runtime.seen!!)
        assertTrue("- count: 23" in runtime.seen!!)
    }

    @Test
    fun `a draft with no figures never reaches the model`() {
        val error = engine.verbalise(VerbalisationDraft(ChatIntent.SPEND, emptyList())).expectErr()

        assertEquals("chat.draft.figures", (error as AppError.Validation).field)
        assertNull("asking a model about nothing is how it invents something", runtime.seen)
    }

    @Test
    fun `a runtime failure is passed on, not papered over`() {
        runtime.reply = Err(AppError.Unexpected("TimeoutException"))

        val error = engine.verbalise(spendDraft()).expectErr()

        assertEquals("unexpected", error.code)
    }

    @Test
    fun `a figure the model invented is refused here, before the guardrail ever sees it`() {
        runtime.reply = Ok("You spent ₹61,427.00 across 23 transactions, up 12 percent.")

        val error = engine.verbalise(spendDraft()).expectErr()

        assertEquals("chat.draft.figure", (error as AppError.Validation).field)
    }

    private fun spendDraft() =
        VerbalisationDraft(
            ChatIntent.SPEND,
            listOf(
                ToolResult(
                    ToolName.QUERY_SPEND,
                    listOf(
                        ToolFigure("total", FigureKind.AMOUNT, amount = Money(61_427_00L)),
                        ToolFigure("count", FigureKind.COUNT, number = 23),
                    ),
                ),
            ),
        )

    private fun Result<String, AppError>.expectOk(): String {
        assertTrue("expected Ok but was $this", this is Ok)
        return (this as Ok).value
    }

    private fun Result<String, AppError>.expectErr(): AppError {
        assertTrue("expected Err but was $this", this is Err)
        return (this as Err).error
    }

    /**
     * A model that says what the test tells it to.
     * Why:    the whole point of [LlmRuntime] — the untestable part is one class, and everything
     *         around it is tested against this.
     * Input:  [reply] — what `generate` returns. Output: a fake [LlmRuntime]; [seen] records the
     *         prompt, so a test can assert what was sent (P-01).
     */
    private class FakeRuntime : LlmRuntime {
        var reply: Result<String, AppError> = Ok("")
        var seen: String? = null

        override fun generate(prompt: String): Result<String, AppError> {
            seen = prompt
            return reply
        }

        override fun close() = Unit
    }
}
