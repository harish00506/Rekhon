package com.aicfo.domain.engines.forecast

import com.aicfo.core.common.AppError
import com.aicfo.core.common.Err
import com.aicfo.core.common.Ok
import com.aicfo.core.common.Result
import com.aicfo.core.model.EngineProvenance
import com.aicfo.core.model.Money
import java.math.BigDecimal
import java.math.MathContext
import java.math.RoundingMode
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth
import kotlin.random.Random

/**
 * §9.2's forecast, as written (issue 9.2; AI-FCT-001..003, P-02, P-08).
 *
 * Why:  §9.2 calls the method "deliberately simple, explainable, upgradeable", so this implements
 *       exactly its terms and nothing cleverer:
 *       `forecast(d) = opening + Σ scheduled(d) − predictedVariableSpend(d)`, with
 *       `predictedVariableSpend = base × dowAdj × domAdj` — a 10%-trimmed mean of the last ninety
 *       days, a weekend/weekday ratio of medians, and a three-bucket pay-cycle ratio of means — and
 *       bands from resampling the model's own past residuals, seeded. §9.2's seasonal term is
 *       AI-SEAS's monthly factor applied to each day's prediction (issue 9.3, ADR-0044).
 * What: validate → project commitments → fit the everyday-spend model on the lookback → predict each
 *       horizon day → resample residuals into P10/P50/P90 → crunch days and the lowest day.
 * Result: a [CashFlowForecast].
 * Changelog: 2026-09-19 — Created for issue 9.2.
 *            2026-09-19 — 1.1 for issue 9.3: the seasonal term; the expected path and so the bands
 *            pay it; its months and AI-SEAS's evidence are carried.
 *
 * Money is `Long` paise throughout (MNY-001); the model's ratios are exact `BigDecimal` and each
 * day's prediction is rounded HALF_EVEN to the paisa. Randomness is `kotlin.random.Random(seed)`,
 * whose algorithm is fixed and platform-independent, so a seed reproduces a forecast exactly (P-08).
 * `internal` per ARC-003; built only by [ForecastEngineFactory].
 */
internal class HeuristicForecastEngine : ForecastEngine {
    override fun forecast(input: ForecastInput): Result<CashFlowForecast, AppError> {
        validate(input)?.let { return Err(it) }
        val rules = input.rules
        val horizon = (1..rules.horizonDays).map { input.today.plusDays(it.toLong()) }
        val scheduled = schedule(input, horizon.last())
        val scheduledByDay = scheduled.groupBy { it.date }.mapValues { (_, items) -> items.sumOf { it.amount.minor } }
        val model = SpendModel.fit(input)
        val predicted = horizon.map { model.predict(it) }
        val seasonal = horizon.indices.map { k -> seasonalAmount(predicted[k], factorFor(input, horizon[k])) }
        val outflow = predicted.indices.map { k -> predicted[k] + seasonal[k] }
        val expected = expectedPath(input.openingBalance.minor, horizon.map { scheduledByDay[it] ?: 0L }, outflow)
        val bands = Bands.simulate(expected, model.residuals, rules, input.seed)
        val days =
            horizon.indices.map { k ->
                ForecastDay(
                    date = horizon[k],
                    p10 = Money(bands.low[k]),
                    p50 = Money(bands.mid[k]),
                    p90 = Money(bands.high[k]),
                    scheduledNet = Money(scheduledByDay[horizon[k]] ?: 0L),
                    predictedSpend = Money(predicted[k]),
                    expected = Money(expected[k]),
                    seasonal = Money(seasonal[k]),
                )
            }
        return Ok(assemble(input, days, scheduled, model))
    }

    /**
     * The inputs that cannot be forecast from.
     * Result: the first refusal, or `null`. Input: [input]. Output: `AppError.Validation?`.
     */
    private fun validate(input: ForecastInput): AppError.Validation? =
        when {
            input.dailySpend.any { it.amount.minor < 0L } -> AppError.Validation(FIELD_SPEND)
            input.oneOffs.any { it.date <= input.today } -> AppError.Validation(FIELD_ITEM)
            else -> null
        }

    /**
     * Every scheduled item in the horizon, dated and ordered (AI-FCT-003).
     * Why:    ordered by date, then label, then amount, so the inspectable list — and every sum over
     *         it — is the same whatever order the caller supplied (P-08).
     * Result: the items. Input: [input]; [end] — the last horizon day. Output: `List<ScheduledItem>`.
     */
    private fun schedule(
        input: ForecastInput,
        end: LocalDate,
    ): List<ScheduledItem> {
        val start = input.today.plusDays(1)
        val projected = input.commitments.flatMap { occurrences(it, start, end) }
        val oneOffs = input.oneOffs.filter { it.date <= end }
        return (projected + oneOffs).sortedWith(compareBy({ it.date }, { it.label.orEmpty() }, { it.amount.minor }))
    }

    /**
     * One commitment's dates in the window.
     * Why:    each date is counted **from the anchor** (`nextDue.plusMonths(k)`), never from the
     *         previous date, so a bill due on the 31st returns to the 31st after a 30-day month
     *         instead of drifting to the 28th for ever. A stale anchor is rolled forward by the same
     *         arithmetic.
     * Result: the items. Input: [commitment]; [start]; [end] — inclusive. Output: `List<ScheduledItem>`.
     */
    private fun occurrences(
        commitment: Commitment,
        start: LocalDate,
        end: LocalDate,
    ): List<ScheduledItem> =
        generateSequence(0L) { it + 1 }
            .map { k ->
                when (commitment.cadence) {
                    Cadence.WEEKLY -> commitment.nextDue.plusWeeks(k)
                    Cadence.MONTHLY -> commitment.nextDue.plusMonths(k)
                    Cadence.YEARLY -> commitment.nextDue.plusYears(k)
                }
            }
            .takeWhile { it <= end }
            .filter { it >= start }
            .map { ScheduledItem(it, commitment.amount, commitment.label, commitment.source) }
            .toList()

    /** Result: opening plus the running sum of each day's scheduled net less its everyday outflow. */
    private fun expectedPath(
        opening: Long,
        scheduledNet: List<Long>,
        outflow: List<Long>,
    ): List<Long> {
        var running = opening
        return scheduledNet.indices.map { k ->
            running = Math.addExact(running, scheduledNet[k] - outflow[k])
            running
        }
    }

    /** Result: AI-SEAS's factor for [day]'s month, in bps; ×1 when it gave none. */
    private fun factorFor(
        input: ForecastInput,
        day: LocalDate,
    ): Int = input.seasonality?.factors?.firstOrNull { it.month == YearMonth.from(day) }?.factorBps ?: FULL_BPS.toInt()

    /**
     * §9.2's `seasonalAdjustment(d)`: the day's prediction times `(factor − 1)`, HALF_EVEN.
     * Why:    applied to the **rounded** daily prediction, so the screen's two numbers for a day
     *         reconcile in whole paise; a factor is never negative, so `predicted + seasonal ≥ 0`.
     * Result: signed paise. Input: [predicted] paise; [factorBps]. Output: [Long].
     */
    private fun seasonalAmount(
        predicted: Long,
        factorBps: Int,
    ): Long =
        BigDecimal.valueOf(predicted)
            .multiply(BigDecimal.valueOf(factorBps - FULL_BPS))
            .divide(BigDecimal.valueOf(FULL_BPS))
            .setScale(0, RoundingMode.HALF_EVEN)
            .longValueExact()

    /**
     * The months the seasonal term moved, with their horizon totals (issue 9.3; P-02).
     * Result: in month order, zero-total months left out. Input: [input]; [days]. Output: a list.
     */
    private fun seasonalMonths(
        input: ForecastInput,
        days: List<ForecastDay>,
    ): List<SeasonalMonth> {
        val factors = input.seasonality?.factors.orEmpty()
        return days.groupBy { YearMonth.from(it.date) }.mapNotNull { (month, monthDays) ->
            val factor = factors.firstOrNull { it.month == month } ?: return@mapNotNull null
            val total = monthDays.sumOf { it.seasonal.minor }
            if (total == 0L) null else SeasonalMonth(factor, Money(total))
        }
    }

    /**
     * Puts the forecast together: totals, crunch days, the lowest day, provenance.
     * Result: the forecast. Input: [input]; [days]; [scheduled]; [model]. Output: [CashFlowForecast].
     */
    private fun assemble(
        input: ForecastInput,
        days: List<ForecastDay>,
        scheduled: List<ScheduledItem>,
        model: SpendModel,
    ): CashFlowForecast {
        val rules = input.rules
        val seasonalTotal = days.sumOf { it.seasonal.minor }
        val seasonalEvidence =
            if (days.any {
                    it.seasonal.minor != 0L
                }
            ) {
                input.seasonality?.provenance?.evidence.orEmpty()
            } else {
                emptyList()
            }
        return CashFlowForecast(
            openingBalance = input.openingBalance,
            days = days,
            scheduled = scheduled,
            scheduledIncome = Money(scheduled.filter { it.amount.minor > 0 }.sumOf { it.amount.minor }),
            scheduledOutflow = Money(-scheduled.filter { it.amount.minor < 0 }.sumOf { it.amount.minor }),
            predictedSpend = Money(days.sumOf { it.predictedSpend.minor }),
            dailyBase = Money(model.base.setScale(0, RoundingMode.HALF_EVEN).longValueExact()),
            crunchDays = days.filter { it.p50 < rules.buffer }.map { it.date },
            buffer = rules.buffer,
            lowest = days.minWithOrNull(compareBy({ it.p50 }, { it.date })),
            historyDays = model.historyDays,
            provenance =
                EngineProvenance(
                    engineId = ENGINE_ID,
                    engineVersion = ENGINE_VERSION,
                    computedAtUtcMillis = input.nowUtcMillis,
                    evidence = listOf(ForecastRules.METHOD, ForecastRules.CRUNCH) + seasonalEvidence,
                    inputWindow =
                        "${input.today.minusDays(rules.lookbackDays.toLong())}..${input.today.minusDays(1)}" +
                            " → ${days.first().date}..${days.last().date}",
                    confidenceBps = (model.historyDays.toLong() * FULL_BPS / rules.lookbackDays).toInt(),
                ),
            seasonalAdjustment = Money(seasonalTotal),
            seasonalMonths = seasonalMonths(input, days),
        )
    }

    private companion object {
        const val ENGINE_ID = "AI-FCT"
        const val ENGINE_VERSION = "1.1"
        const val FIELD_SPEND = "forecast.spend"
        const val FIELD_ITEM = "forecast.item"
        const val FULL_BPS = 10_000L
    }
}

/**
 * §9.2's two multipliers, as exact ratios (issue 9.2).
 *
 * Why:  `dowAdj` has two values (weekend, weekday) and `domAdj` three (the pay-cycle spike, the
 *       middle of the month, the trough); held together so the model is five numbers a reader can
 *       check, and so [SpendModel] stays a short constructor.
 * Input:  [weekend], [weekday], [spike], [mid], [trough] — each a ratio, 1 when its bucket or the
 *         whole is empty. Output: an immutable value.
 * Changelog: 2026-09-19 — Created for issue 9.2.
 */
internal data class Adjustments(
    val weekend: BigDecimal,
    val weekday: BigDecimal,
    val spike: BigDecimal,
    val mid: BigDecimal,
    val trough: BigDecimal,
)

/**
 * §9.2's everyday-spend model, fitted on the lookback (issue 9.2).
 *
 * Why:  `base × dowAdj × domAdj`, each term measured from the user's own history, so the prediction is
 *       numbers a reader can check. The residuals — what actually happened minus what the model
 *       would have predicted, day by day — are kept for the bands.
 * Input:  [base] — the trimmed mean, exact; [adjustments] — the multipliers; [residuals] — paise, in
 *         date order; [historyDays]; [rules].
 * Output: an immutable value.
 * Changelog: 2026-09-19 — Created for issue 9.2.
 */
internal class SpendModel(
    val base: BigDecimal,
    private val adjustments: Adjustments,
    val residuals: List<Long>,
    val historyDays: Int,
    private val rules: ForecastRules,
) {
    /**
     * The predicted everyday outflow on [day], HALF_EVEN to the paisa.
     * Result: paise, never negative. Input: [day]. Output: [Long].
     */
    fun predict(day: LocalDate): Long {
        val dow = if (day.isWeekend()) adjustments.weekend else adjustments.weekday
        val dom =
            when {
                day.dayOfMonth <= rules.monthStartSpikeLastDay -> adjustments.spike
                day.dayOfMonth >= rules.monthEndTroughFirstDay -> adjustments.trough
                else -> adjustments.mid
            }
        return base.multiply(dow, MATH).multiply(dom, MATH).setScale(0, RoundingMode.HALF_EVEN).longValueExact()
    }

    companion object {
        /**
         * Fits the model on the lookback window.
         * Why:    **days before the ledger starts are unknown, not zero** — a fortnight-old profile
         *         must not be averaged against ten weeks of invented zero spend. Inside the known
         *         window a day with no row *is* a zero: nothing was spent.
         * Result: the model; with no history, a zero base and no residuals. Input: [input].
         */
        fun fit(input: ForecastInput): SpendModel {
            val rules = input.rules
            val lookbackStart = input.today.minusDays(rules.lookbackDays.toLong())
            val first = input.historyStart?.let { maxOf(it, lookbackStart) }
            val days =
                if (first == null || first >= input.today) {
                    emptyList()
                } else {
                    generateSequence(first) { it.plusDays(1) }.takeWhile { it < input.today }.toList()
                }
            val spentByDay =
                input.dailySpend.groupBy { it.date }.mapValues {
                        (_, rows) ->
                    rows.sumOf { it.amount.minor }
                }
            val series = days.map { day -> day to (spentByDay[day] ?: 0L) }
            return fitSeries(series, rules)
        }

        /** Result: the model fitted on [series] (date, paise) in date order. Input: [series]; [rules]. */
        private fun fitSeries(
            series: List<Pair<LocalDate, Long>>,
            rules: ForecastRules,
        ): SpendModel {
            val values = series.map { it.second }
            val allMedian = median(values)
            val allMean = mean(values)

            fun medianWhere(keep: (LocalDate) -> Boolean) = median(series.filter { keep(it.first) }.map { it.second })

            fun meanWhere(keep: (LocalDate) -> Boolean) = mean(series.filter { keep(it.first) }.map { it.second })
            val spikeEnd = rules.monthStartSpikeLastDay
            val troughStart = rules.monthEndTroughFirstDay
            val adjustments =
                Adjustments(
                    weekend = ratio(medianWhere { it.isWeekend() }, allMedian),
                    weekday = ratio(medianWhere { !it.isWeekend() }, allMedian),
                    spike = ratio(meanWhere { it.dayOfMonth <= spikeEnd }, allMean),
                    mid = ratio(meanWhere { it.dayOfMonth in (spikeEnd + 1) until troughStart }, allMean),
                    trough = ratio(meanWhere { it.dayOfMonth >= troughStart }, allMean),
                )
            val fitted = SpendModel(trimmedMean(values, rules.trimBps), adjustments, emptyList(), series.size, rules)
            val residuals = series.map { (day, spent) -> spent - fitted.predict(day) }
            return SpendModel(fitted.base, adjustments, residuals, series.size, rules)
        }
    }
}

/**
 * The P10/P50/P90 bands (issue 9.2; §9.2 "Monte Carlo (n=500, seeded) resampling daily residuals").
 *
 * Why:  the spread comes from the user's own past surprises, not an assumed distribution. Each path
 *       draws one past residual per future day, with replacement, and accumulates them onto the
 *       expected path; a percentile per day across the paths gives the band. With no residuals every
 *       path is the expected path and the bands collapse onto it — which is the honest answer for a
 *       history with no surprises in it.
 * Input:  [low], [mid], [high] — paise per horizon day. Output: an immutable value.
 * Changelog: 2026-09-19 — Created for issue 9.2.
 */
internal class Bands(
    val low: List<Long>,
    val mid: List<Long>,
    val high: List<Long>,
) {
    companion object {
        /**
         * Runs the simulations.
         * Why:    a spend above prediction is a positive residual and lowers the balance, so a path is
         *         `expected − Σ drawn residuals`. The draws do not depend on the balance or the
         *         scheduled items, which is what makes "a bill lowers every band by exactly the bill"
         *         an identity rather than an approximation.
         * Result: the three bands. Input: [expected] paise per day; [residuals]; [rules]; [seed].
         */
        fun simulate(
            expected: List<Long>,
            residuals: List<Long>,
            rules: ForecastRules,
            seed: Long,
        ): Bands {
            if (residuals.isEmpty()) return Bands(expected, expected, expected)
            val random = Random(seed)
            val paths = Array(rules.simulations) { LongArray(expected.size) }
            for (path in paths) {
                var drift = 0L
                for (k in expected.indices) {
                    drift = Math.addExact(drift, residuals[random.nextInt(residuals.size)])
                    path[k] = expected[k] - drift
                }
            }
            val byDay = expected.indices.map { k -> LongArray(paths.size) { paths[it][k] }.also { it.sort() } }
            return Bands(
                low = byDay.map { nearestRank(it, rules.bandLowPct) },
                mid = byDay.map { nearestRank(it, rules.bandMidPct) },
                high = byDay.map { nearestRank(it, rules.bandHighPct) },
            )
        }

        /** Result: the nearest-rank [percent]th value of [sorted]: index ⌈p·n/100⌉ − 1. */
        private fun nearestRank(
            sorted: LongArray,
            percent: Int,
        ): Long {
            val rank = (percent.toLong() * sorted.size + PERCENT - 1) / PERCENT
            return sorted[(rank - 1).toInt().coerceIn(0, sorted.lastIndex)]
        }

        private const val PERCENT = 100L
    }
}

/** 34 significant digits, HALF_EVEN — fixed so the model never varies between runs or machines. */
private val MATH: MathContext = MathContext.DECIMAL128

private fun LocalDate.isWeekend(): Boolean = dayOfWeek == DayOfWeek.SATURDAY || dayOfWeek == DayOfWeek.SUNDAY

/**
 * §9.2's trimmed mean: drop ⌊n·trim⌋ values from each end of the sorted list, average the rest.
 * Result: the exact mean, or zero for an empty list. Input: [values] paise; [trimBps].
 */
private fun trimmedMean(
    values: List<Long>,
    trimBps: Int,
): BigDecimal {
    if (values.isEmpty()) return BigDecimal.ZERO
    val cut = (values.size.toLong() * trimBps / FULL_BPS_LONG).toInt()
    return mean(values.sorted().subList(cut, values.size - cut))
}

/** Result: the exact mean, or zero for an empty list. */
private fun mean(values: List<Long>): BigDecimal =
    if (values.isEmpty()) {
        BigDecimal.ZERO
    } else {
        BigDecimal.valueOf(values.sum()).divide(BigDecimal.valueOf(values.size.toLong()), MATH)
    }

/** Result: the exact median (the mean of the two middles for an even count), or zero when empty. */
private fun median(values: List<Long>): BigDecimal {
    if (values.isEmpty()) return BigDecimal.ZERO
    val sorted = values.sorted()
    val mid = sorted.size / 2
    return if (sorted.size % 2 == 1) {
        BigDecimal.valueOf(sorted[mid])
    } else {
        BigDecimal.valueOf(sorted[mid - 1] + sorted[mid]).divide(BigDecimal.valueOf(2), MATH)
    }
}

/**
 * A multiplier: [part] ÷ [whole], or exactly 1 when either is zero.
 * Why:    a sparse history has a median daily spend of zero, and a class or bucket with no days has no
 *         figure at all; neither is evidence the day is different, so neither may bend the base.
 * Result: the exact ratio. Input: [part]; [whole]. Output: [BigDecimal].
 */
private fun ratio(
    part: BigDecimal,
    whole: BigDecimal,
): BigDecimal = if (whole.signum() == 0 || part.signum() == 0) BigDecimal.ONE else part.divide(whole, MATH)

private const val FULL_BPS_LONG = 10_000L
