package com.aicfo.domain.engines.networth

import com.aicfo.core.common.Ok
import com.aicfo.core.model.AccountType
import com.aicfo.core.model.Money
import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.random.Random

/**
 * The net-worth identities, over generated portfolios (issue 2.6; FR-ACC-005, MNY-001, P-08).
 *
 * Why:  two things must hold for *every* set of accounts, not just the handful a golden test can
 *       name. **`netWorth == assets − liabilities`** is FR-ACC-005 stated literally. And
 *       **`netWorth == sum(every signed balance)`** is the property that makes the partition safe:
 *       the split into two subtotals exists for P-02's sake, and if it ever changed the answer, the
 *       headline figure would depend on a classification the user never sees. An example test
 *       cannot rule that out across eleven account types and both signs; a generated one can.
 * What: the two identities over seeded portfolios, plus the scale and sign extremes.
 * Result: **money math, so 100% coverage is the gate, not 85%** (CLAUDE.md §4).
 * Changelog: 2026-08-01 — Created for issue 2.6.
 *
 * Seeded, not random: P-08 requires a failure to be reproducible, and a test that fails only
 * sometimes teaches nobody anything.
 */
class NetWorthPropertyTest {
    private val engine = NetWorthEngineFactory.create()

    @Test
    fun `net worth always equals assets minus liabilities`() {
        forEachGeneratedPortfolio { balances ->
            val result = compute(balances)

            assertEquals(result.assets - result.liabilities, result.netWorth)
        }
    }

    @Test
    fun `net worth always equals the plain sum of every signed balance`() {
        // The partition must not change the answer — only explain it.
        forEachGeneratedPortfolio { balances ->
            val summed = balances.fold(Money.ZERO) { running, row -> running + row.balance }

            assertEquals(summed, compute(balances).netWorth)
        }
    }

    @Test
    fun `the two subtotals account for every balance, with nothing double-counted`() {
        forEachGeneratedPortfolio { balances ->
            val result = compute(balances)
            val expectedAssets =
                balances.filterNot { it.type.isLiability }.fold(Money.ZERO) { a, r -> a + r.balance }
            val expectedLiabilities =
                Money.ZERO - balances.filter { it.type.isLiability }.fold(Money.ZERO) { a, r -> a + r.balance }

            assertEquals(expectedAssets, result.assets)
            assertEquals(expectedLiabilities, result.liabilities)
        }
    }

    @Test
    fun `adding an account changes net worth by exactly that account's balance`() {
        // The incremental identity. It is what makes the figure explainable one account at a time,
        // which is how a user checks it.
        val random = Random(SEEDS.first())
        val base = portfolio(random, size = 10)
        val extra =
            AccountBalance(
                "a:extra",
                AccountType.entries.random(random),
                Money(random.nextLong(-9_00_000_00L, 9_00_000_00L)),
            )

        val before = compute(base).netWorth
        val after = compute(base + extra).netWorth

        assertEquals(before + extra.balance, after)
    }

    @Test
    fun `an account and its exact opposite cancel out`() {
        forEachGeneratedPortfolio { balances ->
            val mirrored =
                balances.mapIndexed {
                        index,
                        row,
                    ->
                    row.copy(accountId = "mirror:$index", balance = Money.ZERO - row.balance)
                }

            assertEquals(Money.ZERO, compute(balances + mirrored).netWorth)
        }
    }

    @Test
    fun `large portfolios do not lose a paisa`() {
        // Two hundred accounts at crore scale, plus one stray paisa. If any step went through a
        // Double the paisa would be the first thing to vanish (MNY-001).
        val big = List(200) { AccountBalance("a:$it", AccountType.BANK, Money(1_00_00_000_00L)) }
        val stray = AccountBalance("a:stray", AccountType.CASH, Money(1L))

        assertEquals(Money(200L * 1_00_00_000_00L + 1L), compute(big + stray).netWorth)
    }

    /**
     * Runs [assertion] over one generated portfolio per seed.
     * Why:    several seeds rather than one — a single seed is one portfolio wearing the word
     *         "property". Each is fixed, so a failure names the seed that produced it.
     * Result: the assertion is applied to every generated case.
     * Input:  [assertion] — what must hold. Output: none.
     */
    private fun forEachGeneratedPortfolio(assertion: (List<AccountBalance>) -> Unit) {
        SEEDS.forEach { seed ->
            val random = Random(seed)
            repeat(PORTFOLIOS_PER_SEED) {
                assertion(portfolio(random, size = random.nextInt(0, MAX_ACCOUNTS)))
            }
        }
    }

    /**
     * Result: [size] accounts with types drawn from all eleven and balances of both signs across
     *         several orders of magnitude. Input: [random], [size]. Output: the portfolio.
     */
    private fun portfolio(
        random: Random,
        size: Int,
    ): List<AccountBalance> =
        List(size) { index ->
            AccountBalance(
                accountId = "a:$index",
                type = AccountType.entries[random.nextInt(AccountType.entries.size)],
                // Deliberately unconstrained by type: a liability may come back positive and an
                // asset negative, because both happen and the engine must not "correct" either.
                balance = Money(random.nextLong(-50_00_000_00L, 50_00_000_00L)),
            )
        }

    /** Result: the engine's answer, or a test failure naming the error. Input: [balances]. */
    private fun compute(balances: List<AccountBalance>): NetWorthResult {
        val outcome = engine.compute(NetWorthInput(balances, "2026-08-01", FIXED_MILLIS))
        return (outcome as? Ok)?.value ?: throw AssertionError("expected Ok, got $outcome")
    }

    private companion object {
        /** Fixed seeds, so a failure is reproducible by name (P-08). */
        val SEEDS = listOf(1L, 20_260_801L, 987_654_321L)

        const val PORTFOLIOS_PER_SEED = 40
        const val MAX_ACCOUNTS = 30

        /** 2026-08-01T00:00:00Z. */
        const val FIXED_MILLIS = 1_785_542_400_000L
    }
}
