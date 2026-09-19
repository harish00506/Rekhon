package com.aicfo.feature.dashboard

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.aicfo.core.designsystem.component.maskedAmount
import com.aicfo.domain.engines.stream.StreamProfile

/**
 * What of the user's spending recurs (issue 9.1; SRS §8.2 AI-CLS Stage 2; P-02, P-03).
 *
 * Why:  §8's purpose line is that "every downstream engine depends on knowing which outflows are
 *       obligations". Before the forecast and health score that consume this exist, the user is the
 *       only consumer, and the question it answers is theirs too: of what I spend, how much is
 *       already spoken for next month? Three figures, each a sum of the streams' typical months,
 *       all masked by the privacy blur like every amount on the screen.
 *
 *       **The work is shown** (P-02): the rules that fired are named on the line below, and when
 *       any stream was classified from the category's prior rather than measured, the screen says
 *       so — §8.2 requires cold-start results to be "clearly labelled".
 * What: a label, the three figures, the estimate note when it applies, and the rule citations.
 * Result: nothing before the first classification or for a profile with no expense history —
 *       "Fixed ₹0.00" would be a claim about commitments nobody has recorded (P-03).
 * Changelog: 2026-09-19 — Created for issue 9.1.
 *
 * Input:  [profile] — the stream classification, or `null`. Output: the composition.
 */
@Composable
internal fun StreamLoadSection(profile: StreamProfile?) {
    if (profile == null || profile.streams.isEmpty()) return

    Text(text = stringResource(R.string.dashboard_streams_label))
    Text(
        text =
            stringResource(
                R.string.dashboard_streams_values,
                maskedAmount(profile.fixedLoad),
                maskedAmount(profile.semiFixedExpected),
                maskedAmount(profile.variableBudgetable),
            ),
        style = MaterialTheme.typography.bodyMedium,
    )
    if (profile.hasEstimates) {
        Text(
            text = stringResource(R.string.dashboard_streams_estimate),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    Text(
        text =
            stringResource(
                R.string.dashboard_streams_rules,
                profile.provenance.evidence.joinToString(", ") { "${it.ruleId} v${it.ruleVersion}" },
            ),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}
