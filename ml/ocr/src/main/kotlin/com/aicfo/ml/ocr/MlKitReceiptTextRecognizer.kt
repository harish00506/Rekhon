package com.aicfo.ml.ocr

import android.graphics.BitmapFactory
import com.aicfo.core.common.AppError
import com.aicfo.core.common.Err
import com.aicfo.core.common.Ok
import com.aicfo.core.common.Result
import com.aicfo.core.model.RecognizedBlock
import com.aicfo.core.model.RecognizedText
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/**
 * The production [ReceiptTextRecognizer], over ML Kit Text Recognition v2 (issue 3.8; FR-OCR-002).
 *
 * **This is the only class in the project that touches proprietary code, and that is a licensing
 * fact as well as an architectural one.** Rekhon is AGPL; ML Kit cannot be, so `LICENSE` carries a
 * linking exception naming it. Keep the coupling here: no ML Kit type may reach a domain model —
 * `RecognizedText` exists precisely so none does — and nothing outside this file may import
 * `com.google.mlkit`. That is what keeps a fully free build, substituting an open-source
 * recogniser behind [ReceiptTextRecognizer], a contained change rather than a redesign.
 *
 * Why:  deliberately the thinnest class in this issue. Everything here needs a device and can only
 *       be checked by running the app, so every *decision* — which number is the total, which line
 *       is the date, what the merchant is called — lives in `:domain:engines:receipt`, where it is
 *       pure Kotlin and provable against frozen fixtures. What is left is a decode, a call and a
 *       mapping, and none of the three has a branch worth arguing about.
 * What: decodes the bytes, hands the bitmap to ML Kit, and maps its blocks onto [RecognizedText].
 * Result: the text, on-device, with no network of any kind involved.
 * Changelog: 2026-08-06 — Created for issue 3.8.
 *
 * `internal` per ARC-003 — constructed only by [ReceiptTextRecognizerFactory].
 *
 * **The one judgement here is [RecognizedBlock.topFraction]** — ML Kit gives a pixel `boundingBox`,
 * and §18.1's merchant rule needs a position that does not depend on the photo's resolution. It is
 * computed against the decoded bitmap's height, in basis points, and it is the piece of this issue
 * that cannot be unit-tested on the JVM. It is designed to fail softly: a wrong `topFraction`
 * degrades the *merchant* guess and nothing else, never the total the ≥ 95% eval gate measures.
 *
 * **It holds no `Context`.** ML Kit's `TextRecognition.getClient` does not take one — the bundled
 * model is loaded from the APK — so keeping a reference would be an unused field that reads as
 * though a context were needed for something.
 *
 * Input:  none. Output: a working recogniser.
 */
internal class MlKitReceiptTextRecognizer : ReceiptTextRecognizer {
    /**
     * Created once and reused. ML Kit's client holds the loaded model, and building a new one per
     * scan would reload it on every photo — a visible pause on the exact screen the user is waiting
     * on. `DEFAULT_OPTIONS` is the Latin-script recogniser: Indian receipts are printed in Latin
     * script even when the shop's name is not, and the Devanagari model is a separate artifact this
     * issue does not ship.
     */
    private val recognizer by lazy { TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS) }

    override suspend fun recognize(bytes: ByteArray): Result<RecognizedText, AppError> {
        // Decoded before the recogniser is touched, because a null bitmap is the one failure the
        // caller can act on: it means "that file was not a picture", not "OCR failed".
        val bitmap =
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                ?: return Err(AppError.Validation("image"))
        // Rotation 0: the system camera and the photo picker both hand back an upright bitmap, and
        // EXIF orientation is stripped before storage anyway (§18). A sideways receipt reads as
        // gibberish and falls back to manual entry, which is the documented degradation.
        val image = InputImage.fromBitmap(bitmap, 0)
        return awaitText(image, imageHeightPx = bitmap.height)
    }

    /**
     * Runs the recogniser and waits for it, without a `Task` → coroutine library.
     * Why:    ML Kit returns a Play-services `Task`. `kotlinx-coroutines-play-services` would turn
     *         that into `await()` — one call, and one more dependency in the APK for it. This is the
     *         same thing in a dozen lines, and it is cancellable, which the naive
     *         `suspendCoroutine` version is not: a user who backs out of the review screen mid-scan
     *         must not leave a continuation waiting on a model that is still running.
     * Result: `Ok(text)` — including an empty one for an unreadable photo — or
     *         `Err(AppError.Unexpected)` when ML Kit reported a failure. The error carries the
     *         exception's *class name only*: a message from a vision library could name the file
     *         path the image came from, and §21.6 bans that from anything that could be logged.
     * Input:  [image] — the decoded frame; [imageHeightPx] — the bitmap's height, used to turn each
     *         block's pixel position into basis points. Output: `Result<RecognizedText, AppError>`.
     * Changelog: 2026-08-06 — Created for issue 3.8.
     */
    private suspend fun awaitText(
        image: InputImage,
        imageHeightPx: Int,
    ): Result<RecognizedText, AppError> =
        suspendCancellableCoroutine { continuation ->
            recognizer
                .process(image)
                .addOnSuccessListener { text -> continuation.resume(Ok(text.toRecognizedText(imageHeightPx))) }
                .addOnFailureListener { failure ->
                    continuation.resume(Err(AppError.Unexpected(failure.javaClass.simpleName)))
                }
        }
}

/**
 * Maps ML Kit's result onto the app's own type (issue 3.8).
 * Why:    the parser must not import ML Kit — see [com.aicfo.core.model.RecognizedText] for why —
 *         so the translation happens here, once. Block order is preserved rather than re-sorted:
 *         ML Kit already returns blocks in reading order, and the engine sorts by [imageHeightPx]
 *         position itself when it needs to.
 * Result: the recognised text as [RecognizedText].
 * Input:  the receiver — ML Kit's result; [imageHeightPx] — the decoded bitmap's height in pixels.
 *         **Guarded against zero**, which a degenerate image could produce and which would
 *         otherwise divide by zero on the first block.
 * Output: [RecognizedText].
 * Changelog: 2026-08-06 — Created for issue 3.8.
 */
private fun Text.toRecognizedText(imageHeightPx: Int): RecognizedText =
    RecognizedText(
        blocks =
            textBlocks.map { block ->
                val top = block.boundingBox?.top ?: 0
                RecognizedBlock(
                    text = block.text,
                    topFraction =
                        if (imageHeightPx <= 0) {
                            0
                        } else {
                            (top.toLong() * BPS_FULL / imageHeightPx).coerceIn(0L, BPS_FULL).toInt()
                        },
                )
            },
    )

/** 10 000 bps = the full height of the image (MNY-002's unit, applied to a position). */
private const val BPS_FULL = 10_000L
