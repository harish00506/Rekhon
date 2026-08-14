package com.aicfo.domain.engines.budget

import com.aicfo.core.model.EngineProvenance
import com.aicfo.core.model.Money
import com.aicfo.core.model.sum
import kotlin.math.absoluteValue

/**
 * §5.5's month-end review, kept apart from the suggestion, pace and band math (issue 4.6).
 *
 * Why:  this is the only operation in `BudgetEngine` that judges a month that has already happened,
 *       and the only one whose whole job is deciding **what is worth mentioning**. Holding it in its
 *       own unit keeps that judgement readable in one screen, and keeps [DefaultBudgetEngine] from
 *       becoming the file where every budget concern lands — the same argument [BudgetAlertBands]
 *       and [BudgetPace] made in 4.5.
 * What: the variance, the materiality verdict, the month totals, and the provenance naming the row
 *       behind them.
 * Result: a [BudgetReview] or `null`, reproducible from its inputs alone (P-08).
 * Changelog: 2026-08-14 — Created for issue 4.6 (§5.5).
 *
 * **This object does not know how to price a budget, and deliberately does not learn.** It is handed
 * a `propose` function and calls it only for the rows it has already judged material. That is what
 * guarantees a reviewed proposal is the *identical* number the suggestion screen would offer for the
 * same category — two implementations of "the median of three months" would be two things to keep in
 * step, and the copy would lose.
 *
 * `internal` — the public seam is [BudgetEngine.review] (ARC-003). **No `Double` anywhere**
 * (MNY-001/MNY-002) and **no clock** (TIM-001): the instant is the caller's.
 */
internal object BudgetMonthReview {
    /** The engine that owns this decision; repeated here so provenance reads the same either way. */
    private const val ENGINE_ID = "budget-planner"

    /** Bump when the variance or materiality arithmetic changes (AI-ARC-006). */
    private const val ENGINE_VERSION = "1.0"

    /**
     * Reviews one closed month.
     *
     * Why:    **every** budgeted category is returned, on-plan ones included, rather than only the
     *         material ones. "Three of your eight categories drifted" is a different and more honest
     *         statement than "three categories drifted", and the screen cannot make the first one
     *         from a list that has already been filtered. [BudgetReview.materialCategories] is there
     *         for callers that only want the findings.
     *
     *         The month totals are summed here, not by a caller: P-03 does not exempt addition, and a
     *         total added up in a ViewModel is still a figure the app displayed that no engine
     *         produced.
     * Result: a [BudgetReview], or `null` when no category had a budget — which is not an error but
     *         the state of a user who has not budgeted yet.
     * Input:  [input] — the closed month and its budgeted categories; [propose] — priced by
     *         [BudgetEngine.suggest], called **only** for material rows and free to return `null`
     *         when a category has too little history.
     * Output: [BudgetReview]?.
     */
    fun evaluate(
        input: BudgetReviewInput,
        propose: (ReviewedCategoryInput) -> BudgetSuggestion?,
    ): BudgetReview? {
        if (input.categories.isEmpty()) return null

        val rows = input.categories.map { category -> reviewed(category, input.rules, propose) }
        return BudgetReview(
            monthStartIsoDate = input.monthStartIsoDate,
            totalBudgeted = rows.map { it.budgeted }.sum(),
            totalActual = rows.map { it.actual }.sum(),
            categories = rows,
            provenance = provenance(input),
        )
    }

    /**
     * Reviews one category's month.
     *
     * Why:    the proposal is asked for **after** the verdict, never before. An on-plan category must
     *         not be priced at all — not because the number would be wrong, but because computing an
     *         alternative the app has decided not to offer is how an unoffered suggestion later leaks
     *         onto a screen.
     * Result: a [ReviewedCategory], material or not.
     * Input:  [category]; [rules] — carries the materiality threshold; [propose].
     * Output: [ReviewedCategory].
     */
    private fun reviewed(
        category: ReviewedCategoryInput,
        rules: BudgetRules,
        propose: (ReviewedCategoryInput) -> BudgetSuggestion?,
    ): ReviewedCategory {
        val varianceBps = varianceBps(category)
        val direction = direction(category, varianceBps, rules)
        return ReviewedCategory(
            categoryId = category.categoryId,
            categoryName = category.categoryName,
            budgeted = category.budgeted,
            actual = category.actual,
            // Signed: positive is an overspend. `direction` carries the verdict, so nothing downstream
            // has to re-derive it from this sign and a threshold.
            variance = category.actual - category.budgeted,
            varianceBps = varianceBps,
            direction = direction,
            proposal = if (direction == VarianceDirection.ON_PLAN) null else propose(category),
        )
    }

    /**
     * How far the month missed the plan, as a share of the plan, in basis points.
     *
     * Why:    a **magnitude**, so the threshold comparison is one `>=` rather than a signed range
     *         with two boundaries and two chances to be wrong at the edge. Direction is a separate
     *         question with its own answer.
     *
     *         Scaled before dividing so the truncation happens once, and truncating is the right
     *         direction here: 14.99% of a budget is not the 15% the rule asks for, so a category is
     *         never reported as drifting a rupee early.
     * Result: 0 or more. A category budgeted at zero returns 0 — with no plan there is no share of a
     *         plan to be off by, and treating "spent ₹5,000 against no budget" as infinite drift
     *         would put a divide-by-zero one refactor away and rank a non-finding above every real
     *         one. Such a row is reported [VarianceDirection.ON_PLAN] and says nothing, which is
     *         correct: the review covers budgeted categories, and an unbudgeted one belongs to
     *         FR-BUD-002's suggestion cards.
     * Input:  [category]. Output: [Int], saturated at [Int.MAX_VALUE] exactly as the alert ratio is.
     */
    private fun varianceBps(category: ReviewedCategoryInput): Int {
        if (category.budgeted == Money.ZERO) return 0
        val gap = (category.actual.minor - category.budgeted.minor).absoluteValue
        return (gap * BPS_FULL / category.budgeted.minor).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
    }

    /**
     * The materiality verdict for one category.
     *
     * Why:    `>=` matches the rule's wording — 15% is inside the band, not past it — and matches
     *         `RULE-BUD-ALERT`'s bands, so the two thresholds in this engine behave the same way at
     *         their boundaries. A user who reads that the review speaks at 15% and finds it silent at
     *         exactly 15% has found a bug, whichever way the code meant it.
     * Result: [VarianceDirection.ON_PLAN] below the threshold; otherwise OVER or UNDER by the sign.
     * Input:  [category]; [varianceBps] — the magnitude from [varianceBps]; [rules].
     * Output: [VarianceDirection].
     */
    private fun direction(
        category: ReviewedCategoryInput,
        varianceBps: Int,
        rules: BudgetRules,
    ): VarianceDirection =
        when {
            varianceBps < rules.minVariancePct * BPS_PER_PERCENT -> VarianceDirection.ON_PLAN
            category.actual > category.budgeted -> VarianceDirection.OVER
            else -> VarianceDirection.UNDER
        }

    /**
     * Provenance for a review (AI-ARC-003).
     *
     * Why:    `RULE-BUD-REVIEW` alone. The row that priced each proposal is cited on the proposal
     *         itself, where a reader looking at a number will find it — citing both here would
     *         attribute the *decision to speak* partly to a rule that had no part in it, and would
     *         claim `RULE-BUD-SUGGEST` even for a review whose every category was on plan and which
     *         therefore priced nothing.
     * Result: engine id/version, the caller's instant, the cited row, and the reviewed month as the
     *         input window — this result is entirely about one month, so naming it is what makes a
     *         stored review reproducible (AI-ARC-006).
     * Input:  [input]. Output: [EngineProvenance].
     */
    private fun provenance(input: BudgetReviewInput): EngineProvenance =
        EngineProvenance(
            engineId = ENGINE_ID,
            engineVersion = ENGINE_VERSION,
            computedAtUtcMillis = input.nowUtcMillis,
            evidence = listOf(BudgetRules.REVIEW),
            inputWindow = input.monthStartIsoDate,
        )
}
