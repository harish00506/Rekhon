package com.aicfo.feature.settings

import android.content.Context
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import com.aicfo.core.designsystem.component.CfoButton
import com.aicfo.core.designsystem.component.CfoCard
import com.aicfo.core.designsystem.component.CfoSecondaryButton
import com.aicfo.core.designsystem.theme.CfoDimens
import com.aicfo.core.designsystem.theme.CfoTheme

/**
 * The encrypted-backup form (issue 8.1; SEC-005, P-01, F6).
 *
 * Why:  SEC-005 asks for two things a screen has to carry: a passphrase-derived backup, and "the
 *       recovery phrase screen makes irrecoverability explicit". The second is the one that matters
 *       to a user — the passphrase is the only key, the app never keeps it, and there is no reset.
 *       So the section will not create a backup until the user has ticked a sentence saying exactly
 *       that, and the button says why it is disabled rather than just being grey.
 * What: what a backup is, the passphrase twice, the acknowledgement, the button, and one line of
 *       status or hint beneath it.
 * Result: a user can make a backup that no one but they can open, knowing what losing the
 *       passphrase means.
 * Changelog: 2026-09-18 — Created for issue 8.1.
 *
 * Stateless, like the rest of `SettingsContent`: the file picker lives in [BackupFileHost], called
 * from the stateful screen — the split `ArchiveHost` records, and for the same reason (a launcher
 * needs a real Activity, so the body stays renderable in a test without one).
 *
 * Input:  [uiState] — the whole screen state, since the consent lives outside the backup form;
 *         [onEvent] — events up (ARC-004).
 * Output: the composition.
 */
@Composable
internal fun BackupSection(
    uiState: SettingsUiState,
    onEvent: (SettingsEvent) -> Unit,
) {
    val backup = uiState.backup
    CfoCard {
        Column(verticalArrangement = Arrangement.spacedBy(CfoDimens.spaceSm)) {
            Text(text = stringResource(R.string.settings_backup_title), style = MaterialTheme.typography.titleMedium)
            Text(
                text = stringResource(R.string.settings_backup_body),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            PassphraseField(
                value = backup.passphraseText,
                label = stringResource(R.string.settings_backup_passphrase),
                onValueChange = { onEvent(SettingsEvent.BackupPassphraseChanged(it)) },
            )
            PassphraseField(
                value = backup.confirmationText,
                label = stringResource(R.string.settings_backup_confirmation),
                onValueChange = { onEvent(SettingsEvent.BackupConfirmationChanged(it)) },
            )
            IrrecoverableAcknowledgement(checked = backup.acknowledged, onEvent = onEvent)
            CfoButton(
                text = stringResource(R.string.settings_backup_create),
                onClick = { onEvent(SettingsEvent.CreateBackup) },
                enabled = uiState.canCreateBackup,
            )
            BackupMessage(uiState = uiState, onEvent = onEvent)
        }
    }
}

/**
 * Owns the two system file pickers — save a backup, pick one to restore (issues 8.1, 8.2; SEC-005).
 *
 * Why:  SEC-005's "local backup to user-chosen storage (SAF)": the user picks the destination, the
 *       app needs no storage permission, and it never learns where the file went. The save picker
 *       opens only once the bytes exist, so a user is never asked where to save a backup that then
 *       fails. Both launchers live here, in the stateful half, and the stateless body gets a plain
 *       "pick a backup" lambda — the split `ArchiveHost` records, because a launcher needs a real
 *       Activity and the body must stay renderable in a test without one.
 * What: a `CreateDocument` launcher and the effect that launches it on
 *       [BackupStatus.ReadyToWrite]; an `OpenDocument` launcher that reads the picked file.
 * Result: sealed bytes in the file the user named; a picked backup's bytes handed up as an event.
 * Changelog: 2026-09-18 — Created for issue 8.1.
 *   2026-09-18 — Issue 8.2 added the open picker and the [content] slot.
 *
 * Input:  [status] — watched for [BackupStatus.ReadyToWrite]; [onEvent] — events up;
 *         [content] — the body, given the "pick a backup to restore" callback.
 * Output: the composition.
 */
@Composable
internal fun BackupFileHost(
    status: BackupStatus,
    onEvent: (SettingsEvent) -> Unit,
    content: @Composable (onPickBackup: () -> Unit) -> Unit,
) {
    val context = LocalContext.current
    val createDocument =
        rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(BACKUP_MIME)) { uri ->
            val bytes = (status as? BackupStatus.ReadyToWrite)?.bytes
            // A null uri is the user backing out of the picker: not a failure, and not reported as one.
            when {
                uri == null || bytes == null -> onEvent(SettingsEvent.BackupDismissed)
                else -> onEvent(SettingsEvent.BackupWritten(context.writeBytes(uri, bytes)))
            }
        }
    val openDocument =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            // Backing out of the picker leaves the restore card where it was.
            if (uri != null) {
                val bytes = context.readBytes(uri)
                onEvent(
                    if (bytes == null) SettingsEvent.RestoreFileUnreadable else SettingsEvent.RestoreFilePicked(bytes),
                )
            }
        }

    LaunchedEffect(status) {
        if (status is BackupStatus.ReadyToWrite) createDocument.launch(BACKUP_FILE_NAME)
    }

    // Any file type: a backup copied through a messaging app or a cloud drive often loses its
    // extension and its type, and the cipher's own header check rejects anything that is not one.
    content { openDocument.launch(arrayOf("*/*")) }
}

/**
 * One passphrase field.
 * Why:    masked, because a passphrase on screen defeats the shoulder-surfing threat the same way a
 *         visible PIN would (`CfoPinField` records the argument); a password keyboard, so the IME
 *         neither suggests nor learns it.
 * Result: the composition. Input: [value]; [label]; [onValueChange]. Output: none.
 * Changelog: 2026-09-18 — Created for issue 8.1.
 */
@Composable
private fun PassphraseField(
    value: String,
    label: String,
    onValueChange: (String) -> Unit,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(text = label) },
        singleLine = true,
        visualTransformation = PasswordVisualTransformation(),
        keyboardOptions =
            KeyboardOptions(
                autoCorrectEnabled = false,
                keyboardType = KeyboardType.Password,
                imeAction = ImeAction.Next,
            ),
        modifier = Modifier.fillMaxWidth(),
    )
}

/**
 * SEC-005's acknowledgement: a lost passphrase is a lost backup.
 * Why:    the whole row is the toggle and is announced as one checkbox with its sentence, so a
 *         screen-reader user hears what they are agreeing to rather than an unlabelled box.
 * Result: the composition. Input: [checked]; [onEvent]. Output: none.
 * Changelog: 2026-09-18 — Created for issue 8.1.
 */
@Composable
private fun IrrecoverableAcknowledgement(
    checked: Boolean,
    onEvent: (SettingsEvent) -> Unit,
) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .toggleable(
                    value = checked,
                    role = Role.Checkbox,
                    onValueChange = { onEvent(SettingsEvent.BackupAcknowledged(it)) },
                ),
        horizontalArrangement = Arrangement.spacedBy(CfoDimens.spaceSm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(checked = checked, onCheckedChange = null)
        Text(text = stringResource(R.string.settings_backup_irrecoverable), style = MaterialTheme.typography.bodyMedium)
    }
}

/**
 * The line under the button: what happened, or what is still missing.
 * Why:    a status when there is one — the result is what the user just asked about — and otherwise
 *         the next blocker, so a disabled button always has its reason beside it (P-02's spirit).
 * Result: at most one line, plus a dismiss button after a result. Input: [uiState]; [onEvent].
 * Output: the composition.
 * Changelog: 2026-09-18 — Created for issue 8.1.
 */
@Composable
private fun BackupMessage(
    uiState: SettingsUiState,
    onEvent: (SettingsEvent) -> Unit,
) {
    val status = uiState.backup.status
    val text =
        when (status) {
            BackupStatus.Sealing -> R.string.settings_backup_sealing
            BackupStatus.Written -> R.string.settings_backup_written
            is BackupStatus.Failed -> status.code.toBackupMessage()
            // ReadyToWrite has the system picker over it; Idle says what is missing, if anything.
            is BackupStatus.ReadyToWrite -> null
            BackupStatus.Idle -> uiState.backupBlocker?.toHint()
        } ?: return

    Text(
        text = stringResource(text),
        style = MaterialTheme.typography.bodySmall,
        color =
            if (status is BackupStatus.Failed) {
                CfoTheme.extendedColors.negative
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
    )
    if (status == BackupStatus.Written || status is BackupStatus.Failed) {
        CfoSecondaryButton(
            text = stringResource(R.string.settings_dismiss),
            onClick = { onEvent(SettingsEvent.BackupDismissed) },
        )
    }
}

/**
 * The hint for each blocker.
 * Why:    a `when` over the enum, so a new blocker fails to compile until it has words.
 * Result: a string resource. Input: the receiver. Output: a resource id.
 * Changelog: 2026-09-18 — Created for issue 8.1.
 */
private fun BackupBlocker.toHint(): Int =
    when (this) {
        BackupBlocker.CONSENT -> R.string.settings_backup_needs_consent
        BackupBlocker.TOO_SHORT -> R.string.settings_backup_too_short
        BackupBlocker.MISMATCH -> R.string.settings_backup_mismatch
        BackupBlocker.NOT_ACKNOWLEDGED -> R.string.settings_backup_needs_acknowledgement
    }

/**
 * Maps a failure code to the line the user reads.
 * Why:    the repository returns codes, never sentences (§21.6). The consent and passphrase refusals
 *         reuse their hints — the user's next step is the same whichever layer noticed.
 * Result: a string resource. Input: the receiver — an `AppError` code or field. Output: a resource id.
 * Changelog: 2026-09-18 — Created for issue 8.1.
 */
private fun String.toBackupMessage(): Int =
    when (this) {
        "backup.consent" -> R.string.settings_backup_needs_consent
        "backup.passphrase" -> R.string.settings_backup_too_short
        "backup.writeFailed" -> R.string.settings_backup_write_failed
        else -> R.string.settings_backup_failed
    }

/**
 * Writes the sealed backup to the file the user picked.
 * Why:    here rather than in the ViewModel, because it needs a `ContentResolver` and a `Uri`'s grant
 *         belongs to this Activity (`ArchiveSection` records the argument). `"wt"` truncates, so
 *         overwriting a longer old backup cannot leave its tail behind a shorter new one — which
 *         would fail the GCM tag and make a good backup look corrupt.
 * Result: `true` when written. Input: the receiver; [uri]; [bytes]. Output: [Boolean].
 * Changelog: 2026-09-18 — Created for issue 8.1.
 */
private fun Context.writeBytes(
    uri: Uri,
    bytes: ByteArray,
): Boolean =
    runCatching {
        contentResolver.openOutputStream(uri, "wt")?.use { it.write(bytes) } ?: return false
        true
    }.getOrDefault(false)

/**
 * Reads a picked backup into memory, up to [MAX_BACKUP_BYTES].
 * Why:    read here, not passed on as a `Uri`, because the `Uri`'s grant belongs to this Activity
 *         and does not survive process death (`ArchiveSection.readText` records the argument).
 *         **Bounded**, because the picker offers any file: an unbounded `readBytes` on a picked
 *         video would take the app down with an out-of-memory crash before the cipher's header
 *         check ever saw it. A real backup is a few hundred kilobytes; §22 caps the blob at 50 MB.
 * Result: the bytes; `null` when the file could not be opened or is larger than a backup can be.
 * Input:  the receiver; [uri]. Output: `ByteArray?`.
 * Changelog: 2026-09-18 — Created for issue 8.2.
 */
private fun Context.readBytes(uri: Uri): ByteArray? =
    runCatching {
        contentResolver.openInputStream(uri)?.use { input -> input.readAtMost(MAX_BACKUP_BYTES) }
    }.getOrNull()

/**
 * Reads a stream to its end, or gives up past [limit] bytes.
 * Why:    `InputStream.readNBytes` is API 33 and minSdk is 26.
 * Result: the bytes, or `null` when the stream holds more than [limit].
 * Input:  the receiver; [limit] — the most bytes accepted. Output: `ByteArray?`.
 * Changelog: 2026-09-18 — Created for issue 8.2.
 */
internal fun java.io.InputStream.readAtMost(limit: Int): ByteArray? {
    val out = java.io.ByteArrayOutputStream()
    val buffer = ByteArray(READ_BUFFER_BYTES)
    while (true) {
        val read = read(buffer)
        if (read < 0) return out.toByteArray()
        if (out.size() + read > limit) return null
        out.write(buffer, 0, read)
    }
}

/** §22's ceiling for a backup blob; anything larger is not a backup this app wrote. */
internal const val MAX_BACKUP_BYTES = 50 * 1024 * 1024

/** How much of a picked file is read at a time. */
private const val READ_BUFFER_BYTES = 64 * 1024

/** The file is opaque ciphertext; nothing should try to open it as anything else. */
private const val BACKUP_MIME = "application/octet-stream"

/** What the picker suggests. The extension says what it is; the user can rename it. */
private const val BACKUP_FILE_NAME = "ai-personal-cfo.cfobackup"
