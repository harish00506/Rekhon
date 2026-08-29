package com.aicfo.feature.accounts

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import com.aicfo.core.designsystem.component.maskedAmount
import com.aicfo.core.model.Money
import com.aicfo.data.repository.PricedHolding

/**
 * What an investment account holds, in one line (issue 6.3; §11, P-02, P-03).
 *
 * Why:    the accounts list already shows the account's balance; what it cannot say is how much of
 *         that is priced and how many instruments it is spread across. An account with no holdings
 *         shows a prompt rather than "₹0 across 0 holdings", which would read as a decision the
 *         user had made rather than one they have not made yet.
 *
 *         Only **priced** holdings are summed. A holding with no price contributes nothing to the
 *         total rather than a zero, because those are different claims — and the count says how
 *         many there are, so a total smaller than expected has a visible explanation.
 * Result: a value and a count, or the prompt.
 * Input:  [holdings] — the account's priced holdings, or `null` when it has none.
 * Output: none.
 * Changelog: 2026-08-24 — Created for issue 6.3.
 */
@Composable
internal fun InvestmentSummary(holdings: List<PricedHolding>?) {
    if (holdings.isNullOrEmpty()) {
        Text(
            text = stringResource(R.string.accounts_investments_none),
            style = MaterialTheme.typography.bodySmall,
        )
        return
    }
    val total =
        holdings.mapNotNull { it.performance.currentValue }.fold(
            Money.ZERO,
        ) { running, value -> running + value }
    val count = pluralStringResource(R.plurals.accounts_investments_count, holdings.size, holdings.size)
    Text(
        text = stringResource(R.string.accounts_investments_value, maskedAmount(total), count),
        style = MaterialTheme.typography.bodyMedium,
    )
}
