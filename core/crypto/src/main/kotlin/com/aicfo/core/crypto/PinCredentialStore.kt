package com.aicfo.core.crypto

import com.aicfo.core.common.AppError
import com.aicfo.core.common.Ok
import com.aicfo.core.common.Result
import com.aicfo.core.common.runCatchingToResult
import java.io.File
import java.io.IOException

/**
 * Where the PIN **verifier** is kept — never the PIN (issue 2.2, SEC-002).
 *
 * Why:  the stored blob has to survive process death and restarts, but the medium is a detail — a
 *       file today, possibly DataStore later. Keeping it behind an interface is what lets
 *       [TinkPinVerifier]'s decisions, which decide whether a stranger holding the phone gets in,
 *       be tested without touching a filesystem. This is the same split issue 1.6 made for the
 *       database passphrase, for the same reason.
 * What: read the stored credential (or `null` before a PIN is set), replace it, and remove it.
 * Result: persistence with no crypto knowledge; it never sees a PIN.
 * Changelog: 2026-07-26 — Created for issue 2.2.
 *
 * Implementations must never log or copy the bytes. They are a salt and a MAC tag, not a PIN, but
 * a duplicate is still one more place an attacker can look.
 */
internal interface PinCredentialStore {
    /**
     * Reads the stored credential.
     * Result: `Ok(bytes)`, `Ok(null)` when no PIN has been set, or `Err(Storage)` when the medium
     *         itself failed — a distinction that matters, because "no PIN" means offer to set one
     *         and "failed" must never.
     * Input:  none. Output: `Result<ByteArray?, AppError>`.
     */
    fun read(): Result<ByteArray?, AppError>

    /**
     * Replaces the stored credential.
     * Result: `Ok(Unit)` or `Err(Storage)`.
     * Input:  [credential] — salt followed by MAC tag. Output: `Result<Unit, AppError>`.
     */
    fun write(credential: ByteArray): Result<Unit, AppError>

    /**
     * Removes the stored credential, so no PIN is set.
     * Result: `Ok(Unit)` — also when there was nothing to remove — or `Err(Storage)`.
     * Input:  none. Output: `Result<Unit, AppError>`.
     */
    fun clear(): Result<Unit, AppError>
}

/**
 * A [PinCredentialStore] backed by a file in the app's private storage.
 *
 * Why:  the simplest thing that survives restarts. It needs no encryption of its own — the bytes
 *       are a salt and a MAC tag produced by a key that never leaves the TEE, so they reveal
 *       nothing about the PIN — and app-private storage keeps them off other apps' reach on a
 *       non-rooted device.
 * What: read / replace / remove, with the write staged to a temporary file and then moved into
 *       place.
 * Result: persistence that cannot leave a half-written credential behind after a crash. A
 *       truncated blob is indistinguishable from tampering and would lock the user out of their
 *       own app.
 * Changelog: 2026-07-26 — Created for issue 2.2.
 *
 * Input:  [file] — the destination, normally `context.filesDir/cfo-pin.bin`.
 * Output: a store ready for [TinkPinVerifier].
 */
internal class FilePinCredentialStore(
    private val file: File,
) : PinCredentialStore {
    /** Input: none. Output: the credential, or `Ok(null)` before any PIN is set. */
    override fun read(): Result<ByteArray?, AppError> =
        if (!file.exists()) {
            Ok(null)
        } else {
            runCatchingToResult { file.readBytes() }
        }

    /**
     * Input:  [credential] — the bytes to persist.
     * Output: `Ok(Unit)`, or `Err(Storage)` if the write or the move failed.
     */
    override fun write(credential: ByteArray): Result<Unit, AppError> =
        runCatchingToResult {
            val staging = File(file.parentFile, file.name + ".tmp")
            staging.writeBytes(credential)
            if (!staging.renameTo(file)) {
                // Windows and some filesystems refuse a rename onto an existing file.
                file.delete()
                if (!staging.renameTo(file)) {
                    // IOException, not check(): a failed move is an I/O condition, so it must become
                    // Err(Storage). runCatchingToResult rethrows IllegalStateException by design,
                    // which would crash the app over a full disk.
                    throw IOException("could not move the PIN credential into place")
                }
            }
        }

    /** Input: none. Output: `Ok(Unit)` once no credential remains, or `Err(Storage)`. */
    override fun clear(): Result<Unit, AppError> =
        runCatchingToResult {
            if (file.exists() && !file.delete()) {
                throw IOException("could not remove the PIN credential")
            }
        }
}
