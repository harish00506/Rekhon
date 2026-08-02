package com.aicfo.data.repository

import com.aicfo.core.common.AppError
import com.aicfo.core.common.Clock
import com.aicfo.core.common.DispatcherProvider
import com.aicfo.core.common.Err
import com.aicfo.core.common.IdGenerator
import com.aicfo.core.common.Result
import com.aicfo.core.common.runCatchingToResult
import com.aicfo.core.database.CfoDatabase
import com.aicfo.core.database.entity.CategoryEntity
import com.aicfo.core.database.entity.TransactionEntity
import com.aicfo.core.model.Category
import com.aicfo.core.model.Money
import com.aicfo.core.model.Transaction
import com.aicfo.core.model.TransactionSource
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

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
     */
    fun observeRecent(): Flow<List<Transaction>>

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
    }
}

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
 *         optional free text; [note] — optional free text.
 * Output: an immutable value.
 */
data class TransactionDraft(
    val accountId: String,
    val amount: Money,
    val categoryId: String? = null,
    val merchant: String? = null,
    val note: String? = null,
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
            database.transactionDao()
                .observeBookedBetween(
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
        return withContext(dispatchers.io) {
            runCatchingToResult {
                // The account is read for three things at once: proof it exists and is live, the
                // profile the row belongs to, and the currency. Reading it is not an optimisation —
                // without it SQLite would happily store a transaction against an id that names
                // nothing, and it would then count towards no balance and show in no history.
                val account =
                    database.accountDao().findWithBalance(validated.accountId)?.account
                        ?: return@runCatchingToResult null
                val now = clock.nowUtcMillis()
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
                        occurredAtUtcMillis = now,
                        bookedOnIsoDate = clock.today().toString(),
                        categoryId = validated.categoryId,
                        merchant = validated.merchant,
                        note = validated.note,
                        // FR-TXN-009. This flow is the one the user typed by hand; OCR (3.8), SMS
                        // (3.9) and import (5.4) stamp their own.
                        source = TransactionSource.MANUAL.storedValue,
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
    val parsed = TransactionSource.fromStored(source) ?: return null
    return Transaction(
        id = id,
        accountId = accountId,
        amount = Money(amountMinor),
        occurredAtUtcMillis = occurredAtUtcMillis,
        bookedOn = bookedOnIsoDate,
        categoryId = categoryId,
        merchant = merchant,
        note = note,
        source = parsed,
    )
}

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
