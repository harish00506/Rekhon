package com.aicfo.domain.engines.marketsignal

import com.aicfo.core.common.AppError
import com.aicfo.core.common.Err
import com.aicfo.core.common.Ok
import com.aicfo.core.common.Result
import com.aicfo.core.model.EngineProvenance
import com.aicfo.core.model.Money
import com.aicfo.core.model.RuleCitation
import java.time.LocalDate
import java.time.format.DateTimeParseException
import java.time.temporal.ChronoUnit

/**
 * AI-MKT 1.0 — the opportunity score over cached closes (issue 10.7; §30).
 *
 * Why:  §30 asks for an objective, backtestable verdict rather than a feeling about the market. The
 *       design that makes it defensible is narrow: every signal is a named row with a ladder, every
 *       score is a sum of those rows, everything it could not evaluate is reported instead of
 *       scored, and the verdict is only ever shown beside the rate that verdict has actually had on
 *       this instrument's own history (§30.3).
 * What: scores the library for today, walks the same scoring back through the history to measure
 *       the band's hit rate, and applies §30.4's tranche ladder behind the capacity gates.
 * Result: an [OpportunityAssessment]. It suggests; it never acts (P-07).
 * Changelog: 2026-09-27 — Created for issue 10.7.
 *
 * `internal` per ARC-003; pure, with no clock, no network and no I/O (P-08, ARC-002, P-04).
 */
@Suppress("TooManyFunctions") // one per signal plus the three policies; each is named and tested
internal class CachedHistoryMarketSignalEngine(
    private val knowledge: MarketKnowledge,
) : MarketSignalEngine {
    override fun assess(input: MarketSignalInput): Result<OpportunityAssessment, AppError> {
        validate(input)?.let { return Err(it) }
        val series = parse(input.closes)
        val today = parseDate(input.todayIsoDate)
        return if (series == null || today == null) {
            // One refusal for both: a date the app cannot read is the same problem wherever it sits.
            Err(AppError.Validation(FIELD_DATE))
        } else {
            Ok(assessFrom(input, series.sortedBy { it.isoDate }, today))
        }
    }

    /**
     * The inputs no assessment can be made from.
     * Result: the first refusal by field, or `null`. Input: [input]. Output: `AppError.Validation?`.
     */
    private fun validate(input: MarketSignalInput): AppError.Validation? =
        when {
            input.closes.any { it.close < Money.ZERO } -> AppError.Validation(FIELD_CLOSE)
            listOfNotNull(input.context.valuationPercentile, input.context.vixPercentile)
                .any { it !in 0..PERCENT_FULL } -> AppError.Validation(FIELD_PERCENTILE)
            else -> null
        }

    /** Result: the closes, or `null` when a date cannot be read. Input: [closes]. */
    private fun parse(closes: List<DailyClose>): List<DailyClose>? =
        if (closes.any { parseDate(it.isoDate) == null }) null else closes

    /**
     * Result: the whole assessment. Input: [input]; [series] — ascending; [today].
     * Output: [OpportunityAssessment].
     *
     * The two gates come first and in this order: an old price is worse than a short history,
     * because a short history is honest about itself and an old one looks current.
     */
    private fun assessFrom(
        input: MarketSignalInput,
        series: List<DailyClose>,
        today: LocalDate,
    ): OpportunityAssessment {
        val staleness = stalenessOf(series, today)
        // A vehicle for the empty case: every price signal reads the last close, and there isn't
        // one. Scoring an empty series crashed the first draft — found by the repository test, on
        // the most ordinary situation there is: an instrument the user holds and the app has never
        // fetched a price for.
        val signals = if (series.isEmpty()) unscored() else score(series, input.context)
        val outcome =
            when {
                // No price at all is a history problem, not a staleness one. "Too stale" would
                // imply there is a price and it is old, which is a different thing to tell a user.
                series.isEmpty() -> AssessmentOutcome.NOT_ENOUGH_HISTORY
                staleness.daysOld > knowledge.staleness.refuseAfterDays -> AssessmentOutcome.TOO_STALE
                series.size < knowledge.history.minimumDaysForAnyScore -> AssessmentOutcome.NOT_ENOUGH_HISTORY
                else -> AssessmentOutcome.SCORED
            }
        val scored = outcome == AssessmentOutcome.SCORED
        val total = if (scored) minOf(signals.sumOf { it.points }, knowledge.bands.cap) else 0
        val band = if (scored) knowledge.bands.bandFor(total) else null
        return OpportunityAssessment(
            instrument = input.instrument,
            outcome = outcome,
            score = total,
            possibleScore = signals.filter { it.evaluated }.sumOf { it.maxPoints },
            band = band,
            signals = signals,
            hitRate = band?.let { hitRateFor(series, input.context, it) },
            tranches = tranchesFor(band, input.capacity),
            staleness = staleness,
            provenance = provenance(input, signals),
        )
    }

    /**
     * Result: every signal, evaluated by nothing — the honest shape when there is no history at all.
     * Input:  none. Output: `List<SignalContribution>`.
     */
    private fun unscored(): List<SignalContribution> =
        knowledge.signals.map { SignalContribution(it.id, 0, it.maxPoints, evaluated = false) }

    /**
     * Scores every signal in the library for the last day of [series].
     * Why:    one function over the library rather than seven call sites, so a signal added to the
     *         knowledge base is scored by the ladder it brought with it.
     * Result: one contribution per signal, in library order. Input: [series]; [context].
     * Output: `List<SignalContribution>`.
     */
    private fun score(
        series: List<DailyClose>,
        context: MarketContext,
    ): List<SignalContribution> =
        knowledge.signals.map { spec ->
            when (spec.id) {
                MarketKnowledge.VALUATION -> fromPercentile(spec, context.valuationPercentile)
                MarketKnowledge.VIX -> fromPercentile(spec, context.vixPercentile)
                MarketKnowledge.DRAWDOWN -> drawdown(spec, series)
                MarketKnowledge.MA200 -> movingAverage(spec, series)
                MarketKnowledge.RSI -> relativeStrength(spec, series)
                MarketKnowledge.RARITY -> rarity(spec, series)
                MarketKnowledge.STREAK -> streak(spec, series)
                else -> SignalContribution(spec.id, 0, spec.maxPoints, evaluated = false)
            }
        }

    /**
     * Result: a signal scored from a percentile the app was given, or **not evaluated** when it was
     *         not given one. Input: [spec]; [percentile]. Output: [SignalContribution].
     *
     * This is the case the whole design turns on: a missing input is not a zero.
     */
    private fun fromPercentile(
        spec: SignalSpec,
        percentile: Int?,
    ): SignalContribution =
        if (percentile == null) {
            SignalContribution(spec.id, 0, spec.maxPoints, evaluated = false)
        } else {
            SignalContribution(spec.id, spec.pointsFor(percentile), spec.maxPoints, true, measuredCount = percentile)
        }

    /** Result: how far below the 52-week high the last close sits. Input: [spec]; [series]. */
    private fun drawdown(
        spec: SignalSpec,
        series: List<DailyClose>,
    ): SignalContribution {
        val window = series.takeLast(knowledge.history.minimumDaysFor52wHigh)
        val high = MarketMath.highest(window)
        val latest = series.last().close
        val bps = high?.let { MarketMath.deviationBps(latest, it) }
        return if (bps == null || window.size < knowledge.history.minimumDaysForAnyScore) {
            SignalContribution(spec.id, 0, spec.maxPoints, evaluated = false)
        } else {
            SignalContribution(spec.id, spec.pointsFor(bps / BPS_PER_PERCENT), spec.maxPoints, true, measuredBps = bps)
        }
    }

    /** Result: how far below the 200-day average the last close sits. Input: [spec]; [series]. */
    private fun movingAverage(
        spec: SignalSpec,
        series: List<DailyClose>,
    ): SignalContribution {
        if (series.size < knowledge.history.minimumDaysForMa200) {
            return SignalContribution(spec.id, 0, spec.maxPoints, evaluated = false)
        }
        val average = MarketMath.average(series.takeLast(knowledge.history.minimumDaysForMa200))
        val bps = average?.let { MarketMath.deviationBps(series.last().close, it) }
        return if (bps == null) {
            SignalContribution(spec.id, 0, spec.maxPoints, evaluated = false)
        } else {
            SignalContribution(spec.id, spec.pointsFor(bps / BPS_PER_PERCENT), spec.maxPoints, true, measuredBps = bps)
        }
    }

    /** Result: RSI(14) scored against its ladder. Input: [spec]; [series]. */
    private fun relativeStrength(
        spec: SignalSpec,
        series: List<DailyClose>,
    ): SignalContribution {
        val rsi = MarketMath.rsi(series, RSI_PERIOD)
        return if (rsi == null) {
            SignalContribution(spec.id, 0, spec.maxPoints, evaluated = false)
        } else {
            SignalContribution(spec.id, spec.pointsFor(rsi), spec.maxPoints, true, measuredCount = rsi)
        }
    }

    /** Result: where the last close sits in the trailing year. Input: [spec]; [series]. */
    private fun rarity(
        spec: SignalSpec,
        series: List<DailyClose>,
    ): SignalContribution {
        val window = series.takeLast(knowledge.history.minimumDaysFor52wHigh)
        val percentile = MarketMath.percentile(series.last().close, window)
        return if (percentile == null || window.size < knowledge.history.minimumDaysForAnyScore) {
            SignalContribution(spec.id, 0, spec.maxPoints, evaluated = false)
        } else {
            SignalContribution(spec.id, spec.pointsFor(percentile), spec.maxPoints, true, measuredCount = percentile)
        }
    }

    /** Result: the run of down days to the last close. Input: [spec]; [series]. */
    private fun streak(
        spec: SignalSpec,
        series: List<DailyClose>,
    ): SignalContribution {
        val run = MarketMath.downStreak(series)
        return SignalContribution(spec.id, spec.pointsFor(run), spec.maxPoints, true, measuredCount = run)
    }

    /**
     * How this band has actually done on this instrument's own history (§30.3).
     * Why:    **walk-forward**: each past day is scored from the closes up to that day only, and
     *         judged by the close a horizon later. Scoring a past day with the whole series would
     *         measure a machine that can see the future, and would flatter every verdict.
     *         The context percentiles are held at today's values, because the app has no history
     *         for them — which is a limitation the ADR records rather than one the code hides.
     * Result: the rate, or `null` when there are fewer than the knowledge base's minimum samples.
     * Input:  [series]; [context]; [band] — today's. Output: `HitRate?`.
     */
    private fun hitRateFor(
        series: List<DailyClose>,
        context: MarketContext,
        band: OpportunityBand,
    ): HitRate? {
        val horizon = knowledge.hitRate.horizonDays
        val first = knowledge.history.minimumDaysForAnyScore
        if (series.size < first + horizon) return null
        var samples = 0
        var hits = 0
        for (day in first until series.size - horizon) {
            val upToDay = series.subList(0, day + 1)
            val past = minOf(score(upToDay, context).sumOf { it.points }, knowledge.bands.cap)
            if (knowledge.bands.bandFor(past) != band) continue
            samples += 1
            if (series[day + horizon].close.minor > series[day].close.minor) hits += 1
        }
        return if (samples < knowledge.hitRate.minSamples) {
            null
        } else {
            HitRate(samples, hits, hits * PERCENT_FULL / samples, horizon)
        }
    }

    /**
     * §30.4's ladder, behind the capacity gates.
     * Why:    the gates are other engines' verdicts and this engine re-derives none of them. Every
     *         gate is reported whether it passed or not, so a suggestion of nothing explains itself.
     * Result: the plan. Input: [band] — `null` when nothing was scored; [capacity].
     * Output: [TranchePlan].
     */
    private fun tranchesFor(
        band: OpportunityBand?,
        capacity: Capacity,
    ): TranchePlan {
        val gates =
            listOf(
                CapacityGate("RULE-IDLE-CASH", capacity.idleCash > Money.ZERO),
                CapacityGate("RULE-RUNWAY-M", capacity.runwayMeetsTarget),
                CapacityGate("AI-FCT", capacity.crunchDaysAhead == 0),
            )
        val ladder =
            when (band) {
                OpportunityBand.STRONG_BUY_DAY -> knowledge.tranches.strongBuyDay
                OpportunityBand.GOOD_DAY -> knowledge.tranches.goodDay
                else -> 0
            }
        return TranchePlan(suggested = if (gates.all { it.passed }) ladder else 0, gates = gates)
    }

    /** Result: how old the newest close is. Input: [series]; [today]. Output: [Staleness]. */
    private fun stalenessOf(
        series: List<DailyClose>,
        today: LocalDate,
    ): Staleness {
        val asOf = series.lastOrNull()?.isoDate
        val days = asOf?.let { ChronoUnit.DAYS.between(LocalDate.parse(it), today).toInt() } ?: Int.MAX_VALUE
        return Staleness(asOf, days, days > knowledge.staleness.staleAfterDays)
    }

    /** Result: who scored it, and from what (AI-ARC-003). Input: [input]; [signals]. */
    private fun provenance(
        input: MarketSignalInput,
        signals: List<SignalContribution>,
    ): EngineProvenance =
        EngineProvenance(
            engineId = ENGINE_ID,
            engineVersion = ENGINE_VERSION,
            computedAtUtcMillis = input.nowUtcMillis,
            evidence =
                listOf(RuleCitation(LIBRARY_CITATION, knowledge.version)) +
                    signals.filter { it.evaluated && it.points > 0 }.map { RuleCitation(it.id, knowledge.version) },
            inputWindow = "${input.closes.size}d",
            confidenceBps = signals.filter { it.evaluated }.sumOf { it.maxPoints } * BPS_PER_PERCENT,
        )

    /** Result: an ISO date, or `null` when it is not one. Input: [isoDate]. Output: `LocalDate?`. */
    private fun parseDate(isoDate: String): LocalDate? =
        try {
            LocalDate.parse(isoDate)
        } catch (_: DateTimeParseException) {
            null
        }

    private companion object {
        const val ENGINE_ID = "AI-MKT"
        const val ENGINE_VERSION = "1.0"
        const val LIBRARY_CITATION = "MKT-SIGNALS"
        const val FIELD_CLOSE = "market.close"
        const val FIELD_DATE = "market.date"
        const val FIELD_PERCENTILE = "market.percentile"
        const val PERCENT_FULL = 100
        const val BPS_PER_PERCENT = 100
        const val RSI_PERIOD = 14
    }
}
