package com.aicfo.core.database.crypto

import com.aicfo.core.common.Err
import com.aicfo.core.common.Ok
import com.aicfo.core.common.getOrNull
import com.google.crypto.tink.Aead
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.GeneralSecurityException
import java.security.SecureRandom

/**
 * Tests for [SqlCipherPassphraseManager] — the testable half of the DB key path (SEC-003, §23).
 *
 * Why:  the Keystore itself only exists on a device, but the decisions *around* it are ordinary
 *       logic and are where the expensive mistakes live: generating a fresh passphrase when one
 *       already exists would lock the user out of their own data permanently; writing the
 *       passphrase anywhere unwrapped would defeat the encryption entirely; and a rotation that
 *       loses the old key destroys the database. Splitting those decisions out from the Keystore
 *       binding is what makes them testable at all — the binding left behind is a few lines with
 *       no branches.
 * What: first-run generation, reuse across opens, wrap-before-store, rotation, and the failure
 *       paths (corrupt ciphertext, unreadable store).
 * Result: everything except the platform call is proven before it ever reaches a device.
 * Changelog: 2026-07-25 — Created for issue 1.6.
 *
 * The fake [Aead] below is Tink's own interface, so the manager is exercised through exactly the
 * contract the real Keystore-backed AEAD implements.
 */
class SqlCipherPassphraseManagerTest {
    private val store = InMemoryWrappedPassphraseStore()
    private val aead = ReversibleFakeAead()
    private val manager = SqlCipherPassphraseManager(store, aead, SecureRandom())

    // --- first run and reuse ------------------------------------------------------------------

    /**
     * Input:  an empty store.
     * Output: asserts a passphrase of the documented length is generated and persisted.
     */
    @Test
    fun `generates a passphrase on first run`() {
        val passphrase = manager.getOrCreate()
        assertTrue(passphrase is Ok)
        assertEquals(SqlCipherPassphraseManager.PASSPHRASE_BYTES, passphrase.getOrNull()!!.size)
        assertTrue("the wrapped passphrase must be persisted", store.hasWrappedPassphrase())
    }

    /**
     * Input:  two consecutive opens.
     * Output: asserts the **same** passphrase comes back. Regenerating instead would leave the
     *         existing database permanently unopenable — total, silent data loss.
     */
    @Test
    fun `returns the same passphrase on every later open`() {
        val first = manager.getOrCreate().getOrNull()!!
        val second = manager.getOrCreate().getOrNull()!!
        assertArrayEquals(first, second)
    }

    /** Input: a fresh manager over the same store. Output: asserts the key survives a restart. */
    @Test
    fun `passphrase survives a new manager over the same store`() {
        val first = manager.getOrCreate().getOrNull()!!
        val afterRestart = SqlCipherPassphraseManager(store, aead, SecureRandom()).getOrCreate()
        assertArrayEquals(first, afterRestart.getOrNull())
    }

    /** Input: two independent stores. Output: asserts passphrases are not shared or derived. */
    @Test
    fun `each installation gets its own passphrase`() {
        val other = SqlCipherPassphraseManager(InMemoryWrappedPassphraseStore(), aead, SecureRandom())
        assertNotEquals(
            manager.getOrCreate().getOrNull()!!.toList(),
            other.getOrCreate().getOrNull()!!.toList(),
        )
    }

    // --- the passphrase is never stored in the clear -------------------------------------------

    /**
     * Input:  a generated passphrase and the bytes actually written to disk.
     * Output: asserts the stored bytes are ciphertext, not the passphrase. This is the whole
     *         point of the Keystore: an attacker with the file gets nothing without the TEE.
     */
    @Test
    fun `never stores the passphrase unwrapped`() {
        val passphrase = manager.getOrCreate().getOrNull()!!
        val stored = store.read().getOrNull()!!
        assertFalse("stored bytes must not equal the passphrase", passphrase.contentEquals(stored))
        assertFalse(
            "the passphrase must not appear inside the stored bytes",
            stored.toList().windowed(passphrase.size).any { it.toByteArray().contentEquals(passphrase) },
        )
        assertTrue("stored bytes must have gone through the AEAD", aead.encryptCalls > 0)
    }

    /** Input: a wrap. Output: asserts the AEAD is bound to a purpose, not called with empty context. */
    @Test
    fun `binds the ciphertext to its purpose with associated data`() {
        manager.getOrCreate()
        assertArrayEquals(SqlCipherPassphraseManager.ASSOCIATED_DATA, aead.lastAssociatedData)
    }

    // --- rotation ------------------------------------------------------------------------------

    /**
     * Input:  a rotation after first run.
     * Output: asserts a different passphrase is issued and persisted, and that later opens return
     *         the new one — a rotation that did not stick would leave the DB keyed to a passphrase
     *         nothing can produce again.
     */
    @Test
    fun `rotate issues and persists a new passphrase`() {
        val original = manager.getOrCreate().getOrNull()!!
        val rotated = manager.rotate().getOrNull()!!
        assertNotEquals(original.toList(), rotated.toList())
        assertArrayEquals(rotated, manager.getOrCreate().getOrNull())
    }

    /**
     * Input:  a rotation.
     * Output: asserts the caller is handed both keys, because re-keying SQLCipher needs the old
     *         passphrase to open the file and the new one to write it back.
     */
    @Test
    fun `rotate reports the previous passphrase so the database can be re-keyed`() {
        val original = manager.getOrCreate().getOrNull()!!
        val change = manager.rotateWithPrevious().getOrNull()!!
        assertArrayEquals(original, change.previous)
        assertNotEquals(original.toList(), change.current.toList())
    }

    /** Input: rotation before any passphrase exists. Output: asserts it fails rather than guessing. */
    @Test
    fun `rotate with previous fails when there is nothing to rotate`() {
        val result =
            SqlCipherPassphraseManager(InMemoryWrappedPassphraseStore(), aead, SecureRandom())
                .rotateWithPrevious()
        assertTrue(result is Err)
    }

    // --- failure paths ---------------------------------------------------------------------------

    /**
     * Input:  stored ciphertext that the AEAD rejects (tampered file, or a Keystore key that was
     *         cleared by a factory reset or a changed lock screen).
     * Output: asserts a typed `Err(AppError.Crypto)` — never an exception across the boundary
     *         (§21.6), and never a silently regenerated passphrase, which would look like the
     *         database had been wiped.
     */
    @Test
    fun `unwrapping tampered ciphertext fails as a typed crypto error`() {
        manager.getOrCreate()
        store.corrupt()
        val result = manager.getOrCreate()
        assertTrue(result is Err)
        assertEquals("crypto", (result as Err).error.code)
    }

    /** Input: a store that cannot be read. Output: asserts a storage error, not a crash. */
    @Test
    fun `an unreadable store surfaces as a storage error`() {
        val failing = SqlCipherPassphraseManager(FailingStore(), aead, SecureRandom())
        val result = failing.getOrCreate()
        assertTrue(result is Err)
        assertEquals("storage", (result as Err).error.code)
    }

    /** Input: an AEAD that fails while wrapping. Output: asserts nothing half-written is left. */
    @Test
    fun `a failed wrap leaves the store empty rather than half-written`() {
        val failing = SqlCipherPassphraseManager(store, FailingAead(), SecureRandom())
        val result = failing.getOrCreate()
        assertTrue(result is Err)
        assertFalse("no ciphertext may be persisted after a failed wrap", store.hasWrappedPassphrase())
    }
}

/**
 * A [WrappedPassphraseStore] held in memory for tests.
 * Why:    the real store is a file; the manager's logic does not care, and a fake keeps the tests
 *         fast and free of temp-directory cleanup.
 * Changelog: 2026-07-25 — Created for issue 1.6.
 */
private class InMemoryWrappedPassphraseStore : WrappedPassphraseStore {
    private var bytes: ByteArray? = null

    override fun read() = Ok(bytes)

    override fun write(wrapped: ByteArray) = Ok(Unit).also { bytes = wrapped }

    fun hasWrappedPassphrase() = bytes != null

    /** Flips a byte, standing in for a tampered file or a Keystore key that no longer decrypts. */
    fun corrupt() {
        bytes = bytes!!.copyOf().also { it[0] = (it[0] + 1).toByte() }
    }
}

/** A store whose reads always fail, to exercise the storage-error path. */
private class FailingStore : WrappedPassphraseStore {
    override fun read() = Err(com.aicfo.core.common.AppError.Storage("IOException"))

    override fun write(wrapped: ByteArray) = Err(com.aicfo.core.common.AppError.Storage("IOException"))
}

/**
 * A reversible stand-in for the Keystore-backed AEAD.
 * Why:    Tink's [Aead] is the exact interface the real implementation satisfies, so the manager
 *         is tested through the production contract. The "encryption" is a tagged XOR — not
 *         security, just a transform that is reversible and visibly not the plaintext, which is
 *         all the orchestration tests need.
 * Changelog: 2026-07-25 — Created for issue 1.6.
 */
private class ReversibleFakeAead : Aead {
    var encryptCalls = 0
    var lastAssociatedData: ByteArray? = null
    private val mask: Byte = 0x5A

    override fun encrypt(
        plaintext: ByteArray,
        associatedData: ByteArray?,
    ): ByteArray {
        encryptCalls++
        lastAssociatedData = associatedData
        return TAG + plaintext.map { (it.toInt() xor mask.toInt()).toByte() }.toByteArray()
    }

    override fun decrypt(
        ciphertext: ByteArray,
        associatedData: ByteArray?,
    ): ByteArray {
        if (!ciphertext.copyOfRange(0, TAG.size).contentEquals(TAG)) {
            throw GeneralSecurityException("bad tag")
        }
        return ciphertext.copyOfRange(TAG.size, ciphertext.size)
            .map { (it.toInt() xor mask.toInt()).toByte() }
            .toByteArray()
    }

    private companion object {
        val TAG = byteArrayOf(7, 7, 7, 7)
    }
}

/** An AEAD that refuses to encrypt, to exercise the failed-wrap path. */
private class FailingAead : Aead {
    override fun encrypt(
        plaintext: ByteArray,
        associatedData: ByteArray?,
    ): ByteArray = throw GeneralSecurityException("keystore unavailable")

    override fun decrypt(
        ciphertext: ByteArray,
        associatedData: ByteArray?,
    ): ByteArray = throw GeneralSecurityException("keystore unavailable")
}
