package com.aicfo.feature.dashboard

import android.content.Context
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.aicfo.core.designsystem.component.CfoSecondaryButton
import com.aicfo.core.designsystem.theme.CfoTheme

/**
 * The export/import controls and everything they can say (issue 5.4; §5.10, §34, P-01).
 *
 * Why:  its own file rather than more of `DashboardScreen.kt`, which is already the longest screen
 *       in the app — and because this is the one part of the dashboard that owns Android types. The
 *       system file picker hands back a `Uri`, and reading or writing it needs a `ContentResolver`;
 *       both stay here, and the ViewModel deals only in text. That is the same split
 *       `ReceiptCapture.kt` makes for photos, and the reason is the same: a `Uri`'s read grant
 *       belongs to the activity that received it and does not survive process death.
 * What: two buttons, the two SAF launchers, the confirmation the destructive half needs, and the
 *       result messages.
 * Result: a user can take their data out and put it back, without a cloud and without the app
 *       having a network path at all.
 * Changelog: 2026-08-16 — Created for issue 5.4.
 *
 * Input:  [state] — where the feature is; [onEvent] — events up (ARC-004).
 * Output: the composition.
 */
@Composable
internal fun ArchiveSection(
    state: ArchiveUiState,
    onEvent: (DashboardEvent) -> Unit,
    onPickArchive: () -> Unit,
) {
    CfoSecondaryButton(
        text = stringResource(R.string.dashboard_export_action),
        onClick = { onEvent(DashboardEvent.ExportRequested) },
    )
    CfoSecondaryButton(
        text = stringResource(R.string.dashboard_import_action),
        onClick = onPickArchive,
    )

    ArchiveMessage(state = state, onEvent = onEvent)

    if (state is ArchiveUiState.PendingImport) {
        ImportConfirmation(onEvent = onEvent)
    }
}

/**
 * Owns the two system file pickers (issue 5.4).
 *
 * Why:  **not inside [ArchiveSection], and a screenshot test is what found the reason.**
 *       `rememberLauncherForActivityResult` needs an `ActivityResultRegistryOwner`, which exists
 *       only under a real Activity — so putting it in the stateless body made `DashboardContent`
 *       impossible to render in Paparazzi, breaking the property that composable's own doc comment
 *       claims: previewable and screenshot-testable without Hilt or navigation. Every screenshot in
 *       this module went red at once.
 *
 *       So the launchers live here, called from the **stateful** `DashboardScreen`, and the body
 *       receives a plain lambda — the same seam that keeps `NavController` out of the body.
 * What: the two launchers, plus the effect that writes the archive once it exists.
 * Result: the body is handed one callback and nothing Android-shaped.
 * Changelog: 2026-08-16 — Created for issue 5.4.
 *
 * Input:  [state] — watched for [ArchiveUiState.ReadyToWrite]; [onEvent] — events up;
 *         [content] — the body, given the "pick an archive to import" callback.
 * Output: the composition.
 */
@Composable
internal fun ArchiveHost(
    state: ArchiveUiState,
    onEvent: (DashboardEvent) -> Unit,
    content: @Composable (onPickArchive: () -> Unit) -> Unit,
) {
    val context = LocalContext.current

    // CreateDocument, not a path: the user chooses where their own data goes, and the app never
    // needs a storage permission to write it.
    val createDocument =
        rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(ARCHIVE_MIME)) { uri ->
            val json = (state as? ArchiveUiState.ReadyToWrite)?.json
            // A null uri is the user backing out of the picker, which is not a failure and must not
            // be reported as one — it simply returns the screen to rest.
            when {
                uri == null || json == null -> onEvent(DashboardEvent.ArchiveMessageDismissed)
                else -> onEvent(DashboardEvent.ExportWritten(context.writeText(uri, json)))
            }
        }

    val openDocument =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            val json = uri?.let(context::readText)
            if (json != null) onEvent(DashboardEvent.ImportPicked(json))
        }

    // The picker opens only once the JSON exists, so the file is written the moment the user names
    // it — rather than asking for a destination and then discovering the export failed.
    LaunchedEffect(state) {
        if (state is ArchiveUiState.ReadyToWrite) createDocument.launch(ARCHIVE_FILE_NAME)
    }

    content { openDocument.launch(arrayOf(ARCHIVE_MIME)) }
}

/**
 * What the archive feature has to say, if anything (issue 5.4; P-02).
 * Why:    a row count rather than "done" — after an operation that replaced everything the user had,
 *         "imported" alone is a claim they have to take on trust. A number is one they can check
 *         against the file they picked and the dashboard a second later.
 * Result: one line, or nothing. Input: [state]; [onEvent]. Output: the composition.
 * Changelog: 2026-08-16 — Created for issue 5.4.
 */
@Composable
private fun ArchiveMessage(
    state: ArchiveUiState,
    onEvent: (DashboardEvent) -> Unit,
) {
    val text =
        when (state) {
            is ArchiveUiState.Working -> stringResource(R.string.dashboard_archive_working)
            ArchiveUiState.Exported -> stringResource(R.string.dashboard_archive_exported)
            is ArchiveUiState.Imported ->
                stringResource(R.string.dashboard_archive_imported, state.rows)
            is ArchiveUiState.Failed -> stringResource(state.code.toArchiveMessage())
            // ReadyToWrite and PendingImport are both mid-flight with their own UI — a message here
            // would sit under a dialog the user has not answered yet.
            else -> null
        } ?: return

    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color =
            if (state is ArchiveUiState.Failed) {
                CfoTheme.extendedColors.negative
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
    )
    if (state !is ArchiveUiState.Working) {
        CfoSecondaryButton(
            text = stringResource(R.string.dashboard_archive_dismiss),
            onClick = { onEvent(DashboardEvent.ArchiveMessageDismissed) },
        )
    }
}

/**
 * The confirmation before an import replaces everything (issue 5.4; P-07).
 *
 * Why:    this is the only operation in the app that destroys data the user did not select. §5.10
 *         asks for a restore, and a restore *is* a replace — but "advice, never orders" (P-07) cuts
 *         both ways: the app must not act on the user's whole dataset because they tapped a button
 *         next to "Export". The dialog says what will happen in the words that matter — everything
 *         on this device is replaced — rather than a generic "are you sure?".
 * Result: the dialog. Input: [onEvent]. Output: the composition.
 * Changelog: 2026-08-16 — Created for issue 5.4.
 */
@Composable
private fun ImportConfirmation(onEvent: (DashboardEvent) -> Unit) {
    AlertDialog(
        onDismissRequest = { onEvent(DashboardEvent.ImportCancelled) },
        title = { Text(stringResource(R.string.dashboard_import_confirm_title)) },
        text = { Text(stringResource(R.string.dashboard_import_confirm_body)) },
        confirmButton = {
            TextButton(onClick = { onEvent(DashboardEvent.ImportConfirmed) }) {
                Text(stringResource(R.string.dashboard_import_confirm_action))
            }
        },
        dismissButton = {
            TextButton(onClick = { onEvent(DashboardEvent.ImportCancelled) }) {
                Text(stringResource(R.string.dashboard_import_cancel_action))
            }
        },
    )
}

/**
 * Maps a failure code to the line the user reads (issue 5.4; §21.6).
 * Why:    `ArchiveRepository` returns codes, never sentences — the wording belongs here, and a
 *         serialisation exception's own message can quote the user's financial data. The two archive
 *         codes get their own copy because the user's next step differs: the wrong file is a
 *         different problem from the right file and the wrong app version.
 * Result: a string resource. Input: the receiver — an `AppError.code` or archive field code.
 * Output: a resource id.
 * Changelog: 2026-08-16 — Created for issue 5.4.
 */
private fun String.toArchiveMessage(): Int =
    when (this) {
        "archive.unreadable" -> R.string.dashboard_archive_unreadable
        "archive.schemaVersion" -> R.string.dashboard_archive_wrong_version
        "archive.writeFailed" -> R.string.dashboard_archive_write_failed
        else -> R.string.dashboard_archive_failed
    }

/**
 * Writes the archive to the destination the user picked.
 * Why:    here rather than in the ViewModel, because it needs a `ContentResolver` — see this file's
 *         doc comment. `truncate` because SAF hands back an existing file when the user overwrites
 *         one, and without it a shorter archive would leave the tail of the longer previous one
 *         behind, producing a file that is valid JSON followed by garbage.
 * Result: `true` when written. Input: the receiver; [uri]; [json]. Output: [Boolean].
 * Changelog: 2026-08-16 — Created for issue 5.4.
 */
private fun Context.writeText(
    uri: Uri,
    json: String,
): Boolean =
    runCatching {
        contentResolver.openOutputStream(uri, "wt")?.use { it.write(json.toByteArray()) } ?: return false
        true
    }.getOrDefault(false)

/**
 * Reads a picked archive into memory.
 * Why:    read here rather than passed onward as a URI, for the reason `ReceiptCapture.readBytes`
 *         records: a URI's grant belongs to the activity that received it and does not survive
 *         process death, so a ViewModel holding one works until the system kills the app.
 * Result: the text, or `null` when the file could not be opened. Input: the receiver; [uri].
 * Output: `String?`.
 * Changelog: 2026-08-16 — Created for issue 5.4.
 */
private fun Context.readText(uri: Uri): String? =
    runCatching { contentResolver.openInputStream(uri)?.use { it.readBytes().decodeToString() } }.getOrNull()

/** The archive is JSON, and the picker filters on it so a photo cannot be offered as a backup. */
private const val ARCHIVE_MIME = "application/json"

/** What the picker suggests. The user can rename it; this is only a starting point. */
private const val ARCHIVE_FILE_NAME = "ai-personal-cfo-backup.json"
