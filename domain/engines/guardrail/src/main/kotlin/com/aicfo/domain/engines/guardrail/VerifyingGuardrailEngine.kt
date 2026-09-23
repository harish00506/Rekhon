package com.aicfo.domain.engines.guardrail

import com.aicfo.core.common.AppError
import com.aicfo.core.common.Err
import com.aicfo.core.common.Ok
import com.aicfo.core.common.Result
import com.aicfo.core.model.EngineProvenance

/**
 * `ai/chat/guardrail.md`, as written (issue 9.7; AI-GRD, AI-ARC-004, P-03, P-08, ADR-0048).
 *
 * Why:  four steps in a fixed order, because the order is what makes the answer explicable.
 *       **Extract** every figure a reader would take for a claim. **Resolve** each against what the
 *       engines produced, through an allowlist of renderings. **Classify** it as verified or not.
 *       **Decide**: all verified is a pass; otherwise write it again while there are attempts left,
 *       and refuse when they are spent. The model is never consulted about its own output
 *       (GRD-001) — this is string and number matching, start to finish.
 * What: validate → extract → resolve → the ladder → provenance.
 * Result: a [GuardrailVerdict].
 * Changelog: 2026-09-23 — Created for issue 9.7.
 *
 * No clock, no randomness, no I/O (P-08): the same text and the same evidence always give the same
 * verdict, which is what makes a refusal arguable. `internal` per ARC-003.
 */
internal class VerifyingGuardrailEngine : GuardrailEngine {
    override fun verify(input: GuardrailInput): Result<GuardrailVerdict, AppError> {
        validate(input)?.let { return Err(it) }
        val allowed = AllowedRenderings(input.evidence, input.rules)
        val (verified, unverifiable) =
            ClaimExtractor(input.rules).extract(input.candidateText, input.evidence.names)
                .partition(allowed::permits)
        return Ok(decide(input, verified, unverifiable))
    }

    /**
     * The ladder (RULE-GRD-LADDER).
     * Why:    regenerate before refuse, because most failures are a real number the model forgot to
     *         fetch. Refusal is the backstop, and it keeps the verified figures so the caller's
     *         fallback can still say what the data does show (GRD-005).
     * Result: the verdict. Input: [input]; [verified]; [unverifiable]. Output: [GuardrailVerdict].
     */
    private fun decide(
        input: GuardrailInput,
        verified: List<GuardrailClaim>,
        unverifiable: List<GuardrailClaim>,
    ): GuardrailVerdict {
        val provenance = provenance(input)
        val attemptsLeft = input.rules.maxAttempts - input.attemptsMade
        return when {
            unverifiable.isEmpty() -> GuardrailVerdict.Pass(verified, provenance)
            attemptsLeft > 0 -> GuardrailVerdict.Regenerate(unverifiable, verified, attemptsLeft, provenance)
            else -> GuardrailVerdict.Refuse(unverifiable, verified, provenance)
        }
    }

    /**
     * The inputs no verdict can be reached from.
     * Result: the first refusal by field, or `null`. Input: [input]. Output: `AppError.Validation?`.
     */
    private fun validate(input: GuardrailInput): AppError.Validation? =
        when {
            input.attemptsMade < 0 -> AppError.Validation(FIELD_ATTEMPTS)
            else -> null
        }

    /**
     * Provenance (AI-ARC-003): both rules, and when the check ran. **No confidence and no input
     * window** — a verification is not an estimate, and it looks at one piece of text rather than a
     * period.
     * Result: the provenance. Input: [input]. Output: [EngineProvenance].
     */
    private fun provenance(input: GuardrailInput) =
        EngineProvenance(
            engineId = ENGINE_ID,
            engineVersion = ENGINE_VERSION,
            computedAtUtcMillis = input.nowUtcMillis,
            evidence = listOf(GuardrailRules.LADDER, GuardrailRules.TRANSFORMS),
        )

    private companion object {
        const val ENGINE_ID = "AI-GRD"
        const val ENGINE_VERSION = "1.0"
        const val FIELD_ATTEMPTS = "guardrail.attemptsMade"
    }
}
