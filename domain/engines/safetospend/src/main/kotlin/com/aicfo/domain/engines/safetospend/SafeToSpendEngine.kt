package com.aicfo.domain.engines.safetospend

import com.aicfo.core.common.AppError
import com.aicfo.core.common.Result
import com.aicfo.core.model.EngineProvenance
import com.aicfo.core.model.Money

/**
 * Answers "how much can I still spend this month?" (issue 5.2; SRS §5.2, §14, AI-STS).
 *
 * Why:  the figure at the top of the app's home screen, and until this engine existed it was a
 *       literal — `Money(12_500_00L + today.dayOfMonth)`, invented in `DashboardViewModel`. A
 *       fabricated number on the first screen the user sees is the plainest possible breach of P-03
 *       ("numbers from math, words from AI"), and it shipped in every build from issue 1.10 to this
 *       one.
 *
 *       **The hard part is not the subtraction, it is deciding what the month has already claimed.**
 *       Money already spent is obvious. The rest is not: a bill the user scheduled for the 28th is
 *       spent as surely as one paid this morning; a confirmed recurring rule due on the 25th is a
 *       commitment the app knows about and the user has forgotten; and the savings the user told
 *       this app they intend to make are not spare cash just because they have not moved yet. A
 *       Safe-to-Spend that ignored any of the three would be optimistic in exactly the way that ends
 *       a month short, which is the failure the figure exists to prevent.
 * What: one method, `RULE-STS`'s formula, applied to amounts the caller has already resolved.
 * Result: one figure plus **the lines it was built from** — the breakdown is part of the result, not
 *       something a screen reconstructs, because P-02 forbids a black-box verdict and a card that
 *       re-derived the terms could disagree with the total above it.
 * Changelog: 2026-08-16 — Created for issue 5.2.
 *
 * **It decides nothing about which rows count.** Every term arrives pre-summed: what belongs in
 * "scheduled" is a storage question and `SafeToSpendRepository` answers it (ARC-005), the same
 * division of labour `BudgetEngine` and `NatureEngine` already keep to. That is what makes the whole
 * formula provable on the JVM.
 *
 * **It never floors at zero** (`RULE-STS.floor_at_zero = false`). A user already past the plan needs
 * to know by how much, and a clamped ₹0 reads identically to a month with nothing left and nothing
 * wrong.
 *
 * Pure Kotlin (ARC-002), so the whole subtraction is provable on the JVM.
 */
interface SafeToSpendEngine {
    /**
     * Applies `RULE-STS` to one month.
     *
     * Why:    a `Result` matching every other engine in this codebase, though a well-formed input
     *         cannot fail — the `Err` branch is reserved for arithmetic that will not fit in a
     *         `Long`, which `Money` raises rather than wrapping (MNY-001). An engine that let that
     *         escape would crash the home screen instead of degrading it (§21.6: no exceptions
     *         across layer boundaries).
     * What:   income, less the buffer, less spend to date, less what is still due, less the savings
     *         the month has not yet made.
     * Result: `Ok(safeToSpend)` for any input whose terms fit in a `Long`; the amount may be
     *         negative, which is a valid answer and not an error.
     * Input:  [input] — the six resolved amounts, the window they were read over, the caller's
     *         instant, and the thresholds to apply.
     * Output: `Result<SafeToSpend, AppError>`.
     */
    fun compute(input: SafeToSpendInput): Result<SafeToSpend, AppError>
}

/**
 * Everything `RULE-STS` needs about one month (issue 5.2).
 *
 * Why: a *shape*, not a ledger. Six amounts and a window state exactly what the formula reads: the
 *      engine does not know what a transaction, an account or a recurring rule is, so it cannot
 *      quietly start deciding which of them counts — the same narrowing `NatureInput` applies to the
 *      classifier and `RecurringCandidate` to the detector.
 *
 *      [nowUtcMillis] is **passed in, never read**: TIM-001 bans wall-clock reads in domain code and
 *      `CfoWallClockInDomain` fails the build on one. It is also what makes the golden file
 *      reproducible (P-08).
 * Result: the argument to [SafeToSpendEngine.compute].
 * Changelog: 2026-08-16 — Created for issue 5.2.
 *
 * Input:  [income] — the period's income in paise (MNY-001), resolved by the caller per
 *         `RULE-STS.income_basis`: the quick-setup envelope total, or the ledger's month-to-date
 *         income for a profile that declared none. Never negative — a caller with no income basis at
 *         all has no Safe-to-Spend to show and must not call this with a zero (P-03);
 *         [spentToDate] — §8.3's true spend so far, needs + wants, as a positive magnitude;
 *         [scheduled] — future-dated transactions due before the window closes, positive magnitude;
 *         [recurringDue] — confirmed recurring rules due before the window closes, positive
 *         magnitude, already deduplicated against [scheduled] by the caller;
 *         [goalContributionsRemaining] — the part of the month's savings envelope not yet made,
 *         positive magnitude, `Money.ZERO` when `RULE-STS.include_goal_contributions` is off or the
 *         user has already saved the whole envelope; [inputWindow] — the period read, as
 *         `yyyy-MM-dd..yyyy-MM-dd` (TIM-002), stamped into provenance (AI-ARC-003);
 *         [nowUtcMillis] — the instant stamped into provenance (TIM-001);
 *         [rules] — the thresholds, injected so a test can move one (ADR-0017).
 * Output: an immutable value.
 */
data class SafeToSpendInput(
    val income: Money,
    val spentToDate: Money,
    val scheduled: Money,
    val recurringDue: Money,
    val goalContributionsRemaining: Money,
    val inputWindow: String,
    val nowUtcMillis: Long,
    val rules: SafeToSpendRules = SafeToSpendRules(),
) {
    init {
        // Every term is a magnitude, and a negative one would silently *increase* Safe-to-Spend —
        // a refund booked as a negative "spent" would hand the user money to spend twice. Caught
        // here rather than in the engine so a miscomputed term fails at the caller that built it.
        require(income >= Money.ZERO) {
            "income is a magnitude and cannot be negative, was ${income.minor} paise: a caller with " +
                "no income basis has no Safe-to-Spend to show and must not call the engine at all"
        }
        require(spentToDate >= Money.ZERO) { "spentToDate is a magnitude, was ${spentToDate.minor} paise" }
        require(scheduled >= Money.ZERO) { "scheduled is a magnitude, was ${scheduled.minor} paise" }
        require(recurringDue >= Money.ZERO) { "recurringDue is a magnitude, was ${recurringDue.minor} paise" }
        require(goalContributionsRemaining >= Money.ZERO) {
            "goalContributionsRemaining is a magnitude, was ${goalContributionsRemaining.minor} paise"
        }
        require(inputWindow.isNotBlank()) {
            "AI-ARC-003 requires the window a result was computed over; a blank one makes the figure " +
                "unreproducible six months from now"
        }
    }
}

/**
 * What is left to spend, and everything that was taken off (issue 5.2; §5.2, P-02).
 *
 * Why: [lines] is not decoration and not a UI concern — it is the result. §5.2's acceptance
 *      criterion is that the card "shows the breakdown/rule that produced it; never a black-box
 *      number", and the only way to guarantee the breakdown adds up to the headline is to compute
 *      both in one place and ship them together. A screen handed only [amount] would have to
 *      re-derive the terms to explain them, and the two could then disagree — which is precisely the
 *      failure P-02 exists to prevent.
 * Result: what `MoneySummary` renders on the dashboard, and what the Purchase Advisor (issue 10.1)
 *      and the health score (§14) will read.
 * Changelog: 2026-08-16 — Created for issue 5.2.
 *
 * Input:  [amount] — what is left, signed paise (MNY-001); **negative means the month is already
 *         overcommitted**, which is a real answer; [lines] — the income line followed by one line
 *         per non-zero deduction, in the order they should be shown; [provenance] — engine id and
 *         version, the caller's instant, the window read, and the `RULE-STS` citation.
 * Output: an immutable value.
 */
data class SafeToSpend(
    val amount: Money,
    val lines: List<SafeToSpendLine>,
    val provenance: EngineProvenance,
) {
    init {
        require(lines.isNotEmpty()) {
            "A Safe-to-Spend figure shows the lines that produced it (P-02) — an empty breakdown is " +
                "the black-box verdict §5.2 forbids"
        }
        require(provenance.evidence.isNotEmpty()) {
            "A Safe-to-Spend figure names the rule that shaped it (P-02, AI-ARC-006)"
        }
        // The invariant the whole type exists for. If the lines and the headline could disagree, the
        // breakdown would be a plausible-looking fiction beside a correct number, which is worse
        // than showing no breakdown at all.
        require(lines.fold(Money.ZERO) { running, line -> running + line.signedAmount } == amount) {
            "the breakdown does not add up to the figure it explains: lines sum to " +
                "${lines.fold(Money.ZERO) { running, line -> running + line.signedAmount }.minor} paise, " +
                "amount is ${amount.minor} paise"
        }
    }
}

/**
 * One term of the breakdown (issue 5.2; P-02).
 *
 * Why:  a component *and* a magnitude, rather than a pre-formatted string, because the words belong
 *       in `:feature:dashboard`'s `strings.xml` with the rest of the app's copy (§21.6) — a domain
 *       module that returned "Bills due" would be untranslatable and would put user-visible English
 *       in a pure-Kotlin engine. The same split `TransactionLabels` already makes.
 * Result: the element type of [SafeToSpend.lines].
 * Changelog: 2026-08-16 — Created for issue 5.2.
 *
 * Input:  [component] — which term this is; [amount] — always a positive magnitude, so the sign
 *         lives in [SafeToSpendComponent.isDeduction] rather than being encoded twice.
 * Output: an immutable value.
 */
data class SafeToSpendLine(
    val component: SafeToSpendComponent,
    val amount: Money,
) {
    init {
        require(amount >= Money.ZERO) {
            "a breakdown line carries a magnitude; the direction is the component's " +
                "(was ${amount.minor} paise for $component)"
        }
    }

    /**
     * The line's contribution to the total.
     * Why:    the one place the direction of a term is decided, so [SafeToSpend]'s adds-up check and
     *         the engine's own arithmetic cannot disagree about whether income is added or taken off.
     * Result: `+amount` for income, `−amount` for a deduction.
     * Input:  none. Output: [Money].
     */
    val signedAmount: Money get() = if (component.isDeduction) Money.ZERO - amount else amount
}

/**
 * The terms `RULE-STS` subtracts, in display order (issue 5.2; §5.2).
 *
 * Why:  an enum rather than free strings, so a `when` over it fails to compile when a term is added
 *       — which is what stops a new deduction from being silently invisible on the card that is
 *       supposed to explain the figure. Declaration order **is** display order, so the breakdown
 *       reads top to bottom the way the formula does.
 * Result: the vocabulary of [SafeToSpendLine], and the key the dashboard maps to a `strings.xml`
 *       label.
 * Changelog: 2026-08-16 — Created for issue 5.2.
 */
enum class SafeToSpendComponent(
    /** Whether this term is taken off income (`true`) or added to it (`false`). */
    val isDeduction: Boolean,
) {
    /** The period's income, per `RULE-STS.income_basis`. */
    INCOME(isDeduction = false),

    /** `RULE-STS.buffer_pct` of income, held back for the commitments the app does not know about. */
    BUFFER(isDeduction = true),

    /** §8.3's true spend so far this month — needs plus wants (issue 4.3). */
    SPENT(isDeduction = true),

    /** Future-dated transactions the user has already scheduled inside the window (FR-TXN-010). */
    SCHEDULED(isDeduction = true),

    /** Confirmed recurring rules falling due inside the window (FR-TXN-006). */
    RECURRING(isDeduction = true),

    /** The part of the month's savings envelope not yet made (§5.2's "goal contributions"). */
    GOALS(isDeduction = true),

    /**
     * How much of an overcommitted month the clamp is hiding — **only** when
     * `RULE-STS.floor_at_zero` is on, which in the shipped rulebook it is not.
     *
     * Why: it is added back rather than silently dropped. A clamp that simply reported ₹0 would
     *      leave the breakdown summing to a negative figure beside a headline of zero — a
     *      plausible-looking fiction, which is the one thing worse than showing no breakdown
     *      (P-02). Naming the shortfall keeps every line on the card true and keeps
     *      [SafeToSpend]'s adds-up invariant absolute rather than conditional.
     */
    SHORTFALL(isDeduction = false),
}

/**
 * Builds the engine for the DI graph (ARC-003).
 * Why:    the implementation is `internal`, so `:app`'s Hilt module cannot name it — the seam every
 *         engine in this codebase uses, kept identical on purpose.
 * Result: a [SafeToSpendEngine]. Input: none. Output: the engine.
 * Changelog: 2026-08-16 — Created for issue 5.2.
 */
object SafeToSpendEngineFactory {
    /** Result: the production engine. Input: none. Output: [SafeToSpendEngine]. */
    fun create(): SafeToSpendEngine = DefaultSafeToSpendEngine()
}
