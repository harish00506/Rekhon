package com.aicfo.core.crypto

import com.aicfo.core.common.AppError
import com.aicfo.core.common.Err
import com.aicfo.core.common.Ok
import com.aicfo.core.common.Result
import com.aicfo.core.common.getOrNull
import com.google.crypto.tink.Mac
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.GeneralSecurityException
import java.security.SecureRandom

/**
 * Tests for [TinkPinVerifier] — the PIN fallback SEC-002 requires (issue 2.2, §23.2).
 *
 * Why:  this class decides whether a person holding the phone is the owner, so every one of its
 *       failure paths has to deny rather than allow. The expensive mistakes are all silent: storing
 *       something the PIN can be recovered from, letting a broken Keystore read as a correct PIN,
 *       or accepting a verification against a PIN that was never set. Each would leave the lock
 *       looking like it works while opening on the first tap.
 * What: the set/verify round trip, rejection of wrong and malformed PINs, what actually reaches
 *       disk, and every failure path — unreadable store, unwritable store, dead MAC, corrupt blob.
 * Result: the whole verifier is proven on the JVM; only the Keystore binding needs a device.
 * Changelog: 2026-07-26 — Created for issue 2.2 (written red before TinkPinVerifier.kt existed).
 *
 * The fake below implements Tink's own [Mac] interface, so the verifier is exercised through
 * exactly the contract the real Keystore-backed MAC satisfies.
 */
class PinVerifierTest {
    private val store = InMemoryPinCredentialStore()
    private val mac = FakeMac()
    private val verifier = TinkPinVerifier(store, mac, SecureRandom())

    // --- the round trip -------------------------------------------------------------------

    /** Input: a PIN that was just set. Output: asserts it verifies. */
    @Test
    fun `the PIN that was set verifies`() {
        assertTrue(verifier.setPin("1234") is Ok)
        assertEquals(true, verifier.verify("1234").getOrNull())
    }

    /** Input: a different PIN. Output: asserts it is rejected — the whole point of the class. */
    @Test
    fun `a different PIN is rejected`() {
        verifier.setPin("1234")
        assertEquals(false, verifier.verify("4321").getOrNull())
    }

    /** Input: a six-digit PIN. Output: asserts the longer form is supported end to end. */
    @Test
    fun `a six digit PIN round trips`() {
        verifier.setPin("135790")
        assertEquals(true, verifier.verify("135790").getOrNull())
        assertEquals(false, verifier.verify("135791").getOrNull())
    }

    /** Input: a re-set PIN. Output: asserts the old PIN stops working the moment a new one is set. */
    @Test
    fun `changing the PIN retires the old one`() {
        verifier.setPin("1234")
        verifier.setPin("567890")
        assertEquals(false, verifier.verify("1234").getOrNull())
        assertEquals(true, verifier.verify("567890").getOrNull())
    }

    // --- is a PIN set at all? -------------------------------------------------------------

    /** Input: a fresh install. Output: asserts no PIN is reported, which is what offers the setup flow. */
    @Test
    fun `no PIN is set on a fresh install`() {
        assertEquals(false, verifier.isPinSet().getOrNull())
    }

    /** Input: after setting and after clearing. Output: asserts the flag tracks both transitions. */
    @Test
    fun `isPinSet tracks set and clear`() {
        verifier.setPin("1234")
        assertEquals(true, verifier.isPinSet().getOrNull())
        assertTrue(verifier.clearPin() is Ok)
        assertEquals(false, verifier.isPinSet().getOrNull())
    }

    /**
     * Input:  a verification when no PIN has ever been set.
     * Output: asserts `false`, never `true`. Answering "correct" for an unset PIN would mean a fresh
     *         install, or one whose credential file was deleted, unlocks on any input at all.
     */
    @Test
    fun `verifying against no stored PIN denies`() {
        assertEquals(false, verifier.verify("1234").getOrNull())
    }

    // --- what is refused before it is ever stored ------------------------------------------

    /**
     * Input:  PINs that are too short, too long, empty, or not digits.
     * Output: asserts each is a typed validation error and that **nothing** is written. A 1-digit
     *         PIN would make SEC-002's lockout schedule meaningless — ten guesses covers it.
     */
    @Test
    fun `malformed PINs are rejected and never stored`() {
        listOf("", "1", "123", "1234567", "12a4", "12 4", "١٢٣٤").forEach { candidate ->
            val result = verifier.setPin(candidate)
            assertTrue("'$candidate' must be rejected", result is Err)
            assertEquals("validation", (result as Err).error.code)
            assertEquals("pin", (result.error as AppError.Validation).field)
        }
        assertFalse("a rejected PIN must leave the store empty", store.hasCredential())
    }

    // --- what actually reaches disk --------------------------------------------------------

    /**
     * Input:  a stored credential.
     * Output: asserts the PIN's own bytes appear nowhere in it. If they did, the file would *be*
     *         the PIN and every other protection here would be decoration.
     */
    @Test
    fun `the stored blob never contains the PIN`() {
        verifier.setPin("135790")
        val stored = store.read().getOrNull()!!
        val pinBytes = "135790".toByteArray()
        assertFalse(
            "the PIN must not appear inside the stored credential",
            stored.toList().windowed(pinBytes.size).any { it.toByteArray().contentEquals(pinBytes) },
        )
    }

    /**
     * Input:  the same PIN set on two independent installations.
     * Output: asserts the stored blobs differ. Equal blobs would mean the file is a fingerprint of
     *         the PIN itself — an attacker with one device's file could recognise the same PIN on
     *         another, and a precomputed table would cover all ten thousand 4-digit values.
     */
    @Test
    fun `the same PIN stores differently on two installations`() {
        verifier.setPin("1234")
        val other = InMemoryPinCredentialStore()
        TinkPinVerifier(other, mac, SecureRandom()).setPin("1234")
        assertNotEquals(
            store.read().getOrNull()!!.toList(),
            other.read().getOrNull()!!.toList(),
        )
    }

    /** Input: a stored credential. Output: asserts it went through the MAC rather than around it. */
    @Test
    fun `the credential is produced by the MAC`() {
        verifier.setPin("1234")
        assertTrue("SEC-003: the tag must come from Tink, not from local code", mac.computeCalls > 0)
    }

    // --- failure paths, all of which must deny ---------------------------------------------

    /** Input: a store that cannot be read. Output: asserts a typed storage error, not a crash. */
    @Test
    fun `an unreadable store surfaces as a storage error`() {
        val failing = TinkPinVerifier(FailingPinCredentialStore(), mac, SecureRandom())
        val result = failing.verify("1234")
        assertTrue(result is Err)
        assertEquals("storage", (result as Err).error.code)
    }

    /** Input: a store that cannot be written. Output: asserts setPin reports it rather than pretending. */
    @Test
    fun `an unwritable store fails the set rather than reporting success`() {
        val failing = TinkPinVerifier(FailingPinCredentialStore(), mac, SecureRandom())
        val result = failing.setPin("1234")
        assertTrue(result is Err)
        assertEquals("storage", (result as Err).error.code)
    }

    /**
     * Input:  a MAC that refuses to compute, standing in for an unavailable Keystore.
     * Output: asserts a typed crypto error and that nothing was written — a half-written credential
     *         would read back as a corrupt PIN and lock the user out for good.
     */
    @Test
    fun `a dead MAC fails the set and leaves nothing behind`() {
        val failing = TinkPinVerifier(store, DeadMac(), SecureRandom())
        val result = failing.setPin("1234")
        assertTrue(result is Err)
        assertEquals("crypto", (result as Err).error.code)
        assertFalse(store.hasCredential())
    }

    /**
     * Input:  a correct PIN, but a MAC that can no longer verify (a cleared Keystore, e.g. after
     *         the device lock screen was removed).
     * Output: asserts `false` — denied. This is the single most important direction in the class:
     *         an unverifiable MAC must read as a wrong PIN, never as a pass. The user's route back
     *         is biometric or a restore, not an app that opens because its crypto broke.
     */
    @Test
    fun `an unverifiable MAC denies rather than admits`() {
        verifier.setPin("1234")
        val broken = TinkPinVerifier(store, DeadMac(), SecureRandom())
        assertEquals(false, broken.verify("1234").getOrNull())
    }

    /**
     * Input:  a credential file truncated below the salt length.
     * Output: asserts a typed crypto error rather than an out-of-bounds crash or an accidental pass.
     */
    @Test
    fun `a truncated credential fails as a crypto error`() {
        verifier.setPin("1234")
        store.truncate()
        val result = verifier.verify("1234")
        assertTrue(result is Err)
        assertEquals("crypto", (result as Err).error.code)
    }

    /** Input: a credential whose tag was flipped. Output: asserts denial, not a pass. */
    @Test
    fun `a tampered credential denies`() {
        verifier.setPin("1234")
        store.corrupt()
        assertEquals(false, verifier.verify("1234").getOrNull())
    }
}

/**
 * A [PinCredentialStore] held in memory for tests.
 * Why:    the real store is a file; the verifier's logic does not care, and a fake keeps the tests
 *         fast and free of temp-directory cleanup — the same trade issue 1.6 made.
 * Changelog: 2026-07-26 — Created for issue 2.2.
 */
private class InMemoryPinCredentialStore : PinCredentialStore {
    private var bytes: ByteArray? = null

    override fun read(): Result<ByteArray?, AppError> = Ok(bytes)

    override fun write(credential: ByteArray): Result<Unit, AppError> = Ok(Unit).also { bytes = credential }

    override fun clear(): Result<Unit, AppError> = Ok(Unit).also { bytes = null }

    fun hasCredential() = bytes != null

    /** Cuts the blob below the salt length, standing in for a partially written or damaged file. */
    fun truncate() {
        bytes = bytes!!.copyOfRange(0, 4)
    }

    /** Flips a byte in the tag, standing in for tampering. */
    fun corrupt() {
        bytes = bytes!!.copyOf().also { it[it.lastIndex] = (it[it.lastIndex] + 1).toByte() }
    }
}

/** A store that fails both ways, to exercise the storage-error paths. */
private class FailingPinCredentialStore : PinCredentialStore {
    override fun read(): Result<ByteArray?, AppError> = Err(AppError.Storage("IOException"))

    override fun write(credential: ByteArray): Result<Unit, AppError> = Err(AppError.Storage("IOException"))

    override fun clear(): Result<Unit, AppError> = Err(AppError.Storage("IOException"))
}

/**
 * A deterministic stand-in for the Keystore-backed MAC.
 * Why:    Tink's [Mac] is the exact interface the real implementation satisfies, so the verifier is
 *         tested through the production contract. The "tag" is a rolling sum — not security, just a
 *         function of every input byte, which is all these tests need in order to tell "same input"
 *         from "different input".
 * Changelog: 2026-07-26 — Created for issue 2.2.
 */
private class FakeMac : Mac {
    var computeCalls = 0

    override fun computeMac(data: ByteArray): ByteArray {
        computeCalls++
        var rolling = 17
        data.forEach { rolling = rolling * 31 + it }
        return byteArrayOf(
            (rolling shr 24).toByte(),
            (rolling shr 16).toByte(),
            (rolling shr 8).toByte(),
            rolling.toByte(),
        )
    }

    override fun verifyMac(
        mac: ByteArray,
        data: ByteArray,
    ) {
        computeCalls++
        var rolling = 17
        data.forEach { rolling = rolling * 31 + it }
        val expected =
            byteArrayOf(
                (rolling shr 24).toByte(),
                (rolling shr 16).toByte(),
                (rolling shr 8).toByte(),
                rolling.toByte(),
            )
        if (!mac.contentEquals(expected)) throw GeneralSecurityException("invalid MAC")
    }
}

/** A MAC that always fails, standing in for a Keystore key that is gone or unavailable. */
private class DeadMac : Mac {
    override fun computeMac(data: ByteArray): ByteArray = throw GeneralSecurityException("keystore unavailable")

    override fun verifyMac(
        mac: ByteArray,
        data: ByteArray,
    ): Unit = throw GeneralSecurityException("keystore unavailable")
}
