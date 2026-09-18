package com.aicfo.core.crypto

import com.aicfo.core.common.AppError
import com.aicfo.core.common.Err
import com.aicfo.core.common.Ok
import com.aicfo.core.common.Result
import com.google.crypto.tink.subtle.AesGcmJce
import org.bouncycastle.crypto.PasswordConverter
import org.bouncycastle.crypto.generators.Argon2BytesGenerator
import org.bouncycastle.crypto.params.Argon2Parameters
import java.nio.ByteBuffer
import java.security.GeneralSecurityException
import java.security.SecureRandom

/**
 * Seals and opens the end-to-end-encrypted backup (issue 8.1; SEC-003, SEC-005, §23.3, P-01).
 *
 * Why:  SEC-005 — "Backups: AES-256-GCM with a key derived from a user passphrase via Argon2id".
 *       A backup exists to leave the phone, and wherever it lands (a user's SD card, a cloud drive
 *       picked through the system file picker, later §22's server-blind blob store) the platform
 *       holding it must never see plaintext or the key. So the key is made from something only the
 *       user knows, on the device, and is never written anywhere.
 * What: `seal` turns an archive into a self-describing encrypted file; `open` reverses it, or
 *       refuses — never a partial or best-effort answer.
 * Result: bytes safe to hand to any storage the user chooses.
 * Changelog: 2026-09-18 — Created for issue 8.1.
 *
 * One public interface, one internal implementation (ARC-003). Nothing here throws across the
 * boundary (§21.6), and nothing here logs (P-01 / `CfoPiiInLogs`).
 */
interface BackupCipher {
    /**
     * Encrypts an archive under a passphrase.
     * Why:    the backup half of SEC-005. A fresh salt, and so a fresh key, per call; Tink draws a
     *         fresh nonce per call as well — the two together mean no (key, nonce) pair can ever
     *         repeat, which is the one mistake AES-GCM does not survive.
     * Result: `Ok(sealed)` — header, nonce, ciphertext, tag; `Err(Validation("backup.passphrase"))`
     *         for a passphrase under [MIN_PASSPHRASE_LENGTH] characters, with nothing derived;
     *         `Err(Crypto("backup.seal"))` if the platform refuses the cipher.
     * Input:  [plaintext] — the archive bytes; [passphrase] — the user's, **not modified** (clearing
     *         it is the caller's decision, because only the caller knows when it is done with it).
     * Output: `Result<ByteArray, AppError>`.
     */
    fun seal(
        plaintext: ByteArray,
        passphrase: CharArray,
    ): Result<ByteArray, AppError>

    /**
     * Decrypts a sealed backup.
     * Why:    the restore half (issue 8.2 applies it; this proves it). **The GCM tag is checked
     *         before a single byte is returned**, so a wrong passphrase and a tampered file look
     *         identical and both yield nothing — there is no partial plaintext to half-restore.
     * Result: `Ok(plaintext)`; `Err(Crypto("backup.open"))` for a wrong passphrase or any tampering
     *         (deliberately one code — telling them apart would tell an attacker which one worked);
     *         `Err(Validation("backup.format"))` for a file that is not a backup, or whose header
     *         asks for KDF parameters outside the accepted bounds; `Err(Validation("backup.version"))`
     *         for a format newer than this build.
     * Input:  [sealed] — what [seal] wrote; [passphrase] — not modified. **No length floor here**:
     *         a policy change must never lock a user out of a backup they already made.
     * Output: `Result<ByteArray, AppError>`.
     */
    fun open(
        sealed: ByteArray,
        passphrase: CharArray,
    ): Result<ByteArray, AppError>

    companion object {
        /**
         * The shortest passphrase [seal] accepts.
         *
         * Why: the backup is protected by nothing else. Argon2id makes each guess expensive; it
         *      cannot make a short passphrase long. Twelve is above NIST SP 800-63B's 8-character floor
         *      because this secret faces an *offline* attacker holding the file, with no lockout —
         *      a phrase, not a password. The screen checks it too, so the user hears it before
         *      tapping rather than after.
         */
        const val MIN_PASSPHRASE_LENGTH = 12
    }
}

/**
 * Argon2id's cost settings, carried in every backup's header (issue 8.1; SEC-005).
 *
 * Why:  in the header rather than fixed in code, so a later build can raise the cost for new
 *       backups and still open every old one — the parameters that made a key are the ones that
 *       must remake it.
 * What: memory in KiB, passes, lanes.
 * Result: the input the KDF needs besides the passphrase and salt.
 * Changelog: 2026-09-18 — Created for issue 8.1.
 *
 * Input:  [memoryKib] — memory cost in KiB; [iterations] — passes over it; [parallelism] — lanes.
 * Output: an immutable value.
 */
data class BackupKdfParams(
    val memoryKib: Int,
    val iterations: Int,
    val parallelism: Int,
) {
    companion object {
        /**
         * What new backups are sealed with: RFC 9106 §4's second recommended option (64 MiB, t = 3,
         * p = 4). The first option (2 GiB) cannot be allocated on most phones. Not a financial
         * threshold, so a code constant rather than an `ai/` row (CLAUDE.md §6 is about money).
         */
        val DEFAULT = BackupKdfParams(memoryKib = 65_536, iterations = 3, parallelism = 4)
    }
}

/**
 * Builds the [BackupCipher] the DI graph injects (issue 8.1).
 * Why:    the implementation is `internal` (ARC-003), and a cipher needs no Android context — the
 *         key is derived, not stored — so this is the whole factory.
 * Result: a cipher sealing with [BackupKdfParams.DEFAULT] and a platform `SecureRandom`.
 * Changelog: 2026-09-18 — Created for issue 8.1.
 */
object BackupCipherFactory {
    /** Input: none. Output: [BackupCipher]. */
    fun create(): BackupCipher = Argon2idAesGcmBackupCipher(BackupKdfParams.DEFAULT, SecureRandom())
}

/**
 * The backup file's layout (issue 8.1). **A contract with files already on users' storage.**
 *
 * Why:  one place that owns every offset, so the writer, the reader and the test that pins the
 *       layout cannot disagree.
 * What: `"CFOB" | version:1 | memoryKib:4 | iterations:4 | parallelism:1 | saltLength:1 | salt:16`
 *       (31 bytes, big-endian), then Tink's AES-GCM output: `nonce:12 | ciphertext | tag:16`.
 *       **The whole header is the GCM associated data**, so editing any field of it — the version,
 *       a cost parameter, the salt — fails the tag rather than being silently honoured.
 * Result: the offsets and limits [Argon2idAesGcmBackupCipher] reads and writes.
 * Changelog: 2026-09-18 — Created for issue 8.1.
 */
internal object BackupFormat {
    val MAGIC = "CFOB".encodeToByteArray()
    const val VERSION: Byte = 1
    const val SALT_BYTES = 16
    const val KEY_BYTES = 32

    const val VERSION_OFFSET = 4
    const val MEMORY_OFFSET = 5
    const val ITERATIONS_OFFSET = 9
    const val PARALLELISM_OFFSET = 13
    const val SALT_LENGTH_OFFSET = 14
    const val SALT_OFFSET = 15
    const val HEADER_BYTES = SALT_OFFSET + SALT_BYTES

    /** Tink's AES-GCM adds a 12-byte nonce in front and a 16-byte tag behind. */
    const val AEAD_OVERHEAD = 12 + 16

    /**
     * The most a header may ask for. Why: `open` reads the cost from the file, so without a ceiling
     * a crafted file could make a restore try to allocate gigabytes. 256 MiB is four times the
     * shipping cost — room for a future build to raise it — and still fits a phone's heap.
     */
    const val MAX_MEMORY_KIB = 262_144
    const val MAX_ITERATIONS = 16
    const val MAX_PARALLELISM = 16
}

/**
 * The Argon2id key derivation, and the only BouncyCastle call in the app (issue 8.1; ADR-0039).
 *
 * Why:  **Tink has no password-based KDF**, and SEC-005 names Argon2id. Writing Argon2 by hand is
 *       exactly what SEC-003 forbids, so the primitive comes from BouncyCastle — a vetted,
 *       pure-Java implementation, checked in `BackupCipherTest` against OpenSSL's independent one.
 *       Everything else, the cipher included, stays in Tink.
 * What: passphrase + salt + cost → 32 key bytes, Argon2 version 0x13, the passphrase encoded UTF-8.
 * Result: an AES-256 key the caller must clear once used.
 * Changelog: 2026-09-18 — Created for issue 8.1.
 */
internal object BackupKdf {
    /**
     * Derives the key.
     * Result: 32 bytes. Input: [passphrase]; [salt]; [params]. Output: [ByteArray].
     */
    fun derive(
        passphrase: CharArray,
        salt: ByteArray,
        params: BackupKdfParams,
    ): ByteArray {
        val builder =
            Argon2Parameters.Builder(Argon2Parameters.ARGON2_id)
                .withVersion(Argon2Parameters.ARGON2_VERSION_13)
                .withMemoryAsKB(params.memoryKib)
                .withIterations(params.iterations)
                .withParallelism(params.parallelism)
                .withSalt(salt)
                .withCharToByteConverter(PasswordConverter.UTF8)
        val key = ByteArray(BackupFormat.KEY_BYTES)
        Argon2BytesGenerator().apply { init(builder.build()) }.generateBytes(passphrase, key)
        return key
    }
}

/**
 * The production [BackupCipher]: Argon2id → AES-256-GCM via Tink (issue 8.1; SEC-003, SEC-005).
 *
 * Why:  **`AesGcmJce` over a raw derived key, not a keyset.** A keyset exists to be stored and
 *       rotated; this key must never be stored, and is remade from the passphrase every time. Tink's
 *       own subtle primitive is still Tink: it draws the nonce itself, so this class never chooses
 *       one and cannot reuse one.
 * What: validate → salt → derive → encrypt with the header as associated data; and the reverse.
 * Result: backups only the passphrase can open.
 * Changelog: 2026-09-18 — Created for issue 8.1.
 *
 * `internal` per ARC-003 — built only by [BackupCipherFactory], or by a test with small parameters.
 *
 * Input:  [params] — the cost new backups are sealed with; [random] — the salt source, a
 *         cryptographic one (P-08's seedable-randomness rule is for engines; a predictable salt
 *         would be a weakness, as `TinkPinVerifier` records).
 * Output: a working cipher.
 */
internal class Argon2idAesGcmBackupCipher(
    private val params: BackupKdfParams,
    private val random: SecureRandom,
) : BackupCipher {
    override fun seal(
        plaintext: ByteArray,
        passphrase: CharArray,
    ): Result<ByteArray, AppError> {
        if (passphrase.size < BackupCipher.MIN_PASSPHRASE_LENGTH) return Err(AppError.Validation(FIELD_PASSPHRASE))
        val salt = ByteArray(BackupFormat.SALT_BYTES).also(random::nextBytes)
        val header = header(params, salt)
        return withKey(passphrase, salt, params, OPERATION_SEAL) { aead -> header + aead.encrypt(plaintext, header) }
    }

    override fun open(
        sealed: ByteArray,
        passphrase: CharArray,
    ): Result<ByteArray, AppError> {
        val parsed =
            when (val result = parse(sealed)) {
                is Ok -> result.value
                is Err -> return result
            }
        val header = sealed.copyOf(BackupFormat.HEADER_BYTES)
        val body = sealed.copyOfRange(BackupFormat.HEADER_BYTES, sealed.size)
        // A failed tag — wrong passphrase, or any byte changed — is one answer for both.
        return withKey(passphrase, parsed.salt, parsed.params, OPERATION_OPEN) { aead -> aead.decrypt(body, header) }
    }

    /**
     * Derives a key, builds Tink's AES-GCM over it, runs [block], and clears the key bytes.
     * Why:    one place that guarantees the derived key is zeroed whatever [block] does — the key is
     *         the one secret here that exists only in this process, and it must not outlive the call.
     *         Every `GeneralSecurityException` Tink can raise — a failed tag, a refused key — becomes
     *         `Crypto([operation])`, so nothing crosses the boundary as an exception (§21.6).
     * Result: `Ok(what [block] returned)`, or `Err(Crypto(operation))`.
     * Input:  [passphrase]; [salt]; [params]; [operation] — the failure code; [block].
     * Output: `Result<ByteArray, AppError>`.
     */
    private inline fun withKey(
        passphrase: CharArray,
        salt: ByteArray,
        params: BackupKdfParams,
        operation: String,
        block: (AesGcmJce) -> ByteArray,
    ): Result<ByteArray, AppError> {
        val key = BackupKdf.derive(passphrase, salt, params)
        return try {
            Ok(block(AesGcmJce(key)))
        } catch (_: GeneralSecurityException) {
            Err(AppError.Crypto(operation))
        } finally {
            key.fill(0)
        }
    }

    /**
     * Writes the header.
     * Result: [BackupFormat.HEADER_BYTES] bytes. Input: [params]; [salt]. Output: [ByteArray].
     */
    private fun header(
        params: BackupKdfParams,
        salt: ByteArray,
    ): ByteArray =
        ByteBuffer.allocate(BackupFormat.HEADER_BYTES)
            .put(BackupFormat.MAGIC)
            .put(BackupFormat.VERSION)
            .putInt(params.memoryKib)
            .putInt(params.iterations)
            .put(params.parallelism.toByte())
            .put(BackupFormat.SALT_BYTES.toByte())
            .put(salt)
            .array()

    /**
     * Reads and bounds-checks the header before any key is derived.
     * Why:    **everything that can be refused cheaply is refused before Argon2id runs**, because
     *         the KDF's cost is read from the file itself: an unbounded header is a way to make a
     *         restore allocate whatever the file asks for.
     * Result: `Ok(Header)`, or `Err(Validation("backup.format" | "backup.version"))`.
     * Input:  [sealed]. Output: `Result<Header, AppError>`.
     */
    private fun parse(sealed: ByteArray): Result<Header, AppError> {
        if (sealed.size < BackupFormat.HEADER_BYTES + BackupFormat.AEAD_OVERHEAD) return formatError()
        if (!sealed.copyOf(BackupFormat.MAGIC.size).contentEquals(BackupFormat.MAGIC)) return formatError()
        if (sealed[BackupFormat.VERSION_OFFSET] != BackupFormat.VERSION) return Err(AppError.Validation(FIELD_VERSION))
        val buffer = ByteBuffer.wrap(sealed)
        val params =
            BackupKdfParams(
                memoryKib = buffer.getInt(BackupFormat.MEMORY_OFFSET),
                iterations = buffer.getInt(BackupFormat.ITERATIONS_OFFSET),
                parallelism = sealed[BackupFormat.PARALLELISM_OFFSET].toInt(),
            )
        val saltLength = sealed[BackupFormat.SALT_LENGTH_OFFSET].toInt()
        if (!params.isWithinBounds() || saltLength != BackupFormat.SALT_BYTES) return formatError()
        return Ok(Header(params, sealed.copyOfRange(BackupFormat.SALT_OFFSET, BackupFormat.HEADER_BYTES)))
    }

    /**
     * Whether a header's cost is one this build will spend.
     * Why:    Argon2 itself requires at least 8 KiB per lane; the ceilings are [BackupFormat]'s.
     * Result: `true` when every field is inside its range. Input: the receiver. Output: [Boolean].
     */
    private fun BackupKdfParams.isWithinBounds(): Boolean =
        parallelism in 1..BackupFormat.MAX_PARALLELISM &&
            iterations in 1..BackupFormat.MAX_ITERATIONS &&
            memoryKib in (MIN_KIB_PER_LANE * parallelism)..BackupFormat.MAX_MEMORY_KIB

    private fun formatError(): Result<Header, AppError> = Err(AppError.Validation(FIELD_FORMAT))

    /** What [parse] hands on: the cost and the salt. Input: [params]; [salt]. */
    private class Header(val params: BackupKdfParams, val salt: ByteArray)

    private companion object {
        /**
         * Stable codes, never sentences — the screen maps them to copy, as `ArchiveRepository` does.
         * Four, because the user's next step differs for each: choose a longer passphrase; check
         * the passphrase; pick the right file; update the app.
         */
        const val FIELD_PASSPHRASE = "backup.passphrase"
        const val FIELD_FORMAT = "backup.format"
        const val FIELD_VERSION = "backup.version"
        const val OPERATION_SEAL = "backup.seal"
        const val OPERATION_OPEN = "backup.open"

        /** Argon2's own minimum memory per lane, in KiB (RFC 9106 §3.1: m ≥ 8·p). */
        const val MIN_KIB_PER_LANE = 8
    }
}
