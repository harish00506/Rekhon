package com.aicfo.ml.llm

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.security.MessageDigest

/**
 * What the model check must get right (ADR-0079; §19, AI-ARC-007).
 *
 * Why:  this class decides whether half a gigabyte of someone else's weights gets loaded into the
 *       app's own process, so the interesting cases are all the ways a file can be wrong: missing,
 *       truncated, replaced by a different model, or damaged after it was verified once. Each of
 *       those must be refused, and refused for the right reason — a truncated download is a thing
 *       the user can fix, and telling them "corrupt" instead would send them to the wrong fix.
 * What: the four outcomes, the memo that avoids re-hashing, the memo's invalidation, and a sanity
 *       check on the published manifest itself.
 * Result: a verified path, or an honest refusal.
 * Changelog: 2026-10-10 — Created for ADR-0079.
 *
 * The fixture is a few bytes with its own manifest, because the real model cannot be a test
 * fixture for the same reason it cannot be in the APK. What is under test is the decision, not the
 * size of the file.
 */
@RunWith(RobolectricTestRunner::class)
class LlmModelStoreTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val weights = "pretend these are weights".toByteArray()
    private val manifest =
        LlmModelManifest(
            fileName = "test-model.task",
            sizeBytes = weights.size.toLong(),
            sha256 = sha256(weights),
            modelId = "on-device:test",
            licence = "Apache-2.0",
            source = "https://example.invalid/model",
        )
    private val store = LlmModelStore(context, manifest)

    @Before
    fun clean() {
        store.directory().listFiles()?.forEach { it.delete() }
    }

    @Test
    fun `no model file is the ordinary case, not a fault`() {
        assertEquals(ModelStatus.Absent, store.status())
    }

    @Test
    fun `a verified file is ready, and names the model for provenance`() {
        install(weights)

        val status = store.status()

        assertTrue("expected Ready but was $status", status is ModelStatus.Ready)
        assertEquals("on-device:test", (status as ModelStatus.Ready).modelId)
        assertEquals(File(store.directory(), "test-model.task").absolutePath, status.path)
    }

    @Test
    fun `a stopped download is refused for its length`() {
        install(weights.copyOfRange(0, weights.size - 3))

        assertEquals(ModelStatus.Rejected(RejectionReason.WRONG_LENGTH), store.status())
    }

    @Test
    fun `the right length with the wrong contents is refused for its digest`() {
        install(ByteArray(weights.size) { 7 })

        assertEquals(ModelStatus.Rejected(RejectionReason.WRONG_DIGEST), store.status())
    }

    @Test
    fun `a file that passed once is not hashed again`() {
        val file = install(weights)
        assertTrue(store.status() is ModelStatus.Ready)
        val stamp = file.lastModified()

        // Same length, same timestamp, different bytes: the only way this still reads Ready is if
        // the digest was taken from the receipt rather than from the file.
        file.writeBytes(ByteArray(weights.size) { 9 })
        file.setLastModified(stamp)

        assertTrue(store.status() is ModelStatus.Ready)
    }

    @Test
    fun `replacing the file after it passed invalidates the receipt`() {
        val file = install(weights)
        assertTrue(store.status() is ModelStatus.Ready)

        file.writeBytes(ByteArray(weights.size) { 9 })
        file.setLastModified(file.lastModified() + 5_000L)

        assertEquals(ModelStatus.Rejected(RejectionReason.WRONG_DIGEST), store.status())
    }

    @Test
    fun `the published manifest is a manifest and not a placeholder`() {
        val published = LlmModelManifest.PUBLISHED

        assertTrue("the file name must be the published one", published.fileName.endsWith(".task"))
        assertEquals("a SHA-256 is 64 hex characters", 64, published.sha256.length)
        assertTrue("the digest must be lower-case hex", published.sha256.all { it in "0123456789abcdef" })
        assertTrue("the published model is half a gigabyte", published.sizeBytes > 100_000_000L)
        assertEquals("Apache-2.0 weights are the whole point (ADR-0079)", "Apache-2.0", published.licence)
        assertTrue("provenance must say it ran on the device", published.modelId.startsWith("on-device:"))
    }

    private fun install(bytes: ByteArray): File = File(store.directory(), manifest.fileName).apply { writeBytes(bytes) }

    private companion object {
        fun sha256(bytes: ByteArray): String =
            MessageDigest
                .getInstance("SHA-256")
                .digest(bytes)
                .joinToString("") { "%02x".format(it) }
    }
}
