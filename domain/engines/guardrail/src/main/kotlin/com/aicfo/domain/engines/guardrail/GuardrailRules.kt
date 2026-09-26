package com.aicfo.domain.engines.guardrail

import com.aicfo.core.model.RuleCitation

/**
 * `RULE-GRD-LADDER` and `RULE-GRD-TRANSFORMS` from `ai/rules/rules-kb.json`, as a typed mirror
 * (issue 9.7; AI-ARC-004, CLAUDE.md §6).
 *
 * Why:  how many times a reply may be written again, and which renderings of a number count as the
 *       same number, are exactly the settings a reviewer should be able to tighten in one file —
 *       and exactly the ones that would otherwise become constants in an engine. There is no
 *       runtime loader (ADR-0017), so this is the copy the engine reads, held honest by
 *       `GuardrailRulebookDriftTest`.
 * What: the ladder's attempt limit, and the allowlist of display transforms (GRD-002).
 * Result: everything [GuardrailEngine] reads besides its input.
 * Changelog: 2026-09-23 — Created for issue 9.7 from rules-kb.json 1.20.0.
 *
 * Input:  [maxAttempts] — how many times unverifiable text may be sent back before it is refused,
 *         at least 0 (zero refuses immediately, which is a valid and very strict policy);
 *         [allowRoundedDisplay] — whether a figure may be rounded for display; [maxDisplayDecimals]
 *         — how many decimals a rendering may keep, 0..4; [allowLakhCroreWords] — whether
 *         "₹1.5 lakh" is the same figure as ₹1,50,000.00; [allowMinorUnits] — whether an amount may
 *         be spelled in paise; [allowBpsAsPercent] — whether basis points may be shown as a
 *         percentage; [allowYearAlone] — whether an engine date's year may stand on its own.
 * Output: an immutable value.
 */
data class GuardrailRules(
    val maxAttempts: Int = DEFAULT_MAX_ATTEMPTS,
    val allowRoundedDisplay: Boolean = true,
    val maxDisplayDecimals: Int = DEFAULT_MAX_DECIMALS,
    val allowLakhCroreWords: Boolean = true,
    val allowMinorUnits: Boolean = true,
    val allowBpsAsPercent: Boolean = true,
    val allowYearAlone: Boolean = true,
) {
    init {
        require(maxAttempts >= 0) { "a negative attempt limit has no meaning" }
        require(maxDisplayDecimals in 0..MAX_DECIMALS_CEILING) {
            "a rendering with more than $MAX_DECIMALS_CEILING decimals is not a display of anything"
        }
    }

    companion object {
        private const val DEFAULT_MAX_ATTEMPTS = 2
        private const val DEFAULT_MAX_DECIMALS = 2
        private const val MAX_DECIMALS_CEILING = 4

        /** The rulebook file these values were copied from, as `_meta.version`. */
        const val RULEBOOK_VERSION = "1.23.0"

        /** `RULE-GRD-LADDER` — pass, write it again, refuse. */
        val LADDER = RuleCitation("RULE-GRD-LADDER", "1.0")

        /** `RULE-GRD-TRANSFORMS` — the renderings that count as the same figure (GRD-002). */
        val TRANSFORMS = RuleCitation("RULE-GRD-TRANSFORMS", "1.0")
    }
}
