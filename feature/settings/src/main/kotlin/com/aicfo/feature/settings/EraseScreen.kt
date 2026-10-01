package com.aicfo.feature.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aicfo.core.designsystem.component.CfoButton
import com.aicfo.core.designsystem.component.CfoCard
import com.aicfo.core.designsystem.component.CfoPinField
import com.aicfo.core.designsystem.component.CfoSecondaryButton
import com.aicfo.core.designsystem.theme.CfoDimens

/**
 * Erase everything (issue 11.4; §23, §34, SEC-003, P-01).
 *
 * Why:  the DPDP right to erasure, and the thing a privacy-first app has to be able to do on
 *       demand: leave nothing behind. The screen's whole job is to make an irreversible action
 *       **deliberate** — it says what will go, says plainly what it cannot reach, asks the user to
 *       type a word, and then asks who they are. None of that is friction for its own sake: an
 *       erase reached by one tap from a settings list is an erase that happens by accident.
 * What: the explanation, the two gates, the button, and the final state.
 * Result: a user who can exercise erasure, and cannot stumble into it.
 * Changelog: 2026-10-01 — Created for issue 11.4.
 *
 * Input:  [onDone] — leave the screen; [onErased] — what the host does once the data is gone, which
 *         is to close the app: every open handle in the process points at a database whose key no
 *         longer exists, so continuing would be a sequence of crashes. [modifier]; [viewModel].
 * Output: the screen.
 */
@Composable
fun EraseScreen(
    onDone: () -> Unit,
    onErased: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: EraseViewModel = hiltViewModel(),
) {
    val word = stringResource(R.string.erase_confirmation_word)
    // The gate compares against the *translated* word, so a Hindi user confirms in Hindi. The view
    // model has no Context and must never hold an English literal to compare against (issue 10.8).
    LaunchedEffect(word) { viewModel.onWordLoaded(word) }
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    EraseContent(uiState, viewModel::onEvent, onDone, onErased, modifier)
}

/**
 * The screen as a function of its state (ARC-004).
 * Result: the rendered screen. Input: [uiState]; [onEvent]; [onDone]; [onErased]; [modifier].
 * Output: none.
 * Changelog: 2026-10-01 — Created for issue 11.4.
 */
@Composable
internal fun EraseContent(
    uiState: EraseUiState,
    onEvent: (EraseEvent) -> Unit,
    onDone: () -> Unit,
    onErased: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(CfoDimens.spaceMd),
        verticalArrangement = Arrangement.spacedBy(CfoDimens.spaceMd),
    ) {
        Text(stringResource(R.string.erase_title), style = MaterialTheme.typography.headlineSmall)
        if (uiState.isErased) {
            ErasedCard(onErased)
        } else {
            EraseExplanation()
            uiState.errorCode?.let { code -> ErrorCard(code, onEvent) }
            EraseGates(uiState, onEvent)
            CfoButton(
                text = stringResource(R.string.erase_action),
                onClick = { onEvent(EraseEvent.Confirmed) },
                enabled = uiState.canErase,
            )
            CfoSecondaryButton(text = stringResource(R.string.erase_cancel), onClick = onDone)
        }
    }
}

/**
 * What an erase does, and the one thing it cannot do.
 * Why:    the honest paragraph. Destroying the Keystore key makes everything on this device
 *         unreadable, but a backup the user exported was sealed with *their* passphrase and sits
 *         wherever they put it — this app never had its key and cannot reach it. Saying "everything
 *         is gone" would be the one lie this feature must not tell (ADR-0060).
 * Result: the composition. Input: none. Output: none.
 * Changelog: 2026-10-01 — Created for issue 11.4.
 */
@Composable
private fun EraseExplanation() {
    CfoCard {
        Column(verticalArrangement = Arrangement.spacedBy(CfoDimens.spaceSm)) {
            Text(stringResource(R.string.erase_what_goes), style = MaterialTheme.typography.bodyMedium)
            Text(
                stringResource(R.string.erase_irreversible),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
            )
            Text(
                stringResource(R.string.erase_backups_not_reached),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * The two gates: type the word, then prove who you are.
 * Why:    §34 asks for explicit confirmation **and** authentication. The PIN field is absent rather
 *         than disabled when no PIN is set, because an empty field a user cannot satisfy reads as a
 *         broken screen (SEC-002 makes the lock optional).
 * Result: the composition. Input: [uiState]; [onEvent]. Output: none.
 * Changelog: 2026-10-01 — Created for issue 11.4.
 */
@Composable
private fun EraseGates(
    uiState: EraseUiState,
    onEvent: (EraseEvent) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(CfoDimens.spaceSm)) {
        Text(
            stringResource(R.string.erase_type_to_confirm, uiState.confirmationWord),
            style = MaterialTheme.typography.bodyMedium,
        )
        OutlinedTextField(
            value = uiState.confirmationText,
            onValueChange = { onEvent(EraseEvent.ConfirmationTyped(it)) },
            label = { Text(stringResource(R.string.erase_confirmation_label)) },
            singleLine = true,
            enabled = !uiState.isErasing,
            keyboardOptions =
                KeyboardOptions(
                    autoCorrectEnabled = false,
                    capitalization = KeyboardCapitalization.Characters,
                    imeAction = ImeAction.Next,
                ),
            modifier = Modifier.fillMaxWidth(),
        )
        if (uiState.isPinRequired) {
            CfoPinField(
                value = uiState.pinText,
                onValueChange = { onEvent(EraseEvent.PinTyped(it)) },
                label = stringResource(R.string.erase_pin_label),
                enabled = !uiState.isErasing,
            )
        }
    }
}

/**
 * It is done.
 * Why:    the app cannot carry on. Every handle in this process points at a database whose key no
 *         longer exists, so the only honest next step is to close — and the user is told that
 *         rather than being dropped into a dashboard that is about to fail.
 * Result: the composition. Input: [onErased]. Output: none.
 * Changelog: 2026-10-01 — Created for issue 11.4.
 */
@Composable
private fun ErasedCard(onErased: () -> Unit) {
    CfoCard {
        Column(verticalArrangement = Arrangement.spacedBy(CfoDimens.spaceSm)) {
            Text(stringResource(R.string.erase_done), style = MaterialTheme.typography.titleMedium)
            Text(stringResource(R.string.erase_done_detail), style = MaterialTheme.typography.bodyMedium)
            CfoButton(text = stringResource(R.string.erase_close_app), onClick = onErased)
        }
    }
}

/**
 * A refused PIN, or an erase that did not finish.
 * Why:    the second case is the one that must never be smoothed over: the user has been told their
 *         data is about to become unrecoverable and it has not. The message says the erase did not
 *         complete and the data is still there, because a reassuring banner over a failed shred is
 *         worse than no banner at all.
 * Result: the composition. Input: [code]; [onEvent]. Output: none.
 * Changelog: 2026-10-01 — Created for issue 11.4.
 */
@Composable
private fun ErrorCard(
    code: String,
    onEvent: (EraseEvent) -> Unit,
) {
    CfoCard {
        Column(verticalArrangement = Arrangement.spacedBy(CfoDimens.spaceSm)) {
            Text(
                stringResource(if (code == "erase.pin") R.string.erase_error_pin else R.string.erase_error_failed),
                color = MaterialTheme.colorScheme.error,
            )
            CfoSecondaryButton(
                text = stringResource(R.string.erase_dismiss),
                onClick = { onEvent(EraseEvent.DismissError) },
            )
        }
    }
}
