package com.aicfo.feature.dashboard

import androidx.compose.runtime.Immutable
import com.aicfo.core.model.Money

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
 * The figures are placeholders: real Safe-to-Spend and net worth arrive with issues 5.1/5.2, which
 * will replace the values without changing this shape. Amounts are [Money] (`Long` paise, MNY-001)
 * even while they are placeholders, because a screen that starts out rendering a `Double` teaches
 * the wrong pattern to everything that copies it.
 *
 * Input:  [isLoading], [safeToSpend], [netWorth], [spendSplit], [errorCode].
 * Output: an immutable snapshot for the composable.
 */
@Immutable
data class DashboardUiState(
    val isLoading: Boolean = true,
    val safeToSpend: Money = Money.ZERO,
    val netWorth: Money = Money.ZERO,
    val spendSplit: SpendSplit = SpendSplit(),
    /**
     * An `AppError.code` when something failed, else `null`.
     * The **code**, not a message: the wording belongs in this feature's `strings.xml` (§21.6), so
     * the state stays free of user-visible copy and the screen can localise it.
     */
    val errorCode: String? = null,
)

/**
 * The needs/wants/savings split behind the dashboard's proportion bar.
 * Why:    the three figures always change together, so they travel together.
 * Result: input for `CfoProportionBar` (issue 1.8).
 * Input:  the three weights in `Long` paise (MNY-001). Output: an immutable value.
 * Changelog: 2026-07-25 — Created for issue 1.10.
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
