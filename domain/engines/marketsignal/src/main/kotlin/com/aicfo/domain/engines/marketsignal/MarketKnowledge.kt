package com.aicfo.domain.engines.marketsignal

/**
 * The signal library, as typed values the engine can read (issue 10.7; §30, §6, ADR-0017).
 *
 * Why:  `ai/knowledge/market-signals.json` is the authoritative library, and §30's whole argument
 *       is that the weights are **data, editable on evidence** — a user who disagrees that price
 *       patterns deserve five points should be able to change five without a code review. An
 *       engine here is pure Kotlin and cannot read a file (ARC-002), so it reads this mirror, and
 *       `MarketKbDriftTest` fails the build the moment the two disagree.
 * What: the seven scored signals with their ladders, the verdict bands, the history minimums, the
 *       hit-rate policy, the staleness limits and the tranche ladder.
 * Result: changing a weight, a tier or a band is an edit to the JSON and this mirror together.
 * Changelog: 2026-09-27 — Created for issue 10.7 from market-signals.json v1.1.
 *
 * Input:  [version]; [signals] — in the file's order; [bands]; [history]; [hitRate]; [staleness];
 *         [tranches]. Output: an immutable value; [BUNDLED] is the one every caller should use.
 */
data class MarketKnowledge(
    val version: String,
    val signals: List<SignalSpec>,
    val bands: BandSpec,
    val history: HistorySpec,
    val hitRate: HitRateSpec,
    val staleness: StalenessSpec,
    val tranches: TrancheSpec,
) {
    /** Result: one signal's specification. Input: [id]. Output: [SignalSpec]. */
    fun signal(id: String): SignalSpec =
        signals.firstOrNull { it.id == id } ?: error("no KB row for $id — the mirror is incomplete")

    companion object {
        /** Every scored signal's id, in the library's order. */
        const val VALUATION = "SIG-VALUATION"

        /** Percent below the 52-week high. */
        const val DRAWDOWN = "SIG-DRAWDOWN"

        /** India VIX percentile. */
        const val VIX = "SIG-VIX"

        /** Percent below the 200-day moving average. */
        const val MA200 = "SIG-MA200"

        /** RSI(14). */
        const val RSI = "SIG-RSI"

        /** Where today's close sits in the trailing year. */
        const val RARITY = "SIG-RARITY"

        /** Consecutive down days. */
        const val STREAK = "SIG-STREAK"

        /**
         * The bundled library, mirroring `market-signals.json` v1.1.
         * Result: the values the app ships with. Input: none. Output: [MarketKnowledge].
         */
        val BUNDLED =
            MarketKnowledge(
                version = "1.1",
                signals =
                    listOf(
                        SignalSpec(VALUATION, 25, LadderKind.PERCENTILE_LOW, listOf(25 to 13, 10 to 25)),
                        SignalSpec(
                            DRAWDOWN,
                            20,
                            LadderKind.TIER_NEGATIVE_PCT,
                            listOf(-5 to 5, -8 to 10, -12 to 15, -20 to 20),
                        ),
                        SignalSpec(VIX, 15, LadderKind.PERCENTILE_HIGH, listOf(75 to 8, 90 to 15)),
                        SignalSpec(MA200, 15, LadderKind.TIER_NEGATIVE_PCT, listOf(-2 to 5, -5 to 10, -10 to 15)),
                        SignalSpec(RSI, 15, LadderKind.THRESHOLD_BELOW, listOf(30 to 10, 25 to 15)),
                        SignalSpec(RARITY, 5, LadderKind.PERCENTILE_LOW, listOf(25 to 2, 10 to 5)),
                        SignalSpec(STREAK, 5, LadderKind.THRESHOLD_AT_LEAST, listOf(4 to 3, 6 to 5)),
                    ),
                bands = BandSpec(),
                history = HistorySpec(),
                hitRate = HitRateSpec(),
                staleness = StalenessSpec(),
                tranches = TrancheSpec(),
            )
    }
}

/**
 * One scored signal.
 * Input:  [id] — the KB's own id, cited in evidence; [maxPoints]; [kind] — how the ladder is read;
 *         [ladder] — threshold to points, in the file's order (weakest step first).
 * Output: an immutable value.
 * Changelog: 2026-09-27 — Created for issue 10.7.
 */
data class SignalSpec(
    val id: String,
    val maxPoints: Int,
    val kind: LadderKind,
    val ladder: List<Pair<Int, Int>>,
) {
    init {
        require(ladder.isNotEmpty()) { "$id has no ladder, so it could never score" }
        require(ladder.all { it.second <= maxPoints }) { "$id awards more than its max_points" }
    }

    /**
     * Awards points for a measured value.
     * Why:    four shapes of comparison, all in one place, so a new signal is a row rather than a
     *         branch somewhere in the engine.
     * Result: the points, taking the **strongest** step the value reaches. Input: [measured] — a
     *         percentile, a signed percent, an index value or a count, per [kind].
     * Output: [Int].
     */
    fun pointsFor(measured: Int): Int =
        ladder.fold(0) { best, (threshold, points) ->
            val reached =
                when (kind) {
                    LadderKind.PERCENTILE_LOW -> measured <= threshold
                    LadderKind.PERCENTILE_HIGH -> measured >= threshold
                    LadderKind.TIER_NEGATIVE_PCT -> measured <= threshold
                    LadderKind.THRESHOLD_BELOW -> measured < threshold
                    LadderKind.THRESHOLD_AT_LEAST -> measured >= threshold
                }
            if (reached) maxOf(best, points) else best
        }
}

/**
 * How a signal's ladder is compared against what was measured.
 * Changelog: 2026-09-27 — Created for issue 10.7.
 */
enum class LadderKind {
    /** Cheaper is better: score at or below the threshold percentile. */
    PERCENTILE_LOW,

    /** More fear is better: score at or above the threshold percentile. */
    PERCENTILE_HIGH,

    /** A signed percent below a reference, so more negative is a stronger step. */
    TIER_NEGATIVE_PCT,

    /** Strictly below an index value, as RSI is read. */
    THRESHOLD_BELOW,

    /** At least this many, as a run of down days is read. */
    THRESHOLD_AT_LEAST,
}

/**
 * §30's verdict bands (`opportunity_score.verdict_bands`).
 * Input:  [strongBuyFrom]; [goodFrom]; [neutralFrom]; [cap] — the score is capped here.
 * Output: an immutable value.
 * Changelog: 2026-09-27 — Created for issue 10.7.
 */
data class BandSpec(
    val strongBuyFrom: Int = 70,
    val goodFrom: Int = 50,
    val neutralFrom: Int = 30,
    val cap: Int = 100,
) {
    /** Result: the band a score falls in. Input: [score]. Output: [OpportunityBand]. */
    fun bandFor(score: Int): OpportunityBand =
        when {
            score >= strongBuyFrom -> OpportunityBand.STRONG_BUY_DAY
            score >= goodFrom -> OpportunityBand.GOOD_DAY
            score >= neutralFrom -> OpportunityBand.NEUTRAL
            else -> OpportunityBand.NO_EDGE
        }
}

/**
 * The KB's `history` block (MKT-HISTORY v1.0): how much is enough.
 * Input:  [minimumDaysForAnyScore]; [minimumDaysForMa200]; [minimumDaysFor52wHigh].
 * Output: an immutable value.
 * Changelog: 2026-09-27 — Created for issue 10.7.
 */
data class HistorySpec(
    val minimumDaysForAnyScore: Int = 60,
    val minimumDaysForMa200: Int = 200,
    val minimumDaysFor52wHigh: Int = 250,
)

/**
 * The KB's `hit_rate` block (MKT-HITRATE v1.0): what "measured" means (§30.3).
 * Input:  [horizonDays]; [minSamples] — below this no rate is shown at all.
 * Output: an immutable value.
 * Changelog: 2026-09-27 — Created for issue 10.7.
 */
data class HitRateSpec(
    val horizonDays: Int = 90,
    val minSamples: Int = 20,
)

/**
 * The KB's `staleness` block (MKT-STALE v1.0).
 * Input:  [staleAfterDays] — label it; [refuseAfterDays] — do not answer at all.
 * Output: an immutable value.
 * Changelog: 2026-09-27 — Created for issue 10.7.
 */
data class StalenessSpec(
    val staleAfterDays: Int = 3,
    val refuseAfterDays: Int = 30,
)

/**
 * The KB's `tranche_policy` ladder (§30.4).
 * Input:  [goodDay]; [strongBuyDay] — tranches at each band; [gates] — the rule ids, in order.
 * Output: an immutable value.
 * Changelog: 2026-09-27 — Created for issue 10.7.
 */
data class TrancheSpec(
    val goodDay: Int = 1,
    val strongBuyDay: Int = 2,
    val gates: List<String> = listOf("RULE-IDLE-CASH", "RULE-RUNWAY-M", "AI-FCT"),
)
