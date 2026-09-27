package com.aicfo.domain.engines.chat

import com.aicfo.core.common.AppError
import com.aicfo.core.common.Err
import com.aicfo.core.common.Ok
import com.aicfo.core.common.Result
import com.aicfo.core.model.Money
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/**
 * AI-CHAT's promises, over many generated turns (issue 10.5; §19, P-01, P-03, §21.5).
 *
 * Why:  the example tests pin particular questions. These pin what must hold for **every** one —
 *       including the two claims the whole design rests on: a reply never contains a figure no tool
 *       produced, and a refusal never contains a figure at all. If either failed once, in one
 *       phrasing, the app would be a confident liar in exactly the case nobody tested.
 * What: six properties, 300 seeded cases each.
 * Result: a broken promise names the case that broke it.
 * Changelog: 2026-09-26 — Created for issue 10.5.
 */
class ChatPropertyTest {
    private val engine = ChatEngineFactory.create()

    @Test
    fun `a plan never calls more tools than the registry allows`() {
        repeat(CASES) { case ->
            val plan = engine.plan(ChatRequest(question(Random(case)), NOW)).expectOk()

            assertTrue(
                "case $case planned ${plan.calls.size} tools",
                plan.calls.size <= ToolRegistry.BUNDLED.routing.maxToolsPerTurn,
            )
        }
    }

    @Test
    fun `a plan only ever calls tools the registry declares`() {
        // §19.2: tools are the only way chat touches data, so a plan that named anything else would
        // be a hole in that claim. The type makes it hard; this makes it checked.
        repeat(CASES) { case ->
            engine.plan(ChatRequest(question(Random(case)), NOW)).expectOk().calls.forEach { call ->
                assertTrue("case $case: ${call.tool}", call.tool in ToolName.entries)
            }
        }
    }

    @Test
    fun `a refused plan reads nothing`() {
        repeat(CASES) { case ->
            val plan = engine.plan(ChatRequest(question(Random(case)), NOW)).expectOk()

            if (plan.refusal != null) {
                assertTrue("case $case: a refusal that still reads data", plan.calls.isEmpty())
            }
        }
    }

    @Test
    fun `a draft with an invented figure is never answered`() {
        // The property the product depends on. Whatever the question, whatever the tools returned,
        // a rupee figure the tools did not produce cannot come back as an answer.
        repeat(CASES) { case ->
            val random = Random(case)
            val text = question(random)
            val plan = engine.plan(ChatRequest(text, NOW)).expectOk()
            val invented = Money(random.nextLong(1_00_000L, 9_00_00_000L))
            val reply =
                engine.compose(
                    ChatComposition(
                        request = ChatRequest(text, NOW),
                        plan = plan,
                        results = listOf(result(Money(12_345_00L))),
                        draft = "That comes to ₹${invented.minor / 100}.00 this month.",
                        modelId = "template",
                    ),
                ).expectOk()

            assertFalse("case $case: an invented ₹${invented.minor} was answered", reply.answered)
        }
    }

    @Test
    fun `a refusal never carries a figure`() {
        repeat(CASES) { case ->
            val text = question(Random(case))
            val plan = engine.plan(ChatRequest(text, NOW)).expectOk()
            val reply =
                engine.compose(
                    ChatComposition(
                        request = ChatRequest(text, NOW),
                        plan = plan,
                        results = listOf(result(Money(12_345_00L))),
                        draft = null,
                        modelId = "template",
                    ),
                ).expectOk()

            assertTrue("case $case", reply.figures.isEmpty())
            assertEquals("case $case", "", reply.text)
        }
    }

    @Test
    fun `the same question plans the same way every time`() {
        repeat(CASES) { case ->
            val text = question(Random(case))

            assertEquals("case $case", engine.plan(ChatRequest(text, NOW)), engine.plan(ChatRequest(text, NOW)))
        }
    }

    // --- generator ---------------------------------------------------------------------------------

    /**
     * One plausible question: registry words, refusal words and noise, in a random order and case.
     * Result: something a person might type. Input: [random] — seeded. Output: [String].
     */
    private fun question(random: Random): String {
        val words = mutableListOf<String>()
        repeat(random.nextInt(1, 6)) {
            words +=
                when (random.nextInt(4)) {
                    0 -> ToolRegistry.BUNDLED.intents.random(random).keywords.random(random)
                    1 -> ToolRegistry.BUNDLED.outOfScope.keywords.random(random)
                    else -> NOISE.random(random)
                }
        }
        val sentence = words.joinToString(" ")
        return if (random.nextBoolean()) sentence.uppercase() else sentence
    }

    /** Result: one tool result carrying a single amount. Input: [amount]. Output: [ToolResult]. */
    private fun result(amount: Money) =
        ToolResult(
            tool = ToolName.QUERY_SPEND,
            figures = listOf(ToolFigure("total", FigureKind.AMOUNT, amount = amount)),
            citations = listOf("RULE-CLS-METHOD"),
        )

    private fun <T> Result<T, AppError>.expectOk(): T =
        when (this) {
            is Ok -> value
            is Err -> throw AssertionError("expected Ok, got $error")
        }

    private companion object {
        const val CASES = 300
        const val NOW = 1_790_000_000_000L

        val NOISE =
            listOf(
                "what", "is", "my", "this", "month", "please", "hey", "tell me", "about", "the",
                "for", "next", "can you", "how", "and",
            )
    }
}
