package com.aicfo.domain.engines.healthscore

import com.aicfo.core.common.AppError
import com.aicfo.core.common.Result
import com.aicfo.core.model.EngineProvenance
import com.aicfo.core.model.Money

/**
 * AI-FHS — the Financial Health Score (issue 9.4; SRS §14, P-02, P-03, AI-ARC-003).
 *
 * Why:  every other screen answers one question about money; the score answers "how am I doing,
 *       overall?" — and §14 only allows it if the answer can be opened all the way down: five
 *       weighted pillars, each built from named signals, each signal a straight line between two
 *       rulebook anchors. A pillar with no data is shown as "—" and its weight shared out, never
 *       guessed. The number comes from arithmetic on those signals (P-03), never from a model.
 * What: the L3 scoring engine. It does not gather data: the repository hands it six signals, each
 *       optional, already measured by the engines and ledger that own them.
 * Result: a [HealthScore] — the total, the band, all five pillars with their contributions (which
 *         sum to the total exactly), and the one signal with the most points still to gain.
 * Changelog: 2026-09-19 — Created for issue 9.4.
 *
 * Input:  [HealthInput]. Output: `Result<HealthScore, AppError>`; `Err` only for an impossible input
 *         (a negative amount, more budgets kept than budgets set).
 */
interface HealthScoreEngine {
    /** Scores the input. See the interface's doc. */
    fun score(input: HealthInput): Result<HealthScore, AppError>
}

/**
 * Builds the one [HealthScoreEngine] (ARC-003 — the implementation stays `internal`).
 * Changelog: 2026-09-19 — Created for issue 9.4.
 */
object HealthScoreEngineFactory {
    /** Result: the §14 weighted-pillar engine. Input: none. Output: [HealthScoreEngine]. */
    fun create(): HealthScoreEngine = WeightedHealthScoreEngine()
}

/**
 * §14.1's five pillars, in the SRS's order (which is the order they are shown).
 * Changelog: 2026-09-19 — Created for issue 9.4.
 */
enum class Pillar {
    LIQUIDITY,
    DEBT,
    DISCIPLINE,
    GOALS,
    PROTECTION,
}

/**
 * The signals v1.0 scores, each belonging to one pillar (issue 9.4; ADR-0045 lists the ones §14
 * names that are not yet scored, and why).
 * Changelog: 2026-09-19 — Created for issue 9.4.
 */
enum class Signal(val pillar: Pillar) {
    /** Months of essentials the liquid funds buy, against the personal target M (§10). */
    RUNWAY(Pillar.LIQUIDITY),

    /** Fixed obligations and EMIs over income. */
    OBLIGATIONS(Pillar.DEBT),

    /** Credit-card balance over credit limit, across every card. */
    CARD_UTILISATION(Pillar.DEBT),

    /** What was kept of what came in, over the lookback's closed months. */
    SAVINGS_RATE(Pillar.DISCIPLINE),

    /** The share of this month's budgets not overspent. */
    BUDGET_ADHERENCE(Pillar.DISCIPLINE),

    /** The share of goals with a target that are on track or funded. */
    GOALS_ON_TRACK(Pillar.GOALS),
}

/**
 * What AI-FHS reads (issue 9.4). Every signal is optional: `null` is "no data", which is not the
 * same as a bad score.
 *
 * Input:  [runway]; [obligations]; [cards]; [savings]; [budgets]; [goals]; [window] — the data
 *         window, echoed into provenance; [nowUtcMillis] — stamped on provenance only; [rules].
 * Output: an immutable value.
 * Changelog: 2026-09-19 — Created for issue 9.4.
 */
data class HealthInput(
    val runway: RunwayInput? = null,
    val obligations: ObligationInput? = null,
    val cards: UtilisationInput? = null,
    val savings: SavingsInput? = null,
    val budgets: ShareInput? = null,
    val goals: ShareInput? = null,
    val window: String? = null,
    val nowUtcMillis: Long,
    val rules: HealthRules = HealthRules(),
)

/**
 * The emergency fund's runway (AI-EMF).
 * Input:  [runwayMonthsBps] — months of essentials, in bps of a month (15 000 = 1.5); [targetMonths]
 *         — the personal target M, at least 1. Output: an immutable value.
 */
data class RunwayInput(
    val runwayMonthsBps: Int,
    val targetMonths: Int,
)

/**
 * Monthly obligations against monthly income.
 * Input:  [obligations] — paise a month, not negative; [income] — the typical month's income,
 *         paise; [monthsOfIncome] — closed months the income was read from. Output: an immutable value.
 */
data class ObligationInput(
    val obligations: Money,
    val income: Money,
    val monthsOfIncome: Int,
)

/**
 * Credit used against credit available, summed across cards.
 * Input:  [used] — paise; a credit balance (negative) counts as nothing used; [limit] — paise.
 * Output: an immutable value.
 */
data class UtilisationInput(
    val used: Money,
    val limit: Money,
)

/**
 * One closed month's income and what was kept of it.
 * Input:  [monthKey] — ISO `yyyy-MM`; [income] — paise, not negative; [saved] — income less spent,
 *         paise, negative when more went out than came in. Output: an immutable value.
 */
data class MonthFlow(
    val monthKey: String,
    val income: Money,
    val saved: Money,
)

/**
 * The lookback's months, for the savings rate.
 * Input:  [months] — closed months, any order. Output: an immutable value.
 */
data class SavingsInput(
    val months: List<MonthFlow>,
)

/**
 * A share: how many of a set are in good standing.
 * Input:  [good] — 0..[total]; [total] — not negative. Output: an immutable value.
 */
data class ShareInput(
    val good: Int,
    val total: Int,
)

/**
 * §14.1's bands.
 * Changelog: 2026-09-19 — Created for issue 9.4.
 */
enum class HealthBand {
    EXCELLENT,
    GOOD,
    FAIR,
    NEEDS_ATTENTION,
    AT_RISK,
}

/**
 * One signal, scored.
 * Input:  [points] — 0..10 000 (hundredths of the 0–100 score); [measureBps] — what was measured, in
 *         bps (a ratio, or months × 10 000 for the runway); [targetBps] — where the signal scores
 *         full marks, in the same unit. Output: an immutable value.
 */
data class SignalScore(
    val signal: Signal,
    val points: Int,
    val measureBps: Int,
    val targetBps: Int,
)

/**
 * One pillar, scored.
 * Input:  [weightBps] — the rulebook weight; [effectiveWeightBps] — its share once pillars with no
 *         data are set aside (0 when this one has none; the five sum to 10 000 whenever any has data);
 *         [points] — 0..10 000, the mean of its signals, or `null` for "—"; [contribution] — points
 *         of the total it adds (the five sum to the total exactly); [signals] — those it has.
 * Output: an immutable value.
 */
data class PillarScore(
    val pillar: Pillar,
    val weightBps: Int,
    val effectiveWeightBps: Int,
    val points: Int?,
    val contribution: Int,
    val signals: List<SignalScore>,
)

/**
 * The single highest-leverage signal (§14 "every drop pairs with the single highest-leverage action").
 * Input:  [signal]; [gain] — points of the total it would add at full marks. Output: an immutable value.
 */
data class Lever(
    val signal: Signal,
    val gain: Int,
)

/**
 * The score (issue 9.4).
 * Input:  [score] — 0..`scoreMax`, `null` when no pillar has data; [band] — `null` with it;
 *         [pillars] — all five, in order; [lever] — `null` when nothing has points to gain;
 *         [provenance] — confidence is the share of the rulebook weight that has data.
 * Output: an immutable value.
 */
data class HealthScore(
    val score: Int?,
    val band: HealthBand?,
    val pillars: List<PillarScore>,
    val lever: Lever?,
    val provenance: EngineProvenance,
)
