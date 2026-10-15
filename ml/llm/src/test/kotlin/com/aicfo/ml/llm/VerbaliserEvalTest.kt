package com.aicfo.ml.llm

import com.aicfo.core.common.AppError
import com.aicfo.core.common.Err
import com.aicfo.core.common.Ok
import com.aicfo.core.model.Money
import com.aicfo.domain.engines.chat.ChatIntent
import com.aicfo.domain.engines.chat.FigureKind
import com.aicfo.domain.engines.chat.ToolFigure
import com.aicfo.domain.engines.chat.ToolName
import com.aicfo.domain.engines.chat.ToolResult
import com.aicfo.domain.engines.chat.VerbalisationDraft
import kotlinx.serialization.json.Json
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
 * The frozen verbalisation-fidelity set (ADR-0080; §21.5, §19.4, P-03).
 *
 * Why:  the app asks the model one narrow thing — *say exactly these figures, in one sentence, the
 *       way this app writes them* — so that is the only thing measured. Not fluency, not
 *       helpfulness, not reasoning: none of those are what an L6 verbaliser is for, and a
 *       benchmark of them would tell this project nothing it could act on.
 *
 *       **Both rates are 100 and neither may be traded for the other.** A drifted or invented
 *       figure reaching the user is the failure the app cannot survive. A faithful reply being
 *       *refused* is the failure nobody sees: the user silently gets the plainer template sentence
 *       every turn, and the neural path the weights were installed for is never used. The first
 *       version of [DraftSanitiser] did exactly that for every answer mentioning money.
 * What: every case in `ai/eval/verbaliser-eval.json` run through the real
 *       [VerbalisationPrompt] → [DraftSanitiser] path — the same one
 *       [OnDeviceLlmEngine] uses — and the two rates measured.
 * Result: a regression fails here, naming the case and printing the report.
 * Changelog: 2026-10-10 — Created for ADR-0080.
 *
 * The set is read from the repository rather than copied into test resources, the same decision
 * `GuardrailEvalTest` made and for the same reason: one file, reviewed like the rulebook. The
 * module declares it as a test input, without which Gradle would hold this task `UP-TO-DATE` and
 * the gate would pass without running.
 */
class VerbaliserEvalTest {
    private val document: JsonObject by lazy { Json.parseToJsonElement(evalFile().readText()).jsonObject }
    private val cases: List<EvalCase> by lazy { document.getValue("cases").jsonArray.map { caseOf(it.jsonObject) } }

    @Test
    fun `the evaluation set is where this test thinks it is, and measures both directions`() {
        assertTrue("the eval set looks truncated: ${cases.size} cases", cases.size >= MINIMUM_CASES)
        assertTrue("no drift cases — the set would prove nothing", cases.any { it.kind == "drift" })
        assertTrue("no invented cases — the dangerous half is missing", cases.any { it.kind == "invented" })
        assertTrue("no rendering cases — the false-refusal half is missing", cases.any { it.kind == "rendering" })
    }

    @Test
    fun `no drifted, invented or empty reply ever reaches the user`() {
        val expected = cases.filter { it.expect == "REFUSED" }
        val leaked = expected.filter { outcomeOf(it).accepted }
        val rate = percentage(expected.size - leaked.size, expected.size)

        assertEquals(
            "a reply that misstates or invents a figure reached the user: ${leaked.map { it.id }}",
            threshold("fidelity_refused_pct"),
            rate,
        )
    }

    @Test
    fun `a refusal is for the reason the set says, so a case cannot pass by failing differently`() {
        // A drifted figure caught by the length check would score as a pass while proving nothing.
        val wrong =
            cases
                .filter { it.expect == "REFUSED" && it.expectReason != null }
                .filter { outcomeOf(it).reason != it.expectReason }

        assertEquals(
            "these cases were refused for the wrong reason: " +
                wrong.joinToString { "${it.id} expected ${it.expectReason} got ${outcomeOf(it).reason}" },
            emptyList<String>(),
            wrong.map { it.id },
        )
    }

    @Test
    fun `every faithful reply is accepted, exactly as it should read`() {
        // The invisible failure. A false refusal costs the user the better sentence on every turn
        // and shows up nowhere but here.
        val expected = cases.filter { it.expect == "ACCEPTED" }
        val refused = expected.filter { !outcomeOf(it).accepted }
        val rate = percentage(expected.size - refused.size, expected.size)

        assertEquals(
            "these faithful replies were refused: " +
                refused.joinToString { "${it.id} (${outcomeOf(it).reason})" },
            threshold("faithful_accepted_pct"),
            rate,
        )
    }

    @Test
    fun `an accepted reply is shown as the set says it reads`() {
        val wrong =
            cases
                .filter { it.expect == "ACCEPTED" && it.expectText != null }
                .filter { outcomeOf(it).text != it.expectText }

        assertEquals(
            "the sanitised sentence differs from the frozen expectation: " +
                wrong.joinToString { "${it.id}: \"${outcomeOf(it).text}\"" },
            emptyList<String>(),
            wrong.map { it.id },
        )
    }

    @Test
    fun `the report says what was measured`() {
        val report =
            cases.groupBy { it.kind }.toSortedMap().map { (kind, rows) ->
                val passed = rows.count { expectationMet(it) }
                "$kind: $passed/${rows.size}"
            }
        val version = document.getValue("_meta").jsonObject["version"]?.jsonPrimitive?.content
        println("verbaliser fidelity ($version) — " + report.joinToString(" · "))
        assertEquals("every case must meet its expectation", cases.size, cases.count(::expectationMet))
    }

    /** Result: true when this case behaved as the frozen set says. Input: [case]. Output: [Boolean]. */
    private fun expectationMet(case: EvalCase): Boolean {
        val outcome = outcomeOf(case)
        return when (case.expect) {
            "ACCEPTED" -> outcome.accepted && (case.expectText == null || outcome.text == case.expectText)
            else -> !outcome.accepted && (case.expectReason == null || outcome.reason == case.expectReason)
        }
    }

    /**
     * Runs one case through the real path.
     * Why:    the prompt builder and the sanitiser together are the unit under test — judging a
     *         reply means knowing which renderings were permitted, and that is the prompt's output.
     * Result: what the app would do with that reply. Input: [case]. Output: [Outcome].
     */
    private fun outcomeOf(case: EvalCase): Outcome {
        val draft = VerbalisationDraft(case.intent, listOf(ToolResult(ToolName.QUERY_SPEND, case.figures)))
        val verbalisation =
            VerbalisationPrompt.of(INSTRUCTION, draft)
                ?: return Outcome(false, null, "chat.draft.figures")
        return when (val sanitised = DraftSanitiser.sanitise(case.reply, verbalisation)) {
            is Ok -> Outcome(true, sanitised.value, null)
            is Err -> Outcome(false, null, (sanitised.error as? AppError.Validation)?.field ?: sanitised.error.code)
        }
    }

    private fun threshold(name: String): Int =
        document.getValue("thresholds").jsonObject.getValue(name).jsonPrimitive.int

    private fun percentage(
        passed: Int,
        total: Int,
    ): Int = if (total == 0) 0 else passed * PERCENT / total

    /** One row of the frozen set. */
    private data class EvalCase(
        val id: String,
        val kind: String,
        val intent: ChatIntent?,
        val figures: List<ToolFigure>,
        val reply: String,
        val expect: String,
        val expectText: String?,
        val expectReason: String?,
    )

    /** What the app did with a reply: shown, or refused with a reason. */
    private data class Outcome(
        val accepted: Boolean,
        val text: String?,
        val reason: String?,
    )

    private fun caseOf(row: JsonObject): EvalCase =
        EvalCase(
            id = row.getValue("id").jsonPrimitive.content,
            kind = row.getValue("kind").jsonPrimitive.content,
            intent = row["intent"]?.jsonPrimitive?.content?.let { ChatIntent.valueOf(it) },
            figures = row.getValue("figures").jsonArray.map { figureOf(it.jsonObject) },
            reply = row.getValue("reply").jsonPrimitive.content,
            expect = row.getValue("expect").jsonPrimitive.content,
            expectText = row["expect_text"]?.jsonPrimitive?.content,
            expectReason = row["expect_reason"]?.jsonPrimitive?.content,
        )

    private fun figureOf(row: JsonObject): ToolFigure {
        val kind = FigureKind.valueOf(row.getValue("kind").jsonPrimitive.content)
        return ToolFigure(
            key = row.getValue("key").jsonPrimitive.content,
            kind = kind,
            amount = row["amount_minor"]?.jsonPrimitive?.long?.let(::Money),
            number = row["number"]?.jsonPrimitive?.int,
            isoDate = row["iso_date"]?.jsonPrimitive?.content,
        )
    }

    private fun evalFile(): File {
        var directory: File? = File("").absoluteFile
        while (directory != null) {
            val candidate = File(directory, EVAL_PATH)
            if (candidate.isFile) return candidate
            directory = directory.parentFile
        }
        error("Could not find $EVAL_PATH walking up from ${File("").absolutePath}")
    }

    private companion object {
        const val EVAL_PATH = "ai/eval/verbaliser-eval.json"
        const val MINIMUM_CASES = 30
        const val PERCENT = 100
        const val INSTRUCTION = "Write one sentence."
    }
}
