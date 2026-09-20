package com.aicfo.domain.engines.healthscore

import com.aicfo.core.common.AppError
import com.aicfo.core.common.Err
import com.aicfo.core.common.Ok
import com.aicfo.core.common.Result
import com.aicfo.core.model.EngineProvenance
import java.math.BigDecimal
import java.math.BigInteger
import java.math.RoundingMode

/**
 * §14.1's five weighted pillars, as written (issue 9.4; AI-FHS, P-02, P-03, P-08, ADR-0045).
 *
 * Why:  the score is a chain of roundings — signal, pillar, total — so each link is rounded once,
 *       half-even, from an exact fraction, and the parts the screen shows are apportioned so they
 *       add up to the whole exactly (the largest remainders take the spare points, ties to the
 *       earlier pillar). A pillar with no signal is "—" and its weight is shared out, never scored
 *       as zero (§14 "insufficient-data handling").
 * What: validate → score each signal on its curve ([SignalCurves]) → mean per pillar → the weighted
 *       total over the pillars with data → band, contributions, effective weights, lever, provenance.
 * Result: a [HealthScore].
 * Changelog: 2026-09-19 — Created for issue 9.4.
 *
 * Integers and exact `BigInteger` fractions only (MNY-002); no clock, no randomness (P-08).
 * `internal` per ARC-003.
 */
internal class WeightedHealthScoreEngine : HealthScoreEngine {
    override fun score(input: HealthInput): Result<HealthScore, AppError> {
        validate(input)?.let { return Err(it) }
        val signals = SignalCurves(input.rules).scoreAll(input)
        val points =
            signals.groupBy { it.signal.pillar }.mapValues { (_, scored) ->
                roundHalfEven(big(scored.sumOf { it.points.toLong() }), big(scored.size.toLong())).toInt()
            }
        return Ok(assemble(input, signals, points))
    }

    /**
     * The score, its band, the five pillars and the lever, once every signal has its points.
     * Why:    the weights of the pillars **with data** are the denominator (§14's re-weighting), and
     *         the parts are apportioned from the same exact fractions the total is rounded from, so
     *         what the screen adds up matches what it is a share of.
     * Result: the score. Input: [input]; [signals]; [points] — per pillar. Output: [HealthScore].
     */
    private fun assemble(
        input: HealthInput,
        signals: List<SignalScore>,
        points: Map<Pillar, Int>,
    ): HealthScore {
        val rules = input.rules
        val available = Pillar.entries.filter { it in points }
        val weightTotal = available.sumOf { rules.weightOf(it).toLong() }
        val numerators =
            Pillar.entries.map { pillar ->
                points[pillar]?.let { big(rules.weightOf(pillar).toLong() * it * rules.scoreMax) } ?: BigInteger.ZERO
            }
        val total =
            if (available.isEmpty()) {
                null
            } else {
                roundHalfEven(numerators.fold(BigInteger.ZERO, BigInteger::add), big(weightTotal * BPS))
            }
        val contributions =
            if (total == null) emptyZeros() else apportion(numerators, big(weightTotal * BPS), total)
        return HealthScore(
            score = total?.toInt(),
            band = total?.let { bandOf(it.toInt(), rules) },
            pillars = pillarScores(rules, signals, points, contributions, effectiveWeights(rules, points, weightTotal)),
            lever = lever(signals, rules, weightTotal),
            provenance = provenance(input, signals, available),
        )
    }

    /**
     * §14's re-weighting, as the shares the screen shows: each pillar with data takes its weight
     * over the weights of the pillars with data, apportioned so the five sum to 10 000 exactly.
     * Result: bps per pillar, 0 for one with no data. Input: [rules]; [points]; [weightTotal].
     */
    private fun effectiveWeights(
        rules: HealthRules,
        points: Map<Pillar, Int>,
        weightTotal: Long,
    ): List<Long> {
        if (weightTotal == 0L) return emptyZeros()
        val weights =
            Pillar.entries.map { if (it in points) big(rules.weightOf(it).toLong() * BPS) else BigInteger.ZERO }
        return apportion(weights, big(weightTotal), BPS.toLong())
    }

    /** Result: the five pillars, in SRS order, each with its signals. */
    private fun pillarScores(
        rules: HealthRules,
        signals: List<SignalScore>,
        points: Map<Pillar, Int>,
        contributions: List<Long>,
        effective: List<Long>,
    ): List<PillarScore> =
        Pillar.entries.mapIndexed { k, pillar ->
            PillarScore(
                pillar = pillar,
                weightBps = rules.weightOf(pillar),
                effectiveWeightBps = effective[k].toInt(),
                points = points[pillar],
                contribution = contributions[k].toInt(),
                signals = signals.filter { it.signal.pillar == pillar },
            )
        }

    /** Result: a zero per pillar, for a score that has none. */
    private fun emptyZeros(): List<Long> = List(Pillar.entries.size) { 0L }

    /**
     * The inputs no score can be built from.
     * Result: the first refusal by field, or `null`. Input: [input]. Output: `AppError.Validation?`.
     */
    private fun validate(input: HealthInput): AppError.Validation? {
        val refusals =
            listOf(
                FIELD_RUNWAY to input.runway?.let { it.runwayMonthsBps < 0 || it.targetMonths < 1 },
                FIELD_OBLIGATIONS to
                    input.obligations?.let { it.obligations.minor < 0 || it.income.minor < 0 || it.monthsOfIncome < 0 },
                FIELD_CARDS to input.cards?.let { it.limit.minor < 0 },
                FIELD_SAVINGS to input.savings?.let { savings -> savings.months.any { it.income.minor < 0 } },
                FIELD_BUDGETS to input.budgets?.let(::impossible),
                FIELD_GOALS to input.goals?.let(::impossible),
            )
        return refusals.firstOrNull { it.second == true }?.let { AppError.Validation(it.first) }
    }

    /** Result: `true` for a share that cannot exist. Input: [share]. Output: [Boolean]. */
    private fun impossible(share: ShareInput): Boolean = share.total < 0 || share.good < 0 || share.good > share.total

    /**
     * The single highest-leverage signal: the most points of the total it would add at full marks.
     * Why:    §14 pairs every drop with "the single highest-leverage action"; the lever is that
     *         action's target, and it is arithmetic on what is already shown — the pillar's
     *         effective weight, shared between its signals, times the points the signal is missing.
     * Result: the lever, `null` when every scored signal is at full marks (or none is scored).
     * Input:  [signals]; [rules]; [weightTotal] — the weights of the pillars with data.
     * Output: [Lever]?.
     */
    private fun lever(
        signals: List<SignalScore>,
        rules: HealthRules,
        weightTotal: Long,
    ): Lever? {
        var best: Pair<SignalScore, Pair<BigInteger, BigInteger>>? = null
        signals.forEach { scored ->
            val siblings = signals.count { it.signal.pillar == scored.signal.pillar }.toLong()
            val numerator = big(rules.weightOf(scored.signal.pillar).toLong() * (BPS - scored.points) * rules.scoreMax)
            val denominator = big(weightTotal * siblings * BPS)
            val current = best
            val gain = numerator to denominator
            if (current == null || greater(gain, current.second)) best = scored to gain
        }
        val (signal, gain) = best ?: return null
        return if (gain.first.signum() == 0) {
            null
        } else {
            Lever(
                signal.signal,
                roundHalfEven(gain.first, gain.second).toInt(),
            )
        }
    }

    /**
     * Provenance (AI-ARC-003): the three FHS rows, plus the rows a scored signal borrowed its top
     * from; confidence is the share of the rulebook weight that rests on data.
     * Result: the provenance. Input: [input]; [signals]; [available]. Output: [EngineProvenance].
     */
    private fun provenance(
        input: HealthInput,
        signals: List<SignalScore>,
        available: List<Pillar>,
    ): EngineProvenance {
        val scored = signals.map { it.signal }.toSet()
        val borrowed =
            listOfNotNull(
                HealthRules.CARD_UTILISATION.takeIf { Signal.CARD_UTILISATION in scored },
                HealthRules.SAVINGS_RATE.takeIf { Signal.SAVINGS_RATE in scored },
            )
        return EngineProvenance(
            engineId = ENGINE_ID,
            engineVersion = ENGINE_VERSION,
            computedAtUtcMillis = input.nowUtcMillis,
            evidence = listOf(HealthRules.PILLARS, HealthRules.BANDS, HealthRules.SIGNALS) + borrowed,
            inputWindow = input.window,
            confidenceBps = available.sumOf { input.rules.weightOf(it) },
        )
    }

    /** Result: the §14.1 band of [score]. Input: [score]; [rules]. Output: [HealthBand]. */
    private fun bandOf(
        score: Int,
        rules: HealthRules,
    ): HealthBand =
        when {
            score >= rules.excellentMin -> HealthBand.EXCELLENT
            score >= rules.goodMin -> HealthBand.GOOD
            score >= rules.fairMin -> HealthBand.FAIR
            score >= rules.attentionMin -> HealthBand.NEEDS_ATTENTION
            else -> HealthBand.AT_RISK
        }

    private companion object {
        const val ENGINE_ID = "AI-FHS"
        const val ENGINE_VERSION = "1.0"
        const val FIELD_RUNWAY = "health.runway"
        const val FIELD_OBLIGATIONS = "health.obligations"
        const val FIELD_CARDS = "health.cards"
        const val FIELD_SAVINGS = "health.savings"
        const val FIELD_BUDGETS = "health.budgets"
        const val FIELD_GOALS = "health.goals"
    }
}

/**
 * §14.1's scoring bases, each a straight line between two rulebook anchors (issue 9.4).
 *
 * Why:  straight lines are the curves a user can check by hand and invert for "what would it
 *       take". Each signal is scored from the **rounded** measure the screen shows, so the points can
 *       be reproduced from what the user reads.
 * Input:  [rules]. Output: [SignalScore]s.
 * Changelog: 2026-09-19 — Created for issue 9.4.
 */
internal class SignalCurves(
    private val rules: HealthRules,
) {
    /** Result: every signal the input can score, in [Signal] order. Input: [input]. */
    fun scoreAll(input: HealthInput): List<SignalScore> =
        listOfNotNull(
            input.runway?.let(::runway),
            input.obligations?.let(::obligations),
            input.cards?.let(::cards),
            input.savings?.let(::savings),
            input.budgets?.let { share(Signal.BUDGET_ADHERENCE, it) },
            input.goals?.let { share(Signal.GOALS_ON_TRACK, it) },
        )

    /** Runway: linear to the target, capped; at a month or more, at least the floor. */
    private fun runway(input: RunwayInput): SignalScore {
        val linear =
            minOf(BPS.toLong(), roundHalfEven(big(input.runwayMonthsBps.toLong()), big(input.targetMonths.toLong())))
        val floored =
            if (input.runwayMonthsBps >= BPS) {
                maxOf(
                    linear,
                    rules.runwayFloorPoints.toLong() * PERCENT_BPS,
                )
            } else {
                linear
            }
        return SignalScore(Signal.RUNWAY, floored.toInt(), input.runwayMonthsBps, input.targetMonths * BPS)
    }

    /** Obligations over income: full to the first anchor, nothing from the second. `null` without income. */
    private fun obligations(input: ObligationInput): SignalScore? {
        if (input.income.minor <= 0L || input.monthsOfIncome < rules.minMonthsOfSignal) return null
        val ratio = ratioBps(input.obligations.minor, input.income.minor)
        val points = falling(ratio, rules.obligationFullPct * PERCENT_BPS, rules.obligationZeroPct * PERCENT_BPS)
        return SignalScore(Signal.OBLIGATIONS, points, ratio, rules.obligationFullPct * PERCENT_BPS)
    }

    /** Card utilisation, with a credit balance counted as nothing used. `null` without a limit. */
    private fun cards(input: UtilisationInput): SignalScore? {
        if (input.limit.minor <= 0L) return null
        val ratio = ratioBps(maxOf(0L, input.used.minor), input.limit.minor)
        val points = falling(ratio, rules.utilisationFullPct * PERCENT_BPS, rules.utilisationZeroPct * PERCENT_BPS)
        return SignalScore(Signal.CARD_UTILISATION, points, ratio, rules.utilisationFullPct * PERCENT_BPS)
    }

    /**
     * Kept over income across every month given — a month with no income still counts its spending,
     * because "spent with nothing coming in" is exactly what the rate must see. `null` when fewer than
     * the minimum months had income.
     */
    private fun savings(input: SavingsInput): SignalScore? {
        if (input.months.count { it.income.minor > 0L } < rules.minMonthsOfSignal) return null
        val rate = ratioBps(input.months.sumOf { it.saved.minor }, input.months.sumOf { it.income.minor })
        val top = rules.savingsFullPct * PERCENT_BPS
        val points =
            when {
                rate <= 0 -> 0
                rate >= top -> BPS
                else -> roundHalfEven(big(rate.toLong() * BPS), big(top.toLong())).toInt()
            }
        return SignalScore(Signal.SAVINGS_RATE, points, rate, top)
    }

    /** A share in good standing, scored as itself. `null` for an empty set. */
    private fun share(
        signal: Signal,
        input: ShareInput,
    ): SignalScore? {
        if (input.total <= 0) return null
        val share = ratioBps(input.good.toLong(), input.total.toLong())
        return SignalScore(signal, share, share, BPS)
    }

    /** Result: 10 000 at or below [full], 0 at or above [zero], linear between. */
    private fun falling(
        ratio: Int,
        full: Int,
        zero: Int,
    ): Int =
        when {
            ratio <= full -> BPS
            ratio >= zero -> 0
            else -> roundHalfEven(big((zero - ratio).toLong() * BPS), big((zero - full).toLong())).toInt()
        }

    /**
     * `numerator / denominator` in bps, half-even, saturating at the `Int` range: a ratio of
     * hundreds of thousands of times is already scored as its curve's worst, and the saturation only
     * bounds the figure shown. Input: [denominator] > 0. Output: [Int].
     */
    private fun ratioBps(
        numerator: Long,
        denominator: Long,
    ): Int =
        roundHalfEven(big(numerator).multiply(big(BPS.toLong())), big(denominator))
            .coerceIn(Int.MIN_VALUE.toLong(), Int.MAX_VALUE.toLong())
            .toInt()
}

/**
 * Splits [total] across [numerators]/[denominator] so the parts sum to it exactly: each part's
 * floor, then one more to the largest remainders, ties to the earlier index.
 * Result: the parts. Input: non-negative numerators; [denominator] > 0; [total]. Output: `List<Long>`.
 */
internal fun apportion(
    numerators: List<BigInteger>,
    denominator: BigInteger,
    total: Long,
): List<Long> {
    val floors = numerators.map { it.divide(denominator).toLong() }.toMutableList()
    val spare = (total - floors.sum()).toInt()
    numerators.indices
        .sortedWith(compareByDescending<Int> { numerators[it].mod(denominator) }.thenBy { it })
        .take(spare)
        .forEach { floors[it] = floors[it] + 1 }
    return floors
}

/** Result: `numerator / denominator` rounded half-even, exactly. Input: [denominator] > 0. */
internal fun roundHalfEven(
    numerator: BigInteger,
    denominator: BigInteger,
): Long = BigDecimal(numerator).divide(BigDecimal(denominator), 0, RoundingMode.HALF_EVEN).longValueExact()

/** Result: whether the fraction [a] is strictly greater than [b]; denominators positive. */
private fun greater(
    a: Pair<BigInteger, BigInteger>,
    b: Pair<BigInteger, BigInteger>,
): Boolean = a.first.multiply(b.second) > b.first.multiply(a.second)

private fun big(value: Long): BigInteger = BigInteger.valueOf(value)

/** 10 000 points = full marks; also 10 000 bps = ×1 (MNY-002). */
private const val BPS = 10_000

/** 100 bps = 1%. */
private const val PERCENT_BPS = 100
