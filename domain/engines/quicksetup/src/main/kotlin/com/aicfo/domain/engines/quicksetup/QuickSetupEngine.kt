package com.aicfo.domain.engines.quicksetup

import com.aicfo.core.common.AppError
import com.aicfo.core.common.Result
import com.aicfo.core.model.EngineProvenance
import com.aicfo.core.model.Money
import com.aicfo.core.model.RuleCitation

/**
 * Turns onboarding's three optional figures into a starting budget (issue 2.3; FR-ONB-002).
 *
 * Why:  FR-ONB-002 says the quick-setup seeds are "used to seed budgets and the emergency-fund
 *       target", and P-03 says every number comes from deterministic math rather than from a
 *       model. This is that math, and it is deliberately the *only* place it lives: the screen
 *       shows what this returns, the repository persists what this returns, and neither computes a
 *       rupee of its own. Pure Kotlin (ARC-002), so the whole thing is provable on the JVM.
 * What: one method, from the seeds to a [QuickSetupPlan] carrying envelopes, recurring seeds, an
 *       emergency-fund target, and the provenance that says which rules produced them.
 * Result: a first-run user has a budget and a target on day one, each traceable to a rule.
 * Changelog: 2026-07-27 — Created for issue 2.3.
 *
 * **It advises, it does not act (P-07).** Nothing here writes anything or moves any money; the
 * caller decides whether to persist the plan, and the user decides whether to keep it.
 */
interface QuickSetupEngine {
    /**
     * Computes the plan for one set of seeds.
     * Why:    a `Result` rather than a throw, because a negative amount reaching here is a UI or
     *         parse fault the screen should surface at its field, not a crash on the last step of
     *         onboarding (§21.6).
     * What:   validates, then derives envelopes, recurring seeds, the emergency target and the
     *         obligation verdict from whatever the user actually answered.
     * Result: `Ok(plan)` — possibly an empty plan when nothing was answered — or
     *         `Err(AppError.Validation)` naming the offending field.
     * Input:  [input] — the seeds plus the caller's clock readings. Output:
     *         `Result<QuickSetupPlan, AppError>`.
     */
    fun plan(input: QuickSetupInput): Result<QuickSetupPlan, AppError>
}

/**
 * Everything the engine needs, as one value.
 *
 * Why:    the three amounts are all optional and all `Money`, so passing them loose is how a rent
 *         figure ends up seeding an income. The two clock readings are **passed in, never read**:
 *         TIM-001 bans wall-clock reads in domain code, and `CfoWallClockInDomain` fails the build
 *         on one — which is also what makes every case in the test suite reproducible (P-08).
 * Result: the argument to [QuickSetupEngine.plan].
 * Changelog: 2026-07-27 — Created for issue 2.3.
 *
 * Input:  [monthlyIncome], [rentOrEmi], [typicalSavings] — what the user typed, `null` when they
 *         left the field blank; a zero is treated as blank, matching how `SettingsStore` stores an
 *         unanswered seed. [periodStartIsoDate] — the first day of the budget month, ISO
 *         `yyyy-MM-dd` in the profile zone (TIM-002). [nowUtcMillis] — the instant stamped into
 *         provenance (TIM-001). [rules] — the thresholds to apply.
 * Output: an immutable value.
 */
data class QuickSetupInput(
    val monthlyIncome: Money? = null,
    val rentOrEmi: Money? = null,
    val typicalSavings: Money? = null,
    val periodStartIsoDate: String,
    val nowUtcMillis: Long,
    val rules: QuickSetupRules = QuickSetupRules(),
)

/**
 * What the engine derived (AI-ARC-003).
 *
 * Why:    one value rather than four return channels, because these figures are only meaningful
 *         together — an emergency target without the needs envelope it was sized from is a number
 *         with no explanation, and P-02 requires the explanation.
 * Result: what the onboarding summary renders and the repository persists.
 * Changelog: 2026-07-27 — Created for issue 2.3.
 *
 * Input:  [periodStartIsoDate] — the month these envelopes are for, echoed from the input so the
 *         plan is self-describing: a caller persisting it should not have to remember which month
 *         it asked about, and a summary card showing envelopes without their period is ambiguous.
 *         [envelopes] — needs/wants/savings, in display order, empty when no income was given;
 *         [recurring] — one seed per answered figure, empty when none were; [emergencyFundTarget]
 *         — the needs envelope times the clamped runway, `null` without an income;
 *         [emergencyRunwayMonths] — how many months that target covers, **after** RULE-RUNWAY-M's
 *         clamp. Carried rather than recomputed, so the screen can say "3 months of your needs
 *         budget" without a second copy of the clamp rule living in the UI (P-02, P-03);
 *         [obligationLoadBps] — rent as a share of income in basis points (MNY-002), `null` when
 *         either figure is missing; [obligationVerdict] — where that lands against RULE-EMI-40;
 *         [provenance] — engine id, version, instant and the rules that fired.
 * Output: an immutable value.
 */
data class QuickSetupPlan(
    val periodStartIsoDate: String,
    val envelopes: List<BudgetEnvelope>,
    val recurring: List<RecurringSeed>,
    val emergencyFundTarget: Money?,
    val emergencyRunwayMonths: Int,
    val obligationLoadBps: Int?,
    val obligationVerdict: ObligationVerdict,
    val provenance: EngineProvenance,
) {
    /**
     * Whether the user answered nothing at all.
     * Why:    the caller has to decide whether to write anything, and "is this plan worth
     *         persisting?" should not be re-derived as `envelopes.isEmpty() && recurring.isEmpty()`
     *         at each call site — one of them would forget a half, and an empty profile row would
     *         be written for a user who skipped the step.
     * Result: `true` when there is nothing to persist. Input: none. Output: `Boolean`.
     */
    val isEmpty: Boolean get() = envelopes.isEmpty() && recurring.isEmpty()
}

/**
 * One budget envelope: a share of income set aside for a kind of spending.
 * Why:    the quick-setup budget is deliberately per **nature**, not per category — no categories
 *         exist at first run (issue 4.1 builds them), and FR-BUD-001 allows a budget per
 *         category-group, which is what a nature is.
 * Result: a row in `budget`, and a line in the onboarding summary.
 * Input:  [nature]; [amount] — paise (MNY-001); [citation] — the rule that sized it (P-02).
 * Output: an immutable value.
 * Changelog: 2026-07-27 — Created for issue 2.3.
 */
data class BudgetEnvelope(
    val nature: BudgetNature,
    val amount: Money,
    val citation: RuleCitation,
)

/**
 * What an envelope is for — the SRS's nature classification (§8.3, AI-CLS-N).
 * Why:    the same closed set `CategoryEntity.nature` stores, so a category attached later by
 *         issue 4.3 lines up with the envelope it belongs to without a translation table.
 * Result: the envelope's kind. Changelog: 2026-07-27 — Created for issue 2.3.
 */
enum class BudgetNature {
    /** Rent, EMIs, utilities, groceries — the obligations. */
    NEED,

    /** Discretionary spending. The flex takes from here first. */
    WANT,

    /** Savings and investment. A floor, never a residual. */
    INVEST,
    ;

    /** The value stored in `budget.nature` and `category.nature`. Result: a lowercase code. */
    val storedValue: String get() = name.lowercase()
}

/**
 * One recurring money movement the user told us about.
 * Why:    FR-ONB-002 calls these "recurring income, rent" — they are the user's own figures, so
 *         **no rule is cited**: nothing was derived, and attaching a citation would imply the app
 *         computed a number it merely wrote down (P-03).
 * Result: a row in `recurring_rule`, unconfirmed until the user says so (FR-TXN-006).
 * Input:  [kind]; [amount] — signed paise, positive for an inflow and negative for an outflow,
 *         matching the `transactions` convention so the sign is decided once; [cadence];
 *         [nextDueIsoDate] — the next occurrence, ISO `yyyy-MM-dd` (TIM-002).
 * Output: an immutable value.
 * Changelog: 2026-07-27 — Created for issue 2.3.
 */
data class RecurringSeed(
    val kind: RecurringKind,
    val amount: Money,
    val nextDueIsoDate: String,
    val cadence: RecurringCadence = RecurringCadence.MONTHLY,
)

/**
 * Which of the three seeds a recurring rule came from.
 * Why:    a stored code rather than a label, so the row stays translatable — the UI maps this to a
 *         `strings.xml` entry (§21.6) instead of the database holding English.
 * Result: the seed's kind. Changelog: 2026-07-27 — Created for issue 2.3.
 */
enum class RecurringKind {
    /** Salary or other monthly income. An inflow. */
    INCOME,

    /** Rent or an EMI. An outflow, and the input to the obligation check. */
    RENT_EMI,

    /** A regular transfer to savings. An outflow from the spending account's point of view. */
    SAVINGS,
    ;

    /** The value stored in `recurring_rule.seed_kind`. Result: a lowercase code. */
    val storedValue: String get() = name.lowercase()
}

/**
 * How often a recurring rule repeats.
 * Why:    only [MONTHLY] exists today because every quick-setup figure is a monthly one; issue 3.7
 *         adds the rest when its detector can actually produce them. An enum with one constant is
 *         still worth having — it puts the column's closed set in the type system now, rather than
 *         leaving a free-text `cadence` that later has to be cleaned up.
 * Result: the rule's cadence. Changelog: 2026-07-27 — Created for issue 2.3.
 */
enum class RecurringCadence {
    MONTHLY,
    ;

    /** The value stored in `recurring_rule.cadence`. Result: a lowercase code. */
    val storedValue: String get() = name.lowercase()
}

/**
 * Where the user's obligations sit against RULE-EMI-40.
 * Why:    P-02 — the summary shows a verdict with the rule behind it, not a bare percentage the
 *         user has to interpret. Three bands rather than a boolean because "over the guideline"
 *         and "at the level lenders refuse" are different conversations.
 * Result: the obligation check's outcome. Changelog: 2026-07-27 — Created for issue 2.3.
 */
enum class ObligationVerdict {
    /** Not enough was answered to say. Never rendered as "you are fine" (P-03). */
    UNKNOWN,

    /** At or under RULE-EMI-40's warn band. */
    WITHIN_LIMIT,

    /** Past the warn band but under the fail band. */
    ABOVE_LIMIT,

    /** At or past the fail band — the rule's own severity is `fail`. */
    HARD_FAIL,
}

/**
 * Builds the engine for the DI graph (ARC-003).
 * Why:    ARC-003 keeps engine implementations `internal`, so `:app`'s Hilt module cannot name one.
 *         This is the same seam `RepositoryFactory` and `CfoDatabaseFactory` already use, kept
 *         deliberately identical so the codebase has one pattern rather than three.
 * Result: a [QuickSetupEngine]. Input: none. Output: the engine.
 * Changelog: 2026-07-27 — Created for issue 2.3.
 */
object QuickSetupEngineFactory {
    /** Result: the production engine. Input: none. Output: [QuickSetupEngine]. */
    fun create(): QuickSetupEngine = DefaultQuickSetupEngine()
}
