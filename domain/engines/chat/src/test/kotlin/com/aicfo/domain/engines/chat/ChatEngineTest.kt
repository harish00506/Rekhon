package com.aicfo.domain.engines.chat

import com.aicfo.core.common.AppError
import com.aicfo.core.common.Err
import com.aicfo.core.common.Ok
import com.aicfo.core.common.Result
import com.aicfo.core.model.Money
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What AI-CHAT must get right (issue 10.5; §19, CHT-001..005, P-01, P-03, AI-ARC-004).
 *
 * Why:  every dangerous thing a financial assistant can do is a thing this engine decides. It can
 *       answer a question it should have refused; it can route "should I buy Reliance" to the
 *       Purchase Advisor and give it a verdict; it can hand the model figures it was never given;
 *       and — the one that ends the product — it can let a number the model invented reach the
 *       screen. These tests are one per way.
 * What: routing, refusals, the tool cap, what the model is and is not handed, and the guardrail
 *       standing between the draft and the reply.
 * Result: a reply in which every figure came from a tool, or no reply at all.
 * Changelog: 2026-09-26 — Created for issue 10.5.
 */
class ChatEngineTest {
    private val engine = ChatEngineFactory.create()

    @Test
    fun `a question about spending routes to the spend tool`() {
        val plan = engine.plan(request("where did my money go this month?")).expectOk()

        assertEquals(ChatIntent.SPEND, plan.intent)
        assertEquals(listOf(ToolName.QUERY_SPEND), plan.calls.map { it.tool })
        assertNull(plan.refusal)
    }

    @Test
    fun `every intent in the registry can actually be reached`() {
        // A route nobody can match is dead data. This walks the mirror and asserts each intent's
        // own first keyword reaches it — which also catches a keyword stolen by an earlier route.
        ToolRegistry.BUNDLED.intents.forEach { route ->
            val plan = engine.plan(request(route.keywords.first())).expectOk()

            assertEquals("route ${route.intent} is unreachable", route.intent, plan.intent)
        }
    }

    @Test
    fun `a stock tip is refused, and something useful is offered instead`() {
        // CHT-002. The refusal is a key, not a sentence: §21.6 keeps the words in strings.xml.
        val plan = engine.plan(request("should i buy reliance today?")).expectOk()

        assertEquals(RefusalReason.OUT_OF_SCOPE, plan.refusal)
        assertTrue("a refusal must read no data", plan.calls.isEmpty())
        assertEquals(listOf(ChatIntent.HEALTH, ChatIntent.AFFORD), plan.chips)
    }

    @Test
    fun `an out-of-scope ask is refused even when it also sounds in scope`() {
        // "file my taxes" contains no intent keyword, but "should i buy reliance" contains "buy".
        // The refusal must win, or the assistant answers the dangerous half of the question.
        val plan = engine.plan(request("can i afford to file my taxes with a lawyer?")).expectOk()

        assertEquals(RefusalReason.OUT_OF_SCOPE, plan.refusal)
        assertTrue(plan.calls.isEmpty())
    }

    @Test
    fun `a question that matches nothing is not answered`() {
        val plan = engine.plan(request("what is the capital of France?")).expectOk()

        assertEquals(RefusalReason.NOT_UNDERSTOOD, plan.refusal)
        assertTrue(plan.calls.isEmpty())
        assertTrue("it should still offer a way in", plan.chips.isNotEmpty())
    }

    @Test
    fun `one question cannot walk the whole registry`() {
        // The registry's max_tools_per_turn is 2. A question mentioning five things must not read
        // five tools' worth of the user's data to answer (§19.2).
        val plan = engine.plan(request("my budget, my goals, my balance, my score and my spend")).expectOk()

        assertTrue("planned ${plan.calls.size} tools", plan.calls.size <= ToolRegistry.BUNDLED.routing.maxToolsPerTurn)
    }

    @Test
    fun `empty input is a refusal by field, not an answer`() {
        assertEquals(AppError.Validation("chat.text"), (engine.plan(request("   ")) as Err).error)
    }

    @Test
    fun `a checked draft becomes the reply, with its figures and evidence`() {
        val reply = compose(draft = "You spent ₹61,427.00 across 23 transactions this month.")

        assertTrue(reply.answered)
        assertEquals("You spent ₹61,427.00 across 23 transactions this month.", reply.text)
        assertNull(reply.refusal)
        assertEquals(listOf("RULE-CLS-METHOD"), reply.citations)
        assertEquals(2, reply.verified.size)
    }

    @Test
    fun `a figure no tool produced never reaches the user`() {
        // The one that ends the product: the model states a number nobody computed. AI-ARC-004.
        val reply = compose(draft = "You spent ₹99,999.00 across 23 transactions this month.")

        assertFalse(reply.answered)
        assertEquals(RefusalReason.GUARDRAIL_BLOCKED, reply.refusal)
        assertEquals("a blocked reply shows nothing at all", "", reply.text)
    }

    @Test
    fun `a blocked reply carries no figures for a screen to render`() {
        // Belt and braces: even the figures the tools *did* return are withheld, so no screen can
        // reassemble a sentence the guardrail refused.
        val reply = compose(draft = "You spent ₹99,999.00 this month.")

        assertTrue(reply.figures.isEmpty())
    }

    @Test
    fun `a count cannot be stated as a rupee figure`() {
        // The tools returned a count of 23 and an amount of ₹61,427. "₹23" is neither: collapsing
        // the kinds into one allowlist would let a model state any number it saw in any dress.
        val reply = compose(draft = "You spent ₹23.00 this month.")

        assertEquals(RefusalReason.GUARDRAIL_BLOCKED, reply.refusal)
    }

    @Test
    fun `a reply with no figures at all is still allowed`() {
        // "I could not find any spending this month" contains no claim, so there is nothing to
        // verify and nothing to block. A guardrail that refused it would silence the honest answer.
        val reply = compose(draft = "I could not find any spending for that period.")

        assertTrue(reply.answered)
    }

    @Test
    fun `no model means no answer, not an invented one`() {
        val reply = compose(draft = null)

        assertEquals(RefusalReason.NO_MODEL, reply.refusal)
        assertFalse(reply.answered)
    }

    @Test
    fun `a tool that could not answer is said, not rounded to zero`() {
        val reply =
            engine.compose(
                ChatComposition(
                    request = request("where did my money go?"),
                    plan = engine.plan(request("where did my money go?")).expectOk(),
                    results = listOf(ToolResult(ToolName.QUERY_SPEND, failed = true)),
                    draft = "You spent nothing at all.",
                    modelId = "template",
                ),
            ).expectOk()

        assertEquals(RefusalReason.NO_DATA, reply.refusal)
    }

    @Test
    fun `the model is handed the tool results and nothing else`() {
        // P-01 and §19.4: the draft the model sees carries the intent and the figures a tool
        // returned. If it ever carried the user's raw text or a merchant list, this is where it
        // would show up.
        val results = listOf(spendResult())
        val draft = VerbalisationDraft(ChatIntent.SPEND, results)

        assertEquals(results, draft.results)
        assertEquals(
            "the draft's whole surface is the intent, the results and the attempt",
            3,
            VerbalisationDraft::class.java.declaredFields.count { !it.isSynthetic },
        )
    }

    @Test
    fun `every reply names the engine, the model and the rules behind it`() {
        val reply = compose(draft = "You spent ₹61,427.00 across 23 transactions this month.")

        assertEquals("AI-CHAT", reply.provenance.engineId)
        assertEquals("1.0", reply.provenance.engineVersion)
        assertTrue(
            "the model must be named: ${reply.provenance.inputWindow}",
            reply.provenance.inputWindow?.contains("template") == true,
        )
    }

    @Test
    fun `the same question plans the same way twice`() {
        assertEquals(engine.plan(request("what is my balance?")), engine.plan(request("what is my balance?")))
    }

    // --- fixtures ----------------------------------------------------------------------------------

    private fun request(text: String) = ChatRequest(text = text, nowUtcMillis = NOW)

    private fun compose(draft: String?): ChatReply {
        val question = request("where did my money go this month?")
        return engine.compose(
            ChatComposition(
                request = question,
                plan = engine.plan(question).expectOk(),
                results = listOf(spendResult()),
                draft = draft,
                modelId = "template",
            ),
        ).expectOk()
    }

    private fun spendResult() =
        ToolResult(
            tool = ToolName.QUERY_SPEND,
            figures =
                listOf(
                    ToolFigure("total", FigureKind.AMOUNT, amount = Money(61_427_00L)),
                    ToolFigure("count", FigureKind.COUNT, number = 23),
                ),
            citations = listOf("RULE-CLS-METHOD"),
        )

    private fun <T> Result<T, AppError>.expectOk(): T =
        when (this) {
            is Ok -> value
            is Err -> throw AssertionError("expected Ok, got $error")
        }

    private companion object {
        const val NOW = 1_790_000_000_000L
    }
}
