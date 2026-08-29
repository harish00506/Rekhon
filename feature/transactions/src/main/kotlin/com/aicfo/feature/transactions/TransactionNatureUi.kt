package com.aicfo.feature.transactions

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import com.aicfo.core.designsystem.theme.CfoDimens
import com.aicfo.core.model.CategoryNature
import com.aicfo.domain.engines.nature.NatureVerdict

// =============================================================================
// What a transaction's money became, on the detail sheet (issue 4.3; SRS §8.3, P-02, P-07).
//
// Why:  a file of its own rather than more of TransactionSourceUi.kt, which detekt already holds
//       near its function ceiling — and the split is the honest one: this is a self-contained
//       section with its own label, its own picker and its own review note.
// Result: the sheet gains a section; nothing else in that file changes shape.
// Changelog: 2026-08-10 — Created for issue 4.3.
// =============================================================================

/**
 * The nature section: what it became, why, and the user's right to disagree (issue 4.3).
 *
 * Why:    §8.3 calls nature "the axis the advice layer is built on", and it is also the axis the
 *         user can least see — a category is a word they chose, a nature is a judgement the app
 *         made about it. So the section states three things rather than one: **what** was decided,
 *         **which rule** decided it, and **whether the app is unsure**.
 *
 *         The rule id is rendered verbatim (`CLS-NAT-002`) for the reason the category suggestion
 *         renders `CLS-MER-001`: it is a citation into `ai/knowledge/classification-kb.json` that a
 *         user or a reviewer can look up, and a paraphrase is not. P-02 is not decoration when the
 *         app is quietly deciding which band of the 50/30/20 view someone's money falls into.
 *
 *         **Every nature is offered, always** — not only when the app is unsure. A picker that
 *         appeared only on low confidence would make disagreeing feel like correcting an error
 *         rather than exercising a choice (P-07), and §8.3 puts the user above every rule in the
 *         list.
 * Result: the composition, or nothing while the verdict is still being worked out — a sheet that
 *         flashed a nature and then replaced it would be worse than one that waits a beat.
 * Input:  [verdict] — what §8.3.1 (or the user) decided; [onOverride] — takes the chosen nature, or
 *         `null` to withdraw an override and hand the transaction back to the rules.
 * Output: none.
 * Changelog: 2026-08-10 — Created for issue 4.3.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun NatureSection(
    verdict: NatureVerdict?,
    onOverride: (CategoryNature?) -> Unit,
) {
    if (verdict == null) return

    Column(verticalArrangement = Arrangement.spacedBy(CfoDimens.spaceXs)) {
        Text(
            text = stringResource(R.string.transactions_detail_nature),
            style = MaterialTheme.typography.labelLarge,
        )
        FlowRow(horizontalArrangement = Arrangement.spacedBy(CfoDimens.spaceSm)) {
            CategoryNature.entries.forEach { nature ->
                val selected = nature == verdict.nature
                FilterChip(
                    selected = selected,
                    // Tapping the selected nature withdraws the override rather than doing nothing,
                    // which is how the user gets back to "whatever the rules say" — the only reason
                    // storing solely the correction is a choice they can undo.
                    onClick = { onOverride(if (selected) null else nature) },
                    label = { Text(stringResource(NatureLabels.name(nature))) },
                )
            }
        }
        Text(
            text = stringResource(R.string.transactions_detail_nature_reason, verdict.citedRule()),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (verdict.isFlagged) {
            Text(
                text = stringResource(R.string.transactions_detail_nature_unsure),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                // Announced, because it appears without the user having asked for anything — §8.3's
                // review flag is a question the app is raising, and a question nobody hears is not
                // one. A word, not a colour (§21.6 accessibility).
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
            )
        }
    }
}

/**
 * The rule this verdict cites, as one string.
 * Why:    §8.3.1 step 6 appends itself to whatever decided the nature, so a verdict can cite two
 *         rows — and the second one is the interesting half ("Need, *and* unusually large"). Joined
 *         rather than truncated so the citation stays complete and checkable (AI-ARC-006).
 * Result: e.g. `CLS-NAT-005` or `CLS-NAT-005 + CLS-NAT-006`.
 * Input:  the receiver. Output: [String].
 * Changelog: 2026-08-10 — Created for issue 4.3.
 */
private fun NatureVerdict.citedRule(): String = provenance.evidence.joinToString(" + ") { it.ruleId }

/**
 * The user-facing name of each nature (issue 4.3; §21.6).
 *
 * Why:    a `when` returning string resources rather than `CategoryNature.name`, because the enum's
 *         names are code (`INVEST`) and §21.6 requires every user-visible string to live in
 *         `strings.xml` with a translation — `CfoHardcodedUiString` fails the build otherwise. The
 *         same shape `TransactionLabels.sourceName` uses, kept identical.
 * Result: the five labels. Changelog: 2026-08-10 — Created for issue 4.3.
 */
internal object NatureLabels {
    /**
     * Result: the string resource naming [nature]. Input: [nature]. Output: a string resource id.
     *
     * Exhaustive over the enum, so a sixth nature fails to compile here rather than rendering blank.
     */
    @StringRes
    fun name(nature: CategoryNature): Int =
        when (nature) {
            CategoryNature.NEED -> R.string.nature_need
            CategoryNature.WANT -> R.string.nature_want
            CategoryNature.INVEST -> R.string.nature_invest
            CategoryNature.ASSET -> R.string.nature_asset
            CategoryNature.LIABILITY -> R.string.nature_liability
        }
}
