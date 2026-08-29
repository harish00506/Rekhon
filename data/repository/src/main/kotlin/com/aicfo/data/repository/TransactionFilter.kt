package com.aicfo.data.repository

import androidx.paging.PagingSource
import com.aicfo.core.common.Clock
import com.aicfo.core.database.dao.DayTotalRow
import com.aicfo.core.database.dao.TransactionDao
import com.aicfo.core.database.dao.TransactionListRow
import com.aicfo.core.database.entity.TagEntity
import com.aicfo.core.model.Money
import com.aicfo.core.model.MoneyFormatter
import com.aicfo.core.model.Tag
import com.aicfo.core.model.Transaction
import com.aicfo.core.model.TransactionSource
import com.aicfo.core.model.TransactionType
import kotlinx.coroutines.flow.Flow
import java.time.LocalDate

/**
 * Everything the user can narrow the transaction list by (issue 3.6; FR-TXN-007).
 *
 * Why:  FR-TXN-007 lists seven facets — "search (payee, note, amount, tag), filters (date range,
 *       account, category, type, amount range)". Passing them as one value rather than as eleven
 *       parameters is what lets the ViewModel hold "the current filter" as a single `StateFlow` and
 *       `flatMapLatest` on it: one changed chip produces one new query, and there is no way to
 *       update one facet while another is stale.
 * What: one nullable field per facet.
 * Result: `TransactionFilter()` — every field null — is the unfiltered whole ledger, which is what
 *       the list opens on.
 * Changelog: 2026-08-04 — Created for issue 3.6 (FR-TXN-007).
 *
 * **`null` means "do not filter on this", never "match nothing".** Every clause in the DAO query is
 * `(:param IS NULL OR …)`, so an absent facet costs nothing and cannot accidentally exclude rows.
 *
 * **[minAmount] and [maxAmount] are bounds on the *magnitude*.** A user asking for transactions
 * between ₹100 and ₹500 means the size of the movement, not its signed value — under a signed
 * comparison every expense in that range would be excluded, because an expense is negative.
 *
 * Input:  [query] — free text matched against payee, note, tag and (when it parses as one) amount;
 *         [accountId]; [categoryId]; [type] — §20.2's stored vocabulary; [source] — FR-TXN-009's;
 *         [tagId]; [minAmount]/[maxAmount] — MNY-001 paise, magnitudes; [fromDate]/[toDate] —
 *         inclusive profile-zone days (TIM-002).
 * Output: an immutable value.
 */
data class TransactionFilter(
    val query: String? = null,
    val accountId: String? = null,
    val categoryId: String? = null,
    val type: TransactionType? = null,
    val source: TransactionSource? = null,
    val tagId: String? = null,
    val minAmount: Money? = null,
    val maxAmount: Money? = null,
    val fromDate: LocalDate? = null,
    val toDate: LocalDate? = null,
) {
    /** Whether anything at all is narrowing the list — what tells "no rows" from "no matches". */
    val isActive: Boolean
        get() =
            searchTerm != null || accountId != null || categoryId != null || type != null ||
                source != null || tagId != null || minAmount != null || maxAmount != null ||
                fromDate != null || toDate != null

    /**
     * The search text once it is worth searching for, else `null`.
     *
     * Blank is not a search — an empty field and a field holding three spaces both mean "show
     * everything", and treating whitespace as a term would empty the list for a stray keystroke.
     */
    val searchTerm: String? get() = query?.trim()?.takeIf { it.isNotBlank() }

    /**
     * The search text as a SQL `LIKE` pattern, or `null` when there is nothing to search for.
     *
     * Why:    **the user's text is data, not syntax.** `%` and `_` are wildcards to SQLite, so a
     *         search for `50%` would otherwise match every row beginning `50` — and a backslash
     *         would break the escape itself. Each is escaped with `\`, which the query declares via
     *         `ESCAPE '\'`. Room parameterises the value, so this is not an injection guard; it is
     *         what makes the search mean what the user typed.
     *
     *         The backslash is replaced **first**. Doing it after would escape the backslashes this
     *         function had just inserted, turning `50%` into `50\\%` — a literal backslash followed
     *         by a wildcard, which is the bug this ordering exists to avoid.
     * Result: `%term%` with wildcards neutralised, or `null`.
     * Input:  none — reads [searchTerm]. Output: `String?`.
     * Changelog: 2026-08-04 — Created for issue 3.6.
     */
    val likePattern: String?
        get() =
            searchTerm?.let { term ->
                val escaped =
                    term.replace("\\", "\\\\")
                        .replace("%", "\\%")
                        .replace("_", "\\_")
                "%$escaped%"
            }

    /**
     * The search text read as an amount, in paise, or `null` when it is not one.
     *
     * Why:    FR-TXN-007 asks search to cover "amount", and the honest reading is an **exact** match
     *         on the magnitude: a user typing `250` is looking for the ₹250 they remember, not for
     *         every amount whose digits happen to contain `250` (which a text match would give them
     *         — ₹2.50, ₹1,250 and ₹25,000 alike). Reusing [MoneyFormatter.parse] means the search
     *         box accepts exactly what the amount field does, including `1,23,456.78` (P-06).
     * Result: the absolute value in paise, or `null` for text that is not an amount — which is the
     *         normal case, and switches the amount clause off rather than matching nothing.
     * Input:  none — reads [searchTerm]. Output: `Long?`.
     * Changelog: 2026-08-04 — Created for issue 3.6.
     */
    val queryAmountMinor: Long?
        get() = searchTerm?.let { MoneyFormatter.parse(it) }?.minor?.let { kotlin.math.abs(it) }
}

/**
 * Stops an unbounded filter at today (issue 3.6; FR-TXN-010).
 * Why:    the list's actuals half must not contain a payment that has not happened — FR-TXN-010 is
 *         explicit that future-dated rows are "excluded from actuals", and those rows are rendered
 *         separately from `observeUpcoming`, so returning them here as well would show them twice
 *         and count them in a day total. The old windowed read got this for free from its upper
 *         bound; when the window went, the bound had to be stated.
 *
 *         **An explicit [TransactionFilter.toDate] wins.** A user who filters to next month is
 *         asking for precisely the rows this bound otherwise hides, and overriding their choice
 *         would make the date filter silently useless for any future range.
 * Result: the filter with [TransactionFilter.toDate] defaulted to today in the profile zone.
 * Input:  the receiver; [clock] — the injected clock, never the wall clock (TIM-001).
 * Output: [TransactionFilter].
 * Changelog: 2026-08-04 — Created for issue 3.6.
 */
internal fun TransactionFilter.boundedToActuals(clock: Clock): TransactionFilter =
    if (toDate != null) this else copy(toDate = clock.today())

/**
 * Runs the paged ledger query for one filter (issue 3.6; FR-TXN-007).
 * Why:    the DAO takes twelve parameters because SQL cannot take an object, and expanding the
 *         filter at the two call sites would be two places for the mapping to drift — a facet
 *         wired into the rows but not into the day totals would give every header a figure for a
 *         different set of transactions. This is the one place the expansion is written.
 * Result: the `PagingSource` backing the list.
 * Input:  the receiver — the DAO; [profileId]; [filter]. Output: `PagingSource<Int, TransactionListRow>`.
 * Changelog: 2026-08-04 — Created for issue 3.6.
 */
internal fun TransactionDao.pagedFiltered(
    profileId: String,
    filter: TransactionFilter,
): PagingSource<Int, TransactionListRow> =
    pagedFiltered(
        profileId = profileId,
        queryPattern = filter.likePattern,
        queryAmountMinor = filter.queryAmountMinor,
        accountId = filter.accountId,
        categoryId = filter.categoryId,
        type = filter.type?.storedValue,
        source = filter.source?.storedValue,
        tagId = filter.tagId,
        minAmountMinor = filter.minAmount?.minor,
        maxAmountMinor = filter.maxAmount?.minor,
        fromIsoDate = filter.fromDate?.toString(),
        toIsoDate = filter.toDate?.toString(),
    )

/**
 * Runs the day-totals query for one filter (issue 3.6; FR-TXN-007).
 * Why:    the twin of [pagedFiltered], and deliberately written beside it — the two queries must
 *         describe the same set of transactions or every day header states a total for rows that
 *         are not under it.
 * Result: the day totals stream.
 * Input:  the receiver — the DAO; [profileId]; [filter]. Output: `Flow<List<DayTotalRow>>`.
 * Changelog: 2026-08-04 — Created for issue 3.6.
 */
internal fun TransactionDao.observeDayTotals(
    profileId: String,
    filter: TransactionFilter,
): Flow<List<DayTotalRow>> =
    observeDayTotals(
        profileId = profileId,
        queryPattern = filter.likePattern,
        queryAmountMinor = filter.queryAmountMinor,
        accountId = filter.accountId,
        categoryId = filter.categoryId,
        type = filter.type?.storedValue,
        source = filter.source?.storedValue,
        tagId = filter.tagId,
        minAmountMinor = filter.minAmount?.minor,
        maxAmountMinor = filter.maxAmount?.minor,
        fromIsoDate = filter.fromDate?.toString(),
        toIsoDate = filter.toDate?.toString(),
    )

/**
 * Converts a filtered page row into the domain model (issue 3.6).
 * Why:    ARC-005 — nothing above `:data:repository` may hold a Room type. **Tombstoned lines and
 *         tags are dropped here**, because `@Relation` cannot carry a `WHERE`; doing it at the one
 *         mapping site is what stops a deleted line or an untagged label reappearing downstream.
 *
 *         [TransactionListRow.counterpartAccountId] is deliberately **not** carried onto
 *         [Transaction]: it is a fact about how the list renders one leg of a transfer, not a fact
 *         about the transaction, and putting it on the model would invite an engine to read it. The
 *         repository returns it separately, as [FilteredTransaction].
 * Result: a [FilteredTransaction], or `null` when the stored `source` or `type` is one this build
 *         does not know — the same forward-compatible drop `TransactionEntity.toTransaction` makes.
 * Input:  the receiver. Output: `FilteredTransaction?`.
 * Changelog: 2026-08-04 — Created for issue 3.6 (FR-TXN-007).
 */
internal fun TransactionListRow.toFilteredTransaction(): FilteredTransaction? {
    val model =
        transaction.toTransaction()?.copy(
            splits = splits.filter { it.deletedAtUtcMillis == null }.map { it.toSplit() },
            tags =
                tags.filter { it.deletedAtUtcMillis == null }
                    .map { it.toTag() }
                    .sortedBy { it.name },
        ) ?: return null
    return FilteredTransaction(transaction = model, counterpartAccountId = counterpartAccountId)
}

/**
 * A transaction as the filtered list renders it (issue 3.6; FR-TXN-003, FR-TXN-007).
 *
 * Why:  the list shows a transfer **once**, as "HDFC Savings → Cash Wallet", and with paging it can
 *       no longer find the sibling leg by scanning what it has loaded — the two legs may be pages
 *       apart, or the sibling may be excluded by the filter entirely. So the query projects the
 *       other side's account and this type carries it, beside the transaction rather than on it.
 * What: the domain transaction, plus the one rendering fact that is not part of it.
 * Result: a transfer row can name both accounts without a second lookup, and [Transaction] stays a
 *       description of one movement rather than of a pair.
 * Changelog: 2026-08-04 — Created for issue 3.6.
 *
 * Input:  [transaction]; [counterpartAccountId] — the other leg's account, `null` on every
 *         non-transfer row and on a transfer whose sibling has been deleted.
 * Output: an immutable value.
 */
data class FilteredTransaction(
    val transaction: Transaction,
    val counterpartAccountId: String? = null,
)

/**
 * This month's income, expense and net, as three already-summed figures (issue 5.1; FR-DASH-*).
 *
 * Why:  the dashboard's cash-flow card needs all three at once, and computing [net] on the screen
 *       from the other two would be one Money subtraction happening twice if a second reader ever
 *       needed it. Carrying it pre-folded means every consumer sees the same number.
 * What: [income] and [expense] as non-negative magnitudes (the convention `BudgetStatus.spent`
 *       already uses); [net] signed — negative for a month spent into savings.
 * Result: [TransactionRepository.observeMonthCashFlow]'s output.
 * Changelog: 2026-08-15 — Created for issue 5.1.
 *
 * Input:  [income]; [expense]; [net] — `income - expense`. Output: an immutable value.
 */
data class CashFlowSummary(
    val income: Money,
    val expense: Money,
    val net: Money,
)

/**
 * Converts a tag row into the domain model (issue 3.6).
 * Why:    ARC-005, the same reason `CategoryEntity.toCategory` exists. The timestamps are dropped:
 *         nothing above the data layer asks when a label was created.
 * Result: a [Tag]. Cannot fail — there is no stored vocabulary here to fall off.
 * Input:  the receiver. Output: [Tag].
 * Changelog: 2026-08-04 — Created for issue 3.6.
 */
internal fun TagEntity.toTag(): Tag = Tag(id = id, name = name)
