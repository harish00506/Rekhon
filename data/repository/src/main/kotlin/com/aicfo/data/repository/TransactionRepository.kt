package com.aicfo.data.repository

import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import androidx.paging.flatMap
import androidx.paging.map
import androidx.room.withTransaction
import com.aicfo.core.common.AppError
import com.aicfo.core.common.Clock
import com.aicfo.core.common.DispatcherProvider
import com.aicfo.core.common.Err
import com.aicfo.core.common.IdGenerator
import com.aicfo.core.common.Ok
import com.aicfo.core.common.Result
import com.aicfo.core.common.runCatchingToResult
import com.aicfo.core.database.CfoDatabase
import com.aicfo.core.database.dao.MerchantNatureOverrideRow
import com.aicfo.core.database.dao.NatureCandidateRow
import com.aicfo.core.database.dao.TransactionWithSplits
import com.aicfo.core.database.entity.AccountEntity
import com.aicfo.core.database.entity.CategoryEntity
import com.aicfo.core.database.entity.TagEntity
import com.aicfo.core.database.entity.TransactionEntity
import com.aicfo.core.database.entity.TransactionSplitEntity
import com.aicfo.core.database.entity.TransactionTagEntity
import com.aicfo.core.model.AccountType
import com.aicfo.core.model.Category
import com.aicfo.core.model.CategoryNature
import com.aicfo.core.model.EngineProvenance
import com.aicfo.core.model.Money
import com.aicfo.core.model.Tag
import com.aicfo.core.model.Transaction
import com.aicfo.core.model.TransactionSource
import com.aicfo.core.model.TransactionType
import com.aicfo.core.model.Transfer
import com.aicfo.domain.engines.classification.CategorySuggestion
import com.aicfo.domain.engines.classification.ClassificationEngine
import com.aicfo.domain.engines.classification.ClassificationInput
import com.aicfo.domain.engines.classification.MerchantHistoryRow
import com.aicfo.domain.engines.classification.normaliseMerchant
import com.aicfo.domain.engines.nature.NatureBreakdown
import com.aicfo.domain.engines.nature.NatureContribution
import com.aicfo.domain.engines.nature.NatureEngine
import com.aicfo.domain.engines.nature.NatureHistoryRow
import com.aicfo.domain.engines.nature.NatureInput
import com.aicfo.domain.engines.nature.NatureRules
import com.aicfo.domain.engines.nature.NatureVerdict
import com.aicfo.domain.engines.nature.natureBreakdown
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
 * What: the reads the list is built from, and every write that creates or changes a transaction.
 * Result: a transaction is the user's to create, find, edit in bulk and undo, and every balance that
 *       derives from one moves.
 * Changelog: 2026-08-02 — Created for issue 3.1.
 *            2026-08-04 — Issue 3.6: the windowed `observeRecent` became [observeFiltered], a paged
 *            read over the whole ledger, and the bulk edits FR-TXN-008 asks for landed beside it.
 *
 * **The only class allowed to touch [com.aicfo.core.database.dao.TransactionDao] (ARC-005).** The
 * transactions ViewModels see [Transaction] — a `:core:model` type — and never a Room entity.
 *
 * **Nothing here writes a balance (DB-001, ADR-0007).** An account's balance is
 * `opening_balance + SUM(live transactions)`, derived on every read, so inserting the row *is* the
 * balance update. A repository that also adjusted `account.current_balance_minor` would be stating
 * a figure that is already derivable, which is precisely what DB-001 forbids.
 *
 * **The class grows one method per issue, not a speculative surface.** Transfers (3.2), splits
 * (3.3), future dating (3.4), source tracking (3.5) and search/filters/bulk edit (3.6) each added
 * exactly what they needed. Per-row editing has no issue yet and so has no method here.
 *
 * **Past detekt's `TooManyFunctions` ceiling, deliberately.** ARC-005 makes this the single gateway
 * to the `transactions` table, so its surface is the sum of every transaction feature. Splitting it
 * would give two classes the same database, clock, id generator and profile flow, and `deleteAll`
 * would lose the `softDeleteOne` that `delete` shares with it — the duplication that split is
 * supposed to prevent.
 */
@Suppress("TooManyFunctions") // See the note above: one gateway per table (ARC-005).
interface TransactionRepository {
    /**
     * Observes the active profile's whole ledger, filtered and paged, newest first (issue 3.6).
     *
     * Why:    the caller does not choose the profile, for the same reason
     *         [AccountRepository.observeAccounts] does not — demo mode puts sample data under a
     *         second profile id, and "which profile?" is a question with one right answer at any
     *         moment that several screens would each have to get right.
     *
     *         **This replaced `observeRecent`, which read a fixed 30-day window.** That window was
     *         scaffolding with its removal written into its own doc comment: "issue 3.6 owns the
     *         full list with search, filters and paging". A user with six months of history could
     *         not reach month four. Paging is what lets the list stop being a window without
     *         becoming an unbounded load.
     * Result: emits a new [PagingData] whenever the data changes; soft-deleted rows excluded. An
     *         empty page is a real state — and the screen must tell "this profile has nothing" from
     *         "this filter matches nothing", which [TransactionFilter.isActive] answers.
     * Input:  [filter] — every facet `null` for the unfiltered ledger.
     * Output: `Flow<PagingData<FilteredTransaction>>`.
     *
     * **The lower bound is gone; the upper bound is not.** Without an explicit [filter] date range
     * this still stops at today, so a scheduled payment stays out of the actuals and out of every
     * day total (FR-TXN-010) — [observeUpcoming] is where those rows are read, and a list that
     * returned them here as well would render them twice. A filter that *names* a future date is
     * honoured, because a user asking for next month is asking for exactly those rows.
     */
    fun observeFiltered(filter: TransactionFilter): Flow<PagingData<FilteredTransaction>>

    /**
     * Observes the active profile's **future-dated** transactions, soonest first (issue 3.4).
     *
     * Why:    FR-TXN-010's second clause — future-dated rows are "excluded from actuals but included
     *         in forecasts". This is the read that makes the second half possible: the cash-flow
     *         forecast (Epic 6) and FR-HOME-001's "upcoming obligations (next 14 days)" card both
     *         need the obligations the user has already told the app about, and neither can find
     *         them in the actuals half of the list, which stops at today by design.
     *
     *         **A separate flow rather than a widened window**, so the two halves cannot be summed
     *         by accident. A caller that wants actuals gets actuals; nothing has to remember to
     *         filter. The bounds are tomorrow to today + [UPCOMING_WINDOW_DAYS], both computed from
     *         the injected `Clock`, so the split moves at the profile's midnight (TIM-001) — a row
     *         leaves this flow and joins the actuals on its own date, with no write.
     * Result: emits on every change, soonest date first; soft-deleted rows excluded. Empty is the
     *         normal state — most users schedule nothing.
     * Input:  none — the active profile. Output: `Flow<List<Transaction>>`.
     */
    fun observeUpcoming(): Flow<List<Transaction>>

    /**
     * Observes each day's net total over the same filtered set (issue 3.6; FR-TXN-007).
     *
     * Why:    FR-TXN-007 asks for "grouping by day with daily totals", and with paging the total
     *         **cannot** be folded from the rows on screen: a page boundary can fall inside a day,
     *         so a header would understate its own day until the user scrolled. A wrong number is
     *         worse than a late one, so the database sums every matching row and the header renders
     *         what it says.
     *
     *         **A transfer contributes nothing**, which is arithmetically true — its legs are `-X`
     *         and `+X` — and matches what the list has shown since issue 3.2.
     * Result: emits day → signed total, keyed by the profile-zone ISO day (TIM-002). A day with no
     *         non-transfer activity is simply absent, which the header renders as zero.
     * Input:  [filter] — the same value passed to [observeFiltered]; the two must agree or the
     *         headers will describe a different set from the rows beneath them.
     * Output: `Flow<Map<String, Money>>`.
     */
    fun observeDayTotals(filter: TransactionFilter): Flow<Map<String, Money>>

    /**
     * Observes the newest few transactions, count-bounded (issue 5.1; FR-DASH-*).
     *
     * Why:    the dashboard's recent-activity preview wants a handful of rows on the screen the app
     *         opens to, not the whole ledger [observeFiltered] pages over. **This is not the fixed
     *         30-day `observeRecent` issue 3.6 removed** — that one was a time window, and a user
     *         with six months of history could not reach month four through it. This is bounded by
     *         *count*: the full ledger stays exactly as reachable as it was after 3.6, one tap away
     *         through the Transactions screen, so a preview here does not reintroduce the problem
     *         3.6 fixed.
     * Result: emits on every change, newest first; soft-deleted rows excluded; future-dated rows
     *         excluded the same way the unfiltered [observeFiltered] case is (FR-TXN-010) — a
     *         scheduled payment belongs on `observeUpcoming`, not in a preview of what already
     *         happened.
     * Input:  [limit] — how many rows at most. Output: `Flow<List<FilteredTransaction>>`.
     */
    fun observeRecent(limit: Int): Flow<List<FilteredTransaction>>

    /**
     * Observes this month's income, expense and net cash flow (issue 5.1; FR-DASH-*).
     *
     * Why:    a raw ledger aggregation, not an engine result — unlike [observeNatureBreakdown],
     *         nothing here classifies a transaction; it only sums the ones the transaction's own
     *         `type` column already says are income or expense, over the current profile-zone
     *         month (TIM-001/TIM-002). That is the same distinction [observeDayTotals] draws, and
     *         it is why this carries no [com.aicfo.core.model.EngineProvenance] either.
     * Result: emits on every change. [CashFlowSummary.income]/[CashFlowSummary.expense] are always
     *         non-negative magnitudes, matching the convention `BudgetStatus.spent` already uses —
     *         the sign lives in [CashFlowSummary.net], not in the two halves that produced it.
     * Input:  none — the active profile and the current month. Output: `Flow<CashFlowSummary>`.
     */
    fun observeMonthCashFlow(): Flow<CashFlowSummary>

    /**
     * Observes the sources present anywhere in the active profile's ledger (issue 3.6; FR-TXN-009).
     *
     * Why:    the source chips. Issue 3.5 derived them in the ViewModel from the rows it had, which
     *         was correct while those rows *were* everything; with paging they are the first page,
     *         so chips built from them would appear and vanish as the user scrolled. **Unfiltered on
     *         purpose** — chips derived from the filtered set would remove every alternative the
     *         moment one was chosen, stranding the user with no way back to "All".
     * Result: emits the distinct sources in [TransactionSource.entries] order, so chips keep their
     *         positions as rows arrive. Unknown stored values are dropped, not guessed at.
     * Input:  none — the active profile. Output: `Flow<List<TransactionSource>>`.
     */
    fun observeSources(): Flow<List<TransactionSource>>

    /**
     * Observes the active profile's tags, name-ordered (issue 3.6; FR-TXN-007).
     *
     * Result: emits on every change; soft-deleted tags excluded. **Empty is the normal state** —
     *         tags are an opt-in habit, so the filter sheet hides the row rather than showing one
     *         with nothing in it.
     * Input:  none — the active profile. Output: `Flow<List<Tag>>`.
     */
    fun observeTags(): Flow<List<Tag>>

    /**
     * Sets the category on many transactions at once (issue 3.6; FR-TXN-008).
     *
     * Why:    FR-TXN-008's "multi-select recategorise". Applied as **one statement inside one
     *         database transaction** (DB-004): a loop of single updates would leave a window in
     *         which half the selection was recategorised, which is the state no screen can render
     *         honestly.
     *
     *         **Transfer legs and split parents are skipped, not refused.** A selection is a rough
     *         instrument — the user swept up twenty rows and two of them happen to be a transfer —
     *         and failing the whole operation would punish them for the app's invariants. The rows
     *         that can carry a category get one; the count says how many did.
     * Result: `Ok(n)` — rows actually changed, **which may be fewer than [ids]**, and `0` is a
     *         success meaning nothing in the selection was eligible. `Err(Validation)` for an empty
     *         selection, or `Err(Storage)`.
     * Input:  [ids] — the selected transactions; [categoryId] — `null` clears the category, which
     *         is a legitimate bulk edit and not a mistake.
     * Output: `Result<Int, AppError>`.
     */
    suspend fun recategoriseAll(
        ids: List<String>,
        categoryId: String?,
    ): Result<Int, AppError>

    /**
     * Replaces the tags on many transactions at once (issue 3.6; FR-TXN-008).
     *
     * Why:    FR-TXN-008's "retag". Expressed as a **set** — "these transactions now carry exactly
     *         these tags" — rather than as add/remove deltas, which makes it idempotent: applying
     *         the same retag twice leaves the same links, so a double-tap cannot double anything.
     *
     *         **Tags the profile does not have yet are created here**, in the same database
     *         transaction as the links (DB-004), matched case-insensitively against existing ones so
     *         `Travel` typed today and `travel` typed last week stay one tag rather than becoming
     *         two chips the user cannot tell apart.
     * Result: `Ok(n)` — the transactions retagged. An empty [tagNames] clears every tag from them,
     *         which is how "remove all tags" is expressed. `Err(Validation)` for an empty selection,
     *         or `Err(Storage)`.
     * Input:  [ids] — the selected transactions; [tagNames] — the labels as the user typed them,
     *         trimmed and de-duplicated by the repository.
     * Output: `Result<Int, AppError>`.
     */
    suspend fun retagAll(
        ids: List<String>,
        tagNames: List<String>,
    ): Result<Int, AppError>

    /**
     * Soft-deletes many transactions at once, and reports exactly what went (issue 3.6; FR-TXN-008).
     *
     * Why:    FR-TXN-008's "delete (with undo snackbar, 7-day soft delete)". Every row goes inside
     *         one database transaction (DB-004) — a half-applied bulk delete is money half-destroyed
     *         — and each one carries its split lines and its sibling transfer leg exactly as a
     *         single [delete] does, because it routes through the same code.
     *
     *         **It returns the ids it actually removed, which may be more than it was given.**
     *         Deleting one leg of a transfer takes the other (FR-TXN-003); an undo that restored
     *         only the selection would bring back one leg and leave the money the transfer moved
     *         sitting in one account with no counterpart. The caller hands this list straight to
     *         [restoreAll].
     * Result: `Ok(ids)` — every id removed, siblings included; the affected balances revert.
     *         `Err(Validation)` for an empty selection, `Err(NotFound)` when nothing named was live,
     *         or `Err(Storage)`.
     * Input:  [ids] — the selected transactions. Output: `Result<List<String>, AppError>`.
     */
    suspend fun deleteAll(ids: List<String>): Result<List<String>, AppError>

    /**
     * Brings soft-deleted transactions back (issue 3.6; FR-TXN-008's undo).
     *
     * Why:    "delete with undo" is only honest if the delete is genuinely reversible, and DB-002's
     *         tombstones are what make it so. The split lines are restored with their parents in the
     *         same database transaction; tag links were never removed, so they return by themselves.
     *
     *         **Every balance recovers with no balance write**, because a balance is a `SUM` over
     *         live transactions (DB-001, ADR-0007) — the same property that made the delete move it.
     * Result: `Ok(n)` — rows restored. `Err(Validation)` for an empty list, `Err(NotFound)` when
     *         nothing named was actually deleted, or `Err(Storage)`.
     * Input:  [ids] — exactly what [deleteAll] returned. Output: `Result<Int, AppError>`.
     */
    suspend fun restoreAll(ids: List<String>): Result<Int, AppError>

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
     * Result: fifteen seeded rows on a real profile since issue 4.1, twelve inside the demo. An
     *         empty list is not an error; the screen hides the chip row and saves `categoryId =
     *         null`, which the column has always allowed.
     * Input:  none — the active profile. Output: `Flow<List<Category>>`.
     */
    fun observeCategories(): Flow<List<Category>>

    /**
     * Proposes a category for a merchant, or declines to (issue 4.2; SRS §8.1, P-02, P-07).
     *
     * Why:    §8.1 Stage 1 needs two things this class is the only one allowed to fetch — what the
     *         user has filed under this merchant before, and which categories the profile currently
     *         has — and one thing it must not decide: the answer. So this method reads, and
     *         [com.aicfo.domain.engines.classification.ClassificationEngine] decides (ARC-005,
     *         P-03). The ViewModel gets a suggestion it can pre-select and explain, and never sees a
     *         DAO row or a rule.
     *
     *         **It writes nothing.** A suggestion is offered, accepted by the user leaving it alone,
     *         and overridden by them tapping a different chip (P-07). The tap that overrides it is
     *         also what teaches the history tier, because that tap becomes a categorised transaction.
     * Result: `Ok(suggestion)` naming a category live on this profile, `Ok(null)` when Stage 1
     *         defers — which is the ordinary answer for an unfamiliar merchant and not a failure —
     *         or `Err(Storage)` when the history read fails.
     * Input:  [merchant] — the payee as typed or parsed, in any case, blank meaning "nothing to
     *         classify". Output: `Result<CategorySuggestion?, AppError>`.
     */
    suspend fun suggestCategory(merchant: String): Result<CategorySuggestion?, AppError>

    /**
     * Decides what one transaction's money became (issue 4.3; SRS §8.3, AI-CLS-N, P-02).
     *
     * Why:    §8.3.1's decision order branches on the account's type, the counterpart account's
     *         type, the category's nature, the user's past corrections and the category's median —
     *         five joins, every one of which only this class may make (ARC-005). It fetches; the
     *         engine decides (P-03).
     *
     *         **A stored override short-circuits the whole order**, and that is not an optimisation.
     *         `transactions.nature` holds nothing but corrections, so a value there is the user
     *         saying they disagree with every step below — re-running the order and then discarding
     *         its answer would be the same result with a worse story to tell (P-02: the card says
     *         "you set this", not "rule CLS-NAT-005").
     * Result: `Ok(verdict)` — never null, because §8.3 gives every transaction a nature and
     *         uncertainty is carried as confidence. `Err(NotFound)` when the id names nothing live,
     *         `Err(Storage)` when a read fails.
     * Input:  [transactionId]. Output: `Result<NatureVerdict, AppError>`.
     */
    suspend fun natureOf(transactionId: String): Result<NatureVerdict, AppError>

    /**
     * Records or clears the user's nature correction (issue 4.3; SRS §8.3, P-07).
     *
     * Why:    §8.3 makes nature "auto-assigned, user-**correctable**, learned", and this is both the
     *         correcting and the learning: the row it writes is what §8.3.1 step 4 reads back for
     *         every later transaction at the same merchant. Which is why passing `null` is a real
     *         operation and not a no-op — it withdraws the correction and returns the transaction to
     *         whatever the rules currently decide, and it must also stop teaching.
     * Result: `Ok(Unit)`; `Err(NotFound)` when the id names nothing live.
     * Input:  [transactionId]; [nature] — what the user chose, or `null` to withdraw.
     * Output: `Result<Unit, AppError>`.
     */
    suspend fun setNature(
        transactionId: String,
        nature: CategoryNature?,
    ): Result<Unit, AppError>

    /**
     * Observes what this month's money became (issue 4.3; SRS §8.3's true-spend split).
     *
     * Why:    "you spent ₹60,000 this month" is a useless sentence if ₹25,000 of it went into an SIP
     *         and a gold purchase, and separating the three is what §8.3 exists for. This is the
     *         separation, for the current month in the profile time zone (TIM-002).
     *
     *         **The month is this class's to choose, not the caller's**, for the same reason
     *         `observeAccounts` chooses the profile: "which month is now" is a profile-zone question
     *         whose only sanctioned answer is the injected `Clock` (TIM-001), and a screen that
     *         passed a range would have had to read a clock to build one.
     *
     *         **Bounded at today, not at month end** — the window is `MonthWindow.actualsEndIsoDate`,
     *         the same bound [observeMonthCashFlow] three methods below has always used. Until issue
     *         5.2 this ran to `lengthOfMonth()`, so a rent payment the user had scheduled for the
     *         28th was reported on the 3rd as money already spent (FR-TXN-010: future-dated rows are
     *         "excluded from actuals but included in forecasts", and this is an actuals read). The
     *         card it feeds is captioned "This month, **actually**".
     *
     *         It also made the two figures overlap: `SafeToSpendRepository` subtracts scheduled
     *         payments as their own term, so every scheduled row inside the month was deducted twice
     *         — once here and once there. Bounding it at today makes the two sets disjoint by
     *         construction rather than by anyone remembering.
     * Result: emits on every change. An all-zero breakdown on a month with no transactions, which
     *         `NatureBreakdown.isEmpty` reports so the screen renders an empty state rather than a
     *         bar of zeroes the app made up (P-03).
     * Input:  none — the active profile. Output: `Flow<NatureBreakdown>`.
     * Changelog: 2026-08-10 — Created for issue 4.3.
     *            2026-08-16 — Issue 5.2: bounded at today (FR-TXN-010), see above.
     */
    fun observeNatureBreakdown(): Flow<NatureBreakdown>

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
         * How many rows [observeFiltered] loads per page (issue 3.6; FR-TXN-007).
         *
         * Not a financial threshold, so §29's data-not-code rule does not reach it — it is the size
         * of a read. Chosen to comfortably overfill a phone screen so the user never sees the list
         * catch up with them, while staying small enough that the first page renders immediately on
         * a ledger of any size. Paging's prefetch does the rest.
         */
        const val PAGE_SIZE = 40

        /**
         * The [IdGenerator] prefix for tag ids (issue 3.6).
         *
         * Distinct from [ID_PREFIX] so a database dump reads as itself: `tag:4` is plainly a label,
         * not a transaction.
         */
        const val TAG_ID_PREFIX = "tag"

        /**
         * The [IdGenerator] prefix for the rows joining a tag to a transaction (issue 3.6).
         *
         * Distinct again: `txtag:4` is a link, and the three tables are read side by side often
         * enough that telling them apart at a glance is worth two extra characters.
         */
        const val TRANSACTION_TAG_ID_PREFIX = "txtag"

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
    /**
     * Where this row came from (issue 3.8; FR-TXN-009).
     *
     * Why a **default** rather than a required argument: every existing caller is the user typing a
     * transaction by hand, and making them all say so would be three edits that change nothing. The
     * receipt scanner passes `OCR`, and issue 3.9's SMS parser will pass `SMS`.
     *
     * Deliberately still not something the *user* picks — FR-TXN-009 is provenance, and a provenance
     * a person can choose is a claim rather than a record. The add-transaction screen never sets it.
     */
    val source: TransactionSource = TransactionSource.MANUAL,
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
// TooManyFunctions: see the interface — one implementation per gateway (ARC-003/ARC-005).
// LongParameterList: seven collaborators, each of which the class is the *only* legitimate holder of
// (issue 4.3 added the nature engine). Grouping them behind a wrapper would hide which reads and
// which decides, which is the distinction ARC-005 turns on.
@Suppress("TooManyFunctions", "LongParameterList")
internal class RoomTransactionRepository(
    private val database: CfoDatabase,
    private val clock: Clock,
    private val ids: IdGenerator,
    private val dispatchers: DispatcherProvider,
    private val activeProfileId: Flow<String>,
    private val classifier: ClassificationEngine,
    private val natureEngine: NatureEngine,
) : TransactionRepository {
    override fun observeFiltered(filter: TransactionFilter): Flow<PagingData<FilteredTransaction>> =
        // flatMapLatest, not map: entering or leaving the demo must *switch* which query is being
        // observed, cancelling the previous one. A `map` would leave the old profile's Flow running
        // and the screen showing the transactions it was already showing.
        activeProfileId.flatMapLatest { profileId ->
            val bounded = filter.boundedToActuals(clock)
            Pager(
                // No `enablePlaceholders`: it needs a total row count, which means a second COUNT
                // query on every invalidation, to render rows the user cannot read anyway. The list
                // simply grows as pages arrive.
                config = PagingConfig(pageSize = TransactionRepository.PAGE_SIZE, enablePlaceholders = false),
                // The factory is re-invoked by Paging on every invalidation, so the query is rebuilt
                // from the same filter after each write rather than going stale.
                pagingSourceFactory = { database.transactionDao().pagedFiltered(profileId, bounded) },
            ).flow
                // mapNotNull, not map: a row whose `source` or `type` this build does not recognise
                // is dropped rather than thrown on, so an old build reading a newer database shows
                // fewer rows instead of crashing the list.
                // flatMap over `listOfNotNull`, because PagingData has no `mapNotNull` and the
                // alternative — filter, then map with a `!!` — runs the mapper twice per row.
                .map { page -> page.flatMap { listOfNotNull(it.toFilteredTransaction()) } }
                .flowOn(dispatchers.io)
        }

    override fun observeDayTotals(filter: TransactionFilter): Flow<Map<String, Money>> =
        activeProfileId.flatMapLatest { profileId ->
            database.transactionDao().observeDayTotals(profileId, filter.boundedToActuals(clock))
                // `Money`, not the raw Long, at the data layer's edge: every arithmetic on it above
                // this line is then overflow-checked (MNY-001).
                .map { rows -> rows.associate { it.isoDate to Money(it.totalMinor) } }
                .flowOn(dispatchers.io)
        }

    override fun observeRecent(limit: Int): Flow<List<FilteredTransaction>> =
        activeProfileId.flatMapLatest { profileId ->
            database.transactionDao().observeRecent(profileId, clock.today().toString(), limit)
                .map { rows -> rows.mapNotNull { it.toFilteredTransaction() } }
                .flowOn(dispatchers.io)
        }

    override fun observeMonthCashFlow(): Flow<CashFlowSummary> =
        activeProfileId.flatMapLatest { profileId ->
            // The same window observeNatureBreakdown resolves, and read the same way: once per
            // subscription, inside flatMapLatest, so entering or leaving the demo rebuilds it
            // (TIM-001/TIM-002).
            val month = MonthWindow.current(clock.today())
            database.transactionDao()
                .observeMonthCashFlow(profileId, month.startIsoDate, month.actualsEndIsoDate)
                .map { row ->
                    val income = Money(row.incomeMinor)
                    val expense = Money(row.expenseMinor)
                    CashFlowSummary(income = income, expense = expense, net = income - expense)
                }.flowOn(dispatchers.io)
        }

    override fun observeSources(): Flow<List<TransactionSource>> =
        activeProfileId.flatMapLatest { profileId ->
            database.transactionDao().observeDistinctSources(profileId)
                .map { stored ->
                    val present = stored.mapNotNullTo(mutableSetOf()) { TransactionSource.fromStored(it) }
                    // Enum order rather than the order SQLite returned, so chips keep their
                    // positions as rows arrive rather than rearranging under the user's finger.
                    TransactionSource.entries.filter { it in present }
                }
                .flowOn(dispatchers.io)
        }

    override fun observeTags(): Flow<List<Tag>> =
        activeProfileId.flatMapLatest { profileId ->
            database.tagDao().observeForProfile(profileId)
                .map { rows -> rows.map { it.toTag() } }
                .flowOn(dispatchers.io)
        }

    override fun observeUpcoming(): Flow<List<Transaction>> =
        activeProfileId.flatMapLatest { profileId ->
            val today = clock.today()
            database.transactionDao()
                .observeBookedBetweenWithSplits(
                    profileId = profileId,
                    // Tomorrow, not today: today's rows are actuals and belong to the main list.
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
                .map { rows -> rows.mapNotNull { it.toCategory() } }
                .flowOn(dispatchers.io)
        }

    override suspend fun suggestCategory(merchant: String): Result<CategorySuggestion?, AppError> {
        // Normalised here, not in the query, so the empty case costs no database round trip and —
        // more to the point — so the same function the engine matches with is the one the SQL
        // argument is built from. Two normalisations would mean the user's own correction quietly
        // stopped being found while the knowledge base kept matching.
        val normalised = normaliseMerchant(merchant)
        if (normalised.isEmpty()) return Ok(null)
        // Read first, decide second, and keep the two in separate steps: the database call is what
        // can fail, and the engine call is what cannot. Wrapping both in `runCatchingToResult` would
        // turn a rule-set bug into an `Err(Storage)` blamed on the disk.
        val read =
            withContext(dispatchers.io) {
                runCatchingToResult {
                    val profileId = activeProfileId.first()
                    val history =
                        database.transactionDao().categoryCountsForMerchant(profileId, normalised)
                            // The column is nullable so the projection is; the query's WHERE has
                            // already excluded nulls, and mapNotNull states that rather than
                            // asserting it with a `!!`.
                            .mapNotNull { row ->
                                row.categoryId?.let { MerchantHistoryRow(categoryId = it, count = row.occurrences) }
                            }
                    ClassificationInput(
                        merchant = merchant,
                        categories =
                            database.categoryDao().observeForProfile(profileId).first()
                                .mapNotNull { it.toCategory() },
                        // TIM-001: the engine reads no clock, so the instant it stamps into
                        // provenance is this one.
                        nowUtcMillis = clock.nowUtcMillis(),
                        history = history,
                    )
                }
            }
        return when (read) {
            is Err -> read
            is Ok -> classifier.suggest(read.value)
        }
    }

    override suspend fun natureOf(transactionId: String): Result<NatureVerdict, AppError> {
        val read =
            withContext(dispatchers.io) {
                runCatchingToResult {
                    val profileId = activeProfileId.first()
                    val row = database.transactionDao().natureCandidate(transactionId)
                    row?.let { candidate -> natureInput(profileId, candidate)?.let { candidate to it } }
                }
            }
        return when (read) {
            is Err -> read
            is Ok -> {
                // `null` covers two cases and answers both the same way: the id names nothing live,
                // or the row's stored type is one this build does not recognise. **A row this build
                // cannot read is, to this build, not there** — the same forward-compatible choice
                // `CategoryEntity.toCategory` makes, and better than a guessed nature.
                val (row, input) = read.value ?: return Err(AppError.NotFound)
                val chosen = row.overrideNature?.let { CategoryNature.fromStored(it) }
                // The override short-circuits, and says so: the card reads "you set this" rather
                // than citing a rule that did not decide anything (P-02).
                if (chosen != null) Ok(userVerdict(chosen, input)) else natureEngine.classify(input)
            }
        }
    }

    override suspend fun setNature(
        transactionId: String,
        nature: CategoryNature?,
    ): Result<Unit, AppError> =
        withContext(dispatchers.io) {
            runCatchingToResult {
                val changed =
                    database.transactionDao().setNature(
                        transactionId = transactionId,
                        nature = nature?.storedValue,
                        updatedAtUtcMillis = clock.nowUtcMillis(),
                    )
                if (changed == 0) Err(AppError.NotFound) else Ok(Unit)
            }.flattenNature()
        }

    override fun observeNatureBreakdown(): Flow<NatureBreakdown> =
        activeProfileId.flatMapLatest { profileId ->
            // The month in the profile zone, resolved once per subscription rather than per row
            // (TIM-001/TIM-002). A form left open across a month boundary re-resolves when the
            // profile or the data changes, which is the same freshness every other query here has.
            //
            // `actualsEndIsoDate`, not the month's last day: this is an actuals read, and a payment
            // scheduled for the 28th is not money spent on the 3rd (FR-TXN-010). See the interface's
            // doc comment for the double-count this also removes (issue 5.2).
            val month = MonthWindow.current(clock.today())
            database.transactionDao()
                .observeNatureCandidates(profileId, month.startIsoDate, month.actualsEndIsoDate)
                .map { rows -> breakdownOf(profileId, rows) }
                .flowOn(dispatchers.io)
        }

    /**
     * Classifies a month and folds it (issue 4.3; §8.3).
     *
     * Why:    **the merchant overrides are fetched once for the whole month**, not per row. Asking
     *         `natureCountsForMerchant` per transaction would be one query per row on the screen the
     *         user opens first; one grouped read and a map gives the learned step the same answer
     *         for a fraction of the work.
     *
     *         **Step 6 is deliberately not applied here**, and it costs nothing: the modifier only
     *         lowers a verdict's confidence and never changes its nature, and a breakdown sums
     *         natures. Applying it would mean a median query per category per month to change no
     *         figure at all.
     * Result: the month's totals. Input: [profileId]; [rows] — the month's candidates.
     * Output: [NatureBreakdown].
     * Changelog: 2026-08-10 — Created for issue 4.3.
     */
    private suspend fun breakdownOf(
        profileId: String,
        rows: List<NatureCandidateRow>,
    ): NatureBreakdown {
        val overrides =
            database.transactionDao().natureOverridesByMerchant(profileId)
                .groupBy({ it.merchant.orEmpty() }) { it }
        val contributions =
            rows.mapNotNull { row ->
                val type = TransactionType.fromStored(row.type) ?: return@mapNotNull null
                val nature = row.resolvedNature(overrides) ?: return@mapNotNull null
                NatureContribution(type = type, amount = Money(row.amountMinor), nature = nature)
            }
        return natureBreakdown(contributions)
    }

    /**
     * The nature of one row inside the monthly fold.
     * Why:    the same precedence [natureOf] applies — a stored override first, the decision order
     *         otherwise — expressed once so the dashboard's totals and the detail sheet's label can
     *         never disagree about a transaction.
     * Result: the nature, or `null` when the row's type is one this build does not recognise, which
     *         is dropped rather than guessed at (the rule every mapper in this module follows).
     * Input:  the receiver; [overrides] — the profile's merchant overrides, by normalised merchant.
     * Output: `CategoryNature?`.
     * Changelog: 2026-08-10 — Created for issue 4.3.
     */
    private fun NatureCandidateRow.resolvedNature(
        overrides: Map<String, List<MerchantNatureOverrideRow>>,
    ): CategoryNature? {
        val stored = overrideNature?.let { CategoryNature.fromStored(it) }
        val input =
            if (stored != null) {
                null
            } else {
                natureInputOf(
                    row = this,
                    history = overrides[normaliseMerchant(merchant.orEmpty())].orEmpty().toHistory(),
                    median = null,
                )
            }
        return stored ?: input?.let { (natureEngine.classify(it) as? Ok)?.value?.nature }
    }

    /**
     * Assembles the engine's input for one transaction, fetching what only this class may fetch.
     * Why:    split from [natureOf] so the joins and the decision stay separable — and because the
     *         median is the one part of the input that costs a query and is only worth it for a
     *         single row (see [breakdownOf]).
     * Result: the input. Input: [profileId]; [row]. Output: `NatureInput?` — `null` for a row whose
     *         stored type or account type this build does not recognise.
     * Changelog: 2026-08-10 — Created for issue 4.3.
     */
    private suspend fun natureInput(
        profileId: String,
        row: NatureCandidateRow,
    ): NatureInput? {
        val history =
            database.transactionDao()
                .natureCountsForMerchant(profileId, normaliseMerchant(row.merchant.orEmpty()))
                .mapNotNull { counted ->
                    counted.nature?.let { CategoryNature.fromStored(it) }
                        ?.let { NatureHistoryRow(nature = it, count = counted.occurrences) }
                }
        val median =
            row.categoryId?.let { categoryId ->
                val stats = database.transactionDao().categoryMedian(profileId, categoryId)
                // The sample-size guard is the rules', not this query's: a median over two
                // transactions is not a typical amount, it is the two amounts.
                stats.medianMinor
                    ?.takeIf { stats.sampleSize >= NatureRules().minHistoryForMedian }
                    ?.let { Money(it) }
            }
        return natureInputOf(row = row, history = history, median = median)
    }

    /**
     * Builds a [NatureInput] from a row and the two things a query had to supply.
     * Why:    the one place the stored strings become typed values, so an unrecognised account type
     *         or transaction type is dropped in a single place rather than defaulting differently in
     *         two — an old build reading a newer database classifies fewer rows, never wrong ones.
     * Result: the input, or `null` when the row cannot be typed.
     * Input:  [row]; [history]; [median]. Output: `NatureInput?`.
     * Changelog: 2026-08-10 — Created for issue 4.3.
     */
    private fun natureInputOf(
        row: NatureCandidateRow,
        history: List<NatureHistoryRow>,
        median: Money?,
    ): NatureInput? {
        val accountType = AccountType.fromStored(row.accountType) ?: return null
        val type = TransactionType.fromStored(row.type) ?: return null
        return NatureInput(
            accountType = accountType,
            type = type,
            amount = Money(row.amountMinor),
            nowUtcMillis = clock.nowUtcMillis(),
            counterpartAccountType = row.counterpartAccountType?.let { AccountType.fromStored(it) },
            merchantHistory = history,
            categoryNature = row.categoryNature?.let { CategoryNature.fromStored(it) },
            categoryMedian = median,
        )
    }

    /**
     * The verdict for a transaction the user has already decided about (§8.3, P-02, P-07).
     * Why:    built here rather than by the engine because the engine's job is to *derive* a nature,
     *         and there is nothing to derive — the user said so. It still carries provenance, citing
     *         §8.3.1's learned step, so the card can name a reason and a stored verdict stays
     *         reproducible (AI-ARC-006).
     * Result: a full-confidence verdict. Input: [nature] — what the user chose; [input] — for the
     *         instant and the rules. Output: [NatureVerdict].
     * Changelog: 2026-08-10 — Created for issue 4.3.
     */
    private fun userVerdict(
        nature: CategoryNature,
        input: NatureInput,
    ): NatureVerdict =
        NatureVerdict(
            nature = nature,
            provenance =
                EngineProvenance(
                    engineId = "nature-override",
                    engineVersion = "1.0",
                    computedAtUtcMillis = input.nowUtcMillis,
                    evidence = listOf(NatureRules.USER_HISTORY),
                    confidenceBps = BPS_FULL,
                ),
            rules = input.rules,
        )

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
                        // FR-TXN-009. Defaults to `manual` on the draft, which is what every screen
                        // that lets a person type a transaction leaves it as; the receipt scanner
                        // (issue 3.8) passes `ocr` and issue 3.9's SMS parser will pass `sms`.
                        source = validated.source.storedValue,
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
                val now = clock.nowUtcMillis()
                // One transaction around every row that goes (DB-004): a parent gone without its
                // lines, or one transfer leg without the other, is a half-deleted record.
                database.withTransaction { softDeleteOne(transactionId, now) }
            }.requireRowTouched()
        }

    /**
     * Soft-deletes one transaction, its lines, and its sibling transfer leg.
     * Why:    extracted so [delete] and [deleteAll] are **the same code**, not two implementations
     *         of "deleting one side deletes both" that can drift. A bulk delete that forgot the
     *         sibling would leave money that came from nowhere sitting in the destination account —
     *         and it would be a bug that only appears when the user selects rather than swipes.
     * Result: rows touched — `0` when the id names nothing live, which is what makes a repeated
     *         delete report honestly rather than claiming success twice.
     * Input:  [transactionId] — either leg of a transfer; [nowUtcMillis] — the tombstone stamp,
     *         from the injected `Clock` (TIM-001). **Must be called inside a `withTransaction`.**
     * Output: [Int].
     * Changelog: 2026-08-04 — Extracted from `delete` for issue 3.6 (FR-TXN-008).
     */
    private suspend fun softDeleteOne(
        transactionId: String,
        nowUtcMillis: Long,
    ): Int {
        val existing = database.transactionDao().findById(transactionId) ?: return 0
        // The transfer case is one statement, not a read-then-delete-each loop, so there is no
        // window in which one leg is gone and the other is not (FR-TXN-003).
        val touched =
            existing.transferId
                ?.let { database.transactionDao().softDeleteTransfer(it, nowUtcMillis) }
                ?: database.transactionDao().softDelete(transactionId, nowUtcMillis)
        // Only when the parent actually went: a repeated delete must stay a no-op all the way down
        // rather than quietly re-stamping the lines' tombstones.
        if (touched > 0) {
            database.transactionSplitDao().softDeleteForTransaction(transactionId, nowUtcMillis)
        }
        return touched
    }

    override suspend fun recategoriseAll(
        ids: List<String>,
        categoryId: String?,
    ): Result<Int, AppError> {
        // Rejected before `withContext`, so an empty selection costs no thread switch and — more to
        // the point — cannot have written anything by the time it is rejected.
        if (ids.isEmpty()) return Err(AppError.Validation("ids"))
        val now = clock.nowUtcMillis()
        return withContext(dispatchers.io) {
            runCatchingToResult {
                // One statement, so no window exists in which half the selection is recategorised.
                // Transfer legs and split parents are excluded by the query itself (FR-TXN-003,
                // FR-TXN-004) rather than filtered here, so the invariant cannot be forgotten.
                database.transactionDao().recategoriseAll(ids, categoryId, now)
            }
        }
    }

    override suspend fun retagAll(
        ids: List<String>,
        tagNames: List<String>,
    ): Result<Int, AppError> {
        if (ids.isEmpty()) return Err(AppError.Validation("ids"))
        // Trimmed, blank-stripped and de-duplicated case-insensitively before anything is written:
        // " Travel " and "travel" are one label, and two rows for them would split a tag's
        // transactions across two chips the user cannot tell apart.
        val names =
            tagNames.map { it.trim() }
                .filter { it.isNotBlank() }
                .distinctBy { it.lowercase() }
        val now = clock.nowUtcMillis()
        return withContext(dispatchers.io) {
            runCatchingToResult {
                val profileId = activeProfileId.first()
                // One transaction around the tags, the clear and the attach (DB-004): a retag that
                // cleared and then failed would silently untag the user's selection.
                database.withTransaction { writeTags(ids, names, profileId, now) }
            }
        }
    }

    /**
     * Clears the selection's tags and attaches the named ones, creating any that are new.
     * Why:    split out of [retagAll] so the part that must run inside `withTransaction` is one
     *         readable block. **Existing tags are looked up before any are minted** — the unique
     *         index on `(profile_id, name)` would reject a duplicate anyway, but failing a bulk
     *         retag because the user already had `travel` would be absurd.
     * Result: the transactions carry exactly [names]; the count of transactions retagged.
     * Input:  [transactionIds] — the selection; [names] — already trimmed and de-duplicated;
     *         [profileId] — the active profile; [nowUtcMillis] — from the injected `Clock`
     *         (TIM-001). Output: [Int].
     * Changelog: 2026-08-04 — Created for issue 3.6 (FR-TXN-008).
     */
    private suspend fun writeTags(
        transactionIds: List<String>,
        names: List<String>,
        profileId: String,
        nowUtcMillis: Long,
    ): Int {
        val dao = database.tagDao()
        // Cleared first, so the operation is "these transactions now carry exactly these tags"
        // rather than an accumulation — which is what makes applying it twice a no-op.
        dao.detachAllFor(transactionIds)
        if (names.isEmpty()) return transactionIds.size

        val existing = dao.findByNames(profileId, names.map { it.lowercase() })
        val byLoweredName = existing.associateBy { it.name.lowercase() }
        val created =
            names.filterNot { it.lowercase() in byLoweredName }
                .map { name ->
                    TagEntity(
                        id = ids.newId(TransactionRepository.TAG_ID_PREFIX),
                        profileId = profileId,
                        name = name,
                        createdAtUtcMillis = nowUtcMillis,
                        updatedAtUtcMillis = nowUtcMillis,
                    )
                }
        dao.upsertAll(created)

        val tagIds = existing.map { it.id } + created.map { it.id }
        dao.attachAll(
            transactionIds.flatMap { transactionId ->
                tagIds.map { tagId ->
                    TransactionTagEntity(
                        id = ids.newId(TransactionRepository.TRANSACTION_TAG_ID_PREFIX),
                        profileId = profileId,
                        transactionId = transactionId,
                        tagId = tagId,
                        createdAtUtcMillis = nowUtcMillis,
                        updatedAtUtcMillis = nowUtcMillis,
                    )
                }
            },
        )
        return transactionIds.size
    }

    override suspend fun deleteAll(ids: List<String>): Result<List<String>, AppError> {
        if (ids.isEmpty()) return Err(AppError.Validation("ids"))
        val now = clock.nowUtcMillis()
        return withContext(dispatchers.io) {
            runCatchingToResult {
                // One transaction around every row that goes (DB-004): a bulk delete that applied
                // to half a selection is a state no screen can render honestly.
                database.withTransaction {
                    // Read the siblings **before** deleting: afterwards every leg is a tombstone and
                    // the query that finds live ones would return nothing, so the undo batch would
                    // silently omit exactly the rows FR-TXN-003 pulled in.
                    val affected = (ids + database.transactionDao().findTransferSiblingIds(ids)).distinct()
                    val touched = ids.sumOf { softDeleteOne(it, now) }
                    // Null, not an empty list: `flatMapPresent` turns it into `Err(NotFound)`, which
                    // is the honest answer when nothing named was live — including a repeat.
                    if (touched > 0) affected else null
                }
            }.flatMapPresent()
        }
    }

    override suspend fun restoreAll(ids: List<String>): Result<Int, AppError> {
        if (ids.isEmpty()) return Err(AppError.Validation("ids"))
        val now = clock.nowUtcMillis()
        return withContext(dispatchers.io) {
            runCatchingToResult {
                database.withTransaction {
                    val restored = database.transactionDao().restoreAll(ids, now)
                    // The lines come back with their parents, in the same transaction: a restored
                    // split whose lines stayed deleted would be an amount attributed to nothing.
                    if (restored > 0) {
                        ids.forEach { database.transactionSplitDao().restoreForTransaction(it, now) }
                    }
                    restored
                }
            }.requireAnyRowTouched()
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
 * Why:    the add screen needs an id and a label to render a chip; issue 4.1's editor needs the
 *         nature it groups by and the parent it indents under. One mapper serves both, so the two
 *         screens cannot end up with different ideas of what a category is.
 * Result: a [Category], or `null` when `nature` holds a value this build does not know — the same
 *         forward-compatible shape [TransactionSource.fromStored] uses, and the reason this mapper
 *         gained a failure case in 4.1 when it had none in 3.1. **A dropped category is a chip that
 *         does not appear; a guessed one is a category silently in the wrong 50/30/20 band.**
 * Input:  the receiver. Output: `Category?`.
 * Changelog: 2026-08-02 — Created for issue 3.1.
 *            2026-08-08 — Issue 4.1: carries `nature`, `parent_id` and `is_system`, and became
 *            nullable because `nature` is now a closed set.
 */
internal fun CategoryEntity.toCategory(): Category? =
    CategoryNature.fromStored(nature)?.let { parsed ->
        Category(id = id, name = name, nature = parsed, parentId = parentId, isSystem = isSystem)
    }
