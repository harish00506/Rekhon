package com.aicfo.feature.market

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
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aicfo.core.designsystem.component.CfoCard
import com.aicfo.core.designsystem.component.CfoSecondaryButton
import com.aicfo.core.designsystem.theme.CfoDimens
import com.aicfo.data.repository.OpportunityView
import com.aicfo.domain.engines.marketsignal.AssessmentOutcome
import com.aicfo.domain.engines.marketsignal.MarketKnowledge
import com.aicfo.domain.engines.marketsignal.OpportunityBand
import com.aicfo.domain.engines.marketsignal.SignalContribution

/**
 * §30's Opportunity screen (issue 10.7; P-02, P-03, P-07).
 *
 * Why:  this is the screen most likely to be read as an instruction, so every part of it is built
 *       to be checked rather than obeyed: the verdict sits above the score it came from, each
 *       signal shows the number it measured and the ones that could not be measured say so, the
 *       hit rate is quoted or its absence is, a blocked suggestion names the gate that blocked it,
 *       and the last line says the screen never buys anything.
 * What: one card per held instrument.
 * Result: evidence, in the order it was produced.
 * Changelog: 2026-09-27 — Created for issue 10.7.
 *
 * Input:  [onDone]; [modifier]; [viewModel] — injected. Output: the screen.
 */
@Composable
fun OpportunityScreen(
    onDone: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: OpportunityViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    OpportunityContent(uiState, onDone, modifier)
}

/**
 * The screen as a function of its state (ARC-004).
 * Result: the rendered screen. Input: [uiState]; [onDone]; [modifier]. Output: none.
 */
@Composable
internal fun OpportunityContent(
    uiState: OpportunityUiState,
    onDone: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(CfoDimens.spaceMd),
        verticalArrangement = Arrangement.spacedBy(CfoDimens.spaceMd),
    ) {
        Text(stringResource(R.string.market_title), style = MaterialTheme.typography.headlineSmall)
        Text(stringResource(R.string.market_intro), style = MaterialTheme.typography.bodyMedium)
        if (uiState.isLoaded && uiState.views.isEmpty()) {
            Text(stringResource(R.string.market_empty), style = MaterialTheme.typography.bodyMedium)
        }
        uiState.views.forEach { view -> OpportunityCard(view) }
        Text(stringResource(R.string.market_nothing_bought), style = MaterialTheme.typography.labelSmall)
        CfoSecondaryButton(text = stringResource(R.string.market_back), onClick = onDone)
    }
}

/**
 * One instrument: the verdict, the score behind it, the signals, the hit rate and the suggestion.
 * Result: the card. Input: [view]. Output: none.
 */
@Composable
private fun OpportunityCard(view: OpportunityView) {
    val assessment = view.assessment
    CfoCard {
        Column(verticalArrangement = Arrangement.spacedBy(CfoDimens.spaceSm)) {
            Text(view.holdingLabel, style = MaterialTheme.typography.titleMedium)
            Text(
                stringResource(verdictLabel(assessment.outcome, assessment.band)),
                style = MaterialTheme.typography.bodyLarge,
            )
            if (assessment.outcome == AssessmentOutcome.SCORED) {
                Text(
                    pluralStringResource(
                        R.plurals.market_score,
                        assessment.score,
                        assessment.score,
                        assessment.possibleScore,
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                )
                HitRateLine(view)
                assessment.signals.forEach { signal -> SignalLine(signal) }
                TrancheLine(view)
            }
            Text(stalenessSentence(view), style = MaterialTheme.typography.labelSmall)
            Text(
                stringResource(
                    R.string.market_evidence,
                    assessment.provenance.engineId,
                    assessment.provenance.engineVersion,
                    assessment.provenance.evidence.joinToString(" · ") { it.ruleId },
                ),
                style = MaterialTheme.typography.labelSmall,
            )
        }
    }
}

/** §30.3's line: the verdict's measured record, or the honest absence of one. */
@Composable
private fun HitRateLine(view: OpportunityView) {
    val rate = view.assessment.hitRate
    if (rate == null) {
        Text(stringResource(R.string.market_hit_rate_unknown), style = MaterialTheme.typography.bodyMedium)
    } else {
        Text(stringResource(R.string.market_hit_rate, rate.ratePct), style = MaterialTheme.typography.bodyMedium)
        Text(
            pluralStringResource(R.plurals.market_hit_samples, rate.samples, rate.samples),
            style = MaterialTheme.typography.labelSmall,
        )
    }
}

/** One signal's points and the number behind them — or that it could not be measured (P-02). */
@Composable
private fun SignalLine(signal: SignalContribution) {
    val name = stringResource(signalLabel(signal.id))
    val text =
        if (!signal.evaluated) {
            stringResource(R.string.market_signal_unavailable, name)
        } else {
            val measured =
                signal.measuredBps?.let { stringResource(R.string.market_signal_pct, it / BPS_PER_PERCENT) }
                    ?: signal.measuredCount?.toString()
            stringResource(
                R.string.market_signal_points,
                "$name${measured?.let { " ($it)" } ?: ""}",
                signal.points,
                signal.maxPoints,
            )
        }
    Text(text, style = MaterialTheme.typography.bodySmall)
}

/** §30.4's suggestion, or the gate that closed it. */
@Composable
private fun TrancheLine(view: OpportunityView) {
    val plan = view.assessment.tranches
    if (plan.suggested > 0) {
        Text(
            pluralStringResource(R.plurals.market_tranches, plan.suggested, plan.suggested),
            style = MaterialTheme.typography.bodyMedium,
        )
    } else {
        Text(stringResource(R.string.market_tranches_none), style = MaterialTheme.typography.bodyMedium)
        plan.gates.filter { !it.passed }.forEach { gate ->
            Text(stringResource(gateLabel(gate.ruleId)), style = MaterialTheme.typography.bodySmall)
        }
    }
}

/** Result: how old the price is, in words (P-04). Input: [view]. Output: [String]. */
@Composable
private fun stalenessSentence(view: OpportunityView): String {
    val days = view.assessment.staleness.daysOld
    return if (days <= 0) {
        stringResource(R.string.market_as_of_today)
    } else {
        pluralStringResource(R.plurals.market_as_of, days, days)
    }
}

/**
 * Result: the verdict sentence's resource. Input: [outcome]; [band]. Output: a resource id.
 * Why:    a `when`, so a new band or outcome cannot ship without words that do not read as an
 *         instruction.
 */
internal fun verdictLabel(
    outcome: AssessmentOutcome,
    band: OpportunityBand?,
): Int =
    when (outcome) {
        AssessmentOutcome.NOT_ENOUGH_HISTORY -> R.string.market_verdict_young
        AssessmentOutcome.TOO_STALE -> R.string.market_verdict_stale
        AssessmentOutcome.SCORED ->
            when (band) {
                OpportunityBand.STRONG_BUY_DAY -> R.string.market_verdict_strong
                OpportunityBand.GOOD_DAY -> R.string.market_verdict_good
                OpportunityBand.NEUTRAL -> R.string.market_verdict_neutral
                else -> R.string.market_verdict_no_edge
            }
    }

/** Result: a signal's words. Input: [id] — the library's own id. Output: a resource id. */
internal fun signalLabel(id: String): Int =
    when (id) {
        MarketKnowledge.VALUATION -> R.string.market_signal_valuation
        MarketKnowledge.DRAWDOWN -> R.string.market_signal_drawdown
        MarketKnowledge.VIX -> R.string.market_signal_vix
        MarketKnowledge.MA200 -> R.string.market_signal_ma200
        MarketKnowledge.RSI -> R.string.market_signal_rsi
        MarketKnowledge.RARITY -> R.string.market_signal_rarity
        else -> R.string.market_signal_streak
    }

/** Result: a closed gate's words. Input: [ruleId]. Output: a resource id. */
internal fun gateLabel(ruleId: String): Int =
    when (ruleId) {
        "RULE-IDLE-CASH" -> R.string.market_gate_idle_cash
        "RULE-RUNWAY-M" -> R.string.market_gate_runway
        else -> R.string.market_gate_forecast
    }

/** A basis point is a hundredth of a percent (MNY-002). */
private const val BPS_PER_PERCENT = 100
