package com.aicfo.data.repository

import com.aicfo.core.common.AppError
import com.aicfo.core.common.DispatcherProvider
import com.aicfo.core.common.Result
import com.aicfo.core.common.flatMap
import com.aicfo.core.model.AuditEvent
import kotlinx.coroutines.withContext

/**
 * Destroying every secret this app holds (issue 11.4; §23, §34, SEC-003, P-01).
 *
 * Why:  the data is encrypted at rest, so "erase everything" has a cheaper and far stronger
 *       meaning than overwriting files: **destroy the keys**. A SQLCipher database whose Keystore
 *       key no longer exists is a file of random bytes — not "deleted and probably unrecoverable",
 *       but unrecoverable by anyone, including whoever holds the disk. The file deletion that
 *       follows is tidying, not protection.
 * What: shred, then sweep, then record that it happened.
 * Result: `Ok(Unit)` when the keys are gone; `Err` when they are not — the one failure a user must
 *       be told about honestly.
 * Changelog: 2026-10-01 — Created for issue 11.4.
 *
 * **Irreversible by design.** There is no second copy of the key, in this app or anywhere else
 * (SEC-003 keeps it in the TEE), so there is nothing to undo this with. The screen that calls it
 * says so before asking.
 */
interface EraseRepository {
    /**
     * Destroys the keys, deletes the files, and records the event.
     * Why:    one call, because the order is the safety property and a caller who could do half of
     *         it would eventually do the wrong half.
     * Result: `Ok(Unit)` once the keys are destroyed — even if some file refused to delete, because
     *         by then the leftovers are ciphertext with no key in the world. `Err` if the shred
     *         itself failed, in which case **nothing** is deleted: destroying a user's data while
     *         leaving it theoretically recoverable is the worst of both.
     * Input:  none. Output: `Result<Unit, AppError>`.
     */
    suspend fun eraseEverything(): Result<Unit, AppError>
}

/**
 * The platform operations an erase needs (issue 11.4).
 *
 * Why:    a seam, for the same reason `SqlCipherPassphraseManager` has one: the Keystore exists
 *         only on a device, and the decisions worth testing are about **order and failure**, not
 *         about how a key is deleted. Behind this interface is a binding with no branches; in
 *         front of it is a sequence that can destroy someone's finances if it is wrong.
 * What:   two steps, named in the order they must happen.
 * Changelog: 2026-10-01 — Created for issue 11.4.
 */
interface SecureEraser {
    /**
     * Destroys every Keystore key this app owns, and the Tink keysets they protect.
     * Result: `Ok(Unit)` only if every key is gone. Input: none. Output: `Result<Unit, AppError>`.
     */
    suspend fun destroyKeys(): Result<Unit, AppError>

    /**
     * Deletes the encrypted files the keys used to open.
     * Result: `Ok(Unit)`, or `Err(Storage)` naming the failure — which the caller tolerates.
     * Input:  none. Output: `Result<Unit, AppError>`.
     */
    suspend fun deleteDataFiles(): Result<Unit, AppError>
}

/**
 * [EraseRepository] over the platform's own key store (issue 11.4).
 *
 * Input:  [eraser] — the platform operations; [auditLog] — where the event is recorded, and which
 *         stamps its own time from the injected clock (TIM-001); [dispatchers] — I/O off the
 *         caller's thread (ARC-006).
 * Output: the repository.
 * Changelog: 2026-10-01 — Created for issue 11.4.
 */
internal class DefaultEraseRepository(
    private val eraser: SecureEraser,
    private val auditLog: AuditLogRepository,
    private val dispatchers: DispatcherProvider,
) : EraseRepository {
    override suspend fun eraseEverything(): Result<Unit, AppError> =
        withContext(dispatchers.io) {
            eraser
                .destroyKeys()
                .flatMap {
                    // Deliberately not `flatMap`: a file that will not delete must not fail the
                    // erase. The key is already gone, so what is left on disk cannot be read by
                    // anyone — and reporting an error here would frighten a user about data that
                    // is already beyond recovery.
                    eraser.deleteDataFiles()
                    // Recorded last, and into whatever store exists after the sweep: an entry
                    // written first would be destroyed by the very erase it describes, and if the
                    // shred then failed the app would be holding a log claiming it had erased data
                    // it still had.
                    auditLog.record(AuditEvent.DATA_ERASED)
                }
        }
}
