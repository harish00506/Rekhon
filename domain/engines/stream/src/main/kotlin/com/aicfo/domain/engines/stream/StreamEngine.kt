package com.aicfo.domain.engines.stream

import com.aicfo.core.common.AppError
import com.aicfo.core.common.Result
import com.aicfo.core.model.EngineProvenance
import com.aicfo.core.model.Money
import java.time.LocalDate

/**
 * AI-CLS Stage 2 — stream classification (issue 9.1; SRS §8.2, AI-ARC-003, AI-ARC-006, P-08).
 *
 * Why:  "every downstream engine (Safe-to-Spend, emergency fund, forecasting, purchase advisor)
 *       depends on knowing which outflows are obligations" (§8). Stage 1 (issue 4.2) files each
 *       rupee under a category and 4.3 decides what it became; neither says whether it will
 *       *recur*. A ₹25,000 rent and a ₹25,000 television are the same size and opposite signals for
 *       a forecast. This engine says which is which, per stream, with the numbers that decided it.
 * What: for each expense stream — a Stage-1 category — over the window, its months with activity,
 *       coefficient of variation, cadence and day-lock, the §8.2 score, and a class; then the
 *       month's fixed load, expected semi-fixed spend and budgetable variable spend.
 * Result: the structured signal forecasting (9.2) and the health score (9.4) consume.
 * Changelog: 2026-09-19 — Created for issue 9.1.
 *
 * One public interface; the implementation is internal (ARC-003). Pure: no clock (the caller
 * passes `nowUtcMillis`), no randomness, no I/O (P-08).
 */
interface StreamEngine {
    /**
     * Classifies every stream in [input].
     * Why:    one call per profile per window, so the totals and the per-stream verdicts cannot
     *         disagree about which streams were counted.
     * Result: `Ok(profile)`; `Err(Validation(field))` for an input that cannot be classified — an
     *         empty or inverted window, a negative amount, a stream key used twice.
     * Input:  [input]. Output: `Result<StreamProfile, AppError>`.
     */
    fun classify(input: StreamInput): Result<StreamProfile, AppError>
}

/**
 * Builds the [StreamEngine] (issue 9.1).
 * Why:    the implementation is internal (ARC-003); a factory keeps it out of callers' reach while
 *         letting `:app`'s DI graph and the tests construct one.
 * Result: the default engine. Input: none. Output: [StreamEngine].
 * Changelog: 2026-09-19 — Created for issue 9.1.
 */
object StreamEngineFactory {
    /** Input: none. Output: [StreamEngine]. */
    fun create(): StreamEngine = DefaultStreamEngine()
}

/**
 * What the engine classifies (issue 9.1).
 *
 * Why:  the repository decides what a stream is and which of them are known obligations; the engine
 *       only scores. Keeping the join out of here is what lets the whole formula be tested with
 *       literal dates and amounts.
 * Result: the input to [StreamEngine.classify].
 * Changelog: 2026-09-19 — Created for issue 9.1.
 *
 * Input:  [windowStart], [windowEnd] — inclusive ISO dates (TIM-002), normally the last six closed
 *         months; occurrences outside are ignored. [streams] — one per stream key.
 *         [nowUtcMillis] — stamped on the provenance, from the caller's injected clock (TIM-001).
 *         [rules] — the `CLS-STR-*` rows, defaulted to the shipped knowledge base.
 * Output: an immutable value.
 */
data class StreamInput(
    val windowStart: LocalDate,
    val windowEnd: LocalDate,
    val streams: List<StreamHistory>,
    val nowUtcMillis: Long,
    val rules: StreamRules = StreamRules(),
)

/**
 * One expense stream's history and what is already known about it (issue 9.1).
 *
 * Input:  [streamKey] — stable id, in practice the Stage-1 category id; [priorKey] — the
 *         `category_defaults` key the category was seeded from, or `null` for a category the user
 *         made (no prior); [occurrences] — every outflow in the window, as positive magnitudes;
 *         [isKnownObligation] — a confirmed recurring rule names this category (CLS-STR-002);
 *         [pin] — the class the user fixed for it, or `null` (CLS-STR-003).
 * Output: an immutable value.
 * Changelog: 2026-09-19 — Created for issue 9.1.
 */
data class StreamHistory(
    val streamKey: String,
    val priorKey: String?,
    val occurrences: List<StreamOccurrence>,
    val isKnownObligation: Boolean = false,
    val pin: StreamClass? = null,
)

/**
 * One outflow (issue 9.1).
 * Input:  [bookedOn] — the date it counts on (TIM-002); [amount] — its size as a **positive**
 *         `Money` (MNY-001); the repository takes the magnitude of the signed ledger amount.
 * Output: an immutable value.
 * Changelog: 2026-09-19 — Created for issue 9.1.
 */
data class StreamOccurrence(
    val bookedOn: LocalDate,
    val amount: Money,
)

/** The three §8.2 classes. Changelog: 2026-09-19 — Created for issue 9.1. */
enum class StreamClass {
    /** An obligation: rent, EMI, subscriptions, fees. */
    FIXED,

    /** Recurring but moving: electricity, fuel, groceries. */
    SEMI_FIXED,

    /** Discretionary and budgetable: dining, shopping, travel. */
    VARIABLE,
}

/**
 * Which step of `stream_classification.steps` decided a verdict (issue 9.1; P-02).
 * Why:    the class alone cannot say whether it was measured, assumed or chosen by the user, and a
 *         screen must say which. `isEstimate` follows from it.
 * Changelog: 2026-09-19 — Created for issue 9.1.
 */
enum class StreamBasis(val isEstimate: Boolean) {
    /** CLS-STR-003 — the user pinned it. */
    PINNED(false),

    /** CLS-STR-002 — a confirmed recurring rule names the category. */
    KNOWN_OBLIGATION(false),

    /** CLS-STR-004 — too little history; the category's `typical_stream` prior. */
    COLD_START_PRIOR(true),

    /** CLS-STR-004 — too little history and no prior at all; VARIABLE, lowest confidence. */
    COLD_START_NO_PRIOR(true),

    /** CLS-STR-001 — the §8.2 score. */
    SCORED(false),
}

/**
 * The §8.2 measurements for one stream (issue 9.1). All ratios are basis points (MNY-002).
 *
 * Input:  [activeMonths] — `n`, calendar months with any outflow; [cvBps] — coefficient of variation
 *         of the monthly totals, uncapped; [cadenceBps] — MAD of gaps ÷ median gap, uncapped;
 *         [dayLockBps] — share of occurrences within the day-lock window of the modal day;
 *         [scoreBps] — the weighted score, 0..10 000, rounded HALF_EVEN for display only (the class
 *         is decided on the exact value); [modalDayOfMonth] — the day the stream usually lands on,
 *         1..31, the centre of the day-lock window (ties to the earliest).
 * Output: an immutable value.
 * Changelog: 2026-09-19 — Created for issue 9.1.
 *   2026-09-19 — Issue 9.2 added [modalDayOfMonth], so the forecast projects a FIXED stream on the
 *   day it actually lands. Additive: no verdict changes, so the engine stays at 1.0.
 */
data class StreamMetrics(
    val activeMonths: Int,
    val cvBps: Int,
    val cadenceBps: Int,
    val dayLockBps: Int,
    val scoreBps: Int,
    val modalDayOfMonth: Int,
)

/**
 * One stream's verdict (issue 9.1; AI-ARC-003).
 *
 * Input:  [streamKey]; [streamClass]; [basis] — the step that decided it; [metrics] — the §8.2
 *         measurements, `null` only when fewer than two months had activity (nothing to measure);
 *         [typicalMonthly] — the median of the stream's monthly totals over its active months;
 *         [provenance] — engine, version, window, confidence, and the rows that fired.
 * Output: an immutable value.
 * Changelog: 2026-09-19 — Created for issue 9.1.
 */
data class StreamVerdict(
    val streamKey: String,
    val streamClass: StreamClass,
    val basis: StreamBasis,
    val metrics: StreamMetrics?,
    val typicalMonthly: Money,
    val provenance: EngineProvenance,
) {
    /** Whether the screen must say "estimate — improves with data" (§8.2 cold start). */
    val isEstimate: Boolean get() = basis.isEstimate
}

/**
 * A profile's streams and the §8.2 monthly outputs (issue 9.1).
 *
 * Input:  [streams] — every verdict, in stream-key order (P-08); [fixedLoad] — Σ typical monthly of
 *         FIXED streams; [semiFixedExpected] — Σ of SEMI_FIXED; [variableBudgetable] — Σ of
 *         VARIABLE; [provenance] — the profile-level record, citing every row any verdict used.
 * Output: an immutable value.
 * Changelog: 2026-09-19 — Created for issue 9.1.
 */
data class StreamProfile(
    val streams: List<StreamVerdict>,
    val fixedLoad: Money,
    val semiFixedExpected: Money,
    val variableBudgetable: Money,
    val provenance: EngineProvenance,
) {
    /** Whether any verdict in the total is an estimate, so the total must say so too. */
    val hasEstimates: Boolean get() = streams.any { it.isEstimate }
}
