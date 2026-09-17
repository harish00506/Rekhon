package com.aicfo.feature.dashboard

import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.res.stringResource
import com.aicfo.domain.engines.orderofoperations.FooStage
import com.aicfo.domain.engines.orderofoperations.StageReason
import com.aicfo.domain.engines.orderofoperations.StageStatus

/**
 * The words for AI-FOO's enums (issue 7.5; §36, §21.6).
 *
 * Why:  `:domain:engines:orderofoperations` is pure Kotlin and holds no user-visible copy (ARC-002) —
 *       the engine decides *what is true* and names it with an enum; this file names it in the
 *       user's language. Every mapping is an exhaustive `when`, so a stage, status or reason added
 *       later fails to compile here rather than rendering as a blank line on the screen that exists
 *       to explain the advice (P-02).
 * What: one `@StringRes` lookup per enum, shared by the dashboard card and the full-order screen so
 *       the two never word the same stage differently.
 * Result: resource ids. Changelog: 2026-09-17 — Created for issue 7.5.
 *
 * **No rule's number appears in any of these strings.** "Less than your minimum runway", not "less
 * than three months": the three is `RULE-EMERG-FIRST`'s, and copying it into a sentence would be a
 * second, unchecked mirror of it (ADR-0035) that stays wrong the day the row changes.
 */
internal object OrderOfOperationsLabels {
    /** Result: the stage's short name. Input: [stage]. Output: a string resource id. */
    @StringRes
    fun stage(stage: FooStage): Int =
        when (stage) {
            FooStage.STARTER_BUFFER -> R.string.foo_stage_starter_buffer
            FooStage.CAPTURE_EPF_VPF -> R.string.foo_stage_capture_epf_vpf
            FooStage.KILL_FIRE_DEBT -> R.string.foo_stage_kill_fire_debt
            FooStage.FULL_EMERGENCY -> R.string.foo_stage_full_emergency
            FooStage.TAX_ADVANTAGED -> R.string.foo_stage_tax_advantaged
            FooStage.GOAL_INVESTING -> R.string.foo_stage_goal_investing
            FooStage.GREY_ZONE_DEBT -> R.string.foo_stage_grey_zone_debt
            FooStage.LOW_RATE_DEBT -> R.string.foo_stage_low_rate_debt
        }

    /** Result: what the stage asks of the user, as a short label. Input: [status]. Output: an id. */
    @StringRes
    fun status(status: StageStatus): Int =
        when (status) {
            StageStatus.ACTION -> R.string.foo_status_action
            StageStatus.SATISFIED -> R.string.foo_status_satisfied
            StageStatus.BLOCKED -> R.string.foo_status_blocked
            StageStatus.SKIPPED -> R.string.foo_status_skipped
            StageStatus.NOT_APPLICABLE -> R.string.foo_status_not_applicable
            StageStatus.CHOICE -> R.string.foo_status_choice
            StageStatus.DEFER_TO_SIMULATOR -> R.string.foo_status_defer
        }

    /** Result: why the stage came out as it did, as a sentence. Input: [reason]. Output: an id. */
    @StringRes
    @Suppress("CyclomaticComplexMethod") // One arm per reason; the `when` is the table.
    fun reason(reason: StageReason): Int =
        when (reason) {
            StageReason.BUFFER_SHORT -> R.string.foo_reason_buffer_short
            StageReason.BUFFER_SHORT_ESSENTIALS_UNKNOWN -> R.string.foo_reason_buffer_short_unknown
            StageReason.BUFFER_HELD -> R.string.foo_reason_buffer_held
            StageReason.NO_EPF_DATA -> R.string.foo_reason_no_epf_data
            StageReason.FIRE_DEBT_OUTSTANDING -> R.string.foo_reason_fire_debt
            StageReason.FIRE_DEBT_CARD_RATE_UNKNOWN -> R.string.foo_reason_fire_debt_rate_unknown
            StageReason.NO_FIRE_DEBT -> R.string.foo_reason_no_fire_debt
            StageReason.EMERGENCY_UNSIZED -> R.string.foo_reason_emergency_unsized
            StageReason.EMERGENCY_BELOW_GATE -> R.string.foo_reason_emergency_below_gate
            StageReason.EMERGENCY_BUILDING -> R.string.foo_reason_emergency_building
            StageReason.EMERGENCY_FUNDED -> R.string.foo_reason_emergency_funded
            StageReason.EMERGENCY_GATE -> R.string.foo_reason_emergency_gate
            StageReason.NO_REGIME_COMPARATOR -> R.string.foo_reason_no_regime_comparator
            StageReason.GOALS_NEED_FUNDING -> R.string.foo_reason_goals_need_funding
            StageReason.GOALS_ON_TRACK -> R.string.foo_reason_goals_on_track
            StageReason.NO_GOALS -> R.string.foo_reason_no_goals
            StageReason.GREY_DEBT_OUTSTANDING -> R.string.foo_reason_grey_debt
            StageReason.NO_GREY_DEBT -> R.string.foo_reason_no_grey_debt
            StageReason.LOW_RATE_DEBT_SIMULATOR_NOT_BUILT -> R.string.foo_reason_low_rate_debt
            StageReason.NO_LOW_RATE_DEBT -> R.string.foo_reason_no_low_rate_debt
        }
}

/**
 * A rate in basis points as a percentage with two decimals — `1350` → `13.50%` (MNY-002).
 *
 * Why:  the stage boundaries are drawn to the basis point, so rounding to a whole or a tenth of a
 *       percent would show a 13.49% loan and a 13.50% one as the same rate on either side of the
 *       fire threshold. Integer arithmetic only: no `Double` touches the figure (AI-ARC-004).
 * Result: the formatted rate. Input: [bps] — never negative here; rates are validated upstream.
 * Output: [String]. Changelog: 2026-09-17 — Created for issue 7.5.
 */
@Composable
@ReadOnlyComposable
internal fun ratePercent(bps: Int): String =
    stringResource(R.string.foo_rate_percent, bps / BPS_PER_PERCENT, bps % BPS_PER_PERCENT)

/** 100 bps = 1% (MNY-002). */
private const val BPS_PER_PERCENT = 100
