package com.aicfo.core.crypto

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.ByteArrayOutputStream

/**
 * Strips a receipt photo of everything but the picture (issue 3.8; §18, P-01).
 *
 * Why:  §18 asks for two things — *"EXIF stripped; auto-compress > 1 MB"* — and they turn out to be
 *       one operation. **Decoding an image to a [Bitmap] and re-encoding it is what strips the
 *       EXIF**, because a `Bitmap` is pixels and holds no metadata at all; there is no separate
 *       stripping step to get wrong or to forget. That matters more than the file size does: a
 *       phone camera writes the GPS coordinates of where a photo was taken into its EXIF, so a
 *       receipt stored as captured would silently record where the user was standing when they
 *       bought their groceries. Encrypting that at rest is not a defence — the app itself would
 *       still be holding it, and any future export or share would carry it out (P-01).
 * What: decode → re-encode as JPEG, stepping the quality down until the result fits the cap.
 * Result: a picture of a receipt and nothing else, small enough to encrypt in memory.
 * Changelog: 2026-08-06 — Created for issue 3.8.
 *
 * **This lives in `:core:crypto` because it is a privacy control, not an image feature.** The module
 * exists to hold the things that stop the user's data leaking; EXIF GPS is exactly such a leak, and
 * keeping it beside the store that encrypts the result means the two cannot be applied separately.
 *
 * **It is the one part of issue 3.8 that needs a device**, like `:ml:ocr`'s recogniser: a real
 * `Bitmap` decode is Android's. It is deliberately branch-free apart from the quality loop, so what
 * can only be checked on a device is as small as possible.
 */
object ReceiptImagePrivacy {
    /** What the sanitised plaintext always is, whatever came in. Recorded on the attachment row. */
    const val MIME_TYPE = "image/jpeg"

    /** §18's cap. Above it the quality steps down; below it the first encode is kept. */
    private const val MAX_BYTES = 1_000_000

    /** Where the quality ladder starts. High enough that printed text stays legible. */
    private const val START_QUALITY = 90

    /** How far each step drops. Four steps from [START_QUALITY] reach [MIN_QUALITY]. */
    private const val QUALITY_STEP = 15

    /**
     * The floor. Below this a receipt's small print stops being readable, and an unreadable
     * attachment is worse than a large one — the whole reason to keep the image is that the user can
     * check it later.
     */
    private const val MIN_QUALITY = 30

    /**
     * Removes the metadata and brings the size under the cap.
     * Why:    **always re-encodes, even for a small image.** Only compressing files over 1 MB would
     *         honour half of §18 and leave the EXIF on every receipt photographed by a phone that
     *         happens to write small JPEGs — which is the half that carries the user's location.
     *         The size cap is then a second, cheaper concern handled by the same loop.
     * Result: JPEG bytes with no metadata, at most [MAX_BYTES] where the quality floor allows it.
     *         **Returns the input unchanged when it will not decode**, which cannot happen on the
     *         real path — `ReceiptTextRecognizer` has already decoded the same bytes and refused
     *         them otherwise — and is a corrupt file rather than a reason to lose the user's scan.
     * Input:  [bytes] — the image as captured or picked. Output: [ByteArray].
     * Changelog: 2026-08-06 — Created for issue 3.8.
     */
    fun sanitize(bytes: ByteArray): ByteArray {
        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return bytes
        var quality = START_QUALITY
        var encoded = bitmap.toJpeg(quality)
        while (encoded.size > MAX_BYTES && quality > MIN_QUALITY) {
            quality -= QUALITY_STEP
            encoded = bitmap.toJpeg(quality)
        }
        return encoded
    }

    /**
     * Encodes a bitmap as JPEG at one quality.
     * Why:    extracted so [sanitize]'s loop reads as the ladder it is, and so the stream is closed
     *         once rather than at each rung.
     * Result: the encoded bytes. Input: the receiver; [quality] — 0..100. Output: [ByteArray].
     * Changelog: 2026-08-06 — Created for issue 3.8.
     */
    private fun Bitmap.toJpeg(quality: Int): ByteArray =
        ByteArrayOutputStream().use { stream ->
            compress(Bitmap.CompressFormat.JPEG, quality, stream)
            stream.toByteArray()
        }
}
