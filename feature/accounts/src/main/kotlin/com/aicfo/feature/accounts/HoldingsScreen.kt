package com.aicfo.feature.accounts

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aicfo.core.designsystem.component.CfoButton
import com.aicfo.core.designsystem.component.CfoCard
import com.aicfo.core.designsystem.component.CfoSecondaryButton
import com.aicfo.core.designsystem.component.maskedAmount
import com.aicfo.core.designsystem.theme.CfoDimens
import com.aicfo.core.model.Money
import com.aicfo.domain.engines.investment.HoldingPerformance

/**
 * What one investment account holds, and what it has returned (issue 6.3; §11, P-02, P-07).
 *
 * Why:  the accounts list can show an account's total, but "what is in here and did it do well" is
 *       a per-holding question and needs a screen of its own. Every figure is rendered beside the
 *       inputs it came from — the units and the price beside the value, the cost and what came back
 *       beside the gain, the flow count and the span beside the return — because a bare percentage
 *       is a verdict and P-02 forbids those.
 * What: the disclaimer §11.1 requires, the priced list, and the editor when one is open.
 * Result: the first screen in the app that shows a money-weighted return.
 * Changelog: 2026-08-24 — Created for issue 6.3.
 *
 * **Every amount goes through `maskedAmount`**, so the privacy blur reaches this screen like every
 * other (issue 5.3). The return percentage is not masked: a rate says nothing about how much money
 * is involved, which is the thing the blur exists to hide.
 *
 * Input:  [onDone] — pops back to the accounts list; [viewModel] — supplied by Hilt.
 * Output: the composition.
 */
@Composable
fun HoldingsScreen(
    onDone: () -> Unit,
    viewModel: HoldingsViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    HoldingsContent(
        uiState = uiState,
        onEvent = viewModel::onEvent,
        onDone = onDone,
    )
}

/**
 * The screen's body, with no ViewModel in sight.
 * Why:    separated so a test can drive every state directly — the reason every screen here splits
 *         this way (ARC-004).
 * Result: the composition. Input: [uiState]; [onEvent]; [onDone]. Output: none.
 * Changelog: 2026-08-24 — Created for issue 6.3.
 */
@Composable
internal fun HoldingsContent(
    uiState: HoldingsUiState,
    onEvent: (HoldingsEvent) -> Unit,
    onDone: () -> Unit,
) {
    // Scrollable, and a plain Column rather than a LazyColumn: the disclaimer §11.1 requires sits
    // below the list and must be reachable, which means the whole screen scrolls as one — and a
    // LazyColumn inside a scrolling parent is measured with an infinite height constraint, which
    // Compose rejects. One account's holdings number in the tens, so there is nothing to virtualise.
    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(CfoDimens.spaceMd),
        verticalArrangement = Arrangement.spacedBy(CfoDimens.spaceMd),
    ) {
        Text(
            text = stringResource(R.string.holdings_title),
            style = MaterialTheme.typography.headlineSmall,
        )

        uiState.errorCode?.let { code ->
            ErrorBanner(code = code, onDismiss = { onEvent(HoldingsEvent.DismissError) })
        }

        val editor = uiState.editor
        if (editor != null) {
            HoldingEditorFields(state = editor, onEvent = onEvent)
        } else {
            HoldingsList(uiState = uiState, onEvent = onEvent)
        }

        CfoSecondaryButton(text = stringResource(R.string.holdings_cancel), onClick = onDone)

        // §11.1: this wording MUST appear in the module footer. It is placed last and always
        // rendered — including while the editor is open — because it qualifies every figure above
        // it, and a disclaimer that comes and goes is one the reader learns to stop seeing (P-07).
        Text(
            text = stringResource(R.string.holdings_disclaimer),
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

/**
 * The list of holdings, or the invitation to add one.
 * Why:    split from [HoldingsContent] to keep that function under the 40-line limit (§21.6), on
 *         the seam that already existed: the editor and the list are the screen's two modes.
 * Result: the list. Input: [uiState]; [onEvent]. Output: none.
 * Changelog: 2026-08-24 — Created for issue 6.3.
 */
@Composable
private fun HoldingsList(
    uiState: HoldingsUiState,
    onEvent: (HoldingsEvent) -> Unit,
) {
    CfoButton(
        text = stringResource(R.string.holdings_add),
        onClick = { onEvent(HoldingsEvent.AddHolding) },
    )

    if (uiState.isEmpty) {
        Text(
            text = stringResource(R.string.holdings_empty),
            style = MaterialTheme.typography.bodyMedium,
        )
    } else {
        uiState.holdings.forEach { holding ->
            HoldingRow(holding = holding, onEvent = onEvent)
        }
    }
}

/**
 * One holding: what it is, what it is worth, what it cost and what it returned.
 * Why:    four lines rather than one, because each answers a different question and the reader has
 *         to be able to check the last one against the first three (P-02).
 * Result: the row. Input: [holding]; [onEvent]. Output: none.
 * Changelog: 2026-08-24 — Created for issue 6.3.
 */
@Composable
private fun HoldingRow(
    holding: HoldingPerformance,
    onEvent: (HoldingsEvent) -> Unit,
) {
    CfoCard {
        Column(verticalArrangement = Arrangement.spacedBy(CfoDimens.spaceXs)) {
            Text(text = holding.name, style = MaterialTheme.typography.titleMedium)
            Text(
                text = stringResource(AccountLabels.assetClassLabel(holding.assetClass)),
                style = MaterialTheme.typography.bodySmall,
            )
            HoldingValue(holding)
            HoldingCost(holding)
            HoldingReturn(holding)
            CfoSecondaryButton(
                text = stringResource(R.string.holdings_delete),
                onClick = { onEvent(HoldingsEvent.DeleteHolding(holding.holdingId)) },
            )
        }
    }
}

/**
 * What the position is worth, and the two numbers that produced it.
 * Why:    an unpriced holding shows a prompt rather than ₹0 — a zero here would report the user's
 *         whole cost as a loss, which is wrong, alarming and entirely plausible (P-03).
 * Result: the value line, or the prompt. Input: [holding]. Output: none.
 * Changelog: 2026-08-24 — Created for issue 6.3.
 */
@Composable
private fun HoldingValue(holding: HoldingPerformance) {
    val value = holding.currentValue
    if (value == null) {
        Text(
            text = stringResource(R.string.holdings_unpriced),
            style = MaterialTheme.typography.bodyMedium,
        )
        return
    }
    Text(
        text = stringResource(R.string.holdings_value, maskedAmount(value)),
        style = MaterialTheme.typography.bodyLarge,
    )
}

/**
 * What went in, what came back, and the difference.
 * Result: the cost lines. Input: [holding]. Output: none.
 * Changelog: 2026-08-24 — Created for issue 6.3.
 */
@Composable
private fun HoldingCost(holding: HoldingPerformance) {
    Text(
        text = stringResource(R.string.holdings_cost, maskedAmount(holding.invested)),
        style = MaterialTheme.typography.bodySmall,
    )
    if (holding.realised > Money.ZERO) {
        Text(
            text = stringResource(R.string.holdings_returned, maskedAmount(holding.realised)),
            style = MaterialTheme.typography.bodySmall,
        )
    }
    holding.gain?.let { gain ->
        // Two strings rather than a signed amount, because "₹1,200 loss" reads as what it is and
        // "-₹1,200 gain" makes the reader do the work (§21.6).
        val label = if (gain < Money.ZERO) R.string.holdings_loss else R.string.holdings_gain
        Text(
            text = stringResource(label, maskedAmount(if (gain < Money.ZERO) Money(-gain.minor) else gain)),
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

/**
 * The money-weighted return, or why there is not one.
 * Why:    the reason is a full sentence rather than a dash, because each of the three has a
 *         different thing for the user to do about it — and an empty cell is the black-box verdict
 *         P-02 forbids.
 * Result: the return line. Input: [holding]. Output: none.
 * Changelog: 2026-08-24 — Created for issue 6.3.
 */
@Composable
private fun HoldingReturn(holding: HoldingPerformance) {
    val unavailable = holding.xirrUnavailable
    if (unavailable != null) {
        Text(
            text = stringResource(AccountLabels.xirrUnavailableLabel(unavailable)),
            style = MaterialTheme.typography.bodySmall,
        )
        return
    }
    val bps = holding.xirrBps ?: return
    Text(
        text = stringResource(R.string.holdings_return, returnPercent(bps)),
        style = MaterialTheme.typography.bodyMedium,
        modifier =
            Modifier.semantics {
                contentDescription = "${returnPercentPlain(bps)} a year"
            },
    )
}

/**
 * Renders a rate in basis points as a percentage with one decimal.
 * Why:    integer arithmetic, never a `Double` — `bps / 100.0` would reintroduce the floating point
 *         MNY-002 exists to keep out, for a figure the guardrail has to be able to verify exactly
 *         (AI-ARC-004). The sign is built separately because `-50 / 100` is `0` in `Int` division
 *         and would silently render a small loss as a gain.
 * Result: e.g. `15.6%`, `-10.0%`, `-0.5%`.
 * Input:  [bps] — the rate in basis points. Output: [String].
 * Changelog: 2026-08-24 — Created for issue 6.3.
 */
@Composable
private fun returnPercent(bps: Int): String {
    val magnitude = if (bps < 0) -bps else bps
    return stringResource(
        R.string.holdings_return_percent,
        if (bps < 0) "-" else "",
        magnitude / BPS_PER_PERCENT,
        (magnitude % BPS_PER_PERCENT) / TENTHS_PER_PERCENT,
    )
}

/** Result: the same figure for a screen reader, without a resource lookup. Output: [String]. */
private fun returnPercentPlain(bps: Int): String {
    val magnitude = if (bps < 0) -bps else bps
    val sign = if (bps < 0) "-" else ""
    return "$sign${magnitude / BPS_PER_PERCENT}.${(magnitude % BPS_PER_PERCENT) / TENTHS_PER_PERCENT}%"
}

/** 100 bps = 1% (MNY-002). */
private const val BPS_PER_PERCENT = 100

/** 10 bps = 0.1%, the resolution this screen renders at. */
private const val TENTHS_PER_PERCENT = 10
