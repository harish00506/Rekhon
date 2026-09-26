package com.aicfo.domain.engines.chat

import com.aicfo.core.common.Err
import com.aicfo.core.common.Ok
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The golden-file gate for AI-CHAT's routing (issue 10.5; §19.2, §21.5).
 *
 * Why:  routing decides which of the user's data a question reads, and it is the kind of table that
 *       drifts silently: a keyword added for one intent quietly steals a question from another, and
 *       nothing looks wrong until someone asks about their budget and is told about their goals.
 *       Fourteen fixed questions are compared line for line with an **independent** oracle
 *       (`golden/chat_oracle.py`) that re-implements the routing from the registry in Python.
 * What: a plain route for every intent, a question that hits the two-tool cap, two refusals — one
 *       of which also sounds in scope — and one the assistant does not understand.
 * Result: a change to the keywords, the order of the checks or the caps fails here, naming the
 *         question it changed.
 * Changelog: 2026-09-26 — Created for issue 10.5.
 *
 * Regenerate with `python3 chat_oracle.py > chat.txt` from the golden directory — but only after
 * deciding that the *registry* is right, never to make this test pass.
 */
class ChatGoldenTest {
    private val engine = ChatEngineFactory.create()

    private val golden: List<String> by lazy {
        (
            javaClass.classLoader.getResource("golden/chat.txt")?.readText()
                ?: throw AssertionError("golden/chat.txt is missing")
        ).lines().filter { it.isNotBlank() && !it.startsWith("#") }
    }

    @Test
    fun `every fixed question routes exactly as the oracle says`() {
        assertEquals(golden, golden.map { line(it.substringBefore(" -> ")) })
    }

    /** Result: one golden line for a question. Input: [question]. Output: [String]. */
    private fun line(question: String): String {
        val plan =
            when (val result = engine.plan(ChatRequest(question, NOW))) {
                is Ok -> result.value
                is Err -> throw AssertionError("$question: ${result.error}")
            }
        val tools = plan.calls.joinToString("|") { it.tool.registryName }.ifEmpty { "-" }
        val chips = plan.chips.joinToString("|") { it.name }.ifEmpty { "-" }
        return "$question -> intent=${plan.intent?.name ?: "-"} tools=$tools chips=$chips " +
            "refusal=${plan.refusal?.name ?: "-"}"
    }

    private companion object {
        const val NOW = 1_790_000_000_000L
    }
}
