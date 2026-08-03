package com.aicfo.data.repository

import androidx.room.withTransaction
import com.aicfo.core.common.AppError
import com.aicfo.core.common.Clock
import com.aicfo.core.common.DispatcherProvider
import com.aicfo.core.common.Err
import com.aicfo.core.common.IdGenerator
import com.aicfo.core.common.Result
import com.aicfo.core.common.runCatchingToResult
import com.aicfo.core.database.CfoDatabase
import com.aicfo.core.database.dao.TransactionWithSplits
import com.aicfo.core.database.entity.AccountEntity
import com.aicfo.core.database.entity.CategoryEntity
import com.aicfo.core.database.entity.TransactionEntity
import com.aicfo.core.database.entity.TransactionSplitEntity
import com.aicfo.core.model.Category
import com.aicfo.core.model.Money
import com.aicfo.core.model.Transaction
import com.aicfo.core.model.TransactionSource
import com.aicfo.core.model.TransactionType
import com.aicfo.core.model.Transfer
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.LocalTime

/**
 * Creates and reads transactions (issue 3.1; FR-TXN-002, FR-TXN-001, FR-TXN-009).
 *
 * Why:  the `transactions` table has existed since issue 1.6 and, until this class, **nothing the
 *       user could reach wrote to it** — only the demo dataset and issue 2.7's reconciliation
 *       adjustment did. That left FR-TXN-002 (add-transaction in one tap, completable in ≤ 3)
 *       unbuildable, every account balance derived from rows the user could not create, and the
 *       whole of Epic 3 blocked. This is the class that opens the capture path.
 * What: two observations and one write.
 * Result: a transaction is the user's to create, and every balance that derives from one moves.
 * Changelog: 2026-08-02 — Created for issue 3.1.
 *
 * **The only class allowed to touch [com.aicfo.core.database.dao.TransactionDao] (ARC-005).** The
 * transactions ViewModels see [Transaction] — a `:core:model` type — and never a Room entity.
 *
 * **Nothing here writes a balance (DB-001, ADR-0007).** An account's balance is
 * `opening_balance + SUM(live transactions)`, derived on every read, so inserting the row *is* the
 * balance update. A repository that also adjusted `account.current_balance_minor` would be stating
 * a figure that is already derivable, which is precisely what DB-001 forbids.
 *
 * **Scope is create-only, deliberately.** Editing, deleting, transfers (issue 3.2), splits (3.3),
 * future dating (3.4), and search/filters/bulk edit (3.6) each have their own issue. This class grows
 * a method when one of them lands, not before.
 */
interface TransactionRepository {
    /**
     * Observes the active profile's recent transactions, newest first.
     *
     * Why:    the caller does not choose the profile, for the same reason
     *         [AccountRepository.observeAccounts] does not — demo mode puts sample data under a
     *         second profile id, and "which profile?" is a question with one right answer at any
     *         moment that several screens would each have to get right.
     *
     *         **A rolling window, not everything.** [RECENT_WINDOW_DAYS] days back from today, in
     *         the profile zone. Issue 3.6 owns the full list with search, filters and paging; a
     *         screen that loaded every row a user has ever entered would be that issue built badly
     *         and early. The window is computed from the injected `Clock` (TIM-001), so it rolls
     *         over at the profile's midnight rather than UTC's.
     * Result: emits on every change; soft-deleted rows excluded. An empty list is a real state the
     *         screen must render as an empty state.
     * Input:  none — the active profile. Output: `Flow<List<Transaction>>`.
     *
     * **The upper bound is today, and since issue 3.4 that is load-bearing.** Until future dating
     * existed no row could be booked past today, so the bound was true by accident; now it is what
     * keeps a scheduled payment out of the actuals half of the list and out of every day total
     * (FR-TXN-010). Scheduled rows are read by [observeUpcoming] instead.
     */
    fun observeRecent(): Flow<List<Transaction>>

    /**
     * Observes the active profile's **future-dated** transactions, soonest first (issue 3.4).
     *
     * Why:    FR-TXN-010's second clause — future-dated rows are "excluded from actuals but included
     *         in forecasts". This is the read that makes the second half possible: the cash-flow
     *         forecast (Epic 6) and FR-HOME-001's "upcoming obligations (next 14 days)" card both
     *         need the obligations the user has already told the app about, and neither can find
     *         them in [observeRecent], which stops at today by design.
     *
     *         **A separate flow rather than a widened window**, so the two halves cannot be summed
     *         by accident. A caller that wants actuals gets actuals; nothing has to remember to
     *         filter. The bounds are tomorrow to today + [UPCOMING_WINDOW_DAYS], both computed from
     *         the injected `Clock`, so the split moves at the profile's midnight (TIM-001) — a row
     *         leaves this flow and enters [observeRecent] on its own date, with no write.
     * Result: emits on every change, soonest date first; soft-deleted rows excluded. Empty is the
     *         normal state — most users schedule nothing.
     * Input:  none — the active profile. Output: `Flow<List<Transaction>>`.
     */
    fun observeUpcoming(): Flow<List<Transaction>>

    /**
     * Stamps every scheduled row whose day has arrived, and reports how many (issue 3.4).
     *
     * Why:    `ScheduledTransactionWorker` calls this once a day. FR-TXN-010 asks for future-dated
     *         transactions to post when their date arrives; this is the app **recording** that,
     *         idempotently — a second call on the same day stamps nothing and returns `0`.
     *
     *         **It moves no money, and nothing should ever make it.** Balances derive from
     *         `booked_on_iso_date`, so a row counts from its own date whether or not this has run;
     *         if posting were gated on the stamp instead, a device that was switched off would show
     *         its user the wrong balance. See `docs/adr/0010-future-dated-posting.md`.
     * Result: `Ok(n)` — the rows stamped, **`0` being a success** and not a failure. `Err(Storage)`
     *         when the write fails, which the worker retries.
     * Input:  none — the active profile. Output: `Result<Int, AppError>`.
     */
    suspend fun postDueTransactions(): Result<Int, AppError>

    /**
     * Observes the categories the active profile can pick from (FR-TXN-002).
     *
     * Why:    FR-TXN-002's three taps are "amount → category suggestion → save", so the add screen
     *         has to be able to offer categories. It reads them here rather than owning them: the
     *         taxonomy editor is issue 4.1 and auto-categorisation is 4.2, both of which sit *after*
     *         this issue in the dependency order (4.1 → 3.5 → 3.1).
     * Result: **empty for a real profile today** — only demo mode seeds categories until issue 4.1 —
     *         and twelve rows inside the demo. An empty list is not an error; the screen hides the
     *         chip row and saves `categoryId = null`, which the column has always allowed.
     * Input:  none — the active profile. Output: `Flow<List<Category>>`.
     */
    fun observeCategories(): Flow<List<Category>>

    /**
     * Records a transaction under the active profile (FR-TXN-002, FR-TXN-009).
     *
     * Why:    the id is generated from the injected [IdGenerator] rather than `UUID.randomUUID()`,
     *         so the write stays reproducible in a test (P-08), and both timestamps come from the
     *         injected [Clock] rather than the wall clock (TIM-001). The account is **verified to
     *         exist and be live before anything is written** — an orphaned transaction would count
     *         towards no balance and appear in no account's history, and SQLite would happily store
     *         one.
     * Result: `Ok(transaction)` with its assigned id and the account's currency; the account's
     *         derived balance moves by exactly [TransactionDraft.amount] with no further write.
     *         `Err(Validation)` with **nothing written** when the draft is not usable,
     *         `Err(NotFound)` when the account id names nothing live, or `Err(Storage)`.
     * Input:  [draft] — what the user entered. Output: `Result<Transaction, AppError>`.
     */
    suspend fun create(draft: TransactionDraft): Result<Transaction, AppError>

    /**
     * Moves money between two of the user's own accounts (issue 3.2; FR-TXN-003, DB-004).
     *
     * Why:    FR-TXN-003 is a MUST: a transfer is "a single logical record affecting two accounts
     *         atomically". It is stored as **two rows** — that is what keeps each account's balance a
     *         plain `SUM` over its own transactions (DB-001) — but they are written inside one
     *         database transaction and share one `transfer_id`, so no reader can ever see one leg
     *         without the other. A half-written transfer would invent or destroy money.
     *
     *         **Both legs share one booked day and one instant.** Splitting them across days would
     *         put the pair under two different headers in the list, and would make net worth wrong
     *         for the days in between once issue 3.4 allows future dating (its as-of query bounds on
     *         `booked_on_iso_date`).
     *
     *         **Neither leg carries a category.** A transfer is not spending; letting one into a
     *         budget envelope would count the user's own savings against their food budget.
     * Result: `Ok(transfer)` with the shared id and a **positive** amount; both accounts' derived
     *         balances move by exactly that amount, in opposite directions, with no balance write.
     *         `Err(Validation)` with **nothing written** for a zero or negative amount, or when both
     *         sides name the same account, or when the two accounts hold different currencies.
     *         `Err(NotFound)` when either account id names nothing live, or `Err(Storage)`.
     * Input:  [draft] — what the user entered. Output: `Result<Transfer, AppError>`.
     */
    suspend fun createTransfer(draft: TransferDraft): Result<Transfer, AppError>

    /**
     * Records one transaction attributed across N category lines (issue 3.3; FR-TXN-004, DB-004).
     *
     * Why:    FR-TXN-004 is a MUST: "N lines with independent categories; lines MUST sum exactly to
     *         the parent amount (validated, no rounding drift)". **The validation is the feature** —
     *         lines that do not sum are refused outright rather than silently adjusted, because an
     *         app that quietly moves a user's figures to make them add up is worse than one that
     *         says no.
     *
     *         **The parent is an ordinary transaction and the lines move no money.** One row holds
     *         the amount and every balance sums it exactly as before (DB-001, ADR-0007); the lines
     *         only say what it was *about*. That is why this needs no balance code at all, and why
     *         splits live in their own table — see `docs/adr/0009-splits-as-a-child-table.md`.
     * Result: `Ok(transaction)` with its lines attached; the account's derived balance moves by
     *         [SplitDraft.amount] **once**. `Err(Validation)` with **nothing written** when there are
     *         fewer than two lines, when a line is zero or signed against the parent, or when the
     *         lines do not sum to the parent. `Err(NotFound)` when the account names nothing live,
     *         or `Err(Storage)`.
     * Input:  [draft] — what the user entered. Output: `Result<Transaction, AppError>`.
     */
    suspend fun createSplit(draft: SplitDraft): Result<Transaction, AppError>

    /**
     * Soft-deletes a transaction, its split lines, **and its sibling leg when it is half of a
     * transfer** (FR-TXN-003, FR-TXN-004).
     *
     * Why:    FR-TXN-003's second clause is "deleting one side deletes both". Deleting one leg of a
     *         transfer and leaving the other would leave money that came from nowhere sitting in the
     *         destination account, and no screen would show why. The caller does not say which case
     *         it is — it passes the row the user tapped, and this decides — because a UI that had to
     *         know would be a UI that can get it wrong.
     *
     *         **Split lines go with their parent** (issue 3.3), in the same database transaction. A
     *         line whose parent is gone attributes an amount that no longer exists, and every read
     *         downstream would have to filter it out for ever.
     *
     *         Soft, per DB-002: the rows stay as tombstones and simply leave every read and every
     *         balance.
     * Result: `Ok(Unit)`; the row, or both legs, disappear from every read and the affected balances
     *         revert. `Err(NotFound)` when the id names nothing live — including a second delete of
     *         something already deleted, which must not report success twice.
     * Input:  [transactionId] — the row the user acted on, either leg of a transfer.
     * Output: `Result<Unit, AppError>`.
     */
    suspend fun delete(transactionId: String): Result<Unit, AppError>

    companion object {
        /**
         * The [IdGenerator] prefix for transaction ids, so a database dump reads as itself.
         *
         * Moved here from `AccountRepository.TRANSACTION_ID_PREFIX`, whose doc comment said it lived
         * there only "because none exists yet — Epic 3 owns that ... It moves there unchanged when
         * that repository lands." **The literal is unchanged**, so every id issue 2.7 has already
         * written still reads the same way.
         */
        const val ID_PREFIX = "txn"

        /**
         * How far back [observeRecent] looks, in profile-zone days.
         *
         * Not a financial threshold, so §29's data-not-code rule does not reach it — it is the size
         * of a list, and the list itself is temporary scaffolding until issue 3.6 builds the real
         * one with filters and paging.
         */
        const val RECENT_WINDOW_DAYS = 30L

        /**
         * How far ahead [observeUpcoming] looks, in profile-zone days (issue 3.4).
         *
         * Not a financial threshold either, so §29's data-not-code rule does not reach it. A quarter
         * is chosen because it comfortably covers the two consumers this read exists for —
         * FR-HOME-001's fourteen-day obligations card and a monthly cash-flow forecast — while still
         * bounding the query, so a user who schedules something for 2031 does not make every list
         * read scan to it. A row past the window is not lost: it simply arrives in the window as its
         * date approaches.
         */
        const val UPCOMING_WINDOW_DAYS = 90L

        /**
         * The [IdGenerator] prefix for the id a transfer's two legs share (issue 3.2).
         *
         * Distinct from [ID_PREFIX] so a database dump reads as itself: `tfr:4` is plainly not a
         * transaction id, which matters when the only thing tying two rows together is this value.
         */
        const val TRANSFER_ID_PREFIX = "tfr"

        /**
         * The [IdGenerator] prefix for split-line ids (issue 3.3).
         *
         * Distinct from [ID_PREFIX] so a database dump reads as itself: `spl:4` is plainly a line,
         * not a transaction, which matters when the two tables are read side by side.
         */
        const val SPLIT_ID_PREFIX = "spl"

        /**
         * The fewest lines a split may have (FR-TXN-004).
         *
         * One line is not a split — it is the transaction it already was, with an extra row saying
         * so. Refusing it keeps "is this split?" a question with one answer.
         */
        const val MIN_SPLIT_LINES = 2
    }
}

/**
 * What the user entered about a transfer — everything they get to decide (issue 3.2; FR-TXN-003).
 *
 * Why:    separate from [TransactionDraft] because a transfer is not one movement with an extra
 *         field: it names **two** accounts and it has no category, no merchant and no direction to
 *         choose. Modelling it as its own type means the compiler refuses a transfer with one
 *         account or a category, rather than a reviewer having to notice.
 * Result: the argument to [TransactionRepository.createTransfer].
 * Changelog: 2026-08-02 — Created for issue 3.2.
 *
 * **[amount] is positive** — unlike [TransactionDraft.amount], which is signed. It is the size of the
 * movement; the signs belong to the two legs, and the repository applies them. Asking the caller for
 * a signed amount here would raise the question "signed which way?", whose only honest answer is
 * "both".
 *
 * Input:  [fromAccountId] — the account money leaves, required; [toAccountId] — the account it
 *         arrives in, required and different; [amount] — MNY-001 paise, strictly positive;
 *         [note] — optional free text, held on **both** legs so either one explains itself;
 *         [bookedOn] — the day it is booked on, today or later, `null` meaning today (issue 3.4).
 * Output: an immutable value.
 */
data class TransferDraft(
    val fromAccountId: String,
    val toAccountId: String,
    val amount: Money,
    val note: String? = null,
    /**
     * The day this transfer is booked on (issue 3.4; FR-TXN-010). `null` means today.
     *
     * **Both legs take it**, resolved once by [Clock.stampsFor] — a scheduled transfer whose legs
     * landed on different days would leave money missing from one account for the days in between,
     * which is exactly what `writeTransferLegs` computing the day twice would have caused.
     */
    val bookedOn: LocalDate? = null,
    /**
     * The time of day both legs are stamped with (FR-TXN-001's "date-time"). `null` keeps the
     * default — now for today, the start of the day for a future date.
     *
     * Shared, like [bookedOn]: a transfer is one movement, so its two legs occur at one instant.
     */
    val bookedAt: LocalTime? = null,
)

/**
 * What the user entered about a transaction — everything they get to decide (issue 3.1).
 *
 * Why:    separate from [Transaction] because the two are not the same set of facts. A [Transaction]
 *         has an id, an instant, a booked day and a source; a draft has none of those, because the
 *         user does not choose an id, and the date and provenance are the app's to stamp (TIM-001,
 *         FR-TXN-009). Modelling the difference means the compiler refuses the mistake rather than a
 *         reviewer having to catch it — the same argument [AccountDraft] makes.
 * Result: the argument to [TransactionRepository.create].
 * Changelog: 2026-08-02 — Created for issue 3.1.
 *
 * **[amount] is signed and the sign is the direction** (MNY-001): negative for money leaving the
 * account, positive for money arriving. There is no `isExpense` flag here — the ViewModel resolves
 * its expense/income toggle into a sign before calling, so there is exactly one representation of
 * direction below the UI and no way for two of them to disagree.
 *
 * Input:  [accountId] — which account moved, required; [amount] — MNY-001 paise, signed, non-zero;
 *         [categoryId] — optional, and `null` for every real profile until issue 4.1; [merchant] —
 *         optional free text; [note] — optional free text; [bookedOn] — the day it is booked on,
 *         today or later, `null` meaning today (issue 3.4).
 * Output: an immutable value.
 */
data class TransactionDraft(
    val accountId: String,
    val amount: Money,
    val categoryId: String? = null,
    val merchant: String? = null,
    val note: String? = null,
    /**
     * The day this transaction is booked on (issue 3.4; FR-TXN-010). `null` means today.
     *
     * **A date, not an instant** (TIM-002): "next Tuesday" is a calendar answer in the profile zone,
     * and a midnight timestamp would shift under a zone change. `null` rather than defaulting to
     * `LocalDate.now()` in the constructor, because a draft may not read a clock at all — the
     * repository's injected one resolves it (TIM-001).
     */
    val bookedOn: LocalDate? = null,
    /**
     * The time of day this occurred (FR-TXN-001's "date-time"). `null` means "do not care", which
     * the repository resolves to now for today and to the start of the day for a future date.
     *
     * **Separate from [bookedOn], not folded into one `LocalDateTime`.** The two answer different
     * questions and only one of them decides money: [bookedOn] is the profile-zone day every
     * balance and budget bounds on (TIM-002), while this only orders rows within that day. Merging
     * them would invite a call site to derive the day from the instant, which is the bug TIM-002
     * exists to prevent.
     */
    val bookedAt: LocalTime? = null,
)

/**
 * The Room-backed [TransactionRepository].
 * Why:    ARC-003 — one public interface, an internal implementation, assembled by the DI graph.
 * Result: the implementation injected into the transactions ViewModels.
 * Changelog: 2026-08-02 — Created for issue 3.1.
 *
 * Input:  [database] — taken whole rather than as DAOs, matching every repository beside it, so a
 *         later multi-table write here needs no constructor change; [clock] — stamps the instant and
 *         the booked day, never the wall clock (TIM-001, TIM-002); [ids] — mints transaction ids
 *         (P-08); [dispatchers] — database I/O off the caller's thread; [activeProfileId] — which
 *         profile reads and writes resolve to, supplied by `DemoModeRepository`, so this class knows
 *         only "which profile is current" and never that demo mode exists.
 * Output: a working repository.
 */
@OptIn(ExperimentalCoroutinesApi::class)
internal class RoomTransactionRepository(
    private val database: CfoDatabase,
    private val clock: Clock,
    private val ids: IdGenerator,
    private val dispatchers: DispatcherProvider,
    private val activeProfileId: Flow<String>,
) : TransactionRepository {
    override fun observeRecent(): Flow<List<Transaction>> =
        // flatMapLatest, not map: entering or leaving the demo must *switch* which query is being
        // observed, cancelling the previous one. A `map` would leave the old profile's Flow running
        // and the screen showing the transactions it was already showing.
        activeProfileId.flatMapLatest { profileId ->
            val today = clock.today()
            // One query, not two combined: Room's `@Relation` fetches each transaction's lines
            // inside the same database transaction and invalidates on either table (issue 3.3).
            database.transactionDao()
                .observeBookedBetweenWithSplits(
                    profileId = profileId,
                    fromIsoDate = today.minusDays(TransactionRepository.RECENT_WINDOW_DAYS).toString(),
                    toIsoDate = today.toString(),
                )
                // mapNotNull, not map: a row whose `source` this build does not recognise is dropped
                // rather than thrown on, so an old build reading a newer database shows fewer rows
                // instead of crashing the list.
                .map { rows -> rows.mapNotNull { it.toTransaction() } }
                .flowOn(dispatchers.io)
        }

    override fun observeUpcoming(): Flow<List<Transaction>> =
        activeProfileId.flatMapLatest { profileId ->
            val today = clock.today()
            database.transactionDao()
                .observeBookedBetweenWithSplits(
                    profileId = profileId,
                    // Tomorrow, not today: today's rows are actuals and belong to `observeRecent`.
                    // The two windows abut exactly, so no row can appear in both or in neither.
                    fromIsoDate = today.plusDays(1).toString(),
                    toIsoDate = today.plusDays(TransactionRepository.UPCOMING_WINDOW_DAYS).toString(),
                )
                // Reversed rather than a second DAO query: the shared one orders newest-instant
                // first, which is the right end for history and the wrong one for a schedule — the
                // next thing due should read first. Over a window bounded at 90 days this is a
                // trivial cost, and it keeps one `@Transaction` query serving both reads.
                .map { rows -> rows.mapNotNull { it.toTransaction() }.asReversed() }
                .flowOn(dispatchers.io)
        }

    override suspend fun postDueTransactions(): Result<Int, AppError> =
        withContext(dispatchers.io) {
            runCatchingToResult {
                // One statement, so there is no window in which a row is due, unstamped and being
                // read. `first()` rather than collecting: this is a one-shot job, not an observer.
                database.transactionDao().postDue(
                    profileId = activeProfileId.first(),
                    todayIsoDate = clock.today().toString(),
                    nowUtcMillis = clock.nowUtcMillis(),
                )
            }
        }

    override fun observeCategories(): Flow<List<Category>> =
        activeProfileId.flatMapLatest { profileId ->
            database.categoryDao().observeForProfile(profileId)
                .map { rows -> rows.map { it.toCategory() } }
                .flowOn(dispatchers.io)
        }

    override suspend fun create(draft: TransactionDraft): Result<Transaction, AppError> {
        // Validated before `withContext`, so a rejected draft costs no thread switch and — more to
        // the point — cannot have written anything by the time it is rejected.
        val validated = draft.validated() ?: return Err(AppError.Validation(draft.invalidField()))
        // Issue 3.4: the booked day and the three stamps it implies, resolved once (FR-TXN-010).
        // `null` means the date is in the past, which this issue does not support — see `stampsFor`.
        val stamps =
            clock.stampsFor(validated.bookedOn, validated.bookedAt)
                ?: return Err(AppError.Validation("bookedOn"))
        // Today, not the booked day: the account lookup only proves the account is live, and its
        // balance is read as at now (issue 3.4 bounded `findWithBalance` by date).
        val today = clock.today().toString()
        return withContext(dispatchers.io) {
            runCatchingToResult {
                // The account is read for three things at once: proof it exists and is live, the
                // profile the row belongs to, and the currency. Reading it is not an optimisation —
                // without it SQLite would happily store a transaction against an id that names
                // nothing, and it would then count towards no balance and show in no history.
                val account =
                    database.accountDao().findWithBalance(validated.accountId, today)?.account
                        ?: return@runCatchingToResult null
                val now = stamps.nowUtcMillis
                val entity =
                    TransactionEntity(
                        id = ids.newId(TransactionRepository.ID_PREFIX),
                        // The **account's** profile, not the active one — the same choice
                        // `writeAdjustment` makes in `AccountRepository` (ADR-0006): a row must land
                        // where the demo wipe can reach it.
                        profileId = account.profileId,
                        accountId = account.id,
                        amountMinor = validated.amount.minor,
                        currencyCode = account.currencyCode,
                        occurredAtUtcMillis = stamps.occurredAtUtcMillis,
                        bookedOnIsoDate = stamps.bookedOnIsoDate,
                        categoryId = validated.categoryId,
                        merchant = validated.merchant,
                        note = validated.note,
                        // FR-TXN-009. This flow is the one the user typed by hand; OCR (3.8), SMS
                        // (3.9) and import (5.4) stamp their own.
                        source = TransactionSource.MANUAL.storedValue,
                        // Derived from the sign, never taken from the caller — the whole of the
                        // type/sign invariant is this one expression plus the transfer legs below.
                        type = validated.amount.directionType().storedValue,
                        // Null for a future-dated row: `ScheduledTransactionWorker` stamps it when
                        // the day arrives (FR-TXN-010). It does not decide any balance.
                        postedAtUtcMillis = stamps.postedAtUtcMillis,
                        createdAtUtcMillis = now,
                        updatedAtUtcMillis = now,
                    )
                database.transactionDao().upsert(entity)
                // No balance write: `account.current_balance_minor` is a cache nothing reads, and the
                // row just inserted has already moved every derived balance (DB-001, ADR-0007).
                entity.toTransaction()
            }.flatMapPresent()
        }
    }

    override suspend fun createTransfer(draft: TransferDraft): Result<Transfer, AppError> {
        val validated = draft.validated() ?: return Err(AppError.Validation(draft.invalidField()))
        // Issue 3.4: resolved once, before the write, so **both legs** are stamped from one value.
        val stamps =
            clock.stampsFor(validated.bookedOn, validated.bookedAt)
                ?: return Err(AppError.Validation("bookedOn"))
        // Today, not the booked day: the account lookup only proves the account is live, and its
        // balance is read as at now (issue 3.4 bounded `findWithBalance` by date).
        val today = clock.today().toString()
        return withContext(dispatchers.io) {
            runCatchingToResult {
                val from =
                    database.accountDao().findWithBalance(validated.fromAccountId, today)?.account
                        ?: return@runCatchingToResult null
                val to =
                    database.accountDao().findWithBalance(validated.toAccountId, today)?.account
                        ?: return@runCatchingToResult null
                // Currencies must match. Converting would need the FX rates §20.1 reserves a table
                // for and no issue has built; guessing a rate would be the app inventing a number
                // (P-03). Refusing is the honest answer until issue 13.x builds conversion.
                if (from.currencyCode != to.currencyCode) {
                    return@runCatchingToResult null
                }
                // One transaction around both writes (DB-004). Nothing may ever observe one leg
                // without the other — a half-transfer is money created or destroyed.
                database.withTransaction { writeTransferLegs(validated, from, to, stamps) }
            }.flatMapPresent()
        }
    }

    /**
     * Writes the two legs and returns the collapsed record.
     * Why:    split out of [createTransfer] so the part that must run inside `withTransaction` is one
     *         readable block, and so the shared values — the id, the instant, the booked day — are
     *         visibly computed **once** and used twice. Computing `clock.today()` per leg would be
     *         the bug that splits a transfer across midnight; issue 3.4 moved that computation out
     *         to [BookingStamps] entirely, so this function can no longer read a clock at all.
     * Result: both rows written; the [Transfer] describing them.
     * Input:  [draft] — already validated; [from], [to] — the verified live accounts; [stamps] — the
     *         booked day and instants both legs share (issue 3.4).
     * Output: [Transfer].
     * Changelog: 2026-08-02 — Created for issue 3.2.
     *            2026-08-03 — Issue 3.4: takes [stamps] instead of reading the clock (FR-TXN-010).
     */
    private suspend fun writeTransferLegs(
        draft: TransferDraft,
        from: AccountEntity,
        to: AccountEntity,
        stamps: BookingStamps,
    ): Transfer {
        val bookedOn = stamps.bookedOnIsoDate
        val transferId = ids.newId(TransactionRepository.TRANSFER_ID_PREFIX)
        val dao = database.transactionDao()

        dao.upsert(
            draft.leg(
                account = from,
                // Money's checked arithmetic (MNY-001): an absurd amount throws rather than wrapping
                // to a positive fortune on the way out of the source account.
                amount = Money.ZERO - draft.amount,
                type = TransactionType.TRANSFER_OUT,
                id = ids.newId(TransactionRepository.ID_PREFIX),
                transferId = transferId,
                stamps = stamps,
            ),
        )
        dao.upsert(
            draft.leg(
                account = to,
                amount = draft.amount,
                type = TransactionType.TRANSFER_IN,
                id = ids.newId(TransactionRepository.ID_PREFIX),
                transferId = transferId,
                stamps = stamps,
            ),
        )
        return Transfer(
            id = transferId,
            fromAccountId = from.id,
            toAccountId = to.id,
            amount = draft.amount,
            bookedOn = bookedOn,
            note = draft.note,
        )
    }

    override suspend fun createSplit(draft: SplitDraft): Result<Transaction, AppError> {
        val validated = draft.validated() ?: return Err(AppError.Validation(draft.invalidField()))
        // Issue 3.4: the parent's day. The lines take no date of their own (FR-TXN-010).
        val stamps =
            clock.stampsFor(validated.bookedOn, validated.bookedAt)
                ?: return Err(AppError.Validation("bookedOn"))
        // Today, not the booked day: the account lookup only proves the account is live, and its
        // balance is read as at now (issue 3.4 bounded `findWithBalance` by date).
        val today = clock.today().toString()
        return withContext(dispatchers.io) {
            runCatchingToResult {
                val account =
                    database.accountDao().findWithBalance(validated.accountId, today)?.account
                        ?: return@runCatchingToResult null
                // One transaction around the parent and every line (DB-004): a parent without its
                // lines is a miscategorised amount, and lines without a parent attribute nothing.
                database.withTransaction { writeSplit(validated, account, stamps) }
            }.flatMapPresent()
        }
    }

    /**
     * Writes the parent transaction and its lines, and returns them assembled.
     * Why:    split out of [createSplit] so the part that must run inside `withTransaction` is one
     *         readable block, and so the values the parent and its lines share — the instant, the
     *         booked day, the profile — are visibly computed **once**.
     *
     *         **The parent carries no `categoryId`.** The lines carry the categories; a category on
     *         the parent as well would be a second, contradictory answer to "what was this?".
     * Result: both writes done; the [Transaction] with its lines attached.
     * Input:  [draft] — already validated; [account] — the verified live account; [stamps] — the
     *         parent's booked day and instants (issue 3.4).
     * Output: [Transaction].
     * Changelog: 2026-08-02 — Created for issue 3.3.
     *            2026-08-03 — Issue 3.4: takes [stamps] instead of reading the clock (FR-TXN-010).
     */
    private suspend fun writeSplit(
        draft: SplitDraft,
        account: AccountEntity,
        stamps: BookingStamps,
    ): Transaction? {
        val now = stamps.nowUtcMillis
        val parentId = ids.newId(TransactionRepository.ID_PREFIX)
        val parent =
            TransactionEntity(
                id = parentId,
                // The account's profile, not the active one — the choice ADR-0006 requires so a row
                // lands where the demo wipe can reach it.
                profileId = account.profileId,
                accountId = account.id,
                amountMinor = draft.amount.minor,
                currencyCode = account.currencyCode,
                occurredAtUtcMillis = stamps.occurredAtUtcMillis,
                bookedOnIsoDate = stamps.bookedOnIsoDate,
                categoryId = null,
                merchant = draft.merchant,
                note = draft.note,
                source = TransactionSource.MANUAL.storedValue,
                type = draft.amount.directionType().storedValue,
                // On the parent only: the lines divide an amount, and it is the parent that moves.
                postedAtUtcMillis = stamps.postedAtUtcMillis,
                createdAtUtcMillis = now,
                updatedAtUtcMillis = now,
            )
        val lines =
            draft.lines.map { line ->
                TransactionSplitEntity(
                    id = ids.newId(TransactionRepository.SPLIT_ID_PREFIX),
                    profileId = account.profileId,
                    transactionId = parentId,
                    amountMinor = line.amount.minor,
                    categoryId = line.categoryId,
                    note = line.note,
                    createdAtUtcMillis = now,
                    updatedAtUtcMillis = now,
                )
            }

        database.transactionDao().upsert(parent)
        database.transactionSplitDao().upsertAll(lines)
        // No balance write: the parent row *is* the balance change (DB-001, ADR-0007), and the lines
        // deliberately contribute nothing to it.
        return parent.toTransaction()?.copy(splits = lines.map { it.toSplit() })
    }

    override suspend fun delete(transactionId: String): Result<Unit, AppError> =
        withContext(dispatchers.io) {
            runCatchingToResult {
                val existing =
                    database.transactionDao().findById(transactionId)
                        ?: return@runCatchingToResult 0
                val now = clock.nowUtcMillis()
                // One transaction around every row that goes (DB-004): a parent gone without its
                // lines, or one transfer leg without the other, is a half-deleted record.
                database.withTransaction {
                    // The transfer case is one statement, not a read-then-delete-each loop, so there
                    // is no window in which one leg is gone and the other is not (FR-TXN-003).
                    val touched =
                        existing.transferId
                            ?.let { database.transactionDao().softDeleteTransfer(it, now) }
                            ?: database.transactionDao().softDelete(transactionId, now)
                    // Only when the parent actually went: a repeated delete must stay a no-op all
                    // the way down rather than quietly re-stamping the lines' tombstones.
                    if (touched > 0) {
                        database.transactionSplitDao().softDeleteForTransaction(transactionId, now)
                    }
                    touched
                }
            }.requireRowTouched()
        }
}

/**
 * Rejects a draft the user cannot have meant.
 * Why:    two things make a draft unusable. **A zero amount** records no fact — it would sit in
 *         every list and every total contributing nothing, and it is far more likely to be an empty
 *         field the user tapped Save on than something they meant. (Issue 2.7 makes the same call
 *         for a zero reconciliation delta, for the same reason.) **A blank account id** would orphan
 *         the row. Trimming the free text rather than merely checking it means `" Chai "` and
 *         `"Chai"` cannot become two merchants that look identical on screen.
 * Result: the draft with its text trimmed and blanks collapsed to `null`, or `null` when the draft
 *         is unusable. `null` rather than an exception because §5 forbids exceptions across a layer
 *         boundary.
 * Input:  the receiver. Output: `TransactionDraft?`.
 * Changelog: 2026-08-02 — Created for issue 3.1.
 */
internal fun TransactionDraft.validated(): TransactionDraft? {
    if (accountId.isBlank() || amount == Money.ZERO) return null
    return copy(
        accountId = accountId.trim(),
        categoryId = categoryId?.trim()?.takeIf { it.isNotBlank() },
        merchant = merchant?.trim()?.takeIf { it.isNotBlank() },
        note = note?.trim()?.takeIf { it.isNotBlank() },
    )
}

/**
 * Rejects a transfer the user cannot have meant (issue 3.2).
 * Why:    three things make one unusable, and each would produce a row that is wrong rather than
 *         merely empty. **A non-positive amount** — the sign is the repository's to apply, so a
 *         negative here means the caller has already decided a direction it does not get to decide,
 *         and zero records no fact. **The same account on both sides** would write two rows that
 *         cancel out: a transfer that moved nothing, cluttering the list for ever. **A blank id**
 *         would orphan a leg.
 * Result: the draft with its ids and note trimmed, or `null` when it is unusable. `null` rather than
 *         an exception because §5 forbids exceptions across a layer boundary.
 * Input:  the receiver. Output: `TransferDraft?`.
 * Changelog: 2026-08-02 — Created for issue 3.2.
 */
internal fun TransferDraft.validated(): TransferDraft? {
    val from = fromAccountId.trim()
    val to = toAccountId.trim()
    val accountsUsable = from.isNotBlank() && to.isNotBlank() && from != to
    if (!accountsUsable || amount <= Money.ZERO) return null
    return copy(
        fromAccountId = from,
        toAccountId = to,
        note = note?.trim()?.takeIf { it.isNotBlank() },
    )
}

/**
 * Names the field that made a transfer draft invalid (issue 3.2).
 * Why:    `AppError.Validation` carries a field name so the screen can point at the control that is
 *         wrong. The destination is checked before the amount because "you cannot transfer to the
 *         same account" is the mistake a user can actually see and fix; a bad amount is the more
 *         ordinary one and reads as the fallback.
 * Result: `"toAccountId"` when the two sides name one account or the destination is blank,
 *         `"fromAccountId"` when the source is blank, otherwise `"amount"`.
 * Input:  the receiver. Output: [String].
 * Changelog: 2026-08-02 — Created for issue 3.2.
 */
internal fun TransferDraft.invalidField(): String =
    when {
        fromAccountId.isBlank() -> "fromAccountId"
        toAccountId.isBlank() || fromAccountId.trim() == toAccountId.trim() -> "toAccountId"
        else -> "amount"
    }

/**
 * Builds one leg of a transfer.
 * Why:    the two legs differ in exactly three things — the account, the sign, and the type — and are
 *         identical in the seven that matter for keeping them one record: the transfer id, the
 *         instant, the booked day, the note, the source, the profile and the currency. Writing them
 *         from one function is what makes that structural rather than a thing a reader has to check
 *         by comparing two nearly-identical blocks.
 *
 *         **No category, ever** (FR-TXN-003): a transfer is not spending, and a categorised leg
 *         would count the user's own savings against a budget envelope once issue 4.4 lands.
 * Result: the [TransactionEntity] for that side of the movement.
 * Input:  the receiver — the validated draft; [account] — the verified live account this leg belongs
 *         to; [amount] — already signed for this side; [type] — `TRANSFER_OUT` or `TRANSFER_IN`;
 *         [id]; [transferId] — shared with the sibling; [stamps] — the day and instants both legs
 *         share (issue 3.4).
 * Output: [TransactionEntity].
 * Changelog: 2026-08-02 — Created for issue 3.2.
 *            2026-08-03 — Issue 3.4: `now` and `bookedOn` became one [BookingStamps], which is what
 *            makes "both legs share one booked day" impossible to get wrong at this call site.
 */
@Suppress("LongParameterList") // Six values, all of them one leg's identity; a wrapper would hide it.
internal fun TransferDraft.leg(
    account: AccountEntity,
    amount: Money,
    type: TransactionType,
    id: String,
    transferId: String,
    stamps: BookingStamps,
): TransactionEntity =
    TransactionEntity(
        id = id,
        // The **account's** profile, not the active one — the same choice `writeAdjustment` makes
        // (ADR-0006): a row must land where the demo wipe can reach it.
        profileId = account.profileId,
        accountId = account.id,
        amountMinor = amount.minor,
        currencyCode = account.currencyCode,
        occurredAtUtcMillis = stamps.occurredAtUtcMillis,
        bookedOnIsoDate = stamps.bookedOnIsoDate,
        categoryId = null,
        merchant = null,
        note = note,
        source = TransactionSource.MANUAL.storedValue,
        type = type.storedValue,
        transferId = transferId,
        // Both legs are scheduled or both are posted — they share one day, so they cannot differ.
        postedAtUtcMillis = stamps.postedAtUtcMillis,
        createdAtUtcMillis = stamps.nowUtcMillis,
        updatedAtUtcMillis = stamps.nowUtcMillis,
    )

/**
 * The type an ordinary, non-transfer amount implies (issue 3.2).
 * Why:    the single place a plain transaction's `type` comes from, so the column can never disagree
 *         with the sign that produced it. Callers pass an amount, never a type.
 * Result: [TransactionType.EXPENSE] for an outflow, [TransactionType.INCOME] otherwise. Zero cannot
 *         reach here — `TransactionDraft.validated` rejects it before any type is chosen.
 * Input:  the receiver — a signed amount. Output: [TransactionType].
 * Changelog: 2026-08-02 — Created for issue 3.2.
 */
internal fun Money.directionType(): TransactionType =
    if (this < Money.ZERO) TransactionType.EXPENSE else TransactionType.INCOME

/**
 * Names the field that made a draft invalid.
 * Why:    `AppError.Validation` carries a field name so a screen can point at the input that is
 *         wrong rather than showing one generic message for two unrelated mistakes. The amount is
 *         checked first because it is the one the user can actually see and fix — a blank account id
 *         means the picker had nothing in it, which is a different conversation.
 * Result: `"amount"` or `"accountId"`. Only meaningful when [validated] returned `null`.
 * Input:  the receiver. Output: [String].
 * Changelog: 2026-08-02 — Created for issue 3.1.
 */
internal fun TransactionDraft.invalidField(): String = if (amount == Money.ZERO) "amount" else "accountId"

/**
 * Converts a row into the domain model.
 * Why:    ARC-005 — nothing above `:data:repository` may hold a Room type. The amount is wrapped in
 *         [Money] rather than passed as a `Long` so every arithmetic on it upstream is
 *         overflow-checked (MNY-001).
 * Result: a [Transaction], or `null` when the stored `source` is one this build does not know.
 * Input:  the receiver. Output: `Transaction?`.
 * Changelog: 2026-08-02 — Created for issue 3.1.
 */
internal fun TransactionEntity.toTransaction(): Transaction? {
    val parsedSource = TransactionSource.fromStored(source) ?: return null
    // Issue 3.2: `type` is dropped the same way `source` is when this build does not know the value.
    // A row typed by a newer build is skipped rather than guessed at — guessing would be worse here
    // than for `source`, because a mis-typed transfer leg would be counted as spending.
    val parsedType = TransactionType.fromStored(type) ?: return null
    return Transaction(
        id = id,
        accountId = accountId,
        amount = Money(amountMinor),
        occurredAtUtcMillis = occurredAtUtcMillis,
        bookedOn = bookedOnIsoDate,
        categoryId = categoryId,
        merchant = merchant,
        note = note,
        source = parsedSource,
        type = parsedType,
        transferId = transferId,
        postedAtUtcMillis = postedAtUtcMillis,
    )
}

/**
 * Converts a parent and its lines into the domain model (issue 3.3; FR-TXN-004).
 * Why:    the single place a stored split becomes a [Transaction] with [Transaction.splits] on it.
 *         **Tombstoned lines are dropped here**, because Room's `@Relation` cannot carry a `WHERE`;
 *         doing it once, at the one mapping site, is what stops a deleted line reappearing in some
 *         later read.
 * Result: a [Transaction] carrying its live lines, or `null` when the parent's stored `source` or
 *         `type` is one this build does not know.
 * Input:  the receiver. Output: `Transaction?`.
 * Changelog: 2026-08-02 — Created for issue 3.3.
 */
internal fun TransactionWithSplits.toTransaction(): Transaction? =
    transaction.toTransaction()?.copy(
        splits = splits.filter { it.deletedAtUtcMillis == null }.map { it.toSplit() },
    )

/**
 * Converts a category row into the domain model.
 * Why:    the add screen needs an id and a label to render a chip. `nature`, `parent_id` and
 *         `is_system` are on the entity and are deliberately dropped — issues 4.1 and 4.3 own those,
 *         and carrying them now would be a second, drifting definition of a model they will build.
 * Result: a [Category]. Cannot fail: unlike a source or an account type, there is no stored
 *         vocabulary here to fall off.
 * Input:  the receiver. Output: [Category].
 * Changelog: 2026-08-02 — Created for issue 3.1.
 */
internal fun CategoryEntity.toCategory(): Category = Category(id = id, name = name)
