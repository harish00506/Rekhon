package com.aicfo.domain.engines.notification

import com.aicfo.core.model.RuleCitation

/**
 * `RULE-NTF-BUDGET` and `RULE-NTF-QUIET` from `ai/rules/rules-kb.json`, as a typed mirror
 * (issue 9.6; §17.2, CLAUDE.md §6).
 *
 * Why:  how often the app may interrupt someone, and when it may not, are exactly the numbers a
 *       reviewer should be able to change in one file — and exactly the numbers that would
 *       otherwise end up as constants in a worker. There is no runtime loader (ADR-0017), so this
 *       is the copy the engine reads, held honest by `NotificationRulebookDriftTest`.
 * What: NTF-001's two caps and their exemption, and NTF-002's window and its bypass.
 * Result: everything [NotificationPolicyEngine] reads besides its input.
 * Changelog: 2026-09-20 — Created for issue 9.6 from rules-kb.json 1.19.0.
 *
 * Input:  [dailyMax] / [weeklyMax] — non-critical notifications allowed per day and per window, at
 *         least 0 (zero is a valid, if silent, policy); [windowDays] — what "a week" counts over;
 *         [criticalExempt] — whether §17.1's critical row ignores the caps; [quietStartHour] /
 *         [quietEndHour] — 0..23, the window wrapping midnight; [criticalMayBypassQuietHours].
 * Output: an immutable value.
 */
data class NotificationRules(
    val dailyMax: Int = DEFAULT_DAILY_MAX,
    val weeklyMax: Int = DEFAULT_WEEKLY_MAX,
    val windowDays: Int = DEFAULT_WINDOW_DAYS,
    val criticalExempt: Boolean = true,
    val quietStartHour: Int = DEFAULT_QUIET_START,
    val quietEndHour: Int = DEFAULT_QUIET_END,
    val criticalMayBypassQuietHours: Boolean = true,
) {
    init {
        require(dailyMax >= 0 && weeklyMax >= 0) { "a cap cannot be negative" }
        require(weeklyMax >= dailyMax) { "a weekly cap below the daily one would make the daily cap a lie" }
        require(windowDays >= 1) { "the window must span at least a day" }
        require(quietStartHour in 0..LAST_HOUR && quietEndHour in 0..LAST_HOUR) { "quiet hours are hours of a day" }
        require(quietStartHour != quietEndHour) { "quiet hours that start when they end are either all day or none" }
    }

    companion object {
        private const val DEFAULT_DAILY_MAX = 2
        private const val DEFAULT_WEEKLY_MAX = 8
        private const val DEFAULT_WINDOW_DAYS = 7
        private const val DEFAULT_QUIET_START = 22
        private const val DEFAULT_QUIET_END = 8
        private const val LAST_HOUR = 23

        /** The rulebook file these values were copied from, as `_meta.version`. */
        const val RULEBOOK_VERSION = "1.22.0"

        /** `RULE-NTF-BUDGET` — NTF-001's caps. */
        val BUDGET = RuleCitation("RULE-NTF-BUDGET", "1.0")

        /** `RULE-NTF-QUIET` — NTF-002's window. */
        val QUIET = RuleCitation("RULE-NTF-QUIET", "1.0")
    }
}
