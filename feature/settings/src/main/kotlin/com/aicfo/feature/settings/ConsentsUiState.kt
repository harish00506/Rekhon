package com.aicfo.feature.settings

import com.aicfo.core.datastore.ConsentFeature

/**
 * What the consents dashboard shows (issue 11.3; §23, P-01, DPDP).
 *
 * Why:  P-01 promises consent that is explicit, per-feature and **revocable**, and until this
 *       screen the user could operate that promise without ever being able to inspect it: the
 *       settings section rendered a switch per feature and dropped the ledger's timestamps, which
 *       issue 1.9 had been recording since the first release precisely so "when did I agree to
 *       this?" stayed answerable.
 * What: one row per consent, in the enum's own order, with both dates as stored ISO days.
 * Result: the screen is a pure function of this, and a test can assert the whole thing (ARC-004).
 * Changelog: 2026-10-01 — Created for issue 11.3.
 *
 * Input:  [rows] — every [ConsentFeature], always, including the ones nobody has answered for;
 *         [isLoading] — true until the ledger's first emission, so "no consents granted" is never
 *         shown before it is known; [errorCode] — a ledger that could not be read.
 * Output: an immutable value.
 */
data class ConsentsUiState(
    val rows: List<ConsentRow> = emptyList(),
    val isLoading: Boolean = true,
    val errorCode: String? = null,
)

/**
 * One consent, as the user sees it (issue 11.3).
 *
 * Why:    the dates are the point. A switch says what is true now; the ledger says what was agreed
 *         and what was withdrawn, which is what a DPDP request asks for and what makes a privacy
 *         promise checkable rather than merely stated.
 * What:   the feature, whether it is in force, and both timestamps as ISO days in the **profile's**
 *         zone — never UTC, which would tell a user in Kolkata they agreed a day before they did.
 * Result: a row the screen renders with no arithmetic of its own.
 * Changelog: 2026-10-01 — Created for issue 11.3.
 *
 * Input:  [feature]; [granted]; [grantedOnIsoDate] — `null` when never granted;
 *         [revokedOnIsoDate] — `null` when never withdrawn. Output: an immutable value.
 */
data class ConsentRow(
    val feature: ConsentFeature,
    val granted: Boolean,
    val grantedOnIsoDate: String? = null,
    val revokedOnIsoDate: String? = null,
)

/**
 * What the user can do on the consents dashboard (issue 11.3; ARC-004).
 *
 * Why:    two events and no toggle. A `Toggled(feature, Boolean)` would read as one action, and
 *         the two are not symmetrical: granting starts a data flow and withdrawing stops one, and
 *         the screen says different things about each. Separate events keep that visible at every
 *         call site.
 * Changelog: 2026-10-01 — Created for issue 11.3.
 */
sealed interface ConsentsEvent {
    /** Withdraw a consent. Takes effect at once — see the screen's own wording. */
    data class Revoked(val feature: ConsentFeature) : ConsentsEvent

    /** Give a consent that was never given, or was withdrawn. */
    data class Granted(val feature: ConsentFeature) : ConsentsEvent

    /** Dismiss the error banner. */
    data object DismissError : ConsentsEvent
}
