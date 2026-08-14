package com.aicfo.core.model

/**
 * What [NumericGuardrail.verify] concluded about a piece of user-facing text.
 *
 * Why:  a boolean would lose the one thing a caller needs when the answer is no — *which* figures
 *       could not be traced. The full L3 gate (issue 9.7) hands those spans back to the model to
 *       regenerate; this one logs the count and stays silent, but it must still be able to say what
 *       it objected to or the failure is undiagnosable.
 * What: a closed result — passed, or a list of the offending spans.
 * Result: the caller either renders the text or does not; there is no third option and no "render it
 *       anyway with a warning" (AI-ARC-004).
 * Changelog: 2026-08-13 — Created for issue 4.5 (FR-BUD-004).
 */
sealed interface GuardrailResult {
    /** Every figure in the text traced back to an engine result. Safe to display. */
    data object Pass : GuardrailResult

    /**
     * At least one figure could not be traced.
     * Input: [spans] — the offending substrings, in the order they appear, for diagnosis.
     *        **Never log these** — they are amounts (`CfoPiiInLogs`, §21.6).
     */
    data class Unverifiable(val spans: List<String>) : GuardrailResult
}

/**
 * Checks that every figure in a piece of user-facing text came from an engine (AI-ARC-004).
 *
 * Why:  P-03 says numbers come from math and words come from the AI, and AI-ARC-004 makes that
 *       enforceable rather than aspirational: text is checked against the values that produced it
 *       before anyone sees it. Issue 4.5 needs this for a notification, which is the worst possible
 *       place for a wrong number — it arrives unsolicited, out of context, and the user cannot tap
 *       through to the working.
 *
 *       **This is a minimal, deliberate subset of `ai/chat/guardrail.md`, and that is recorded, not
 *       hidden.** The full L3 gate — extraction of dates and scores, the lakh/crore and FX display
 *       transforms, and the REGENERATE-then-REFUSE ladder — is **issue 9.7's** to build.
 *       Two of those are not merely unbuilt here but inapplicable: there is no LLM anywhere on this
 *       path (the text is templated from engine output), so there is nothing to regenerate, and no
 *       fallback to compose. What is left is the part that matters — deterministic matching, never
 *       the model judging itself (GRD-001).
 * What: extracts every ₹-amount, percentage and plain count from the text and resolves each against
 *       the values the engine actually produced, rendered through the one sanctioned formatter.
 * Result: [GuardrailResult.Pass], or the spans that failed. **Fail-closed**: a caller that cannot
 *       verify its text does not send it.
 * Changelog:
 *   2026-08-13 — Created for issue 4.5 (FR-BUD-004).
 *   2026-08-14 — Counts extracted, for issue 4.6's review notification ("3 categories to look at").
 *                This closes the limitation ADR-0019 wrote down rather than routing around it: that
 *                ADR stated in its own words that "a caller that templates a bare count would not be
 *                checked", and 4.6 is the first caller that wants to.
 *   2026-08-14 — Added `allowedText` in the same change, because count extraction on its own broke
 *                a case nobody had thought about: a user's own category name may contain a digit
 *                ("Zone 3 parking"), and without a way to say "this substring is a name I supplied,
 *                not a claim", the gate would have refused the app's own correct notification —
 *                quietly, since a refusal looks exactly like a denied permission at the call site.
 *
 * Pure Kotlin in `:core:model` beside [MoneyFormatter], which it reuses on purpose: the allowlist of
 * renderings is *defined* as "whatever the one formatter the app displays amounts with produces", so
 * the two cannot drift into disagreeing about what ₹1,23,456.78 looks like (ARC-002, GRD-002).
 *
 * **Known limitations, stated rather than papered over.** A count is recognised as a run of one to
 * three digits, so a **four-digit or longer** bare number is not checked at all — years are the
 * reason, and `COUNT_CLAIM` argues it. Dates and scores are still unextracted. And `allowedText` is
 * a hole by construction: whatever a caller passes there is struck out before counts are read, so it
 * must only ever be the **verbatim value the caller itself interpolated** — a name read from the
 * database — and never a fragment chosen to make a failing check pass. Issue 9.7 widens the
 * extractor; until then, callers must not template large bare numbers.
 */
object NumericGuardrail {
    /**
     * A currency claim: an optional minus, ₹, digits with optional grouping, optional decimals.
     * Deliberately permissive about *shape* — anything a reader would take for an amount has to be
     * caught and then judged, because a fabricated figure that the extractor skips is worse than one
     * it rejects.
     */
    private val AMOUNT_CLAIM = Regex("""-?₹\s?[\d,]+(?:\.\d+)?""")

    /** A percentage claim. Decimals included so "79.5%" is judged rather than silently allowed. */
    private val PERCENT_CLAIM = Regex("""\d+(?:\.\d+)?\s?%""")

    /**
     * A count claim — a standalone run of **one to three digits**, found only in text from which the
     * amount and percentage spans have already been removed.
     *
     * `ponytail:` three digits is the ceiling, and it is a heuristic rather than a proof. Four-digit
     * tokens are overwhelmingly years in this app's copy ("July 2026") and treating them as counts
     * would make every message carrying a date unverifiable. The cost is stated plainly: a caller
     * that templates a fabricated four-digit count is **not** checked. The upgrade path, when a
     * message needs one, is a count the caller declares explicitly rather than a wider regex —
     * widening this one starts an arms race with the copy.
     */
    private val COUNT_CLAIM = Regex("""\b\d{1,3}\b""")

    /**
     * Decides whether a candidate string may be shown to the user.
     *
     * Why:    the check runs on the composed text rather than on the template, because the template
     *         is not what the user reads. A resource string edited to add "that's about ₹500 a week"
     *         would sail past any check on the arguments alone, and would be exactly the kind of
     *         plausible invention AI-ARC-004 exists to stop.
     * What:   extracts claims, normalises whitespace out of them, and requires each to equal a
     *         permitted rendering of a value the caller was actually given.
     * Result: [GuardrailResult.Pass] when every claim resolves, [GuardrailResult.Unverifiable] with
     *         the failures otherwise. Text with no figures at all passes — there is nothing to
     *         fabricate.
     * Input:  [candidateText] — the composed, user-facing string; [allowedAmounts] — every [Money]
     *         the engine produced for this message; [allowedPercents] — every whole percentage it
     *         produced; [allowedCounts] — every plain integer it produced ("3 categories");
     *         [allowedText] — verbatim strings the caller interpolated that are **names, not
     *         claims**, struck out before counts are read. All four default to empty, which makes
     *         the strict thing the easy thing: a caller that forgets to pass its values gets a
     *         refusal, not a pass.
     * Output: [GuardrailResult].
     */
    fun verify(
        candidateText: String,
        allowedAmounts: List<Money> = emptyList(),
        allowedPercents: List<Int> = emptyList(),
        allowedCounts: List<Int> = emptyList(),
        allowedText: List<String> = emptyList(),
    ): GuardrailResult {
        val permittedAmounts = allowedAmounts.map { MoneyFormatter.format(it) }.toSet()
        val permittedPercents = allowedPercents.map { "$it%" }.toSet()
        val permittedCounts = allowedCounts.map { "$it" }.toSet()

        // Counts are read from text the amounts, percentages and caller-supplied names have been
        // struck out of. Without the first two, the "1" of "₹1,240" and the "40" of "40%" would be
        // offered up as counts, and a caller would have to allow them to get its own message through
        // — turning the count check into a rubber stamp for the digits it exists to police. Without
        // the third, a user's own "Zone 3 parking" would make every notification about that category
        // unverifiable, and the guardrail would silently suppress the app's own correct message.
        val remainder =
            allowedText.filter { it.isNotBlank() }
                .fold(candidateText) { text, name -> text.replace(name, BLANK) }
                .let { AMOUNT_CLAIM.replace(it) { BLANK } }
                .let { PERCENT_CLAIM.replace(it) { BLANK } }

        val unverifiable =
            buildList {
                AMOUNT_CLAIM.findAll(candidateText)
                    .filterNot { it.value.normalised() in permittedAmounts }
                    .forEach { add(it.value) }
                PERCENT_CLAIM.findAll(candidateText)
                    .filterNot { it.value.normalised() in permittedPercents }
                    .forEach { add(it.value) }
                COUNT_CLAIM.findAll(remainder)
                    .filterNot { it.value in permittedCounts }
                    .forEach { add(it.value) }
            }

        return if (unverifiable.isEmpty()) GuardrailResult.Pass else GuardrailResult.Unverifiable(unverifiable)
    }

    /**
     * Removes the whitespace a translator may put inside a figure.
     * Why:    "₹ 1,000.00" and "₹1,000.00" are the same claim to a reader, and a guardrail that
     *         rejected the first would be a guardrail teams learn to route around. Nothing else is
     *         normalised — in particular the digits, the separators and the decimals are compared
     *         exactly, so "₹1,000" is *not* accepted for "₹1,000.00" (GRD-002: the allowlist is the
     *         formatter's output, not anything that rounds to it).
     * Result: the claim with spaces removed. Input: the receiver. Output: [String].
     */
    private fun String.normalised(): String = filterNot { it.isWhitespace() }

    /**
     * What an amount or percentage span is replaced by before counts are read.
     * A space, not an empty string: removing "₹1,240" from "spent ₹1,240 of" outright would join
     * "spent" to "of" and could weld two digit runs into one that neither claim contained.
     */
    private const val BLANK = " "
}
