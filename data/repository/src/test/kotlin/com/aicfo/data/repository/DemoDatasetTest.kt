package com.aicfo.data.repository

import com.aicfo.core.common.FakeClock
import com.aicfo.core.common.toProfileDate
import com.aicfo.core.model.AccountType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * Tests for the demo dataset (issue 2.4; FR-ONB-004, P-08, MNY-001, TIM-002).
 *
 * Why:  a sample dataset is the one kind of code where "it looks about right on screen" is the only
 *       feedback anybody ever gets, so every property that makes it *trustworthy* has to be asserted
 *       instead. Three matter. It must be **deterministic** — the acceptance criterion says seeded,
 *       and a demo that differs between two launches cannot be reasoned about or supported. It must
 *       be **isolated** — every row carrying the demo profile id is what makes the wipe complete.
 *       And it must obey the money and time rules the rest of the app does, because the moment it
 *       does not, it teaches the wrong shape to whatever copies it.
 * What: determinism, that the seed is genuinely used, profile scoping, the golden row counts, the
 *       date window, arithmetic consistency, and provenance.
 * Result: the fixture half of FR-ONB-004 is provable on the JVM without a device.
 * Changelog: 2026-07-28 — Created for issue 2.4.
 *
 * The clock is fixed at a **mid-month day in March 2026** on purpose. Mid-month exercises the
 * "do not show spending that has not happened yet" truncation; a window ending in March means it
 * spans February, which is where the length-of-month clamp on the month-end insurance debit fires.
 */
class DemoDatasetTest {
    private val clock = FakeClock(initialMillis = MID_MARCH_MILLIS, initialZone = ZoneId.of("Asia/Kolkata"))

    /**
     * Input:  the same clock and the same seed, twice.
     * Output: asserts the two datasets are identical in every field. This is the acceptance
     *         criterion "the dataset is deterministic (seeded)" stated directly (P-08). Without it
     *         the demo would be untestable, unsupportable, and would show two users different money.
     */
    @Test
    fun `the same clock and seed always produce the same rows`() {
        assertEquals(DemoDataset.build(clock), DemoDataset.build(clock))
    }

    /**
     * Input:  the same clock with a different seed.
     * Output: asserts the discretionary amounts actually change — which proves the seed is wired to
     *         the generator rather than being a decorative constant. Without this, the determinism
     *         test above would still pass if the jitter were accidentally removed, and the dataset
     *         would silently become a flat repeating month.
     *
     *         The **total** count is deliberately not asserted equal across seeds: the jitter moves
     *         which day a row lands on, so it moves how many of the current month's rows survive the
     *         truncation at today. A *complete* month is the structural invariant, and that is what
     *         is checked instead.
     */
    @Test
    fun `a different seed produces a different dataset`() {
        val standard = DemoDataset.build(clock)
        val perturbed = DemoDataset.build(clock, seed = DemoDataset.DEMO_SEED + 1)

        assertNotEquals(standard.transactions, perturbed.transactions)
        assertEquals(ROWS_PER_COMPLETE_MONTH, perturbed.transactions.count { it.bookedOnIsoDate.startsWith("2026-02") })
    }

    /**
     * Input:  a generated dataset.
     * Output: asserts **every** row carries the demo profile id. This is the isolation guarantee the
     *         whole design rests on (ADR-0006): one row scoped to `local` by mistake would be sample
     *         data the wipe cannot reach and the user cannot tell from their own.
     */
    @Test
    fun `every row is scoped to the demo profile`() {
        val rows = DemoDataset.build(clock)
        val demo = DemoModeRepository.DEMO_PROFILE_ID

        assertEquals(demo, rows.profile.id)
        assertTrue(rows.accounts.all { it.profileId == demo })
        assertTrue(rows.categories.all { it.profileId == demo })
        assertTrue(rows.transactions.all { it.profileId == demo })
        assertTrue(rows.budgets.all { it.profileId == demo })
        assertTrue(rows.recurring.all { it.profileId == demo })
    }

    /**
     * Input:  a generated dataset.
     * Output: asserts the golden row counts. Frozen figures, not derived ones: recomputing the
     *         expectation from the same specs the generator reads would assert nothing at all. A
     *         change to the dataset reds this test, which is the intended signal — update the
     *         constant deliberately, never to go green.
     */
    @Test
    fun `the dataset has its recorded shape`() {
        val rows = DemoDataset.build(clock)

        assertEquals(ACCOUNT_COUNT, rows.accounts.size)
        assertEquals(CATEGORY_COUNT, rows.categories.size)
        assertEquals(TRANSACTION_COUNT, rows.transactions.size)
        // Needs, wants, savings — the engine's three envelopes for the current month.
        assertEquals(3, rows.budgets.size)
        // Income, rent, savings — one per answered seed.
        assertEquals(3, rows.recurring.size)
    }

    /**
     * Input:  a generated dataset.
     * Output: asserts a full month in the middle of the window holds exactly the expected number of
     *         rows — the fixed obligations plus every jittered occurrence. Asserting a *whole* month
     *         rather than the total is what distinguishes "the generator produced the right rows" from
     *         "the truncation happened to land on the right count".
     */
    @Test
    fun `a complete month holds every generated row`() {
        val february = DemoDataset.build(clock).transactions.filter { it.bookedOnIsoDate.startsWith("2026-02") }

        assertEquals(ROWS_PER_COMPLETE_MONTH, february.size)
    }

    /**
     * Input:  a generated dataset built on a mid-month clock.
     * Output: asserts nothing is dated after today and nothing before the window opens (TIM-002).
     *         Future spending would put the forecast engines into a state no real user can reach, and
     *         a row outside the window would be data the demo's own summary cannot explain.
     */
    @Test
    fun `no transaction falls outside the three-month window ending today`() {
        val rows = DemoDataset.build(clock)
        val today = clock.today()
        val windowStart = today.withDayOfMonth(1).minusMonths(2)

        rows.transactions.forEach { transaction ->
            val date = LocalDate.parse(transaction.bookedOnIsoDate)
            assertTrue("$date is in the future", !date.isAfter(today))
            assertTrue("$date is before the window", !date.isBefore(windowStart))
        }
    }

    /**
     * Input:  a generated dataset.
     * Output: asserts each stored instant resolves back to the ISO day it is booked on, in the
     *         profile zone. TIM-001/TIM-002 in one assertion: the pair is the exact place a 23:30 IST
     *         transaction ends up filed on the wrong day, and midday timestamps are what avoid it.
     */
    @Test
    fun `each instant resolves to its booked day in the profile zone`() {
        DemoDataset.build(clock).transactions.forEach { transaction ->
            assertEquals(
                LocalDate.parse(transaction.bookedOnIsoDate),
                clock.toProfileDate(transaction.occurredAtUtcMillis),
            )
        }
    }

    /**
     * Input:  a dataset whose window spans February.
     * Output: asserts the month-end insurance debit lands on the 28th rather than being lost. A
     *         `withDayOfMonth(31)` on February throws, so without the clamp this whole dataset would
     *         fail to build in any window containing a short month — three months of the year.
     */
    @Test
    fun `a month-end obligation is clamped to a short month`() {
        val february =
            DemoDataset.build(clock).transactions
                .filter { it.merchant == "Term cover premium" && it.bookedOnIsoDate.startsWith("2026-02") }

        assertEquals(1, february.size)
        assertEquals("2026-02-28", february.single().bookedOnIsoDate)
    }

    /**
     * Input:  a generated dataset.
     * Output: asserts every amount is a whole number of rupees in paise (MNY-001). Jitter is drawn in
     *         rupees precisely so a grocery bill does not come out as ₹2,847.63, which reads as a
     *         rounding bug rather than as a shopping trip.
     */
    @Test
    fun `every amount is a whole rupee in paise`() {
        val rows = DemoDataset.build(clock)

        assertTrue(rows.transactions.all { it.amountMinor % 100L == 0L })
        assertTrue(rows.accounts.all { it.openingBalanceMinor % 100L == 0L })
    }

    /**
     * Input:  a generated dataset.
     * Output: asserts each account's closing balance is its opening balance plus its own
     *         transactions. A demo whose balances disagree with its transaction list would be the
     *         first thing a user notices when issue 3.6 renders that list, and the conclusion they
     *         would draw is that the app cannot add up.
     */
    @Test
    fun `each closing balance is its opening balance plus its own transactions`() {
        val rows = DemoDataset.build(clock)

        rows.accounts.forEach { account ->
            val movement = rows.transactions.filter { it.accountId == account.id }.sumOf { it.amountMinor }
            assertEquals(account.name, account.openingBalanceMinor + movement, account.currentBalanceMinor)
        }
    }

    /**
     * Input:  a generated dataset.
     * Output: asserts the signs follow the `transactions` convention — the salary is an inflow and
     *         everything else an outflow. Getting this backwards would show a demo user spending
     *         their salary and earning their rent, and every derived figure in the app would follow.
     */
    @Test
    fun `income is positive and spending negative`() {
        val transactions = DemoDataset.build(clock).transactions
        val (income, spending) = transactions.partition { it.merchant == "Monthly salary" }

        assertTrue(income.isNotEmpty())
        assertTrue(income.all { it.amountMinor > 0L })
        assertTrue(spending.all { it.amountMinor < 0L })
    }

    /**
     * Input:  a generated dataset.
     * Output: asserts the budget envelopes carry a rule id and version (P-02, AI-ARC-006). They are
     *         not typed into the fixture — they come from the real quick-setup engine — and the
     *         citation is the evidence of that. A demo budget with no derivation would be exactly the
     *         black-box number P-02 exists to forbid.
     */
    @Test
    fun `the demo budget carries the rule that produced it`() {
        val budgets = DemoDataset.build(clock).budgets

        assertTrue(budgets.isNotEmpty())
        budgets.forEach { budget ->
            assertTrue("a demo budget must cite its rule", !budget.ruleId.isNullOrBlank())
            assertTrue(!budget.ruleVersion.isNullOrBlank())
            assertEquals(SOURCE_DEMO, budget.source)
        }
    }

    /**
     * Input:  a generated dataset.
     * Output: asserts every row that has a `source` column says `demo`, and that the recurring rules
     *         arrive **unconfirmed**. FR-TXN-006 says an unconfirmed rule creates nothing; a demo
     *         that shipped confirmed rules would start proposing transactions from fabricated
     *         figures.
     */
    @Test
    fun `every row is marked as demo data and no rule is pre-confirmed`() {
        val rows = DemoDataset.build(clock)

        assertTrue(rows.transactions.all { it.source == SOURCE_DEMO })
        assertTrue(rows.recurring.all { it.source == SOURCE_DEMO })
        assertTrue(rows.recurring.none { it.isConfirmed })
    }

    /**
     * Input:  a generated dataset.
     * Output: asserts transaction ids are unique and derived from position rather than generated
     *         (P-08). Random ids would make re-entering the demo append a second copy of every
     *         transaction instead of replacing the first.
     */
    @Test
    fun `transaction ids are unique and derived`() {
        val ids = DemoDataset.build(clock).transactions.map { it.id }

        assertEquals(ids.size, ids.toSet().size)
        assertEquals("${DemoModeRepository.DEMO_PROFILE_ID}:txn:0000", ids.first())
    }

    /**
     * Input:  the salary rows.
     * Output: asserts income carries **no** category. The §8.3 nature set has no income member, and
     *         inventing one in a fixture would be this file deciding a classification that issue
     *         4.3's classifier owns.
     */
    @Test
    fun `income rows carry no category`() {
        DemoDataset.build(clock).transactions
            .filter { it.merchant == "Monthly salary" }
            .forEach { assertNull(it.categoryId) }
    }

    /**
     * Every demo account carries a type FR-ACC-001 actually names (issue 2.5).
     *
     * Why:  until issue 2.5 this file asserted row *counts* and never the type strings, and the
     *       demo's credit card was stored as `"card"` — a value in no SRS list, which no type-aware
     *       query would ever have matched. Nothing caught it because nothing looked. This is the
     *       gate that looks: the demo is the app's own sample data, so a type it invents is a type
     *       the app is teaching itself is real.
     */
    @Test
    fun `every demo account has a type FR-ACC-001 recognises`() {
        DemoDataset.build(clock).accounts.forEach { account ->
            assertNotNull(
                "${account.name} has type '${account.type}', which is not an AccountType",
                AccountType.fromStored(account.type),
            )
        }
    }

    @Test
    fun `the demo covers an asset and a liability, so net worth has both sides`() {
        // Issue 2.6 subtracts liabilities from assets. A sample dataset with only assets would let
        // that engine ship with its subtraction never once exercised.
        val types = DemoDataset.build(clock).accounts.mapNotNull { AccountType.fromStored(it.type) }

        assertTrue("the demo needs a bank account", AccountType.BANK in types)
        assertTrue("the demo needs a liability", AccountType.CREDIT_CARD in types)
    }

    private companion object {
        /** 2026-03-17, 11:30 IST — mid-month, in a window that spans February. */
        val MID_MARCH_MILLIS: Long = Instant.parse("2026-03-17T06:00:00Z").toEpochMilli()

        /** Bank, cash, card, investment folio. */
        const val ACCOUNT_COUNT = 4

        /** Seven needs, four wants, one investment. */
        const val CATEGORY_COUNT = 12

        /** Five fixed obligations plus twenty-four jittered occurrences. */
        const val ROWS_PER_COMPLETE_MONTH = 29

        /**
         * The frozen total for [MID_MARCH_MILLIS]: two complete months (29 each) plus the 18 March
         * rows that fall on or before the 17th. Recorded from a run, then pinned — change it only
         * alongside a deliberate change to the dataset, never to go green.
         */
        const val TRANSACTION_COUNT = 76
    }
}
