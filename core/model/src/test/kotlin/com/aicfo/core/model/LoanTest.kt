package com.aicfo.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Locks [Loan]'s validation to what the amortisation engine is allowed to assume (issue 6.2;
 * FR-ACC-003, §5.8, MNY-001/MNY-002).
 *
 * Why:  `LoanEngine` returns `Err` for exactly one condition — an instalment too small to cover the
 *       first month's interest — and checks nothing else, because this constructor has already
 *       refused everything unusable. That division of labour only holds if the refusals are proven
 *       here. A principal of zero has no schedule to build; a tenure of 0 divides by zero in the
 *       zero-rate branch; an unparseable first-EMI date fails once per row, 240 rows later, with
 *       nothing left pointing at the cause.
 * What: the six `require` paths, the accepted boundaries either side of them, and the one field
 *       whose absence is legitimate (a loan whose EMI the app derives rather than being told).
 * Result: `Loan` is proven fail-fast at its edges, or the build goes red.
 * Changelog: 2026-08-19 — Created for issue 6.2 (written red before Loan.kt existed).
 */
class LoanTest {
    /**
     * A valid loan — the base every case below deviates from by exactly one field via `copy`.
     *
     * Why:    `copy` rather than a helper with six defaulted parameters, for the reason
     *         `CreditCardTest.card()` gives: the parameter list would be the whole constructor
     *         again, while `copy` re-runs `init`, which is the very thing under test.
     * Result: ₹30,00,000 at 8.5% over 20 years, first instalment 2026-09-05.
     * Input:  none. Output: [Loan].
     */
    private fun loan() =
        Loan(
            accountId = "account:home-loan",
            principal = Money(300_000_000),
            annualRateBps = 850,
            tenureMonths = 240,
            firstEmiIsoDate = "2026-09-05",
            emiOverride = null,
        )

    /** Input: the base loan. Output: asserts every term survives construction unchanged. */
    @Test
    fun `an ordinary loan keeps every term it was given`() {
        val subject = loan()

        assertEquals("account:home-loan", subject.accountId)
        assertEquals(Money(300_000_000), subject.principal)
        assertEquals(850, subject.annualRateBps)
        assertEquals(240, subject.tenureMonths)
        assertEquals("2026-09-05", subject.firstEmiIsoDate)
        assertNull(subject.emiOverride)
    }

    /** Input: a blank account id. Output: asserts a loan must belong to an account. */
    @Test
    fun `a loan without an account is refused`() {
        assertThrows(IllegalArgumentException::class.java) { loan().copy(accountId = "  ") }
    }

    /**
     * Input: a zero and a negative principal.
     * Output: asserts both are refused — there is nothing to amortise, and a negative principal is a
     *         deposit that would produce a schedule of negative instalments.
     */
    @Test
    fun `a loan of nothing or less is refused`() {
        assertThrows(IllegalArgumentException::class.java) { loan().copy(principal = Money.ZERO) }
        assertThrows(IllegalArgumentException::class.java) { loan().copy(principal = Money(-1)) }
    }

    /** Input: a negative rate. Output: asserts it is refused rather than paying the borrower. */
    @Test
    fun `a negative rate is refused`() {
        assertThrows(IllegalArgumentException::class.java) { loan().copy(annualRateBps = -1) }
    }

    /**
     * Input: a zero rate.
     * Output: asserts it is accepted — an interest-free family loan and a 0% consumer-durable EMI
     *         are both real, and the engine has a branch for them.
     */
    @Test
    fun `a zero rate is a real loan`() {
        assertEquals(0, loan().copy(annualRateBps = 0).annualRateBps)
    }

    /**
     * Input: tenures either side of both bounds.
     * Output: asserts 1 and 600 are accepted and 0 and 601 are refused — 0 divides by zero in the
     *         zero-rate branch, and an unbounded tenure lets a typo ask for a hundred thousand rows.
     */
    @Test
    fun `a tenure outside one to six hundred months is refused`() {
        assertEquals(1, loan().copy(tenureMonths = 1).tenureMonths)
        assertEquals(600, loan().copy(tenureMonths = 600).tenureMonths)
        assertThrows(IllegalArgumentException::class.java) { loan().copy(tenureMonths = 0) }
        assertThrows(IllegalArgumentException::class.java) { loan().copy(tenureMonths = 601) }
    }

    /**
     * Input: a malformed date, a day that does not exist, and a well-formed leap day.
     * Output: asserts the field is parsed rather than pattern-matched — `2026-02-30` matches every
     *         plausible regex and is not a day.
     */
    @Test
    fun `a first EMI date that is not a calendar day is refused`() {
        assertThrows(IllegalArgumentException::class.java) { loan().copy(firstEmiIsoDate = "05-09-2026") }
        assertThrows(IllegalArgumentException::class.java) { loan().copy(firstEmiIsoDate = "2026-02-30") }
        assertThrows(IllegalArgumentException::class.java) { loan().copy(firstEmiIsoDate = "") }
        assertEquals("2028-02-29", loan().copy(firstEmiIsoDate = "2028-02-29").firstEmiIsoDate)
    }

    /**
     * Input: a zero and a negative EMI override.
     * Output: asserts both are refused — a zero instalment never repays the loan and would send the
     *         schedule walk round for ever.
     */
    @Test
    fun `an EMI override of nothing or less is refused`() {
        assertThrows(IllegalArgumentException::class.java) { loan().copy(emiOverride = Money.ZERO) }
        assertThrows(IllegalArgumentException::class.java) { loan().copy(emiOverride = Money(-1)) }
    }

    /** Input: the lender's own instalment. Output: asserts a positive override is kept as given. */
    @Test
    fun `an EMI override the lender charges is kept`() {
        assertEquals(Money(2_603_500), loan().copy(emiOverride = Money(2_603_500)).emiOverride)
    }

    /**
     * Input: two loans differing only in tenure.
     * Output: asserts the generated `equals`/`hashCode`/`toString` members are exercised — kover
     *         holds `:core:model` to 100%, and a data class's synthetic members count.
     */
    @Test
    fun `value semantics hold`() {
        assertEquals(loan(), loan())
        assertEquals(loan().hashCode(), loan().hashCode())
        assertNotEquals(loan(), loan().copy(tenureMonths = 120))
        assertTrue(loan().toString().contains("home-loan"))
    }
}
