package com.aicfo.domain.engines.seasonality

import com.aicfo.core.common.AppError
import com.aicfo.core.common.Result
import com.aicfo.core.model.EngineProvenance
import com.aicfo.core.model.Money
import java.time.LocalDate
import java.time.YearMonth

/**
 * AI-SEAS — how much a month is expected to cost compared with an ordinary one (issue 9.3; §9.3).
 *
 * Why:  the 9.2 forecast predicts everyday spend from the last ninety days, which is right for a
 *       quiet autumn and wrong for Diwali — and wrong the other way for October after a monsoon
 *       that inflated the lookback. §9.3 gives the answer: a **seasonal index per category and
 *       month**, from the user's own months across years where they exist and the Indian calendar
 *       knowledge base where they do not, shrunk toward "no change" while the history is short.
 * What: the L4 engine that turns closed-month category history into those indices, and turns them
 *       into one factor per month ahead for the forecast's everyday spend — the `seasonalAdjustment`
 *       term of §9.2's formula.
 * Result: a [SeasonalityResult] whose every index says where it came from (P-02).
 * Changelog: 2026-09-19 — Created for issue 9.3.
 *
 * Input:  [SeasonalityInput]. Output: `Result<SeasonalityResult, AppError>`; `Err` only for
 *         negative spend or a lookback that ends before it starts.
 */
interface SeasonalityEngine {
    /** Computes the indices and the monthly factors. See the interface's doc. */
    fun index(input: SeasonalityInput): Result<SeasonalityResult, AppError>
}

/**
 * Builds the one [SeasonalityEngine] (ARC-003 — the implementation stays `internal`).
 * Changelog: 2026-09-19 — Created for issue 9.3.
 */
object SeasonalityEngineFactory {
    /** Result: the §9.3 median-index engine. Input: none. Output: [SeasonalityEngine]. */
    fun create(): SeasonalityEngine = MedianSeasonalityEngine()
}

/**
 * What AI-SEAS reads (issue 9.3).
 *
 * Input:  [history] — spend per category per **closed** calendar month, oldest first or not (order
 *         is irrelevant); a month with no row for a category is a month it cost nothing, and every
 *         month with any row counts as observed; [lookback] — the forecast's everyday spend over
 *         [lookbackStart]..[lookbackEnd] (inclusive), per category, the weights the factor is taken
 *         over; [months] — the months to give a factor for; [nowUtcMillis] — stamped on provenance
 *         only; [rules] — `SEAS-INDEX`.
 * Output: an immutable value.
 * Changelog: 2026-09-19 — Created for issue 9.3.
 */
data class SeasonalityInput(
    val history: List<CategoryMonthSpend>,
    val lookback: List<CategorySpend>,
    val lookbackStart: LocalDate,
    val lookbackEnd: LocalDate,
    val months: List<YearMonth>,
    val nowUtcMillis: Long,
    val rules: SeasonalityRules = SeasonalityRules(),
)

/**
 * One category's spend in one closed month.
 * Input:  [categoryId] — `null` for uncategorised (counts the month as observed, has no index);
 *         [categoryName] — matched against the knowledge base's category names, case-insensitively;
 *         [month]; [amount] — paise, not negative. Output: an immutable value.
 * Changelog: 2026-09-19 — Created for issue 9.3.
 */
data class CategoryMonthSpend(
    val categoryId: String?,
    val categoryName: String?,
    val month: YearMonth,
    val amount: Money,
)

/**
 * One category's everyday spend over the lookback — its weight in the factor.
 * Input:  [categoryId] — `null` for uncategorised, which is weighted but never seasonal;
 *         [categoryName]; [amount] — paise, not negative. Output: an immutable value.
 * Changelog: 2026-09-19 — Created for issue 9.3.
 */
data class CategorySpend(
    val categoryId: String?,
    val categoryName: String?,
    val amount: Money,
)

/**
 * Where an index's raw value came from (P-02).
 * Changelog: 2026-09-19 — Created for issue 9.3.
 */
enum class IndexSource {
    /** The user's own median for this calendar month over their median month (§9.3 bullet 1). */
    OWN_HISTORY,

    /** A calendar event's prior — the user has no earlier year of this month to go on (§9.3 bullet 2). */
    CALENDAR_PRIOR,

    /** Neither: an ordinary month for this category. */
    NONE,
}

/**
 * One category's index for one month.
 * Input:  [rawBps] — before shrinkage (10 000 = ×1); [indexBps] — after `1 + k(raw − 1)`;
 *         [source]; [eventId] — the knowledge-base event when [source] is [IndexSource.CALENDAR_PRIOR].
 * Output: an immutable value.
 * Changelog: 2026-09-19 — Created for issue 9.3.
 */
data class SeasonalIndex(
    val categoryId: String,
    val categoryName: String?,
    val month: YearMonth,
    val rawBps: Int,
    val indexBps: Int,
    val source: IndexSource,
    val eventId: String?,
)

/**
 * The factor on everyday spend for one month, against the lookback it was measured on.
 * Why:  `Σ weight × index(month) / index(lookback)` — the lookback's own season is divided out, so
 *       a monsoon-heavy lookback does not carry its monsoon into October, and the factor is one
 *       number a reader can apply to the forecast's daily prediction.
 * Input:  [factorBps] — 10 000 = the lookback's pace; [rising] — calendar events that lift this
 *         month above the lookback, in knowledge-base order; [easing] — events that lifted the
 *         lookback and do not apply now; [fromOwnHistory] — whether any weighted category's index
 *         came from the user's own earlier years.
 * Output: an immutable value.
 * Changelog: 2026-09-19 — Created for issue 9.3.
 */
data class SpendFactor(
    val month: YearMonth,
    val factorBps: Int,
    val rising: List<String>,
    val easing: List<String>,
    val fromOwnHistory: Boolean,
)

/**
 * What AI-SEAS decided.
 * Input:  [indices] — every category seen in the history or the lookback, for every requested
 *         month, ordered by month then category id; [factors] — one per requested month, in the
 *         order asked; [monthsObserved] — the `months_observed` of the shrinkage; [provenance].
 * Output: an immutable value.
 * Changelog: 2026-09-19 — Created for issue 9.3.
 */
data class SeasonalityResult(
    val indices: List<SeasonalIndex>,
    val factors: List<SpendFactor>,
    val monthsObserved: Int,
    val provenance: EngineProvenance,
)
