package com.aicfo.domain.engines.stream

import com.aicfo.core.common.AppError
import com.aicfo.core.common.Err
import com.aicfo.core.common.Ok
import com.aicfo.core.common.Result
import com.aicfo.core.model.EngineProvenance
import com.aicfo.core.model.Money
import com.aicfo.core.model.RuleCitation
import java.math.BigDecimal
import java.math.MathContext
import java.math.RoundingMode
import java.time.LocalDate
import java.time.YearMonth

/**
 * The §8.2 stream classifier (issue 9.1; AI-CLS Stage 2).
 *
 * Why:  §8.2 states the formula in fractions — a coefficient of variation, a ratio of medians, a
 *       share of days — and a square root sits inside the first. **Every value is computed exactly
 *       in `BigDecimal` to 34 significant digits and the class is decided on that exact score**;
 *       only the numbers shown are rounded to basis points (HALF_EVEN). Rounding each part first and
 *       adding the rounded parts moves the score by up to a basis point, which is enough to move a
 *       stream sitting on a threshold — and a score that depends on the order of rounding is not
 *       deterministic in any sense a reader would accept (P-08). `BigDecimal` arithmetic is fully
 *       specified, so the same input gives the same bits on every JVM.
 * What: validate → per stream: monthly totals, then (if there are enough months) cv, cadence,
 *       day-lock and score → class by the first matching `CLS-STR-*` step → totals by class.
 * Result: a [StreamProfile].
 * Changelog: 2026-09-19 — Created for issue 9.1.
 *
 * `internal` per ARC-003; built only by [StreamEngineFactory].
 */
internal class DefaultStreamEngine : StreamEngine {
    override fun classify(input: StreamInput): Result<StreamProfile, AppError> {
        validate(input)?.let { return Err(it) }
        val window = "${input.windowStart}..${input.windowEnd}"
        val verdicts =
            input.streams
                .sortedBy { it.streamKey }
                .mapNotNull { stream -> verdictFor(stream, input, window) }
        return Ok(
            StreamProfile(
                streams = verdicts,
                fixedLoad = total(verdicts, StreamClass.FIXED),
                semiFixedExpected = total(verdicts, StreamClass.SEMI_FIXED),
                variableBudgetable = total(verdicts, StreamClass.VARIABLE),
                provenance =
                    provenance(
                        evidence = verdicts.flatMap { it.provenance.evidence }.distinct(),
                        window = window,
                        now = input.nowUtcMillis,
                        confidenceBps = null,
                    ),
            ),
        )
    }

    /**
     * The inputs that cannot be scored.
     * Result: the first refusal, or `null`. Input: [input]. Output: `AppError.Validation?`.
     */
    private fun validate(input: StreamInput): AppError.Validation? =
        when {
            input.windowEnd < input.windowStart -> AppError.Validation(FIELD_WINDOW)
            input.streams.map { it.streamKey }.toSet().size != input.streams.size -> AppError.Validation(FIELD_KEY)
            input.streams.any { stream -> stream.occurrences.any { it.amount.minor < 0L } } ->
                AppError.Validation(FIELD_AMOUNT)
            else -> null
        }

    /**
     * Classifies one stream, or drops it when nothing of it falls inside the window.
     * Why:    a stream with no occurrence in the window has no typical amount and nothing to measure;
     *         reporting it as VARIABLE-with-zero would be a class invented from absence.
     * Result: the verdict, or `null`. Input: [stream]; [input]; [window]. Output: `StreamVerdict?`.
     */
    private fun verdictFor(
        stream: StreamHistory,
        input: StreamInput,
        window: String,
    ): StreamVerdict? {
        val inside = stream.occurrences.filter { it.bookedOn in input.windowStart..input.windowEnd }
        if (inside.isEmpty()) return null
        val monthly =
            inside.groupBy { YearMonth.from(it.bookedOn) }.values.map {
                    month ->
                month.sumOf { it.amount.minor }
            }
        val rules = input.rules
        val measured = if (monthly.size >= rules.coldStartMinMonths) Measured.of(monthly, inside, rules) else null
        val decision = decide(stream, monthly.size, measured, rules)
        return StreamVerdict(
            streamKey = stream.streamKey,
            streamClass = decision.streamClass,
            basis = decision.basis,
            metrics = measured?.metrics,
            typicalMonthly = Money(median(monthly)),
            provenance = provenance(decision.evidence, window, input.nowUtcMillis, decision.confidenceBps),
        )
    }

    /**
     * `stream_classification.steps`, first match wins: pin → known obligation → cold start → score.
     * Result: the decision. Input: [stream]; [activeMonths]; [measured]; [rules]. Output: [Decision].
     */
    private fun decide(
        stream: StreamHistory,
        activeMonths: Int,
        measured: Measured?,
        rules: StreamRules,
    ): Decision {
        val pin = stream.pin
        return when {
            pin != null -> Decision(pin, StreamBasis.PINNED, listOf(StreamRules.PINNED), rules.pinnedConfidenceBps)
            stream.isKnownObligation ->
                Decision(
                    StreamClass.FIXED,
                    StreamBasis.KNOWN_OBLIGATION,
                    listOf(StreamRules.KNOWN_OBLIGATION),
                    rules.obligationConfidenceBps,
                )
            measured == null -> coldStart(stream.priorKey, rules)
            else ->
                Decision(
                    measured.classify(activeMonths, rules),
                    StreamBasis.SCORED,
                    listOf(StreamRules.SCORED),
                    rules.scoredConfidenceBps,
                )
        }
    }

    /**
     * §8.2's cold start: the category's prior, or VARIABLE with the lowest confidence when there is none.
     * Why:    VARIABLE, not FIXED, for an unknown stream — FIXED would tell a forecast to reserve money
     *         for a commitment nobody has shown exists, while VARIABLE only calls it budgetable.
     * Result: the decision. Input: [priorKey]; [rules]. Output: [Decision].
     */
    private fun coldStart(
        priorKey: String?,
        rules: StreamRules,
    ): Decision {
        val prior = priorKey?.let(rules.priors::get)
        val citation = priorKey?.let(StreamRules.PRIOR_CITATIONS::get)
        return if (prior != null && citation != null) {
            Decision(
                prior,
                StreamBasis.COLD_START_PRIOR,
                listOf(StreamRules.COLD_START, citation),
                rules.priorConfidenceBps,
            )
        } else {
            Decision(
                StreamClass.VARIABLE,
                StreamBasis.COLD_START_NO_PRIOR,
                listOf(StreamRules.COLD_START),
                rules.noPriorConfidenceBps,
            )
        }
    }

    /** Result: Σ typical monthly of the verdicts in [streamClass]. Input: [verdicts]; [streamClass]. */
    private fun total(
        verdicts: List<StreamVerdict>,
        streamClass: StreamClass,
    ): Money = Money(verdicts.filter { it.streamClass == streamClass }.sumOf { it.typicalMonthly.minor })

    /** Result: the provenance every verdict and the profile carry (AI-ARC-003). */
    private fun provenance(
        evidence: List<RuleCitation>,
        window: String,
        now: Long,
        confidenceBps: Int?,
    ) = EngineProvenance(
        engineId = ENGINE_ID,
        engineVersion = ENGINE_VERSION,
        computedAtUtcMillis = now,
        evidence = evidence,
        inputWindow = window,
        confidenceBps = confidenceBps,
    )

    /** What [decide] settles on. */
    private data class Decision(
        val streamClass: StreamClass,
        val basis: StreamBasis,
        val evidence: List<RuleCitation>,
        val confidenceBps: Int,
    )

    /**
     * The §8.2 measurements of one stream, held exactly.
     * Why:    the class is decided on [score], never on the rounded [metrics] (see the class doc).
     * Input:  [cv], [cadence], [dayLock], [score] — exact fractions; [metrics] — the same, in bps.
     */
    private class Measured(
        val score: BigDecimal,
        val metrics: StreamMetrics,
    ) {
        /** CLS-STR-001's thresholds. Result: the class. Input: [activeMonths]; [rules]. */
        fun classify(
            activeMonths: Int,
            rules: StreamRules,
        ): StreamClass =
            when {
                score >= fraction(rules.fixedMinScoreBps) && activeMonths >= rules.fixedMinMonths -> StreamClass.FIXED
                score >= fraction(rules.semiFixedMinScoreBps) -> StreamClass.SEMI_FIXED
                else -> StreamClass.VARIABLE
            }

        companion object {
            /**
             * Measures a stream: cv of its monthly totals, cadence of its gaps, day-lock of its days.
             * Result: the measurements. Input: [monthly] totals; [occurrences]; [rules].
             */
            fun of(
                monthly: List<Long>,
                occurrences: List<StreamOccurrence>,
                rules: StreamRules,
            ): Measured {
                val cv = coefficientOfVariation(monthly)
                val cadence = cadence(occurrences.map { it.bookedOn }.sorted())
                val dayLock = dayLock(occurrences.map { it.bookedOn.dayOfMonth }, rules.dayLockWindowDays)
                val score =
                    fraction(rules.cvWeightBps).multiply(ONE - cv.min(ONE), MATH) +
                        fraction(rules.cadenceWeightBps).multiply(ONE - cadence.min(ONE), MATH) +
                        fraction(rules.dayLockWeightBps).multiply(dayLock, MATH)
                return Measured(
                    score = score,
                    metrics = StreamMetrics(monthly.size, bps(cv), bps(cadence), bps(dayLock), bps(score)),
                )
            }
        }
    }

    private companion object {
        const val ENGINE_ID = "AI-CLS.stream"
        const val ENGINE_VERSION = "1.0"
        const val FIELD_WINDOW = "stream.window"
        const val FIELD_KEY = "stream.key"
        const val FIELD_AMOUNT = "stream.amount"
    }
}

/** 34 significant digits, HALF_EVEN — `MathContext.DECIMAL128`, fixed so results never vary. */
private val MATH: MathContext = MathContext.DECIMAL128

private val ONE: BigDecimal = BigDecimal.ONE

/** Result: [bps] as an exact fraction of one. Input: basis points. Output: [BigDecimal]. */
private fun fraction(bps: Int): BigDecimal = BigDecimal.valueOf(bps.toLong(), BPS_SCALE)

/** Result: an exact fraction shown as whole basis points, HALF_EVEN. Input: [value]. Output: [Int]. */
private fun bps(value: BigDecimal): Int =
    value.movePointRight(BPS_SCALE).setScale(0, RoundingMode.HALF_EVEN).intValueExact()

/** Basis points are four decimal places of a fraction: 10 000 bps = 1 (MNY-002). */
private const val BPS_SCALE = 4

/**
 * cv = population stdev ÷ mean = √(n·Σx² − (Σx)²) ÷ Σx — one square root, no intermediate division.
 * Result: the exact cv (0 for identical months). Input: [totals] — at least two, positive paise.
 */
private fun coefficientOfVariation(totals: List<Long>): BigDecimal {
    val n = BigDecimal.valueOf(totals.size.toLong())
    val sum = totals.fold(BigDecimal.ZERO) { acc, x -> acc + BigDecimal.valueOf(x) }
    if (sum.signum() == 0) return ONE
    val squares = totals.fold(BigDecimal.ZERO) { acc, x -> acc + BigDecimal.valueOf(x).pow(2) }
    val spread = (n * squares - sum.pow(2)).max(BigDecimal.ZERO)
    return spread.sqrt(MATH).divide(sum, MATH)
}

/**
 * cadence = MAD of the gaps between occurrences ÷ their median gap.
 * Why:    fewer than two gaps, or a median gap of zero days, is "no evidence of a rhythm" and scores
 *         the worst cadence, 1 — never the best, which is what an empty MAD would otherwise give.
 * Result: the exact cadence. Input: [dates], sorted.
 */
private fun cadence(dates: List<LocalDate>): BigDecimal {
    val gaps = dates.zipWithNext { a, b -> BigDecimal.valueOf(b.toEpochDay() - a.toEpochDay()) }
    val medianGap = if (gaps.size < 2) BigDecimal.ZERO else medianOf(gaps)
    return if (medianGap.signum() == 0) {
        ONE
    } else {
        medianOf(gaps.map { (it - medianGap).abs() }).divide(medianGap, MATH)
    }
}

/**
 * dayLock = share of occurrences within ±[windowDays] of the modal day-of-month.
 * Why:    distance is circular over 31 days, so the 30th, the 31st and the 1st count as neighbours —
 *         rent paid "at month end" lands on either side of the boundary. Ties for the mode go to the
 *         earliest day, so the answer never depends on iteration order (P-08).
 * Result: the exact share. Input: [days]; [windowDays].
 */
private fun dayLock(
    days: List<Int>,
    windowDays: Int,
): BigDecimal {
    val counts = days.groupingBy { it }.eachCount()
    val modal = counts.entries.sortedWith(compareBy({ -it.value }, { it.key })).first().key
    val within =
        days.count { day ->
            val distance = kotlin.math.abs(day - modal)
            minOf(distance, DAYS_IN_CYCLE - distance) <= windowDays
        }
    return BigDecimal.valueOf(within.toLong()).divide(BigDecimal.valueOf(days.size.toLong()), MATH)
}

/** The day-of-month cycle the day-lock distance wraps over. */
private const val DAYS_IN_CYCLE = 31

/** Result: the exact median of [values] (the mean of the two middles for an even count). */
private fun medianOf(values: List<BigDecimal>): BigDecimal {
    val sorted = values.sorted()
    val mid = sorted.size / 2
    return if (sorted.size % 2 == 1) {
        sorted[mid]
    } else {
        (sorted[mid - 1] + sorted[mid]).divide(
            BigDecimal.valueOf(2),
            MATH,
        )
    }
}

/**
 * The typical month: the median monthly total in paise, HALF_EVEN on a half paisa (MNY-001).
 * Result: paise. Input: [totals] — at least one.
 */
private fun median(totals: List<Long>): Long =
    medianOf(totals.map(BigDecimal::valueOf)).setScale(0, RoundingMode.HALF_EVEN).longValueExact()
