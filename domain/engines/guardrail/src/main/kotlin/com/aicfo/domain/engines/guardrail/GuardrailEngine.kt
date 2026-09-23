package com.aicfo.domain.engines.guardrail

import com.aicfo.core.common.AppError
import com.aicfo.core.common.Result
import com.aicfo.core.model.EngineProvenance
import com.aicfo.core.model.Money
import java.math.BigDecimal
import java.time.LocalDate

/**
 * AI-GRD — no figure reaches the user that an engine did not produce (issue 9.7; AI-ARC-004, P-03,
 * `ai/chat/guardrail.md`).
 *
 * Why:  a language model will write a confident wrong number as readily as a right one, and a
 *       wrong rupee figure in a financial app is not a typo — it is advice the user may act on.
 *       AI-ARC-004 makes P-03 enforceable instead of aspirational: text is checked against the
 *       values that produced it, deterministically, **before** anyone sees it. The model is never
 *       asked to certify itself (GRD-001), because a model that can invent a figure can invent the
 *       justification too.
 * What: extract every numeric claim from the text, resolve each against the evidence through an
 *       allowlist of display transforms, and answer with the ladder — pass, write it again, or
 *       refuse.
 * Result: a [GuardrailVerdict]. Nothing here rewrites the text: the caller renders it, asks the
 *       model again, or falls back. This engine only decides.
 * Changelog: 2026-09-23 — Created for issue 9.7, superseding `core:model`'s minimal
 *            `NumericGuardrail` (ADR-0048).
 *
 * Input:  [GuardrailInput]. Output: `Result<GuardrailVerdict, AppError>`; `Err` only for an
 *         impossible input (a negative attempt count).
 *
 * **Pure and LLM-free** (ARC-002, P-08, GRD-001): string and number matching, no clock, no I/O.
 */
interface GuardrailEngine {
    /** Checks one candidate text. See the interface's doc. */
    fun verify(input: GuardrailInput): Result<GuardrailVerdict, AppError>
}

/**
 * Builds the one [GuardrailEngine] (ARC-003 — the implementation stays `internal`).
 * Changelog: 2026-09-23 — Created for issue 9.7.
 */
object GuardrailEngineFactory {
    /** Result: the AI-ARC-004 gate. Input: none. Output: [GuardrailEngine]. */
    fun create(): GuardrailEngine = VerifyingGuardrailEngine()
}

/**
 * Everything the engines and tools actually produced for this piece of text.
 *
 * Why:  the allowlist is the whole gate. A caller that forgets to pass a value gets a refusal, not
 *       a pass, which is the fail-closed direction: a correct message that is silently not sent
 *       costs less than a fabricated figure that is.
 * What: one list per kind of figure, in the units the engines publish them in (MNY-001 paise,
 *       MNY-002 basis points).
 * Result: what a claim may resolve to.
 * Changelog: 2026-09-23 — Created for issue 9.7.
 *
 * Input:  [amounts] — every [Money] behind the text; [percents] — whole percentages; [percentsBps] —
 *         percentages the engine published as basis points, which the text may show as a
 *         percentage; [counts] — plain integers, **including scores**, which are integers like any
 *         other; [quantities] — non-integer, non-money figures such as months of runway;
 *         [dates] — every date an engine produced (GRD-004); [names] — verbatim strings the caller
 *         interpolated that are **names, not claims** ("Zone 3 parking"), struck out before any
 *         number is read.
 * Output: an immutable value.
 */
data class GuardrailEvidence(
    val amounts: List<Money> = emptyList(),
    val percents: List<Int> = emptyList(),
    val percentsBps: List<Int> = emptyList(),
    val counts: List<Int> = emptyList(),
    val quantities: List<BigDecimal> = emptyList(),
    val dates: List<LocalDate> = emptyList(),
    val names: List<String> = emptyList(),
)

/**
 * What AI-GRD reads.
 * Input:  [candidateText] — the composed, user-facing text, never the template: a resource string
 *         edited to add "that's about ₹500 a week" would sail past any check on the arguments alone;
 *         [evidence]; [attemptsMade] — how many times this text has already been sent back to be
 *         written again, 0 on the first pass; [nowUtcMillis] — stamped on provenance; [rules].
 * Output: an immutable value.
 * Changelog: 2026-09-23 — Created for issue 9.7.
 */
data class GuardrailInput(
    val candidateText: String,
    val evidence: GuardrailEvidence = GuardrailEvidence(),
    val attemptsMade: Int = 0,
    val nowUtcMillis: Long = 0L,
    val rules: GuardrailRules = GuardrailRules(),
)

/**
 * What kind of figure a claim is, which decides what it may resolve to.
 * Changelog: 2026-09-23 — Created for issue 9.7.
 */
enum class ClaimKind {
    /** A rupee figure, or an amount spelled in paise. */
    AMOUNT,

    /** A percentage. */
    PERCENT,

    /** A date or a month and year (GRD-004). */
    DATE,

    /** Any other bare number: a count, a score, a quantity, or a year on its own. */
    NUMBER,
}

/**
 * One numeric claim found in the text.
 * Input:  [span] — the substring exactly as written, which is what a caller shows the model when it
 *         asks for the text again; [kind]; [index] — where it starts, so the spans can be reported
 *         in reading order. **Never log a span** — it is an amount (`CfoPiiInLogs`, §21.6).
 * Output: an immutable value.
 * Changelog: 2026-09-23 — Created for issue 9.7.
 */
data class GuardrailClaim(
    val span: String,
    val kind: ClaimKind,
    val index: Int,
)

/**
 * What the gate decided (issue 9.7; `ai/chat/guardrail.md` step 4).
 *
 * Why:  three outcomes and no fourth. There is deliberately no "show it with a warning": a figure
 *       the app cannot trace is one it does not state.
 * Changelog: 2026-09-23 — Created for issue 9.7.
 */
sealed interface GuardrailVerdict {
    /** Every claim resolved; the caller may render the text. */
    val provenance: EngineProvenance

    /**
     * The claims that resolved, in reading order. On a refusal these are the only figures a
     * fallback may repeat (GRD-005).
     */
    val verified: List<GuardrailClaim>

    /**
     * Every figure traced back to an engine result. Safe to display, with evidence chips.
     * Input: [verified]; [provenance]. Output: an immutable value.
     */
    data class Pass(
        override val verified: List<GuardrailClaim>,
        override val provenance: EngineProvenance,
    ) : GuardrailVerdict

    /**
     * Some figure did not resolve, and there are attempts left.
     * Why:   most failures are a real number the model forgot to fetch, and handing back the
     *        offending spans fixes those cheaply. The engine hands back spans, not a sentence: the
     *        prompt is the chat layer's to write.
     * Input: [unverifiable] — the offending spans in reading order; [verified]; [attemptsLeft] —
     *        how many more times the text may be written again; [provenance].
     */
    data class Regenerate(
        val unverifiable: List<GuardrailClaim>,
        override val verified: List<GuardrailClaim>,
        val attemptsLeft: Int,
        override val provenance: EngineProvenance,
    ) : GuardrailVerdict

    /**
     * Some figure did not resolve and the attempts are spent, so the text is not shown.
     * Why:   the backstop for the claim no tool can support — exactly the case where a
     *        confident-sounding fabrication does the most harm. GRD-005: the fallback the caller
     *        composes may repeat [verified] and nothing else, and it must say what could not be
     *        verified rather than quietly dropping the question.
     * Input: [unverifiable]; [verified]; [provenance].
     */
    data class Refuse(
        val unverifiable: List<GuardrailClaim>,
        override val verified: List<GuardrailClaim>,
        override val provenance: EngineProvenance,
    ) : GuardrailVerdict
}
