package com.aicfo.feature.advisor

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import com.aicfo.core.designsystem.component.CfoCard
import com.aicfo.core.designsystem.theme.CfoDimens
import com.aicfo.core.model.DateFormatter
import com.aicfo.core.model.MoneyFormatter
import com.aicfo.domain.engines.purchase.GateFigure
import com.aicfo.domain.engines.purchase.GateResult
import com.aicfo.domain.engines.purchase.PurchaseVerdictCard

/**
 * §13.2's reasoning card (issue 10.1; P-02).
 *
 * Why:  its own file because the card is the screen's whole claim — a verdict is worth nothing
 *       without the gate table, the impact strip and the alternatives under it — and because the
 *       screen file had grown past detekt's function count.
 * What: the verdict, the gates with their figures, what the purchase moves, what would change the
 *       answer, and which engine and rules decided it.
 * Result: an answer the user can argue with.
 * Changelog: 2026-09-25 — Created for issue 10.1, extracted from `AdvisorScreen`.
 */
@Composable
internal fun VerdictCard(card: PurchaseVerdictCard) {
    CfoCard {
        Column(verticalArrangement = Arrangement.spacedBy(CfoDimens.spaceSm)) {
            Text(stringResource(AdvisorLabels.verdict(card.verdict)), style = MaterialTheme.typography.headlineSmall)
            Text(
                stringResource(
                    R.string.advisor_verdict_for,
                    card.request.item,
                    MoneyFormatter.format(card.request.price),
                ),
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(stringResource(AdvisorLabels.summary(card.verdict)), style = MaterialTheme.typography.bodyMedium)

            Text(stringResource(R.string.advisor_gates_label), style = MaterialTheme.typography.titleSmall)
            card.gates.forEach { GateRow(it) }

            ImpactStrip(card)

            Text(stringResource(R.string.advisor_alternatives_label), style = MaterialTheme.typography.titleSmall)
            Alternatives(card)

            Text(
                stringResource(
                    R.string.advisor_evidence,
                    card.provenance.engineId,
                    card.provenance.engineVersion,
                    card.provenance.evidence.joinToString(" · ") { it.ruleId },
                ),
                style = MaterialTheme.typography.labelSmall,
            )
        }
    }
}

/** §13.2's impact strip: the money and the runway, before and after. */
@Composable
private fun ImpactStrip(card: PurchaseVerdictCard) {
    Text(stringResource(R.string.advisor_impact_label), style = MaterialTheme.typography.titleSmall)
    Text(
        stringResource(
            R.string.advisor_impact_money,
            MoneyFormatter.format(card.impact.liquidBefore),
            MoneyFormatter.format(card.impact.liquidAfter),
        ),
        style = MaterialTheme.typography.bodyMedium,
    )
    Text(
        stringResource(
            R.string.advisor_impact_runway,
            tenths(card.impact.runwayMonthsBeforeTenths),
            tenths(card.impact.runwayMonthsAfterTenths),
        ),
        style = MaterialTheme.typography.bodyMedium,
    )
}

/** One line of the gate table: the check, what it concluded, and the figures behind it. */
@Composable
private fun GateRow(gate: GateResult) {
    Column(modifier = Modifier.padding(vertical = CfoDimens.spaceXs)) {
        Text(
            "${stringResource(AdvisorLabels.gate(gate.gate))} · ${stringResource(AdvisorLabels.outcome(gate.outcome))}",
            style = MaterialTheme.typography.bodyLarge,
        )
        gate.figures.forEach { FigureLine(it) }
    }
}

/** One figure, labelled. Plurals are worded as sentences; everything else is "label: value". */
@Composable
private fun FigureLine(figure: GateFigure) {
    val text =
        when (figure.key) {
            AdvisorLabels.GOAL_DELAY_DAYS ->
                figure.count?.let { pluralStringResource(R.plurals.advisor_figure_goal_delay, it, it) }
            AdvisorLabels.CRUNCH_DAYS_BEFORE ->
                figure.count?.takeIf { it > 0 }
                    ?.let { pluralStringResource(R.plurals.advisor_figure_crunch_days_before, it, it) }
            else -> AdvisorLabels.figure(figure.key)?.let { label -> "${stringResource(label)}: ${value(figure)}" }
        }
    text?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
}

/** Result: the figure's value as the app writes it — an amount, a percentage, a count or a month. */
@Composable
private fun value(figure: GateFigure): String =
    when {
        figure.amount != null -> MoneyFormatter.format(figure.amount!!)
        figure.bps != null -> stringResource(R.string.advisor_figure_percent, figure.bps!! / BPS_PER_PERCENT)
        figure.count != null -> figure.count.toString()
        else -> figure.text.orEmpty()
    }

/** §13.2's alternatives: the price, the date, and the cooling-off note. */
@Composable
private fun Alternatives(card: PurchaseVerdictCard) {
    val alternatives = card.alternatives
    if (alternatives.comfortablePrice == null && alternatives.comfortableFrom == null) {
        Text(stringResource(R.string.advisor_alternative_none), style = MaterialTheme.typography.bodyMedium)
    }
    alternatives.comfortablePrice?.let {
        Text(
            stringResource(R.string.advisor_alternative_price, MoneyFormatter.format(it)),
            style = MaterialTheme.typography.bodyMedium,
        )
    }
    alternatives.comfortableFrom?.let {
        Text(
            stringResource(R.string.advisor_alternative_date, DateFormatter.day(it.toString())),
            style = MaterialTheme.typography.bodyMedium,
        )
    }
    if (alternatives.coolOffSuggested) {
        Text(stringResource(R.string.advisor_alternative_cooloff), style = MaterialTheme.typography.bodyMedium)
    }
}

private const val TENTHS = 10
private const val BPS_PER_PERCENT = 100

/** Result: months held in tenths, written for a reader — 24 becomes "2.4". Input: [tenths]. */
private fun tenths(tenths: Int): String = "${tenths / TENTHS}.${tenths % TENTHS}"
