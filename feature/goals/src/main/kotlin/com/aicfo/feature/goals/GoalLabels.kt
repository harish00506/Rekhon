package com.aicfo.feature.goals

import androidx.annotation.StringRes
import com.aicfo.domain.engines.goals.GoalStatus
import com.aicfo.domain.engines.goals.Horizon

/**
 * Turns the domain's verdicts into words (issue 7.1; §21.6).
 *
 * Why:  the engine returns a [GoalStatus] and a [Horizon], never a sentence — the domain decides
 *       what is true and a feature module decides how to say it, which is what lets the app be
 *       translated without touching `:domain:*`. Keeping the mapping in one `when` also means adding
 *       a status cannot compile until somebody has decided what it says.
 * What: two exhaustive mappings to string resources.
 * Result: one place to argue about tone.
 * Changelog: 2026-08-30 — Created for issue 7.1.
 */
internal object GoalLabels {
    /**
     * Result: the resource for this verdict. Input: [status]. Output: a string resource id.
     *
     * Exhaustive with no `else`, so a new [GoalStatus] fails the build here rather than rendering
     * as a blank line on somebody's goal card.
     */
    @StringRes
    fun status(status: GoalStatus): Int =
        when (status) {
            GoalStatus.ON_TRACK -> R.string.goals_status_on_track
            GoalStatus.BEHIND -> R.string.goals_status_behind
            GoalStatus.PAST_DUE -> R.string.goals_status_past_due
            GoalStatus.OVER_FUNDED -> R.string.goals_status_over_funded
            GoalStatus.NO_TARGET -> R.string.goals_status_no_target
        }

    /**
     * Result: the resource for this funding bucket. Input: [horizon]. Output: a string resource id.
     *
     * The wording states what `RULE-HORIZON` says the money is *eligible* for, never what to buy —
     * the app advises and the user decides (P-07), and §11.1 forbids recommending a security.
     */
    @StringRes
    fun horizon(horizon: Horizon): Int =
        when (horizon) {
            Horizon.SHORT -> R.string.goals_horizon_short
            Horizon.HYBRID -> R.string.goals_horizon_hybrid
            Horizon.LONG -> R.string.goals_horizon_long
        }
}
