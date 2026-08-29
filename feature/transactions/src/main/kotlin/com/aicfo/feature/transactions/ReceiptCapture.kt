package com.aicfo.feature.transactions

import android.content.Context
import android.net.Uri
import androidx.core.content.FileProvider
import java.io.File

/*
 * Getting a photograph into the app (issue 3.8; FR-OCR-001, P-01).
 *
 * A separate file from `ReceiptReviewScreen.kt` for the reason `SplitEditor.kt` is separate from the
 * add screen — that file sits at detekt's eleven-function ceiling (§21.6). The seam is real: nothing
 * here is a composable, and both functions are about the boundary between this app and another one.
 */

/**
 * A private, shareable destination for the camera to write into (FR-OCR-001).
 * Why:    `ActivityResultContracts.TakePicture` writes full-resolution output to a URI the caller
 *         supplies, and the only way to hand another app a file inside this app's own storage is a
 *         `FileProvider` grant. The file lives in **the cache directory**, so the receipt the camera
 *         wrote is disposable: the copy that matters is the encrypted blob the repository stores
 *         (FR-OCR-005), and Android is free to reclaim this one at any time (P-01).
 * Result: the URI to pass to the camera. Input: the receiver — any context. Output: [Uri].
 * Changelog: 2026-08-06 — Created for issue 3.8.
 */
internal fun Context.newPhotoUri(): Uri {
    val directory = File(cacheDir, CAPTURE_DIRECTORY).apply { mkdirs() }
    val file = File(directory, "$CAPTURE_PREFIX${System.nanoTime()}$CAPTURE_SUFFIX")
    return FileProvider.getUriForFile(this, "$packageName$FILE_PROVIDER_SUFFIX", file)
}

/**
 * Reads a picked or captured image into memory.
 * Why:    read here rather than passed onward as a URI, because a URI's read grant belongs to the
 *         activity that received it and does not survive process death — a ViewModel holding one
 *         would work until the day the system killed the app behind the camera.
 * Result: the bytes, or `null` when the URI could not be opened, which the ViewModel treats as no
 *         photo having arrived. Input: the receiver; [uri]. Output: `ByteArray?`.
 * Changelog: 2026-08-06 — Created for issue 3.8.
 */
internal fun Context.readBytes(uri: Uri): ByteArray? =
    runCatching { contentResolver.openInputStream(uri)?.use { it.readBytes() } }.getOrNull()

/** Inside `cacheDir`, so a captured photo is disposable — see [Context.newPhotoUri]. */
private const val CAPTURE_DIRECTORY = "receipt-capture"

private const val CAPTURE_PREFIX = "receipt-"

private const val CAPTURE_SUFFIX = ".jpg"

/** Must match the `android:authorities` in this module's manifest. */
private const val FILE_PROVIDER_SUFFIX = ".fileprovider"
