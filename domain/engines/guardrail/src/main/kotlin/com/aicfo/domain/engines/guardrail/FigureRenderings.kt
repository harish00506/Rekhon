package com.aicfo.domain.engines.guardrail

/**
 * Every way the app itself would write these figures (ADR-0080; `ai/chat/guardrail.md` GRD-002).
 *
 * Why:  [AllowedRenderings] already decides this, for the gate that matters — but it decides it
 *       *inside* the guardrail, at the end of the turn. `:ml:llm`'s sanitiser has to make the same
 *       judgement one step earlier, on a neural model's draft, and the two must not disagree. When
 *       they did, the stricter one won invisibly: a model writing "₹61,427" where the prompt said
 *       "₹61,427.00" was refused by the sanitiser although AI-GRD would have passed it, which means
 *       the neural path was being switched off by a check nobody could see.
 *
 *       So the renderings are published rather than copied. There is one definition of "the same
 *       figure, written differently", it lives beside the rules that configure it, and a change to
 *       `RULE-GRD-TRANSFORMS` reaches both layers at once (CLAUDE.md §6).
 * What: the union of every rendering of every evidence value, normalised the way the guardrail
 *       normalises a claim — whitespace removed, lower-cased.
 * Result: a set a caller can test a span against, or subtract from a sentence to find the digits
 *         nothing accounts for.
 * Changelog: 2026-10-10 — Created for ADR-0080.
 *
 * **Kind-blind on purpose, and only safe because of what the caller does with it.** The guardrail
 * checks a claim against values *of its kind* and must keep doing so — stating a count as an
 * amount is a real failure. This set is for the weaker question that comes first: *is this digit
 * one the engines produced at all?* A sanitiser that answered the kind question too would be a
 * second guardrail, and a second guardrail is a second thing to drift.
 */
object FigureRenderings {
    /**
     * Result: every rendering of every value in [evidence], normalised.
     * Input:  [evidence] — what the engines produced; [rules] — which display transforms are on,
     *         defaulting to the bundled rulebook mirror.
     * Output: `Set<String>`.
     */
    fun of(
        evidence: GuardrailEvidence,
        rules: GuardrailRules = GuardrailRules(),
    ): Set<String> = AllowedRenderings(evidence, rules).all()

    /**
     * Result: the comparable form of a written span — what [of]'s members are already in.
     * Why:    a caller comparing its own text against this set has to normalise it the same way, and
     *         asking it to reimplement "ignore whitespace, ignore case" is asking for the two to
     *         diverge on the first unusual input.
     * Input:  [written]. Output: [String].
     */
    fun normalise(written: String): String = AllowedRenderings.normalise(written)
}
