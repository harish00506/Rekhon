package com.aicfo.feature.dashboard

import androidx.compose.runtime.Immutable
import com.aicfo.domain.engines.orderofoperations.OrderOfOperations

/**
 * The full-order screen's state (issue 7.5; §36, ARC-004).
 *
 * Why:  one immutable value per screen, the pattern every screen here follows — the ranking, whether
 *       it has arrived, and whether reading it failed.
 * What: the engine's whole result, carried untouched so the screen renders exactly what was computed
 *       (P-03).
 * Result: every state the screen can be in is a value a test can construct.
 * Changelog: 2026-09-17 — Created for issue 7.5.
 *
 * @property ranking the Financial Order of Operations, or `null` before the first emission.
 * @property isLoading true until the first emission or failure.
 * @property errorCode an `AppError`-style code when the read failed, else `null`. A code, not a
 *   message: the wording lives in `strings.xml` (§21.6).
 */
@Immutable
data class OrderOfOperationsUiState(
    val ranking: OrderOfOperations? = null,
    val isLoading: Boolean = true,
    val errorCode: String? = null,
)

/**
 * What the full-order screen can ask for (ARC-004).
 * Why:  events flow up through one sealed type, never through ad-hoc callbacks. Navigation is not an
 *       event here — it leaves the screen, so it arrives as [OrderOfOperationsActions] lambdas.
 * Changelog: 2026-09-17 — Created for issue 7.5.
 */
sealed interface OrderOfOperationsEvent {
    /** The user has read the error and wants it gone. */
    data object DismissError : OrderOfOperationsEvent
}
