package com.aicfo.domain.engines.insight

import com.aicfo.core.common.AppError
import com.aicfo.core.common.Result
import com.aicfo.core.model.EngineProvenance
import com.aicfo.core.model.Money
import com.aicfo.core.model.RuleCitation
import java.time.LocalDate

/**
 * AI-ORCH — the Insight Orchestrator's L5 assembly step (issue 9.5; SRS §7.2, AI-ARC-001/003/005).
 *
 * Why:  the app computes a great deal and says almost none of it unprompted. §7.2's pipeline ends
 *       with "persist Insights → notify": something has to decide **which** of the engines' results
 *       is worth a card, in what order, and how a card stays the same card when the numbers behind
 *       it are recomputed. That is this engine. It computes no figure of its own (P-03): every
 *       insight repeats a number another engine already published, and carries that engine's id and
 *       version with it (AI-ARC-006).
 * What: the ranking and deduplication step of the pipeline. The stages below it — L2 snapshot, L3
 *       rules, L4 predictions — are the repositories that already own them; the orchestrator
 *       repository runs them in order (AI-ARC-001) and hands their results here as signals.
 * Result: an [InsightFeed], ordered by RULE-INS-RANK, each insight with the fingerprint
 *         RULE-INS-DEDUP identifies it by.
 * Changelog: 2026-09-20 — Created for issue 9.5.
 *
 * **No dependency on another engine module, deliberately.** The signals below are small types
 * declared here and filled by the repository. An L5 engine that imported AI-FCT and AI-FHS could
 * start re-deriving what they own; this one can only repeat what it is given.
 *
 * Input:  [InsightInput]. Output: `Result<InsightFeed, AppError>`; `Err` only for an impossible
 *         input (a negative count, a snooze window below zero).
 */
interface InsightEngine {
    /** Assembles the feed. See the interface's doc. */
    fun insights(input: InsightInput): Result<InsightFeed, AppError>
}

/**
 * Builds the one [InsightEngine] (ARC-003 — the implementation stays `internal`).
 * Changelog: 2026-09-20 — Created for issue 9.5.
 */
object InsightEngineFactory {
    /** Result: the §7.2 assembly engine. Input: none. Output: [InsightEngine]. */
    fun create(): InsightEngine = RankedInsightEngine()
}

/**
 * How much attention an insight asks for (RULE-INS-RANK's `severity_order`).
 * Declaration order **is** the order: worst first, as the rule states it.
 * Changelog: 2026-09-20 — Created for issue 9.5.
 */
enum class Severity {
    /** Money will run out, or has. Shown first, always. */
    CRITICAL,

    /** A commitment is off track and can still be corrected. */
    WARNING,

    /** Worth knowing, nothing is wrong. */
    INFO,
}

/**
 * The insights v1.0 can raise, each from one engine's published result (ADR-0046 lists what is not
 * raised yet, and why).
 * Changelog: 2026-09-20 — Created for issue 9.5.
 */
enum class InsightType(val severity: Severity) {
    /** AI-FCT found a day whose expected balance falls below the buffer. */
    CRUNCH_DAY(Severity.CRITICAL),

    /** A budget for this month is already overspent. */
    BUDGET_OVERSPENT(Severity.WARNING),

    /** AI-EMF's fund is short of its target. */
    EMERGENCY_FUND_SHORT(Severity.WARNING),

    /** AI-GOAL says a goal will not reach its target at the planned contribution. */
    GOAL_BEHIND(Severity.WARNING),

    /** AI-SEAS, through the forecast: a month ahead costs more than the last ninety days. */
    SEASONAL_MONTH(Severity.INFO),

    /** AI-FHS's biggest lever — the one thing that would raise the score most. */
    HEALTH_LEVER(Severity.INFO),
}

/**
 * One card (issue 9.5; FR-AI-002 — finding, evidence, confidence, at most one action).
 *
 * Why:  the finding is the **type plus its figures**, never a sentence: §21.6 keeps user-visible
 *       words in `strings.xml`, and an engine that wrote English could not be translated or tested
 *       for wording. The screen turns this into a sentence.
 * Input:  [type]; [subject] — the id this is about (a category, a goal, a month), `null` when the
 *         whole profile is; [subjectLabel] — that thing's own name, which is the user's data, not
 *         app copy; [period] — ISO `yyyy-MM` or a date, the window this is about; [amount] — the
 *         figure at stake, which is also the rank's tie-break; [secondary] — the supporting figure;
 *         [date]; [quantity] — a count where the card has one; [confidenceBps] — the source
 *         engine's own confidence; [citations] — the rules behind the figure; [sourceEngineId] and
 *         [sourceEngineVersion] — whose figure it is (AI-ARC-006).
 * Output: an immutable value.
 * Changelog: 2026-09-20 — Created for issue 9.5.
 */
data class Insight(
    val type: InsightType,
    val subject: String?,
    val subjectLabel: String?,
    val period: String,
    val amount: Money?,
    val secondary: Money? = null,
    val date: LocalDate? = null,
    val quantity: Int? = null,
    val confidenceBps: Int? = null,
    val citations: List<RuleCitation> = emptyList(),
    val sourceEngineId: String,
    val sourceEngineVersion: String,
) {
    /** How much attention it asks for — its type's, never set per insight. */
    val severity: Severity get() = type.severity

    /**
     * RULE-INS-DEDUP's identity: `type + subject + period`. The same overspent category in the same
     * month is the same card however often it is recomputed.
     */
    val fingerprint: String get() = "${type.name}|${subject ?: "-"}|$period"
}

/**
 * What AI-ORCH reads. Every signal is optional: `null` is "that engine has nothing to say".
 * Input:  [today]; the signals; [nowUtcMillis] — stamped on provenance; [rules].
 * Output: an immutable value.
 * Changelog: 2026-09-20 — Created for issue 9.5.
 */
data class InsightInput(
    val today: LocalDate,
    val forecast: ForecastSignal? = null,
    val emergency: EmergencyFundSignal? = null,
    val health: HealthSignal? = null,
    val budgets: List<BudgetSignal> = emptyList(),
    val goals: List<GoalSignal> = emptyList(),
    val nowUtcMillis: Long,
    val rules: InsightRules = InsightRules(),
)

/**
 * AI-FCT's published forecast, as the orchestrator reads it.
 * Input:  [crunchDays] — how many days fall below the buffer; [firstCrunchDate] — the first of them;
 *         [lowest] — the expected balance on it; [buffer]; [seasonalMonths] — months whose everyday
 *         spend the season moves, with the signed amount; [provenance] — AI-FCT's own.
 * Output: an immutable value.
 */
data class ForecastSignal(
    val crunchDays: Int,
    val firstCrunchDate: LocalDate?,
    val lowest: Money?,
    val buffer: Money,
    val seasonalMonths: List<SeasonalMonthSignal>,
    val provenance: EngineProvenance,
)

/** One month the season moves, and by how much. Input: [month] — ISO `yyyy-MM`; [adjustment] — signed. */
data class SeasonalMonthSignal(
    val month: String,
    val adjustment: Money,
)

/**
 * AI-EMF's plan.
 * Input:  [shortfall] — what is still missing, zero when funded; [topUpMonthly] — the monthly top-up
 *         it suggests; [runwayMonthsBps]; [provenance].
 */
data class EmergencyFundSignal(
    val shortfall: Money,
    val topUpMonthly: Money,
    val runwayMonthsBps: Int?,
    val provenance: EngineProvenance,
)

/**
 * AI-FHS's score and its biggest lever.
 * Input:  [score] — `null` when nothing could be scored; [leverLabel] — the lever's signal name,
 *         a stable id the screen turns into words; [leverGain] — points it would add; [provenance].
 */
data class HealthSignal(
    val score: Int?,
    val leverLabel: String?,
    val leverGain: Int?,
    val provenance: EngineProvenance,
)

/**
 * One overspent budget for the month.
 * Input:  [categoryId]; [categoryName]; [overspentBy] — positive paise over the plan; [monthKey];
 *         [provenance] — the budget engine's.
 */
data class BudgetSignal(
    val categoryId: String,
    val categoryName: String,
    val overspentBy: Money,
    val monthKey: String,
    val provenance: EngineProvenance,
)

/**
 * One goal that will not make its target at the planned contribution.
 * Input:  [goalId]; [name]; [shortfallMonthly] — the extra per month it needs; [targetDateIso];
 *         [provenance] — AI-GOAL's.
 */
data class GoalSignal(
    val goalId: String,
    val name: String,
    val shortfallMonthly: Money,
    val targetDateIso: String,
    val provenance: EngineProvenance,
)

/**
 * The feed (issue 9.5).
 * Input:  [insights] — every insight raised, in RULE-INS-RANK's order; [dashboard] — the first
 *         `dashboard_max` of them (FR-HOME-001's "top 3"); [provenance].
 * Output: an immutable value.
 * Changelog: 2026-09-20 — Created for issue 9.5.
 */
data class InsightFeed(
    val insights: List<Insight>,
    val dashboard: List<Insight>,
    val provenance: EngineProvenance,
)
