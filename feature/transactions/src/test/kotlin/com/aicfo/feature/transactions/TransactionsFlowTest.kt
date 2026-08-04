package com.aicfo.feature.transactions

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.paging.PagingData
import androidx.paging.compose.collectAsLazyPagingItems
import com.aicfo.core.designsystem.theme.CfoTheme
import com.aicfo.core.model.Money
import com.aicfo.core.model.Tag
import com.aicfo.core.model.TransactionSource
import com.aicfo.core.model.TransactionType
import com.aicfo.data.repository.TransactionFilter
import kotlinx.coroutines.flow.flowOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Compose tests for the transactions list (issue 3.1; §21.5).
 *
 * Why:  the ViewModel tests prove the grouping and the totals; these prove the screen renders them.
 *       Two things only a rendered test catches: **the amount reaching the screen formatted for
 *       India** — ₹1,23,456.78, not ₹123,456.78 (P-06) — and **the day header being a readable date
 *       rather than the ISO string it is stored as** (TIM-002). Both would pass every state test.
 * What: a populated list, the empty-ish states, the row's title fallback, and — since issue 3.6 —
 *       search, the filter sheet, selection and undo.
 * Result: the screen that makes a save observable is exercised on every `test` run.
 * Changelog: 2026-08-02 — Created for issue 3.1.
 *            2026-08-04 — Issue 3.6: the rows now arrive as `PagingData` rather than on the state,
 *            so every fixture builds a list of [TransactionListItem] instead of a `TransactionDay`.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w400dp-h1200dp")
class TransactionsFlowTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    /** Result: a string from this module's resources. Input: [id]. Output: the resolved text. */
    private fun text(id: Int): String = compose.activity.getString(id)

    @Test
    fun `a transaction renders with its note and an Indian-grouped amount`() {
        setContent(
            items =
                day(
                    transaction { copy(id = "txn:1", note = "Chai", amount = Money(-1_23_456_78L)) },
                    transaction { copy(id = "txn:2", note = "Salary", amount = Money(60_000_00L)) },
                ),
        )

        compose.onNodeWithText("Chai").assertIsDisplayed()
        // P-06: 2,2,3 grouping, which `NumberFormat` does not give reliably — hence MoneyFormatter.
        compose.onNodeWithText("₹1,23,456.78", substring = true).assertIsDisplayed()
        // Positive amounts carry a leading + so the direction is readable without reading the colour.
        compose.onNodeWithText("+₹60,000.00", substring = true).assertIsDisplayed()
    }

    @Test
    fun `the day header is a readable date, not the stored ISO string`() {
        setContent(items = day(transaction()))

        compose.onNodeWithText("2026-08-02").assertDoesNotExist()
        compose.onNodeWithText(TransactionLabels.dayHeader("2026-08-02")).assertIsDisplayed()
    }

    @Test
    fun `a row with neither note nor merchant still says something`() {
        // Every field but the amount is optional (FR-TXN-001), and a row a user cannot identify is
        // a row they cannot decide to delete.
        setContent(items = day(transaction()))

        compose.onNodeWithText(text(R.string.transactions_uncategorised)).assertIsDisplayed()
    }

    @Test
    fun `an empty store invites the user to the FAB`() {
        setContent()

        compose.onNodeWithText(text(R.string.transactions_empty)).assertIsDisplayed()
    }

    @Test
    fun `a failed read shows an error rather than the empty invitation`() {
        // The distinction: a database that would not open must not render as "no transactions yet".
        setContent(state = TransactionsUiState(isLoading = false, errorCode = "storage"))

        compose.onNodeWithText(text(R.string.add_txn_error_storage)).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.transactions_empty)).assertDoesNotExist()
    }

    // --- transfers and delete (issue 3.2; FR-TXN-003) ----------------------------------------------

    @Test
    fun `a transfer renders as one row naming both accounts`() {
        // FR-TXN-003's "single logical record", as pixels: one line, not two, and the two account
        // names carry the direction because a collapsed pair has no single sign to colour.
        setContent(
            state =
                TransactionsUiState(
                    isLoading = false,
                    accountNames = mapOf("account:1" to "HDFC Savings", "account:2" to "Cash Wallet"),
                ),
            items = transferDay(),
        )

        compose.onNodeWithText("HDFC Savings", substring = true).assertIsDisplayed()
        compose.onNodeWithText("Cash Wallet", substring = true).assertIsDisplayed()
    }

    @Test
    fun `a transfer's amount carries no plus sign`() {
        // A leading "+" would claim the user gained money they only moved between their own accounts.
        setContent(
            state =
                TransactionsUiState(
                    isLoading = false,
                    accountNames = mapOf("account:1" to "HDFC Savings", "account:2" to "Cash Wallet"),
                ),
            items = transferDay(),
        )

        compose.onNodeWithText("₹5,000.00", substring = true).assertIsDisplayed()
        compose.onNodeWithText("+₹5,000.00", substring = true).assertDoesNotExist()
    }

    @Test
    fun `an account id with no name falls back to the id rather than an empty arrow`() {
        setContent(items = transferDay())

        compose.onNodeWithText("account:1", substring = true).assertIsDisplayed()
    }

    @Test
    fun `tapping delete on a transfer hands up one of its leg ids`() {
        val events = mutableListOf<TransactionsEvent>()
        setContent(items = transferDay(), onEvent = { events += it })

        compose.onNodeWithContentDescription(text(R.string.transactions_delete)).performClick()

        // A transaction id, not the transfer id: the repository decides whether a sibling goes too.
        assertEquals(listOf(TransactionsEvent.Delete("tfr:1:out")), events)
    }

    @Test
    fun `an ordinary row has a delete action too`() {
        val events = mutableListOf<TransactionsEvent>()
        setContent(items = day(transaction()), onEvent = { events += it })

        compose.onNodeWithContentDescription(text(R.string.transactions_delete)).performClick()

        assertEquals(listOf(TransactionsEvent.Delete("txn:1")), events)
    }

    // --- splits (issue 3.3; FR-TXN-004) ------------------------------------------------------------

    @Test
    fun `a split row says how many lines it has, and still shows one amount`() {
        // The parent holds all the money, so listing the lines here would show it twice. The row
        // says how many categories the amount is spread across instead.
        setContent(items = day(splitTransaction(lines = 2), transaction()))

        compose.onNodeWithText("2 lines", substring = true).assertIsDisplayed()
        // Once, not twice: splitting does not change what the transaction is worth.
        compose.onNodeWithText("-₹1,000.00", substring = true).assertIsDisplayed()
    }

    @Test
    fun `an unsplit row says nothing about lines`() {
        setContent(items = day(transaction()))

        compose.onNodeWithText("line", substring = true).assertDoesNotExist()
    }

    // --- future-dated transactions (issue 3.4; FR-TXN-010) -----------------------------------------

    @Test
    fun `a scheduled row is labelled, so no user has to guess whether the money has gone`() {
        setContent(
            state = TransactionsUiState(isLoading = false, upcoming = listOf(scheduledDay())),
            items = day(transaction { copy(note = "Chai") }),
        )

        compose.onNodeWithText(text(R.string.transactions_scheduled)).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.transactions_posted)).assertIsDisplayed()
        compose.onNodeWithText("Rent", substring = true).assertIsDisplayed()
    }

    @Test
    fun `a scheduled day carries no total`() {
        // A day total is a statement about money that has moved. Printing one over rows that have
        // not moved would invite exactly the reading FR-TXN-010 exists to prevent, and it would
        // reconcile with no balance on any other screen.
        setContent(state = TransactionsUiState(isLoading = false, upcoming = listOf(scheduledDay())))

        compose.onNodeWithContentDescription(text(R.string.transactions_day_total)).assertDoesNotExist()
        // The row's own amount is still shown — it is the aggregate that is withheld, not the figure.
        compose.onNodeWithText("-₹25,000.00", substring = true).assertIsDisplayed()
    }

    @Test
    fun `neither section heading appears when nothing is scheduled`() {
        // The common case: most users schedule nothing, and a "Posted" heading over the only list on
        // screen would be noise that says nothing.
        setContent(items = day(transaction()))

        compose.onNodeWithText(text(R.string.transactions_scheduled)).assertDoesNotExist()
        compose.onNodeWithText(text(R.string.transactions_posted)).assertDoesNotExist()
    }

    @Test
    fun `a scheduled row can still be deleted`() {
        // A payment scheduled by mistake must be removable before it ever counts, without waiting
        // for its date to arrive.
        val events = mutableListOf<TransactionsEvent>()
        setContent(
            state = TransactionsUiState(isLoading = false, upcoming = listOf(scheduledDay())),
            onEvent = { events += it },
        )

        compose.onNodeWithContentDescription(text(R.string.transactions_delete)).performClick()

        assertEquals(listOf(TransactionsEvent.Delete("txn:rent")), events)
    }

    // --- source tracking (issue 3.5; FR-TXN-009, P-02) ---------------------------------------------

    @Test
    fun `a reconciliation row says the app posted it, instead of reading as anonymous`() {
        // **The row issue 3.5 exists for.** Its note is deliberately null (issue 2.7), so before it
        // rendered as "Uncategorised -Rs 500.00" with nothing saying the app posted it to close a
        // gap against a statement. On that row the source is the only explanation there is.
        setContent(items = day(transaction { copy(source = TransactionSource.RECONCILIATION) }))

        compose.onNodeWithText(text(R.string.transactions_source_reconciliation)).assertIsDisplayed()
    }

    @Test
    fun `a hand-typed row carries no source label at all`() {
        // Manual is the default and the overwhelming majority; tagging every row "Manual" would bury
        // the few labels that carry information.
        setContent(items = day(transaction()))

        compose.onNodeWithText(text(R.string.transactions_source_manual)).assertDoesNotExist()
    }

    @Test
    fun `a row that is both imported and split says both, provenance first`() {
        // One supporting slot, two things wanting it. Provenance leads because it explains what the
        // row *is*; the line count only describes how it was categorised.
        setContent(
            items = day(splitTransaction(lines = 2).copy(source = TransactionSource.IMPORT), transaction()),
        )

        compose.onNodeWithText("${text(R.string.transactions_source_import)} · 2 lines").assertIsDisplayed()
    }

    @Test
    fun `one source means no chip row at all`() {
        // The profile every real user has today is entirely hand-typed, and pays nothing for this
        // feature. (Two tests rather than one: `setContent` may be called only once per test — the
        // mistake issue 3.3 recorded.)
        setContent(items = day(transaction()))

        compose.onNodeWithText(text(R.string.transactions_source_all)).assertDoesNotExist()
    }

    @Test
    fun `two sources bring out the chip row`() {
        setContent(
            state =
                TransactionsUiState(
                    isLoading = false,
                    availableSources = listOf(TransactionSource.MANUAL, TransactionSource.DEMO),
                ),
            items = day(transaction()),
        )

        compose.onNodeWithText(text(R.string.transactions_source_all)).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.transactions_source_demo)).assertIsDisplayed()
    }

    @Test
    fun `tapping a chip asks for that source, and tapping it again clears the filter`() {
        val events = mutableListOf<TransactionsEvent>()
        setContent(
            state =
                TransactionsUiState(
                    isLoading = false,
                    availableSources = listOf(TransactionSource.MANUAL, TransactionSource.DEMO),
                    filter = TransactionFilter(source = TransactionSource.DEMO),
                ),
            items = day(transaction()),
            onEvent = { events += it },
        )

        // Already selected, so this clears rather than re-selecting — the same behaviour the
        // category chips have, and it saves a trip to "All".
        compose.onNodeWithText(text(R.string.transactions_source_demo)).performClick()

        assertEquals(listOf(TransactionsEvent.SourceFilterSelected(null)), events)
    }

    @Test
    fun `a filter matching nothing says so, rather than inviting a first transaction`() {
        setContent(
            state =
                TransactionsUiState(
                    isLoading = false,
                    filter = TransactionFilter(source = TransactionSource.OCR),
                    availableSources = listOf(TransactionSource.MANUAL, TransactionSource.OCR),
                ),
        )

        compose.onNodeWithText(text(R.string.transactions_filter_empty)).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.transactions_empty)).assertDoesNotExist()
    }

    @Test
    fun `tapping a row asks to open its detail`() {
        val events = mutableListOf<TransactionsEvent>()
        setContent(items = day(transaction()), onEvent = { events += it })

        compose.onNodeWithText(text(R.string.transactions_uncategorised)).performClick()

        // The transaction, not its id (issue 3.6): with paging there is no snapshot of the whole
        // list for the ViewModel to resolve an id against.
        assertEquals(listOf(TransactionsEvent.RowTapped(transaction())), events)
    }

    // --- search and filters (issue 3.6; FR-TXN-007) ------------------------------------------------

    @Test
    fun `typing in the search field asks for that query`() {
        val events = mutableListOf<TransactionsEvent>()
        setContent(items = day(transaction()), onEvent = { events += it })

        compose.onNodeWithText(text(R.string.transactions_search)).performTextInput("chai")

        assertEquals(listOf(TransactionsEvent.SearchChanged("chai")), events)
    }

    @Test
    fun `tapping the filter button opens the sheet`() {
        val events = mutableListOf<TransactionsEvent>()
        setContent(items = day(transaction()), onEvent = { events += it })

        compose.onNodeWithContentDescription(text(R.string.transactions_filter)).performClick()

        assertEquals(listOf(FilterEvent.Opened), events)
    }

    @Test
    fun `the filter sheet offers every facet the profile has something for`() {
        // Rendered directly rather than through `TransactionFilterSheet`, for the reason the detail
        // content is: a stateless body does not have to fight Robolectric over sheet animation.
        setFilterContent(
            TransactionsUiState(
                isLoading = false,
                accountNames = mapOf("account:1" to "HDFC Savings"),
                availableTags = listOf(Tag(id = "tag:1", name = "goa-trip")),
            ),
        )

        compose.onNodeWithText("HDFC Savings").assertIsDisplayed()
        compose.onNodeWithText(text(R.string.transactions_type_expense)).assertIsDisplayed()
        compose.onNodeWithText("goa-trip").assertIsDisplayed()
        compose.onNodeWithText(text(R.string.transactions_filter_min_amount)).assertIsDisplayed()
    }

    @Test
    fun `the filter sheet hides the tag facet when the profile has no tags`() {
        // An empty chip row reads as a broken picker, and tags are opt-in — most profiles have none.
        setFilterContent(TransactionsUiState(isLoading = false))

        compose.onNodeWithText(text(R.string.transactions_filter_tag)).assertDoesNotExist()
    }

    @Test
    fun `picking a type in the sheet asks for a filter carrying it`() {
        val events = mutableListOf<TransactionsEvent>()
        setFilterContent(TransactionsUiState(isLoading = false), onEvent = { events += it })

        compose.onNodeWithText(text(R.string.transactions_type_income)).performClick()

        assertEquals(listOf(FilterEvent.Changed(TransactionFilter(type = TransactionType.INCOME))), events)
    }

    @Test
    fun `the sheet offers no transfer-leg type, because half a transfer is not a thing to look for`() {
        setFilterContent(TransactionsUiState(isLoading = false))

        // Three chips, not five: `transfer_out` and `transfer_in` are a consequence of saving a
        // transfer, and filtering to one would show half of every transfer.
        compose.onNodeWithText(text(R.string.transactions_type_expense)).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.transactions_type_income)).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.transactions_type_adjustment)).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.transactions_type_transfer)).assertDoesNotExist()
    }

    // --- bulk edit (issue 3.6; FR-TXN-008) ---------------------------------------------------------

    @Test
    fun `long-pressing a row asks to select it`() {
        val events = mutableListOf<TransactionsEvent>()
        setContent(items = day(transaction()), onEvent = { events += it })

        compose.onNodeWithText(text(R.string.transactions_uncategorised))
            .performTouchInput { longClick() }

        assertEquals(listOf(BulkEvent.Toggled("txn:1")), events)
    }

    @Test
    fun `the action bar replaces the title and counts the selection`() {
        setContent(
            state = TransactionsUiState(isLoading = false, selection = setOf("txn:1", "txn:2")),
            items = day(transaction()),
        )

        compose.onNodeWithText("2 selected").assertIsDisplayed()
        compose.onNodeWithText(text(R.string.transactions_title)).assertDoesNotExist()
    }

    @Test
    fun `the per-row delete disappears while selecting`() {
        // Two destructive controls on one screen — a per-row bin and a bulk Delete — is how a user
        // deletes the wrong thing.
        setContent(
            state = TransactionsUiState(isLoading = false, selection = setOf("txn:1")),
            items = day(transaction()),
        )

        compose.onNodeWithContentDescription(text(R.string.transactions_delete)).assertDoesNotExist()
        compose.onNodeWithContentDescription(text(R.string.transactions_bulk_delete)).assertIsDisplayed()
    }

    @Test
    fun `tapping a row while selecting toggles it rather than opening the detail`() {
        val events = mutableListOf<TransactionsEvent>()
        setContent(
            state = TransactionsUiState(isLoading = false, selection = setOf("txn:2")),
            items = day(transaction()),
            onEvent = { events += it },
        )

        compose.onNodeWithText(text(R.string.transactions_uncategorised)).performClick()

        assertEquals(listOf(BulkEvent.Toggled("txn:1")), events)
        assertTrue(
            "a sheet must not open over rows the user is picking",
            events.none { it is TransactionsEvent.RowTapped },
        )
    }

    @Test
    fun `tapping bulk delete asks for it`() {
        val events = mutableListOf<TransactionsEvent>()
        setContent(
            state = TransactionsUiState(isLoading = false, selection = setOf("txn:1")),
            items = day(transaction()),
            onEvent = { events += it },
        )

        compose.onNodeWithContentDescription(text(R.string.transactions_bulk_delete)).performClick()

        assertEquals(listOf(BulkEvent.Delete), events)
    }

    @Test
    fun `leaving selection mode is one tap`() {
        val events = mutableListOf<TransactionsEvent>()
        setContent(
            state = TransactionsUiState(isLoading = false, selection = setOf("txn:1")),
            items = day(transaction()),
            onEvent = { events += it },
        )

        compose.onNodeWithContentDescription(text(R.string.transactions_selection_clear)).performClick()

        assertEquals(listOf(BulkEvent.Cleared), events)
    }

    @Test
    fun `the undo snackbar counts what the user selected, not what the store removed`() {
        // Deleting one leg of a transfer removes both (FR-TXN-003). Reporting "2 deleted" for a
        // one-row selection would be alarming and true; reporting "1" is what the user meant.
        setContent(
            state =
                TransactionsUiState(
                    isLoading = false,
                    undo = UndoBatch(ids = listOf("tfr:1:out", "tfr:1:in"), selectedCount = 1),
                ),
            items = day(transaction()),
        )

        compose.onNodeWithText("1 deleted").assertIsDisplayed()
        compose.onNodeWithText(text(R.string.transactions_undo)).assertIsDisplayed()
    }

    @Test
    fun `tapping undo asks to restore`() {
        val events = mutableListOf<TransactionsEvent>()
        setContent(
            state =
                TransactionsUiState(
                    isLoading = false,
                    undo = UndoBatch(ids = listOf("txn:1"), selectedCount = 1),
                ),
            items = day(transaction()),
            onEvent = { events += it },
        )

        compose.onNodeWithText(text(R.string.transactions_undo)).performClick()

        assertEquals(listOf(BulkEvent.Undo), events)
    }

    // --- the detail sheet's content (issue 3.5) ----------------------------------------------------
    //
    // Rendered directly rather than through `TransactionDetailSheet`: the content is stateless by
    // design precisely so these do not have to fight Robolectric over a sheet's animation.

    @Test
    fun `the detail names every field the transaction has`() {
        compose.setContent {
            CfoTheme {
                TransactionDetailContent(
                    transaction =
                        transaction {
                            copy(
                                amount = Money(-1_23_456_78L),
                                merchant = "Big Bazaar",
                                note = "Weekly shop",
                                source = TransactionSource.OCR,
                            )
                        },
                    accountNames = mapOf("account:1" to "HDFC Savings"),
                    onClose = {},
                )
            }
        }

        compose.onNodeWithText("₹1,23,456.78", substring = true).assertIsDisplayed()
        compose.onNodeWithText("HDFC Savings").assertIsDisplayed()
        compose.onNodeWithText(TransactionLabels.dayHeader("2026-08-02")).assertIsDisplayed()
        compose.onNodeWithText("Big Bazaar").assertIsDisplayed()
        compose.onNodeWithText("Weekly shop").assertIsDisplayed()
        compose.onNodeWithText(text(R.string.transactions_source_ocr)).assertIsDisplayed()
    }

    @Test
    fun `the detail names Manual, unlike the row`() {
        // In a list a missing label reads as "ordinary"; in a field list it reads as missing data.
        compose.setContent {
            CfoTheme {
                TransactionDetailContent(transaction = transaction(), accountNames = emptyMap(), onClose = {})
            }
        }

        compose.onNodeWithText(text(R.string.transactions_source_manual)).assertIsDisplayed()
    }

    @Test
    fun `the detail omits fields the transaction does not have`() {
        // FR-TXN-001 makes every field but the amount optional, so the sheet is as short as the row
        // is simple — an empty "Merchant" line would read as data the app lost.
        compose.setContent {
            CfoTheme {
                TransactionDetailContent(transaction = transaction(), accountNames = emptyMap(), onClose = {})
            }
        }

        compose.onNodeWithText(text(R.string.transactions_detail_merchant)).assertDoesNotExist()
        compose.onNodeWithText(text(R.string.transactions_detail_note)).assertDoesNotExist()
        compose.onNodeWithText(text(R.string.transactions_detail_split)).assertDoesNotExist()
    }

    @Test
    fun `an account with no name falls back to its id rather than a blank`() {
        compose.setContent {
            CfoTheme {
                TransactionDetailContent(transaction = transaction(), accountNames = emptyMap(), onClose = {})
            }
        }

        compose.onNodeWithText("account:1").assertIsDisplayed()
    }

    // --- fixtures ----------------------------------------------------------------------------------

    /**
     * Result: the composition is set. Input: [state]; [items] — the paged rows; [onEvent].
     *
     * The rows arrive as a `PagingData` rather than on the state (issue 3.6): the screen collects
     * them separately, so a test seeding them anywhere else would exercise a screen the app does not
     * have. `PagingData.from` is the synchronous single-page constructor Paging provides for this.
     */
    private fun setContent(
        state: TransactionsUiState = TransactionsUiState(isLoading = false),
        items: List<TransactionListItem> = emptyList(),
        onEvent: (TransactionsEvent) -> Unit = {},
    ) {
        compose.setContent {
            CfoTheme {
                TransactionsContent(
                    uiState = state,
                    items = flowOf(PagingData.from(items, LOADED)).collectAsLazyPagingItems(),
                    onEvent = onEvent,
                )
            }
        }
    }

    /** Result: the filter sheet's body is composed. Input: [state], [onEvent]. Output: none. */
    private fun setFilterContent(
        state: TransactionsUiState,
        onEvent: (TransactionsEvent) -> Unit = {},
    ) {
        compose.setContent { CfoTheme { TransactionFilterContent(uiState = state, onEvent = onEvent) } }
    }

    /**
     * Result: a day header followed by [transactions] as ordinary rows.
     *
     * The header is an item in the list now rather than a property of a `TransactionDay` (issue
     * 3.6), because with paging the grouping is inserted into the stream — a fixture that built a
     * day object would describe a shape the screen no longer renders.
     *
     * Input: [transactions]. Output: `List<TransactionListItem>`.
     */
    private fun day(vararg transactions: com.aicfo.core.model.Transaction): List<TransactionListItem> =
        listOf(
            TransactionListItem.DayHeader(
                isoDate = "2026-08-02",
                total = transactions.fold(Money.ZERO) { running, row -> running + row.amount },
            ),
        ) + transactions.map { TransactionListItem.Row(TransactionRow.Single(it)) }

    /**
     * Result: a day holding one collapsed ₹5,000 transfer. Output: `List<TransactionListItem>`.
     *
     * The header's total is **zero**: a transfer's legs are −X and +X, so it moves no day total —
     * which is exactly what the collapsed row must not contradict.
     */
    private fun transferDay(): List<TransactionListItem> =
        listOf(
            TransactionListItem.DayHeader(isoDate = "2026-08-02", total = Money.ZERO),
            TransactionListItem.Row(
                TransactionRow.TransferPair(
                    transferId = "tfr:1",
                    transaction =
                        transaction {
                            copy(
                                id = "tfr:1:out",
                                amount = Money.ZERO - Money(5_000_00L),
                                type = TransactionType.TRANSFER_OUT,
                                transferId = "tfr:1",
                            )
                        },
                    outAccountId = "account:1",
                    inAccountId = "account:2",
                    amount = Money(5_000_00L),
                ),
            ),
        )

    /** Result: one future day holding a ₹25,000 rent payment. Input: none. Output: a day. */
    private fun scheduledDay() =
        TransactionDay(
            isoDate = "2026-08-10",
            rows =
                listOf(
                    TransactionRow.Single(
                        transaction {
                            copy(
                                id = "txn:rent",
                                note = "Rent",
                                amount = Money(-25_000_00L),
                                bookedOn = "2026-08-10",
                            )
                        },
                    ),
                ),
        )

    /** Result: a ₹1,000 expense split across [lines] lines. Input: [lines]. Output: [Transaction]. */
    private fun splitTransaction(lines: Int) =
        transaction {
            copy(
                id = "txn:split",
                amount = Money(-1_000_00L),
                splits =
                    Money(-1_000_00L).split(lines).mapIndexed { index, share ->
                        com.aicfo.core.model.TransactionSplit(
                            id = "spl:$index",
                            transactionId = "txn:split",
                            amount = share,
                        )
                    },
            )
        }
}
