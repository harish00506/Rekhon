package com.aicfo.feature.advisor

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.tooling.preview.Preview
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aicfo.core.designsystem.component.CfoButton
import com.aicfo.core.designsystem.component.CfoCard
import com.aicfo.core.designsystem.component.CfoSecondaryButton
import com.aicfo.core.designsystem.theme.CfoDimens
import com.aicfo.core.designsystem.theme.CfoTheme
import com.aicfo.domain.engines.purchase.PaymentMethod
import com.aicfo.domain.engines.purchase.Urgency

/**
 * §13's Purchase Advisor, on screen (issue 10.1; §13.2, FR-AI-003, P-02, P-07).
 *
 * Why:  the verdict is one word, and the reason it can be trusted is everything under it — the gate
 *       table with its figures, what the purchase moves, and what would change the answer. The
 *       screen renders those; it decides nothing and computes nothing (P-03).
 * What: the question at the top, the card beneath it, and the history of past verdicts below that.
 * Result: an answer the user can argue with.
 * Changelog: 2026-09-25 — Created for issue 10.1.
 *
 * Input:  [onDone] — leaves the screen; [viewModel] — injected. Output: the screen.
 */
@Composable
fun AdvisorScreen(
    onDone: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: AdvisorViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    AdvisorContent(uiState, viewModel::onEvent, onDone, modifier)
}

/**
 * The screen as a function of its state (ARC-004).
 * Result: the rendered screen. Input: [uiState]; [onEvent]; [onDone]; [modifier]. Output: none.
 */
@Composable
internal fun AdvisorContent(
    uiState: AdvisorUiState,
    onEvent: (AdvisorEvent) -> Unit,
    onDone: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(CfoDimens.spaceMd),
        verticalArrangement = Arrangement.spacedBy(CfoDimens.spaceMd),
    ) {
        Text(stringResource(R.string.advisor_title), style = MaterialTheme.typography.headlineSmall)
        Text(stringResource(R.string.advisor_intro), style = MaterialTheme.typography.bodyMedium)

        QuestionCard(uiState, onEvent)
        uiState.errorCode?.let { ErrorCard(onEvent) }
        uiState.card?.let { VerdictCard(it) }
        HistoryCard(uiState, onEvent)

        CfoSecondaryButton(text = stringResource(R.string.advisor_back), onClick = onDone)
    }
}

/** The question: what, how much, how paid for, how soon. */
@Composable
private fun QuestionCard(
    uiState: AdvisorUiState,
    onEvent: (AdvisorEvent) -> Unit,
) {
    CfoCard {
        Column(verticalArrangement = Arrangement.spacedBy(CfoDimens.spaceSm)) {
            OutlinedTextField(
                value = uiState.item,
                onValueChange = { onEvent(AdvisorEvent.ItemChanged(it)) },
                label = { Text(stringResource(R.string.advisor_item_label)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            RupeeField(uiState.priceRupees, R.string.advisor_price_label) { onEvent(AdvisorEvent.PriceChanged(it)) }
            MethodChips(uiState, onEvent)
            if (uiState.method == PaymentMethod.EMI) {
                RupeeField(
                    uiState.monthlyEmiRupees,
                    R.string.advisor_emi_label,
                ) { onEvent(AdvisorEvent.EmiChanged(it)) }
            }
            UrgencyChips(uiState, onEvent)
            CfoButton(
                text =
                    stringResource(if (uiState.isAsking) R.string.advisor_asking else R.string.advisor_ask),
                onClick = { onEvent(AdvisorEvent.Ask) },
                enabled = uiState.canAsk && !uiState.isAsking,
            )
        }
    }
}

/** A whole-rupee field. The ViewModel keeps only the digits; paise are its business, not the user's. */
@Composable
private fun RupeeField(
    value: String,
    labelRes: Int,
    onChange: (String) -> Unit,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(stringResource(labelRes)) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        modifier = Modifier.fillMaxWidth(),
    )
}

/** How it would be paid for — §13.1's gates 1 and 3 read this. */
@Composable
private fun MethodChips(
    uiState: AdvisorUiState,
    onEvent: (AdvisorEvent) -> Unit,
) {
    Text(stringResource(R.string.advisor_method_label), style = MaterialTheme.typography.labelLarge)
    Row(horizontalArrangement = Arrangement.spacedBy(CfoDimens.spaceSm)) {
        PaymentMethod.entries.forEach { method ->
            FilterChip(
                selected = uiState.method == method,
                onClick = { onEvent(AdvisorEvent.MethodChanged(method)) },
                label = { Text(stringResource(AdvisorLabels.method(method))) },
            )
        }
    }
}

/** How much it can wait — what §13's verdict may be softened by. */
@Composable
private fun UrgencyChips(
    uiState: AdvisorUiState,
    onEvent: (AdvisorEvent) -> Unit,
) {
    Text(stringResource(R.string.advisor_urgency_label), style = MaterialTheme.typography.labelLarge)
    Row(horizontalArrangement = Arrangement.spacedBy(CfoDimens.spaceSm)) {
        Urgency.entries.forEach { urgency ->
            FilterChip(
                selected = uiState.urgency == urgency,
                onClick = { onEvent(AdvisorEvent.UrgencyChanged(urgency)) },
                label = { Text(stringResource(AdvisorLabels.urgency(urgency))) },
            )
        }
    }
}

/** The verdicts already given — §13.2's "revisit why a past decision was made". */
@Composable
private fun HistoryCard(
    uiState: AdvisorUiState,
    onEvent: (AdvisorEvent) -> Unit,
) {
    CfoCard {
        Column(verticalArrangement = Arrangement.spacedBy(CfoDimens.spaceXs)) {
            Text(stringResource(R.string.advisor_history_label), style = MaterialTheme.typography.titleSmall)
            if (uiState.history.isEmpty()) {
                Text(stringResource(R.string.advisor_history_empty), style = MaterialTheme.typography.bodyMedium)
            }
            uiState.history.forEach { kept ->
                CfoSecondaryButton(
                    text =
                        stringResource(
                            R.string.advisor_history_row,
                            kept.item,
                            stringResource(AdvisorLabels.verdict(kept.verdict)),
                        ),
                    onClick = { onEvent(AdvisorEvent.OpenKept(kept.id)) },
                )
            }
        }
    }
}

/** The error banner: what failed, and that nothing changed. */
@Composable
private fun ErrorCard(onEvent: (AdvisorEvent) -> Unit) {
    CfoCard {
        Column(verticalArrangement = Arrangement.spacedBy(CfoDimens.spaceSm)) {
            Text(stringResource(R.string.advisor_error), style = MaterialTheme.typography.bodyMedium)
            CfoSecondaryButton(
                text = stringResource(R.string.advisor_error_dismiss),
                onClick = { onEvent(AdvisorEvent.DismissError) },
            )
        }
    }
}

/** A preview of the empty screen, before anything is asked. */
@Preview(showBackground = true)
@Composable
private fun AdvisorEmptyPreview() {
    CfoTheme {
        Column(modifier = Modifier.clearAndSetSemantics { }) {
            AdvisorContent(AdvisorUiState(item = "Headphones", priceRupees = "8000"), {}, {})
        }
    }
}
