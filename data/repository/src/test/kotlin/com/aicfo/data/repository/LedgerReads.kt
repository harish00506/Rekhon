package com.aicfo.data.repository

import androidx.paging.testing.asSnapshot
import com.aicfo.core.model.Transaction

/**
 * Reads the whole filtered ledger as a plain list, for assertions (issue 3.6).
 *
 * Why:  every test written before issue 3.6 asserted against `repository.observeRecent().first()` —
 *       a `Flow<List<Transaction>>` that a test could simply take the first emission of. That read
 *       is now paged, and `first()` on a `Flow<PagingData<…>>` gives a value nothing can be asserted
 *       about: `PagingData` is a stream of loading events, not a list. `asSnapshot` from
 *       `paging-testing` drives the pager to completion and hands back what it loaded, which is the
 *       list those assertions were always about.
 *
 *       **One helper rather than `asSnapshot` at thirty call sites**, so the tests keep reading as
 *       statements about transactions rather than about paging.
 * What: collects one snapshot of [TransactionRepository.observeFiltered] and drops the rendering
 *       fact beside each row.
 * Result: the transactions the list would show, newest first.
 * Changelog: 2026-08-04 — Created for issue 3.6, replacing `observeRecent().first()`.
 *
 * **Loads every page**, which is exactly what a test wants and exactly what the screen must not do.
 * A fixture holding more than a page or two would make this slow rather than wrong.
 *
 * Input:  the receiver; [filter] — the unfiltered ledger by default.
 * Output: `List<Transaction>`.
 */
internal suspend fun TransactionRepository.liveTransactions(
    filter: TransactionFilter = TransactionFilter(),
): List<Transaction> = observeFiltered(filter).asSnapshot().map { it.transaction }

/**
 * The same read, keeping each row's transfer counterpart (issue 3.6).
 * Why:    a transfer row's other account is projected by the query rather than paired in Kotlin, and
 *         that projection needs its own assertions — [liveTransactions] deliberately drops it so the
 *         thirty older call sites stay readable.
 * Result: the rows the list would show, counterpart included.
 * Input:  the receiver; [filter]. Output: `List<FilteredTransaction>`.
 * Changelog: 2026-08-04 — Created for issue 3.6.
 */
internal suspend fun TransactionRepository.liveRows(
    filter: TransactionFilter = TransactionFilter(),
): List<FilteredTransaction> = observeFiltered(filter).asSnapshot()
