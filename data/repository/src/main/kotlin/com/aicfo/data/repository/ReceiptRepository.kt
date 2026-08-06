package com.aicfo.data.repository

import com.aicfo.core.common.AppError
import com.aicfo.core.common.Clock
import com.aicfo.core.common.DispatcherProvider
import com.aicfo.core.common.Err
import com.aicfo.core.common.IdGenerator
import com.aicfo.core.common.Ok
import com.aicfo.core.common.Result
import com.aicfo.core.common.flatMap
import com.aicfo.core.common.runCatchingToResult
import com.aicfo.core.crypto.ReceiptImagePrivacy
import com.aicfo.core.crypto.ReceiptImageStore
import com.aicfo.core.database.CfoDatabase
import com.aicfo.core.database.entity.AttachmentEntity
import com.aicfo.core.model.Money
import com.aicfo.core.model.Transaction
import com.aicfo.core.model.TransactionSource
import com.aicfo.domain.engines.receipt.ReceiptEngine
import com.aicfo.domain.engines.receipt.ReceiptFields
import com.aicfo.domain.engines.receipt.ReceiptInput
import com.aicfo.domain.engines.receipt.ReceiptRules
import com.aicfo.ml.ocr.ReceiptTextRecognizer
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import java.time.LocalDate

/**
 * Turns a photograph into a proposed transaction, and keeps its image (issue 3.8; ARC-005).
 *
 * Why:  FR-OCR-001..006 need four things joined up that nothing else in the app joins: an
 *       on-device recogniser, a pure-Kotlin parser, an encrypted blob store and the ledger. This is
 *       the only class that sees all four, and the only one allowed to touch a DAO (ARC-005).
 *
 *       **It writes nothing until the user says so** (P-07). [scan] recognises, parses and looks for
 *       a possible duplicate, and that is all it does — no row, no file. Everything that persists
 *       goes through [save], [mergeInto] or [deleteImage], each of which is a button the user
 *       pressed.
 * What: one read-only scan, two writes, and one deletion.
 * Result: FR-OCR-002's on-device pipeline, FR-OCR-005's encrypted attachment, FR-OCR-006's merge.
 * Changelog: 2026-08-06 — Created for issue 3.8.
 *
 * **No network of any kind is reachable from here** (P-01). The recogniser's model ships in the APK
 * and the parser is pure Kotlin; a receipt cannot leave the device because there is no code path
 * that could carry it.
 */
interface ReceiptRepository {
    /**
     * Reads one photograph and proposes what it says.
     * Why:    the duplicate search happens here rather than at save time because FR-OCR-006 says the
     *         app must *"offer merge instead of creating a duplicate"* — an offer has to arrive
     *         before the user has committed to anything, not as a refusal afterwards.
     * Result: `Ok(scan)` with any subset of the fields filled — including none, for an unreadable
     *         photo, which §18 says falls back to manual entry rather than to an error.
     *         `Err(AppError.Validation)` when the bytes are not an image.
     * Input:  [bytes] — the image from the camera or the picker.
     * Output: `Result<ReceiptScan, AppError>`.
     */
    suspend fun scan(bytes: ByteArray): Result<ReceiptScan, AppError>

    /**
     * Looks for a transaction this receipt may already be (FR-OCR-006).
     * Why:    public as well as used by [scan], because the user may correct the amount or the date
     *         on the review screen — and a duplicate guard that only ran against the *parser's*
     *         reading would miss the case where the user fixed a misread into an exact match.
     * Result: `Ok(candidates)`, newest first; empty is the ordinary answer and means "save a new
     *         transaction". Only `manual` and `sms` rows are candidates — the requirement's own
     *         wording, because another scan is a different receipt rather than this one twice.
     * Input:  [amount] — the receipt total, sign-insensitive; [bookedOn] — the receipt date.
     * Output: `Result<List<Transaction>, AppError>`.
     */
    suspend fun findDuplicates(
        amount: Money,
        bookedOn: LocalDate,
    ): Result<List<Transaction>, AppError>

    /**
     * Saves a new transaction with its receipt attached (FR-OCR-005).
     * Why:    **the blob is written before the row** and erased again if the row fails, so a failure
     *         can leave an orphaned file at worst — recoverable, invisible and bounded — rather than
     *         a row pointing at an image that was never stored, which no later read could repair.
     * Result: `Ok(transaction)` with `source = ocr` (FR-TXN-009). `Err` with nothing persisted: a
     *         failed row erases the blob, and a failed blob writes no row.
     * Input:  [draft] — as the user confirmed it on the review screen; [imageBytes] — the photo, or
     *         `null` when there is nothing to keep.
     * Output: `Result<Transaction, AppError>`.
     */
    suspend fun save(
        draft: TransactionDraft,
        imageBytes: ByteArray?,
    ): Result<Transaction, AppError>

    /**
     * Attaches this receipt to a transaction that already exists (FR-OCR-006's merge).
     * Why:    the alternative the requirement asks for instead of a duplicate. It deliberately does
     *         **not** overwrite the existing row's amount, date or merchant: the row is the money
     *         that actually moved, recorded by the bank or by the user, and a parser's reading is
     *         not better evidence than either.
     * Result: `Ok(Unit)` with the receipt now linked. `Err` with the blob erased.
     * Input:  [transactionId] — the row to attach to; [imageBytes] — the photo.
     * Output: `Result<Unit, AppError>`.
     */
    suspend fun mergeInto(
        transactionId: String,
        imageBytes: ByteArray,
    ): Result<Unit, AppError>

    /**
     * Observes the receipt one transaction carries, if any.
     * Result: emits `null` when there is none — for a typed transaction, or after the image was
     *         deleted, which are the same thing as far as the screen is concerned.
     * Input:  [transactionId]. Output: `Flow<ReceiptAttachment?>`.
     */
    fun observeAttachment(transactionId: String): Flow<ReceiptAttachment?>

    /**
     * Decrypts one stored receipt for display.
     * Result: `Ok(bytes)` — the sanitised JPEG — or `Err(AppError.NotFound)` once it has been
     *         deleted. Never cached anywhere: the plaintext exists only for as long as the screen
     *         showing it does (P-01).
     * Input:  [attachment] — as emitted by [observeAttachment].
     * Output: `Result<ByteArray, AppError>`.
     */
    suspend fun readImage(attachment: ReceiptAttachment): Result<ByteArray, AppError>

    /**
     * Deletes the image and keeps the transaction (FR-OCR-005's second half).
     * Why:    both halves in one call, because they must not come apart. A tombstoned row whose blob
     *         survived is a receipt the user believes they deleted; an erased blob whose row
     *         survived is a screen showing a receipt that cannot be opened.
     * Result: `Ok(Unit)` with the transaction untouched. `Err(AppError.Storage)` when the erase
     *         failed, in which case the row is left live rather than lying about the file.
     * Input:  [attachmentId]. Output: `Result<Unit, AppError>`.
     */
    suspend fun deleteImage(attachmentId: String): Result<Unit, AppError>

    companion object {
        /**
         * Id prefix for an attachment row. Distinct from `txn` so a database dump reads as itself.
         */
        const val ID_PREFIX = "att"

        /** `attachments.kind` for a scanned receipt. A code from a closed set, never copy. */
        const val KIND_RECEIPT = "receipt"
    }
}

/**
 * What one scan produced (issue 3.8; FR-OCR-003, FR-OCR-006).
 *
 * Why:    the fields and the duplicates travel together because the review screen needs both at
 *         once: it pre-fills from one and decides whether to offer a merge from the other, and
 *         fetching them separately would let the screen render a save button before it knew whether
 *         saving was the right offer.
 * Result: the payload of [ReceiptRepository.scan].
 * Changelog: 2026-08-06 — Created for issue 3.8.
 *
 * Input:  [fields] — what the parser read, each with a confidence; [duplicates] — rows that may
 *         already be this purchase, newest first, empty in the ordinary case.
 * Output: an immutable value.
 */
data class ReceiptScan(
    val fields: ReceiptFields,
    val duplicates: List<Transaction>,
)

/**
 * One stored receipt, as anything above the data layer sees it (issue 3.8; FR-OCR-005).
 *
 * Why:    a domain-side value rather than `AttachmentEntity`, because ARC-005 says a ViewModel never
 *         sees a Room type. It carries the file name because [ReceiptRepository.readImage] needs it,
 *         and the size because a settings screen will want to say what receipts are costing.
 * Result: the element of [ReceiptRepository.observeAttachment].
 * Changelog: 2026-08-06 — Created for issue 3.8.
 *
 * Input:  [id] — the attachment row's id, also the AEAD's associated data; [fileName] — the blob's
 *         name inside app-private storage; [byteSize] — the plaintext's size in bytes.
 * Output: an immutable value.
 */
data class ReceiptAttachment(
    val id: String,
    val fileName: String,
    val byteSize: Long,
)

/**
 * The Room-backed [ReceiptRepository].
 * Why:    ARC-003 — one public interface, an internal implementation, assembled by the DI graph.
 *
 *         **It delegates the transaction write to [TransactionRepository] rather than repeating
 *         it.** That class already owns account validation, the date/time stamping FR-TXN-010 needs
 *         and the sign/type invariant; a second copy here would be the same rules in two places,
 *         drifting. This class adds only what is new — the blob and the attachment row.
 * Result: the implementation injected into the receipt review ViewModel.
 * Changelog: 2026-08-06 — Created for issue 3.8.
 *
 * Input:  [database] — for the attachment rows and the duplicate query; [transactions] — writes the
 *         ledger row; [recognizer] — on-device OCR (FR-OCR-002); [engine] — the parser (P-03);
 *         [images] — the encrypted blob store (FR-OCR-005); [clock] — every stamped instant and the
 *         parser's "today", never a wall-clock read (TIM-001); [ids]; [dispatchers];
 *         [activeProfileId] — which profile is scanned into, so the demo gets its own.
 * Output: a working repository.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@Suppress("LongParameterList") // Four collaborators and the three every repository here takes; see
// the interface — this class exists precisely to be the one place they meet (ARC-005).
internal class RoomReceiptRepository(
    private val database: CfoDatabase,
    private val transactions: TransactionRepository,
    private val recognizer: ReceiptTextRecognizer,
    private val engine: ReceiptEngine,
    private val images: ReceiptImageStore,
    private val clock: Clock,
    private val ids: IdGenerator,
    private val dispatchers: DispatcherProvider,
    private val activeProfileId: Flow<String>,
) : ReceiptRepository {
    override suspend fun scan(bytes: ByteArray): Result<ReceiptScan, AppError> =
        withContext(dispatchers.io) {
            recognizer.recognize(bytes)
                .flatMap { text ->
                    engine.extract(
                        ReceiptInput(
                            text = text,
                            todayIsoDate = clock.today().toString(),
                            nowUtcMillis = clock.nowUtcMillis(),
                        ),
                    )
                }
                .flatMap { fields -> Ok(ReceiptScan(fields, duplicatesFor(fields))) }
        }

    override suspend fun findDuplicates(
        amount: Money,
        bookedOn: LocalDate,
    ): Result<List<Transaction>, AppError> =
        withContext(dispatchers.io) {
            runCatchingToResult { duplicateRows(amount, bookedOn) }
        }

    override suspend fun save(
        draft: TransactionDraft,
        imageBytes: ByteArray?,
    ): Result<Transaction, AppError> =
        withContext(dispatchers.io) {
            // FR-TXN-009: the provenance is stamped here, not taken from the screen. A caller that
            // passed `manual` would be recording a claim rather than a fact.
            val ocrDraft = draft.copy(source = TransactionSource.OCR)
            if (imageBytes == null) return@withContext transactions.create(ocrDraft)
            val attachmentId = ids.newId(ReceiptRepository.ID_PREFIX)
            images.write(attachmentId, imageBytes).flatMap { stored ->
                val created = transactions.create(ocrDraft)
                when (created) {
                    // Erase, so a rejected draft cannot leave ciphertext nothing points at.
                    is Err -> images.erase(stored.fileName).let { created }
                    is Ok ->
                        link(attachmentId, created.value.id, stored.fileName, stored.byteSize)
                            .flatMap { Ok(created.value) }
                }
            }
        }

    override suspend fun mergeInto(
        transactionId: String,
        imageBytes: ByteArray,
    ): Result<Unit, AppError> =
        withContext(dispatchers.io) {
            val attachmentId = ids.newId(ReceiptRepository.ID_PREFIX)
            images.write(attachmentId, imageBytes).flatMap { stored ->
                link(attachmentId, transactionId, stored.fileName, stored.byteSize)
            }
        }

    override fun observeAttachment(transactionId: String): Flow<ReceiptAttachment?> =
        database.attachmentDao()
            .observeForTransaction(transactionId)
            .map { rows -> rows.firstOrNull()?.let { ReceiptAttachment(it.id, it.fileName, it.byteSize) } }
            .flowOn(dispatchers.io)

    override suspend fun readImage(attachment: ReceiptAttachment): Result<ByteArray, AppError> =
        withContext(dispatchers.io) { images.read(attachment.id, attachment.fileName) }

    override suspend fun deleteImage(attachmentId: String): Result<Unit, AppError> =
        withContext(dispatchers.io) {
            val row = database.attachmentDao().findById(attachmentId) ?: return@withContext Err(AppError.NotFound)
            // The blob first: a tombstoned row whose file survived is a receipt the user believes
            // they deleted, which is the failure this ordering makes impossible.
            images.erase(row.fileName).flatMap {
                runCatchingToResult { database.attachmentDao().softDelete(attachmentId, clock.nowUtcMillis()) }
                    .flatMap { Ok(Unit) }
            }
        }

    /**
     * Writes the attachment row linking a blob to a transaction.
     * Why:    one function for [save] and [mergeInto], which differ only in where the transaction id
     *         comes from. **The blob is erased if the row fails**, so the two never come apart in the
     *         direction that leaves unreferenced ciphertext on the user's device.
     * Result: `Ok(Unit)` once the row is present, or `Err(AppError.Storage)` with the blob erased.
     * Input:  [attachmentId]; [transactionId]; [fileName]; [byteSize] — the plaintext's size.
     * Output: `Result<Unit, AppError>`.
     * Changelog: 2026-08-06 — Created for issue 3.8.
     */
    private suspend fun link(
        attachmentId: String,
        transactionId: String,
        fileName: String,
        byteSize: Long,
    ): Result<Unit, AppError> {
        val now = clock.nowUtcMillis()
        val written =
            runCatchingToResult {
                database.attachmentDao().upsert(
                    AttachmentEntity(
                        id = attachmentId,
                        profileId = activeProfileId.first(),
                        transactionId = transactionId,
                        kind = ReceiptRepository.KIND_RECEIPT,
                        fileName = fileName,
                        mimeType = ReceiptImagePrivacy.MIME_TYPE,
                        byteSize = byteSize,
                        createdAtUtcMillis = now,
                        updatedAtUtcMillis = now,
                    ),
                )
            }
        return if (written is Err) images.erase(fileName).let { written } else Ok(Unit)
    }

    /**
     * Runs FR-OCR-006's guard against whatever the parser managed to read.
     * Why:    a scan with no total or no date cannot be a duplicate of anything — the requirement's
     *         test needs both — so this returns empty rather than guessing at one of them, which
     *         would offer to merge a receipt into a transaction that shares only a date.
     * Result: the candidate rows, or empty. Never throws: a failing duplicate query must not lose
     *         the scan the user just took, and "no duplicates" degrades to saving a new row.
     * Input:  [fields] — the parser's reading. Output: `List<Transaction>`.
     * Changelog: 2026-08-06 — Created for issue 3.8.
     */
    private suspend fun duplicatesFor(fields: ReceiptFields): List<Transaction> {
        val total = fields.total?.value ?: return emptyList()
        val date = fields.date?.value?.let { runCatching { LocalDate.parse(it) }.getOrNull() } ?: return emptyList()
        return runCatching { duplicateRows(total, date) }.getOrDefault(emptyList())
    }

    /**
     * FR-OCR-006's "±1% and ±1 day", as a query.
     * Why:    **the band is computed here in integer paise and handed to SQL as two bounds**
     *         (MNY-001). `amount * 99 / 100` truncates towards zero, which widens the lower bound by
     *         at most one paisa and can therefore only make the guard slightly more willing to
     *         offer a merge — the safe direction, since a merge is an offer the user can decline
     *         while a silent duplicate is one they never see.
     * Result: the candidate rows, newest first, mapped to domain models (ARC-005).
     * Input:  [amount] — the receipt total, magnitude taken; [bookedOn] — the receipt date.
     * Output: `List<Transaction>`.
     * Changelog: 2026-08-06 — Created for issue 3.8.
     */
    private suspend fun duplicateRows(
        amount: Money,
        bookedOn: LocalDate,
    ): List<Transaction> {
        val rules = ReceiptRules()
        val magnitude = if (amount.minor < 0L) -amount.minor else amount.minor
        val slack = Math.multiplyExact(magnitude, rules.duplicateAmountTolerancePct.toLong()) / PCT_TOTAL
        return database.transactionDao()
            .findDuplicateCandidates(
                profileId = activeProfileId.first(),
                minMinor = magnitude - slack,
                maxMinor = magnitude + slack,
                fromIsoDate = bookedOn.minusDays(rules.duplicateDateToleranceDays).toString(),
                toIsoDate = bookedOn.plusDays(rules.duplicateDateToleranceDays).toString(),
            )
            .mapNotNull { it.toTransaction() }
    }

    private companion object {
        /** The whole of an amount, as the divisor that turns a percent band into paise. */
        const val PCT_TOTAL = 100L
    }
}
