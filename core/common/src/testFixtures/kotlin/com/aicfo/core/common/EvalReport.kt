package com.aicfo.core.common

/**
 * What an evaluation run measured, against what it required (issue 12.2; §21.5).
 *
 * Why:  §21.5 sets accuracy floors — categorisation ≥ 92%, receipts ≥ 95%, SMS ≥ 95% — and by issue
 *       12.2 all four runners asserted them and **none reported the number**. A gate that only says
 *       "passed" hides the thing worth watching: a 92% floor sitting at 92.1% is one bad case from
 *       red, and nobody can see it coming. Three releases later someone loosens the floor rather than
 *       fixing the regression, because by then there is no record of what the score used to be.
 * What: the share, the formatted line, and refusals to report nonsense.
 * Result: a CI log carries the accuracy trend, and a failure message already contains it.
 * Changelog: 2026-10-02 — Created for issue 12.2.
 *
 * Input:  [dataset] — the set's name, as it appears in reports; [version] — its declared revision,
 *         from [EvalDataset]; [floorPercent] — the §21.5 floor, inclusive.
 * Output: a reporter.
 *
 * **The share is truncated, never rounded.** 91.9% must not print as 92% beside a 92% floor: a
 * failing run would read as a passing one in the single line a reader trusts.
 */
class EvalReport(
    private val dataset: String,
    private val version: String,
    private val floorPercent: Int,
) {
    init {
        require(version.isNotBlank()) { "the dataset version must not be blank (issue 12.2, §21.5)" }
        require(floorPercent in 0..FULL) { "a floor must be a percentage; was $floorPercent" }
    }

    /**
     * The measured share as a whole percentage, truncated.
     * Why:    integer and truncating so the comparison against the floor cannot be flattered by
     *         rounding. 919/1000 is 91, not 92.
     * Result: 0..100. Input: [correct]; [total]. Output: [Int].
     * Changelog: 2026-10-02 — Created for issue 12.2.
     */
    fun percent(
        correct: Int,
        total: Int,
    ): Int {
        validate(correct, total)
        return correct * FULL / total
    }

    /**
     * Whether the run met its floor.
     * Result: `true` at or above the floor — inclusive, because §21.5 says "at least", and an
     *         exclusive boundary would silently make every threshold a point stricter than the SRS.
     * Input:  [correct]; [total]. Output: [Boolean].
     * Changelog: 2026-10-02 — Created for issue 12.2.
     */
    fun meetsFloor(
        correct: Int,
        total: Int,
    ): Boolean = percent(correct, total) >= floorPercent

    /**
     * The one line a run prints and a failure carries.
     * Why:    one format for all four runners, so a reader comparing two engines' logs is comparing
     *         like with like, and so the raw counts are always present — a share with no counts
     *         cannot be checked.
     * Result: e.g. `categorisation v1.0 — accuracy 94.7% (72/76), floor 92% — ok`, or `… — BELOW`.
     * Input:  [metric] — what was measured; [correct]; [total]. Output: [String].
     * Changelog: 2026-10-02 — Created for issue 12.2.
     */
    fun line(
        metric: String,
        correct: Int,
        total: Int,
    ): String {
        validate(correct, total)
        val tenths = correct.toLong() * FULL * TENTHS / total
        val whole = tenths / TENTHS
        val fraction = tenths % TENTHS
        val verdict = if (percent(correct, total) >= floorPercent) "ok" else "BELOW the §21.5 floor"
        return "$dataset v$version — $metric $whole.$fraction% ($correct/$total), floor $floorPercent% — $verdict"
    }

    /**
     * Refuses to report on something that cannot be a result.
     * Why:    `0/0` is not 100%: it is a dataset that was never loaded, and reporting it as a pass is
     *         the vacuous-gate failure this project has found five times. `11/10` is a counting bug in
     *         the runner, and 110% would look like good news.
     * Result: returns, or throws [IllegalArgumentException]. Input: [correct]; [total]. Output: none.
     * Changelog: 2026-10-02 — Created for issue 12.2.
     */
    private fun validate(
        correct: Int,
        total: Int,
    ) {
        require(total > 0) {
            "cannot report on an empty evaluation set ($dataset v$version): 0/0 is not 100%, it is a " +
                "dataset that was never loaded"
        }
        require(correct <= total) {
            "more correct ($correct) than total ($total) in $dataset v$version — that is a counting " +
                "bug in the runner, not a result"
        }
        require(correct >= 0) { "negative correct count ($correct) in $dataset v$version" }
    }

    private companion object {
        const val FULL = 100
        const val TENTHS = 10
    }
}
