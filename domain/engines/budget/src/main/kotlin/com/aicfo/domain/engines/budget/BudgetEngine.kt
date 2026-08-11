package com.aicfo.domain.engines.budget

import com.aicfo.core.common.AppError
import com.aicfo.core.common.Result
import com.aicfo.core.model.EngineProvenance
import com.aicfo.core.model.Money

/**
 * Proposes a per-category budget from history, and reports how a live budget is tracking (§5.5).
 *
 * Why:  FR-BUD-002 and FR-BUD-003 are the two questions a budget screen has to answer — "what
 *       should this cost?" and "am I on track?" — and both are arithmetic. P-03 puts them here, in
 *       a deterministic engine, so the numbers exist before any words are put around them.
 * What: one interface, two operations, each returning a value that carries the rule that produced
 *       it (AI-ARC-003).
 * Result: every figure on the budget screen is reproducible from its inputs and attributable to a
 *       rulebook row a reviewer can open (P-02, P-08).
 * Changelog: 2026-08-11 — Created for issue 4.4 (FR-BUD-002, FR-BUD-003).
 *
 * **This engine performs no calendar arithmetic.** It is handed `daysInPeriod` and `daysElapsed`
 * already resolved by the repository, which owns the injected `Clock` and the profile time zone
 * (TIM-001). That keeps every figure below provable on the JVM with no clock to freeze.
 *
 * **It also never applies anything.** `suggest` returns advice; only the user's tap writes a budget
 * (P-07).
 */
interface BudgetEngine {
    /**
     * Proposes an amount for one category's next month (FR-BUD-002).
     * Result: a [BudgetSuggestion], or `Ok(null)` when there is too little history to have an
     *         opinion — which is a legitimate answer, not an error.
     * Input:  [input] — the category's monthly totals and the month being budgeted for.
     * Output: `Result<BudgetSuggestion?, AppError>`.
     */
    fun suggest(input: BudgetSuggestionInput): Result<BudgetSuggestion?, AppError>

    /**
     * Reports spent, remaining, pace and projection for one live budget (FR-BUD-003).
     * Result: a [BudgetStatus]; its projection is `null` early in the month by design.
     * Input:  [input] — the budget, what has been spent against it, and the month's shape.
     * Output: `Result<BudgetStatus, AppError>`.
     */
    fun status(input: BudgetStatusInput): Result<BudgetStatus, AppError>
}

/**
 * One month's total spend in one category.
 *
 * Why:  the suggestion is a median over months, so the engine needs the months separated rather
 *       than a single total — and it needs them labelled, so the provenance can state the window it
 *       actually read (AI-ARC-003).
 * What: an ISO month start and the amount spent in it.
 * Result: an input the repository can build straight from a `GROUP BY` without further shaping.
 * Changelog: 2026-08-11 — Created for issue 4.4.
 *
 * Input:  [monthStartIsoDate] — TIM-002 `yyyy-MM-dd`, the first of the month; [amount] — the
 *         positive total spent, in paise (MNY-001).
 * Output: an immutable value.
 */
data class MonthlySpend(val monthStartIsoDate: String, val amount: Money) {
    init {
        require(monthStartIsoDate.length == ISO_DATE_LENGTH) {
            "monthStartIsoDate must be an ISO yyyy-MM-dd date (TIM-002), was '$monthStartIsoDate'"
        }
        require(amount >= Money.ZERO) { "a month's spend total is a magnitude and cannot be negative" }
    }
}

/**
 * Everything [BudgetEngine.suggest] needs to propose one category's budget.
 *
 * Why:  FR-BUD-002 names three inputs — history, a median, and a seasonality adjustment — and the
 *       adjustment needs both the category's *name* (the seasonality KB indexes by name) and the
 *       month being budgeted for.
 * What: the history window, the target month, and how much of this profile's life the app has seen.
 * Result: a self-contained input; the same value always yields the same suggestion (P-08).
 * Changelog: 2026-08-11 — Created for issue 4.4.
 *
 * Input:  [categoryId] — carried through to the result so a caller can match suggestions back;
 *         [categoryName] — matched against the seasonality KB's `inflates`, case-insensitively;
 *         [monthlySpends] — most recent last, at most [BudgetRules.lookbackMonths] are read;
 *         [targetMonth] — 1..12, the month being budgeted for; [monthsObserved] — months of history
 *         this profile has, which shrinks the seasonal prior; [nowUtcMillis] — **passed in, never
 *         read for calendar logic** (TIM-001), stamped onto the provenance; [rules] — injected so a
 *         test can move a threshold.
 * Output: an immutable value.
 */
data class BudgetSuggestionInput(
    val categoryId: String,
    val categoryName: String,
    val monthlySpends: List<MonthlySpend>,
    val targetMonth: Int,
    val monthsObserved: Int,
    val nowUtcMillis: Long,
    val rules: BudgetRules = BudgetRules(),
) {
    init {
        require(categoryId.isNotBlank()) { "categoryId must not be blank" }
        require(targetMonth in 1..MONTHS_IN_YEAR) { "targetMonth must be 1..12, was $targetMonth" }
        require(monthsObserved >= 0) { "monthsObserved cannot be negative, was $monthsObserved" }
    }
}

/**
 * A proposed budget amount and the reasoning behind it.
 *
 * Why:  P-02 — a number with no visible rule is a black-box verdict, and P-07 means the user has to
 *       be able to disagree with it on informed terms. The provenance carries both the rulebook row
 *       and the history window the median came from.
 * What: the amount, the seasonal event that moved it (if any), and full provenance.
 * Result: the screen can say "₹4,200 — median of 3 months, +38% for Diwali" without inventing
 *       anything (AI-ARC-003, AI-ARC-006).
 * Changelog: 2026-08-11 — Created for issue 4.4.
 *
 * Input:  [amount] — the proposal, rounded to [BudgetRules.roundToMinor]; [medianAmount] — the
 *         unadjusted median, so the screen can show what seasonality changed; [seasonalEventId] —
 *         the KB event cited, or `null` when the month is ordinary; [seasonalIndexBps] — the
 *         applied index after shrinkage, 10 000 = no change; [provenance] — engine id/version, the
 *         caller's instant, the rules cited and the window read.
 * Output: an immutable value.
 */
data class BudgetSuggestion(
    val amount: Money,
    val medianAmount: Money,
    val seasonalEventId: String?,
    val seasonalIndexBps: Int,
    val provenance: EngineProvenance,
) {
    init {
        require(provenance.evidence.isNotEmpty()) { "a suggestion with no cited rule violates P-02" }
        require(provenance.inputWindow != null) {
            "a suggestion must state the history window it read (AI-ARC-003)"
        }
        require(seasonalIndexBps >= BPS_FULL) { "a seasonal index below 10 000 bps would cut a budget" }
    }

    /** Result: true when seasonality actually moved the number, so the screen knows to explain it. */
    val isSeasonallyAdjusted: Boolean get() = seasonalIndexBps > BPS_FULL && seasonalEventId != null
}

/**
 * Everything [BudgetEngine.status] needs to report on one live budget.
 *
 * Why:  FR-BUD-003 asks for pace, and pace is the budget divided by the month — which means the
 *       engine needs the month's *shape*, not its dates. Passing days rather than a `Clock` keeps
 *       the module pure (ARC-002) and the whole calculation frozen in a test.
 * What: the planned amount, anything rolled over, what has been spent, and the month's shape.
 * Result: a self-contained input; the same value always yields the same status (P-08).
 * Changelog: 2026-08-11 — Created for issue 4.4.
 *
 * Input:  [categoryId]; [plannedAmount] — the budget the user set; [carriedOver] — unspent budget
 *         rolled in from last month when `rollover_enabled` (FR-BUD-001), else [Money.ZERO];
 *         [spent] — the positive total spent this month; [daysInPeriod] — days in the budget month;
 *         [daysElapsed] — days of it gone, inclusive of today, `1..daysInPeriod`; [nowUtcMillis] —
 *         **passed in, never read for calendar logic** (TIM-001); [rules] — injected.
 * Output: an immutable value.
 */
data class BudgetStatusInput(
    val categoryId: String,
    val plannedAmount: Money,
    val carriedOver: Money = Money.ZERO,
    val spent: Money,
    val daysInPeriod: Int,
    val daysElapsed: Int,
    val nowUtcMillis: Long,
    val rules: BudgetRules = BudgetRules(),
) {
    init {
        require(categoryId.isNotBlank()) { "categoryId must not be blank" }
        require(plannedAmount >= Money.ZERO) { "a planned budget cannot be negative" }
        require(spent >= Money.ZERO) { "spend is a magnitude and cannot be negative" }
        require(daysInPeriod in MIN_DAYS_IN_MONTH..MAX_DAYS_IN_MONTH) {
            "daysInPeriod must be a real month length 28..31, was $daysInPeriod"
        }
        require(daysElapsed in 1..daysInPeriod) {
            "daysElapsed must be 1..$daysInPeriod, was $daysElapsed"
        }
    }
}

/**
 * How one budget is tracking, mid-month.
 *
 * Why:  FR-BUD-003 lists four figures, and three of them are only meaningful together — spent
 *       against remaining says nothing about whether the month will end well, which is the question
 *       a user actually has on the 12th.
 * What: the four required figures plus the rollover-inclusive budget they are measured against.
 * Result: the screen renders numbers it did not compute (P-03).
 * Changelog: 2026-08-11 — Created for issue 4.4.
 *
 * Input:  [budgeted] — planned + carried over, the figure everything else is measured against;
 *         [carriedOver]; [spent]; [remaining] — may be **negative**, which is precisely how an
 *         overspend shows; [safePaceToDate] — the budget spread evenly, up to today;
 *         [projectedEndOfMonth] — the run rate extrapolated, or `null` before
 *         [BudgetRules.minElapsedDaysForProjection] days have passed; [provenance].
 * Output: an immutable value.
 */
data class BudgetStatus(
    val budgeted: Money,
    val carriedOver: Money,
    val spent: Money,
    val remaining: Money,
    val safePaceToDate: Money,
    val projectedEndOfMonth: Money?,
    val provenance: EngineProvenance,
) {
    init {
        require(provenance.evidence.isNotEmpty()) { "a status with no cited rule violates P-02" }
    }

    /** Result: true when more has been spent than the budget allows — an overspend, already. */
    val isOverspent: Boolean get() = remaining < Money.ZERO

    /** Result: true when spending is ahead of the even-pace line, whether or not it has overspent. */
    val isAheadOfPace: Boolean get() = spent > safePaceToDate

    /**
     * Result: true when the run rate points past the budget. `false` while the projection is
     * withheld — an unknown is not a warning, and turning one into a warning on day 1 is the failure
     * [BudgetRules.minElapsedDaysForProjection] exists to prevent.
     */
    val isProjectedToOverspend: Boolean get() = projectedEndOfMonth?.let { it > budgeted } ?: false
}

/** Result: a [BudgetEngine]. Why: the implementation is `internal`, so this is the only seam (ARC-003). */
object BudgetEngineFactory {
    /** Result: the default engine. Input: none. Output: [BudgetEngine]. */
    fun create(): BudgetEngine = DefaultBudgetEngine()
}

/** `yyyy-MM-dd` (TIM-002). */
internal const val ISO_DATE_LENGTH = 10

/** February. No calendar produces a shorter month, so anything below this is a caller bug. */
internal const val MIN_DAYS_IN_MONTH = 28

/** The longest month. */
internal const val MAX_DAYS_IN_MONTH = 31
