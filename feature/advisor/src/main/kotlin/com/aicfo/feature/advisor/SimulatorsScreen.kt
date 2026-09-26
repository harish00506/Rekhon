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
import androidx.compose.ui.text.input.KeyboardType
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aicfo.core.designsystem.component.CfoButton
import com.aicfo.core.designsystem.component.CfoCard
import com.aicfo.core.designsystem.component.CfoSecondaryButton
import com.aicfo.core.designsystem.theme.CfoDimens

/**
 * §36's and §40.2's simulators, on screen (issue 10.3; P-02, P-07).
 *
 * Why:  both answers are comparisons, so both are shown as comparisons — the two figures, the gap
 *       between them, and the point at which the answer would change. The screen ends by saying
 *       that nothing was paid, because a simulator that looked like a payment screen would be a
 *       cruel misunderstanding waiting to happen.
 * What: pick a loan and a sum, or name what is spare each month, and read what follows.
 * Result: arithmetic the user can act on, or ignore.
 * Changelog: 2026-09-26 — Created for issue 10.3.
 *
 * Input:  [onDone]; [viewModel] — injected. Output: the screen.
 */
@Composable
fun SimulatorsScreen(
    onDone: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: SimulatorsViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    SimulatorsContent(uiState, viewModel::onEvent, onDone, modifier)
}

/**
 * The screen as a function of its state (ARC-004).
 * Result: the rendered screen. Input: [uiState]; [onEvent]; [onDone]; [modifier]. Output: none.
 */
@Composable
internal fun SimulatorsContent(
    uiState: SimulatorsUiState,
    onEvent: (SimulatorsEvent) -> Unit,
    onDone: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(CfoDimens.spaceMd),
        verticalArrangement = Arrangement.spacedBy(CfoDimens.spaceMd),
    ) {
        Text(stringResource(R.string.sim_title), style = MaterialTheme.typography.headlineSmall)
        Text(stringResource(R.string.sim_intro), style = MaterialTheme.typography.bodyMedium)
        PrepayCard(uiState, onEvent)
        PayoffCard(uiState, onEvent)
        Text(stringResource(R.string.sim_nothing_moved), style = MaterialTheme.typography.labelSmall)
        CfoSecondaryButton(text = stringResource(R.string.advisor_back), onClick = onDone)
    }
}

/** §36: the loan, the spare money, and the two outcomes. */
@Composable
private fun PrepayCard(
    uiState: SimulatorsUiState,
    onEvent: (SimulatorsEvent) -> Unit,
) {
    CfoCard {
        Column(verticalArrangement = Arrangement.spacedBy(CfoDimens.spaceSm)) {
            Text(stringResource(R.string.sim_prepay_label), style = MaterialTheme.typography.titleSmall)
            if (uiState.loans.isEmpty()) {
                Text(stringResource(R.string.sim_prepay_none), style = MaterialTheme.typography.bodyMedium)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(CfoDimens.spaceSm)) {
                uiState.loans.forEach { loan ->
                    FilterChip(
                        selected = uiState.selectedAccountId == loan.accountId,
                        onClick = { onEvent(SimulatorsEvent.LoanSelected(loan.accountId)) },
                        label = { Text(loan.name) },
                    )
                }
            }
            NumberField(uiState.lumpSumRupees, R.string.sim_lump_label) { onEvent(SimulatorsEvent.LumpSumChanged(it)) }
            NumberField(uiState.expectedReturnPercent, R.string.sim_return_label) {
                onEvent(SimulatorsEvent.ExpectedReturnChanged(it))
            }
            NumberField(uiState.taxPercent, R.string.sim_tax_label) { onEvent(SimulatorsEvent.TaxChanged(it)) }
            CfoButton(
                text = stringResource(if (uiState.isSimulating) R.string.sim_simulating else R.string.sim_prepay_run),
                onClick = { onEvent(SimulatorsEvent.SimulatePrepay) },
                enabled = uiState.canSimulatePrepay && !uiState.isSimulating,
            )
            uiState.prepay?.let { PrepayResult(it) }
        }
    }
}

/** §40.2: what is spare, and the two ways to spend it. */
@Composable
private fun PayoffCard(
    uiState: SimulatorsUiState,
    onEvent: (SimulatorsEvent) -> Unit,
) {
    CfoCard {
        Column(verticalArrangement = Arrangement.spacedBy(CfoDimens.spaceSm)) {
            Text(stringResource(R.string.sim_payoff_label), style = MaterialTheme.typography.titleSmall)
            if (!uiState.canSimulatePayoff) {
                Text(stringResource(R.string.sim_payoff_none), style = MaterialTheme.typography.bodyMedium)
            }
            NumberField(uiState.extraMonthlyRupees, R.string.sim_extra_label) {
                onEvent(SimulatorsEvent.ExtraMonthlyChanged(it))
            }
            CfoButton(
                text = stringResource(if (uiState.isSimulating) R.string.sim_simulating else R.string.sim_payoff_run),
                onClick = { onEvent(SimulatorsEvent.SimulatePayoff) },
                enabled = uiState.canSimulatePayoff && !uiState.isSimulating,
            )
            uiState.payoff?.let { PayoffResult(it) }
        }
    }
}

/** A whole-number field; paise and basis points are the ViewModel's business, not the user's. */
@Composable
private fun NumberField(
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
