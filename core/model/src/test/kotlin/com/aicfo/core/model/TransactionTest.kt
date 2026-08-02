package com.aicfo.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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
        type = TransactionType.EXPENSE,
    )
}

/**
 * Locks [TransactionType] to §20.2 and pins the type/sign invariant (issue 3.2; FR-TXN-003).
 *
 * Why:  this enum makes the app store **direction twice** — once here and once in the amount's sign —
 *       which is a hazard the codebase did not have before issue 3.2. §20.2 puts a `CHECK` constraint
 *       on the column, but SQLite cannot add one through `ALTER TABLE … ADD COLUMN`, so on every
 *       upgraded database **this file is the constraint**. A build that lets a positive amount be an
 *       `EXPENSE` should fail here, not in front of a user reading a balance that disagrees with its
 *       own transaction list.
 * What: the type vocabulary, the round trip, forward compatibility, [TransactionType.matches], and
 *       [Transfer] — the collapsed view the two legs are read back as.
 * Result: a change that lets the two representations drift cannot ship.
 * Changelog: 2026-08-02 — Created for issue 3.2.
 */
class TransactionTypeTest {
    /**
     * §20.2's `CHECK(type IN (…))` list, copied by hand from the SRS.
     * Derived expectations prove nothing — this is transcribed from the blueprint, not from the enum.
     */
    private val storedValues = listOf("expense", "income", "transfer_out", "transfer_in", "adjustment")

    @Test
    fun `every type SS20-2 names exists, and no others`() {
        assertEquals(storedValues.sorted(), TransactionType.entries.map { it.storedValue }.sorted())
    }

    @Test
    fun `every type round-trips through its stored value`() {
        TransactionType.entries.forEach { assertEquals(it, TransactionType.fromStored(it.storedValue)) }
    }

    @Test
    fun `an unknown type is null rather than an exception`() {
        // An old build reading a newer database drops the row; it never crashes the list.
        assertNull(TransactionType.fromStored("refund"))
        assertNull(TransactionType.fromStored(""))
        assertNull(TransactionType.fromStored("EXPENSE"))
        // The persisted contract is storedValue, never name — TRANSFER_OUT vs transfer_out.
        assertNull(TransactionType.fromStored(TransactionType.TRANSFER_OUT.name))
    }

    @Test
    fun `only the two transfer legs are transfers`() {
        // What keeps a transfer out of spend totals. Getting ADJUSTMENT in here would hide issue
        // 2.7's balance corrections from every report.
        assertEquals(
            listOf(TransactionType.TRANSFER_OUT, TransactionType.TRANSFER_IN),
            TransactionType.entries.filter { it.isTransfer },
        )
    }

    @Test
    fun `outflow types require a negative amount and reject a positive one`() {
        listOf(TransactionType.EXPENSE, TransactionType.TRANSFER_OUT).forEach { type ->
            assertTrue("$type must accept an outflow", type.matches(Money(-1L)))
            assertFalse("$type must reject an inflow", type.matches(Money(1L)))
            assertFalse("$type must reject zero", type.matches(Money.ZERO))
        }
    }

    @Test
    fun `inflow types require a positive amount and reject a negative one`() {
        listOf(TransactionType.INCOME, TransactionType.TRANSFER_IN).forEach { type ->
            assertTrue("$type must accept an inflow", type.matches(Money(1L)))
            assertFalse("$type must reject an outflow", type.matches(Money(-1L)))
            assertFalse("$type must reject zero", type.matches(Money.ZERO))
        }
    }

    @Test
    fun `an adjustment may run either way but not to zero`() {
        // FR-ACC-006 corrections go in whichever direction the user's records were wrong in, so this
        // is the one type whose sign is genuinely free. Zero still writes nothing (issue 2.7).
        assertTrue(TransactionType.ADJUSTMENT.matches(Money(-5_000L)))
        assertTrue(TransactionType.ADJUSTMENT.matches(Money(5_000L)))
        assertFalse(TransactionType.ADJUSTMENT.matches(Money.ZERO))
    }

    @Test
    fun `a transfer carries both accounts and a positive amount`() {
        // [Transfer] is the collapsed view of two stored legs. **The amount is positive by
        // contract** — the signs live on the legs — so a screen never has to ask which side it is
        // looking at, and a caller that handed this a negative would be describing one leg while
        // claiming to describe the pair.
        val transfer =
            Transfer(
                id = "tfr:1",
                fromAccountId = "account:1",
                toAccountId = "account:2",
                amount = Money(5_000_00L),
                bookedOn = "2026-08-02",
                note = "Rent float",
            )

        assertEquals("tfr:1", transfer.id)
        assertEquals("account:1", transfer.fromAccountId)
        assertEquals("account:2", transfer.toAccountId)
        assertTrue("the collapsed amount is the size of the movement", transfer.amount > Money.ZERO)
        // TIM-002: one profile-zone day shared by both legs, never a midnight timestamp.
        assertEquals("2026-08-02", transfer.bookedOn)
        assertEquals("Rent float", transfer.note)
    }

    @Test
    fun `a transaction is not split until it has lines`() {
        // Empty is the normal case, and `isSplit` is what the list reads to decide whether to say so.
        val plain =
            Transaction(
                id = "txn:1",
                accountId = "account:1",
                amount = Money(-1_00_000L),
                occurredAtUtcMillis = 1_785_000_000_000L,
                bookedOn = "2026-08-02",
                categoryId = null,
                merchant = null,
                note = null,
                source = TransactionSource.MANUAL,
                type = TransactionType.EXPENSE,
            )

        assertFalse(plain.isSplit)
        assertTrue(plain.copy(splits = listOf(split(Money(-1_00_000L)))).isSplit)
    }

    @Test
    fun `split lines are signed like their parent and total to it`() {
        // FR-TXN-004's requirement expressed as it is actually checked: a plain comparison of two
        // signed Money values, which only works because a line of an expense is negative like the
        // expense. Magnitudes plus a direction flag would need two comparisons and could disagree.
        val lines = listOf(split(Money(-60_000L)), split(Money(-40_000L)))

        assertEquals(Money(-1_00_000L), lines.total())
        assertTrue("a line of an expense is negative", lines.all { it.amount < Money.ZERO })
    }

    @Test
    fun `an income splits into positive lines`() {
        val lines = listOf(split(Money(30_000L)), split(Money(70_000L)))

        assertEquals(Money(1_00_000L), lines.total())
    }

    @Test
    fun `no lines total to zero, so an unsplit amount is entirely unattributed`() {
        // What makes "no lines yet" read as a full remaining amount on the add screen rather than as
        // a balanced split with nothing in it.
        assertEquals(Money.ZERO, emptyList<TransactionSplit>().total())
    }

    @Test
    fun `a line carries its own category, independently of its siblings`() {
        // "N lines with independent categories" (FR-TXN-004). One uncategorised line beside a
        // categorised one is legal — a real profile has no categories at all until issue 4.1.
        val lines =
            listOf(
                split(Money(-60_000L), categoryId = "category:groceries"),
                split(Money(-40_000L)),
            )

        assertEquals("category:groceries", lines.first().categoryId)
        assertNull(lines.last().categoryId)
    }

    @Test
    fun `total uses checked arithmetic rather than wrapping`() {
        // MNY-001: `total` delegates to Money's addition, so an absurd pair throws instead of
        // silently wrapping to a small positive number.
        val lines = listOf(split(Money(Long.MAX_VALUE)), split(Money(1L)))

        try {
            lines.total()
            error("expected the overflow to be refused")
        } catch (expected: ArithmeticException) {
            assertNotNull(expected)
        }
    }

    /**
     * Result: a split line with everything but the field under test defaulted.
     * Input: [amount], [categoryId]. Output: [TransactionSplit].
     * Changelog: 2026-08-02 — Created for issue 3.3.
     */
    private fun split(
        amount: Money,
        categoryId: String? = null,
    ) = TransactionSplit(id = "spl:1", transactionId = "txn:1", amount = amount, categoryId = categoryId)

    @Test
    fun `a transfer needs no note`() {
        val transfer =
            Transfer(
                id = "tfr:1",
                fromAccountId = "account:1",
                toAccountId = "account:2",
                amount = Money(5_000_00L),
                bookedOn = "2026-08-02",
            )

        assertNull(transfer.note)
    }

    @Test
    fun `exactly one non-adjustment type accepts any given non-zero amount`() {
        // The property that makes the invariant usable: for a signed amount there is one correct
        // outflow type and one correct inflow type, never both and never neither. Walks a spread of
        // magnitudes including the Long extremes, where a sign check written as `-amount > 0` breaks.
        val amounts =
            listOf(1L, 25_000L, 1_23_456_78L, Long.MAX_VALUE)
                .flatMap { listOf(Money(it), Money(-it)) } + Money(Long.MIN_VALUE)

        amounts.forEach { amount ->
            val accepting =
                TransactionType.entries.filterNot { it == TransactionType.ADJUSTMENT }
                    .filter { it.matches(amount) }
            assertEquals("exactly two types accept $amount", 2, accepting.size)
            assertEquals(
                "the accepting types must be one transfer leg and one non-transfer",
                1,
                accepting.count { it.isTransfer },
            )
        }
    }
}
