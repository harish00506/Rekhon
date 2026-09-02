package com.aicfo.feature.emergencyfund

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
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aicfo.core.designsystem.component.CfoCard
import com.aicfo.core.designsystem.component.CfoSecondaryButton
import com.aicfo.core.designsystem.theme.CfoDimens
import com.aicfo.core.designsystem.theme.CfoTheme
import com.aicfo.core.model.MoneyFormatter
import com.aicfo.domain.engines.emergencyfund.EmergencyFundPlan
import com.aicfo.domain.engines.emergencyfund.EmergencyStatus

/**
 * The emergency-fund screen (issue 7.2; §10.1, AI-EMF, ARC-004).
 *
 * Why:  the screen that makes `EmergencyFundEngine` reachable. An engine whose runway nobody can see
 *       is an engine nobody can use — the exact shape of the bug issue 6.7 found in 6.5, where a
 *       whole market-data stack shipped with no field to fill in, and the reason issue 7.1 shipped a
 *       screen its acceptance criteria never asked for.
 *
 *       It is also where §10.1's two softer requirements live: the **coach behaviour**, which is
 *       wording about a band the engine already decided, and **"every number links to its
 *       evidence"**, which is the drawer below.
 * What: the runway headline, the coach line, the money, and the working behind all of it.
 * Result: the composition.
 * Changelog: 2026-09-02 — Created for issue 7.2.
 */
@Composable
fun EmergencyFundScreen(
    onDone: () -> Unit,
    viewModel: EmergencyFundViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    EmergencyFundContent(uiState = uiState, onEvent = viewModel::onEvent, onDone = onDone)
}

/**
 * The screen's body, with no ViewModel in sight.
 * Why:    separated so a test can drive every state directly — the reason every screen here splits
 *         this way (ARC-004).
 * Result: the composition. Input: [uiState]; [onEvent]; [onDone]. Output: none.
 * Changelog: 2026-09-02 — Created for issue 7.2.
 */
@Composable
internal fun EmergencyFundContent(
    uiState: EmergencyFundUiState,
    onEvent: (EmergencyFundEvent) -> Unit,
    onDone: () -> Unit,
) {
    // A plain scrolling Column rather than a LazyColumn, the reason `GoalsScreen` gives: the
    // disclaimer sits below everything and must be reachable, so the whole screen scrolls as one.
    // There is exactly one card here; there is nothing to virtualise.
    Column(
        modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(CfoDimens.spaceMd),
        verticalArrangement = Arrangement.spacedBy(CfoDimens.spaceMd),
    ) {
        Text(text = stringResource(R.string.emf_title), style = MaterialTheme.typography.headlineSmall)

        uiState.errorCode?.let {
            Text(text = stringResource(R.string.emf_error), color = CfoTheme.extendedColors.negative)
            CfoSecondaryButton(
                text = stringResource(R.string.emf_dismiss_error),
                onClick = { onEvent(EmergencyFundEvent.DismissError) },
            )
        }

        uiState.plan?.let { plan ->
            FundCard(plan = plan)
            EvidenceSection(plan = plan, isOpen = uiState.isEvidenceOpen, onEvent = onEvent)
        }

        CfoSecondaryButton(text = stringResource(R.string.emf_back), onClick = onDone)

        Text(
            text = stringResource(R.string.emf_disclaimer),
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

/**
 * The headline: where the user stands, and what closes the gap.
 * Why:    split out to keep [EmergencyFundContent] within the 40-line limit (§21.6).
 * Result: the composition. Input: [plan]. Output: none.
 */
@Composable
private fun FundCard(plan: EmergencyFundPlan) {
    CfoCard(modifier = Modifier.fillMaxWidth()) {
        Column(verticalArrangement = Arrangement.spacedBy(CfoDimens.spaceSm)) {
            Text(
                text = plan.runwayText(),
                style = MaterialTheme.typography.headlineMedium,
            )
            Text(
                text = stringResource(EmergencyFundLabels.status(plan.status)),
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                text = stringResource(EmergencyFundLabels.coach(plan.status)),
                style = MaterialTheme.typography.bodyMedium,
            )
            if (plan.status != EmergencyStatus.UNKNOWN) {
                Text(
                    text = stringResource(R.string.emf_target, MoneyFormatter.format(plan.target)),
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    text =
                        stringResource(
                            R.string.emf_saved_of_target,
                            MoneyFormatter.format(plan.liquidFunds),
                            MoneyFormatter.format(plan.target),
                        ),
                    style = MaterialTheme.typography.bodyMedium,
                )
                TopUpLine(plan = plan)
            }
            Text(text = stringResource(R.string.emf_rule), style = MaterialTheme.typography.labelSmall)
        }
    }
}

/**
 * What it takes to close the gap, or that there is none.
 *
 * Why:    **a separate composable because the two readings are not the same sentence.** Issue 7.1
 *         shipped a goal card that read "At ₹0.00 a month you get there 2026-08-30" for an already
 *         funded goal — arithmetically true, and absurd. A funded emergency fund must not say
 *         "₹0.00 a month closes it over 6 months"; it says there is nothing left to put aside.
 * Result: the composition. Input: [plan]. Output: none.
 */
@Composable
private fun TopUpLine(plan: EmergencyFundPlan) {
    val text =
        if (plan.isFunded) {
            stringResource(R.string.emf_fully_funded)
        } else {
            stringResource(
                R.string.emf_top_up,
                MoneyFormatter.format(plan.topUpMonthly),
                plan.multiplierMonths,
            )
        }
    Text(text = text, style = MaterialTheme.typography.bodyMedium)
    if (!plan.isFunded) {
        Text(
            text = stringResource(R.string.emf_shortfall, MoneyFormatter.format(plan.shortfall)),
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

/**
 * §10.1's "every number links to its evidence", as a drawer.
 *
 * Why:    collapsed by default so the headline leads, but present on every state — including
 *         `UNKNOWN`, where **the evidence is the whole answer**: the user needs to know what is
 *         missing, not merely that something is.
 * Result: the composition. Input: [plan]; [isOpen]; [onEvent]. Output: none.
 */
@Composable
private fun EvidenceSection(
    plan: EmergencyFundPlan,
    isOpen: Boolean,
    onEvent: (EmergencyFundEvent) -> Unit,
) {
    CfoSecondaryButton(
        text = stringResource(if (isOpen) R.string.emf_evidence_hide else R.string.emf_evidence_show),
        onClick = { onEvent(EmergencyFundEvent.ToggleEvidence) },
    )
    if (!isOpen) return
    CfoCard(modifier = Modifier.fillMaxWidth()) {
        Column(verticalArrangement = Arrangement.spacedBy(CfoDimens.spaceSm)) {
            Text(
                text = stringResource(R.string.emf_evidence_essentials, plan.essentialsText()),
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                text =
                    stringResource(
                        R.string.emf_evidence_multiplier,
                        plan.multiplierMonths,
                        plan.multiplierReason(),
                    ),
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(text = plan.essentialsCountedText(), style = MaterialTheme.typography.bodyMedium)
            Text(text = plan.liquidText(), style = MaterialTheme.typography.bodyMedium)
            Text(
                text = stringResource(R.string.emf_evidence_liquid_note),
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

/**
 * The runway as words.
 *
 * Why:    `runwayMonthsBps` is basis points of a month (MNY-002 admits no floating point), and
 *         "45000" is not a sentence. The whole and tenth parts are taken with integer division, so
 *         **no `Double` is constructed here either** — the rule holds all the way to the screen.
 * Result: "4.5 months of cover", or the unknown line. Input: the receiver. Output: [String].
 */
@Composable
private fun EmergencyFundPlan.runwayText(): String {
    val bps = runwayMonthsBps ?: return stringResource(R.string.emf_runway_unknown)
    val whole = bps / BPS_PER_MONTH
    val tenths = (bps % BPS_PER_MONTH) / TENTH_OF_A_MONTH_BPS
    return stringResource(R.string.emf_runway, "$whole.$tenths")
}

/** Result: the essentials figure and where it came from. Input: the receiver. Output: [String]. */
@Composable
private fun EmergencyFundPlan.essentialsText(): String {
    val basis = stringResource(EmergencyFundLabels.essentialsBasis(essentialsBasis))
    val amount = monthlyEssentials?.let { MoneyFormatter.format(it) }
    return if (amount == null) basis else "$amount — $basis"
}

/**
 * Why M came out as it did.
 *
 * Why:    §10.1's multiplier is the least obvious number on the screen, and a bare "held for 7
 *         months" invites the reasonable question "why seven?". Naming the volatility bump — or
 *         saying plainly that nothing was added, and whether that is because the income is steady or
 *         because there is too little of it to tell — is the difference between a figure and an
 *         explanation (P-02).
 * Result: the clause after "6 to start with". Input: the receiver. Output: [String].
 */
@Composable
private fun EmergencyFundPlan.multiplierReason(): String {
    val bump = multiplierMonths - BASE_MONTHS
    val reason =
        when {
            incomeCvBps == null -> stringResource(R.string.emf_multiplier_unmeasured)
            bump <= 0 -> stringResource(R.string.emf_multiplier_steady)
            else -> stringResource(R.string.emf_multiplier_bumped, bump)
        }
    return if (multiplierWasClamped) {
        "$reason, ${stringResource(R.string.emf_multiplier_clamped)}"
    } else {
        reason
    }
}

/**
 * What counted as essential, in the user's words rather than the domain's.
 *
 * Why:    the evidence list carries §8.3's `NEED` **nature**, not category names, and rendering it
 *         raw put **"Counted as essential: NEED"** on the screen — a domain token the user has met
 *         nowhere else in the app. Found by running it; no assertion about a figure could have.
 * Result: the sentence. Input: the receiver. Output: [String].
 */
@Composable
private fun EmergencyFundPlan.essentialsCountedText(): String {
    // `map`, not `joinToString`: `map` is inline, so `stringResource` is still called from a
    // composable scope. `joinToString`'s transform is not, and will not compile.
    val named =
        essentialCategoryNames.map { name ->
            EmergencyFundLabels.essentialCategory(name)?.let { stringResource(it) } ?: name
        }
    return stringResource(R.string.emf_evidence_categories, named.joinToString())
}

/** Result: which accounts counted, or that none did. Input: the receiver. Output: [String]. */
@Composable
private fun EmergencyFundPlan.liquidText(): String =
    if (liquidAccountNames.isEmpty()) {
        stringResource(R.string.emf_evidence_liquid, stringResource(R.string.emf_evidence_liquid_none))
    } else {
        stringResource(R.string.emf_evidence_liquid, liquidAccountNames.joinToString())
    }

/** 10 000 bps is one month, the scale `EmergencyFundPlan.runwayMonthsBps` uses (MNY-002). */
private const val BPS_PER_MONTH = 10_000

/** A tenth of a month, so the runway renders to one decimal place without floating point. */
private const val TENTH_OF_A_MONTH_BPS = 1_000

/** `RULE-EMF-MULT.base_months`, so the drawer can name the bump rather than only the total. */
private const val BASE_MONTHS = 6
