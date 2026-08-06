package com.aicfo.core.crypto

import android.graphics.Bitmap
import com.aicfo.core.common.AppError
import com.aicfo.core.common.Err
import com.aicfo.core.common.Ok
import com.aicfo.core.common.getOrNull
import com.google.crypto.tink.Aead
import com.google.crypto.tink.KeyTemplates
import com.google.crypto.tink.KeysetHandle
import com.google.crypto.tink.RegistryConfiguration
import com.google.crypto.tink.aead.AeadConfig
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * Tests for [TinkReceiptImageStore] — FR-OCR-005's encrypted attachment (issue 3.8).
 *
 * Why:  the failure that matters here is silent. A store that wrote the receipt to disk unencrypted
 *       would round-trip perfectly, pass every functional test, and leave a photograph of the user's
 *       shopping — with the GPS coordinates of where they were standing — readable by anything with
 *       filesystem access. So the load-bearing assertion is not "read gives back what write took",
 *       it is **"the bytes on disk are not the bytes we were given"**.
 * What: the round trip, what is actually on disk, the associated-data binding, and erasure.
 * Result: FR-OCR-005 is proved rather than asserted in a doc comment.
 * Changelog: 2026-08-06 — Created for issue 3.8.
 *
 * **Runs on Tink's own in-memory keyset, not the Android Keystore** — the Keystore needs a device,
 * and what is being tested here is this class's behaviour rather than Tink's key management. The
 * Keystore wiring is [ReceiptImageStoreFactory]'s three lines, verified on the device run.
 *
 * **Robolectric**, because [ReceiptImagePrivacy] decodes a real `Bitmap`.
 */
@RunWith(RobolectricTestRunner::class)
class ReceiptImageStoreTest {
    @get:Rule
    val folder = TemporaryFolder()

    private lateinit var aead: Aead
    private lateinit var store: ReceiptImageStore

    @Before
    fun setUp() {
        AeadConfig.register()
        aead =
            KeysetHandle.generateNew(KeyTemplates.get("AES256_GCM"))
                .getPrimitive(RegistryConfiguration.get(), Aead::class.java)
        store = ReceiptImageStoreFactory.create(aead, File(folder.root, "receipts"))
    }

    @Test
    fun `an image written can be read back`() =
        runTest {
            val stored = store.write(ATTACHMENT, anImage()).getOrNull()!!

            val read = store.read(ATTACHMENT, stored.fileName)

            assertTrue("a freshly written blob must decrypt", read is Ok)
            assertTrue("the plaintext must not be empty", (read as Ok).value.isNotEmpty())
        }

    @Test
    fun `the bytes on disk are not the bytes handed in`() =
        runTest {
            val plaintext = anImage()

            val stored = store.write(ATTACHMENT, plaintext).getOrNull()!!

            val onDisk = File(folder.root, "receipts/${stored.fileName}").readBytes()
            assertFalse(
                "FR-OCR-005: a receipt must never sit on disk in the clear",
                onDisk.contentEquals(plaintext),
            )
            // JPEG's magic bytes. Ciphertext that still opened in a gallery app would mean the
            // encryption step had been skipped in a way the round-trip test could not see.
            assertFalse("the blob must not still be a readable image", onDisk[0] == JPEG_MAGIC)
        }

    @Test
    fun `a blob cannot be moved to another attachment`() =
        runTest {
            val stored = store.write(ATTACHMENT, anImage()).getOrNull()!!

            val read = store.read("some-other-attachment", stored.fileName)

            assertTrue("the attachment id is the associated data — this must not decrypt", read is Err)
            assertEquals(AppError.Crypto("receipt_read").code, (read as Err).error.code)
        }

    @Test
    fun `a missing blob is not found rather than a crypto failure`() =
        runTest {
            val read = store.read(ATTACHMENT, "never-written.bin")

            assertEquals(AppError.NotFound, (read as Err).error)
        }

    @Test
    fun `erasing removes the file`() =
        runTest {
            val stored = store.write(ATTACHMENT, anImage()).getOrNull()!!

            assertTrue(store.erase(stored.fileName) is Ok)

            assertFalse(
                "FR-OCR-005: deleting the image must leave nothing behind",
                File(folder.root, "receipts/${stored.fileName}").exists(),
            )
            assertEquals(AppError.NotFound, (store.read(ATTACHMENT, stored.fileName) as Err).error)
        }

    @Test
    fun `erasing something already gone is a success`() =
        runTest {
            assertTrue(
                "an idempotent erase is what lets a retry or a demo wipe finish",
                store.erase("never-written.bin") is Ok,
            )
        }

    @Test
    fun `the stored size describes the sanitised plaintext`() =
        runTest {
            val stored = store.write(ATTACHMENT, anImage()).getOrNull()!!

            assertEquals(ReceiptImagePrivacy.MIME_TYPE, stored.mimeType)
            assertTrue("a stored image must have a size", stored.byteSize > 0L)
        }

    @Test
    fun `the blob is named after its attachment`() =
        runTest {
            val stored = store.write(ATTACHMENT, anImage()).getOrNull()!!

            assertTrue("the file name must derive from the row id", stored.fileName.startsWith(ATTACHMENT))
        }

    @Test
    fun `a real attachment id with a colon in it still erases`() =
        runTest {
            // The regression this exists for: production ids are `att:<uuid>`, and on Windows a
            // colon in a path is an NTFS alternate-data-stream separator — so the blob was written
            // to a stream hanging off a file called `att`, and erasing it silently did nothing while
            // reporting success. A receipt the user asked to delete surviving is an FR-OCR-005
            // failure that nothing else here would have caught.
            val realId = "att:1e5f0c8a-2b44-4e3f-9f2f-8c9e0d1a2b3c"

            val stored = store.write(realId, anImage()).getOrNull()!!
            assertTrue(store.erase(stored.fileName) is Ok)

            assertEquals(
                "nothing may be left in the directory after the only blob is erased",
                emptyList<String>(),
                File(folder.root, "receipts").listFiles().orEmpty().map { it.name },
            )
        }

    // There is deliberately no case here for bytes that are not an image. Robolectric's
    // `BitmapFactory` hands back a placeholder bitmap for any input rather than null, so such a test
    // would assert Robolectric's behaviour instead of ours. `ReceiptImagePrivacy.sanitize` documents
    // that branch; it is unreachable on the real path, because `ReceiptTextRecognizer` decodes the
    // same bytes first and refuses them.

    /**
     * Result: a small JPEG. Input: none. Output: [ByteArray] — real encoded bytes rather than random
     *         data, so the sanitising step is exercised the way the app exercises it.
     */
    private fun anImage(): ByteArray =
        ByteArrayOutputStream().use { stream ->
            Bitmap.createBitmap(IMAGE_SIDE, IMAGE_SIDE, Bitmap.Config.ARGB_8888)
                .compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, stream)
            stream.toByteArray()
        }

    private companion object {
        const val ATTACHMENT = "at-1"
        const val IMAGE_SIDE = 32
        const val JPEG_QUALITY = 90

        /** The first byte of every JPEG file, `0xFF`. */
        const val JPEG_MAGIC: Byte = -1
    }
}
