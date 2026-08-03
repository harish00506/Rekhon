package com.aicfo.feature.transactions

import app.cash.turbine.test
import com.aicfo.core.common.AppError
import com.aicfo.core.common.FakeClock
import com.aicfo.core.model.Category
import com.aicfo.core.model.Money
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.Instant

/**
 * Tests for [AddTransactionViewModel] (issue 3.1; FR-TXN-002, ARC-004).
 *
 * Why:  most of FR-TXN-002's tap budget is won or lost here rather than in the composable. **The
 *       first account must already be selected** when the screen opens, or the common path costs a
 *       third tap; that is asserted directly. **The expense toggle must become a sign** before the
 *       draft crosses into `:data:repository`, because below the UI the sign is the only
 *       representation of direction there is — a positive amount arriving from an expense would be
 *       money appearing out of nowhere. And **a refused write must not leave the screen**, or the
 *       user is told a transaction was saved that was not.
 * What: the state sequence, the draft the store is handed, and both failure paths.
 * Result: the tap budget and the sign convention are properties a test holds, not conventions.
 * Changelog: 2026-08-02 — Created for issue 3.1.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AddTransactionViewModelTest {
    private val transactions = FakeTransactionRepository()
    private val accounts = FakeAccountRepository()
    private val clock = FakeClock(initialMillis = Instant.parse("2026-08-02T18:00:00Z").toEpochMilli())

    /** Input: none. Output: `viewModelScope` runs on a test dispatcher. */
    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    /** Input: none. Output: restores the real main dispatcher. */
    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    // --- the tap budget ----------------------------------------------------------------------------

    @Test
    fun `the first account is preselected, so the common path needs no account tap`() =
        runTest {
            // FR-TXN-002's ≤ 3 taps depends on this. A screen that opened with nothing selected
            // would render identically and quietly break the requirement.
            accounts.setAccounts(account { copy(id = "account:1") }, account { copy(id = "account:2") })

            viewModel().uiState.test {
                val loaded = awaitItem()
                assertFalse(loaded.isLoading)
                assertEquals("account:1", loaded.selectedAccountId)
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun `no category is preselected — the middle tap is the user's to spend or skip`() =
        runTest {
            accounts.setAccounts(account())
            transactions.setCategories(Category("category:fuel", "Fuel"))

            viewModel().uiState.test {
                assertNull(awaitItem().selectedCategoryId)
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun `an explicit account choice survives a later emission from the store`() =
        runTest {
            // The preselection must not re-apply on every emission: a balance changing elsewhere
            // re-emits the list, and a ViewModel that reset the choice would fight the user.
            accounts.setAccounts(account { copy(id = "account:1") }, account { copy(id = "account:2") })
            val viewModel = viewModel()
            viewModel.onEvent(AddTransactionEvent.AccountSelected("account:2"))

            accounts.setAccounts(account { copy(id = "account:1") }, account { copy(id = "account:2") })

            viewModel.uiState.test {
                assertEquals("account:2", awaitItem().selectedAccountId)
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun `a selection whose account has gone falls back to the first remaining one`() =
        runTest {
            accounts.setAccounts(account { copy(id = "account:1") }, account { copy(id = "account:2") })
            val viewModel = viewModel()
            viewModel.onEvent(AddTransactionEvent.AccountSelected("account:2"))

            accounts.setAccounts(account { copy(id = "account:1") })

            viewModel.uiState.test {
                assertEquals("account:1", awaitItem().selectedAccountId)
                cancelAndIgnoreRemainingEvents()
            }
        }

    // --- the sign ----------------------------------------------------------------------------------

    @Test
    fun `an expense is handed to the store as a negative amount`() =
        runTest {
            val viewModel = savableViewModel(amount = "250")

            viewModel.onEvent(AddTransactionEvent.Save)

            // MNY-001: 250 rupees is 25000 paise, and the direction is the sign and nothing else.
            assertEquals(Money(-250_00L), transactions.created.single().amount)
        }

    @Test
    fun `an income is handed to the store as a positive amount`() =
        runTest {
            val viewModel = savableViewModel(amount = "60000")
            viewModel.onEvent(AddTransactionEvent.DirectionChanged(TransactionDirection.INCOME))

            viewModel.onEvent(AddTransactionEvent.Save)

            assertEquals(Money(60_000_00L), transactions.created.single().amount)
        }

    @Test
    fun `paise survive the round trip`() =
        runTest {
            val viewModel = savableViewModel(amount = "1234.56")

            viewModel.onEvent(AddTransactionEvent.Save)

            assertEquals(Money(-1_234_56L), transactions.created.single().amount)
        }

    @Test
    fun `the selected account and category reach the draft`() =
        runTest {
            accounts.setAccounts(account { copy(id = "account:1") })
            transactions.setCategories(Category("category:fuel", "Fuel"))
            val viewModel = viewModel()
            viewModel.onEvent(AddTransactionEvent.AmountChanged("1200"))
            viewModel.onEvent(AddTransactionEvent.CategorySelected("category:fuel"))
            viewModel.onEvent(AddTransactionEvent.NoteChanged("Petrol"))

            viewModel.onEvent(AddTransactionEvent.Save)

            val draft = transactions.created.single()
            assertEquals("account:1", draft.accountId)
            assertEquals("category:fuel", draft.categoryId)
            assertEquals("Petrol", draft.note)
        }

    @Test
    fun `tapping the selected category again clears it`() =
        runTest {
            accounts.setAccounts(account())
            transactions.setCategories(Category("category:fuel", "Fuel"))
            val viewModel = viewModel()
            viewModel.onEvent(AddTransactionEvent.CategorySelected("category:fuel"))

            viewModel.onEvent(AddTransactionEvent.CategorySelected(null))

            viewModel.uiState.test {
                assertNull(awaitItem().selectedCategoryId)
                cancelAndIgnoreRemainingEvents()
            }
        }

    // --- what blocks Save --------------------------------------------------------------------------

    @Test
    fun `an empty amount cannot be saved`() =
        runTest {
            accounts.setAccounts(account())
            val viewModel = viewModel()

            viewModel.onEvent(AddTransactionEvent.Save)

            viewModel.uiState.test {
                assertFalse(awaitItem().canSave)
                cancelAndIgnoreRemainingEvents()
            }
            assertTrue(transactions.created.isEmpty())
        }

    @Test
    fun `a zero amount cannot be saved`() =
        runTest {
            // A zero row would sit in every list and every total contributing nothing. Blocked here
            // as well as in the repository so Save is *disabled* rather than tapped and refused.
            val viewModel = savableViewModel(amount = "0")

            viewModel.onEvent(AddTransactionEvent.Save)

            assertTrue(transactions.created.isEmpty())
        }

    @Test
    fun `an amount too precise for paise cannot be saved`() =
        runTest {
            // MoneyFormatter.parse refuses "12.345" rather than rounding it — guessing what the user
            // meant about money is the one thing this app must never do (P-03).
            val viewModel = savableViewModel(amount = "12.345")

            viewModel.onEvent(AddTransactionEvent.Save)

            assertTrue(transactions.created.isEmpty())
        }

    @Test
    fun `a profile with no account reports it rather than offering a Save that always fails`() =
        runTest {
            viewModel().uiState.test {
                val loaded = awaitItem()
                assertTrue(loaded.hasNoAccount)
                assertFalse(loaded.canSave)
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun `a second Save while one is in flight writes nothing extra`() =
        runTest {
            val viewModel = savableViewModel(amount = "250")

            viewModel.onEvent(AddTransactionEvent.Save)
            viewModel.onEvent(AddTransactionEvent.Save)

            // The first save completed on the unconfined dispatcher, so the second is refused by
            // `isSaved` having already sent the screen away rather than by `isSaving` — either way
            // a double-tap must not book the spend twice.
            assertEquals(1, transactions.created.size)
        }

    // --- outcomes ----------------------------------------------------------------------------------

    @Test
    fun `a successful save asks the screen to leave`() =
        runTest {
            val viewModel = savableViewModel(amount = "250")

            viewModel.onEvent(AddTransactionEvent.Save)

            viewModel.uiState.test {
                val state = awaitItem()
                assertTrue(state.isSaved)
                assertFalse(state.isSaving)
                assertNull(state.errorCode)
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun `a refused save reports the code and stays on the screen`() =
        runTest {
            transactions.failWith = AppError.NotFound
            val viewModel = savableViewModel(amount = "250")

            viewModel.onEvent(AddTransactionEvent.Save)

            viewModel.uiState.test {
                val state = awaitItem()
                assertFalse(state.isSaved)
                assertFalse(state.isSaving)
                // The code, never a message: the wording lives in strings.xml (§21.6, P-01).
                assertEquals(AppError.NotFound.code, state.errorCode)
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun `dismissing the error clears it`() =
        runTest {
            transactions.failWith = AppError.NotFound
            val viewModel = savableViewModel(amount = "250")
            viewModel.onEvent(AddTransactionEvent.Save)

            viewModel.onEvent(AddTransactionEvent.DismissError)

            viewModel.uiState.test {
                assertNull(awaitItem().errorCode)
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun `a read that throws clears loading and reports something`() =
        runTest {
            // Which code it is belongs to `toAppError`'s own tests. What matters here is that the
            // screen does not sit on a spinner for ever, and does not render an empty picker as
            // though the user genuinely had no accounts.
            transactions.failOnObserve = AppError.Storage("boom")

            viewModel().uiState.test {
                val state = awaitItem()
                assertFalse(state.isLoading)
                assertTrue(state.errorCode != null)
                cancelAndIgnoreRemainingEvents()
            }
        }

    // --- transfers (issue 3.2; FR-TXN-003) ---------------------------------------------------------

    @Test
    fun `a transfer is handed to the store as a positive amount and two accounts`() =
        runTest {
            // The transfer path must not go through `create`: one signed row would move one account
            // and invent the money at the other end.
            val viewModel = transferViewModel(amount = "5000")

            viewModel.onEvent(AddTransactionEvent.Save)

            val draft = transactions.transfersCreated.single()
            assertEquals("account:1", draft.fromAccountId)
            assertEquals("account:2", draft.toAccountId)
            // Positive: the signs belong to the two legs, and the repository applies them.
            assertEquals(Money(5_000_00L), draft.amount)
            assertTrue("a transfer must not be written as a plain transaction", transactions.created.isEmpty())
        }

    @Test
    fun `a transfer without a destination cannot be saved`() =
        runTest {
            accounts.setAccounts(account { copy(id = "account:1") }, account { copy(id = "account:2") })
            val viewModel = viewModel()
            viewModel.onEvent(AddTransactionEvent.AmountChanged("5000"))
            viewModel.onEvent(AddTransactionEvent.DirectionChanged(TransactionDirection.TRANSFER))

            viewModel.onEvent(AddTransactionEvent.Save)

            viewModel.uiState.test {
                assertFalse(awaitItem().canSave)
                cancelAndIgnoreRemainingEvents()
            }
            assertTrue(transactions.transfersCreated.isEmpty())
        }

    @Test
    fun `a transfer to the source account cannot be saved`() =
        runTest {
            // The repository refuses it too, but a disabled button explains it before the user
            // commits rather than after.
            val viewModel = transferViewModel(amount = "5000")
            viewModel.onEvent(AddTransactionEvent.AccountSelected("account:2"))

            viewModel.uiState.test {
                val state = awaitItem()
                // Picking the source that was already the destination clears the destination, so the
                // form asks again instead of holding an invalid pair.
                assertNull(state.toAccountId)
                assertFalse(state.canSave)
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun `the destination picker never offers the source account`() =
        runTest {
            accounts.setAccounts(
                account { copy(id = "account:1") },
                account { copy(id = "account:2") },
                account { copy(id = "account:3") },
            )

            viewModel().uiState.test {
                val state = awaitItem()
                assertEquals(
                    listOf("account:2", "account:3"),
                    state.destinationChoices.map { it.id },
                )
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun `a profile with one account cannot transfer at all`() =
        runTest {
            // There is nowhere to move money to. The toggle disables the option rather than
            // accepting a tap and then refusing to save.
            accounts.setAccounts(account())

            viewModel().uiState.test {
                assertFalse(awaitItem().canTransfer)
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun `switching to transfer clears a category picked as an expense`() =
        runTest {
            // FR-TXN-003: a transfer is not spending. A stale category would be silently dropped by
            // the store, which is worse than clearing it where the user can see.
            accounts.setAccounts(account { copy(id = "account:1") }, account { copy(id = "account:2") })
            transactions.setCategories(Category("category:fuel", "Fuel"))
            val viewModel = viewModel()
            viewModel.onEvent(AddTransactionEvent.CategorySelected("category:fuel"))

            viewModel.onEvent(AddTransactionEvent.DirectionChanged(TransactionDirection.TRANSFER))

            viewModel.uiState.test {
                val state = awaitItem()
                assertNull(state.selectedCategoryId)
                assertFalse("the category row must be hidden for a transfer", state.hasCategories)
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun `switching away from transfer clears the destination`() =
        runTest {
            val viewModel = transferViewModel(amount = "5000")

            viewModel.onEvent(AddTransactionEvent.DirectionChanged(TransactionDirection.EXPENSE))

            viewModel.uiState.test {
                assertNull(awaitItem().toAccountId)
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun `a refused transfer reports the code and stays on the screen`() =
        runTest {
            transactions.failWith = AppError.Validation("toAccountId")
            val viewModel = transferViewModel(amount = "5000")

            viewModel.onEvent(AddTransactionEvent.Save)

            viewModel.uiState.test {
                val state = awaitItem()
                assertFalse(state.isSaved)
                assertEquals(AppError.Validation("toAccountId").code, state.errorCode)
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun `a successful transfer asks the screen to leave`() =
        runTest {
            val viewModel = transferViewModel(amount = "5000")

            viewModel.onEvent(AddTransactionEvent.Save)

            viewModel.uiState.test {
                assertTrue(awaitItem().isSaved)
                cancelAndIgnoreRemainingEvents()
            }
        }

    // --- splits (issue 3.3; FR-TXN-004) ------------------------------------------------------------

    @Test
    fun `splitting is not offered until there is an amount to divide`() =
        runTest {
            accounts.setAccounts(account())
            val viewModel = viewModel()

            viewModel.uiState.test {
                assertFalse("nothing to split yet", awaitItem().canSplit)
                cancelAndIgnoreRemainingEvents()
            }

            viewModel.onEvent(AddTransactionEvent.AmountChanged("1000"))

            viewModel.uiState.test {
                assertTrue(awaitItem().canSplit)
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun `splitting is never offered for a transfer`() =
        runTest {
            // Moving money between your own accounts is not spending, so there is nothing to
            // attribute across categories (FR-TXN-003 vs FR-TXN-004).
            val viewModel = transferViewModel(amount = "5000")

            viewModel.uiState.test {
                assertFalse(awaitItem().canSplit)
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun `turning splitting on seeds two empty lines`() =
        runTest {
            // One line is not a split, and an editor that opens empty asks the user to find an
            // "add line" button before it does anything.
            val viewModel = splitViewModel()

            viewModel.uiState.test {
                val state = awaitItem()
                assertEquals(MIN_SPLIT_LINES, state.splitLines.size)
                assertTrue(state.splitLines.all { it.amountText.isEmpty() })
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun `the remainder starts at the full amount and falls to zero as lines are entered`() =
        runTest {
            // The running remainder the AC asks for, as a sequence rather than a single reading.
            val viewModel = splitViewModel()

            viewModel.onEvent(SplitEvent.SplitLineAmountChanged(0, "600"))
            viewModel.uiState.test {
                assertEquals(Money(400_00L), awaitItem().splitRemainder)
                cancelAndIgnoreRemainingEvents()
            }

            viewModel.onEvent(SplitEvent.SplitLineAmountChanged(1, "400"))
            viewModel.uiState.test {
                assertEquals(Money.ZERO, awaitItem().splitRemainder)
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun `Save is blocked until the lines balance exactly`() =
        runTest {
            // One paise out is out — no tolerance, because paise are integers.
            val viewModel = splitViewModel()
            viewModel.onEvent(SplitEvent.SplitLineAmountChanged(0, "600"))
            viewModel.onEvent(SplitEvent.SplitLineAmountChanged(1, "399.99"))

            viewModel.uiState.test {
                val state = awaitItem()
                assertEquals(Money(1L), state.splitRemainder)
                assertFalse(state.canSave)
                cancelAndIgnoreRemainingEvents()
            }
            viewModel.onEvent(AddTransactionEvent.Save)
            assertTrue(transactions.splitsCreated.isEmpty())
        }

    @Test
    fun `a half-typed line leaves the remainder owing rather than counting as zero`() =
        runTest {
            val viewModel = splitViewModel()
            viewModel.onEvent(SplitEvent.SplitLineAmountChanged(0, "600"))
            // "4.999" is over-precise for paise, so MoneyFormatter.parse refuses it rather than
            // rounding — the line is not a figure yet and must not count as zero.
            viewModel.onEvent(SplitEvent.SplitLineAmountChanged(1, "4.999"))

            viewModel.uiState.test {
                val state = awaitItem()
                assertEquals(Money(400_00L), state.splitRemainder)
                assertFalse(state.canSave)
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun `split evenly always balances, including when it does not divide cleanly`() =
        runTest {
            // ₹1,000 over three is the case that exposes rounding drift: 333.33 three times loses a
            // paise. `Money.split`'s largest-remainder rule gives the spare to the first line.
            val viewModel = splitViewModel()
            viewModel.onEvent(SplitEvent.SplitLineAdded)

            viewModel.onEvent(SplitEvent.SplitEvenly)

            viewModel.uiState.test {
                val state = awaitItem()
                assertEquals(listOf("333.34", "333.33", "333.33"), state.splitLines.map { it.amountText })
                assertEquals(Money.ZERO, state.splitRemainder)
                assertTrue(state.canSave)
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun `split evenly keeps the categories already chosen`() =
        runTest {
            // The user usually decides what each line is *for* before deciding the amounts are equal.
            transactions.setCategories(Category("category:fuel", "Fuel"))
            val viewModel = splitViewModel()
            viewModel.onEvent(SplitEvent.SplitLineCategorySelected(0, "category:fuel"))

            viewModel.onEvent(SplitEvent.SplitEvenly)

            viewModel.uiState.test {
                assertEquals("category:fuel", awaitItem().splitLines.first().categoryId)
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun `lines reach the store signed like their parent`() =
        runTest {
            // The user types unsigned figures; below the UI a line of an expense is negative like its
            // parent, which is what lets the repository check the sum as one comparison.
            val viewModel = balancedSplitViewModel()

            viewModel.onEvent(AddTransactionEvent.Save)

            val draft = transactions.splitsCreated.single()
            assertEquals(Money(-1_000_00L), draft.amount)
            assertEquals(listOf(Money(-600_00L), Money(-400_00L)), draft.lines.map { it.amount })
            assertTrue("a split must not be written as a plain transaction", transactions.created.isEmpty())
        }

    @Test
    fun `an income splits into positive lines`() =
        runTest {
            accounts.setAccounts(account())
            val viewModel = viewModel()
            viewModel.onEvent(AddTransactionEvent.AmountChanged("1000"))
            viewModel.onEvent(AddTransactionEvent.DirectionChanged(TransactionDirection.INCOME))
            viewModel.onEvent(SplitEvent.SplitToggled(true))
            viewModel.onEvent(SplitEvent.SplitLineAmountChanged(0, "600"))
            viewModel.onEvent(SplitEvent.SplitLineAmountChanged(1, "400"))

            viewModel.onEvent(AddTransactionEvent.Save)

            assertEquals(
                listOf(Money(600_00L), Money(400_00L)),
                transactions.splitsCreated.single().lines.map { it.amount },
            )
        }

    @Test
    fun `each line carries its own category`() =
        runTest {
            transactions.setCategories(
                Category("category:groceries", "Groceries"),
                Category("category:household", "Household"),
            )
            val viewModel = balancedSplitViewModel()
            viewModel.onEvent(SplitEvent.SplitLineCategorySelected(0, "category:groceries"))
            viewModel.onEvent(SplitEvent.SplitLineCategorySelected(1, "category:household"))

            viewModel.onEvent(AddTransactionEvent.Save)

            assertEquals(
                listOf("category:groceries", "category:household"),
                transactions.splitsCreated.single().lines.map { it.categoryId },
            )
        }

    @Test
    fun `splitting hides the parent's category picker and clears any choice`() =
        runTest {
            // The lines carry the categories now; one on the parent as well would contradict them.
            transactions.setCategories(Category("category:fuel", "Fuel"))
            accounts.setAccounts(account())
            val viewModel = viewModel()
            viewModel.onEvent(AddTransactionEvent.AmountChanged("1000"))
            viewModel.onEvent(AddTransactionEvent.CategorySelected("category:fuel"))

            viewModel.onEvent(SplitEvent.SplitToggled(true))

            viewModel.uiState.test {
                val state = awaitItem()
                assertNull(state.selectedCategoryId)
                assertFalse(state.hasCategories)
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun `turning splitting off clears the lines rather than hiding them`() =
        runTest {
            // A stale set of lines reappearing later, half-matching a different amount, is a worse
            // surprise than retyping two figures.
            val viewModel = balancedSplitViewModel()

            viewModel.onEvent(SplitEvent.SplitToggled(false))

            viewModel.uiState.test {
                val state = awaitItem()
                assertTrue(state.splitLines.isEmpty())
                assertTrue("a plain expense must be savable again", state.canSave)
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun `adding and removing lines re-opens the remainder`() =
        runTest {
            val viewModel = balancedSplitViewModel()

            viewModel.onEvent(SplitEvent.SplitLineAdded)

            viewModel.uiState.test {
                val state = awaitItem()
                assertEquals(3, state.splitLines.size)
                // The new line is empty, so the sum is unchanged but the form is not ready.
                assertEquals(Money.ZERO, state.splitRemainder)
                assertFalse("an empty line is not a figure", state.canSave)
                cancelAndIgnoreRemainingEvents()
            }

            viewModel.onEvent(SplitEvent.SplitLineRemoved(2))

            viewModel.uiState.test {
                assertTrue(awaitItem().canSave)
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun `removing a line leaves the rest in order`() =
        runTest {
            val viewModel = balancedSplitViewModel()
            viewModel.onEvent(SplitEvent.SplitLineAdded)
            viewModel.onEvent(SplitEvent.SplitLineAmountChanged(2, "1"))

            viewModel.onEvent(SplitEvent.SplitLineRemoved(0))

            viewModel.uiState.test {
                assertEquals(listOf("400", "1"), awaitItem().splitLines.map { it.amountText })
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun `an edit to a line that no longer exists is ignored`() =
        runTest {
            // A stale recomposition can genuinely deliver an index that has just been removed.
            val viewModel = balancedSplitViewModel()

            viewModel.onEvent(SplitEvent.SplitLineAmountChanged(9, "1"))

            viewModel.uiState.test {
                assertEquals(listOf("600", "400"), awaitItem().splitLines.map { it.amountText })
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun `a refused split reports the code and stays on the screen`() =
        runTest {
            transactions.failWith = AppError.Validation("lines")
            val viewModel = balancedSplitViewModel()

            viewModel.onEvent(AddTransactionEvent.Save)

            viewModel.uiState.test {
                val state = awaitItem()
                assertFalse(state.isSaved)
                assertEquals(AppError.Validation("lines").code, state.errorCode)
                cancelAndIgnoreRemainingEvents()
            }
        }

    // --- fixtures ----------------------------------------------------------------------------------

    /**
     * Result: a ViewModel over the two fakes and a frozen clock.
     *
     * The clock is fixed at 2026-08-02T23:30 IST — the same instant the repository suites use, an
     * hour and a half before the profile's midnight and after UTC's. Issue 3.4's date assertions are
     * relative to [TODAY], never to the wall clock (P-08).
     *
     * Input: none. Output: [AddTransactionViewModel].
     */
    private fun viewModel() = AddTransactionViewModel(transactions, accounts, clock)

    /**
     * Result: a ViewModel with one account, ₹1,000 typed and splitting on — two empty lines.
     * Input:  none. Output: [AddTransactionViewModel].
     */
    private fun splitViewModel(): AddTransactionViewModel {
        accounts.setAccounts(account())
        return viewModel().apply {
            onEvent(AddTransactionEvent.AmountChanged("1000"))
            onEvent(SplitEvent.SplitToggled(true))
        }
    }

    /**
     * Result: [splitViewModel] with the two lines filled to 600/400 — a balanced, savable split.
     * Input:  none. Output: [AddTransactionViewModel].
     */
    private fun balancedSplitViewModel(): AddTransactionViewModel =
        splitViewModel().apply {
            onEvent(SplitEvent.SplitLineAmountChanged(0, "600"))
            onEvent(SplitEvent.SplitLineAmountChanged(1, "400"))
        }

    /**
     * Result: a ViewModel with two accounts, [amount] typed, Transfer chosen and a destination
     *         picked — the state a transfer Save test starts from.
     * Input:  [amount]. Output: [AddTransactionViewModel].
     */
    private fun transferViewModel(amount: String): AddTransactionViewModel {
        accounts.setAccounts(account { copy(id = "account:1") }, account { copy(id = "account:2") })
        return viewModel().apply {
            onEvent(AddTransactionEvent.AmountChanged(amount))
            onEvent(AddTransactionEvent.DirectionChanged(TransactionDirection.TRANSFER))
            onEvent(AddTransactionEvent.DestinationSelected("account:2"))
        }
    }

    /**
     * Result: a ViewModel with one account and [amount] typed — the state a Save test starts from.
     * Input:  [amount] — what the user typed. Output: [AddTransactionViewModel].
     */
    private fun savableViewModel(amount: String): AddTransactionViewModel {
        accounts.setAccounts(account())
        return viewModel().apply { onEvent(AddTransactionEvent.AmountChanged(amount)) }
    }
}
