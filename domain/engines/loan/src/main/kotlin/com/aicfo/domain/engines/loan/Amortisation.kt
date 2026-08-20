package com.aicfo.domain.engines.loan

import com.aicfo.core.model.Loan
import com.aicfo.core.model.Money
import java.time.LocalDate

/**
 * The reducing-balance walk that turns a loan and an EMI into instalments (issue 6.2; FR-ACC-003).
 *
 * Why:  this is the whole issue in twenty lines, and the twenty lines have one trap in them.
 *       Interest each month is a rounded figure — `balance × rate ÷ 12`, HALF_EVEN to the paise —
 *       and the principal is what is left of the instalment after it. Do that 240 times and the
 *       roundings do not cancel: the balance after the last instalment is a few rupees away from
 *       zero, in either direction, and no fixed EMI can land on zero for every set of terms.
 *
 *       **So the last instalment absorbs the remainder.** Its principal is whatever is still owed
 *       and its amount is that plus its interest — which is exactly what a lender's own final
 *       instalment does. That makes both invariants exact *by construction* rather than by luck:
 *       the principals sum to the loan, and every row's principal plus interest is its amount.
 *
 *       **The alternative was `Money.allocate`**, the largest-remainder distributor `Money` already
 *       owns. It is the right tool for splitting one amount into weighted parts, and the wrong one
 *       here: the parts are not known up front, each depends on the balance the previous one left,
 *       and the interest is not a share of the principal at all. Forcing it would mean computing
 *       the schedule to find the weights, which is the thing being computed.
 * What: the row walk, and the instalment dates that go with it.
 * Result: rows that balance exactly and end at zero.
 * Changelog: 2026-08-19 — Created for issue 6.2.
 *
 * `internal` — the public seam is [LoanEngine.schedule] (ARC-003). **No clock** (TIM-001): every
 * date is derived from the loan's own `firstEmiIsoDate`.
 */
internal object Amortisation {
    /**
     * Walks the whole schedule.
     * Why:    one loop, used by both the schedule and the single-instalment read, so there is
     *         exactly one place where a balance advances and one rounding rule to review.
     * Result: at most [Loan.tenureMonths] rows, first to last, whose principals sum exactly to the
     *         principal and whose final closing balance is [Money.ZERO]. **Fewer than the tenure
     *         when the instalment clears the loan early** — a lender-stated EMI above the derived
     *         one shortens the loan, and reporting the full tenure would invent instalments the
     *         borrower will never pay.
     * Input:  [loan] — validated terms; [instalment] — the EMI, already proven to amortise by
     *         [Emi.amortises]. Output: a read-only `List<AmortisationRow>`.
     */
    fun rows(
        loan: Loan,
        instalment: Money,
    ): List<AmortisationRow> = walk(loan, instalment, upTo = loan.tenureMonths)

    /**
     * Walks only as far as one instalment and returns it.
     * Why:    the accounts list reads one row per loan. The walk still has to start at instalment 1
     *         — every balance depends on the one before — but nothing before [number] is retained,
     *         which is the difference between a list of ten loans holding ten rows and holding 2 400.
     * Result: the [number]th row, or `null` when the loan was already cleared before it — an EMI
     *         high enough to close a twenty-year loan in eight years has no instalment 100.
     * Input:  [loan]; [instalment]; [number] — 1..[Loan.tenureMonths].
     * Output: [AmortisationRow]?.
     */
    fun row(
        loan: Loan,
        instalment: Money,
        number: Int,
    ): AmortisationRow? = walk(loan, instalment, upTo = number).getOrNull(number - 1)

    /**
     * The shared walk.
     * Why:    see the class note. It stops on the first of two conditions — the tenure running out,
     *         or the instalment being enough to clear what is left — and that row is the one that
     *         absorbs the remainder. Checking both is what keeps the balance from going negative
     *         when a user enters a lender EMI far above the derived one.
     * Result: rows 1..[upTo], or fewer if the loan closes first.
     * Input:  [loan]; [instalment]; [upTo] — how many rows to produce at most. Output: the list.
     */
    private fun walk(
        loan: Loan,
        instalment: Money,
        upTo: Int,
    ): List<AmortisationRow> {
        val firstDue = LocalDate.parse(loan.firstEmiIsoDate)
        val rows = mutableListOf<AmortisationRow>()
        var balance = loan.principal
        var number = 1
        while (number <= upTo && balance > Money.ZERO) {
            val interest = balance.percentOf(loan.annualRateBps, overPeriods = Emi.MONTHS_PER_YEAR)
            val scheduled = instalment - interest
            // The closing instalment clears whatever is left — the tenure's last, or the first one
            // big enough to finish the loan. Its *amount* moves; every other instalment is the EMI.
            val isFinal = number == loan.tenureMonths || scheduled >= balance
            val principal = if (isFinal) balance else scheduled
            val closing = balance - principal
            rows +=
                AmortisationRow(
                    number = number,
                    // Always from the first date, never from the previous one: `plusMonths` clamps
                    // to a short month, so stepping 31 Jan -> 28 Feb -> 28 Mar would quietly move a
                    // loan's EMI day to the 28th for ever. Adding to the origin keeps March on 31.
                    dueIsoDate = firstDue.plusMonths((number - 1).toLong()).toString(),
                    amount = principal + interest,
                    principal = principal,
                    interest = interest,
                    openingBalance = balance,
                    closingBalance = closing,
                )
            balance = closing
            number++
        }
        return rows
    }
}
