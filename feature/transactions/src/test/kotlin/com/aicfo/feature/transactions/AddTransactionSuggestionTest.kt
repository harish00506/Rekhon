package com.aicfo.feature.transactions

import com.aicfo.core.common.AppError
import com.aicfo.core.common.FakeClock
import com.aicfo.core.model.Category
import com.aicfo.core.model.CategoryNature
import com.aicfo.core.model.EngineProvenance
import com.aicfo.core.model.RuleCitation
import com.aicfo.domain.engines.classification.CategorySuggestion
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.Instant

/**
 * Tests for Stage-1 auto-categorisation on the add screen (issue 4.2; SRS §8.1, P-02, P-07).
 *
 * Why:  a file of its own rather than more of [AddTransactionViewModelTest], which detekt already
 *       holds at its class-size ceiling — and the split is the honest one anyway: everything here
 *       turns on one question the rest of that file never asks, which is **who decided the
 *       category**. §8.1 lets the app propose one; P-07 says the user disposes. Every test below is
 *       a way that could go wrong:
 *
 *       - a proposal that never arrives is a feature that does nothing;
 *       - a proposal that overrides a choice the user made is the app arguing with them;
 *       - a proposal left selected after its merchant is gone files money under a category nobody
 *         picked, and looks exactly like one that was picked;
 *       - a proposal asked for on every keystroke is a database query per character on the app's
 *         most-used screen.
 * What: the debounced query, the pre-selection, and every way it must yield.
 * Result: "the app decides, the user disposes" is a property tests hold, not a convention.
 * Changelog: 2026-08-10 — Created for issue 4.2.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AddTransactionSuggestionTest {
    private val transactions = FakeTransactionRepository()
    private val accounts = FakeAccountRepository()
    private val clock = FakeClock(initialMillis = Instant.parse("2026-08-10T06:00:00Z").toEpochMilli())

    /** Input: none. Output: `viewModelScope` runs on a test dispatcher whose clock this test drives. */
    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    /** Input: none. Output: restores the real main dispatcher. */
    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    /**
     * Input:  a merchant the classifier recognises, typed into the field.
     * Output: after the debounce, the suggestion is on the state **and the chip is pre-selected** —
     *         the second half being the whole point, since a suggestion the user still has to tap
     *         would cost the tap it exists to save.
     */
    @Test
    fun `typing a known merchant suggests and preselects its category`() =
        runTest {
            categorised(suggested = "category:dining", ruleId = "CLS-MER-001", forMerchant = "SWIGGY")

            val vm = viewModel()
            vm.onEvent(AddTransactionEvent.MerchantChanged("SWIGGY"))
            advanceTimeBy(DEBOUNCE_SETTLED)

            val state = vm.uiState.value
            assertEquals("Dining", state.suggestion?.categoryName)
            assertEquals("CLS-MER-001", state.suggestion?.ruleId)
            assertEquals("category:dining", state.selectedCategoryId)
        }

    /**
     * P-07, as a test.
     * Input:  the user picks a category by hand, then types a merchant the classifier recognises.
     * Output: their choice stands. A proposal that overrode a person would not be a proposal, and
     *         this is the assertion that would fail if the pre-selection were made unconditional.
     */
    @Test
    fun `a category the user picked is never overwritten by a suggestion`() =
        runTest {
            categorised(suggested = "category:dining", ruleId = "CLS-MER-001", forMerchant = "SWIGGY")

            val vm = viewModel()
            vm.onEvent(AddTransactionEvent.CategorySelected("category:groceries"))
            vm.onEvent(AddTransactionEvent.MerchantChanged("SWIGGY"))
            advanceTimeBy(DEBOUNCE_SETTLED)

            assertEquals("category:groceries", vm.uiState.value.selectedCategoryId)
            // Still *offered*, so the user can change their mind — just not applied over them.
            assertEquals("Dining", vm.uiState.value.suggestion?.categoryName)
        }

    /**
     * Input:  a suggestion the user dismisses.
     * Output: both the note and the chip it pre-selected are gone, and a later suggestion for the
     *         same merchant does not re-apply itself. Dismissing a proposal that left its answer
     *         selected behind would dismiss nothing.
     */
    @Test
    fun `dismissing a suggestion clears the chip it preselected and does not come back`() =
        runTest {
            categorised(suggested = "category:dining", ruleId = "CLS-MER-001", forMerchant = "SWIGGY")

            val vm = viewModel()
            vm.onEvent(AddTransactionEvent.MerchantChanged("SWIGGY"))
            advanceTimeBy(DEBOUNCE_SETTLED)
            vm.onEvent(AddTransactionEvent.SuggestionDismissed)

            assertNull(vm.uiState.value.suggestion)
            assertNull(vm.uiState.value.selectedCategoryId)

            vm.onEvent(AddTransactionEvent.MerchantChanged("SWIGGY "))
            advanceTimeBy(DEBOUNCE_SETTLED)
            assertNull("the dismissed suggestion re-applied itself", vm.uiState.value.selectedCategoryId)
        }

    /**
     * Input:  a recognised merchant, then an unrecognised one.
     * Output: the stale pre-selection goes with the suggestion that made it. Leaving it would file
     *         the transaction under a category belonging to a merchant no longer in the field —
     *         wrong, and invisible, because the chip looks exactly like one the user chose.
     */
    @Test
    fun `changing to an unknown merchant clears the previous suggestion and its chip`() =
        runTest {
            categorised(suggested = "category:dining", ruleId = "CLS-MER-001", forMerchant = "SWIGGY")

            val vm = viewModel()
            vm.onEvent(AddTransactionEvent.MerchantChanged("SWIGGY"))
            advanceTimeBy(DEBOUNCE_SETTLED)
            vm.onEvent(AddTransactionEvent.MerchantChanged("SHARMA GENERAL STORE"))
            advanceTimeBy(DEBOUNCE_SETTLED)

            assertNull(vm.uiState.value.suggestion)
            assertNull(vm.uiState.value.selectedCategoryId)
        }

    /**
     * The debounce, asserted as a count rather than as a delay.
     * Input:  six characters typed in quick succession, then a pause.
     * Output: **one** query, for the finished text. Asserting the count is what makes this a test of
     *         the debounce; asserting only the final suggestion would pass with no debounce at all.
     */
    @Test
    fun `the classifier is asked once per pause, not once per keystroke`() =
        runTest {
            val vm = viewModel()
            (1.."SWIGGY".length).forEach { typed ->
                vm.onEvent(AddTransactionEvent.MerchantChanged("SWIGGY".take(typed)))
            }
            advanceTimeBy(DEBOUNCE_SETTLED)

            assertEquals(listOf("SWIGGY"), transactions.suggestionQueries)
        }

    /**
     * Input:  a repository that fails the suggestion read.
     * Output: no suggestion, **no error banner**, and the form still savable. The chip row works
     *         without this feature, so a failed convenience must not interrupt the user — the
     *         opposite of how a failed *accounts* read is handled, and deliberately so.
     */
    @Test
    fun `a failed suggestion is silent and leaves the form usable`() =
        runTest {
            accounts.setAccounts(account())
            transactions.failWith = AppError.Storage("disk")

            val vm = viewModel()
            vm.onEvent(AddTransactionEvent.AmountChanged("250"))
            vm.onEvent(AddTransactionEvent.MerchantChanged("SWIGGY"))
            advanceTimeBy(DEBOUNCE_SETTLED)

            assertNull(vm.uiState.value.suggestion)
            assertNull("a failed suggestion raised a banner", vm.uiState.value.errorCode)
            assertTrue("a failed suggestion disabled Save", vm.uiState.value.canSave)
        }

    /**
     * Result: a ViewModel harness with one account, a two-category taxonomy, and the classifier
     *         primed to answer [suggested] for [forMerchant].
     * Input:  [suggested] — the category id to propose; [ruleId] — the rule it cites;
     *         [forMerchant] — the exact merchant text the suggestion answers to. Output: none.
     */
    private fun categorised(
        suggested: String,
        ruleId: String,
        forMerchant: String,
    ) {
        accounts.setAccounts(account())
        transactions.setCategories(
            Category("category:dining", "Dining", CategoryNature.WANT),
            Category("category:groceries", "Groceries", CategoryNature.NEED),
        )
        transactions.suggestions[forMerchant] =
            CategorySuggestion(
                categoryId = suggested,
                provenance =
                    EngineProvenance(
                        engineId = "auto-categoriser",
                        engineVersion = "1.0",
                        computedAtUtcMillis = clock.nowUtcMillis(),
                        evidence = listOf(RuleCitation(ruleId, "1.0")),
                        confidenceBps = 8_500,
                    ),
            )
    }

    private fun viewModel() = AddTransactionViewModel(transactions, accounts, clock)
}

/**
 * Virtual time to advance so a debounced suggestion has certainly landed (issue 4.2).
 *
 * Comfortably past the ViewModel's 300 ms rather than exactly equal to it: `advanceTimeBy` runs the
 * tasks scheduled *before* the new instant, so a value on the boundary would leave the emission
 * pending and make every assertion below it fail for a reason that has nothing to do with the code.
 */
private const val DEBOUNCE_SETTLED = 400L
