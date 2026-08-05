package com.aicfo.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Locks [AccountType] to the SRS (issue 2.5; FR-ACC-001, §20.2).
 *
 * Why:  the stored strings are a **persistence contract**, not an implementation detail — once a
 *       user has a row saying `credit_card`, renaming that string orphans their account silently.
 *       So the list is asserted against §20.2's DDL literally, here, rather than trusted to stay
 *       right. The absent `wallet` is asserted too: it was in issue 1.6's guess, it is in no SRS
 *       list, and a test is the only thing that stops it drifting back in.
 * What: the type vocabulary, the round trip, and the forward-compatibility of an unknown value.
 * Result: a change to a stored value cannot ship without a reviewer seeing this file change.
 * Changelog: 2026-07-28 — Created for issue 2.5.
 */
class AccountTest {
    /**
     * The eleven values §20.2's CHECK constraint permits, spelled exactly as it spells them.
     * Copied by hand from the SRS rather than derived from the enum — a test that derives its
     * expectation from the code under test asserts nothing.
     */
    private val srsTypeValues =
        listOf(
            "bank", "cash", "credit_card", "loan", "investment",
            "gold", "crypto", "property", "vehicle", "receivable", "payable",
        )

    @Test
    fun `every SRS account type exists, and no others`() {
        assertEquals(srsTypeValues.sorted(), AccountType.entries.map { it.storedValue }.sorted())
    }

    @Test
    fun `FR-ACC-001 names eleven types`() {
        assertEquals(11, AccountType.entries.size)
    }

    @Test
    fun `wallet is not an account type`() {
        // It was in AccountEntity's original doc comment (issue 1.6) and in no SRS list.
        assertNull(AccountType.fromStored("wallet"))
    }

    @Test
    fun `every stored value round-trips`() {
        AccountType.entries.forEach { type ->
            assertEquals(type, AccountType.fromStored(type.storedValue))
        }
    }

    @Test
    fun `an unknown stored value returns null rather than throwing`() {
        // A row written by a newer build. The list shows fewer accounts; it does not crash.
        assertNull(AccountType.fromStored("timeshare"))
    }

    @Test
    fun `parsing is case-sensitive and does not trim`() {
        // The column holds exactly what was written. Being lenient here would let two spellings of
        // the same type coexist, and only one of them would match a WHERE clause.
        assertNull(AccountType.fromStored("BANK"))
        assertNull(AccountType.fromStored(" bank"))
    }

    @Test
    fun `an empty stored value returns null`() {
        assertNull(AccountType.fromStored(""))
    }

    @Test
    fun `the stored value of the credit card differs from its constant name`() {
        // The reason `name.lowercase()` is not used anywhere: it would store `credit_card` today
        // and silently break if the constant were ever renamed.
        assertEquals("credit_card", AccountType.CREDIT_CARD.storedValue)
        assertTrue(AccountType.CREDIT_CARD.name != AccountType.CREDIT_CARD.storedValue)
    }

    @Test
    fun `stored values are unique`() {
        val values = AccountType.entries.map { it.storedValue }
        assertEquals(values.size, values.toSet().size)
    }

    // --- asset vs liability (issue 2.6; FR-ACC-005) -----------------------------------------------

    /**
     * The three types the user owes on, written out by hand.
     * Derived from FR-ACC-005's meaning rather than from the enum: a test that asks the code what it
     * thinks would agree with any answer, including a wrong one.
     */
    private val liabilityTypeValues = listOf("credit_card", "loan", "payable")

    @Test
    fun `exactly three account types are liabilities`() {
        assertEquals(
            liabilityTypeValues.sorted(),
            AccountType.entries.filter { it.isLiability }.map { it.storedValue }.sorted(),
        )
    }

    @Test
    fun `money owed to you is an asset, money you owe is a liability`() {
        // The pair most likely to be swapped, and the swap would be invisible in any total that
        // happened to hold only one of them.
        assertFalse("a receivable is money owed TO the user", AccountType.RECEIVABLE.isLiability)
        assertTrue("a payable is money the user owes", AccountType.PAYABLE.isLiability)
    }

    @Test
    fun `every type is on exactly one side`() {
        // A partition, not two overlapping lists — net worth double-counts otherwise.
        val assets = AccountType.entries.filterNot { it.isLiability }
        val liabilities = AccountType.entries.filter { it.isLiability }

        assertEquals(AccountType.entries.size, assets.size + liabilities.size)
        assertTrue("the two sides must not overlap", assets.intersect(liabilities.toSet()).isEmpty())
    }

    @Test
    fun `an investment is an asset even though it can lose value`() {
        assertFalse(AccountType.INVESTMENT.isLiability)
        assertFalse(AccountType.CRYPTO.isLiability)
    }

    @Test
    fun `an account carries both the opening and the derived balance`() {
        // The two are different questions and the model keeps them apart: the opening balance is
        // what the user typed once, the balance is that plus everything since (DB-001).
        val account =
            Account(
                id = "account:1",
                profileId = "local",
                name = "HDFC Savings",
                type = AccountType.BANK,
                institution = "HDFC Bank",
                openingBalance = Money(1_00_000_00L),
                balance = Money(1_23_450_00L),
                currencyCode = "INR",
                isArchived = false,
            )

        assertEquals(Money(1_00_000_00L), account.openingBalance)
        assertEquals(Money(1_23_450_00L), account.balance)
    }

    @Test
    fun `an account may have no institution`() {
        // Cash has no issuer, and neither does an informal receivable.
        val cash =
            Account(
                id = "account:2",
                profileId = "local",
                name = "Cash",
                type = AccountType.CASH,
                institution = null,
                openingBalance = Money(5_000_00L),
                balance = Money(5_000_00L),
                currencyCode = "INR",
                isArchived = false,
            )

        assertNull(cash.institution)
    }
}
