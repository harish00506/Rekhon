package com.aicfo.feature.advisor

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import com.aicfo.core.model.Money
import com.aicfo.core.model.MoneyFormatter
import com.aicfo.domain.engines.simulator.PayoffComparison
import com.aicfo.domain.engines.simulator.PrepayComparison
import com.aicfo.domain.engines.simulator.SimulatorVerdict

// What each simulator came to, read back to the user (issue 10.3; P-02).
//
// Why:  a comparison is only useful if both sides of it are on screen, together with the point at
//       which the answer flips and the engine that produced it. These readouts live in their own
//       file so SimulatorsScreen.kt stays about the form and this one about the answer — the two
//       change for different reasons.
// What: the prepay-versus-invest readout, both payoff plans, and the evidence line under each.
// Result: figures with their provenance attached; nothing here computes a number.
// Changelog: 2026-09-26 — Split out of SimulatorsScreen.kt for issue 10.3.

/** What §36's comparison came to, with the breakeven that decides it. */
@Composable
internal fun PrepayResult(comparison: PrepayComparison) {
    Text(
        stringResource(
            R.string.sim_prepay_saved,
            MoneyFormatter.format(comparison.prepay.interestSaved),
            months(comparison.prepay.monthsSaved),
        ),
        style = MaterialTheme.typography.bodyMedium,
    )
    Text(
        stringResource(R.string.sim_invest_gain, MoneyFormatter.format(comparison.invest.afterTaxGain)),
        style = MaterialTheme.typography.bodyMedium,
    )
    Text(verdict(comparison), style = MaterialTheme.typography.bodyLarge)
    Text(
        stringResource(R.string.sim_breakeven, comparison.breakevenReturnBps / BPS_PER_PERCENT),
        style = MaterialTheme.typography.bodySmall,
    )
    Evidence(
        comparison.provenance.engineId,
        comparison.provenance.engineVersion,
        comparison.provenance.evidence.joinToString(
            " · ",
        ) {
            it.ruleId
        },
    )
}

/** Both plans, and what choosing the cheaper one is worth. */
@Composable
internal fun PayoffResult(comparison: PayoffComparison) {
    Text(
        stringResource(
            R.string.sim_avalanche,
            months(comparison.avalanche.months),
            MoneyFormatter.format(comparison.avalanche.totalInterest),
        ),
        style = MaterialTheme.typography.bodyMedium,
    )
    Text(
        stringResource(
            R.string.sim_snowball,
            months(comparison.snowball.months),
            MoneyFormatter.format(comparison.snowball.totalInterest),
        ),
        style = MaterialTheme.typography.bodyMedium,
    )
    PayoffDelta(comparison)
    Text(
        stringResource(R.string.sim_order, comparison.avalanche.order.joinToString(", ")),
        style = MaterialTheme.typography.bodySmall,
    )
    Evidence(
        comparison.provenance.engineId,
        comparison.provenance.engineVersion,
        comparison.provenance.evidence.joinToString(
            " · ",
        ) {
            it.ruleId
        },
    )
}

/**
 * What choosing the cheaper strategy is worth — or that it is worth nothing here.
 * Why:    when the dearest debt is also the smallest, both plans are identical, and claiming a
 *         saving of ₹0 would read as a bug rather than as the honest answer.
 */
@Composable
private fun PayoffDelta(comparison: PayoffComparison) {
    val saved = comparison.interestSavedByAvalanche
    if (saved > Money.ZERO || comparison.monthsSavedByAvalanche > 0) {
        Text(
            stringResource(
                R.string.sim_payoff_saved,
                MoneyFormatter.format(saved),
                months(comparison.monthsSavedByAvalanche),
            ),
            style = MaterialTheme.typography.bodyLarge,
        )
    } else {
        Text(stringResource(R.string.sim_payoff_same), style = MaterialTheme.typography.bodyLarge)
    }
}

/** Which engine and rules produced a figure (P-02). */
@Composable
private fun Evidence(
    engineId: String,
    engineVersion: String,
    rules: String,
) {
    Text(
        stringResource(R.string.sim_evidence, engineId, engineVersion, rules),
        style = MaterialTheme.typography.labelSmall,
    )
}

/** Result: a count of months, pluralised (§21.6's ICU rule). */
@Composable
private fun months(count: Int): String = pluralStringResource(R.plurals.sim_months, count, count)

/** Result: the verdict sentence for a comparison. */
@Composable
private fun verdict(comparison: PrepayComparison): String =
    when (comparison.verdict) {
        SimulatorVerdict.PREPAY_AHEAD ->
            stringResource(
                R.string.sim_verdict_prepay,
                MoneyFormatter.format(comparison.advantage),
            )
        SimulatorVerdict.INVEST_AHEAD ->
            stringResource(
                R.string.sim_verdict_invest,
                MoneyFormatter.format(comparison.advantage),
            )
        SimulatorVerdict.LEVEL -> stringResource(R.string.sim_verdict_level)
    }

private const val BPS_PER_PERCENT = 100
