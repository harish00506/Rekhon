package com.aicfo.feature.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import com.aicfo.core.designsystem.component.CfoButton
import com.aicfo.core.designsystem.component.CfoCard
import com.aicfo.core.designsystem.component.CfoSecondaryButton
import com.aicfo.core.designsystem.theme.CfoDimens
import com.aicfo.core.designsystem.theme.CfoTheme

/**
 * Restoring an encrypted backup (issue 8.2; SEC-005, P-07, F6).
 *
 * Why:  F6's "new device: restore → decrypt → done". Restoring replaces everything on the device,
 *       so it is two deliberate steps — pick the file, then type its passphrase and press a button
 *       whose label says *replace everything* — and the warning sits beside that button rather than
 *       in a dialog the user dismisses by reflex. **No consent switch gates it**: P-01 is about data
 *       leaving the device, and a restore brings it in from a file the user chose.
 * What: a "restore from a backup" button; once a file is picked, the warning, the passphrase field,
 *       the confirm and cancel buttons, and the last attempt's failure; then the row count.
 * Result: a wiped or new phone rebuilt from a backup, with a number the user can check.
 * Changelog: 2026-09-18 — Created for issue 8.2.
 *
 * Input:  [state] — the restore flow; [onEvent] — events up; [onPickBackup] — opens the system
 *         picker, supplied by the stateful screen (`BackupFileHost` owns the launcher).
 * Output: the composition.
 */
@Composable
internal fun RestoreSection(
    state: RestoreUiState,
    onEvent: (SettingsEvent) -> Unit,
    onPickBackup: () -> Unit,
) {
    CfoCard {
        Column(verticalArrangement = Arrangement.spacedBy(CfoDimens.spaceSm)) {
            Text(text = stringResource(R.string.settings_restore_title), style = MaterialTheme.typography.titleMedium)
            Text(
                text = stringResource(R.string.settings_restore_body),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            when (val status = state.status) {
                is RestoreStatus.Picked -> PickedBackup(state = state, failure = status.failure, onEvent = onEvent)
                RestoreStatus.Restoring -> StatusLine(stringResource(R.string.settings_restore_working))
                is RestoreStatus.Restored ->
                    Finished(
                        pluralStringResource(R.plurals.settings_restore_done, status.rows, status.rows),
                        onEvent,
                    )
                is RestoreStatus.Failed ->
                    Finished(
                        stringResource(status.code.toRestoreMessage()),
                        onEvent,
                        isFailure = true,
                    )
                RestoreStatus.Idle ->
                    CfoSecondaryButton(text = stringResource(R.string.settings_restore_pick), onClick = onPickBackup)
            }
        }
    }
}

/**
 * The staged file: what will happen, its passphrase, and the two ways out.
 * Why:    the warning is the P-07 confirmation, so it is on screen for as long as the button is.
 * Result: the composition. Input: [state]; [failure] — the last attempt's code; [onEvent].
 * Output: none.
 * Changelog: 2026-09-18 — Created for issue 8.2.
 */
@Composable
private fun PickedBackup(
    state: RestoreUiState,
    failure: String?,
    onEvent: (SettingsEvent) -> Unit,
) {
    Text(
        text = stringResource(R.string.settings_restore_warning),
        style = MaterialTheme.typography.bodyMedium,
        color = CfoTheme.extendedColors.negative,
    )
    OutlinedTextField(
        value = state.passphraseText,
        onValueChange = { onEvent(SettingsEvent.RestorePassphraseChanged(it)) },
        label = { Text(text = stringResource(R.string.settings_restore_passphrase)) },
        singleLine = true,
        visualTransformation = PasswordVisualTransformation(),
        keyboardOptions =
            KeyboardOptions(
                autoCorrectEnabled = false,
                keyboardType = KeyboardType.Password,
                imeAction = ImeAction.Done,
            ),
        modifier = Modifier.fillMaxWidth(),
    )
    failure?.let { StatusLine(stringResource(it.toRestoreMessage()), isFailure = true) }
    CfoButton(
        text = stringResource(R.string.settings_restore_confirm),
        onClick = { onEvent(SettingsEvent.ConfirmRestore) },
        enabled = state.canConfirm,
    )
    CfoSecondaryButton(
        text = stringResource(R.string.settings_restore_cancel),
        onClick = { onEvent(SettingsEvent.CancelRestore) },
    )
}

/**
 * A result, and the button that clears it.
 * Result: the composition. Input: [text]; [onEvent]; [isFailure]. Output: none.
 * Changelog: 2026-09-18 — Created for issue 8.2.
 */
@Composable
private fun Finished(
    text: String,
    onEvent: (SettingsEvent) -> Unit,
    isFailure: Boolean = false,
) {
    StatusLine(text, isFailure)
    CfoSecondaryButton(
        text = stringResource(R.string.settings_dismiss),
        onClick = { onEvent(SettingsEvent.CancelRestore) },
    )
}

/**
 * One line of status.
 * Result: the composition. Input: [text]; [isFailure] — coloured as a failure. Output: none.
 * Changelog: 2026-09-18 — Created for issue 8.2.
 */
@Composable
private fun StatusLine(
    text: String,
    isFailure: Boolean = false,
) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color =
            if (isFailure) {
                CfoTheme.extendedColors.negative
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
    )
}

/**
 * Maps a restore failure code to the line the user reads.
 * Why:    each refusal has a different next step — retype the passphrase, pick another file,
 *         update the app, leave the demo — so each gets its own words (§21.6: codes in, copy here).
 *         `crypto` is a wrong passphrase **or** a damaged file, deliberately indistinguishable, and
 *         the copy says both.
 * Result: a string resource. Input: the receiver — an `AppError` code or field. Output: a resource id.
 * Changelog: 2026-09-18 — Created for issue 8.2.
 */
private fun String.toRestoreMessage(): Int =
    when (this) {
        "crypto" -> R.string.settings_restore_wrong_passphrase
        "backup.format", "archive.unreadable", "restore.unreadable" -> R.string.settings_restore_not_a_backup
        "backup.version", "archive.schemaVersion" -> R.string.settings_restore_wrong_version
        "archive.profile" -> R.string.settings_restore_wrong_profile
        else -> R.string.settings_restore_failed
    }
