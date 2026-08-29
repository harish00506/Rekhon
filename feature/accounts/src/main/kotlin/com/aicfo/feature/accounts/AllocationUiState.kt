package com.aicfo.feature.accounts

import androidx.compose.runtime.Immutable
import com.aicfo.domain.engines.investment.PortfolioAllocation

/**
 * Everything the allocation screen shows, in one immutable value (issue 6.4; FR-INV-002, ARC-004).
 *
 * Why:  one state class per screen as a `StateFlow`, for the reason [HoldingsUiState] gives — every
 *       reachable state is constructible in a test, and there is no second source of truth for the
 *       screen to disagree with.
 *
 *       **The engine's result is held whole rather than flattened into fields.** A screen that
 *       copied out `slices` and `flags` and left `provenance` behind would be a screen that cannot
 *       show which rule fired, and P-02 is not optional here: the whole point of this screen is
 *       that a warning names the row that produced it.
 * What: the allocation, and the loading/error flags around it.
 * Result: a screen whose every state is assertable without a device.
 * Changelog: 2026-08-28 — Created for issue 6.4.
 *
 * @property allocation the split, the flags and the provenance, or `null` before the first read.
 * @property isLoading whether the first read has landed.
 * @property errorCode a failure to show in the banner, or `null`.
 */
@Immutable
data class AllocationUiState(
    val allocation: PortfolioAllocation? = null,
    val isLoading: Boolean = true,
    val errorCode: String? = null,
) {
    /**
     * Whether to show the "nothing invested yet" invitation rather than a chart.
     *
     * Why: the same three-way distinction [HoldingsUiState.isEmpty] draws. Still loading is not
     *      "you own nothing" — the invitation would flash before the store answered — and neither
     *      is a failed read, which has a banner of its own. The engine's own reason is carried
     *      through rather than re-derived, so the screen can tell "add a holding" apart from
     *      "price the ones you have".
     */
    val isEmpty: Boolean get() = allocation?.slices?.isEmpty() == true && !isLoading && errorCode == null

    /**
     * Whether some of the portfolio could not be valued and the user should be told.
     *
     * Why: P-02. A split computed over eight of eleven holdings is not wrong, it is partial, and a
     *      chart that says so is checkable where one that stays silent is a confident half-truth.
     */
    val hasUnvalued: Boolean get() = (allocation?.unvaluedCount ?: 0) > 0
}
