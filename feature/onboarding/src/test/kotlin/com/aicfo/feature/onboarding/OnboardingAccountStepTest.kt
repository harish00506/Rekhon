package com.aicfo.feature.onboarding

import androidx.lifecycle.SavedStateHandle
import com.aicfo.core.common.AppError
import com.aicfo.core.common.FakeClock
import com.aicfo.core.model.AccountType
import com.aicfo.core.model.Money
import com.aicfo.domain.engines.quicksetup.QuickSetupEngineFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.ZoneId

/**
 * Tests for onboarding's ACCOUNT step (issue 2.5; FR-ONB-001 step 4, FR-ACC-001).
 *
 * Why:  this step closes two things that have been open since Epic 2 began. **FR-ONB-001 has never
 *       been satisfied** — ADR-0002 deferred its fourth step twice, saying each time that the
 *       requirement was not met yet. And **issue 2.3's recurring rules have had a null
 *       `account_id`** with a comment naming this issue as the one that would fill it. Both are
 *       asserted here rather than assumed.
 *
 *       The third thing asserted is what does *not* happen: a user who leaves the name blank must
 *       end up with no account at all. An empty "My Account" invented for them would be fabricated
 *       data (P-03), and it is the kind of default that looks harmless until the net-worth engine
 *       counts it.
 * What: the write, the attach, the skip, the money parsing, and process death.
 * Result: FR-ONB-001's last step is proven, including its refusals.
 * Changelog: 2026-07-28 — Created for issue 2.5.
 *
 * Kept in its own file rather than appended to [OnboardingViewModelTest], which is already the
 * longest test in the module — the split is by step, which is how the production code is split too.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class OnboardingAccountStepTest {
    private val settings = FakeSettingsStore()
    private val consents = FakeConsentStore()
    private val appLock = FakeAppLockStore()
    private val pinVerifier = FakePinVerifier()
    private val quickSetup = FakeQuickSetupRepository()
    private val accounts = FakeAccountRepository()
    private val demoMode = FakeDemoModeRepository()
    private val engine = QuickSetupEngineFactory.create()
    private val clock = FakeClock(initialZone = ZoneId.of("Asia/Kolkata"))
    private val savedState = SavedStateHandle()

    /** Input: none. Output: pins `viewModelScope` to a test dispatcher so saves run inline. */
    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    /** Input: none. Output: releases the main dispatcher between tests. */
    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun viewModel() =
        OnboardingViewModel(
            OnboardingWriter(settings, consents),
            AppLockSetup(pinVerifier, appLock),
            FinancialSetupCoordinator(engine, quickSetup, accounts, clock),
            demoMode,
            clock,
            savedState,
        )

    /**
     * Advances to a named step.
     * Why:    derived from the enum rather than counting Next presses, which is what made four
     *         tests in this module break the moment a step was inserted.
     * Result: the flow sits on [target]. Input: [target]. Output: none.
     */
    private fun OnboardingViewModel.goTo(target: OnboardingStep) {
        repeat(target.ordinal) { onEvent(OnboardingEvent.Next) }
    }

    /** Presses Next until the flow finishes. Input: none. Output: none. */
    private fun OnboardingViewModel.finishFlow() {
        repeat(OnboardingStep.entries.size) {
            if (uiState.value.isComplete) return
            onEvent(OnboardingEvent.Next)
        }
    }

    // --- the write ------------------------------------------------------------------------------

    @Test
    fun `the account step creates the account and attaches the seeded rules`() {
        val viewModel = viewModel()
        viewModel.onEvent(OnboardingEvent.MonthlyIncomeChanged("85000"))
        viewModel.goTo(OnboardingStep.ACCOUNT)
        viewModel.onEvent(OnboardingEvent.AccountNameChanged("HDFC Savings"))
        viewModel.onEvent(OnboardingEvent.AccountTypeChanged(AccountType.BANK))
        viewModel.onEvent(OnboardingEvent.AccountOpeningBalanceChanged("1,25,000"))
        viewModel.onEvent(OnboardingEvent.Next)

        val draft = accounts.createdDrafts.single()
        assertEquals("HDFC Savings", draft.name)
        assertEquals(AccountType.BANK, draft.type)
        assertEquals(Money(1_25_000_00L), draft.openingBalance)
        // The loose end from issue 2.3: those rules were written with account_id = null because
        // there was nothing to point them at.
        assertEquals("account:1", quickSetup.attachedAccountId)
        assertTrue(viewModel.uiState.value.isComplete)
    }

    @Test
    fun `the account is created in the profile's currency`() {
        val viewModel = viewModel()
        viewModel.goTo(OnboardingStep.PROFILE)
        viewModel.onEvent(OnboardingEvent.CurrencyChanged("INR"))
        viewModel.goTo(OnboardingStep.ACCOUNT)
        viewModel.onEvent(OnboardingEvent.AccountNameChanged("Cash"))
        viewModel.onEvent(OnboardingEvent.Next)

        assertEquals("INR", accounts.createdDrafts.single().currencyCode)
    }

    @Test
    fun `the account name is trimmed`() {
        val viewModel = viewModel()
        viewModel.goTo(OnboardingStep.ACCOUNT)
        viewModel.onEvent(OnboardingEvent.AccountNameChanged("  HDFC Savings  "))
        viewModel.onEvent(OnboardingEvent.Next)

        assertEquals("HDFC Savings", accounts.createdDrafts.single().name)
    }

    // --- the refusals ---------------------------------------------------------------------------

    @Test
    fun `leaving the account name blank writes no account`() {
        // Not an empty one, not a zero-balance placeholder. Absent.
        val viewModel = viewModel()
        viewModel.finishFlow()

        assertTrue("a skipped step must create nothing", accounts.createdDrafts.isEmpty())
        assertNull(quickSetup.attachedAccountId)
        assertTrue("skipping still completes onboarding", viewModel.uiState.value.isComplete)
    }

    @Test
    fun `a whitespace-only account name counts as skipped`() {
        val viewModel = viewModel()
        viewModel.goTo(OnboardingStep.ACCOUNT)
        viewModel.onEvent(OnboardingEvent.AccountNameChanged("   "))
        viewModel.onEvent(OnboardingEvent.Next)

        assertTrue(accounts.createdDrafts.isEmpty())
        assertTrue(viewModel.uiState.value.isComplete)
    }

    @Test
    fun `skipping the account step finishes without creating one`() {
        // Skip on the last step still means finish, as it did before issue 2.5 moved quick setup
        // off the end of the flow.
        val viewModel = viewModel()
        viewModel.goTo(OnboardingStep.ACCOUNT)

        viewModel.onEvent(OnboardingEvent.SkipQuickSetup)

        assertTrue(accounts.createdDrafts.isEmpty())
        assertTrue(viewModel.uiState.value.isComplete)
    }

    @Test
    fun `an unrepresentable opening balance blocks the save`() {
        // Guessing what "12.345" meant is the one thing this app must never do about money.
        val viewModel = viewModel()
        viewModel.goTo(OnboardingStep.ACCOUNT)
        viewModel.onEvent(OnboardingEvent.AccountNameChanged("HDFC"))
        viewModel.onEvent(OnboardingEvent.AccountOpeningBalanceChanged("12.345"))
        viewModel.onEvent(OnboardingEvent.Next)

        assertTrue(accounts.createdDrafts.isEmpty())
        assertFalse(viewModel.uiState.value.isComplete)
        assertEquals(AppError.Validation("openingBalance").code, viewModel.uiState.value.errorCode)
    }

    @Test
    fun `a failed account write surfaces the error and does not complete`() {
        // The account write is last in the chain, so everything before it succeeded. The user must
        // be told rather than landed on a dashboard missing the account they just entered.
        accounts.failWith = AppError.Storage("disk")
        val viewModel = viewModel()
        viewModel.goTo(OnboardingStep.ACCOUNT)
        viewModel.onEvent(OnboardingEvent.AccountNameChanged("HDFC"))
        viewModel.onEvent(OnboardingEvent.Next)

        assertFalse(viewModel.uiState.value.isComplete)
        assertEquals(AppError.Storage("disk").code, viewModel.uiState.value.errorCode)
    }

    // --- the money ------------------------------------------------------------------------------

    @Test
    fun `an account with no opening balance is created at zero`() {
        // Ordinary, not an error: making the amount required would block the users least sure of
        // their numbers on an optional step.
        val viewModel = viewModel()
        viewModel.goTo(OnboardingStep.ACCOUNT)
        viewModel.onEvent(OnboardingEvent.AccountNameChanged("Cash"))
        viewModel.onEvent(OnboardingEvent.Next)

        assertEquals(Money.ZERO, accounts.createdDrafts.single().openingBalance)
    }

    @Test
    fun `a negative opening balance keeps its sign`() {
        // A card holds what is owed, and the net-worth engine (issue 2.6) subtracts by adding a
        // negative. An absolute value here would silently turn a debt into an asset.
        val viewModel = viewModel()
        viewModel.goTo(OnboardingStep.ACCOUNT)
        viewModel.onEvent(OnboardingEvent.AccountNameChanged("ICICI Card"))
        viewModel.onEvent(OnboardingEvent.AccountTypeChanged(AccountType.CREDIT_CARD))
        viewModel.onEvent(OnboardingEvent.AccountOpeningBalanceChanged("-18000"))
        viewModel.onEvent(OnboardingEvent.Next)

        assertEquals(Money(-18_000_00L), accounts.createdDrafts.single().openingBalance)
    }

    @Test
    fun `paise are not lost`() {
        val viewModel = viewModel()
        viewModel.goTo(OnboardingStep.ACCOUNT)
        viewModel.onEvent(OnboardingEvent.AccountNameChanged("HDFC"))
        viewModel.onEvent(OnboardingEvent.AccountOpeningBalanceChanged("1234.56"))
        viewModel.onEvent(OnboardingEvent.Next)

        assertEquals(Money(1_234_56L), accounts.createdDrafts.single().openingBalance)
    }

    // --- state ----------------------------------------------------------------------------------

    @Test
    fun `the account answers survive process death`() {
        // Every field added to the state has to be added to the bundle — the thing a test catches
        // and a reviewer does not.
        val first = viewModel()
        first.goTo(OnboardingStep.ACCOUNT)
        first.onEvent(OnboardingEvent.AccountNameChanged("HDFC Savings"))
        first.onEvent(OnboardingEvent.AccountTypeChanged(AccountType.CREDIT_CARD))
        first.onEvent(OnboardingEvent.AccountOpeningBalanceChanged("1250"))

        val restored = viewModel().uiState.value

        assertEquals(OnboardingStep.ACCOUNT, restored.step)
        assertEquals("HDFC Savings", restored.accountName)
        assertEquals(AccountType.CREDIT_CARD, restored.accountType)
        assertEquals("1250", restored.accountOpeningBalanceText)
    }

    @Test
    fun `the account step defaults to a bank account`() {
        assertEquals(AccountType.BANK, viewModel().uiState.value.accountType)
    }

    @Test
    fun `skipping quick setup still writes no seeds once another step follows it`() {
        // Before issue 2.5 quick setup was last, so Skip finished immediately and the decision
        // never had to be remembered. It does now, and forgetting it would write the very seeds the
        // user declined.
        val viewModel = viewModel()
        viewModel.onEvent(OnboardingEvent.MonthlyIncomeChanged("85000"))
        viewModel.goTo(OnboardingStep.QUICK_SETUP)

        viewModel.onEvent(OnboardingEvent.SkipQuickSetup)
        assertEquals("Skip must advance, not finish", OnboardingStep.ACCOUNT, viewModel.uiState.value.step)
        viewModel.onEvent(OnboardingEvent.Next)

        assertEquals(0, quickSetup.applyCallCount)
        assertNull(settings.savedProfile!!.quickSetup.monthlyIncome)
        assertTrue(viewModel.uiState.value.isComplete)
    }

    @Test
    fun `only the two data steps are skippable`() {
        // The app cannot work without a time zone, and welcome and consent ask for nothing to skip.
        assertEquals(
            listOf(OnboardingStep.QUICK_SETUP, OnboardingStep.ACCOUNT),
            OnboardingStep.entries.filter { it.isSkippable },
        )
    }
}
