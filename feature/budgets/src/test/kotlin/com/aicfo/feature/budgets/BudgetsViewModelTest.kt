package com.aicfo.feature.budgets

import app.cash.turbine.test
import com.aicfo.core.common.AppError
import com.aicfo.core.model.Money
import com.aicfo.domain.engines.budget.BudgetAlertBand
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
 * Tests for [BudgetsViewModel] — the state half of issue 4.4 (FR-BUD-001/002/003, ARC-004).
 *
 * Why:  five things about this screen are invisible until they are wrong. **An empty list is three
 *       different situations** and only one is an invitation — the clause that decides has been
 *       widened four times elsewhere in this app, always caught by a test. **Nothing may be written
 *       without a tap** (P-07), which is a claim about what the ViewModel does *not* do and so needs
 *       asserting directly. **A rejected save must keep the user's typing.** **A failing suggestion
 *       stream must not blank the budgets** the user came to read. And **the worst row must sort
 *       first**, because a budget screen that buries the overspend under Groceries has answered the
 *       wrong question.
 * What: the full `UiState` sequence through load, edit, accept, delete and every failure branch.
 * Result: every state the screen can be in is reachable and asserted.
 * Changelog: 2026-08-11 — Created for issue 4.4.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class BudgetsViewModelTest {
    /** Input: none. Output: pins `viewModelScope` so collectors and writes run inline. */
    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    /** Input: none. Output: releases the main dispatcher. */
    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    // --- loading and emptiness --------------------------------------------------------------------

    @Test
    fun `the list loads and stops loading`() =
        runTest {
            val repository = FakeBudgetRepository(listOf(budgetRow(name = "Groceries")))
            val viewModel = BudgetsViewModel(repository)

            viewModel.uiState.test {
                val loaded = awaitItem()
                assertFalse(loaded.isLoading)
                assertEquals(listOf("Groceries"), loaded.planned.map { it.category.name })
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun `nothing planned is empty, but a loading or failed screen is not`() {
        assertTrue(BudgetsUiState(isLoading = false).isEmpty)
        // The invitation must not flash before the store has answered.
        assertFalse(BudgetsUiState(isLoading = true).isEmpty)
        // A database that would not open must not read as a user who has planned nothing.
        assertFalse(BudgetsUiState(isLoading = false, errorCode = "storage").isEmpty)
    }

    @Test
    fun `a screen with only suggestions is not empty`() {
        // There is something to act on, so telling the user the screen is empty while offering them
        // a budget would contradict itself.
        val state = BudgetsUiState(isLoading = false, suggestions = listOf(suggestionRow()))
        assertFalse(state.isEmpty)
    }

    @Test
    fun `a read failure sets the error and stops loading`() =
        runTest {
            val repository = FailingBudgetRepository()
            val viewModel = BudgetsViewModel(repository)

            viewModel.uiState.test {
                val failed = awaitItem()
                assertFalse(failed.isLoading)
                assertEquals(AppError.Storage("").code, failed.errorCode)
                cancelAndIgnoreRemainingEvents()
            }
        }

    // --- ordering ---------------------------------------------------------------------------------

    @Test
    fun `the categories in trouble sort above the ones that are fine`() {
        val onTrack = budgetRow(id = "b:1", name = "Groceries", spent = Money(100_000L))
        val ahead =
            budgetRow(id = "b:2", name = "Dining", spent = Money(600_000L), safePace = Money(500_000L))
        val overspent =
            budgetRow(id = "b:3", name = "Shopping", spent = Money(1_200_000L), projected = Money(2_000_000L))
        val state = BudgetsUiState(isLoading = false, budgets = listOf(onTrack, ahead, overspent))

        assertEquals(listOf("Shopping", "Dining", "Groceries"), state.planned.map { it.category.name })
    }

    @Test
    fun `unplanned categories are listed only when money has moved through them`() {
        val spentOn = budgetRow(id = null, name = "Cabs", budgeted = Money.ZERO, spent = Money(300_000L))
        val untouched = budgetRow(id = null, name = "Gifts", budgeted = Money.ZERO, spent = Money.ZERO)
        val state = BudgetsUiState(isLoading = false, budgets = listOf(spentOn, untouched))

        // A taxonomy has dozens of categories nobody spent in this month, and none of them is news.
        assertEquals(listOf("Cabs"), state.unplanned.map { it.category.name })
        assertTrue(state.planned.isEmpty())
    }

    // --- the editor -------------------------------------------------------------------------------

    @Test
    fun `editing a budgeted category pre-fills the amount it already has`() =
        runTest {
            val repository = FakeBudgetRepository(listOf(budgetRow(name = "Groceries", budgeted = Money(1_000_000L))))
            val viewModel = BudgetsViewModel(repository)

            viewModel.onEvent(BudgetsEvent.EditClicked("category:Groceries"))

            val editing = viewModel.uiState.value.editing
            assertEquals("₹10,000.00", editing?.amountText)
            assertEquals("Groceries", editing?.categoryName)
        }

    @Test
    fun `setting a budget on an unplanned category opens a blank field, not a zero`() =
        runTest {
            val row = budgetRow(id = null, name = "Cabs", budgeted = Money.ZERO, spent = Money(300_000L))
            val viewModel = BudgetsViewModel(FakeBudgetRepository(listOf(row)))

            viewModel.onEvent(BudgetsEvent.EditClicked("category:Cabs"))

            // A "₹0.00" the user did not type reads as a decision they made.
            assertEquals("", viewModel.uiState.value.editing?.amountText)
        }

    @Test
    fun `an amount that is not money cannot be saved`() {
        assertFalse(BudgetEditorState("c", "Groceries", amountText = "").canSave)
        assertFalse(BudgetEditorState("c", "Groceries", amountText = "lots").canSave)
        // Three decimal places is not representable in paise (MNY-001), so the parser refuses it.
        assertFalse(BudgetEditorState("c", "Groceries", amountText = "100.005").canSave)
        assertTrue(BudgetEditorState("c", "Groceries", amountText = "1000").canSave)
        // A budget of zero is a real instruction: spend nothing here.
        assertTrue(BudgetEditorState("c", "Groceries", amountText = "0").canSave)
        // Not while a write is in flight.
        assertFalse(BudgetEditorState("c", "Groceries", amountText = "1000", isSaving = true).canSave)
    }

    @Test
    fun `saving writes the typed amount and the rollover choice, then closes the sheet`() =
        runTest {
            val repository = FakeBudgetRepository(listOf(budgetRow(name = "Groceries")))
            val viewModel = BudgetsViewModel(repository)

            viewModel.onEvent(BudgetsEvent.EditClicked("category:Groceries"))
            viewModel.onEvent(BudgetsEvent.AmountChanged("12,500"))
            viewModel.onEvent(BudgetsEvent.RolloverChanged(true))
            viewModel.onEvent(BudgetsEvent.Save)

            assertEquals(
                listOf(Triple("category:Groceries", Money(1_250_000L), true)),
                repository.written,
            )
            assertNull(viewModel.uiState.value.editing)
        }

    @Test
    fun `a rejected save keeps the sheet open with what the user typed`() =
        runTest {
            val repository = FakeBudgetRepository(listOf(budgetRow(name = "Groceries")))
            repository.nextError = AppError.Validation("amount")
            val viewModel = BudgetsViewModel(repository)

            viewModel.onEvent(BudgetsEvent.EditClicked("category:Groceries"))
            viewModel.onEvent(BudgetsEvent.AmountChanged("999"))
            viewModel.onEvent(BudgetsEvent.Save)

            val state = viewModel.uiState.value
            // Clearing the field would make the user retype it to find out what was wrong with it.
            assertEquals("999", state.editing?.amountText)
            assertFalse(state.editing?.isSaving ?: true)
            assertEquals(BudgetLabels.VALIDATION_AMOUNT, state.errorCode)
        }

    @Test
    fun `the two validation refusals stay distinguishable`() =
        runTest {
            val repository = FakeBudgetRepository(listOf(budgetRow(name = "Groceries")))
            repository.nextError = AppError.Validation("categoryId")
            val viewModel = BudgetsViewModel(repository)

            viewModel.onEvent(BudgetsEvent.EditClicked("category:Groceries"))
            viewModel.onEvent(BudgetsEvent.AmountChanged("999"))
            viewModel.onEvent(BudgetsEvent.Save)

            // Both arrive as AppError.Validation with the same code; the field is what tells the
            // user to fix their typing rather than to accept that the category is gone.
            assertEquals(BudgetLabels.VALIDATION_CATEGORY, viewModel.uiState.value.errorCode)
        }

    @Test
    fun `cancelling closes the sheet and writes nothing`() =
        runTest {
            val repository = FakeBudgetRepository(listOf(budgetRow(name = "Groceries")))
            val viewModel = BudgetsViewModel(repository)

            viewModel.onEvent(BudgetsEvent.EditClicked("category:Groceries"))
            viewModel.onEvent(BudgetsEvent.AmountChanged("5000"))
            viewModel.onEvent(BudgetsEvent.CancelEdit)

            assertNull(viewModel.uiState.value.editing)
            assertTrue(repository.written.isEmpty())
        }

    @Test
    fun `editing a category that is not on screen does nothing`() =
        runTest {
            val viewModel = BudgetsViewModel(FakeBudgetRepository(listOf(budgetRow(name = "Groceries"))))

            viewModel.onEvent(BudgetsEvent.EditClicked("category:Gone"))

            assertNull(viewModel.uiState.value.editing)
        }

    // --- suggestions (FR-BUD-002, P-07) -----------------------------------------------------------

    @Test
    fun `a suggestion is offered but never applied on its own`() =
        runTest {
            val repository =
                FakeBudgetRepository(
                    initialBudgets = listOf(budgetRow(name = "Groceries")),
                    initialSuggestions = listOf(suggestionRow(name = "Shopping")),
                )
            val viewModel = BudgetsViewModel(repository)

            // The whole of P-07 in one assertion: the offer is on screen and nothing was written.
            assertEquals(1, viewModel.uiState.value.suggestions.size)
            assertTrue(repository.accepted.isEmpty())
            assertTrue(repository.written.isEmpty())
        }

    @Test
    fun `accepting a suggestion names the category and passes no amount`() =
        runTest {
            val repository =
                FakeBudgetRepository(initialSuggestions = listOf(suggestionRow(name = "Shopping")))
            val viewModel = BudgetsViewModel(repository)

            viewModel.onEvent(BudgetsEvent.AcceptSuggestion("category:Shopping"))

            // The amount does not travel through the UI: the repository re-reads what it published,
            // so the number written is provably the one the engine produced.
            assertEquals(listOf("category:Shopping"), repository.accepted)
            assertTrue(repository.written.isEmpty())
        }

    @Test
    fun `a refused accept surfaces in the banner`() =
        runTest {
            val repository =
                FakeBudgetRepository(initialSuggestions = listOf(suggestionRow(name = "Shopping")))
            repository.nextError = AppError.NotFound
            val viewModel = BudgetsViewModel(repository)

            viewModel.onEvent(BudgetsEvent.AcceptSuggestion("category:Shopping"))

            assertEquals(AppError.NotFound.code, viewModel.uiState.value.errorCode)
        }

    @Test
    fun `a failing suggestion stream leaves the budgets alone`() =
        runTest {
            val repository = SuggestionlessBudgetRepository(listOf(budgetRow(name = "Groceries")))
            val viewModel = BudgetsViewModel(repository)

            val state = viewModel.uiState.value
            // Suggestions are an offer. Reporting an error because the app could not think of one
            // would be alarming about nothing, and would hide the list the user came to read.
            assertEquals(listOf("Groceries"), state.planned.map { it.category.name })
            assertTrue(state.suggestions.isEmpty())
            assertNull(state.errorCode)
        }

    // --- deleting ---------------------------------------------------------------------------------

    @Test
    fun `removing a budget deletes it with no confirmation step`() =
        runTest {
            val repository = FakeBudgetRepository(listOf(budgetRow(id = "budget:9")))
            val viewModel = BudgetsViewModel(repository)

            viewModel.onEvent(BudgetsEvent.DeleteClicked("budget:9"))

            // Nothing is destroyed: the spending stays and the row moves to the unplanned section.
            assertEquals(listOf("budget:9"), repository.deleted)
            assertNull(viewModel.uiState.value.errorCode)
        }

    @Test
    fun `a failed delete surfaces in the banner`() =
        runTest {
            val repository = FakeBudgetRepository(listOf(budgetRow(id = "budget:9")))
            repository.nextError = AppError.NotFound
            val viewModel = BudgetsViewModel(repository)

            viewModel.onEvent(BudgetsEvent.DeleteClicked("budget:9"))

            assertEquals(AppError.NotFound.code, viewModel.uiState.value.errorCode)
        }

    @Test
    fun `dismissing the error clears it`() =
        runTest {
            val repository = FakeBudgetRepository(listOf(budgetRow(id = "budget:9")))
            repository.nextError = AppError.NotFound
            val viewModel = BudgetsViewModel(repository)
            viewModel.onEvent(BudgetsEvent.DeleteClicked("budget:9"))

            viewModel.onEvent(BudgetsEvent.DismissError)

            assertNull(viewModel.uiState.value.errorCode)
        }

    // --- FR-BUD-004: the bands, and the permission prompt -----------------------------------------

    @Test
    fun `a crossed band reaches the state as a band, not a colour`() =
        runTest {
            val repository =
                FakeBudgetRepository(
                    listOf(budgetRow(name = "Groceries")),
                    initialAlerts = listOf(alertRow(name = "Groceries", band = BudgetAlertBand.WARN)),
                )

            val state = BudgetsViewModel(repository).uiState.value

            assertEquals(BudgetAlertBand.WARN, state.bandByCategoryId["category:Groceries"])
            assertEquals(1, state.warnedAlerts.size)
            assertTrue(state.overspentAlerts.isEmpty())
        }

    @Test
    fun `the banner separates the two bands`() =
        runTest {
            // The counts drive two different sentences and two different colour roles. Collapsing
            // them would tell a user with one overspend and three warnings that four categories are
            // over budget, which is not true of three of them.
            val repository =
                FakeBudgetRepository(
                    initialAlerts =
                        listOf(
                            alertRow(name = "Dining", band = BudgetAlertBand.EXCEEDED),
                            alertRow(name = "Groceries", band = BudgetAlertBand.WARN),
                            alertRow(name = "Fuel", band = BudgetAlertBand.WARN),
                        ),
                )

            val state = BudgetsViewModel(repository).uiState.value

            assertEquals(1, state.overspentAlerts.size)
            assertEquals(2, state.warnedAlerts.size)
        }

    @Test
    fun `spending crossing a band while the screen is open moves it`() =
        runTest {
            val repository = FakeBudgetRepository(listOf(budgetRow(name = "Groceries")))
            val viewModel = BudgetsViewModel(repository)

            viewModel.uiState.test {
                assertTrue(awaitItem().alerts.isEmpty())

                repository.emitAlerts(listOf(alertRow(name = "Groceries", band = BudgetAlertBand.EXCEEDED)))

                assertEquals(BudgetAlertBand.EXCEEDED, awaitItem().bandByCategoryId["category:Groceries"])
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun `setting a budget asks for permission to notify about it`() =
        runTest {
            // The timing is the design: Android offers this prompt twice in an app's life, and a
            // request made before the user has a budget is a request with no visible reason.
            val repository = FakeBudgetRepository(listOf(budgetRow(id = null, name = "Groceries")))
            val viewModel = BudgetsViewModel(repository)
            viewModel.onEvent(BudgetsEvent.EditClicked("category:Groceries"))
            viewModel.onEvent(BudgetsEvent.AmountChanged("5000"))

            viewModel.onEvent(BudgetsEvent.Save)

            assertTrue(viewModel.uiState.value.requestNotificationPermission)
        }

    @Test
    fun `accepting a suggestion asks too`() =
        runTest {
            val repository = FakeBudgetRepository(initialSuggestions = listOf(suggestionRow(name = "Shopping")))
            val viewModel = BudgetsViewModel(repository)

            viewModel.onEvent(BudgetsEvent.AcceptSuggestion("category:Shopping"))

            assertTrue(viewModel.uiState.value.requestNotificationPermission)
        }

    @Test
    fun `a refused save does not ask for permission`() =
        runTest {
            // Nothing was planned, so there is nothing to be warned about — and spending one of the
            // two prompts Android allows on a write that failed would waste it.
            val repository = FakeBudgetRepository(listOf(budgetRow(id = null, name = "Groceries")))
            repository.nextError = AppError.Storage("disk")
            val viewModel = BudgetsViewModel(repository)
            viewModel.onEvent(BudgetsEvent.EditClicked("category:Groceries"))
            viewModel.onEvent(BudgetsEvent.AmountChanged("5000"))

            viewModel.onEvent(BudgetsEvent.Save)

            assertFalse(viewModel.uiState.value.requestNotificationPermission)
        }

    @Test
    fun `answering the prompt clears the request, whichever way it went`() =
        runTest {
            // The flag is one-shot. Left set, the effect behind it would re-fire on every subsequent
            // state change for the rest of the session.
            val repository = FakeBudgetRepository(initialSuggestions = listOf(suggestionRow(name = "Shopping")))
            val viewModel = BudgetsViewModel(repository)
            viewModel.onEvent(BudgetsEvent.AcceptSuggestion("category:Shopping"))

            viewModel.onEvent(BudgetsEvent.NotificationPermissionSettled)

            assertFalse(viewModel.uiState.value.requestNotificationPermission)
        }

    @Test
    fun `an alert stream that fails leaves the budgets readable`() =
        runTest {
            // The alerts derive from the same read the budget list uses, so a failure that matters
            // has already reached the banner. Two errors on screen for one broken read is worse than
            // one, and the band is the less important of the two things to lose.
            val viewModel = BudgetsViewModel(FailingBudgetRepository())

            assertTrue(viewModel.uiState.value.alerts.isEmpty())
        }

    // --- live updates -----------------------------------------------------------------------------

    @Test
    fun `a transaction landing elsewhere moves the figures on this screen`() =
        runTest {
            val repository = FakeBudgetRepository(listOf(budgetRow(name = "Groceries", spent = Money(400_000L))))
            val viewModel = BudgetsViewModel(repository)

            viewModel.uiState.test {
                assertEquals(Money(400_000L), awaitItem().planned.single().status.spent)

                repository.emitBudgets(listOf(budgetRow(name = "Groceries", spent = Money(900_000L))))

                assertEquals(Money(900_000L), awaitItem().planned.single().status.spent)
                cancelAndIgnoreRemainingEvents()
            }
        }
}
