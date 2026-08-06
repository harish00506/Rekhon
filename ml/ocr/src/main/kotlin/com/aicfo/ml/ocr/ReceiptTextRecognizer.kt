package com.aicfo.ml.ocr

import com.aicfo.core.common.AppError
import com.aicfo.core.common.Result
import com.aicfo.core.model.RecognizedText

/**
 * Reads the text off a receipt photo, entirely on this device (issue 3.8; FR-OCR-002, P-01, P-04).
 *
 * Why:  FR-OCR-002 is a MUST and it is absolute — *"OCR MUST run fully on-device (ML Kit Text
 *       Recognition v2); no receipt image ever leaves the device unless the user explicitly shares
 *       it."* A receipt is the most personal document this app will ever hold: it names where the
 *       user was, when, and what they bought. So there is no network call behind this interface and
 *       no configuration that could add one — the model ships in the APK (see the module's
 *       `build.gradle.kts`), which is also what makes the scanner work in airplane mode.
 * What: one method, from image bytes to the recognised blocks.
 * Result: the input to `:domain:engines:receipt`, which decides what the text means. **Nothing here
 *       interprets anything** — no amount, no date, no merchant. That split is what lets the parser
 *       be a pure-Kotlin engine with frozen test fixtures while this stays a thin, device-only call.
 * Changelog: 2026-08-06 — Created for issue 3.8.
 *
 * One public interface with an `internal` implementation behind [ReceiptTextRecognizerFactory],
 * the ARC-003 seam every engine in this codebase already uses.
 */
interface ReceiptTextRecognizer {
    /**
     * Recognises the text in one image.
     * Why:    a `Result` rather than a throw, matching every other boundary in the app (§21.6).
     *         The realistic failures are ordinary rather than exceptional — an unreadable file, a
     *         picture that is not an image at all, an ML Kit failure on a corrupt frame — and each
     *         one should leave the user on a review screen they can fill in by hand (§18: "all
     *         failures fall back to manual entry"), not on a crash.
     * Result: `Ok(text)`, possibly with **no blocks at all** when the photo held nothing legible —
     *         that is a real answer, not an error, and the review screen renders it as empty fields.
     *         `Err(AppError.Validation)` when the bytes will not decode as an image, and
     *         `Err(AppError.Unexpected)` when the recogniser itself failed.
     * Input:  [bytes] — the image as read from the camera or the gallery, in any format
     *         `BitmapFactory` decodes (JPEG, PNG, WebP, HEIF).
     * Output: `Result<RecognizedText, AppError>`.
     */
    suspend fun recognize(bytes: ByteArray): Result<RecognizedText, AppError>
}

/**
 * Builds the recogniser for the DI graph (ARC-003).
 * Why:    the implementation is `internal`, so `:app`'s Hilt module cannot name it — the same seam
 *         `RecurringEngineFactory` uses, kept deliberately identical so the codebase has one
 *         pattern rather than several.
 * Result: a [ReceiptTextRecognizer].
 * Input:  none — the bundled model is loaded from the APK, so ML Kit's client needs no `Context`.
 * Output: the recogniser.
 * Changelog: 2026-08-06 — Created for issue 3.8.
 */
object ReceiptTextRecognizerFactory {
    /** Result: the production recogniser. Input: none. Output: [ReceiptTextRecognizer]. */
    fun create(): ReceiptTextRecognizer = MlKitReceiptTextRecognizer()
}
