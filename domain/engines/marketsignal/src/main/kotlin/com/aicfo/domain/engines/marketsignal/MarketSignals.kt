package com.aicfo.domain.engines.marketsignal

import com.aicfo.core.common.AppError
import com.aicfo.core.common.Result
import com.aicfo.core.model.EngineProvenance
import com.aicfo.core.model.Money

/**
 * AI-MKT — is today a better day than usual to deploy? (issue 10.7; SRS §30.)
 *
 * Why:  §30's promise is to remove the daily burden of watching markets, and its danger is obvious:
 *       a screen that says "strong buy day" is the one most likely to be read as an instruction.
 *       So every part of this engine is built to be checkable rather than persuasive — the score is
 *       a sum of named signals with their measured inputs beside them (P-02), the verdict is shown
 *       **only** with the hit rate that same verdict has had on this instrument's own history
 *       (§30.3), the suggestion is staged tranches gated on the household's capacity (§30.4), and
 *       nothing here moves a rupee (P-07).
 * What: one engine. It reads the daily closes the app has already cached, scores the signal
 *       library over them, walks the same scoring back through history to measure the band's hit
 *       rate, and reports what it could not evaluate rather than scoring it zero.
 * Result: an [OpportunityAssessment] that can be reproduced from the same cached closes (P-08).
 * Changelog: 2026-09-27 — Created for issue 10.7.
 *
 * Pure (ARC-002): no network, no clock, no I/O. `todayIsoDate` is an input, and the history is the
 * caller's — which is what makes the whole thing work offline (P-04).
 */
interface MarketSignalEngine {
    /**
     * Scores one instrument for one day.
     * Why:    one call, because every part of the answer depends on the same scoring: the band
     *         needs the score, the hit rate needs the band, and the tranche suggestion needs both.
     * Result: `Ok(OpportunityAssessment)` — which may say it could not score at all, because too
     *         little history and too stale a price are answers rather than errors.
     *         `Err(AppError.Validation)` names the field for data that cannot be read: an
     *         unparseable date, a negative close, an out-of-range percentile.
     * Input:  [input] — the instrument, its cached closes, the optional context series, and the
     *         household's capacity.
     * Output: `Result<OpportunityAssessment, AppError>`.
     */
    fun assess(input: MarketSignalInput): Result<OpportunityAssessment, AppError>
}

/**
 * Builds the engine (ARC-003 — the implementation stays `internal`).
 * Changelog: 2026-09-27 — Created for issue 10.7.
 */
object MarketSignalEngineFactory {
    /**
     * Result: §30's signal engine over the bundled library.
     * Input: [knowledge] — overridable in tests only. Output: [MarketSignalEngine].
     */
    fun create(knowledge: MarketKnowledge = MarketKnowledge.BUNDLED): MarketSignalEngine =
        CachedHistoryMarketSignalEngine(knowledge)
}

/**
 * What is being scored.
 * Input:  [key] — the price key the app caches this instrument under; [label] — the user's own
 *         words for it, for the screen.
 * Output: an immutable value.
 * Changelog: 2026-09-27 — Created for issue 10.7.
 */
data class Instrument(
    val key: String,
    val label: String,
)

/**
 * One day's close, as the app cached it.
 * Why:    closes rather than full OHLC, because closes are what this app has: each refresh stores
 *         one price per instrument per day. Every signal in the library is computable from them.
 * Input:  [isoDate] — `yyyy-MM-dd` (TIM-002); [close] — paise (MNY-001).
 * Output: an immutable value.
 * Changelog: 2026-09-27 — Created for issue 10.7.
 */
data class DailyClose(
    val isoDate: String,
    val close: Money,
)

/**
 * The two series the app cannot derive from an instrument's own closes.
 * Why:    valuation and implied volatility carry the library's two highest weights and neither can
 *         be computed from a price series. They arrive as percentiles from wherever the app got
 *         them, or they arrive as `null` — in which case the signals are **not evaluated**, the
 *         possible score shrinks by their weight, and the screen says so. A missing input scored as
 *         zero would quietly read as "valuation says no".
 * Input:  [valuationPercentile] — the index P/E against its own 10-year history, 0..100;
 *         [vixPercentile] — India VIX against 5 years, 0..100. Both `null` when unknown.
 * Output: an immutable value.
 * Changelog: 2026-09-27 — Created for issue 10.7.
 */
data class MarketContext(
    val valuationPercentile: Int? = null,
    val vixPercentile: Int? = null,
)

/**
 * Whether this household is in a position to deploy anything (§30.4).
 * Why:    each of these is another engine's published figure, and this engine re-derives none of
 *         them. A tranche suggestion that ignored the crunch day two weeks out would be advice to
 *         create the emergency it was meant to prevent.
 * Input:  [idleCash] — what AI-INV calls idle (RULE-IDLE-CASH); [runwayMeetsTarget] — AI-EMF's
 *         verdict (RULE-RUNWAY-M); [crunchDaysAhead] — AI-FCT's count inside the next 30 days.
 * Output: an immutable value.
 * Changelog: 2026-09-27 — Created for issue 10.7.
 */
data class Capacity(
    val idleCash: Money = Money.ZERO,
    val runwayMeetsTarget: Boolean = false,
    val crunchDaysAhead: Int = 0,
)

/**
 * Everything AI-MKT reads.
 * Input:  [instrument]; [closes] — in any order, deduplicated by date by the caller; [context];
 *         [capacity]; [todayIsoDate] — the profile's own today (TIM-001, injected, never read
 *         here); [nowUtcMillis]; [knowledge] — the KB mirror, overridable in tests only.
 * Output: an immutable value.
 * Changelog: 2026-09-27 — Created for issue 10.7.
 */
data class MarketSignalInput(
    val instrument: Instrument,
    val closes: List<DailyClose>,
    val context: MarketContext = MarketContext(),
    val capacity: Capacity = Capacity(),
    val todayIsoDate: String,
    val nowUtcMillis: Long,
    val knowledge: MarketKnowledge = MarketKnowledge.BUNDLED,
)

/**
 * What the engine concluded.
 * Input:  [instrument]; [outcome] — whether it scored at all; [score] — 0..100, capped;
 *         [possibleScore] — the sum of the max points of the signals it could evaluate, so a score
 *         of 40 out of a possible 60 is not read as 40 out of 100; [band] — non-null only when
 *         scored; [signals] — every signal, evaluated or not, with what it measured (P-02);
 *         [hitRate] — `null` when there are too few past days in this band to say (§30.3);
 *         [tranches]; [staleness]; [provenance].
 * Output: an immutable value.
 * Changelog: 2026-09-27 — Created for issue 10.7.
 */
data class OpportunityAssessment(
    val instrument: Instrument,
    val outcome: AssessmentOutcome,
    val score: Int,
    val possibleScore: Int,
    val band: OpportunityBand?,
    val signals: List<SignalContribution>,
    val hitRate: HitRate?,
    val tranches: TranchePlan,
    val staleness: Staleness,
    val provenance: EngineProvenance,
)

/**
 * Whether there was anything to say, and why not when there was not.
 * Changelog: 2026-09-27 — Created for issue 10.7.
 */
enum class AssessmentOutcome {
    /** There was enough recent history, and the signals were scored. */
    SCORED,

    /** Too few cached closes. A drawdown measured against three weeks is not a drawdown. */
    NOT_ENOUGH_HISTORY,

    /** The newest close is older than the knowledge base allows a verdict to be built on. */
    TOO_STALE,
}

/**
 * §30's verdict bands.
 * Changelog: 2026-09-27 — Created for issue 10.7 from market-signals.json v1.1.
 */
enum class OpportunityBand {
    /** 70 and above. */
    STRONG_BUY_DAY,

    /** 50 to 69. */
    GOOD_DAY,

    /** 30 to 49. */
    NEUTRAL,

    /** Below 30 — the ordinary state of the world. */
    NO_EDGE,
}

/**
 * One signal's contribution, with the number it measured.
 * Why:    P-02 on a screen people will want to argue with. "Drawdown: 20 points" is a verdict;
 *         "Drawdown −13.4% from the 52-week high: 15 of 20 points" is something a user can check.
 * Input:  [id] — the KB's own signal id; [points] — awarded; [maxPoints]; [evaluated] — false when
 *         the input was missing, in which case [points] is zero **and the possible score excludes
 *         it**; [measuredBps] — the quantity in basis points where the signal measures a
 *         proportion; [measuredCount] — where it measures a count or a percentile.
 * Output: an immutable value.
 * Changelog: 2026-09-27 — Created for issue 10.7.
 */
data class SignalContribution(
    val id: String,
    val points: Int,
    val maxPoints: Int,
    val evaluated: Boolean,
    val measuredBps: Int? = null,
    val measuredCount: Int? = null,
)

/**
 * How this band has actually done on this instrument's own history (§30.3).
 * Why:    the core differentiator, and the one number that turns a verdict into evidence. It is
 *         measured walk-forward: each past day is scored from the closes available on that day, so
 *         the rate is not a machine that can see the future.
 * Input:  [samples] — past days that landed in the same band and had a full forward horizon;
 *         [hits] — of those, how many were higher [horizonDays] trading days later; [ratePct].
 * Output: an immutable value.
 * Changelog: 2026-09-27 — Created for issue 10.7.
 */
data class HitRate(
    val samples: Int,
    val hits: Int,
    val ratePct: Int,
    val horizonDays: Int,
)

/**
 * What §30.4 suggests doing about it — and what stopped it.
 * Why:    tranches, never all-in, and never at all unless the household can afford to. Each gate
 *         is another engine's verdict, reported by name so a blocked suggestion explains itself.
 * Input:  [suggested] — how many tranches; zero when any gate fails; [gates].
 * Output: an immutable value.
 * Changelog: 2026-09-27 — Created for issue 10.7.
 */
data class TranchePlan(
    val suggested: Int,
    val gates: List<CapacityGate>,
) {
    /** Whether every gate passed. */
    val clear: Boolean get() = gates.all { it.passed }
}

/**
 * One capacity gate and its verdict.
 * Input:  [ruleId] — the rulebook row (RULE-IDLE-CASH, RULE-RUNWAY-M) or the engine (AI-FCT);
 *         [passed]. Output: an immutable value.
 * Changelog: 2026-09-27 — Created for issue 10.7.
 */
data class CapacityGate(
    val ruleId: String,
    val passed: Boolean,
)

/**
 * How old the newest close is (P-04).
 * Input:  [asOfIsoDate] — the newest close's day, or `null` when there are none; [daysOld];
 *         [isStale] — past the knowledge base's threshold, so the screen labels it.
 * Output: an immutable value.
 * Changelog: 2026-09-27 — Created for issue 10.7.
 */
data class Staleness(
    val asOfIsoDate: String?,
    val daysOld: Int,
    val isStale: Boolean,
)
