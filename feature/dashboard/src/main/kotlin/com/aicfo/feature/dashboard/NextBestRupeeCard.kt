package com.aicfo.feature.dashboard

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.aicfo.core.designsystem.component.CfoCard
import com.aicfo.core.designsystem.component.CfoSecondaryButton
import com.aicfo.core.designsystem.component.maskedAmount
import com.aicfo.core.designsystem.theme.CfoDimens
import com.aicfo.core.model.Money
import com.aicfo.domain.engines.orderofoperations.OrderOfOperations
import com.aicfo.domain.engines.orderofoperations.StageOutcome

/**
 * FOO-002's Home half: the single top waterfall action (issue 7.5; §36, AI-FOO).
 *
 * Why:  §36 says "the Home dashboard ... MUST surface the single top waterfall action". The whole
 *       ranking lives on its own screen; this card is the one line a user sees on payday without
 *       asking — the stage, why it is first, what it would take, and the rule that put it there.
 * What: three states, drawn differently on purpose:
 *       - **not worked out yet** (`null`) — a pending line, never an empty card, because "nothing
 *         shown" is not "nothing to do" (issue 7.4's lesson about empty states);
 *       - **nothing to do** — no stage asks for money; any idle surplus is named;
 *       - **a top action** — its name, its reason, an amount when one is suggested, the rule.
 * Result: the card, always with a way through to the full order.
 * Changelog: 2026-09-17 — Created for issue 7.5.
 *
 * Every amount goes through [maskedAmount], so the privacy blur (issue 5.3) hides this card like
 * every other figure on the dashboard. Nothing is computed here (P-03): the amount, the need and the
 * leftover are the engine's.
 *
 * Input:  [ranking] — the engine's result, or `null` before the first emission; [onOpen] — to the
 *         full-order screen. Output: the composition.
 */
@Composable
internal fun NextBestRupeeCard(
    ranking: OrderOfOperations?,
    onOpen: () -> Unit,
) {
    CfoCard(modifier = Modifier.fillMaxWidth()) {
        Column(verticalArrangement = Arrangement.spacedBy(CfoDimens.spaceSm)) {
            Text(text = stringResource(R.string.dashboard_foo_title), style = MaterialTheme.typography.titleMedium)
            val top = ranking?.topAction
            when {
                ranking == null -> Text(text = stringResource(R.string.dashboard_foo_pending))
                top == null -> NothingToDo(unallocated = ranking.unallocated)
                else -> TopAction(top)
            }
            CfoSecondaryButton(text = stringResource(R.string.dashboard_foo_open), onClick = onOpen)
        }
    }
}

/**
 * The top action itself.
 * Why:    split out of [NextBestRupeeCard] to keep each composable short (§21.6).
 * Result: the stage name, its reason, the suggested amount — or, with no surplus to pour, what the
 *         stage still needs — and the stage's own rule citation (P-02).
 * Input:  [top]. Output: the composition.
 */
@Composable
private fun TopAction(top: StageOutcome) {
    Text(text = stringResource(OrderOfOperationsLabels.stage(top.stage)), style = MaterialTheme.typography.bodyLarge)
    Text(
        text = stringResource(OrderOfOperationsLabels.reason(top.reason)),
        style = MaterialTheme.typography.bodyMedium,
    )
    val need = top.need
    when {
        top.amountMonthly > Money.ZERO ->
            Text(text = stringResource(R.string.dashboard_foo_amount, maskedAmount(top.amountMonthly)))
        need != null && need > Money.ZERO ->
            Text(text = stringResource(R.string.dashboard_foo_need, maskedAmount(need)))
    }
    val citation = top.citations.first()
    Text(
        text = stringResource(R.string.dashboard_reason_rule, citation.ruleId, citation.ruleVersion),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/**
 * No stage asks for money.
 * Result: says so, and names any idle surplus — §36's cue that money is sitting unused.
 * Input:  [unallocated]. Output: the composition.
 */
@Composable
private fun NothingToDo(unallocated: Money) {
    Text(text = stringResource(R.string.dashboard_foo_nothing))
    if (unallocated > Money.ZERO) {
        Text(text = stringResource(R.string.dashboard_foo_idle, maskedAmount(unallocated)))
    }
}
