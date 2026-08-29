package com.aicfo.domain.engines.card

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.time.LocalDate

/**
 * Tests for [BillingCycles] — the date arithmetic every card answer stands on (issue 6.1).
 *
 * Why:  three of these cases are the whole reason this calculator exists, and every one of them
 *       fails *silently* — producing a plausible date that is simply the wrong one, which a user
 *       discovers as a missed payment rather than as a crash:
 *
 *       a card that bills on the 31st, in February; a due day that falls **before** the statement
 *       day, which is the ordinary "statement on the 25th, pay by the 5th" card and not an edge at
 *       all; and the two boundaries, where today *is* the statement day or *is* the due date.
 * What: the clamp, the month rollover, both boundaries, a leap year, and the year wrap.
 * Result: the dates the reminder window is measured against are provably right.
 * Changelog: 2026-08-17 — Created for issue 6.1.
 */
class BillingCycleTest {
    /** Input: an ordinary card, mid-cycle. Output: asserts the plain case before the edges. */
    @Test
    fun `places a date inside an ordinary cycle`() {
        val cycle = BillingCycles.of(LocalDate.of(2026, 3, 10), statementDay = 5, dueDay = 25)

        assertThat(cycle.statementDate).isEqualTo(LocalDate.of(2026, 3, 5))
        assertThat(cycle.dueDate).isEqualTo(LocalDate.of(2026, 3, 25))
        assertThat(cycle.nextStatementDate).isEqualTo(LocalDate.of(2026, 4, 5))
        assertThat(cycle.daysUntilDue).isEqualTo(15)
    }

    /**
     * Input: a day-31 card asked about February.
     * Output: asserts the statement clamps to the 28th rather than throwing or rolling into March.
     * `LocalDate.of(2026, 2, 31)` throws; rolling forward would move the statement out of its cycle.
     */
    @Test
    fun `clamps a day-31 card to the end of a short month`() {
        val cycle = BillingCycles.of(LocalDate.of(2026, 2, 20), statementDay = 31, dueDay = 20)

        assertThat(cycle.statementDate).isEqualTo(LocalDate.of(2026, 1, 31))
        assertThat(cycle.dueDate).isEqualTo(LocalDate.of(2026, 2, 20))
        assertThat(cycle.nextStatementDate).isEqualTo(LocalDate.of(2026, 2, 28))
    }

    /** Input: the same card in a leap February. Output: asserts the clamp is leap-year aware. */
    @Test
    fun `clamps to the 29th in a leap year`() {
        val cycle = BillingCycles.of(LocalDate.of(2028, 3, 1), statementDay = 31, dueDay = 20)

        assertThat(cycle.statementDate).isEqualTo(LocalDate.of(2028, 2, 29))
    }

    /**
     * Input: statement on the 25th, due on the 5th — the common Indian card.
     * Output: asserts the due date is in the **following** month. Read as two days in one month it
     * would land twenty days before the statement was even cut.
     */
    @Test
    fun `a due day before the statement day belongs to the next month`() {
        val cycle = BillingCycles.of(LocalDate.of(2026, 3, 28), statementDay = 25, dueDay = 5)

        assertThat(cycle.statementDate).isEqualTo(LocalDate.of(2026, 3, 25))
        assertThat(cycle.dueDate).isEqualTo(LocalDate.of(2026, 4, 5))
        assertThat(cycle.daysUntilDue).isEqualTo(8)
    }

    /**
     * Input: today is the statement day.
     * Output: asserts that day's statement is the current one. `isBefore` instead of `!isAfter`
     * would send the user back a whole month on every statement day.
     */
    @Test
    fun `the statement day itself belongs to the new cycle`() {
        val cycle = BillingCycles.of(LocalDate.of(2026, 3, 5), statementDay = 5, dueDay = 25)

        assertThat(cycle.statementDate).isEqualTo(LocalDate.of(2026, 3, 5))
    }

    /**
     * Input: today is the due date.
     * Output: asserts `daysUntilDue == 0` — due today, not overdue by one. This is the value
     * `remind_on_due_day` keys off, so an off-by-one here silently deletes the last reminder.
     */
    @Test
    fun `the due date itself reads as zero days, not minus one`() {
        val cycle = BillingCycles.of(LocalDate.of(2026, 3, 25), statementDay = 5, dueDay = 25)

        assertThat(cycle.daysUntilDue).isEqualTo(0)
    }

    /** Input: a date past the due date. Output: asserts the count goes negative rather than clamping. */
    @Test
    fun `an overdue card counts negative`() {
        val cycle = BillingCycles.of(LocalDate.of(2026, 3, 29), statementDay = 5, dueDay = 25)

        assertThat(cycle.daysUntilDue).isEqualTo(-4)
    }

    /** Input: December, so the next statement is in the following year. Output: asserts the wrap. */
    @Test
    fun `rolls over the year end`() {
        val cycle = BillingCycles.of(LocalDate.of(2026, 12, 20), statementDay = 15, dueDay = 5)

        assertThat(cycle.statementDate).isEqualTo(LocalDate.of(2026, 12, 15))
        assertThat(cycle.dueDate).isEqualTo(LocalDate.of(2027, 1, 5))
        assertThat(cycle.nextStatementDate).isEqualTo(LocalDate.of(2027, 1, 15))
    }

    /**
     * Input: a date before this month's statement day.
     * Output: asserts it belongs to the previous month's cycle — the payment now due is for money
     * already spent, which is the whole reason the walk goes backwards.
     */
    @Test
    fun `a date before the statement day belongs to the previous cycle`() {
        val cycle = BillingCycles.of(LocalDate.of(2026, 3, 2), statementDay = 5, dueDay = 25)

        assertThat(cycle.statementDate).isEqualTo(LocalDate.of(2026, 2, 5))
        assertThat(cycle.dueDate).isEqualTo(LocalDate.of(2026, 2, 25))
        assertThat(cycle.daysUntilDue).isEqualTo(-5)
    }

    /**
     * Input: a card whose statement and due day are the same number.
     * Output: asserts the payment is a month after the statement, not the same afternoon. The
     * comparison is on resolved dates precisely so this cannot collapse to zero.
     */
    @Test
    fun `equal statement and due days put the payment a month later`() {
        val cycle = BillingCycles.of(LocalDate.of(2026, 3, 10), statementDay = 10, dueDay = 10)

        assertThat(cycle.statementDate).isEqualTo(LocalDate.of(2026, 3, 10))
        assertThat(cycle.dueDate).isEqualTo(LocalDate.of(2026, 4, 10))
    }
}
