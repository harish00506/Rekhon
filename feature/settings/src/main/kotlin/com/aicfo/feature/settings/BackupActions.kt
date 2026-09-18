package com.aicfo.feature.settings

import com.aicfo.core.common.AppError
import com.aicfo.core.common.Err
import com.aicfo.core.common.Ok
import com.aicfo.data.repository.BackupRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * The settings screen's backup and restore handling (issues 8.1, 8.2; SEC-005, P-01, P-07).
 *
 * Why:  backup and restore are one self-contained flow with its own events and its own
 *       repository, and folding both into `SettingsViewModel` put it past the function budget
 *       detekt enforces as a readability limit (§21.6). Split out, each half reads as one unit and
 *       the ViewModel keeps to the settings it owns. It is not a second ViewModel: it holds no
 *       state of its own, and writes only the `backup` and `restore` parts of the screen's state.
 * What: the backup form (8.1) and the restore flow (8.2).
 * Result: the backup's sealed bytes or the restore's row count, reflected in the screen state.
 * Changelog: 2026-09-18 — Created for issue 8.2, taking over issue 8.1's handling from
 *   `SettingsViewModel` unchanged.
 *
 * Input:  [repository] — seals and restores; [state] — the screen's state, owned by the ViewModel;
 *         [scope] — the ViewModel's scope, so work is cancelled with it (ARC-006).
 * Output: a handler the ViewModel routes events to.
 */
internal class BackupActions(
    private val repository: BackupRepository,
    private val state: MutableStateFlow<SettingsUiState>,
    private val scope: CoroutineScope,
) {
    /**
     * Handles the backup form's events (issue 8.1).
     * Result: the form advances, or a backup is sealed. Input: [event]. Output: none.
     */
    fun onBackupEvent(event: SettingsEvent.Backup) {
        when (event) {
            is SettingsEvent.BackupPassphraseChanged -> updateBackup { it.copy(passphraseText = event.value) }
            is SettingsEvent.BackupConfirmationChanged -> updateBackup { it.copy(confirmationText = event.value) }
            is SettingsEvent.BackupAcknowledged -> updateBackup { it.copy(acknowledged = event.checked) }
            SettingsEvent.CreateBackup -> createBackup()
            is SettingsEvent.BackupWritten ->
                updateBackup {
                    it.copy(status = if (event.written) BackupStatus.Written else BackupStatus.Failed(WRITE_FAILED))
                }
            SettingsEvent.BackupDismissed -> updateBackup { it.copy(status = BackupStatus.Idle) }
        }
    }

    /**
     * Handles the restore flow's events (issue 8.2).
     * Why:    picking only stages the file — nothing is replaced until [SettingsEvent.ConfirmRestore],
     *         which the screen labels "replace everything" (P-07).
     * Result: the restore flow advances. Input: [event]. Output: none.
     */
    fun onRestoreEvent(event: SettingsEvent.Restore) {
        when (event) {
            is SettingsEvent.RestoreFilePicked ->
                state.update {
                    it.copy(restore = RestoreUiState(status = RestoreStatus.Picked(event.bytes)))
                }
            SettingsEvent.RestoreFileUnreadable ->
                state.update { it.copy(restore = RestoreUiState(status = RestoreStatus.Failed(UNREADABLE))) }
            is SettingsEvent.RestorePassphraseChanged ->
                state.update { it.copy(restore = it.restore.copy(passphraseText = event.value)) }
            SettingsEvent.ConfirmRestore -> confirmRestore()
            SettingsEvent.CancelRestore -> state.update { it.copy(restore = RestoreUiState()) }
        }
    }

    /**
     * Seals a backup under the typed passphrase (SEC-005, P-01).
     * Why:    **the passphrase leaves the screen state before the seal starts** — copied into a
     *         `CharArray` the repository zero-fills, with both fields cleared in the same update that
     *         shows "sealing". The blocker is re-checked here rather than trusting the disabled
     *         button, so a stale tap can never reach the repository without the consent.
     * Result: [BackupStatus.ReadyToWrite] with the sealed bytes, or [BackupStatus.Failed].
     * Input:  none (reads [state]). Output: none.
     * Changelog: 2026-09-18 — Created for issue 8.1 in `SettingsViewModel`; moved here for 8.2.
     */
    private fun createBackup() {
        val current = state.value
        if (!current.canCreateBackup) return
        val passphrase = current.backup.passphraseText.toCharArray()
        state.update { it.copy(backup = BackupUiState(status = BackupStatus.Sealing)) }
        scope.launch {
            val status =
                when (val outcome = repository.create(passphrase)) {
                    is Ok -> BackupStatus.ReadyToWrite(outcome.value)
                    is Err -> BackupStatus.Failed(outcome.error.screenCode())
                }
            updateBackup { it.copy(status = status) }
        }
    }

    /**
     * Restores the picked backup (issue 8.2; SEC-005).
     * Why:    the passphrase is cleared from state as the restore starts, like the backup's. **A
     *         failure keeps the file picked**, with the reason: a mistyped passphrase is the likely
     *         failure, and making the user find the file again to retry would be a needless second
     *         trip through the picker.
     * Result: [RestoreStatus.Restored] with the row count, or [RestoreStatus.Picked] carrying the
     *         failure code. Input: none (reads [state]). Output: none.
     */
    private fun confirmRestore() {
        val restore = state.value.restore
        val picked = restore.status as? RestoreStatus.Picked ?: return
        if (!restore.canConfirm) return
        val passphrase = restore.passphraseText.toCharArray()
        state.update { it.copy(restore = RestoreUiState(status = RestoreStatus.Restoring)) }
        scope.launch {
            val status =
                when (val outcome = repository.restore(picked.bytes, passphrase)) {
                    is Ok -> RestoreStatus.Restored(rows = outcome.value.rowsImported)
                    is Err -> RestoreStatus.Picked(picked.bytes, failure = outcome.error.screenCode())
                }
            state.update { it.copy(restore = it.restore.copy(status = status)) }
        }
    }

    /** Replaces the backup form. Input: [transform]. Output: none. */
    private fun updateBackup(transform: (BackupUiState) -> BackupUiState) {
        state.update { it.copy(backup = transform(it.backup)) }
    }

    private companion object {
        /** The picker returned but the bytes did not reach the file. */
        const val WRITE_FAILED = "backup.writeFailed"

        /** The picked restore file could not be read. */
        const val UNREADABLE = "restore.unreadable"
    }
}

/**
 * The code the screen maps to copy (issues 8.1, 8.2).
 * Why:    a validation error's field names the specific refusal (`backup.format`, `archive.profile`
 *         …), which is what the user needs; every other error's own code is enough.
 * Result: a stable code. Input: the receiver. Output: [String].
 * Changelog: 2026-09-18 — Created for issue 8.2.
 */
private fun AppError.screenCode(): String = (this as? AppError.Validation)?.field ?: code
