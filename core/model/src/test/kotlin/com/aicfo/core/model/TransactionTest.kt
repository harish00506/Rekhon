package com.aicfo.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Locks [TransactionSource] and [Transaction] to the SRS (issue 3.1; FR-TXN-009, FR-TXN-001).
 *
 * Why:  `transactions.source` is a **persistence contract** exactly as `account.type` is — issue 2.7
 *       has already written `reconciliation` rows into real databases, so renaming a stored value
 *       orphans them silently. FR-TXN-009's four names are asserted literally here rather than
 *       trusted to stay right, and the fifth is asserted *with its reason* so nobody deletes it as
 *       an unauthorised extra.
 * What: the source vocabulary, the round trip, forward compatibility, and the sign convention that
 *       makes an account balance a plain `SUM`.
 * Result: a change to a stored value cannot ship without a reviewer seeing this file change.
 * Changelog: 2026-08-02 — Created for issue 3.1.
 */
class TransactionTest {
    /**
     * The four values FR-TXN-009 names, plus the two the app writes that it does not.
     *
     * Copied by hand from the SRS and from `grep "source =" data/repository/src/main` rather than
     * derived from the enum — a test that derives its expectation from the code under test asserts
     * nothing. **The grep is the part that matters:** omitting `demo` here first time round made
     * every sample transaction unreadable (see [TransactionSource]'s note).
     */
    private val storedValues =
        listOf("manual", "ocr", "sms", "import", "reconciliation", "demo")

    @Test
    fun `every stored source exists, and no others`() {
        assertEquals(storedValues.sorted(), TransactionSource.entries.map { it.storedValue }.sorted())
    }

    @Test
    fun `FR-TXN-009 names manual, ocr, sms and import`() {
        listOf("manual", "ocr", "sms", "import").forEach {
            assertNotNull("FR-TXN-009 requires the source $it", TransactionSource.fromStored(it))
        }
    }

    @Test
    fun `reconciliation round-trips, because issue 2-7 already writes it`() {
        // AccountRepository.SOURCE_RECONCILIATION has been writing this literal since issue 2.7.
        // If this fails, existing adjustment rows have become unreadable.
        assertEquals(TransactionSource.RECONCILIATION, TransactionSource.fromStored("reconciliation"))
    }

    @Test
    fun `demo round-trips, because issue 2-4 already writes it`() {
        // `SOURCE_DEMO` in :data:repository has been writing this literal since issue 2.4. When
        // this enum omitted it, every sample transaction was dropped by the mapper's mapNotNull and
        // the recent-transactions list rendered empty on a demo profile full of history.
        assertEquals(TransactionSource.DEMO, TransactionSource.fromStored("demo"))
    }

    @Test
    fun `every source round-trips through its stored value`() {
        TransactionSource.entries.forEach {
            assertEquals(it, TransactionSource.fromStored(it.storedValue))
        }
    }

    @Test
    fun `an unknown source is null rather than an exception`() {
        // Forward compatibility: an old build reading a newer database drops the row, never crashes.
        assertNull(TransactionSource.fromStored("recurring-auto"))
        assertNull(TransactionSource.fromStored(""))
        assertNull(TransactionSource.fromStored("MANUAL"))
    }

    @Test
    fun `a name is not the stored value`() {
        // The persisted contract is storedValue; name is free to be renamed. Asserting the two are
        // read separately is what stops someone "simplifying" fromStored to compare against name.
        assertNull(TransactionSource.fromStored(TransactionSource.RECONCILIATION.name))
    }

    @Test
    fun `an outflow is negative and an inflow is positive`() {
        // The sign IS the type (MNY-001, DB-001) — there is no separate expense/income field to
        // disagree with it, and an account's balance is a plain SUM over these amounts.
        val expense = transaction(amount = Money(-25_000L))
        val income = transaction(amount = Money(6_000_000L))
        assertEquals(-25_000L, expense.amount.minor)
        assertEquals(6_000_000L, income.amount.minor)
        assertEquals(Money(5_975_000L), expense.amount + income.amount)
    }

    @Test
    fun `an uncategorised transaction is representable`() {
        // A real profile has no categories at all until issue 4.1, so null is the common case here,
        // not an edge case.
        assertNull(transaction().categoryId)
    }

    @Test
    fun `the instant and the booked day are carried separately`() {
        // TIM-001 vs TIM-002: 2026-08-02T23:30 IST is 18:00Z on the same date, and a call site that
        // re-derived the day from the instant without the profile zone would book it wrongly.
        val txn = transaction(occurredAtUtcMillis = 1_785_000_000_000L, bookedOn = "2026-08-02")
        assertEquals(1_785_000_000_000L, txn.occurredAtUtcMillis)
        assertEquals("2026-08-02", txn.bookedOn)
    }

    @Test
    fun `a category carries an id and a name`() {
        val category = Category(id = "category:groceries", name = "Groceries")
        assertEquals("category:groceries", category.id)
        assertEquals("Groceries", category.name)
    }

    /**
     * Builds a transaction with everything but the field under test defaulted.
     * Why:    keeps each assertion above about one fact rather than about nine constructor arguments.
     * Result: a valid [Transaction]. Input: the fields a test varies. Output: [Transaction].
     * Changelog: 2026-08-02 — Created for issue 3.1.
     */
    private fun transaction(
        amount: Money = Money(-25_000L),
        occurredAtUtcMillis: Long = 1_785_000_000_000L,
        bookedOn: String = "2026-08-02",
    ) = Transaction(
        id = "txn:1",
        accountId = "account:1",
        amount = amount,
        occurredAtUtcMillis = occurredAtUtcMillis,
        bookedOn = bookedOn,
        categoryId = null,
        merchant = null,
        note = null,
        source = TransactionSource.MANUAL,
    )
}
