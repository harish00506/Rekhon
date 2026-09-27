package com.aicfo.domain.engines.chat

import com.aicfo.core.common.AppError
import com.aicfo.core.common.Err
import com.aicfo.core.common.Ok
import com.aicfo.core.common.Result
import com.aicfo.core.model.EngineProvenance
import com.aicfo.core.model.RuleCitation
import com.aicfo.domain.engines.guardrail.GuardrailEngine
import com.aicfo.domain.engines.guardrail.GuardrailEngineFactory
import com.aicfo.domain.engines.guardrail.GuardrailEvidence
import com.aicfo.domain.engines.guardrail.GuardrailInput
import com.aicfo.domain.engines.guardrail.GuardrailVerdict
import java.time.LocalDate
import java.time.format.DateTimeParseException

/**
 * AI-CHAT 1.0 — intent in, checked answer out (issue 10.5; §19).
 *
 * Why:  the model is the least trustworthy part of this app and the most persuasive, so the design
 *       gives it the smallest possible job: words, over figures it did not choose, checked by
 *       something that is not a model. Routing is keyword matching over the registry — boring,
 *       inspectable and deterministic (P-08) — because a question that reaches the wrong tool is a
 *       wrong answer however fluent it reads.
 * What: [plan] matches the registry's intents, refuses what §19 says to refuse, and caps how much
 *       of the user's data one question may read. [compose] hands the model's draft to AI-GRD and
 *       returns either the checked words or a refusal.
 * Result: a [ChatReply] whose every figure came from a tool (CHT-001).
 * Changelog: 2026-09-26 — Created for issue 10.5.
 *
 * `internal` per ARC-003; pure, with no clock and no I/O (P-08, ARC-002).
 */
internal class IntentRoutingChatEngine(
    private val registry: ToolRegistry,
    private val guardrail: GuardrailEngine = GuardrailEngineFactory.create(),
) : ChatEngine {
    override fun plan(request: ChatRequest): Result<ChatPlan, AppError> {
        val question = request.text.trim().lowercase()
        return if (question.isEmpty()) Err(AppError.Validation(FIELD_TEXT)) else Ok(planFor(question))
    }

    /**
     * Result: the plan for a normalised question. Input: [question] — trimmed, lower-case.
     * Output: [ChatPlan].
     *
     * **The refusal is checked first, deliberately.** "Can I afford to file my taxes with a lawyer"
     * contains an intent keyword *and* two out-of-scope ones; answering the half it understands
     * would be answering the dangerous half of the question (CHT-002).
     */
    private fun planFor(question: String): ChatPlan {
        if (registry.outOfScope.keywords.any { it in question }) {
            return ChatPlan(
                intent = null,
                calls = emptyList(),
                chips = registry.outOfScope.offerChips.take(registry.routing.maxChips),
                refusal = RefusalReason.OUT_OF_SCOPE,
            )
        }
        val matches = registry.intents.filter { route -> hits(route, question) >= registry.routing.minKeywordHits }
        if (matches.isEmpty()) {
            return ChatPlan(
                intent = null,
                calls = emptyList(),
                chips = registry.intents.take(registry.routing.maxChips).map { it.intent },
                refusal = RefusalReason.NOT_UNDERSTOOD,
            )
        }
        val best = matches.maxByOrNull { hits(it, question) } ?: matches.first()
        return ChatPlan(
            intent = best.intent,
            calls =
                matches
                    .sortedByDescending { hits(it, question) }
                    .flatMap { it.tools }
                    .distinct()
                    .take(registry.routing.maxToolsPerTurn)
                    .map { ToolCall(it) },
            chips = best.chips.take(registry.routing.maxChips),
        )
    }

    /** Result: how many of a route's keywords appear. Input: [route]; [question]. Output: [Int]. */
    private fun hits(
        route: IntentRoute,
        question: String,
    ): Int = route.keywords.count { it in question }

    override fun compose(composition: ChatComposition): Result<ChatReply, AppError> {
        val refusal = refusalFor(composition)
        return Ok(
            if (refusal != null) {
                refused(composition, refusal)
            } else {
                checked(composition)
            },
        )
    }

    /**
     * Whether there is anything to say before the guardrail is even asked.
     * Why:    three of the four refusals are decided by what the caller brought: a plan that
     *         already refused, tools that could not answer, and a model that did not speak. Each is
     *         a different sentence on screen, so each is a different reason here.
     * Result: the reason, or `null` when there is a draft worth checking.
     * Input:  [composition]. Output: `RefusalReason?`.
     */
    private fun refusalFor(composition: ChatComposition): RefusalReason? =
        when {
            composition.plan.refusal != null -> composition.plan.refusal
            composition.results.any { it.failed } -> RefusalReason.NO_DATA
            composition.draft.isNullOrBlank() -> RefusalReason.NO_MODEL
            else -> null
        }

    /**
     * Puts the model's draft through AI-ARC-004 and keeps it only if every figure checks out.
     * Why:    this is the whole point of the layer. The allowlist is built from the tool results
     *         and nothing else, so a figure the model invented has nothing to match against and the
     *         reply is dropped — **with its figures**, so no screen can reassemble the sentence.
     * Result: the answered reply, or a `GUARDRAIL_BLOCKED` refusal.
     * Input:  [composition]. Output: [ChatReply].
     */
    private fun checked(composition: ChatComposition): ChatReply {
        val figures = composition.results.flatMap { it.figures }
        val verdict =
            guardrail.verify(
                GuardrailInput(
                    candidateText = composition.draft.orEmpty(),
                    evidence = evidenceOf(figures),
                    attemptsMade = composition.attemptsMade,
                    nowUtcMillis = composition.request.nowUtcMillis,
                ),
            )
        return when (verdict) {
            is Err -> refused(composition, RefusalReason.GUARDRAIL_BLOCKED)
            is Ok ->
                when (val outcome = verdict.value) {
                    is GuardrailVerdict.Pass ->
                        ChatReply(
                            text = composition.draft.orEmpty(),
                            figures = figures,
                            chips = composition.plan.chips,
                            citations = composition.results.flatMap { it.citations }.distinct(),
                            refusal = null,
                            verified = outcome.verified,
                            provenance = provenance(composition),
                        )
                    else -> refused(composition, RefusalReason.GUARDRAIL_BLOCKED)
                }
        }
    }

    /**
     * Result: a reply with no words and no figures. Input: [composition]; [reason]. Output: reply.
     *
     * The chips survive a refusal: §19's refusal is "I can't do that, but here is what I can do".
     */
    private fun refused(
        composition: ChatComposition,
        reason: RefusalReason,
    ): ChatReply =
        ChatReply(
            text = "",
            figures = emptyList(),
            chips = composition.plan.chips,
            citations = emptyList(),
            refusal = reason,
            verified = emptyList(),
            provenance = provenance(composition),
        )

    /**
     * Turns the tools' figures into the guardrail's allowlist.
     * Why:    AI-GRD checks a claim against values **of its kind**, so the split has to survive the
     *         journey: a count of 12 and ₹12 are different claims, and collapsing them would let a
     *         model state one as the other.
     * Result: the evidence. Input: [figures]. Output: [GuardrailEvidence].
     */
    private fun evidenceOf(figures: List<ToolFigure>): GuardrailEvidence =
        GuardrailEvidence(
            amounts = figures.mapNotNull { if (it.kind == FigureKind.AMOUNT) it.amount else null },
            percents = figures.mapNotNull { if (it.kind == FigureKind.PERCENT) it.number else null },
            counts = figures.mapNotNull { if (it.kind == FigureKind.COUNT) it.number else null },
            dates = figures.mapNotNull { if (it.kind == FigureKind.DATE) it.isoDate?.let(::parseDate) else null },
        )

    /** Result: an ISO date, or `null` when it is not one. Input: [isoDate]. Output: `LocalDate?`. */
    private fun parseDate(isoDate: String): LocalDate? =
        try {
            LocalDate.parse(isoDate)
        } catch (_: DateTimeParseException) {
            null
        }

    /**
     * Result: who answered (AI-ARC-003). Input: [composition]. Output: [EngineProvenance].
     *
     * The model's id goes in `inputWindow` because that is the field that describes *what was
     * read*, and for this engine what was read is a model's draft over N tool results.
     */
    private fun provenance(composition: ChatComposition): EngineProvenance =
        EngineProvenance(
            engineId = ENGINE_ID,
            engineVersion = ENGINE_VERSION,
            computedAtUtcMillis = composition.request.nowUtcMillis,
            evidence =
                listOf(RuleCitation(REGISTRY_CITATION, registry.version)) +
                    composition.results.flatMap { it.citations }.distinct().map { RuleCitation(it, "1.0") },
            inputWindow = "model=${composition.modelId}, tools=${composition.results.size}",
        )

    private companion object {
        const val ENGINE_ID = "AI-CHAT"
        const val ENGINE_VERSION = "1.0"
        const val FIELD_TEXT = "chat.text"
        const val REGISTRY_CITATION = "CHT-ROUTE"
    }
}
