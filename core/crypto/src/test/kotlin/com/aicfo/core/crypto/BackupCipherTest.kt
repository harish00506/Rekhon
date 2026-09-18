package com.aicfo.core.crypto

import com.aicfo.core.common.AppError
import com.aicfo.core.common.Err
import com.aicfo.core.common.Ok
import com.aicfo.core.common.Result
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import java.security.SecureRandom

/**
 * The E2EE backup cipher (issue 8.1; SEC-003, SEC-005, P-01).
 *
 * Why:  a backup that cannot be opened is worse than none — the user believes they are covered —
 *       and one that opens under the wrong passphrase, or after tampering, is worse still. Both
 *       failures are silent until the day the phone is lost, so they are pinned here.
 * What: known-answer vectors for the KDF from an **independent** implementation (OpenSSL 3.5's
 *       `ARGON2ID`, not BouncyCastle checking itself); round trips at the test and the shipping
 *       parameters; nonce and salt uniqueness; every tamper the GCM tag and the header's associated
 *       data must reject; the format refusals; the passphrase floor; and the on-disk header layout,
 *       which is a contract with files already on users' storage.
 * Result: the acceptance criteria "round-trip decrypt test; a tampered tag is rejected" and "a
 *       unique IV/nonce is used per backup", asserted on the JVM.
 * Changelog: 2026-09-18 — Created for issue 8.1.
 */
class BackupCipherTest {
    /** Small parameters so the suite stays fast; the shipping ones get their own round trip. */
    private val fastParams = BackupKdfParams(memoryKib = 1024, iterations = 2, parallelism = 1)
    private val cipher = Argon2idAesGcmBackupCipher(fastParams, SecureRandom())
    private val passphrase = "correct horse battery staple".toCharArray()
    private val archive =
        """{"archiveVersion":1,"schemaVersion":22,"accounts":[{"balanceMinor":123456}]}""".encodeToByteArray()

    // --- the KDF against an independent implementation -------------------------------------------

    @Test
    fun `the KDF matches OpenSSL's Argon2id at the test parameters`() {
        // openssl kdf -keylen 32 -kdfopt hexpass:<utf-8 of the passphrase>
        //   -kdfopt hexsalt:000102030405060708090a0b0c0d0e0f -kdfopt iter:2 -kdfopt memcost:1024
        //   -kdfopt lanes:1 -kdfopt threads:1 ARGON2ID
        val key = BackupKdf.derive(passphrase, SEQUENTIAL_SALT, fastParams)

        assertEquals("58782fc96f06a6d7fcec4728099f6a788ee98a04f4cd8fa0a27af6db8b7a46b8", key.toHex())
    }

    @Test
    fun `the KDF matches OpenSSL's Argon2id at the shipping parameters`() {
        // Same command with iter:3 memcost:65536 lanes:4 — RFC 9106 §4's second recommended option.
        val key = BackupKdf.derive(passphrase, SEQUENTIAL_SALT, BackupKdfParams.DEFAULT)

        assertEquals("853b272a44db1421c02962669a55eb0994f3cab385ed1c4c79253eee19bab49e", key.toHex())
    }

    @Test
    fun `the shipping parameters are RFC 9106's second recommended option`() {
        assertEquals(BackupKdfParams(memoryKib = 65_536, iterations = 3, parallelism = 4), BackupKdfParams.DEFAULT)
    }

    // --- round trips ------------------------------------------------------------------------------

    @Test
    fun `a sealed archive opens to exactly the bytes that went in`() {
        val sealed = cipher.seal(archive, passphrase).value()

        assertArrayEquals(archive, cipher.open(sealed, passphrase).value())
    }

    @Test
    fun `a backup sealed with the shipping parameters opens`() {
        val shipping = BackupCipherFactory.create()
        val sealed = shipping.seal(archive, passphrase).value()

        assertArrayEquals(archive, shipping.open(sealed, passphrase).value())
    }

    @Test
    fun `a cipher with different parameters still opens a backup, because they travel in the header`() {
        val sealed = cipher.seal(archive, passphrase).value()
        val other = Argon2idAesGcmBackupCipher(BackupKdfParams.DEFAULT, SecureRandom())

        assertArrayEquals(archive, other.open(sealed, passphrase).value())
    }

    @Test
    fun `an empty archive round-trips`() {
        val sealed = cipher.seal(ByteArray(0), passphrase).value()

        assertArrayEquals(ByteArray(0), cipher.open(sealed, passphrase).value())
    }

    // --- uniqueness -------------------------------------------------------------------------------

    @Test
    fun `sealing the same archive twice gives different salts, nonces and ciphertext`() {
        val first = cipher.seal(archive, passphrase).value()
        val second = cipher.seal(archive, passphrase).value()

        assertFalse("a fresh salt per backup", first.salt().contentEquals(second.salt()))
        assertFalse("a fresh nonce per backup", first.nonce().contentEquals(second.nonce()))
        assertFalse(first.contentEquals(second))
    }

    @Test
    fun `the salt comes from the injected source`() {
        val fixed = FixedRandom(0x5A)
        val sealed = Argon2idAesGcmBackupCipher(fastParams, fixed).seal(archive, passphrase).value()

        assertArrayEquals(ByteArray(BackupFormat.SALT_BYTES) { 0x5A }, sealed.salt())
    }

    // --- refusals: wrong passphrase and tampering --------------------------------------------------

    @Test
    fun `the wrong passphrase is refused`() {
        val sealed = cipher.seal(archive, passphrase).value()

        assertEquals(
            AppError.Crypto("backup.open"),
            cipher.open(sealed, "correct horse battery stapler".toCharArray()).error(),
        )
    }

    @Test
    fun `an empty passphrase is refused on open, as a crypto failure`() {
        val sealed = cipher.seal(archive, passphrase).value()

        assertEquals(AppError.Crypto("backup.open"), cipher.open(sealed, CharArray(0)).error())
    }

    @Test
    fun `a flipped bit in the GCM tag is rejected`() {
        val sealed = cipher.seal(archive, passphrase).value()
        sealed[sealed.lastIndex] = (sealed.last().toInt() xor 0x01).toByte()

        assertEquals(AppError.Crypto("backup.open"), cipher.open(sealed, passphrase).error())
    }

    @Test
    fun `a flipped bit in the ciphertext body is rejected`() {
        val sealed = cipher.seal(archive, passphrase).value()
        val body = BackupFormat.HEADER_BYTES + NONCE_BYTES + 3
        sealed[body] = (sealed[body].toInt() xor 0x80).toByte()

        assertEquals(AppError.Crypto("backup.open"), cipher.open(sealed, passphrase).error())
    }

    @Test
    fun `a flipped bit in the nonce is rejected`() {
        val sealed = cipher.seal(archive, passphrase).value()
        sealed[BackupFormat.HEADER_BYTES] = (sealed[BackupFormat.HEADER_BYTES].toInt() xor 0x01).toByte()

        assertEquals(AppError.Crypto("backup.open"), cipher.open(sealed, passphrase).error())
    }

    @Test
    fun `a changed salt is rejected`() {
        val sealed = cipher.seal(archive, passphrase).value()
        sealed[BackupFormat.SALT_OFFSET] = (sealed[BackupFormat.SALT_OFFSET].toInt() xor 0x01).toByte()

        assertEquals(AppError.Crypto("backup.open"), cipher.open(sealed, passphrase).error())
    }

    @Test
    fun `a changed KDF parameter in the header is rejected rather than silently used`() {
        val sealed = cipher.seal(archive, passphrase).value()
        // iterations 2 -> 3: still inside the accepted bounds, so only the key and the associated
        // data can catch it.
        sealed[BackupFormat.ITERATIONS_OFFSET + 3] = 3

        assertEquals(AppError.Crypto("backup.open"), cipher.open(sealed, passphrase).error())
    }

    @Test
    fun `a truncated backup is rejected`() {
        val sealed = cipher.seal(archive, passphrase).value()

        assertEquals(AppError.Crypto("backup.open"), cipher.open(sealed.copyOf(sealed.size - 1), passphrase).error())
    }

    // --- refusals: not a backup at all -------------------------------------------------------------

    @Test
    fun `a file that is not a backup is refused as a format error`() {
        assertEquals(
            AppError.Validation("backup.format"),
            cipher.open("""{"archiveVersion":1}""".encodeToByteArray(), passphrase).error(),
        )
    }

    @Test
    fun `an empty file is refused as a format error`() {
        assertEquals(AppError.Validation("backup.format"), cipher.open(ByteArray(0), passphrase).error())
    }

    @Test
    fun `a header with no room for a nonce and tag is refused as a format error`() {
        val sealed = cipher.seal(archive, passphrase).value()
        val headerOnly = sealed.copyOf(BackupFormat.HEADER_BYTES + NONCE_BYTES + TAG_BYTES - 1)

        assertEquals(AppError.Validation("backup.format"), cipher.open(headerOnly, passphrase).error())
    }

    @Test
    fun `a newer format version is refused with its own code`() {
        val sealed = cipher.seal(archive, passphrase).value()
        sealed[BackupFormat.VERSION_OFFSET] = 2

        assertEquals(AppError.Validation("backup.version"), cipher.open(sealed, passphrase).error())
    }

    @Test
    fun `KDF parameters past the accepted bounds are refused before any memory is spent`() {
        val sealed = cipher.seal(archive, passphrase).value()
        // 4 GiB of memory: a crafted file must not be able to make a restore allocate that.
        writeInt(sealed, BackupFormat.MEMORY_OFFSET, 4 * 1024 * 1024)

        assertEquals(AppError.Validation("backup.format"), cipher.open(sealed, passphrase).error())
    }

    @Test
    fun `zero iterations or zero parallelism are refused as format errors`() {
        val noIterations =
            cipher.seal(
                archive,
                passphrase,
            ).value().also { writeInt(it, BackupFormat.ITERATIONS_OFFSET, 0) }
        val noLanes = cipher.seal(archive, passphrase).value().also { it[BackupFormat.PARALLELISM_OFFSET] = 0 }

        assertEquals(AppError.Validation("backup.format"), cipher.open(noIterations, passphrase).error())
        assertEquals(AppError.Validation("backup.format"), cipher.open(noLanes, passphrase).error())
    }

    @Test
    fun `a salt length other than sixteen is refused as a format error`() {
        val sealed = cipher.seal(archive, passphrase).value()
        sealed[BackupFormat.SALT_LENGTH_OFFSET] = 8

        assertEquals(AppError.Validation("backup.format"), cipher.open(sealed, passphrase).error())
    }

    // --- the passphrase ---------------------------------------------------------------------------

    @Test
    fun `a passphrase shorter than twelve characters is refused and nothing is sealed`() {
        assertEquals(
            AppError.Validation("backup.passphrase"),
            cipher.seal(archive, "elevenchars".toCharArray()).error(),
        )
    }

    @Test
    fun `a passphrase of exactly twelve characters is accepted`() {
        val twelve = "twelve chars".toCharArray()
        val sealed = cipher.seal(archive, twelve).value()

        assertArrayEquals(archive, cipher.open(sealed, twelve).value())
    }

    @Test
    fun `sealing leaves the caller's passphrase untouched — clearing it is the caller's decision`() {
        val copy = passphrase.copyOf()
        cipher.seal(archive, passphrase)

        assertArrayEquals(copy, passphrase)
    }

    @Test
    fun `neither the archive nor the passphrase appears in the sealed bytes`() {
        val sealed = cipher.seal(archive, passphrase).value()

        assertFalse(sealed.containsSlice("balanceMinor".encodeToByteArray()))
        assertFalse(sealed.containsSlice(String(passphrase).encodeToByteArray()))
    }

    // --- the on-disk header -----------------------------------------------------------------------

    @Test
    fun `the header layout is pinned — it is a contract with files already on users' storage`() {
        val sealed = Argon2idAesGcmBackupCipher(fastParams, FixedRandom(0x11)).seal(archive, passphrase).value()

        val expected =
            "CFOB".encodeToByteArray() +
                byteArrayOf(1) +
                byteArrayOf(0, 0, 4, 0) + // memory 1024 KiB, big-endian
                byteArrayOf(0, 0, 0, 2) + // iterations
                byteArrayOf(1) + // parallelism
                byteArrayOf(16) + // salt length
                ByteArray(16) { 0x11 }
        assertEquals(31, BackupFormat.HEADER_BYTES)
        assertArrayEquals(expected, sealed.copyOf(BackupFormat.HEADER_BYTES))
        assertEquals(
            "header + nonce + plaintext + tag, and nothing else",
            BackupFormat.HEADER_BYTES + NONCE_BYTES + archive.size + TAG_BYTES,
            sealed.size,
        )
    }

    // --- helpers ----------------------------------------------------------------------------------

    /** A `SecureRandom` that fills every request with [byte] — for pinning the salt, never shipped. */
    private class FixedRandom(private val byte: Int) : SecureRandom() {
        override fun nextBytes(bytes: ByteArray) = bytes.fill(byte.toByte())
    }

    private fun <T> Result<T, AppError>.value(): T =
        when (this) {
            is Ok -> value
            is Err -> throw AssertionError("expected Ok, got $error")
        }

    private fun Result<*, AppError>.error(): AppError =
        when (this) {
            is Ok -> throw AssertionError("expected Err, got Ok")
            is Err -> error
        }

    private fun ByteArray.salt() =
        copyOfRange(
            BackupFormat.SALT_OFFSET,
            BackupFormat.SALT_OFFSET + BackupFormat.SALT_BYTES,
        )

    private fun ByteArray.nonce() = copyOfRange(BackupFormat.HEADER_BYTES, BackupFormat.HEADER_BYTES + NONCE_BYTES)

    private fun ByteArray.toHex() = joinToString("") { "%02x".format(it) }

    private fun ByteArray.containsSlice(slice: ByteArray): Boolean =
        (0..size - slice.size).any { start -> slice.indices.all { this[start + it] == slice[it] } }

    private fun writeInt(
        bytes: ByteArray,
        offset: Int,
        value: Int,
    ) {
        for (i in 0 until Int.SIZE_BYTES) bytes[offset + i] = (value ushr (8 * (3 - i))).toByte()
    }

    private companion object {
        val SEQUENTIAL_SALT = ByteArray(16) { it.toByte() }

        /** Tink's AES-GCM output is a 12-byte nonce, the ciphertext, and a 16-byte tag. */
        const val NONCE_BYTES = 12
        const val TAG_BYTES = 16
    }
}
