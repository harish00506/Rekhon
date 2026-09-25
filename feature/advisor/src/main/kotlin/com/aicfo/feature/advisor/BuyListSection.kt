package com.aicfo.feature.advisor

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import com.aicfo.core.designsystem.component.CfoButton
import com.aicfo.core.designsystem.component.CfoCard
import com.aicfo.core.designsystem.component.CfoSecondaryButton
import com.aicfo.core.designsystem.theme.CfoDimens
import com.aicfo.core.model.MoneyFormatter
import com.aicfo.data.repository.BuyListEntry
import com.aicfo.data.repository.BuyListStatus
import com.aicfo.domain.engines.purchase.InterviewAnswer
import com.aicfo.domain.engines.purchase.InterviewOutcome
import com.aicfo.domain.engines.purchase.InterviewQuestion
import com.aicfo.domain.engines.purchase.PurchaseWeight

/**
 * §13.3's buy list, on screen (issue 10.2; P-02, P-07).
 *
 * Why:  the list's value is that adding to it is cheaper than buying — so adding is two fields and
 *       a button, and the interview arrives one question at a time, only while there is one left to
 *       ask. A wish that scores badly shows the **user's own answers** as the reason, with a tap to
 *       remove and a tap to keep; the app never removes anything itself.
 * What: the add row, each wish with its score and band, the next question, and the three things a
 *       user can do with a wish.
 * Result: a list that argues its case and then does as it is told.
 * Changelog: 2026-09-26 — Created for issue 10.2.
 */
@Composable
internal fun BuyListSection(
    uiState: AdvisorUiState,
    onEvent: (AdvisorEvent) -> Unit,
) {
    CfoCard {
        Column(verticalArrangement = Arrangement.spacedBy(CfoDimens.spaceSm)) {
            Text(stringResource(R.string.buylist_label), style = MaterialTheme.typography.titleSmall)
            Text(stringResource(R.string.buylist_intro), style = MaterialTheme.typography.bodyMedium)
            AddWishRow(uiState, onEvent)
            if (uiState.buyList.isEmpty()) {
                Text(stringResource(R.string.buylist_empty), style = MaterialTheme.typography.bodyMedium)
            }
            uiState.buyList.forEach { wish -> WishRow(wish, onEvent) }
        }
    }
}

/** The two fields and the button that put a wish on the list instead of in a basket. */
@Composable
private fun AddWishRow(
    uiState: AdvisorUiState,
    onEvent: (AdvisorEvent) -> Unit,
) {
    OutlinedTextField(
        value = uiState.wishName,
        onValueChange = { onEvent(AdvisorEvent.WishNameChanged(it)) },
        label = { Text(stringResource(R.string.buylist_name_label)) },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
    OutlinedTextField(
        value = uiState.wishPriceRupees,
        onValueChange = { onEvent(AdvisorEvent.WishPriceChanged(it)) },
        label = { Text(stringResource(R.string.buylist_price_label)) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        modifier = Modifier.fillMaxWidth(),
    )
    CfoButton(
        text = stringResource(R.string.buylist_add),
        onClick = { onEvent(AdvisorEvent.AddWish) },
        enabled = uiState.canAddWish,
    )
}

/** One wish: what it is, where the interview has got to, and what can be done about it. */
@Composable
private fun WishRow(
    wish: BuyListEntry,
    onEvent: (AdvisorEvent) -> Unit,
) {
    Column(modifier = Modifier.padding(vertical = CfoDimens.spaceXs)) {
        Text(
            stringResource(R.string.buylist_row, wish.name, MoneyFormatter.format(wish.price)),
            style = MaterialTheme.typography.bodyLarge,
        )
        Text(
            stringResource(
                R.string.buylist_score,
                wish.assessment.wantScore,
                stringResource(BuyListLabels.outcome(wish.assessment.outcome)),
            ),
            style = MaterialTheme.typography.bodySmall,
        )
        Text(stringResource(BuyListLabels.weight(wish.assessment.weight)), style = MaterialTheme.typography.labelSmall)
        if (wish.assessment.coolingOffRequired) {
            Text(
                pluralStringResource(
                    R.plurals.buylist_cooloff,
                    wish.assessment.coolingOffHours,
                    wish.assessment.coolingOffHours,
                ),
                style = MaterialTheme.typography.bodySmall,
            )
        }
        wish.assessment.questionsToAsk.firstOrNull()?.let { question -> NextQuestion(wish.id, question, onEvent) }
        if (wish.assessment.outcome == InterviewOutcome.SUGGEST_REMOVE) Evidence(wish)
        WishActions(wish, onEvent)
    }
}

/**
 * The next unanswered question, and nothing else.
 * Why:    one at a time, because §13.3's ladder is about how much is asked in total — a wall of
 *         seven questions would be the friction the ladder exists to ration.
 */
@Composable
private fun NextQuestion(
    itemId: String,
    question: InterviewQuestion,
    onEvent: (AdvisorEvent) -> Unit,
) {
    Text(stringResource(BuyListLabels.question(question)), style = MaterialTheme.typography.bodyMedium)
    // A column, not a row: three answers ("Most days · A few times a month · Rarely") do not fit
    // across a phone, and the emulator run found the third one clipped off the edge — invisible and
    // unanswerable. Stacking also gives each answer a full-width target (§25's 48dp minimum).
    Column(verticalArrangement = Arrangement.spacedBy(CfoDimens.spaceXs)) {
        BuyListLabels.answers(question).forEach { (labelRes, answer) ->
            CfoSecondaryButton(
                text = stringResource(labelRes),
                onClick = { onEvent(AdvisorEvent.AnswerWish(itemId, answer)) },
            )
        }
    }
}

/** §13.3.2's "with the user's own answers as evidence" — the reason a wish is questioned. */
@Composable
private fun Evidence(wish: BuyListEntry) {
    Text(stringResource(R.string.buylist_because), style = MaterialTheme.typography.labelSmall)
    wish.assessment.deltas.forEach { delta ->
        BuyListLabels.reason(delta.reason)?.let { reasonRes ->
            Text(
                stringResource(R.string.buylist_reason_points, stringResource(reasonRes), delta.points),
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

/** The three things a user can do with a wish. Removing is always their tap (§13.3, P-07). */
@Composable
private fun WishActions(
    wish: BuyListEntry,
    onEvent: (AdvisorEvent) -> Unit,
) {
    // Stacked for the same reason the answers are: three labelled buttons overflow a phone's width.
    Column(verticalArrangement = Arrangement.spacedBy(CfoDimens.spaceXs)) {
        CfoSecondaryButton(
            text = stringResource(R.string.buylist_check),
            onClick = { onEvent(AdvisorEvent.AdviseWish(wish.id)) },
        )
        CfoSecondaryButton(
            text = stringResource(R.string.buylist_remove),
            onClick = { onEvent(AdvisorEvent.MoveWish(wish.id, BuyListStatus.REMOVED)) },
        )
        CfoSecondaryButton(
            text = stringResource(R.string.buylist_bought),
            onClick = { onEvent(AdvisorEvent.MoveWish(wish.id, BuyListStatus.BOUGHT)) },
        )
    }
}

/**
 * The words for the interview's findings (issue 10.2; §21.6).
 *
 * Why:  the engine names an outcome `SUGGEST_REMOVE` and a reason `owns_similar`; the screen says
 *       "Maybe drop this one" and "you already own something that does this". One place for that
 *       mapping, so a new answer is a compile error here rather than a blank line on a phone.
 * Changelog: 2026-09-26 — Created for issue 10.2.
 */
internal object BuyListLabels {
    /** Result: the outcome's words. Input: [outcome]. */
    @StringRes
    fun outcome(outcome: InterviewOutcome): Int =
        when (outcome) {
            InterviewOutcome.KEEP -> R.string.buylist_outcome_keep
            InterviewOutcome.PARK -> R.string.buylist_outcome_park
            InterviewOutcome.SUGGEST_REMOVE -> R.string.buylist_outcome_suggest_remove
        }

    /** Result: how heavy the purchase is, in the user's terms. Input: [weight]. */
    @StringRes
    fun weight(weight: PurchaseWeight): Int =
        when (weight) {
            PurchaseWeight.CASUAL -> R.string.buylist_weight_casual
            PurchaseWeight.SMALL -> R.string.buylist_weight_small
            PurchaseWeight.SIGNIFICANT -> R.string.buylist_weight_significant
            PurchaseWeight.MAJOR -> R.string.buylist_weight_major
            PurchaseWeight.HEAVY -> R.string.buylist_weight_heavy
        }

    /** Result: the question, as asked. Input: [question]. */
    @StringRes
    fun question(question: InterviewQuestion): Int =
        when (question) {
            InterviewQuestion.NEED_OR_WANT -> R.string.buylist_q_need
            InterviewQuestion.USES_PER_MONTH -> R.string.buylist_q_uses
            InterviewQuestion.ALREADY_OWN_SIMILAR -> R.string.buylist_q_owns
            InterviewQuestion.WAIT_THIRTY_DAYS -> R.string.buylist_q_wait
            InterviewQuestion.GOAL_DELAY_ACCEPTABLE -> R.string.buylist_q_goal_delay
            InterviewQuestion.TOTAL_COST_OF_OWNERSHIP -> R.string.buylist_q_owning_cost
            InterviewQuestion.TIMING_FLEXIBLE -> R.string.buylist_q_timing
            InterviewQuestion.COOLING_OFF_ACKNOWLEDGED -> R.string.buylist_q_cooloff
            InterviewQuestion.STILL_WANTED -> R.string.buylist_q_still_wanted
        }

    /**
     * Result: the answers offered for a question, each with the words on its button.
     * Input:  [question]. Output: label and answer, in the order shown.
     */
    fun answers(question: InterviewQuestion): List<Pair<Int, InterviewAnswer>> =
        when (question) {
            InterviewQuestion.NEED_OR_WANT ->
                listOf(
                    R.string.buylist_a_need to InterviewAnswer.NeedOrWant(isNeed = true),
                    R.string.buylist_a_want to InterviewAnswer.NeedOrWant(isNeed = false),
                )
            InterviewQuestion.USES_PER_MONTH ->
                listOf(
                    R.string.buylist_a_uses_high to InterviewAnswer.Uses(InterviewAnswer.UsesPerMonth.HIGH),
                    R.string.buylist_a_uses_medium to InterviewAnswer.Uses(InterviewAnswer.UsesPerMonth.MEDIUM),
                    R.string.buylist_a_uses_low to InterviewAnswer.Uses(InterviewAnswer.UsesPerMonth.LOW),
                )
            InterviewQuestion.ALREADY_OWN_SIMILAR -> yesNo { InterviewAnswer.AlreadyOwns(ownsSimilar = it) }
            InterviewQuestion.WAIT_THIRTY_DAYS ->
                listOf(
                    R.string.buylist_a_wait_breaks to InterviewAnswer.WaitThirtyDays(somethingBreaks = true),
                    R.string.buylist_a_wait_fine to InterviewAnswer.WaitThirtyDays(somethingBreaks = false),
                )
            InterviewQuestion.GOAL_DELAY_ACCEPTABLE -> yesNo { InterviewAnswer.GoalDelay(accepted = it) }
            InterviewQuestion.TIMING_FLEXIBLE -> yesNo { InterviewAnswer.TimingFlexible(canWait = it) }
            InterviewQuestion.COOLING_OFF_ACKNOWLEDGED -> yesNo { InterviewAnswer.CoolingOff(acknowledged = it) }
            InterviewQuestion.STILL_WANTED -> yesNo { InterviewAnswer.StillWanted(stillWanted = it) }
            // Owning costs need an amount, not a tap; the field for it waits for a later issue
            // (ADR-0050), so the question is never the next one offered.
            InterviewQuestion.TOTAL_COST_OF_OWNERSHIP -> emptyList()
        }

    /** Result: the words for one scored answer, or `null` for one the screen does not quote. */
    @StringRes
    fun reason(reason: String): Int? =
        when (reason) {
            "need" -> R.string.buylist_reason_need
            "want" -> R.string.buylist_reason_want
            "uses_high" -> R.string.buylist_reason_uses_high
            "uses_medium" -> R.string.buylist_reason_uses_medium
            "uses_low" -> R.string.buylist_reason_uses_low
            "owns_similar" -> R.string.buylist_reason_owns_similar
            "owns_nothing_similar" -> R.string.buylist_reason_owns_nothing_similar
            "waiting_breaks_something" -> R.string.buylist_reason_waiting_breaks_something
            "waiting_breaks_nothing" -> R.string.buylist_reason_waiting_breaks_nothing
            "goal_delay_accepted" -> R.string.buylist_reason_goal_delay_accepted
            "goal_delay_refused" -> R.string.buylist_reason_goal_delay_refused
            "still_wanted_after_30_days" -> R.string.buylist_reason_still_wanted
            else -> null
        }

    /** Result: a yes/no pair for a question whose answer is a boolean. Input: [build]. */
    private fun yesNo(build: (Boolean) -> InterviewAnswer): List<Pair<Int, InterviewAnswer>> =
        listOf(R.string.buylist_a_yes to build(true), R.string.buylist_a_no to build(false))
}
