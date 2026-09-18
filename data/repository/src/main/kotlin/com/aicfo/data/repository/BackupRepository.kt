package com.aicfo.data.repository

import com.aicfo.core.common.AppError
import com.aicfo.core.common.DispatcherProvider
import com.aicfo.core.common.Err
import com.aicfo.core.common.Ok
import com.aicfo.core.common.Result
import com.aicfo.core.crypto.BackupCipher
import com.aicfo.core.datastore.ConsentFeature
import com.aicfo.core.datastore.ConsentStore
import com.aicfo.core.model.AuditEvent
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

/**
 * Makes the end-to-end-encrypted backup (issue 8.1; SEC-005, §23.3, P-01).
 *
 * Why:  §5.10's archive (issue 5.4) is plaintext on purpose — a file the user can read. A backup is
 *       the opposite artefact: it exists to leave the phone, so it must be unreadable to wherever it
 *       lands. This composes the two halves that already exist — the archive and the cipher — behind
 *       the one gate P-01 requires for anything that leaves the device: the user's consent.
 * What: backup: consent → archive → seal → audit. Restore: open → import → audit.
 * Result: sealed bytes the screen writes wherever the user picks, or a reason it refused.
 * Changelog: 2026-09-18 — Created for issue 8.1.
 *   2026-09-18 — Issue 8.2 added [restore].
 *
 * **No network path, and no file I/O either.** Like `ArchiveRepository`, this hands back bytes and
 * the screen writes them through the system file picker (SEC-005: "local backup to user-chosen
 * storage (SAF)"). §22's server-blind blob store is a later issue and will be a second consumer of
 * these same bytes, behind the same consent.
 */
interface BackupRepository {
    /**
     * Seals the active profile's archive under a passphrase.
     *
     * Why:    **the consent is checked before the archive is read**, so a refused backup never has
     *         the user's data in memory at all. A backup is off-device storage by definition — the
     *         system picker can hand back a cloud drive as easily as an SD card, and this app cannot
     *         tell them apart — so the whole feature sits behind `ConsentFeature.CLOUD_BACKUP`
     *         (ADR-0039).
     *
     *         **The passphrase is consumed**: cleared on every path, success or refusal, before this
     *         returns. It is never written anywhere (SEC-005), and the caller has no further use
     *         for it — holding it any longer would only widen the window it sits in memory.
     * Result: `Ok(sealed)`; `Err(Validation("backup.consent"))` when the consent is not granted or
     *         the ledger cannot be read (never a default that reads as granted); the archive's own
     *         error when the export fails; the cipher's own error — `Validation("backup.passphrase")`
     *         for a passphrase that is too short, `Crypto` otherwise.
     * Input:  [passphrase] — the user's; zero-filled on return. Output: `Result<ByteArray, AppError>`.
     */
    suspend fun create(passphrase: CharArray): Result<ByteArray, AppError>

    /**
     * Replaces the active profile's data with an encrypted backup's (issue 8.2; SEC-005, F6).
     *
     * Why:    the other half of a backup — a fresh device, or a wiped one, rebuilt from the file.
     *         **Every check runs before anything is deleted**, in the order that costs least to
     *         refuse: the file's format and KDF bounds, then the GCM tag (which is both the integrity
     *         check and the passphrase check — one answer for either), then the archive's parse,
     *         schema version and profile. Only then does `ArchiveRepository.import` wipe and insert,
     *         inside one transaction, so a failure part-way leaves the database as it was.
     *
     *         **No consent is asked.** P-01 gates data leaving the device; a restore brings it in,
     *         from a file the user picked themselves.
     * Result: `Ok(summary)` — rows restored and when the backup was taken;
     *         `Err(Crypto("backup.open"))` for a wrong passphrase or a tampered file;
     *         `Err(Validation(...))` for `backup.format`, `backup.version`, `archive.unreadable`,
     *         `archive.schemaVersion` or `archive.profile`; `Err(Storage)` if the write fails,
     *         rolled back. **In every `Err` case nothing has been written.**
     * Input:  [sealed] — the file's bytes; [passphrase] — zero-filled on return.
     * Output: `Result<ImportSummary, AppError>`.
     */
    suspend fun restore(
        sealed: ByteArray,
        passphrase: CharArray,
    ): Result<ImportSummary, AppError>
}

/**
 * The production [BackupRepository].
 * Why:    ARC-003 — one public interface, an internal implementation, assembled by the DI graph.
 * Result: the implementation injected into the settings ViewModel.
 * Changelog: 2026-09-18 — Created for issue 8.1.
 *
 * Input:  [archive] — reads the active profile (so the demo never backs up the real one);
 *         [cipher] — Argon2id → AES-256-GCM; [consents] — the P-01 ledger; [audit] — records the
 *         security event; [dispatchers] — Argon2id is deliberately CPU- and memory-heavy, so it runs
 *         on `default`, never on the main thread.
 * Output: a working repository.
 */
internal class EncryptedBackupRepository(
    private val archive: ArchiveRepository,
    private val cipher: BackupCipher,
    private val consents: ConsentStore,
    private val audit: AuditLogRepository,
    private val dispatchers: DispatcherProvider,
) : BackupRepository {
    override suspend fun create(passphrase: CharArray): Result<ByteArray, AppError> =
        try {
            if (!isConsented()) {
                Err(AppError.Validation(FIELD_CONSENT))
            } else {
                when (val exported = archive.export()) {
                    is Err -> exported
                    is Ok -> seal(exported.value.encodeToByteArray(), passphrase)
                }
            }
        } finally {
            passphrase.fill('\u0000')
        }

    override suspend fun restore(
        sealed: ByteArray,
        passphrase: CharArray,
    ): Result<ImportSummary, AppError> {
        val opened =
            try {
                withContext(dispatchers.default) { cipher.open(sealed, passphrase) }
            } finally {
                passphrase.fill('\u0000')
            }
        val plaintext =
            when (opened) {
                is Err -> return opened
                is Ok -> opened.value
            }
        val restored =
            try {
                archive.import(plaintext.decodeToString())
            } finally {
                plaintext.fill(0)
            }
        if (restored is Ok) audit.record(AuditEvent.BACKUP_RESTORED)
        return restored
    }

    /**
     * Seals the plaintext and records the event.
     * Why:    the plaintext bytes are cleared as soon as they are sealed — they are a complete copy
     *         of the user's finances, and nothing needs them afterwards. The audit write is
     *         best-effort: failing to record a backup must not throw away a backup that exists,
     *         which is the mirror of `AuditLogRepository`'s rule for refusals.
     * Result: the cipher's result. Input: [plaintext]; [passphrase]. Output: `Result<ByteArray, AppError>`.
     */
    private suspend fun seal(
        plaintext: ByteArray,
        passphrase: CharArray,
    ): Result<ByteArray, AppError> {
        val sealed =
            try {
                withContext(dispatchers.default) { cipher.seal(plaintext, passphrase) }
            } finally {
                plaintext.fill(0)
            }
        if (sealed is Ok) audit.record(AuditEvent.BACKUP_CREATED)
        return sealed
    }

    /**
     * Whether the backup consent is granted right now.
     * Why:    read once, at the moment of the backup, rather than cached — a revocation must stop
     *         the very next backup. An unreadable ledger answers no.
     * Result: `true` only for a readable, granted record. Input: none. Output: [Boolean].
     */
    private suspend fun isConsented(): Boolean =
        (consents.observe(ConsentFeature.CLOUD_BACKUP).first() as? Ok)?.value?.granted == true

    private companion object {
        /** The screen maps this to "turn on backups first" copy; a code, never a sentence. */
        const val FIELD_CONSENT = "backup.consent"
    }
}
