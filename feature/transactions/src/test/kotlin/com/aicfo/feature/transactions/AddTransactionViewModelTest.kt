package com.aicfo.feature.transactions

import app.cash.turbine.test
import com.aicfo.core.common.AppError
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
            viewModel.onEvent(AddTransactionEvent.ExpenseChanged(false))

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

    // --- fixtures ----------------------------------------------------------------------------------

    /** Result: a ViewModel over the two fakes. Input: none. Output: [AddTransactionViewModel]. */
    private fun viewModel() = AddTransactionViewModel(transactions, accounts)

    /**
     * Result: a ViewModel with one account and [amount] typed — the state a Save test starts from.
     * Input:  [amount] — what the user typed. Output: [AddTransactionViewModel].
     */
    private fun savableViewModel(amount: String): AddTransactionViewModel {
        accounts.setAccounts(account())
        return viewModel().apply { onEvent(AddTransactionEvent.AmountChanged(amount)) }
    }
}
