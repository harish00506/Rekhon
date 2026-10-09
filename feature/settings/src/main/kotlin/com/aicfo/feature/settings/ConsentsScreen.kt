package com.aicfo.feature.settings

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
import com.aicfo.core.datastore.ConsentFeature
import com.aicfo.core.designsystem.component.CfoCard
import com.aicfo.core.designsystem.component.CfoSecondaryButton
import com.aicfo.core.designsystem.theme.CfoDimens
import com.aicfo.core.model.DateFormatter

/**
 * The consents dashboard (issue 11.3; §23, P-01, DPDP).
 *
 * Why:  P-01's promise is consent that is explicit, per-feature and revocable — and a promise the
 *       user can only *operate* is one they have to take on trust. Until this screen the app had a
 *       row of switches; what it did not have was a place that said what each consent is **for**,
 *       what **stops** without it, and **when** it was given. The ledger has recorded the last of
 *       those since issue 1.9 and nothing ever showed it.
 * What: one card per consent, with its purpose, its consequence, its dates, and one tap.
 * Result: a control surface a person can audit rather than merely flip.
 * Changelog: 2026-10-01 — Created for issue 11.3.
 *
 * Input:  [onDone]; [modifier]; [viewModel] — injected. Output: the screen.
 */
@Composable
fun ConsentsScreen(
    onDone: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: ConsentsViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    ConsentsContent(uiState, viewModel::onEvent, onDone, modifier)
}

/**
 * The screen as a function of its state (ARC-004).
 * Result: the rendered screen. Input: [uiState]; [onEvent]; [onDone]; [modifier]. Output: none.
 */
@Composable
internal fun ConsentsContent(
    uiState: ConsentsUiState,
    onEvent: (ConsentsEvent) -> Unit,
    onDone: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(CfoDimens.spaceMd),
        verticalArrangement = Arrangement.spacedBy(CfoDimens.spaceMd),
    ) {
        Text(stringResource(R.string.consents_title), style = MaterialTheme.typography.headlineSmall)
        Text(
            stringResource(R.string.consents_intro),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        uiState.errorCode?.let {
            CfoCard {
                Column(verticalArrangement = Arrangement.spacedBy(CfoDimens.spaceSm)) {
                    Text(stringResource(R.string.consents_error), color = MaterialTheme.colorScheme.error)
                    CfoSecondaryButton(
                        text = stringResource(R.string.consents_dismiss),
                        onClick = { onEvent(ConsentsEvent.DismissError) },
                    )
                }
            }
        }
        uiState.rows.forEach { row -> ConsentCard(row, onEvent) }
        Text(stringResource(R.string.consents_nothing_leaves), style = MaterialTheme.typography.labelSmall)
        CfoSecondaryButton(text = stringResource(R.string.consents_done), onClick = onDone)
    }
}

/**
 * One consent: what it is for, what stops without it, when it was given, and the tap.
 * Why:    the consequence line is not decoration. "Withdraw" on its own asks the user to guess what
 *         they are about to break, and a privacy control people are afraid to use is one they leave
 *         on.
 * Result: the card. Input: [row]; [onEvent]. Output: none.
 * Changelog: 2026-10-01 — Created for issue 11.3.
 */
@Composable
private fun ConsentCard(
    row: ConsentRow,
    onEvent: (ConsentsEvent) -> Unit,
) {
    CfoCard {
        Column(verticalArrangement = Arrangement.spacedBy(CfoDimens.spaceSm)) {
            Text(stringResource(row.feature.label()), style = MaterialTheme.typography.titleMedium)
            Text(
                stringResource(row.feature.purpose()),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(stringResource(row.feature.whenWithdrawn()), style = MaterialTheme.typography.bodySmall)
            Text(historyOf(row), style = MaterialTheme.typography.labelMedium)
            if (row.granted) {
                CfoSecondaryButton(
                    text = stringResource(R.string.consents_withdraw),
                    onClick = { onEvent(ConsentsEvent.Revoked(row.feature)) },
                )
            } else {
                CfoSecondaryButton(
                    text = stringResource(R.string.consents_allow),
                    onClick = { onEvent(ConsentsEvent.Granted(row.feature)) },
                )
            }
        }
    }
}

/**
 * The one line that says what the user actually did, and when.
 * Why:    three states, not two. "Never given" and "withdrawn" are different facts about a person,
 *         and a screen that showed both as "off" would misreport their own history back to them —
 *         which is the opposite of what a consent record is for.
 * Result: the sentence. Input: [row]. Output: [String].
 * Changelog: 2026-10-01 — Created for issue 11.3.
 */
@Composable
private fun historyOf(row: ConsentRow): String =
    when {
        row.granted && row.grantedOnIsoDate != null ->
            stringResource(R.string.consents_in_use_since, DateFormatter.day(row.grantedOnIsoDate))
        row.revokedOnIsoDate != null && row.grantedOnIsoDate != null ->
            stringResource(
                R.string.consents_withdrawn_on,
                DateFormatter.day(row.revokedOnIsoDate),
                DateFormatter.day(row.grantedOnIsoDate),
            )
        else -> stringResource(R.string.consents_never_given)
    }

/**
 * Result: what this consent is used for, in a sentence. Input: the receiver. Output: a resource id.
 * Why:    a `when`, so a consent added to the enum cannot ship without one — DPDP's purpose
 *         limitation is only meaningful if the purpose is stated where the user agrees to it.
 * Changelog: 2026-10-01 — Created for issue 11.3.
 */
internal fun ConsentFeature.purpose(): Int =
    when (this) {
        ConsentFeature.SMS_PARSING -> R.string.consent_sms_parsing_purpose
        ConsentFeature.MARKET_DATA -> R.string.consent_market_data_purpose
        ConsentFeature.CLOUD_LLM -> R.string.consent_cloud_llm_purpose
        ConsentFeature.CLOUD_BACKUP -> R.string.consent_cloud_backup_purpose
        ConsentFeature.ACCOUNT_AGGREGATOR -> R.string.consent_account_aggregator_purpose
    }

/**
 * Result: what stops if this consent is withdrawn. Input: the receiver. Output: a resource id.
 * Why:    the honest half of a revoke button, and the one that has to stay true to the code: each
 *         sentence describes what the repository that owns the feature actually does when the
 *         consent goes away.
 * Changelog: 2026-10-01 — Created for issue 11.3.
 */
internal fun ConsentFeature.whenWithdrawn(): Int =
    when (this) {
        ConsentFeature.SMS_PARSING -> R.string.consent_sms_parsing_withdrawn
        ConsentFeature.MARKET_DATA -> R.string.consent_market_data_withdrawn
        ConsentFeature.CLOUD_LLM -> R.string.consent_cloud_llm_withdrawn
        ConsentFeature.CLOUD_BACKUP -> R.string.consent_cloud_backup_withdrawn
        ConsentFeature.ACCOUNT_AGGREGATOR -> R.string.consent_account_aggregator_withdrawn
    }
