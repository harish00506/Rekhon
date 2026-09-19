package com.aicfo.domain.engines.forecast

import com.aicfo.core.common.AppError
import com.aicfo.core.common.Result
import com.aicfo.core.model.EngineProvenance
import com.aicfo.core.model.Money
import com.aicfo.domain.engines.seasonality.SeasonalityResult
import com.aicfo.domain.engines.seasonality.SpendFactor
import java.time.LocalDate

/**
 * AI-FCT — the cash-flow forecast (issue 9.2; SRS §9.1, §9.2, AI-ARC-003, P-08).
 *
 * Why:  every other figure in the app describes the past or this month. The forecast is the one
 *       that says what the next ninety days will look like — the day the balance dips, the day a
 *       payment lands on an empty account — while there is still time to move money (AI-FCT-002).
 *       §9.2 calls its method "deliberately simple, explainable, upgradeable", and that is the
 *       contract here: every rupee on a forecast day is either a scheduled item the user can name
 *       or a predicted everyday spend with its multipliers on record (AI-FCT-003).
 * What: a daily consolidated liquid-balance path for the horizon, with P10/P50/P90 bands from seeded
 *       resampling of past residuals, the crunch days below the buffer, and the three components.
 * Result: a [CashFlowForecast].
 * Changelog: 2026-09-19 — Created for issue 9.2.
 *            2026-09-19 — 1.1 for issue 9.3: §9.2's `seasonalAdjustment(d)` term, from AI-SEAS.
 *
 * One public interface; the implementation is internal (ARC-003). **§9.2's upgrade path — a learned
 * variable-spend model in Phase 4 — replaces the implementation behind this interface; the contract
 * must not change.** Pure: no clock (the caller passes `today` and `nowUtcMillis`), randomness only
 * from the input's seed, no I/O (P-08).
 */
interface ForecastEngine {
    /**
     * Forecasts the horizon after [ForecastInput.today].
     * Why:    one call per profile per day, so the bands, the crunch days and the components are
     *         computed from one set of draws and cannot disagree.
     * Result: `Ok(forecast)`; `Err(Validation(field))` for a negative daily spend (`forecast.spend`)
     *         or a scheduled item dated on or before today (`forecast.item`).
     * Input:  [input]. Output: `Result<CashFlowForecast, AppError>`.
     */
    fun forecast(input: ForecastInput): Result<CashFlowForecast, AppError>
}

/**
 * Builds the [ForecastEngine] (issue 9.2).
 * Result: the §9.2 heuristic engine. Input: none. Output: [ForecastEngine].
 * Changelog: 2026-09-19 — Created for issue 9.2.
 */
object ForecastEngineFactory {
    /** Input: none. Output: [ForecastEngine]. */
    fun create(): ForecastEngine = HeuristicForecastEngine()
}

/**
 * What the forecast is built from (issue 9.2).
 *
 * Why:  the repository decides what is scheduled and what counts as everyday spending; the engine
 *       projects, predicts and resamples. Keeping the joins out lets every identity be tested with
 *       literal dates and paise.
 * Input:  [today] — the last day of actuals, in the profile zone (TIM-001/002); the forecast starts
 *         tomorrow. [openingBalance] — consolidated liquid balance at the end of today.
 *         [commitments] — repeating items the engine projects by cadence. [oneOffs] — dated items
 *         (future-dated transactions), each after today. [dailySpend] — everyday outflow per day over
 *         the lookback, positive paise; a day absent from the list is a day with no spend, provided
 *         it is on or after [historyStart]. [historyStart] — the first day the ledger has anything,
 *         or `null` for none; days before it are not "zero spend", they are unknown. [seed] — the
 *         Monte Carlo seed (P-08). [nowUtcMillis] — stamped on the provenance. [rules] — RULE-FCT-*.
 *         [seasonality] — AI-SEAS's result for the horizon's months (issue 9.3), or `null` for none;
 *         a month it gives no factor for is ×1.
 * Output: an immutable value.
 * Changelog: 2026-09-19 — Created for issue 9.2.
 *            2026-09-19 — [seasonality] added for issue 9.3.
 */
data class ForecastInput(
    val today: LocalDate,
    val openingBalance: Money,
    val commitments: List<Commitment>,
    val oneOffs: List<ScheduledItem>,
    val dailySpend: List<DailySpend>,
    val historyStart: LocalDate?,
    val seed: Long,
    val nowUtcMillis: Long,
    val rules: ForecastRules = ForecastRules(),
    val seasonality: SeasonalityResult? = null,
)

/**
 * A repeating item: a confirmed recurring rule, or a stream 9.1 classified FIXED (issue 9.2).
 *
 * Input:  [label] — the name the user gave it ("Landlord", "Rent"), or `null` when there is none —
 *         the screen then supplies a generic word from its own resources (§21.6); [amount] — signed paise, positive for
 *         income; [cadence]; [nextDue] — its next date on or after tomorrow, or earlier (the engine
 *         rolls a stale date forward by whole cadences); [source].
 * Output: an immutable value.
 * Changelog: 2026-09-19 — Created for issue 9.2.
 */
data class Commitment(
    val label: String?,
    val amount: Money,
    val cadence: Cadence,
    val nextDue: LocalDate,
    val source: ItemSource,
)

/** How often a [Commitment] repeats; the values `recurring_rule.cadence` stores. */
enum class Cadence {
    WEEKLY,
    MONTHLY,
    YEARLY,
}

/**
 * Where a scheduled item came from — what the screen says when the user asks "why is this here?"
 * (AI-FCT-003, P-02).
 */
enum class ItemSource {
    /** A recurring rule the user confirmed (issue 3.7) or quick setup seeded (issue 2.3). */
    RECURRING_RULE,

    /** A stream AI-CLS Stage 2 scored FIXED (issue 9.1), projected on its usual day. */
    FIXED_STREAM,

    /** A future-dated transaction the user entered (issue 3.4). */
    FUTURE_DATED,
}

/**
 * One dated scheduled item in the horizon (issue 9.2).
 * Input:  [date]; [amount] — signed paise; [label] — as on [Commitment], nullable; [source].
 * Output: an immutable value.
 * Changelog: 2026-09-19 — Created for issue 9.2.
 */
data class ScheduledItem(
    val date: LocalDate,
    val amount: Money,
    val label: String?,
    val source: ItemSource,
)

/**
 * One day's everyday outflow (issue 9.2).
 * Input:  [date]; [amount] — positive paise (MNY-001). Output: an immutable value.
 * Changelog: 2026-09-19 — Created for issue 9.2.
 */
data class DailySpend(
    val date: LocalDate,
    val amount: Money,
)

/**
 * One forecast day (issue 9.2; AI-FCT-001, AI-FCT-003).
 *
 * Input:  [date]; [p10], [p50], [p90] — balance bands (P10 ≤ P50 ≤ P90); [scheduledNet] — that
 *         day's scheduled items summed, signed; [predictedSpend] — that day's predicted everyday
 *         outflow, positive; [seasonal] — §9.2's `seasonalAdjustment(d)`: the predicted spend times
 *         (the month's AI-SEAS factor − 1), HALF_EVEN, signed — positive is extra spend, and
 *         `predictedSpend + seasonal` is never negative; [expected] — the path with no surprises:
 *         opening + Σ scheduled − Σ (predicted + seasonal) up to and including this day.
 * Output: an immutable value.
 * Changelog: 2026-09-19 — Created for issue 9.2.
 *            2026-09-19 — [seasonal] added for issue 9.3.
 */
data class ForecastDay(
    val date: LocalDate,
    val p10: Money,
    val p50: Money,
    val p90: Money,
    val scheduledNet: Money,
    val predictedSpend: Money,
    val expected: Money,
    val seasonal: Money = Money.ZERO,
)

/**
 * One month the seasonal term moved, as the screen shows it (issue 9.3; P-02).
 * Input:  [factor] — AI-SEAS's factor for the month, with the events it names; [adjustment] — Σ of
 *         the month's [ForecastDay.seasonal] **inside the horizon**, signed.
 * Output: an immutable value.
 * Changelog: 2026-09-19 — Created for issue 9.3.
 */
data class SeasonalMonth(
    val factor: SpendFactor,
    val adjustment: Money,
)

/**
 * The forecast (issue 9.2; AI-FCT-001..003).
 *
 * Input:  [openingBalance] — the consolidated liquid balance at the end of today, echoed so the screen
 *         can show where the path starts; [days] — one per horizon day, in order; [scheduled] —
 *         every scheduled item in the horizon, dated, in date order (the inspectable list,
 *         AI-FCT-003); [scheduledIncome] and
 *         [scheduledOutflow] — their sums, both positive; [predictedSpend] — Σ predicted everyday
 *         outflow over the horizon; [dailyBase] — §9.2's trimmed-mean base, paise per day;
 *         [crunchDays] — days whose P50 is below [buffer] (RULE-FCT-CRUNCH); [lowest] — the day
 *         with the lowest P50 (earliest on a tie), `null` only for an empty horizon;
 *         [historyDays] — lookback days that had data; [seasonalAdjustment] — Σ of every day's
 *         seasonal amount, signed; [seasonalMonths] — the horizon's months whose adjustment is not
 *         zero, in order; [provenance] — AI-SEAS's evidence follows the forecast's own rules when an
 *         adjustment applies.
 * Output: an immutable value.
 * Changelog: 2026-09-19 — Created for issue 9.2.
 *            2026-09-19 — [seasonalAdjustment] and [seasonalMonths] added for issue 9.3.
 */
data class CashFlowForecast(
    val openingBalance: Money,
    val days: List<ForecastDay>,
    val scheduled: List<ScheduledItem>,
    val scheduledIncome: Money,
    val scheduledOutflow: Money,
    val predictedSpend: Money,
    val dailyBase: Money,
    val crunchDays: List<LocalDate>,
    val buffer: Money,
    val lowest: ForecastDay?,
    val historyDays: Int,
    val provenance: EngineProvenance,
    val seasonalAdjustment: Money = Money.ZERO,
    val seasonalMonths: List<SeasonalMonth> = emptyList(),
)
