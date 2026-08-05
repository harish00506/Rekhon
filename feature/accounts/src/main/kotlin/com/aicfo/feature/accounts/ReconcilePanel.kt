package com.aicfo.feature.accounts

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import com.aicfo.core.designsystem.component.CfoAmountText
import com.aicfo.core.designsystem.component.CfoButton
import com.aicfo.core.designsystem.component.CfoCard
import com.aicfo.core.designsystem.component.CfoSecondaryButton
import com.aicfo.core.designsystem.theme.CfoDimens
import com.aicfo.core.model.Money
import com.aicfo.core.model.MoneyFormatter

/**
 * FR-ACC-006's reconciliation panel (issue 2.7; P-02, ARC-004).
 *
 * Why:  balances in this app are derived, never stated — the editor has no field for one, on
 *       purpose (DB-001, ADR-0007). When the app and the bank disagree, the sanctioned fix is to
 *       post an **adjustment transaction**, and this is where the user decides to post one.
 *
 *       **Everything P-02 asks for is on screen before the user commits** — what the app holds,
 *       what they typed, the difference between them, and the rule that produced it, spelled out
 *       rather than implied. The adjustment becomes a permanent row in their history; a control
 *       offering only "Confirm" would be asking them to sign something unread.
 * What: the current balance, the statement field, the live adjustment, and the two actions.
 * Result: a correction the user can see the whole of before it is written.
 * Changelog: 2026-08-02 — Created for issue 2.7.
 *
 * **An inline card, not a modal dialog** — this began as an `AlertDialog` and was changed for two
 * reasons that pointed the same way. A `Dialog` opens its own window, which Robolectric never
 * drives to idle, so all four rendered tests hung for sixty seconds each and the screen would have
 * been covered only when an emulator happened to be attached. And a modal holding a text field,
 * five lines of explanation and two buttons is exactly the shape that gets clipped at 200% font —
 * the class of defect issue 2.5 found on a device and could not reproduce in a test. Inline, it
 * scrolls with the page and is checked on every `unitTests` run.
 *
 * **No money maths here.** [ReconcileState.delta] does the subtraction with `Money`'s
 * overflow-checked arithmetic, and the repository does it again — authoritatively — against a
 * freshly derived balance before writing. This composable only renders (P-03).
 *
 * Input:  [state] — the open panel's state; [onEvent] — events up (ARC-004).
 * Output: the rendered panel.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ReconcilePanel(
    state: ReconcileState,
    onEvent: (AccountsEvent) -> Unit,
) {
    val delta = state.delta
    CfoCard {
        Column(
            // Announced when it opens: the user tapped a row's button and the panel appears
            // elsewhere on the page, which a screen reader would otherwise pass over in silence.
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
            verticalArrangement = Arrangement.spacedBy(CfoDimens.spaceSm),
        ) {
            Text(
                text = stringResource(R.string.accounts_reconcile_title, state.account.name),
                style = MaterialTheme.typography.titleMedium,
            )
            FigureRow(
                label = stringResource(R.string.accounts_reconcile_app_balance),
                amount = state.account.balance,
            )
            StatementField(text = state.statementText, onEvent = onEvent)
            if (delta != null) {
                FigureRow(label = stringResource(R.string.accounts_reconcile_adjustment), amount = delta)
            }
            Trace(delta = delta)
            // FlowRow for the same reason `AccountRow`'s actions use one: at 200% font two labelled
            // buttons do not share a line, and a plain Row pushes the second off the right edge.
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(CfoDimens.spaceSm),
                verticalArrangement = Arrangement.spacedBy(CfoDimens.spaceXs),
            ) {
                CfoButton(
                    text = stringResource(R.string.accounts_reconcile_confirm),
                    onClick = { onEvent(AccountsEvent.ConfirmReconcile) },
                    enabled = state.canConfirm,
                )
                CfoSecondaryButton(
                    text = stringResource(R.string.accounts_reconcile_cancel),
                    onClick = { onEvent(AccountsEvent.CancelReconcile) },
                )
            }
        }
    }
}

/**
 * The one thing the user has to supply: the balance printed on their statement.
 * Why:    split from [ReconcilePanel] to keep it inside detekt's 40-line function limit. The help
 *         line travels with the field rather than sitting loose in the panel, because it is about
 *         *this input* — specifically the sign convention, which is the mistake a user reconciling
 *         a credit card is most likely to make.
 * Result: the composition.
 * Input:  [text] — the statement as typed; [onEvent] — events up (ARC-004). Output: none.
 * Changelog: 2026-08-02 — Created for issue 2.7.
 */
@Composable
private fun StatementField(
    text: String,
    onEvent: (AccountsEvent) -> Unit,
) {
    OutlinedTextField(
        value = text,
        onValueChange = { onEvent(AccountsEvent.StatementChanged(it)) },
        label = { Text(stringResource(R.string.accounts_reconcile_statement)) },
        // A decimal keypad, not a number pad: a card's balance is entered negative and
        // KeyboardType.Number omits the minus sign on most IMEs. Same call the editor's
        // opening-balance field makes, for the same reason.
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
    Text(
        text = stringResource(R.string.accounts_reconcile_statement_help),
        style = MaterialTheme.typography.bodySmall,
    )
}

/**
 * P-02's two lines: the rule that fired, and what confirming will actually do.
 *
 * Why:    "show the work" is not satisfied by the numbers alone — a user looking at three amounts
 *         still has to guess how the third came from the first two, and whether pressing the button
 *         adds one row or none. Both sentences are stated rather than implied. Split from
 *         [ReconcilePanel] to keep it inside detekt's 40-line function limit without deleting the
 *         reasoning above them.
 *
 *         **The rule line is always shown; the outcome line only once there is a delta.** Found by
 *         driving the app: on an untouched form the panel announced "this adds one adjustment
 *         transaction" while Confirm was disabled and nothing could be added — a promise about an
 *         action the screen was simultaneously refusing. No unit test caught it, because every one
 *         asserted a state where an amount had already been typed.
 * Result: the composition.
 * Input:  [delta] — the previewed adjustment, `null` while the statement is not yet an amount.
 * Output: none.
 * Changelog: 2026-08-02 — Created for issue 2.7.
 */
@Composable
private fun Trace(delta: Money?) {
    Text(
        text = stringResource(R.string.accounts_reconcile_rule),
        style = MaterialTheme.typography.bodySmall,
    )
    if (delta == null) return
    Text(
        // Says plainly which of the two things is about to happen. A user who has typed the figure
        // the app already holds should not be left wondering whether confirming will duplicate it.
        text =
            stringResource(
                if (delta == Money.ZERO) {
                    R.string.accounts_reconcile_in_step
                } else {
                    R.string.accounts_reconcile_explain
                },
            ),
        style = MaterialTheme.typography.bodySmall,
    )
}

/**
 * One labelled figure in the panel.
 * Why:    the label and the amount have to stay on one line together, and [CfoAmountText] already
 *         owns the formatting, the explicit sign and the no-wrap rule — re-implementing any of that
 *         here is how two screens come to render the same amount differently.
 * Result: the composition.
 * Input:  [label] — localised wording; [amount] — MNY-001 paise. Output: none.
 * Changelog: 2026-08-02 — Created for issue 2.7.
 */
@Composable
private fun FigureRow(
    label: String,
    amount: Money,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(text = label, style = MaterialTheme.typography.bodyMedium)
        // The label is the screen reader's context; without it the amount is announced bare. The
        // wording goes through MoneyFormatter, never Money's toString, which would read out
        // "Money(minor=42350)" to anyone using TalkBack.
        CfoAmountText(amount = amount, contentDescription = "$label ${MoneyFormatter.format(amount)}")
    }
}
