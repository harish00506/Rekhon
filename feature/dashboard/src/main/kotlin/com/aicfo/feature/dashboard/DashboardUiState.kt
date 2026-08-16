package com.aicfo.feature.dashboard

import androidx.compose.runtime.Immutable
import com.aicfo.core.model.Money
import com.aicfo.data.repository.CashFlowSummary
import com.aicfo.data.repository.CategoryBudget
import com.aicfo.data.repository.CategoryBudgetAlert
import com.aicfo.data.repository.FilteredTransaction
import com.aicfo.domain.engines.nature.NatureBreakdown
import com.aicfo.domain.engines.safetospend.SafeToSpend

/**
 * Everything the dashboard renders, as one value (ARC-004).
 *
 * Why:  §21.2 requires UI state to be **one immutable data class per screen**, exposed as a
 *       `StateFlow`. One value rather than several means a screen can never render a half-updated
 *       mix — a new balance beside a stale spending split — and it makes every state the screen can
 *       be in nameable in a test. `@Immutable` also lets Compose skip recomposition when nothing
 *       changed, which matters on a screen that observes several flows.
 * What: the loading flag, the figures, and any error to surface.
 * Result: the reference shape every other screen in this app copies.
 * Changelog: 2026-07-25 — Created for issue 1.10 as the ARC-004 reference implementation.
 *
 * **Every figure here is now engine-derived (issue 5.2).** [spendSplit] comes from the budget
 * envelopes quick setup persisted (FR-ONB-002), [netWorth] from the daily snapshot (FR-ACC-005), and
 * [safeToSpend] — the last literal on this screen, a hardcoded ₹12,500 from issue 1.10 until now —
 * from `SafeToSpendEngine` (§5.2, AI-STS). Amounts are [Money] (`Long` paise, MNY-001) throughout,
 * because a screen that renders a `Double` teaches the wrong pattern to everything that copies it.
 *
 * Input:  [isLoading]; [safeToSpend] — **`null` until the first emission, and for a profile with no
 *         income basis at all** (see `SafeToSpendRepository.observeSafeToSpend`), which the screen
 *         renders as an absence rather than ₹0; [netWorth]; [spendSplit] — **`null` when the user
 *         skipped quick setup**, which the screen renders as an empty state. Deliberately not a
 *         zeroed `SpendSplit`: a bar of three zeroes is indistinguishable from a real budget of
 *         nothing, and showing one to a user who has no budget is a number the app made up (P-03).
 *         [errorCode].
 * Output: an immutable snapshot for the composable.
 */
@Immutable
data class DashboardUiState(
    val isLoading: Boolean = true,
    /**
     * What is still safe to spend this month, with the breakdown that explains it (issue 5.2;
     * §5.2, AI-STS).
     *
     * Carried whole rather than as a bare [Money], because §5.2's acceptance criterion is that the
     * card shows "the breakdown/rule that produced it; never a black-box number" — and the only way
     * the lines can be guaranteed to add up to the headline is for both to arrive from the engine
     * together. A state holding only the amount would force the screen to re-derive the terms, and
     * the two could then disagree.
     *
     * `null` before the first emission **and** for a profile with no income basis, which are
     * rendered identically and deliberately: neither is a ₹0 anyone told this app about (P-03), the
     * same rule [netWorth] follows.
     */
    val safeToSpend: SafeToSpend? = null,
    /**
     * The latest stored snapshot's net worth, or **`null` before the first one is taken** (2.6).
     *
     * Nullable rather than zeroed for the same reason [spendSplit] is: a user who onboarded a minute
     * ago has no snapshot, and ₹0 is both a figure the app made up (P-03) and indistinguishable from
     * a real net worth of nothing. The screen renders the absence.
     */
    val netWorth: Money? = null,
    val spendSplit: SpendSplit? = null,
    /**
     * What this month's money actually became (issue 4.3; SRS §8.3).
     *
     * **Not the same number as [spendSplit], and the difference is the point.** That is the *budget*
     * — what the user planned, from the quick-setup envelopes. This is what happened, classified by
     * §8.3.1 from the ledger. A dashboard that showed only the plan would be a dashboard that never
     * disagreed with the user.
     *
     * `null` until the first emission, and `NatureBreakdown.isEmpty` for a month with nothing in it
     * — the screen renders an empty state for both rather than a row of zeroes, which is a figure
     * the app made up (P-03), the same rule [spendSplit] follows.
     */
    val natureBreakdown: NatureBreakdown? = null,
    /**
     * This month's income, expense and net (issue 5.1; FR-DASH-*).
     *
     * `null` until the first emission — the same rule [netWorth] follows, and for the same reason:
     * a zeroed card before the read has actually happened would be a figure the app made up (P-03).
     */
    val cashFlow: CashFlowSummary? = null,
    /**
     * Every category with its live status this month (issue 5.1; FR-DASH-*).
     *
     * `null` until the first emission; **not the same absence as "nothing budgeted"**, which is an
     * empty list once [BudgetTotals] filters out the unbudgeted rows — see [budgetTotals]. Kept as
     * the raw rows, not a total, so the getter is the one place that sums them (P-03: the state
     * itself computes nothing, [BudgetsUiState] in `:feature:budgets` makes the same choice).
     */
    val budgets: List<CategoryBudget>? = null,
    /**
     * Budgets currently sitting in an alert band, for the "needs attention" line (issue 5.1;
     * FR-DASH-*; FR-BUD-004).
     *
     * Empty by default rather than nullable: unlike [budgets], a bare list is never ambiguous here —
     * "no alerts yet" and "genuinely nothing to warn about" render identically. Derived from
     * [budgets] in the same update, via `BudgetRepository.alertFor` — not its own read, and not its
     * own failure mode: a broken budgets read surfaces through [errorCode] exactly once, not once
     * per figure it happens to affect.
     */
    val budgetAlerts: List<CategoryBudgetAlert> = emptyList(),
    /**
     * The newest few transactions, for the recent-activity preview (issue 5.1; FR-DASH-*).
     *
     * `null` until the first emission, for the reason [budgets] gives — the empty list is a real
     * state (a brand-new profile) and must read differently from "not read yet".
     */
    val recentActivity: List<FilteredTransaction>? = null,
    /**
     * An `AppError.code` when something failed, else `null`.
     * The **code**, not a message: the wording belongs in this feature's `strings.xml` (§21.6), so
     * the state stays free of user-visible copy and the screen can localise it.
     */
    val errorCode: String? = null,
) {
    /**
     * The month's budgeted and spent totals across every **budgeted** category (issue 5.1).
     *
     * Why:    unbudgeted categories carry `budgeted = Money.ZERO` by construction (`CategoryBudget`'s
     *         own doc comment) but real spend, and folding their spend into this total would make
     *         "spent" exceed "budgeted" by exactly the amount nobody planned for — a card that reads
     *         as a blown budget when nothing was ever budgeted. Excluding them is what
     *         `:feature:budgets`' own `PlannedSection` does for the same reason.
     * Result: the totals, or `null` when [budgets] has not loaded yet **or** nothing has been
     *         budgeted this month — both render the same empty state, matching how the screen
     *         already treats "not loaded" and "genuinely nothing" for [spendSplit].
     * Input:  none — reads [budgets]. Output: `BudgetTotals?`.
     */
    val budgetTotals: BudgetTotals?
        get() {
            val planned = budgets?.filterNot { it.isUnbudgeted }
            if (planned.isNullOrEmpty()) return null
            return BudgetTotals(
                budgeted = planned.fold(Money.ZERO) { running, row -> running + row.status.budgeted },
                spent = planned.fold(Money.ZERO) { running, row -> running + row.status.spent },
            )
        }
}

/**
 * The budget summary card's two figures, already folded (issue 5.1).
 * Why:    the arithmetic lives in one place — [DashboardUiState.budgetTotals]'s getter — rather than
 *         in the composable, so a screenshot test and a ViewModel test agree on what "the total" means.
 * Result: what the card renders. Input: [budgeted]; [spent]. Output: an immutable value.
 * Changelog: 2026-08-15 — Created for issue 5.1.
 */
@Immutable
data class BudgetTotals(
    val budgeted: Money,
    val spent: Money,
)

/**
 * The needs/wants/savings split behind the dashboard's proportion bar.
 * Why:    the three figures always change together, so they travel together.
 * Result: input for `CfoProportionBar` (issue 1.8).
 * Input:  the three weights in `Long` paise (MNY-001). Output: an immutable value.
 * Changelog: 2026-07-25 — Created for issue 1.10.
 *            2026-07-27 — Issue 2.3: populated from the persisted budget envelopes.
 */
@Immutable
data class SpendSplit(
    val needsMinor: Long = 0L,
    val wantsMinor: Long = 0L,
    val savingsMinor: Long = 0L,
)

/**
 * Everything the user can do on the dashboard (ARC-004).
 *
 * Why:    events flow **up** through a sealed interface, so the composable stays a function of
 *         state with no logic of its own, and the compiler lists every interaction the ViewModel
 *         must handle. A lambda-per-action would let a new interaction be added without anyone
 *         noticing the ViewModel never handled it.
 * Result: the screen's complete input surface.
 * Changelog: 2026-07-25 — Created for issue 1.10.
 */
sealed interface DashboardEvent {
    /** The user asked for a refresh. */
    data object Refresh : DashboardEvent

    /** The user dismissed the error banner. */
    data object DismissError : DashboardEvent
}
