package com.aicfo.feature.goals

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import com.aicfo.core.designsystem.component.CfoCard
import com.aicfo.core.designsystem.theme.CfoTheme
import com.aicfo.core.model.Money
import com.aicfo.core.model.MoneyFormatter
import com.aicfo.domain.engines.goals.Feasibility
import com.aicfo.domain.engines.goals.GoalAllocation
import com.aicfo.domain.engines.goals.GoalLevers
import com.aicfo.domain.engines.goals.GoalWaterfall
import com.aicfo.domain.engines.goals.SurplusBasis

/**
 * The month's contribution plan, above the goals it divides between (issue 7.3; §15.1, P-02).
 *
 * Why:  until this card existed the screen showed three goals each asking for a monthly and never
 *       said whether the three together were possible. **The verdict is the point, and the gap is
 *       what makes it act on** — "your goals need ₹12,000 a month more than you have spare" is
 *       something a person can do something about; "infeasible" is not.
 * What: the verdict, the surplus with its source named, the emergency-fund claim when
 *       `RULE-EMERG-FIRST` fires, the leftover, and the rule citation.
 * Result: the composition.
 * Changelog: 2026-09-03 — Created for issue 7.3.
 *
 * **Every figure names where it came from** (P-02). The surplus in particular: §15.1 asks for a
 * forecast this app does not have, so the card says "the middle of your last 6 closed months"
 * rather than presenting a number as though it were projected. A user who disagrees with the plan
 * can see which input to argue with.
 */
@Composable
internal fun GoalWaterfallCard(waterfall: GoalWaterfall) {
    CfoCard {
        Text(text = stringResource(R.string.goals_plan_title), style = MaterialTheme.typography.titleMedium)
        Verdict(waterfall)
        SurplusLine(waterfall)
        if (waterfall.emergencyFirstApplied && waterfall.emergencyAllocated > Money.ZERO) {
            Text(
                text =
                    stringResource(
                        R.string.goals_plan_emergency_first,
                        MoneyFormatter.format(waterfall.emergencyAllocated),
                    ),
            )
        }
        if (waterfall.unallocated > Money.ZERO) {
            Text(
                text = stringResource(R.string.goals_plan_leftover, MoneyFormatter.format(waterfall.unallocated)),
                style = MaterialTheme.typography.bodySmall,
            )
        }
        Text(text = stringResource(R.string.goals_plan_rule), style = MaterialTheme.typography.bodySmall)
    }
}

/**
 * §15.1's verdict, in one sentence.
 * Why:    split out to keep [GoalWaterfallCard] inside the 40-line limit (§21.6), and because the
 *         three branches genuinely say different things rather than being one string with a number
 *         in it. `UNKNOWN` in particular must not read as bad news — it is the absence of an answer.
 * Result: the composition. Input: [waterfall]. Output: none.
 */
@Composable
private fun Verdict(waterfall: GoalWaterfall) {
    when (waterfall.feasibility) {
        Feasibility.FEASIBLE ->
            Text(
                text = stringResource(R.string.goals_plan_feasible),
                style = MaterialTheme.typography.bodyLarge,
                color = CfoTheme.extendedColors.positive,
            )
        Feasibility.INFEASIBLE ->
            Text(
                text =
                    stringResource(R.string.goals_plan_infeasible, MoneyFormatter.format(waterfall.gapMonthly)),
                style = MaterialTheme.typography.bodyLarge,
                color = CfoTheme.extendedColors.negative,
            )
        Feasibility.UNKNOWN ->
            Text(text = stringResource(R.string.goals_plan_unknown), style = MaterialTheme.typography.bodyMedium)
    }
}

/**
 * The surplus, and where it came from.
 * Why:    P-02 forbids a figure with no provenance, and this figure has an unusual one worth being
 *         honest about — §15.1 wants a forecast, and this is a median of what has already happened.
 * Result: the composition, or **nothing at all** when the basis is `NONE`: the verdict above has
 *         already said there is not enough history, and repeating it under a missing number would
 *         be noise. Input: [waterfall]. Output: none.
 */
@Composable
private fun SurplusLine(waterfall: GoalWaterfall) {
    val surplus = waterfall.monthlySurplus ?: return
    val text =
        when (waterfall.surplusBasis) {
            SurplusBasis.OBSERVED_MEDIAN ->
                stringResource(R.string.goals_plan_basis_observed, MoneyFormatter.format(surplus))
            SurplusBasis.DECLARED_ENVELOPE ->
                stringResource(R.string.goals_plan_basis_declared, MoneyFormatter.format(surplus))
            SurplusBasis.NONE -> return
        }
    Text(text = text)
}

/**
 * What this plan can give one goal, and the three ways to close the rest (issue 7.3; FR-GOAL-003).
 *
 * Why:    the requirement is explicit that an infeasible plan "shows the gap **and three levers**".
 *         The levers are the difference between the app reporting a problem and the app being
 *         useful — and offering them is advice, never an instruction: the user picks which of the
 *         three moves, or none (P-07).
 * What: the allocation, whether the emergency fund is what is holding it, and the levers.
 * Result: the composition. Input: [line]. Output: none.
 * Changelog: 2026-09-03 — Created for issue 7.3.
 */
@Composable
internal fun GoalAllocationLines(line: GoalAllocation) {
    Column {
        when {
            line.fullyFunded -> Text(text = stringResource(R.string.goals_allocated_full))
            line.blockedByEmergencyFund ->
                Text(
                    text = stringResource(R.string.goals_blocked),
                    color = CfoTheme.extendedColors.negative,
                )
            else ->
                Text(
                    text =
                        stringResource(
                            R.string.goals_allocated,
                            MoneyFormatter.format(line.allocatedMonthly),
                        ),
                )
        }
        line.levers?.let { Levers(levers = it) }
    }
}

/**
 * The three ways out, as sentences (issue 7.3; FR-GOAL-003).
 *
 * Why:    split from [GoalAllocationLines] to keep both inside the 40-line limit (§21.6), and
 *         because the two answer different questions — one says what this plan gives the goal, this
 *         one says what would have to change.
 * What:   each lever, skipped when it does not exist. A null is not "we did not bother": no amount
 *         of time reaches a target at ₹0 a month, and a goal with no month left has nothing to
 *         spread a smaller target over.
 * Result: the composition. Input: [levers]. Output: none.
 * Changelog: 2026-09-03 — Created for issue 7.3.
 */
@Composable
private fun Levers(levers: GoalLevers) {
    val style = MaterialTheme.typography.bodySmall
    Text(text = stringResource(R.string.goals_levers_title), style = style)
    levers.extendByMonths?.let { months ->
        Text(text = pluralStringResource(R.plurals.goals_lever_extend, months, months), style = style)
    }
    levers.reduceTargetTo?.let { target ->
        Text(text = stringResource(R.string.goals_lever_reduce, MoneyFormatter.format(target)), style = style)
    }
    Text(
        text =
            stringResource(
                R.string.goals_lever_increase,
                MoneyFormatter.format(levers.increaseContributionBy),
            ),
        style = style,
    )
}
