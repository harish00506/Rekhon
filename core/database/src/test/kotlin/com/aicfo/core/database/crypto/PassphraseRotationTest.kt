package com.aicfo.core.database.crypto

import com.aicfo.core.common.AppError
import com.aicfo.core.common.Err
import com.aicfo.core.common.Ok
import com.aicfo.core.common.Result
import com.aicfo.core.common.getOrNull
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.SecureRandom

/**
 * Rotating the database key without losing the database (issue 11.1; SEC-003, §23).
 *
 * Why:  §23 asks for a rotation path, and the naive one destroys data. Re-keying is two writes
 *       that must agree — the file's own encryption and the wrapped passphrase on disk — and a
 *       process can die between them. Whichever order you choose, one crash window leaves a
 *       database encrypted with a key the app no longer has, which is indistinguishable from
 *       losing every transaction the user ever recorded, and unrecoverable.
 *
 *       So the passphrase gets **two slots**. The new key is written to a pending slot *before*
 *       the file is re-keyed and promoted only *after*; if the app dies anywhere in between, both
 *       keys still exist and the next open simply tries them in turn. These tests are the
 *       protocol: each one is a point where the power could go out.
 * What: the ordering, the promotion, the rollback on a failed re-key, and the recovery on the
 *       open that follows a crash.
 * Result: no sequence of failures leaves the file encrypted with a key that is not on disk.
 * Changelog: 2026-09-28 — Created for issue 11.1.
 */
class PassphraseRotationTest {
    private val store = RecordingPassphraseStore()
    private val aead = ReversibleFakeAead()
    private val manager = SqlCipherPassphraseManager(store, aead, SecureRandom())

    @Test
    fun `the new key is on disk before the file is re-keyed`() {
        // The ordering the whole protocol rests on. Re-keying first would open a window where the
        // file's key exists nowhere but in memory.
        val original = manager.candidates().getOrNull()!!.current
        var pendingWhenCalled: ByteArray? = null

        manager.rotate { change ->
            pendingWhenCalled = store.pending
            assertArrayEquals("the re-key needs the key the file is on", original, change.previous)
            Ok(Unit)
        }

        assertNotNull("nothing was staged before the re-key", pendingWhenCalled)
    }

    @Test
    fun `a successful re-key promotes the new key and leaves no pending slot`() {
        manager.candidates()

        val rotated = manager.rotate { Ok(Unit) }.getOrNull()!!

        val after = manager.candidates().getOrNull()!!
        assertArrayEquals("the promoted key is the one the caller was given", rotated, after.current)
        assertNull("a promoted key must not stay pending", after.pending)
    }

    @Test
    fun `a failed re-key leaves the old key in charge and clears the staging slot`() {
        // The file is untouched, so the app must go on opening it with the key it already had.
        // Leaving the new key staged would make the *next* open try a key that opens nothing.
        val original = manager.candidates().getOrNull()!!.current

        val outcome = manager.rotate { Err(AppError.Crypto("rekey_failed")) }

        assertTrue("a failed rotation is an error, not a silent no-op", outcome is Err)
        val after = manager.candidates().getOrNull()!!
        assertArrayEquals(original, after.current)
        assertNull(after.pending)
    }

    @Test
    fun `a crash between the re-key and the promotion leaves both keys openable`() {
        // The window the two slots exist for: the file is on the new key, the store still says the
        // old one is current. Both must come back, or the database is gone.
        val original = manager.candidates().getOrNull()!!.current
        val staged = store.stageOnly(manager, aead)

        val after = manager.candidates().getOrNull()!!

        assertArrayEquals(original, after.current)
        assertArrayEquals("the staged key must survive the crash", staged, after.pending)
    }

    @Test
    fun `confirming the pending key promotes it`() {
        val staged = store.stageOnly(manager, aead)

        manager.confirm(staged)

        val after = manager.candidates().getOrNull()!!
        assertArrayEquals(staged, after.current)
        assertNull(after.pending)
    }

    @Test
    fun `confirming the current key throws the pending one away`() {
        // The other half of the same crash: the process died *before* the re-key took effect, so
        // the file is still on the old key. The staged key never applied to anything and must go,
        // or every later open would carry a candidate that opens nothing.
        val current = manager.candidates().getOrNull()!!.current
        store.stageOnly(manager, aead)

        manager.confirm(current)

        val after = manager.candidates().getOrNull()!!
        assertArrayEquals(current, after.current)
        assertNull(after.pending)
    }

    @Test
    fun `rotating before there is anything to rotate is refused`() {
        // A caller in this state is confused about whether a database exists, and minting a second
        // key would be the start of exactly the divergence this protocol prevents.
        val outcome = manager.rotate { Ok(Unit) }

        assertTrue(outcome is Err)
        assertNull("nothing may be staged by a refused rotation", store.pending)
    }

    @Test
    fun `a pending key with no current one is discarded rather than trusted`() {
        // Only reachable through a partial wipe or a restore that copied one file and not the
        // other. A key with nothing to be pending *to* is not a candidate; it is debris.
        store.pending = aead.encrypt(ByteArray(32) { 7 }, SqlCipherPassphraseManager.ASSOCIATED_DATA)

        val candidates = manager.candidates().getOrNull()!!

        assertEquals(SqlCipherPassphraseManager.PASSPHRASE_BYTES, candidates.current.size)
        assertNull(candidates.pending)
    }
}

/**
 * A two-slot store that keeps its bytes in memory.
 * Why:    the protocol's failure cases are *states*, not exceptions — a pending slot with no
 *         promotion, a promotion with no pending — so the fake exposes both slots directly and
 *         lets a test put the store in the state a crash would have left behind.
 * Result: every window in the rotation is reachable from a test.
 * Changelog: 2026-09-28 — Created for issue 11.1.
 */
private class RecordingPassphraseStore : WrappedPassphraseStore {
    var current: ByteArray? = null
    var pending: ByteArray? = null

    override fun read(): Result<ByteArray?, AppError> = Ok(current)

    override fun write(wrapped: ByteArray): Result<Unit, AppError> = Ok(Unit).also { current = wrapped }

    override fun readPending(): Result<ByteArray?, AppError> = Ok(pending)

    override fun writePending(wrapped: ByteArray): Result<Unit, AppError> = Ok(Unit).also { pending = wrapped }

    override fun promotePending(): Result<Unit, AppError> =
        Ok(Unit).also {
            current = pending
            pending = null
        }

    override fun clearPending(): Result<Unit, AppError> = Ok(Unit).also { pending = null }

    /**
     * Leaves the store exactly as a crash between the re-key and the promotion would.
     * Result: the staged passphrase. Input: [manager]; [aead] — to wrap it the way the manager
     *         would have. Output: the raw staged bytes.
     */
    fun stageOnly(
        manager: SqlCipherPassphraseManager,
        aead: com.google.crypto.tink.Aead,
    ): ByteArray {
        manager.candidates()
        val staged = ByteArray(SqlCipherPassphraseManager.PASSPHRASE_BYTES) { 9 }
        pending = aead.encrypt(staged, SqlCipherPassphraseManager.ASSOCIATED_DATA)
        return staged
    }
}
