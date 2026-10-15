package com.aicfo.ml.llm

import android.content.Context
import androidx.test.platform.app.InstrumentationRegistry
import com.aicfo.core.common.Ok
import com.aicfo.core.model.Money
import com.aicfo.domain.engines.chat.ChatIntent
import com.aicfo.domain.engines.chat.FigureKind
import com.aicfo.domain.engines.chat.ToolFigure
import com.aicfo.domain.engines.chat.ToolName
import com.aicfo.domain.engines.chat.ToolResult
import com.aicfo.domain.engines.chat.VerbalisationDraft
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * What the **real model** actually says, measured on a device that has the weights (ADR-0080).
 *
 * Why:  `VerbaliserEvalTest` measures the layer that judges a reply, against replies written by
 *       hand in the style a small model produces. That is the gate that can run in CI, and it is
 *       not the same claim as *this model, on this phone, gets it right often enough to be worth
 *       using*. Only a device with 521 MB of weights on it can answer that, so the measurement
 *       lives here and is run by hand (`/run`, `/verify`) rather than by the merge gate.
 * What: the same intents and figures the frozen set uses, asked of the real
 *       [MediaPipeLlmRuntime], with the acceptance rate of the whole path reported. **No expected
 *       text**: a model's wording is its own, and asserting it would be asserting a sampler's
 *       output. What is asserted is fidelity — every accepted sentence is one the app may show —
 *       and that enough of them are accepted for the feature to be worth its 521 MB.
 * Result: a printed report and a floor. A run below the floor says the prompt needs work, not that
 *         the test is wrong — the prompt is data (`ai/chat/system-prompt.md`) and tuning it is the
 *         intended fix (CLAUDE.md §6).
 * Changelog: 2026-10-10 — Created for ADR-0080.
 *
 * **Skipped, not failed, when there are no weights.** Most devices and every CI runner will skip
 * it. `scripts/install_llm_model.py` is what makes it run.
 */
class OnDeviceVerbaliserAccuracyTest {
    private val context: Context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun `the installed model states the figures it was given, often enough to be worth using`() {
        val status = LlmModelStore(context).status()
        assumeTrue(
            "no verified model installed — run scripts/install_llm_model.py to measure this",
            status is ModelStatus.Ready,
        )
        val engine = LlmEngineFactory.create(context)

        val results = CASES.map { (intent, figures) -> intent to engine.verbalise(draft(intent, figures)) }
        val accepted = results.filter { (_, reply) -> reply is Ok }

        results.forEach { (intent, reply) ->
            val text = (reply as? Ok)?.value ?: "— refused"
            println("$intent → $text")
        }
        val rate = accepted.size * PERCENT / results.size
        val model = (status as ModelStatus.Ready).modelId
        println("on-device verbaliser: $rate% of ${results.size} answers accepted (model $model)")

        assertTrue(
            "the on-device model produced a usable sentence for only $rate% of the cases; the " +
                "prompt in ai/chat/system-prompt.md is the thing to tune (ADR-0080)",
            rate >= MINIMUM_ACCEPTED_PCT,
        )
    }

    @Test
    fun `nothing the model says reaches a screen with a figure the tools did not give it`() {
        val status = LlmModelStore(context).status()
        assumeTrue("no verified model installed", status is ModelStatus.Ready)
        val engine = LlmEngineFactory.create(context)

        // The claim that may never be traded away, so it is asserted separately from the rate: an
        // accepted sentence must contain no digit outside the figures' own renderings. The
        // sanitiser is what enforces it; this is the check that it enforced it on real output.
        CASES.forEach { (intent, figures) ->
            val verbalisation = VerbalisationPrompt.of(VerbaliserInstruction(context).text(), draft(intent, figures))
            val reply = engine.verbalise(draft(intent, figures))
            if (reply is Ok && verbalisation != null) {
                val sanitised = DraftSanitiser.sanitise(reply.value, verbalisation)
                assertTrue(
                    "a shown sentence does not survive its own sanitiser: ${reply.value}",
                    sanitised is Ok || engine.id == TEMPLATE_ID,
                )
            }
        }
    }

    private fun draft(
        intent: ChatIntent,
        figures: List<ToolFigure>,
    ) = VerbalisationDraft(intent, listOf(ToolResult(ToolName.QUERY_SPEND, figures)))

    private companion object {
        const val PERCENT = 100

        /** What the template verbaliser calls itself — an answer from it is not the model's. */
        const val TEMPLATE_ID = "template"

        /**
         * The floor, and it is deliberately not 100.
         *
         * A 0.5B model will sometimes add a flourish with a number in it, and the right response
         * to that is the template's sentence for that turn — not a failed build. Below two thirds
         * the feature is costing 521 MB to be used a third of the time, which is the point at
         * which the prompt or the model is the wrong one.
         */
        const val MINIMUM_ACCEPTED_PCT = 67

        /** The same shapes the frozen set uses: one amount, two figures, three figures, a date. */
        val CASES: List<Pair<ChatIntent, List<ToolFigure>>> =
            listOf(
                ChatIntent.BALANCE to listOf(ToolFigure("liquid", FigureKind.AMOUNT, amount = Money(982300L))),
                ChatIntent.SPEND to
                    listOf(
                        ToolFigure("total", FigureKind.AMOUNT, amount = Money(6142700L)),
                        ToolFigure("count", FigureKind.COUNT, number = 23),
                    ),
                ChatIntent.FORECAST to
                    listOf(
                        ToolFigure("horizonDays", FigureKind.COUNT, number = 90),
                        ToolFigure("lowest", FigureKind.AMOUNT, amount = Money(214000L)),
                        ToolFigure("lowestOn", FigureKind.DATE, isoDate = "2026-11-03"),
                    ),
                ChatIntent.HEALTH to
                    listOf(
                        ToolFigure("score", FigureKind.COUNT, number = 712),
                        ToolFigure("scoreMax", FigureKind.COUNT, number = 1000),
                    ),
                ChatIntent.GOALS to listOf(ToolFigure("required", FigureKind.AMOUNT, amount = Money(15000000L))),
                ChatIntent.AFFORD to listOf(ToolFigure("leftAfter", FigureKind.AMOUNT, amount = Money(12345678L))),
                ChatIntent.BUYLIST to listOf(ToolFigure("count", FigureKind.COUNT, number = 4)),
                ChatIntent.VEHICLE to
                    listOf(
                        ToolFigure("cost", FigureKind.AMOUNT, amount = Money(480000L)),
                        ToolFigure("dueOn", FigureKind.DATE, isoDate = "2026-11-03"),
                    ),
            )
    }
}
