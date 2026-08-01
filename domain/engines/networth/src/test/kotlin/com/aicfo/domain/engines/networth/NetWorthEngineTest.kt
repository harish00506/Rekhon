package com.aicfo.domain.engines.networth

import com.aicfo.core.common.Ok
import com.aicfo.core.common.Result
import com.aicfo.core.model.AccountType
import com.aicfo.core.model.Money
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for [NetWorthEngine] — FR-ACC-005's assets − liabilities (issue 2.6).
 *
 * Why:  the arithmetic is one subtraction, so the tests that earn their place are the ones about
 *       **which side an account lands on**. That decision is invisible in the headline figure —
 *       net worth is the same whether a negative bank balance is called a small asset or a small
 *       liability — so a misclassification would show up only in the two subtotals the user checks
 *       against their own accounts, which is exactly the kind of error that survives review.
 * What: the golden case the acceptance criteria ask for, the two sign edges, and provenance.
 * Result: FR-ACC-005 is proven on a fixed account set (the AC), and the classification rule is
 *       pinned against the shortcut that would replace it.
 * Changelog: 2026-08-01 — Created for issue 2.6.
 */
class NetWorthEngineTest {
    private val engine = NetWorthEngineFactory.create()

    // --- the golden case (the acceptance criterion) -------------------------------------------

    /**
     * A fixed, realistic household — the same four accounts the demo dataset holds.
     * Frozen on purpose: these figures change only alongside a deliberate change to the formula,
     * never to make a test go green.
     */
    private val goldenBalances =
        listOf(
            AccountBalance("a:savings", AccountType.BANK, Money(1_85_000_00L)),
            AccountBalance("a:cash", AccountType.CASH, Money(5_000_00L)),
            AccountBalance("a:card", AccountType.CREDIT_CARD, Money(-18_000_00L)),
            AccountBalance("a:sip", AccountType.INVESTMENT, Money(1_20_000_00L)),
        )

    @Test
    fun `golden - a four-account household`() {
        val result = engine.compute(input(goldenBalances)).expectOk()

        assertEquals("assets: 1,85,000 + 5,000 + 1,20,000", Money(3_10_000_00L), result.assets)
        assertEquals("liabilities: the card, as a positive magnitude", Money(18_000_00L), result.liabilities)
        assertEquals("net worth: 3,10,000 − 18,000", Money(2_92_000_00L), result.netWorth)
    }

    @Test
    fun `net worth is exactly assets minus liabilities`() {
        // FR-ACC-005's sentence, asserted as an identity rather than as a number.
        val result = engine.compute(input(goldenBalances)).expectOk()

        assertEquals(result.assets - result.liabilities, result.netWorth)
    }

    @Test
    fun `net worth also equals the plain sum of every signed balance`() {
        // The two routes to the same figure. They must never diverge: the partition exists to
        // produce the subtotals (P-02), not to change the answer.
        val summed = goldenBalances.fold(Money.ZERO) { running, row -> running + row.balance }

        assertEquals(summed, engine.compute(input(goldenBalances)).expectOk().netWorth)
    }

    // --- the sign edges, which the type rule exists for ------------------------------------------

    @Test
    fun `an overdrawn bank account stays an asset`() {
        // It is an asset with a negative value, not a debt the user took on. Classifying by sign
        // would move it, leave net worth unchanged, and silently misreport both subtotals.
        val result =
            engine.compute(
                input(listOf(AccountBalance("a:od", AccountType.BANK, Money(-5_000_00L)))),
            ).expectOk()

        assertEquals("the overdraft reduces assets", Money(-5_000_00L), result.assets)
        assertEquals("and is not a liability", Money.ZERO, result.liabilities)
        assertEquals(Money(-5_000_00L), result.netWorth)
    }

    @Test
    fun `a credit card paid past zero stays a liability`() {
        // The mirror case: a positive card balance is credit sitting with the issuer, not savings.
        val result =
            engine.compute(
                input(listOf(AccountBalance("a:card", AccountType.CREDIT_CARD, Money(2_000_00L)))),
            ).expectOk()

        assertEquals("it is not an asset", Money.ZERO, result.assets)
        assertEquals("a negative liability — the user is ahead", Money(-2_000_00L), result.liabilities)
        assertEquals(Money(2_000_00L), result.netWorth)
    }

    @Test
    fun `money owed to the user counts as an asset`() {
        val result =
            engine.compute(
                input(listOf(AccountBalance("a:lent", AccountType.RECEIVABLE, Money(10_000_00L)))),
            ).expectOk()

        assertEquals(Money(10_000_00L), result.assets)
        assertEquals(Money.ZERO, result.liabilities)
    }

    @Test
    fun `money the user owes counts as a liability`() {
        val result =
            engine.compute(
                input(listOf(AccountBalance("a:borrowed", AccountType.PAYABLE, Money(-10_000_00L)))),
            ).expectOk()

        assertEquals(Money.ZERO, result.assets)
        assertEquals(Money(10_000_00L), result.liabilities)
        assertEquals(Money(-10_000_00L), result.netWorth)
    }

    @Test
    fun `every account type lands on exactly one side`() {
        // One balance of each of the eleven types. The totals must account for all of them, with
        // nothing counted twice and nothing dropped.
        val oneOfEach = AccountType.entries.map { AccountBalance("a:${it.storedValue}", it, Money(1_000_00L)) }

        val result = engine.compute(input(oneOfEach)).expectOk()

        val assetCount = AccountType.entries.count { !it.isLiability }
        val liabilityCount = AccountType.entries.count { it.isLiability }
        assertEquals(Money(1_000_00L * assetCount), result.assets)
        assertEquals(Money(-1_000_00L * liabilityCount), result.liabilities)
    }

    // --- the empty and trivial cases ---------------------------------------------------------------

    @Test
    fun `no accounts is a zero, not an error`() {
        // A user who has added nothing has a net worth of zero. Returning an error would make the
        // dashboard show a failure for a perfectly ordinary state.
        val result = engine.compute(input(emptyList())).expectOk()

        assertEquals(Money.ZERO, result.assets)
        assertEquals(Money.ZERO, result.liabilities)
        assertEquals(Money.ZERO, result.netWorth)
    }

    @Test
    fun `an odd paise survives — nothing here rounds`() {
        // MNY-001: there is no division in this engine, so there is nothing to round, and 1 paisa
        // must come back as 1 paisa.
        val result =
            engine.compute(
                input(
                    listOf(
                        AccountBalance("a:1", AccountType.BANK, Money(1_23_456_79L)),
                        AccountBalance("a:2", AccountType.CREDIT_CARD, Money(-1L)),
                    ),
                ),
            ).expectOk()

        assertEquals(Money(1_23_456_78L), result.netWorth)
    }

    @Test
    fun `liabilities alone give a negative net worth`() {
        val result =
            engine.compute(
                input(
                    listOf(
                        AccountBalance("a:card", AccountType.CREDIT_CARD, Money(-18_000_00L)),
                        AccountBalance("a:loan", AccountType.LOAN, Money(-12_00_000_00L)),
                    ),
                ),
            ).expectOk()

        assertEquals(Money(12_18_000_00L), result.liabilities)
        assertEquals("a user who owes more than they hold", Money(-12_18_000_00L), result.netWorth)
    }

    // --- provenance (AI-ARC-003, AI-ARC-006) ---------------------------------------------------------

    @Test
    fun `the result carries the engine that produced it and when`() {
        val result = engine.compute(input(goldenBalances, nowUtcMillis = FIXED_MILLIS)).expectOk()

        assertEquals("net-worth", result.provenance.engineId)
        assertEquals("1.0", result.provenance.engineVersion)
        assertEquals(
            "the injected instant, never a wall-clock read",
            FIXED_MILLIS,
            result.provenance.computedAtUtcMillis,
        )
    }

    @Test
    fun `no rule is cited, because none fired`() {
        // Arithmetic, not a threshold. A citation here would imply the app applied a rulebook row it
        // did not — which is exactly the kind of false trail P-02 exists to prevent.
        assertTrue(engine.compute(input(goldenBalances)).expectOk().provenance.evidence.isEmpty())
    }

    @Test
    fun `the result echoes the day it describes`() {
        val result = engine.compute(input(goldenBalances, asOf = "2026-03-17")).expectOk()

        assertEquals("2026-03-17", result.asOfIsoDate)
        assertEquals("and records it as the window read", "2026-03-17", result.provenance.inputWindow)
    }

    @Test
    fun `the same input always gives the same result`() {
        // P-08. Trivially true for pure arithmetic, and asserted anyway because the day this engine
        // grows a clock read or a map iteration order dependency is the day it stops being true.
        assertEquals(
            engine.compute(input(goldenBalances)).expectOk(),
            engine.compute(input(goldenBalances)).expectOk(),
        )
    }

    @Test
    fun `the order accounts arrive in does not change the answer`() {
        val forwards = engine.compute(input(goldenBalances)).expectOk()
        val backwards = engine.compute(input(goldenBalances.reversed())).expectOk()

        assertEquals(forwards.assets, backwards.assets)
        assertEquals(forwards.liabilities, backwards.liabilities)
        assertEquals(forwards.netWorth, backwards.netWorth)
    }

    /** Result: an input with the fixed clock and day unless overridden. Output: [NetWorthInput]. */
    private fun input(
        balances: List<AccountBalance>,
        asOf: String = "2026-08-01",
        nowUtcMillis: Long = FIXED_MILLIS,
    ) = NetWorthInput(balances = balances, asOfIsoDate = asOf, nowUtcMillis = nowUtcMillis)

    private companion object {
        /** 2026-08-01T00:00:00Z. Fixed, because provenance stamps it (TIM-001, P-08). */
        const val FIXED_MILLIS = 1_785_542_400_000L
    }
}

/**
 * Unwraps a result the test expects to have succeeded.
 * Why:    a failure names the error rather than throwing a bare `ClassCastException` thirty lines
 *         from the assertion that mattered.
 * Result: the value. Input: the receiver. Output: [T].
 * Changelog: 2026-08-01 — Created for issue 2.6.
 */
private fun <T> Result<T, com.aicfo.core.common.AppError>.expectOk(): T =
    when (this) {
        is Ok -> value
        else -> throw AssertionError("expected Ok, got $this")
    }
