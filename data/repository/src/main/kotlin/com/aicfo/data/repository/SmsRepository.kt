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
import com.aicfo.core.database.CfoDatabase
import com.aicfo.core.database.entity.SmsDraftEntity
import com.aicfo.core.datastore.ConsentFeature
import com.aicfo.core.datastore.ConsentStore
import com.aicfo.core.datastore.SettingsStore
import com.aicfo.core.model.Money
import com.aicfo.core.model.Transaction
import com.aicfo.core.model.TransactionSource
import com.aicfo.data.sms.SmsInboxReader
import com.aicfo.domain.engines.sms.SmsDirection
import com.aicfo.domain.engines.sms.SmsDraftFields
import com.aicfo.domain.engines.sms.SmsEngine
import com.aicfo.domain.engines.sms.SmsInput
import com.aicfo.domain.engines.sms.SmsRules
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import java.time.LocalDate

/**
 * Turns bank alerts into proposals the user decides on (issue 3.9; §18, §23, P-01, P-07, ARC-005).
 *
 * Why:  three things have to be joined that nothing else in the app joins: the consent ledger, the
 *       inbox, and the parser. This is the only class that sees all three, and the only one allowed
 *       to touch a DAO (ARC-005).
 *
 *       **The consent is checked here, before the reader is touched at all** — not in the ViewModel,
 *       not in the worker, and not in `:data:sms`. Putting the gate at the single chokepoint means
 *       "disabled means zero SMS access" is a property of the architecture rather than a rule every
 *       future caller has to remember: a new entry point added in a year gets the gate for free,
 *       because there is no way to reach the inbox that does not pass through here.
 *
 *       **It writes no transaction on its own** (P-07). [scan] reads, parses and records proposals;
 *       a ledger row appears only from [accept], which is a button the user pressed.
 * What: one scan, two decisions, one revocation, and a stream of what is waiting.
 * Result: the SMS half of FR-TXN-009's provenance, and the review screen's contents.
 * Changelog: 2026-08-07 — Created for issue 3.9.
 *
 * **No network of any kind is reachable from here** (P-01, P-04). The reader is a `ContentResolver`
 * query and the parser is pure Kotlin; a message cannot leave the device because there is no code
 * path that could carry it, which is also why the whole feature works in airplane mode.
 */
interface SmsRepository {
    /**
     * Whether the app currently holds **both** permissions this feature needs.
     * Why:    the two are genuinely independent and the UI must tell them apart: an in-app consent
     *         the user has not given is a switch to offer, while an OS permission they have not
     *         granted is a system dialog to request. Collapsing them into one boolean would make the
     *         screen ask for the wrong thing.
     * Result: the two flags, as a stream, so the screen reacts to a revocation from Settings.
     * Input:  none. Output: `Flow<SmsAccess>`.
     */
    fun observeAccess(): Flow<SmsAccess>

    /**
     * Observes the drafts waiting for a decision.
     * Result: emits on every change; empty is the ordinary state. Emits **empty without reading
     *         anything** when the consent is off, so a screen left open across a revocation clears.
     * Input:  none. Output: `Flow<List<SmsDraft>>`, newest alert first.
     */
    fun observePending(): Flow<List<SmsDraft>>

    /**
     * Reads the inbox from the stored cursor and records what the parser judges to be transactions.
     *
     * Why:    **returns `Ok(0)` without constructing a query when the consent is off**, rather than
     *         an error. A scan on a phone whose owner has not opted in is not a failure — it is the
     *         feature correctly doing nothing, and a caller (the worker, the screen) should not have
     *         to distinguish that from a broken provider.
     *
     *         The cursor advances only after the batch is processed, so a scan that fails halfway
     *         leaves the messages it did not handle to be picked up next time rather than skipped
     *         for ever.
     * Result: `Ok(n)` — how many *new* drafts were recorded, which is not the number of messages
     *         read: most messages are not transactions, and an alert already judged is ignored.
     *         `Err` when the inbox could not be read.
     * Input:  none. Output: `Result<Int, AppError>`.
     */
    suspend fun scan(): Result<Int, AppError>

    /**
     * Turns one draft into a transaction, as the user confirmed it (P-07, FR-TXN-009).
     * Why:    the draft carries no account, because a bank alert does not name one the app knows —
     *         it quotes four masked digits. So the screen supplies the [draft], built from what was
     *         read plus the account and any correction the user made, and this stamps the provenance.
     * Result: `Ok(transaction)` with `source = sms`, the draft marked accepted and linked to the row
     *         it became. `Err(AppError.NotFound)` for an unknown id; `Err` from the ledger write
     *         leaves the draft pending so the user can try again.
     * Input:  [draftId]; [draft] — as confirmed on the review screen. Output: `Result<Transaction, AppError>`.
     */
    suspend fun accept(
        draftId: String,
        draft: TransactionDraft,
    ): Result<Transaction, AppError>

    /**
     * Records that the user does not want this alert as a transaction.
     * Why:    the row is kept rather than deleted, which is the whole reason the drafts are a table:
     *         a dismissal that vanished would be re-proposed by the next scan, for ever.
     * Result: `Ok(Unit)`, or `Err(AppError.NotFound)` for an unknown id.
     * Input:  [draftId]. Output: `Result<Unit, AppError>`.
     */
    suspend fun dismiss(draftId: String): Result<Unit, AppError>

    /**
     * Finds transactions this alert may already be (FR-OCR-006's mirror).
     * Why:    the receipt the user photographed at the till and the alert the bank sent thirty
     *         seconds later are one purchase. The receipt path already offers a merge in the other
     *         direction; without this, the symmetric case creates a silent duplicate.
     * Result: `Ok(candidates)`, newest first; empty is the ordinary answer. Only `manual` and `ocr`
     *         rows are candidates — another alert is a different transaction, not this one twice.
     * Input:  [amount] — sign-insensitive; [bookedOn]. Output: `Result<List<Transaction>, AppError>`.
     */
    suspend fun findDuplicates(
        amount: Money,
        bookedOn: LocalDate,
    ): Result<List<Transaction>, AppError>

    /**
     * Erases every pending proposal and resets the scan cursor — what revoking the consent does.
     *
     * Why:    P-01 makes consent *revocable*, and revocable has to mean the inference goes, not that
     *         a switch flipped while the app keeps what it read. A pending draft is not the user's
     *         data — it is something this app concluded from messages it has just been told to stop
     *         reading — so it is **hard-deleted**, the only hard delete in this schema (ADR-0013).
     *
     *         **Accepted drafts survive**, because those became transactions the user deliberately
     *         saved, and deleting their provenance would leave rows in the ledger that could no
     *         longer say where they came from (AI-ARC-003). Dismissed ones survive too, so a later
     *         re-grant does not re-ask a question already answered.
     *
     *         **Across every profile**, because the consent is device-wide: a revocation scoped
     *         to the active profile would leave the demo's — or the real user's — drafts behind,
     *         depending on which happened to be showing when they turned it off.
     *
     *         The cursor resets so a re-grant starts from a clean inbox rather than inheriting a
     *         position from a decision that has since been withdrawn.
     * Result: `Ok(Unit)`. Input: none. Output: `Result<Unit, AppError>`.
     */
    suspend fun onConsentRevoked(): Result<Unit, AppError>

    companion object {
        /** Prefix for generated draft ids, matching every other repository here. */
        const val ID_PREFIX = "smsd"

        /**
         * The most messages one scan will read.
         *
         * A first scan runs against a phone that may hold a decade of messages; without a bound the
         * app would load all of them into memory at once. Bounded rather than paged because the
         * cursor makes the next scan resume exactly where this one stopped — a slow first catch-up
         * over a few scans is the right trade for never holding the user's whole inbox in RAM.
         */
        const val SCAN_BATCH = 500

        /** `sms_draft.status` — awaiting the user's decision. */
        const val STATUS_PENDING = "pending"

        /** `sms_draft.status` — became a transaction. */
        const val STATUS_ACCEPTED = "accepted"

        /** `sms_draft.status` — the user said no. */
        const val STATUS_DISMISSED = "dismissed"
    }
}

/**
 * The two permissions this feature needs, and whether each is held (issue 3.9; P-01).
 *
 * Why:    they are independent and the UI must tell them apart — see [SmsRepository.observeAccess].
 *         Both default to `false` here for the reason `ConsentState.NOT_GRANTED` exists: absence is
 *         never consent, so a value that failed to load must never read as permission.
 * Result: what the review screen branches on.
 * Changelog: 2026-08-07 — Created for issue 3.9.
 *
 * Input:  [consentGranted] — the in-app, per-feature opt-in (`ConsentFeature.SMS_PARSING`);
 *         [permissionGranted] — the OS `READ_SMS` grant.
 * Output: an immutable value.
 */
data class SmsAccess(
    val consentGranted: Boolean = false,
    val permissionGranted: Boolean = false,
) {
    /** Whether the inbox can actually be read. Both, always — either one alone is not permission. */
    val canScan: Boolean get() = consentGranted && permissionGranted
}

/**
 * One parsed alert awaiting a decision, as anything above the data layer sees it (issue 3.9).
 *
 * Why:    a domain-side value rather than `SmsDraftEntity`, because ARC-005 says a ViewModel never
 *         sees a Room type. [isLowConfidence] is resolved here rather than exposing the raw
 *         threshold, so the screen never has to know what the rulebook's floor is — moving that
 *         floor changes what is flagged without touching a line of UI.
 * Result: the element of [SmsRepository.observePending].
 * Changelog: 2026-08-07 — Created for issue 3.9.
 *
 * Input:  [id] — the draft row; [sender] — the DLT header, shown so the user can recognise it;
 *         [amount] — a positive magnitude in paise (MNY-001); [direction]; [bookedOn];
 *         [counterparty] and [accountTail] — best-effort, `null` when the alert named none;
 *         [confidenceBps] (MNY-002); [isLowConfidence] — whether the screen must flag it.
 * Output: an immutable value.
 */
data class SmsDraft(
    val id: String,
    val sender: String,
    val amount: Money,
    val direction: SmsDirection,
    val bookedOn: LocalDate,
    val counterparty: String?,
    val accountTail: String?,
    val confidenceBps: Int,
    val isLowConfidence: Boolean,
)

/**
 * The Room-backed [SmsRepository].
 * Why:    ARC-003 — one public interface, an internal implementation, assembled by the DI graph.
 *
 *         **It delegates the transaction write to [TransactionRepository] rather than repeating
 *         it**, the same choice `RoomReceiptRepository` makes: that class already owns account
 *         validation, the date/time stamping FR-TXN-010 needs and the sign/type invariant.
 * Result: the implementation injected into the review ViewModel and the scan worker.
 * Changelog: 2026-08-07 — Created for issue 3.9.
 *
 * Input:  [database] — the draft rows and the duplicate query; [transactions] — writes the ledger
 *         row; [reader] — the inbox (`:data:sms`); [engine] — the parser (P-03); [consents] — the
 *         gate; [settings] — the scan cursor; [clock] — every stamped instant and the profile-zone
 *         day, never a wall-clock read (TIM-001); [ids]; [dispatchers]; [activeProfileId] — which
 *         profile is scanned into, so the demo gets its own.
 * Output: a working repository.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@Suppress("LongParameterList") // Six collaborators and the three every repository here takes; see
// the interface — this class exists precisely to be the one place they meet (ARC-005).
internal class RoomSmsRepository(
    private val database: CfoDatabase,
    private val transactions: TransactionRepository,
    private val reader: SmsInboxReader,
    private val engine: SmsEngine,
    private val consents: ConsentStore,
    private val settings: SettingsStore,
    private val clock: Clock,
    private val ids: IdGenerator,
    private val dispatchers: DispatcherProvider,
    private val activeProfileId: Flow<String>,
) : SmsRepository {
    override fun observeAccess(): Flow<SmsAccess> =
        consents.observe(ConsentFeature.SMS_PARSING)
            .map { consent ->
                SmsAccess(
                    // An unreadable consent store reads as not granted — never as permission.
                    consentGranted = (consent as? Ok)?.value?.granted == true,
                    permissionGranted = reader.canRead(),
                )
            }
            .flowOn(dispatchers.io)

    override fun observePending(): Flow<List<SmsDraft>> =
        observeAccess()
            .flatMapLatest { access ->
                // Emitting nothing while the consent is off, rather than filtering the query's
                // result, means a screen left open across a revocation empties without a reload —
                // and means a revoked user's drafts are not read from disk at all.
                if (!access.consentGranted) {
                    kotlinx.coroutines.flow.flowOf(emptyList())
                } else {
                    activeProfileId.flatMapLatest { profileId ->
                        database.smsDraftDao().observePending(profileId).map { rows -> rows.map { it.toDraft() } }
                    }
                }
            }
            .flowOn(dispatchers.io)

    override suspend fun scan(): Result<Int, AppError> =
        withContext(dispatchers.io) {
            // The gate, before anything touches the inbox. Not an error: a phone whose owner has
            // not opted in is the feature correctly doing nothing.
            if (!observeAccess().first().canScan) return@withContext Ok(0)
            val cursor = settings.observe().first().let { (it as? Ok)?.value?.smsScanCursorId ?: 0L }
            reader.readSince(cursor, SmsRepository.SCAN_BATCH)
                .flatMap { messages -> runCatchingToResult { record(messages) } }
        }

    override suspend fun accept(
        draftId: String,
        draft: TransactionDraft,
    ): Result<Transaction, AppError> =
        withContext(dispatchers.io) {
            val row = database.smsDraftDao().findById(draftId) ?: return@withContext Err(AppError.NotFound)
            // FR-TXN-009: the provenance is stamped here, not taken from the screen. A caller that
            // passed `manual` would be recording a claim rather than a fact.
            transactions.create(draft.copy(source = TransactionSource.SMS))
                .flatMap { created ->
                    // Stamped only after the ledger write succeeds — a draft marked accepted with no
                    // transaction behind it would vanish from the review screen taking the alert
                    // with it, and the user would never learn the save had failed.
                    runCatchingToResult {
                        database.smsDraftDao().setStatus(
                            id = row.id,
                            status = SmsRepository.STATUS_ACCEPTED,
                            transactionId = created.id,
                            updatedAtUtcMillis = clock.nowUtcMillis(),
                        )
                    }.flatMap { Ok(created) }
                }
        }

    override suspend fun dismiss(draftId: String): Result<Unit, AppError> =
        withContext(dispatchers.io) {
            runCatchingToResult {
                database.smsDraftDao().setStatus(
                    id = draftId,
                    status = SmsRepository.STATUS_DISMISSED,
                    transactionId = null,
                    updatedAtUtcMillis = clock.nowUtcMillis(),
                )
            }.flatMap { changed -> if (changed == 0) Err(AppError.NotFound) else Ok(Unit) }
        }

    override suspend fun findDuplicates(
        amount: Money,
        bookedOn: LocalDate,
    ): Result<List<Transaction>, AppError> =
        withContext(dispatchers.io) {
            runCatchingToResult {
                val rules = SmsRules()
                val magnitude = if (amount.minor < 0L) -amount.minor else amount.minor
                // The band is computed here in integer paise and handed to SQL as two bounds
                // (MNY-001) — the reasoning `RoomReceiptRepository.duplicateRows` states in full.
                val slack = Math.multiplyExact(magnitude, rules.duplicateAmountTolerancePct.toLong()) / PCT_TOTAL
                database.transactionDao()
                    .findAlertDuplicateCandidates(
                        profileId = activeProfileId.first(),
                        minMinor = magnitude - slack,
                        maxMinor = magnitude + slack,
                        fromIsoDate = bookedOn.minusDays(rules.duplicateDateToleranceDays).toString(),
                        toIsoDate = bookedOn.plusDays(rules.duplicateDateToleranceDays).toString(),
                    )
                    .mapNotNull { it.toTransaction() }
            }
        }

    override suspend fun onConsentRevoked(): Result<Unit, AppError> =
        withContext(dispatchers.io) {
            // Every profile, not the active one. The consent is device-wide, so a revocation scoped
            // to whichever profile happened to be showing would leave the other's drafts on disk —
            // a user who revoked while browsing the demo would keep every inference drawn from their
            // real inbox. Found by the issue 3.9 security review; see the DAO for the full argument.
            runCatchingToResult { database.smsDraftDao().deleteAllPending() }
                .flatMap { settings.setSmsScanCursor(0L) }
        }

    /**
     * Parses a batch and records the drafts, then advances the cursor.
     * Why:    **the cursor moves last, and only to the id of the last message actually processed.**
     *         A failure part-way through therefore leaves the rest to the next scan instead of
     *         skipping them for ever — the one ordering mistake in this feature that would lose a
     *         user's transactions silently.
     *
     *         A message the parser refuses still advances the cursor, because "not a transaction" is
     *         a judgement, not a deferral: re-reading it next time would touch the user's messages
     *         again to reach the same conclusion.
     * Result: how many new drafts were recorded — not how many messages were read.
     * Input:  [messages] — oldest first, as the reader returns them.
     * Output: [Int]. Throws only what `runCatchingToResult` classifies at the call site.
     * Changelog: 2026-08-07 — Created for issue 3.9.
     */
    private suspend fun record(messages: List<com.aicfo.core.model.SmsMessage>): Int {
        if (messages.isEmpty()) return 0
        val profileId = activeProfileId.first()
        val zone = clock.zone()
        var recorded = 0
        messages.forEach { message ->
            val receivedOn = java.time.Instant.ofEpochMilli(message.receivedAtUtcMillis).atZone(zone).toLocalDate()
            val parsed =
                engine.parse(
                    SmsInput(
                        message = message,
                        receivedOnIsoDate = receivedOn.toString(),
                        nowUtcMillis = clock.nowUtcMillis(),
                    ),
                )
            val fields = (parsed as? Ok)?.value
            if (fields != null && insert(profileId, message.id, message.sender, fields)) recorded++
        }
        settings.setSmsScanCursor(messages.last().id)
        return recorded
    }

    /**
     * Writes one draft, leaving an already-judged alert alone.
     * Why:    `insertIfNew` is IGNORE-on-conflict against the unique `(profile_id, sms_id)` index,
     *         so an overlapping re-scan cannot overwrite a dismissal with a fresh proposal. The
     *         return value is what makes the scan's count honest: a re-scan that found nothing new
     *         reports zero rather than the number of messages it re-read.
     * Result: `true` when a new row was inserted.
     * Input:  [profileId]; [smsId]; [sender]; [fields] — what the parser concluded.
     * Output: `Boolean`.
     * Changelog: 2026-08-07 — Created for issue 3.9.
     */
    private suspend fun insert(
        profileId: String,
        smsId: Long,
        sender: String,
        fields: SmsDraftFields,
    ): Boolean {
        val now = clock.nowUtcMillis()
        val inserted =
            database.smsDraftDao().insertIfNew(
                SmsDraftEntity(
                    id = ids.newId(SmsRepository.ID_PREFIX),
                    profileId = profileId,
                    smsId = smsId,
                    sender = sender,
                    amountMinor = fields.amount.minor,
                    direction = fields.direction.stored(),
                    bookedOn = fields.bookedOn,
                    counterparty = fields.counterparty,
                    accountTail = fields.accountTail,
                    confidenceBps = fields.provenance.confidenceBps ?: 0,
                    // AI-ARC-006: stored on the row, because a draft can sit unreviewed across an
                    // app update and "why did it say this?" must still have an answer.
                    engineVersion = fields.provenance.engineVersion,
                    ruleVersion = SmsRules.TRANSACTION_PARSE.ruleVersion,
                    status = SmsRepository.STATUS_PENDING,
                    createdAtUtcMillis = now,
                    updatedAtUtcMillis = now,
                ),
            )
        return inserted != IGNORED
    }

    /**
     * Maps a row to the value callers see (ARC-005).
     * Result: the [SmsDraft]. Input: the receiver. Output: [SmsDraft].
     * Changelog: 2026-08-07 — Created for issue 3.9.
     */
    private fun SmsDraftEntity.toDraft(): SmsDraft =
        SmsDraft(
            id = id,
            sender = sender,
            amount = Money(amountMinor),
            direction = if (direction == DIRECTION_CREDIT) SmsDirection.CREDIT else SmsDirection.DEBIT,
            bookedOn = LocalDate.parse(bookedOn),
            counterparty = counterparty,
            accountTail = accountTail,
            confidenceBps = confidenceBps,
            // Resolved here so the screen never has to know the rulebook's floor — moving it
            // changes what is flagged without touching a line of UI.
            isLowConfidence = confidenceBps < SmsRules().lowConfidenceBps,
        )

    private companion object {
        /** The whole of an amount, as the divisor that turns a percent band into paise. */
        const val PCT_TOTAL = 100L

        /** What `@Insert(OnConflictStrategy.IGNORE)` returns when the row was already there. */
        const val IGNORED = -1L

        /** `sms_draft.direction` for money arriving. */
        const val DIRECTION_CREDIT = "credit"
    }
}

/**
 * The value stored in `sms_draft.direction`.
 * Why:    a code from a closed set, never `name.lowercase()` — the rule every other stored enum in
 *         this schema follows, so renaming a constant cannot silently orphan existing rows.
 * Result: `debit` or `credit`. Input: the receiver. Output: [String].
 * Changelog: 2026-08-07 — Created for issue 3.9.
 */
private fun SmsDirection.stored(): String =
    when (this) {
        SmsDirection.DEBIT -> "debit"
        SmsDirection.CREDIT -> "credit"
    }
