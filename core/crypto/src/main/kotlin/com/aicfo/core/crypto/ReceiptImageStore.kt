package com.aicfo.core.crypto

import android.content.Context
import com.aicfo.core.common.AppError
import com.aicfo.core.common.Err
import com.aicfo.core.common.Ok
import com.aicfo.core.common.Result
import com.google.crypto.tink.Aead
import com.google.crypto.tink.KeyTemplates
import com.google.crypto.tink.RegistryConfiguration
import com.google.crypto.tink.aead.AeadConfig
import com.google.crypto.tink.integration.android.AndroidKeysetManager
import java.io.File

/**
 * Keeps receipt images encrypted at rest (issue 3.8; FR-OCR-005, SEC-003, P-01).
 *
 * Why:  FR-OCR-005 is a MUST — *"The original image MUST be stored encrypted and linked as an
 *       attachment; user can delete image while keeping the transaction."* A receipt photo is the
 *       most disclosive single file this app will ever hold: it names a shop, a date, a time and a
 *       list of purchases, and a photo of one taken on a phone usually carries the GPS coordinates
 *       of where it was taken as well. So the bytes are encrypted before they touch the disk, and
 *       erasing one is a file deletion rather than a row edit.
 * What: three operations — write, read, erase — over app-private storage.
 * Result: the storage half of FR-OCR-005. The attachment *row* is the database's job; this owns the
 *       bytes.
 * Changelog: 2026-08-06 — Created for issue 3.8.
 *
 * **SEC-003 by construction**: Tink only, no hand-rolled crypto, no new primitive, and no
 * `javax.crypto` anywhere in this module.
 */
interface ReceiptImageStore {
    /**
     * Sanitises and encrypts one image, and writes it.
     * Why:    **the image is decoded and re-encoded before it is encrypted**, which is how §18's
     *         "EXIF stripped; auto-compress > 1 MB" is honoured — see [ReceiptImagePrivacy] for why
     *         those two are one operation rather than two.
     * Result: `Ok(stored)` naming the blob and its plaintext size, or `Err(AppError.Crypto)` when
     *         the encryption or the write failed. Nothing partial is left behind: the write is one
     *         call, and a failure leaves no file.
     * Input:  [attachmentId] — the row this belongs to, used as the file name **and** as the
     *         associated data, so a blob cannot be swapped between rows; [bytes] — the image as
     *         captured or picked.
     * Output: `Result<StoredImage, AppError>`.
     */
    suspend fun write(
        attachmentId: String,
        bytes: ByteArray,
    ): Result<StoredImage, AppError>

    /**
     * Reads one image back.
     * Result: `Ok(bytes)` — the sanitised plaintext — or `Err(AppError.NotFound)` when the blob is
     *         gone, which is the ordinary answer after the user deleted it, and
     *         `Err(AppError.Crypto)` when it will not decrypt. **A tampered or swapped blob fails
     *         here rather than returning someone else's receipt**: the attachment id is the
     *         associated data, so AES-GCM's tag covers it.
     * Input:  [attachmentId]; [fileName] — as recorded on the attachment row.
     * Output: `Result<ByteArray, AppError>`.
     */
    suspend fun read(
        attachmentId: String,
        fileName: String,
    ): Result<ByteArray, AppError>

    /**
     * Erases one image (FR-OCR-005's "delete image while keeping the transaction").
     * Why:    **idempotent on purpose.** A blob that is already gone is the state the caller wanted,
     *         so a second erase — a retry, a demo wipe after a delete — is a success rather than an
     *         error. Reporting it as a failure would make the caller retry forever.
     * Result: `Ok(Unit)` once the file is absent. `Err(AppError.Storage)` only when the filesystem
     *         refused.
     * Input:  [fileName] — as recorded on the attachment row. Output: `Result<Unit, AppError>`.
     */
    suspend fun erase(fileName: String): Result<Unit, AppError>
}

/**
 * What a stored receipt image turned out to be (issue 3.8).
 *
 * Why:    the three facts the attachment row records, returned together so the caller never has to
 *         re-measure a file it just wrote — and so [StoredImage.byteSize] describes the *sanitised*
 *         plaintext rather than whatever came out of the camera, which is what a settings screen
 *         asking "how much are receipts costing?" needs to know.
 * Result: the payload of [ReceiptImageStore.write].
 * Changelog: 2026-08-06 — Created for issue 3.8.
 *
 * Input:  [fileName] — the blob's name inside app-private storage, never a path; [mimeType] — what
 *         the plaintext is; [byteSize] — the sanitised plaintext's size in bytes.
 * Output: an immutable value.
 */
data class StoredImage(
    val fileName: String,
    val mimeType: String,
    val byteSize: Long,
)

/**
 * Builds the production [ReceiptImageStore] (ARC-003, SEC-003).
 *
 * Why:  mirrors [KeystoreMacFactory] exactly, including the part that matters most: **its own
 *       keyset, its own preferences file and its own master-key alias.** Sharing issue 1.6's
 *       database-key alias would tie a receipt image to the database passphrase, so rotating or
 *       destroying either would silently take the other with it — and erase-all (SEC-006) destroys
 *       the database key on purpose.
 * What: creates or loads the Keystore-backed [Aead] and assembles the store over `filesDir`.
 * Result: the store the DI graph injects.
 * Changelog: 2026-08-06 — Created for issue 3.8.
 *
 * **On StrongBox:** as in `KeystoreAeadFactory` and [KeystoreMacFactory], Tink's Android
 * integration exposes no StrongBox flag, and selecting one would mean hand-building a
 * `KeyGenParameterSpec` with `javax.crypto` — which SEC-003 forbids. The master key is TEE-backed
 * via the standard Keystore provider. Revisit if Tink adds a first-class option.
 */
object ReceiptImageStoreFactory {
    /** Keyset name inside the preferences file; changing it orphans every stored receipt. */
    private const val KEYSET_NAME = "cfo_receipt_keyset"

    /** The preferences file holding the *encrypted* keyset. Never holds a usable key. */
    private const val KEYSET_PREF_FILE = "cfo_receipt_keyset_prefs"

    /** The Keystore alias of the master key that encrypts the keyset. Never exported. */
    private const val MASTER_KEY_URI = "android-keystore://cfo_receipt_master_key"

    /** The app-private directory the ciphertext blobs live in. */
    private const val DIRECTORY = "receipts"

    /**
     * Creates (or loads) the store.
     * Why:    the keyset is generated on first call and reused afterwards; regenerating it would
     *         make every previously stored receipt permanently unreadable.
     * Result: a [ReceiptImageStore] over the Keystore and app-private storage.
     * Input:  [context] — any context; the application context is used.
     * Output: [ReceiptImageStore].
     *
     * Throws only on a platform-level Keystore failure, which is a programmer- or device-level
     * problem rather than anything the store's own operations can convert to an [AppError].
     */
    fun create(context: Context): ReceiptImageStore {
        val application = context.applicationContext
        AeadConfig.register()
        val aead =
            AndroidKeysetManager
                .Builder()
                .withSharedPref(application, KEYSET_NAME, KEYSET_PREF_FILE)
                .withKeyTemplate(KeyTemplates.get("AES256_GCM"))
                .withMasterKeyUri(MASTER_KEY_URI)
                .build()
                .keysetHandle
                .getPrimitive(RegistryConfiguration.get(), Aead::class.java)
        return TinkReceiptImageStore(aead, File(application.filesDir, DIRECTORY))
    }

    /**
     * Assembles a store over a caller-supplied key and directory.
     * Why:    the seam the tests use — Tink's own in-memory keyset works on the JVM, where the
     *         Android Keystore does not, so the encryption round-trip is provable without a device.
     *         Marked as such rather than being reachable only through reflection or an `internal`
     *         visibility the test would have to defeat.
     * Result: a [ReceiptImageStore]. Input: [aead] — any Tink AEAD; [directory] — where blobs go.
     * Output: [ReceiptImageStore].
     */
    fun create(
        aead: Aead,
        directory: File,
    ): ReceiptImageStore = TinkReceiptImageStore(aead, directory)
}

/**
 * The production [ReceiptImageStore] (issue 3.8; FR-OCR-005, SEC-003).
 *
 * Why:  **`Aead`, not `StreamingAead`.** §18 caps a stored receipt at 1 MB, which fits in memory
 *       comfortably, and the streaming API is the more complex of two correct options — more state,
 *       more ways to leave a half-written file behind. Cleverness is a cost.
 * What: sanitise → encrypt → write; read → decrypt; erase → delete.
 * Result: ciphertext on disk and nothing else.
 * Changelog: 2026-08-06 — Created for issue 3.8.
 *
 * `internal` per ARC-003 — constructed only by [ReceiptImageStoreFactory].
 *
 * **The attachment id is the associated data**, so AES-GCM's authentication tag covers *which row*
 * the blob belongs to. Moving a ciphertext file from one attachment to another therefore fails to
 * decrypt rather than quietly showing the user a different receipt.
 *
 * Input:  [aead] — the Tink primitive; [directory] — app-private, created on first write.
 * Output: a working store.
 */
internal class TinkReceiptImageStore(
    private val aead: Aead,
    private val directory: File,
) : ReceiptImageStore {
    override suspend fun write(
        attachmentId: String,
        bytes: ByteArray,
    ): Result<StoredImage, AppError> =
        runCatching {
            val plaintext = ReceiptImagePrivacy.sanitize(bytes)
            directory.mkdirs()
            val fileName = attachmentId.asFileName()
            File(directory, fileName).writeBytes(aead.encrypt(plaintext, attachmentId.toByteArray()))
            StoredImage(fileName, ReceiptImagePrivacy.MIME_TYPE, plaintext.size.toLong())
        }.fold(
            // The operation name only — never the file path or the exception's message, either of
            // which could name the user's storage layout in something that gets logged (§21.6).
            onSuccess = { Ok(it) },
            onFailure = { Err(AppError.Crypto("receipt_write")) },
        )

    override suspend fun read(
        attachmentId: String,
        fileName: String,
    ): Result<ByteArray, AppError> {
        val file = File(directory, fileName)
        if (!file.isFile) return Err(AppError.NotFound)
        return runCatching { aead.decrypt(file.readBytes(), attachmentId.toByteArray()) }
            .fold(onSuccess = { Ok(it) }, onFailure = { Err(AppError.Crypto("receipt_read")) })
    }

    override suspend fun erase(fileName: String): Result<Unit, AppError> =
        runCatching {
            val file = File(directory, fileName)
            // `!isFile` first: an absent blob is the state the caller asked for, so this is a
            // success rather than a `delete()` returning false and reading as a failure.
            if (!file.isFile || file.delete()) Ok(Unit) else Err(AppError.Storage("receipt_erase"))
        }.getOrElse { Err(AppError.Storage("receipt_erase")) }
}

/**
 * Turns an attachment id into a file name that is a file name everywhere.
 *
 * Why:    ids in this app are `att:<uuid>`, and **the colon is not a legal filename character on
 *         every platform this code runs on**. On Windows — where the unit tests run — `att:1.bin`
 *         is not a file called `att:1.bin` at all: it is an NTFS alternate data stream hanging off a
 *         file called `att`. Writing appeared to work and deleting appeared to succeed while leaving
 *         the data behind, which is the worst possible failure for something whose job is erasing a
 *         receipt on request (FR-OCR-005). Stripping the punctuation costs nothing: a UUID is still
 *         unique without its dashes and colons.
 *
 *         **The associated data is still the *unsanitised* id**, so the AEAD binding between a blob
 *         and its row is unaffected by this.
 * Result: the blob's file name. Input: the receiver — the attachment id. Output: [String].
 * Changelog: 2026-08-06 — Created for issue 3.8, after a Windows test run proved the colon silently
 *            defeated erasure.
 */
private fun String.asFileName(): String = filter { it.isLetterOrDigit() || it == '-' || it == '_' } + BLOB_SUFFIX

/** Ciphertext, not an image — named so nothing tries to open one with a gallery app. */
private const val BLOB_SUFFIX = ".bin"
