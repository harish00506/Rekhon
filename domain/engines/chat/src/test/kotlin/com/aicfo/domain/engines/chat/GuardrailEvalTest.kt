package com.aicfo.domain.engines.chat

import com.aicfo.core.common.Err
import com.aicfo.core.common.Ok
import com.aicfo.core.model.Money
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The frozen guardrail evaluation set (issue 10.6; §21.5, AI-ARC-004, CHT-001).
 *
 * Why:  "the guardrail works" is a claim, and §21.5 says a claim about an AI component is measured
 *       against a frozen labelled set with a threshold that blocks merges. The asymmetry is the
 *       whole design: a fabricated figure reaching a user is the failure this app cannot survive,
 *       so that rate is **100% or the build fails**. A false block only costs silence.
 * What: every case in `ai/eval/guardrail-eval.json` run through the **real** `ChatEngine.compose`
 *       — the same path the app uses, with AI-GRD inside it — and the two rates measured.
 * Result: a regression in the guardrail fails here, naming the case that regressed and printing
 *         the report.
 * Changelog: 2026-09-27 — Created for issue 10.6.
 *
 * This reads the set from the repository rather than from test resources, deliberately: it is one
 * file, reviewed like the rulebook, and copying it into a module's resources would create a second
 * copy to drift. The module declares it as a test input, without which Gradle would leave this task
 * `UP-TO-DATE` and the gate would pass without running.
 */
class GuardrailEvalTest {
    private val engine = ChatEngineFactory.create()
    private val document: JsonObject by lazy {
        Json.parseToJsonElement(evalFile().readText()).jsonObject
    }
    private val cases: List<EvalCase> by lazy { document.getValue("cases").jsonArray.map(::caseOf) }

    @Test
    fun `the evaluation set is where this test thinks it is, and is not empty`() {
        assertTrue("the eval set looks truncated: ${cases.size} cases", cases.size >= MINIMUM_CASES)
        assertTrue("no fabricated cases — the set would prove nothing", cases.any { it.kind == "fabricated" })
        assertTrue("no adversarial cases — the interesting half is missing", cases.any { it.kind == "adversarial" })
    }

    @Test
    fun `no fabricated or adversarial figure ever reaches the user`() {
        // The acceptance criterion, and the one rate that has no room: 100%.
        val expected = cases.filter { it.expect == "BLOCKED" }
        val leaked = expected.filter { outcomeOf(it) != "BLOCKED" }
        val rate = percentage(expected.size - leaked.size, expected.size)

        assertEquals(
            "a fabricated figure reached the user: ${leaked.map { it.id }}",
            threshold("fabricated_blocked_pct"),
            rate,
        )
    }

    @Test
    fun `an honest reply is not silenced`() {
        val expected = cases.filter { it.expect == "ANSWERED" }
        val blocked = expected.filter { outcomeOf(it) != "ANSWERED" }
        val rate = percentage(expected.size - blocked.size, expected.size)

        assertTrue(
            "honest replies were blocked: ${blocked.map { it.id }} (rate $rate%, threshold " +
                "${threshold("honest_answered_pct")}%)",
            rate >= threshold("honest_answered_pct"),
        )
    }

    @Test
    fun `every single case comes out as the set says it must`() {
        // The rates above are what §21.5 gates on; this is the one that names the case. Both exist
        // because a rate can be met while a specific, important case has quietly flipped.
        val wrong = cases.filter { outcomeOf(it) != it.expect }

        assertEquals(
            "cases that came out wrong: ${wrong.map { "${it.id} (${it.kind}, expected ${it.expect})" }}",
            emptyList<String>(),
            wrong.map { it.id },
        )
    }

    @Test
    fun `the set has no duplicate ids, so a case cannot be quietly replaced`() {
        // A frozen set is only frozen if a row cannot be edited into a different row under the same
        // name. Ids are how a failure is discussed in a commit.
        assertEquals(cases.map { it.id }.distinct().size, cases.size)
    }

    @Test
    fun `the thresholds are the ones the file states`() {
        // The numbers live in the file, not here, for the same reason every other threshold in this
        // project does (§6). This asserts the harness is reading them rather than assuming them.
        assertEquals(100, threshold("fabricated_blocked_pct"))
        assertEquals(100, threshold("honest_answered_pct"))
    }

    @Test
    fun `the report says what was measured`() {
        // Printed so a CI log shows the shape of the run, not just a green tick.
        val byKind = cases.groupBy { it.kind }
        val report =
            buildString {
                appendLine("AI-GRD evaluation — ${cases.size} cases from ai/eval/guardrail-eval.json")
                byKind.toSortedMap().forEach { (kind, group) ->
                    val correct = group.count { outcomeOf(it) == it.expect }
                    appendLine("  $kind: $correct/${group.size} as expected")
                }
            }
        println(report)

        assertTrue(report, report.contains("fabricated"))
    }

    // --- running one case ---------------------------------------------------------------------------

    /**
     * Runs one case through the real pipeline.
     * Why:    through `compose`, not through AI-GRD directly — the thing being evaluated is what
     *         reaches a user, which includes the chat layer's decision to drop a blocked reply and
     *         everything it carries. Testing the guardrail alone would measure a component the user
     *         never meets.
     * Result: `"ANSWERED"` or `"BLOCKED"`. Input: [case]. Output: [String].
     */
    private fun outcomeOf(case: EvalCase): String {
        val question = ChatRequest(text = "eval", nowUtcMillis = NOW)
        val composition =
            ChatComposition(
                request = question,
                plan = ChatPlan(intent = case.intent, calls = emptyList(), chips = emptyList()),
                results = listOf(ToolResult(ToolName.QUERY_SPEND, figures = case.figures)),
                draft = case.draft,
                modelId = "eval",
            )
        return when (val reply = engine.compose(composition)) {
            is Err -> throw AssertionError("${case.id}: compose refused with ${reply.error}")
            is Ok -> if (reply.value.answered) "ANSWERED" else "BLOCKED"
        }
    }

    // --- parsing ------------------------------------------------------------------------------------

    /** Result: one case from its row. Input: the JSON element. Output: [EvalCase]. */
    private fun caseOf(element: kotlinx.serialization.json.JsonElement): EvalCase {
        val row = element.jsonObject
        return EvalCase(
            id = row.getValue("id").jsonPrimitive.content,
            kind = row.getValue("kind").jsonPrimitive.content,
            intent = ChatIntent.entries.first { it.name == row.getValue("intent").jsonPrimitive.content },
            figures = figuresOf(row.getValue("figures").jsonArray),
            draft = row.getValue("draft").jsonPrimitive.content,
            expect = row.getValue("expect").jsonPrimitive.content,
        )
    }

    /** Result: the figures a case's tools returned. Input: [array]. Output: the figures. */
    private fun figuresOf(array: JsonArray): List<ToolFigure> =
        array.map { element ->
            val figure = element.jsonObject
            val kind = FigureKind.entries.first { it.name == figure.getValue("kind").jsonPrimitive.content }
            ToolFigure(
                key = figure.getValue("key").jsonPrimitive.content,
                kind = kind,
                amount = figure["amount_minor"]?.jsonPrimitive?.long?.let(::Money),
                number = figure["number"]?.jsonPrimitive?.int,
                isoDate = figure["iso_date"]?.jsonPrimitive?.content,
            )
        }

    /** Result: a threshold from the file. Input: [key]. Output: [Int]. */
    private fun threshold(key: String): Int = document.getValue("thresholds").jsonObject.getValue(key).jsonPrimitive.int

    /** Result: [part] of [whole] as a whole percentage; 100 when there is nothing to measure. */
    private fun percentage(
        part: Int,
        whole: Int,
    ): Int = if (whole == 0) 100 else part * 100 / whole

    private fun evalFile(): File {
        var directory: File? = File("").absoluteFile
        while (directory != null) {
            val candidate = File(directory, EVAL_PATH)
            if (candidate.isFile) return candidate
            directory = directory.parentFile
        }
        error("Could not find $EVAL_PATH walking up from ${File("").absolutePath}")
    }

    /** One row of the frozen set. */
    private data class EvalCase(
        val id: String,
        val kind: String,
        val intent: ChatIntent,
        val figures: List<ToolFigure>,
        val draft: String,
        val expect: String,
    )

    private companion object {
        const val EVAL_PATH = "ai/eval/guardrail-eval.json"
        const val NOW = 1_790_000_000_000L

        /** Below this the set is not an evaluation, it is a handful of examples. */
        const val MINIMUM_CASES = 20
    }
}
