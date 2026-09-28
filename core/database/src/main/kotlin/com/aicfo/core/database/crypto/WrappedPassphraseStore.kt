package com.aicfo.core.database.crypto

import com.aicfo.core.common.AppError
import com.aicfo.core.common.Ok
import com.aicfo.core.common.Result
import com.aicfo.core.common.runCatchingToResult
import java.io.File
import java.io.IOException

/**
 * Where the **wrapped** database passphrase is kept.
 *
 * Why:  the ciphertext has to survive process death and app restarts, but the mechanism is a
 *       detail — a file today, possibly DataStore later. Keeping it behind an interface is what
 *       lets [SqlCipherPassphraseManager]'s logic, which can destroy a user's database if it is
 *       wrong, be tested without touching a filesystem.
 * What: read the stored ciphertext (or `null` on first run) and replace it.
 * Result: persistence with no crypto knowledge; it never sees an unwrapped passphrase.
 * Changelog: 2026-07-25 — Created for issue 1.6.
 *
 * Implementations must never log or copy the bytes elsewhere. They are ciphertext, but a
 * duplicate is still one more place an attacker can look.
 */
interface WrappedPassphraseStore {
    /**
     * Reads the stored ciphertext.
     * Result: `Ok(bytes)`, `Ok(null)` when nothing has been stored yet (first run), or
     *         `Err(Storage)` when the medium itself failed — a distinction that matters, because
     *         "absent" means create one and "failed" must never.
     * Input:  none. Output: `Result<ByteArray?, AppError>`.
     */
    fun read(): Result<ByteArray?, AppError>

    /**
     * Replaces the stored ciphertext.
     * Result: `Ok(Unit)` or `Err(Storage)`.
     * Input:  [wrapped] — the ciphertext. Output: `Result<Unit, AppError>`.
     */
    fun write(wrapped: ByteArray): Result<Unit, AppError>

    /**
     * Reads the **staged** ciphertext — a key that has been minted but not yet promoted.
     * Why:    a rotation is two writes that must agree, and a process can die between them
     *         (issue 11.1). A second slot is what makes that survivable: both keys are on disk
     *         for the length of the window, so the next open can simply try them in turn.
     * Result: `Ok(bytes)`, `Ok(null)` when no rotation is in flight, or `Err(Storage)`.
     * Input:  none. Output: `Result<ByteArray?, AppError>`.
     */
    fun readPending(): Result<ByteArray?, AppError>

    /**
     * Stages a ciphertext without disturbing the current one.
     * Result: `Ok(Unit)` or `Err(Storage)`. Input: [wrapped]. Output: `Result<Unit, AppError>`.
     */
    fun writePending(wrapped: ByteArray): Result<Unit, AppError>

    /**
     * Makes the staged ciphertext the current one, in a single step.
     * Why:    the last act of a rotation. It must not be able to leave *neither* in place, which
     *         is why the file implementation is a rename rather than a delete and a write.
     * Result: `Ok(Unit)` or `Err(Storage)`. Input: none. Output: `Result<Unit, AppError>`.
     */
    fun promotePending(): Result<Unit, AppError>

    /**
     * Discards the staged ciphertext.
     * Why:    called when the re-key failed or never took effect. A staged key left behind is a
     *         candidate that opens nothing, offered on every future open.
     * Result: `Ok(Unit)`, including when there was nothing staged. Input: none. Output: `Result`.
     */
    fun clearPending(): Result<Unit, AppError>
}

/**
 * A [WrappedPassphraseStore] backed by a file in the app's private storage.
 *
 * Why:  the simplest thing that survives restarts. It needs no encryption of its own — the bytes
 *       are already wrapped by a key that never leaves the TEE — and app-private storage keeps it
 *       off other apps' reach on a non-rooted device.
 * What: read/replace, with the write done to a temporary file and then moved into place.
 * Result: persistence that cannot leave a half-written key file behind after a crash — a
 *       truncated ciphertext would be indistinguishable from tampering and would lock the user
 *       out of their database.
 * Changelog: 2026-07-25 — Created for issue 1.6.
 *
 * Input:  [file] — the destination, normally `context.filesDir/db-passphrase.bin`.
 * Output: a store ready for [SqlCipherPassphraseManager].
 */
class FileWrappedPassphraseStore(
    private val file: File,
) : WrappedPassphraseStore {
    /** Input: none. Output: the ciphertext, or `Ok(null)` before the first write. */
    override fun read(): Result<ByteArray?, AppError> =
        if (!file.exists()) {
            Ok(null)
        } else {
            runCatchingToResult { file.readBytes() }
        }

    /**
     * Input:  [wrapped] — the ciphertext to persist.
     * Output: `Ok(Unit)`, or `Err(Storage)` if the write or the move failed.
     */
    override fun write(wrapped: ByteArray): Result<Unit, AppError> = replace(file, wrapped)

    /** Input: none. Output: the staged ciphertext, or `Ok(null)` when no rotation is in flight. */
    override fun readPending(): Result<ByteArray?, AppError> =
        if (!pendingFile.exists()) Ok(null) else runCatchingToResult { pendingFile.readBytes() }

    /** Input: [wrapped] — the staged ciphertext. Output: `Ok(Unit)` or `Err(Storage)`. */
    override fun writePending(wrapped: ByteArray): Result<Unit, AppError> = replace(pendingFile, wrapped)

    /**
     * Input:  none.
     * Output: `Ok(Unit)`; `Err(Storage)` if the move failed or there was nothing staged.
     * Why:    a rename, so the promotion cannot be interrupted between deleting one file and
     *         writing the other — the state where *neither* key is on disk and the database is
     *         gone for good.
     */
    override fun promotePending(): Result<Unit, AppError> =
        runCatchingToResult {
            if (!pendingFile.exists()) throw IOException("nothing staged to promote")
            if (!pendingFile.renameTo(file)) {
                file.delete()
                if (!pendingFile.renameTo(file)) {
                    throw IOException("could not promote the staged passphrase")
                }
            }
        }

    /** Input: none. Output: `Ok(Unit)`, whether or not anything was staged. */
    override fun clearPending(): Result<Unit, AppError> = runCatchingToResult { pendingFile.delete() }

    /**
     * Writes bytes to [destination] through a staging file.
     * Why:    a half-written key file is indistinguishable from a tampered one, and both lock the
     *         user out. Writing elsewhere and moving into place means the destination is either
     *         the old contents or the new, never a truncation.
     * Result: `Ok(Unit)` or `Err(Storage)`. Input: [destination]; [bytes]. Output: `Result`.
     * Changelog: 2026-09-28 — Issue 11.1: lifted out of `write` so the staged slot shares it.
     */
    private fun replace(
        destination: File,
        bytes: ByteArray,
    ): Result<Unit, AppError> =
        runCatchingToResult {
            val staging = File(destination.parentFile, destination.name + ".tmp")
            staging.writeBytes(bytes)
            if (!staging.renameTo(destination)) {
                // Windows and some filesystems refuse a rename onto an existing file.
                destination.delete()
                if (!staging.renameTo(destination)) {
                    // IOException, not check(): a failed move is an I/O condition, so it must
                    // become Err(Storage). runCatchingToResult rethrows IllegalStateException by
                    // design, which would crash the app over a full disk.
                    throw IOException("could not move the wrapped passphrase into place")
                }
            }
        }

    /** The staged slot, beside the current one so a promotion is a rename on the same volume. */
    private val pendingFile: File get() = File(file.parentFile, file.name + ".pending")
}
