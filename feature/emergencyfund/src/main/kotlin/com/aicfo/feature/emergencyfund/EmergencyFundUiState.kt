package com.aicfo.feature.emergencyfund

import androidx.compose.runtime.Immutable
import com.aicfo.domain.engines.emergencyfund.EmergencyFundPlan

/**
 * Everything the emergency-fund screen draws, as one immutable value (issue 7.2; ARC-004).
 *
 * Why:  one state class per screen as a `StateFlow`, events up via a sealed interface — the shape
 *       every screen in this app keeps, so a composable holds nothing and a test can drive any
 *       state directly.
 * What: the assessment, whether the evidence drawer is open, and the two transient flags.
 * Result: what `EmergencyFundScreen` renders.
 * Changelog: 2026-09-02 — Created for issue 7.2.
 *
 * @property plan the assessment, already computed by the engine (P-03). **Null only while loading**
 *   — a profile with no data still produces a plan, an `UNKNOWN` one, because "we cannot size this
 *   yet" is an answer the screen has to render rather than an absence.
 * @property isEvidenceOpen whether the drill-down is expanded. §10.1 requires every number to link
 *   to its evidence; this is where that lives, collapsed by default so the headline leads.
 * @property isLoading true until the first emission, so "no data yet" and "not read yet" are not
 *   drawn the same way.
 * @property errorCode a dotted key from `AppError`, or null. A key rather than a message, because
 *   the domain must not decide wording.
 */
@Immutable
data class EmergencyFundUiState(
    val plan: EmergencyFundPlan? = null,
    val isEvidenceOpen: Boolean = false,
    val isLoading: Boolean = true,
    val errorCode: String? = null,
)

/**
 * What the emergency-fund screen can be asked to do (issue 7.2; ARC-004).
 *
 * Why:  a sealed interface so the `when` in the ViewModel is exhaustive — a new event cannot be
 *       added without somewhere to handle it.
 *
 *       **Every event here is a view event, and that is the point.** §10.1's coach suggests pausing
 *       goals or deploying a surplus; none of that is actioned from this screen, because the app
 *       advises and the user decides (P-07). There is nothing here that moves money.
 * Result: events travel up, state comes down, and the composable holds nothing.
 * Changelog: 2026-09-02 — Created for issue 7.2.
 */
sealed interface EmergencyFundEvent {
    /** Show or hide the evidence drill-down. */
    data object ToggleEvidence : EmergencyFundEvent

    /** Clear the error banner. */
    data object DismissError : EmergencyFundEvent
}
