package com.aicfo.feature.onboarding

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.aicfo.core.designsystem.component.CfoAmountText
import com.aicfo.core.designsystem.component.CfoCard
import com.aicfo.core.designsystem.component.CfoListRow
import com.aicfo.domain.engines.quicksetup.BudgetNature
import com.aicfo.domain.engines.quicksetup.ObligationVerdict
import com.aicfo.domain.engines.quicksetup.QuickSetupPlan
import com.aicfo.domain.engines.quicksetup.RecurringKind

/*
 * The quick-setup summary card and its wording (issue 2.3; FR-ONB-002, P-02).
 *
 * Why:  split out of OnboardingSteps.kt, which this card pushed past detekt's per-file function
 *       limit — the same reason that file was itself split out of OnboardingScreen.kt. The steps
 *       are a list of forms; this is a piece of reasoning shown to the user, and it will keep
 *       growing as issues 4.4 and 7.2 give it real budgets and goals to point at.
 * What: the card, plus the small pure functions that turn engine output into wording.
 * Result: adding a line to the summary touches this file only.
 * Changelog: 2026-07-27 — Created for issue 2.3.
 */

/**
 * The derived plan, shown as the user types (FR-ONB-002, P-02).
 *
 * Why:    P-02 says every output shows its inputs, the rule that fired, and a plain-language
 *         reason. A card that showed three amounts and nothing else would be a black box asking
 *         the user to trust it on the screen where they have least reason to. So each figure is
 *         accompanied by the rule ids behind it, and the awkward case — a rent the budget cannot
 *         cover — is stated in words rather than left for the user to infer from a short envelope.
 * What:   the three envelopes, the emergency-fund target, the obligation verdict, and the rules.
 * Result: the composition. Input: [plan] — what the engine derived. Output: the rendered card.
 * Changelog: 2026-07-27 — Created for issue 2.3.
 *
 * **It renders; it does not compute.** Every figure here comes off [plan] as-is — P-03 puts all
 * arithmetic in the engine, and a UI that did its own would be a second, untested implementation.
 */
@Composable
internal fun QuickSetupSummary(plan: QuickSetupPlan) {
    CfoCard {
        Text(
            text = stringResource(R.string.onboarding_quick_setup_summary_title),
            style = MaterialTheme.typography.titleMedium,
        )
        plan.envelopes.forEach { envelope ->
            CfoListRow(
                title = stringResource(envelope.nature.labelRes()),
                trailing = {
                    // No sign: these are budgets, not movements, and a "+₹42,500" would read as
                    // money arriving.
                    CfoAmountText(amount = envelope.amount, showSign = false)
                },
            )
        }
        plan.emergencyFundTarget?.let { target ->
            CfoListRow(
                title = stringResource(R.string.onboarding_quick_setup_summary_emergency),
                supporting =
                    stringResource(
                        R.string.onboarding_quick_setup_summary_emergency_help,
                        plan.emergencyRunwayMonths,
                    ),
                trailing = { CfoAmountText(amount = target, showSign = false) },
            )
        }
        QuickSetupCaveats(plan)
    }
}

/**
 * The two sentences that qualify the figures above them.
 * Why:    split from [QuickSetupSummary] to keep each function inside detekt's length limit, along
 *         a real seam: the rows above are the plan, and these are the app's commentary on it —
 *         what the obligation ratio means, and the admission when the budget does not fit.
 * Result: the composition. Input: [plan]. Output: the rendered text.
 * Changelog: 2026-07-27 — Created for issue 2.3.
 */
@Composable
private fun QuickSetupCaveats(plan: QuickSetupPlan) {
    plan.obligationSummary()?.let { Text(text = it, style = MaterialTheme.typography.bodyMedium) }
    if (plan.needsEnvelopeFallsShort()) {
        Text(
            text = stringResource(R.string.onboarding_quick_setup_summary_short),
            style = MaterialTheme.typography.bodyMedium,
        )
    }
    Text(
        text = stringResource(R.string.onboarding_quick_setup_summary_rules, plan.citedRuleIds()),
        style = MaterialTheme.typography.bodySmall,
    )
    Text(
        text = stringResource(R.string.onboarding_quick_setup_summary_editable),
        style = MaterialTheme.typography.bodySmall,
    )
}

/**
 * The label for one envelope.
 * Why:    the mapping lives here rather than on [BudgetNature] because the enum is in a pure-Kotlin
 *         engine module that has no resources and must not gain any (ARC-002) — the words belong to
 *         the feature, in its own `strings.xml` (§21.6).
 * Result: a string resource id. Input: the receiver. Output: `Int`.
 * Changelog: 2026-07-27 — Created for issue 2.3.
 */
private fun BudgetNature.labelRes(): Int =
    when (this) {
        BudgetNature.NEED -> R.string.onboarding_quick_setup_summary_needs
        BudgetNature.WANT -> R.string.onboarding_quick_setup_summary_wants
        BudgetNature.INVEST -> R.string.onboarding_quick_setup_summary_savings
    }

/**
 * The obligation line, in words.
 * Why:    a bare "2823 bps" means nothing to a user, and a bare percentage means nothing without a
 *         judgement attached. Both sentences come from `strings.xml` so the wording is localisable
 *         and the verdict's severity is carried by the words rather than by colour alone (ACC).
 * Result: the sentence, or `null` when the user did not answer enough for a verdict — in which
 *         case nothing is shown, rather than a reassuring "within the guideline" nobody earned.
 * Input:  the receiver. Output: `String?`.
 * Changelog: 2026-07-27 — Created for issue 2.3.
 */
@Composable
private fun QuickSetupPlan.obligationSummary(): String? {
    val bps = obligationLoadBps ?: return null
    val verdictRes =
        when (obligationVerdict) {
            ObligationVerdict.WITHIN_LIMIT -> R.string.onboarding_quick_setup_summary_obligations_within
            ObligationVerdict.ABOVE_LIMIT -> R.string.onboarding_quick_setup_summary_obligations_above
            ObligationVerdict.HARD_FAIL -> R.string.onboarding_quick_setup_summary_obligations_fail
            ObligationVerdict.UNKNOWN -> return null
        }
    val share = stringResource(R.string.onboarding_quick_setup_summary_obligations, formatBps(bps))
    return "$share ${stringResource(verdictRes)}"
}

/**
 * Whether the needs envelope is smaller than the rent it was meant to cover.
 * Why:    this is the honest-but-uncomfortable case the engine deliberately produces, and it needs
 *         saying out loud. Left unexplained, a needs envelope below the user's own rent looks like
 *         an arithmetic bug rather than the deliberate refusal to raid their savings that it is.
 * Result: `true` when the flex hit its ceiling short of the rent.
 * Input:  the receiver. Output: `Boolean`.
 * Changelog: 2026-07-27 — Created for issue 2.3.
 */
private fun QuickSetupPlan.needsEnvelopeFallsShort(): Boolean {
    val needs = envelopes.firstOrNull { it.nature == BudgetNature.NEED } ?: return false
    val rent = recurring.firstOrNull { it.kind == RecurringKind.RENT_EMI } ?: return false
    // The seed is stored signed (an outflow is negative), so compare against its magnitude.
    return needs.amount.minor < -rent.amount.minor
}

/** Result: the cited rule ids, comma-separated for the "Based on …" line (P-02). Output: `String`. */
private fun QuickSetupPlan.citedRuleIds(): String = provenance.evidence.joinToString { it.ruleId }

/**
 * Renders a basis-point rate as a percentage.
 * Why:    engines carry rates as integer bps (MNY-002) and users read percentages, so the
 *         conversion has to happen somewhere; doing it at the very edge keeps every layer below
 *         this one free of a decimal rate. Truncated to one decimal to match how the engine
 *         truncates the ratio itself — rounding here would show a percentage the verdict disagrees
 *         with, e.g. "40.0%" beside "above the guideline".
 * Result: e.g. `28.2%`. Input: [bps] — basis points. Output: `String`.
 * Changelog: 2026-07-27 — Created for issue 2.3.
 */
private fun formatBps(bps: Int): String = "${bps / BPS_PER_PERCENT}.${bps % BPS_PER_PERCENT / BPS_PER_TENTH}%"

/** 100 bps = 1% (MNY-002). */
private const val BPS_PER_PERCENT = 100

/** 10 bps = 0.1%, the precision this line is shown to. */
private const val BPS_PER_TENTH = 10
