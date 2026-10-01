package com.aicfo.feature.settings

/**
 * Where the settings screen can send the user (issue 11.4; ARC-001).
 *
 * Why:  by issue 11.4 the screen took seven parameters and three of them were "somewhere else to
 *       go" — the consents dashboard, the erase page, and the file picker. Naming that group makes
 *       the signature about settings again, and follows the same argument `AccountsActions` records:
 *       a feature receives its navigation as lambdas so it never holds a `NavController` or knows a
 *       sibling destination exists.
 * What: the three departures, with defaults so a preview or a test can leave them out.
 * Result: one parameter instead of three, and a compile error if a new destination is added without
 *       being wired.
 * Changelog: 2026-10-01 — Created for issue 11.4.
 *
 * Input:  [onPickBackup] — opens the system file picker (issue 8.2); [onNavigateToConsents] — the
 *         consents dashboard (issue 11.3); [onNavigateToErase] — erase everything (issue 11.4).
 * Output: an immutable value.
 */
data class SettingsActions(
    val onPickBackup: () -> Unit = {},
    val onNavigateToConsents: () -> Unit = {},
    val onNavigateToErase: () -> Unit = {},
)
