package com.aicfo.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Locks [CreditCard]'s validation to what the engine downstream is allowed to assume (issue 6.1;
 * FR-ACC-002, §5.7, MNY-001/MNY-002).
 *
 * Why:  `CardEngine` is total — every operation returns `Ok`, and none of them checks its input —
 *       precisely because this constructor has already refused everything unusable. That division
 *       only holds if the refusals are proven here. A limit of zero would make utilisation divide
 *       by zero; a statement day of 32 has no date to clamp to; a negative minimum due reaches a
 *       notification as "pay -₹500".
 * What: the four `require` paths, the accepted boundaries either side of them, and the two fields
 *       whose absence is legitimate (a card with no statement recorded, a card with no APR known).
 * Result: `CreditCard` is proven fail-fast at its edges, or the build goes red.
 * Changelog: 2026-08-18 — Created for issue 6.1.
 */
class CreditCardTest {
    /**
     * A valid card — the base every case below deviates from by exactly one field via `copy`.
     *
     * Why:    `copy` rather than a helper with eight defaulted parameters: the parameter list would
     *         be the whole constructor again (and detekt refuses it at six), while `copy` re-runs
     *         `init`, which is the very thing under test here.
     * Result: a ₹2,00,000 card cut on the 5th, due on the 25th, with a statement recorded.
     * Input:  none. Output: [CreditCard].
     */
    private fun card() =
        CreditCard(
            accountId = "account:card",
            creditLimit = Money(20_000_000),
            statementDay = 5,
            dueDay = 25,
            lastStatement = Money(4_500_000),
            lastStatementIsoDate = "2026-08-05",
            minimumDue = Money(225_000),
            aprBps = 4_200,
        )

    /** Input: the base card. Output: asserts every field survives construction unchanged. */
    @Test
    fun `an ordinary card keeps every term it was given`() {
        val subject = card()

        assertEquals("account:card", subject.accountId)
        assertEquals(Money(20_000_000), subject.creditLimit)
        assertEquals(5, subject.statementDay)
        assertEquals(25, subject.dueDay)
        assertEquals(Money(4_500_000), subject.lastStatement)
        assertEquals("2026-08-05", subject.lastStatementIsoDate)
        assertEquals(Money(225_000), subject.minimumDue)
        assertEquals(4_200, subject.aprBps)
    }

    /** Input: a blank account id. Output: asserts a card must belong to an account. */
    @Test
    fun `a card without an account is refused`() {
        assertThrows(IllegalArgumentException::class.java) { card().copy(accountId = "  ") }
    }

    /**
     * Input: a zero and a negative limit.
     * Output: asserts both are refused — utilisation divides by the limit, so zero is not merely
     *         odd, it is a division by zero one layer down.
     */
    @Test
    fun `a non-positive credit limit is refused`() {
        assertThrows(IllegalArgumentException::class.java) { card().copy(creditLimit = Money.ZERO) }
        assertThrows(IllegalArgumentException::class.java) { card().copy(creditLimit = Money(-1)) }
    }

    /** Input: one paise of limit. Output: asserts the smallest positive limit is accepted. */
    @Test
    fun `one paise is a valid limit`() {
        assertEquals(Money(1), card().copy(creditLimit = Money(1)).creditLimit)
    }

    /** Input: days 0 and 32. Output: asserts a statement day outside 1..31 is refused. */
    @Test
    fun `a statement day outside the month is refused`() {
        assertThrows(IllegalArgumentException::class.java) { card().copy(statementDay = 0) }
        assertThrows(IllegalArgumentException::class.java) { card().copy(statementDay = 32) }
    }

    /** Input: days 0 and 32. Output: asserts a due day outside 1..31 is refused. */
    @Test
    fun `a due day outside the month is refused`() {
        assertThrows(IllegalArgumentException::class.java) { card().copy(dueDay = 0) }
        assertThrows(IllegalArgumentException::class.java) { card().copy(dueDay = 32) }
    }

    /**
     * Input: the 1st and the 31st for both days.
     * Output: asserts the boundaries are inside the range — the 31st is the case the engine clamps
     *         in February, and refusing it here would make that clamp unreachable.
     */
    @Test
    fun `the first and the thirty-first are both valid days`() {
        assertEquals(1, card().copy(statementDay = 1, dueDay = 1).statementDay)
        assertEquals(31, card().copy(statementDay = 31, dueDay = 31).dueDay)
    }

    /**
     * Input: a due day before the statement day.
     * Output: asserts it is accepted — a card cut on the 25th and due on the 14th is ordinary, and
     *         the engine resolves the due date into the following month.
     */
    @Test
    fun `a due day before the statement day is allowed`() {
        assertEquals(14, card().copy(statementDay = 25, dueDay = 14).dueDay)
    }

    /** Input: a negative minimum due. Output: asserts it is refused — "pay -₹500" is not a bill. */
    @Test
    fun `a negative minimum due is refused`() {
        assertThrows(IllegalArgumentException::class.java) { card().copy(minimumDue = Money(-1)) }
    }

    /** Input: a zero minimum due. Output: asserts zero is accepted — a fully paid card owes none. */
    @Test
    fun `a zero minimum due is allowed`() {
        assertEquals(Money.ZERO, card().copy(minimumDue = Money.ZERO).minimumDue)
    }

    /**
     * Input: a negative statement.
     * Output: asserts it is accepted — a credit balance is real when a refund lands after the cut,
     *         which is exactly why the *minimum due* is checked and the statement is not.
     */
    @Test
    fun `a credit balance on the statement is allowed`() {
        assertEquals(Money(-50_000), card().copy(lastStatement = Money(-50_000)).lastStatement)
    }

    /** Input: a negative APR. Output: asserts it is refused (MNY-002 — bps, non-negative). */
    @Test
    fun `a negative APR is refused`() {
        assertThrows(IllegalArgumentException::class.java) { card().copy(aprBps = -1) }
    }

    /** Input: a zero APR. Output: asserts an interest-free card is representable. */
    @Test
    fun `a zero APR is allowed`() {
        assertEquals(0, card().copy(aprBps = 0).aprBps)
    }

    /**
     * Input: a card with no statement, no statement date, no minimum due and no APR.
     * Output: asserts all four are optional — a card added before its first statement is a real
     *         card, and absence must stay absent rather than becoming zero (P-03).
     */
    @Test
    fun `a card with nothing recorded yet is valid and stays null`() {
        val subject =
            card().copy(lastStatement = null, lastStatementIsoDate = null, minimumDue = null, aprBps = null)

        assertNull(subject.lastStatement)
        assertNull(subject.lastStatementIsoDate)
        assertNull(subject.minimumDue)
        assertNull(subject.aprBps)
    }

    /**
     * Input: two identical cards, and a copy differing by one field.
     * Output: asserts the generated value members behave — equality, hash, `copy` and a `toString`
     *         that names the terms (which is what a failure message in every other suite prints).
     */
    @Test
    fun `the value members behave`() {
        assertEquals(card(), card())
        assertEquals(card().hashCode(), card().hashCode())
        assertEquals(20, card().copy(dueDay = 20).dueDay)
        assertNotEquals(card().copy(dueDay = 20), card())
        assertTrue(card().toString().contains("statementDay=5"))
    }
}
