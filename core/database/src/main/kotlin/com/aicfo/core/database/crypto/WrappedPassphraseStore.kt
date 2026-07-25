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
    override fun write(wrapped: ByteArray): Result<Unit, AppError> =
        runCatchingToResult {
            val staging = File(file.parentFile, file.name + ".tmp")
            staging.writeBytes(wrapped)
            if (!staging.renameTo(file)) {
                // Windows and some filesystems refuse a rename onto an existing file.
                file.delete()
                if (!staging.renameTo(file)) {
                    // IOException, not check(): a failed move is an I/O condition, so it must
                    // become Err(Storage). runCatchingToResult rethrows IllegalStateException by
                    // design, which would crash the app over a full disk.
                    throw IOException("could not move the wrapped passphrase into place")
                }
            }
        }
}
