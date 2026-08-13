package com.aicfo.feature.budgets

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import com.aicfo.core.designsystem.component.CfoCard
import com.aicfo.core.designsystem.theme.CfoDimens
import com.aicfo.domain.engines.budget.BudgetAlertBand

/**
 * The in-app half of FR-BUD-004: a summary banner and a per-row band chip (issue 4.5).
 *
 * Why:  the notification is the interruption; this is the *state*. They are separate because they
 *       answer to different things — a notification is sent once and can be missed, denied or
 *       swiped away, while a crossed band stays true for the rest of the month. A user who never
 *       granted the permission, or who dismissed the alert on the bus, must still open this screen
 *       and see where they stand (P-02).
 * What: a banner that counts the categories in each band, and a chip that names one row's band.
 * Result: the band is visible without a notification ever being posted.
 * Changelog: 2026-08-13 — Created for issue 4.5 (FR-BUD-004).
 *
 * **The banner quotes no amounts, deliberately.** Every figure is already on the card below it, and
 * a second copy is a second thing to keep in step — the kind of duplication that ends with a banner
 * and a card disagreeing about the same budget.
 */
@Composable
internal fun BudgetAlertBanner(uiState: BudgetsUiState) {
    if (uiState.alerts.isEmpty()) return
    CfoCard {
        Column(
            // Polite, not assertive: this is a summary of something the user came here to read, not
            // an interruption. Assertive would talk over whatever the screen reader was saying.
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
            verticalArrangement = Arrangement.spacedBy(CfoDimens.spaceXs),
        ) {
            if (uiState.overspentAlerts.isNotEmpty()) {
                Text(
                    text =
                        pluralStringResource(
                            R.plurals.budgets_alert_banner_exceeded,
                            uiState.overspentAlerts.size,
                            uiState.overspentAlerts.size,
                        ),
                    style = MaterialTheme.typography.bodyMedium,
                    // The error role, never the money-in green — the defect the 4.4 emulator run
                    // found and `BudgetAmountText` records. Overspending is not a positive figure.
                    color = MaterialTheme.colorScheme.error,
                )
            }
            if (uiState.warnedAlerts.isNotEmpty()) {
                Text(
                    text =
                        pluralStringResource(
                            R.plurals.budgets_alert_banner_warned,
                            uiState.warnedAlerts.size,
                            uiState.warnedAlerts.size,
                        ),
                    style = MaterialTheme.typography.bodyMedium,
                    // Distinct from both the error role and the default: a warning that looked
                    // identical to an overspend would collapse the two bands the rule separates.
                    color = MaterialTheme.colorScheme.tertiary,
                )
            }
        }
    }
}

/**
 * One row's band, as a short label beside the figures (issue 4.5).
 *
 * Why:  the banner says how many; the row says which. Without this the user reads "2 categories are
 *       over budget" and then has to work out which two from the amounts — on the screen whose
 *       entire job is to answer that.
 * Result: the label, or nothing when this row has crossed no band.
 * Input:  [band] — the row's band, `null` when it is within its budget. Output: the composition.
 * Changelog: 2026-08-13 — Created for issue 4.5.
 *
 * **Colour is never the only signal**: the label says the band in words, so it survives a
 * colour-blind user, a greyscale screenshot and a high-contrast theme.
 *
 * The label is short enough to be ambiguous on its own, so the spoken description says what the
 * "80%" is 80% *of* — a screen-reader user hears the card's category name and then a full sentence
 * rather than a bare fraction.
 */
@Composable
internal fun BudgetBandChip(band: BudgetAlertBand?) {
    if (band == null) return
    val exceeded = band == BudgetAlertBand.EXCEEDED
    val description =
        stringResource(
            if (exceeded) R.string.budgets_band_exceeded_description else R.string.budgets_band_warn_description,
        )
    Text(
        text = stringResource(if (exceeded) R.string.budgets_band_exceeded else R.string.budgets_band_warn),
        style = MaterialTheme.typography.labelMedium,
        color = if (exceeded) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.tertiary,
        modifier = Modifier.semantics { contentDescription = description },
    )
}
