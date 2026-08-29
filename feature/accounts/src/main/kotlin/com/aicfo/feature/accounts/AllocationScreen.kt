package com.aicfo.feature.accounts

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aicfo.core.designsystem.chart.CfoProportionBar
import com.aicfo.core.designsystem.chart.CfoProportionSegment
import com.aicfo.core.designsystem.component.CfoCard
import com.aicfo.core.designsystem.component.CfoSecondaryButton
import com.aicfo.core.designsystem.component.maskedAmount
import com.aicfo.core.designsystem.theme.CfoDimens
import com.aicfo.core.model.AssetClass
import com.aicfo.domain.engines.investment.AllocationSlice
import com.aicfo.domain.engines.investment.AllocationUnavailable
import com.aicfo.domain.engines.investment.ConcentrationFlag
import com.aicfo.domain.engines.investment.ConcentrationKind
import com.aicfo.domain.engines.investment.PortfolioAllocation

/**
 * How the portfolio is spread, and what about that is worth a look (issue 6.4; FR-INV-002, P-02).
 *
 * Why:  the holdings screen answers "what is in this account"; §11.2 and FR-INV-002 ask a question
 *       no single account can answer — "what shape is all of this in". Every warning here is
 *       rendered beside the two numbers that produced it, the measured share and the line it
 *       crossed, and beside the rulebook row that drew the line. A warning the user cannot check is
 *       the black-box verdict P-02 forbids, and one they cannot trace to a rule is one they cannot
 *       disagree with.
 * What: the total, the split as a bar and a legend, the concentration flags, the coverage line when
 *       part of the portfolio has no price, and the §11.1 disclaimer.
 * Result: the first screen in the app that says something about a portfolio rather than a holding.
 * Changelog: 2026-08-28 — Created for issue 6.4.
 *
 * **Observations, never instructions** (P-07). Nothing here tells the user to sell anything. §11.1
 * binds this module to analysing and flagging, and the wording of every flag string is chosen to
 * report a fact — "gold is 24% of your portfolio, above the 10% we flag at" — rather than to
 * prescribe an action.
 *
 * **Every amount goes through `maskedAmount`**, so the privacy blur reaches this screen like every
 * other (issue 5.3). The percentages are not masked: a share says nothing about how much money is
 * involved, which is the thing the blur exists to hide.
 *
 * Input:  [onDone] — pops back to the accounts list; [viewModel] — supplied by Hilt.
 * Output: the composition.
 */
@Composable
fun AllocationScreen(
    onDone: () -> Unit,
    viewModel: AllocationViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    AllocationContent(uiState = uiState, onDone = onDone)
}

/**
 * The screen, separated from Hilt so a test can drive it from a literal state.
 * Result: the composition. Input: [uiState]; [onDone]. Output: none.
 * Changelog: 2026-08-28 — Created for issue 6.4.
 */
@Composable
internal fun AllocationContent(
    uiState: AllocationUiState,
    onDone: () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(CfoDimens.spaceMd),
        verticalArrangement = Arrangement.spacedBy(CfoDimens.spaceMd),
    ) {
        Text(
            text = stringResource(R.string.allocation_title),
            style = MaterialTheme.typography.headlineSmall,
        )

        val allocation = uiState.allocation
        when {
            uiState.errorCode != null -> Text(stringResource(R.string.allocation_error))
            allocation == null -> Unit
            allocation.unavailable != null -> Unavailable(allocation.unavailable!!)
            else -> {
                Split(allocation)
                Coverage(allocation)
                Flags(allocation)
            }
        }

        Text(
            text = stringResource(R.string.holdings_disclaimer),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        CfoSecondaryButton(text = stringResource(R.string.allocation_done), onClick = onDone)
    }
}

/**
 * The two ways there can be nothing to show.
 * Why:    kept apart because they ask for different things. "You have not invested yet" wants an
 *         account; "nothing is priced" wants a number against holdings that already exist. Collapsing
 *         them into one message would send half the users to the wrong place (P-02).
 * Result: a title and a sentence. Input: [reason]. Output: the composition.
 * Changelog: 2026-08-28 — Created for issue 6.4.
 */
@Composable
private fun Unavailable(reason: AllocationUnavailable) {
    val title =
        when (reason) {
            AllocationUnavailable.NO_POSITIONS -> R.string.allocation_empty_title
            AllocationUnavailable.NOTHING_PRICED -> R.string.allocation_unpriced_title
        }
    val body =
        when (reason) {
            AllocationUnavailable.NO_POSITIONS -> R.string.allocation_empty_body
            AllocationUnavailable.NOTHING_PRICED -> R.string.allocation_unpriced_body
        }
    CfoCard {
        Column(verticalArrangement = Arrangement.spacedBy(CfoDimens.spaceXs)) {
            Text(text = stringResource(title), style = MaterialTheme.typography.titleMedium)
            Text(
                text = stringResource(body),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * The total, the bar and the legend.
 * Why:    the bar and the legend carry the same numbers because the bar alone is not readable — a
 *         segment has no label, and at 200% font it has very little width either. The legend is the
 *         accessible version and the bar is the glance version, and they are built from one list so
 *         they cannot disagree.
 * Result: the composition. Input: [allocation]. Output: none.
 * Changelog: 2026-08-28 — Created for issue 6.4.
 */
@Composable
private fun Split(allocation: PortfolioAllocation) {
    // `map` and then join, rather than `joinToString { }`: the latter's transform is not an inline
    // lambda, so a `stringResource` call inside it is not in a composable context and will not
    // compile. `map` is inline, so it is.
    val spoken =
        allocation.slices
            .map { slice ->
                stringResource(AccountLabels.assetClassLabel(slice.assetClass)) + " " + percent(slice.shareBps)
            }
            .joinToString()
    CfoCard {
        Column(verticalArrangement = Arrangement.spacedBy(CfoDimens.spaceSm)) {
            Text(
                text = stringResource(R.string.allocation_total, maskedAmount(allocation.total)),
                style = MaterialTheme.typography.titleMedium,
            )
            CfoProportionBar(
                segments = allocation.slices.map { CfoProportionSegment(it.value.minor, colourOf(it.assetClass)) },
                contentDescription = stringResource(R.string.allocation_bar_description, spoken),
                modifier = Modifier.fillMaxWidth(),
            )
            allocation.slices.forEach { LegendRow(it) }
        }
    }
}

/**
 * One class's line under the bar: its name, its amount and its share.
 * Result: the composition. Input: [slice]. Output: none.
 * Changelog: 2026-08-28 — Created for issue 6.4.
 */
@Composable
private fun LegendRow(slice: AllocationSlice) {
    val label = stringResource(AccountLabels.assetClassLabel(slice.assetClass))
    val amount = maskedAmount(slice.value)
    val share = percent(slice.shareBps)
    // One node for a screen reader, not three: a name, an amount and a percentage announced
    // separately are three fragments the user has to reassemble.
    val spoken = stringResource(R.string.allocation_legend_row, label, amount, share)
    Row(
        modifier = Modifier.fillMaxWidth().semantics(mergeDescendants = true) { contentDescription = spoken },
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(text = label, style = MaterialTheme.typography.bodyMedium)
        Text(text = "$amount · $share", style = MaterialTheme.typography.bodyMedium)
    }
}

/**
 * Says so when the split could only see part of the portfolio (P-02).
 * Why:    silence here would be a confident half-truth. A user with three unpriced holdings is
 *         looking at a chart of the other eight and has no way to tell.
 * Result: the composition, or nothing when everything was priced.
 * Input:  [allocation]. Output: none.
 * Changelog: 2026-08-28 — Created for issue 6.4.
 */
@Composable
private fun Coverage(allocation: PortfolioAllocation) {
    if (allocation.unvaluedCount == 0) return
    val total = allocation.valuedCount + allocation.unvaluedCount
    Text(
        text =
            pluralStringResource(
                R.plurals.allocation_unvalued,
                allocation.unvaluedCount,
                allocation.valuedCount,
                total,
                allocation.unvaluedCount,
            ),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/**
 * The concentration warnings, or the sentence that says there are none.
 * Why:    "nothing is over the limits we check" is rendered rather than left blank, because an
 *         empty section reads as a section that failed to load. It also tells the user that
 *         something *was* checked, which is what makes the clean case informative.
 * Result: the composition. Input: [allocation]. Output: none.
 * Changelog: 2026-08-28 — Created for issue 6.4.
 */
@Composable
private fun Flags(allocation: PortfolioAllocation) {
    CfoCard {
        Column(verticalArrangement = Arrangement.spacedBy(CfoDimens.spaceSm)) {
            Text(
                text = stringResource(R.string.allocation_flags_title),
                style = MaterialTheme.typography.titleMedium,
            )
            if (allocation.flags.isEmpty()) {
                Text(
                    text = stringResource(R.string.allocation_flags_none),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            allocation.flags.forEach { FlagRow(it) }
        }
    }
}

/**
 * One warning: what breached, by how much, past what, and on whose authority.
 * Why:    the citation is on the card rather than behind a tap, for the reason
 *         `BudgetSuggestionCard` gives — §29's governance clause is that a figure the app proposes
 *         can be traced to the row that produced it, and a citation nobody can see is not a
 *         citation.
 * Result: the composition. Input: [flag]. Output: none.
 * Changelog: 2026-08-28 — Created for issue 6.4.
 */
@Composable
private fun FlagRow(flag: ConcentrationFlag) {
    val subject =
        when (flag.kind) {
            ConcentrationKind.SINGLE_HOLDING -> flag.name
            else -> stringResource(AccountLabels.assetClassLabel(flag.assetClass!!))
        }
    val template =
        when (flag.kind) {
            ConcentrationKind.ASSET_CLASS_CAP -> R.string.allocation_flag_class_cap
            ConcentrationKind.SINGLE_CLASS -> R.string.allocation_flag_single_class
            ConcentrationKind.SINGLE_HOLDING -> R.string.allocation_flag_single_holding
        }
    Column(verticalArrangement = Arrangement.spacedBy(CfoDimens.spaceXs)) {
        Text(
            text = stringResource(template, subject, percent(flag.measuredBps), percent(flag.thresholdBps)),
            style = MaterialTheme.typography.bodyMedium,
        )
        Text(
            text = stringResource(R.string.allocation_flag_amount, maskedAmount(flag.value)),
            style = MaterialTheme.typography.bodySmall,
        )
        Text(
            text = stringResource(R.string.allocation_reason_rule, flag.citation.ruleId, flag.citation.ruleVersion),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * Renders integer basis points as a whole-percent string.
 * Why:    integer division, never a `Double` and never `String.format("%.1f")` — MNY-002 keeps
 *         every rate in basis points precisely so no floating point touches a figure the user
 *         reads. Whole percent rather than one decimal because a share is a shape, not a
 *         measurement: "gold is 24%" is the fact, and "24.3%" implies a precision the underlying
 *         prices do not have.
 * Result: e.g. `"24%"`. Input: [bps] — basis points. Output: the string.
 * Changelog: 2026-08-28 — Created for issue 6.4.
 */
@Composable
private fun percent(bps: Int): String = stringResource(R.string.allocation_share, bps / BPS_PER_PERCENT)

/**
 * The colour one asset class is drawn in.
 * Why:    `CfoProportionSegment` deliberately makes the caller supply the palette, so the mapping
 *         lives here rather than in `:core:designsystem` — the design system has no opinion about
 *         asset classes and should not gain one for a single screen. Every colour is a theme token,
 *         so light and dark are both handled and the contrast checks in
 *         `:core:designsystem` still cover them.
 * Result: a colour per class, stable across recompositions and themes.
 * Input:  [assetClass]. Output: [Color].
 * Changelog: 2026-08-28 — Created for issue 6.4.
 */
@Composable
private fun colourOf(assetClass: AssetClass): Color =
    when (assetClass) {
        AssetClass.EQUITY -> MaterialTheme.colorScheme.primary
        AssetClass.DEBT -> MaterialTheme.colorScheme.secondary
        AssetClass.GOLD -> MaterialTheme.colorScheme.tertiary
        AssetClass.CRYPTO -> MaterialTheme.colorScheme.error
        AssetClass.CASH -> MaterialTheme.colorScheme.primaryContainer
        AssetClass.REAL_ESTATE -> MaterialTheme.colorScheme.secondaryContainer
        AssetClass.OTHER -> MaterialTheme.colorScheme.outline
    }

/** 100 bps = 1% (MNY-002). The divisor turning a stored share into the number on screen. */
private const val BPS_PER_PERCENT = 100
