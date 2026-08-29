package com.aicfo.domain.engines.loan

import com.aicfo.core.common.Ok
import com.aicfo.core.model.Loan
import com.aicfo.core.model.Money
import com.aicfo.core.model.sum
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import org.junit.Test
import java.time.LocalDate
import kotlin.random.Random

/**
 * The identities that must hold for every loan (issue 6.2; §21.5, P-08 — **the acceptance
 * criterion**).
 *
 * Why:  the issue asks, in as many words, that "each EMI splits into principal/interest exactly
 *       (sum = EMI, property test)". The golden file pins five loans somebody chose; these are the
 *       statements that have to be true for the terms nobody chose — the ones a real borrower's
 *       principal, rate and tenure will find. Each identity is here because breaking it is
 *       *plausible* and would not fail any example test: a schedule that discards the monthly
 *       rounding residue instead of absorbing it into the last instalment ends a few rupees off
 *       zero, and every individual row still looks right.
 * What: 500 seeded loans, each schedule checked against five invariants.
 * Result: the arithmetic holds off the tested path, and holds identically on every run.
 * Changelog: 2026-08-19 — Created for issue 6.2.
 *
 * **Seeded, never `Random()`** (P-08). A property test that generates a different five hundred
 * cases each run is a flake generator: it fails once on somebody's machine, passes on the rerun,
 * and teaches the team to rerun.
 */
class LoanEnginePropertyTest {
    private val engine = LoanEngineFactory.create()

    /**
     * Input:  500 generated loans across the whole plausible range of Indian retail terms.
     * Output: asserts the five identities, naming the loan that broke if one does.
     */
    @Test
    fun `the identities hold across five hundred generated loans`() {
        generated().forEach { loan ->
            val case =
                "principal=${loan.principal.minor} rate=${loan.annualRateBps}bps " +
                    "tenure=${loan.tenureMonths} first=${loan.firstEmiIsoDate}"
            val schedule = (engine.schedule(LoanTermsInput(loan)) as Ok).value
            val rows = schedule.rows

            // 1. THE criterion: every instalment is exactly its principal plus its interest. The row
            //    type's own `require` enforces it, so what this really asserts is that the engine
            //    can always *construct* a balancing row — a walk that computed the amount and the
            //    parts independently would throw here rather than return a wrong number.
            rows.forEach { row ->
                assertWithMessage("instalment %s does not balance — %s", row.number, case)
                    .that(row.principal + row.interest).isEqualTo(row.amount)
            }

            // 2. The principals sum to the loan. Not "to within a rupee" — exactly. This is the
            //    identity the last-instalment remainder absorption exists for, and the one that
            //    fails the moment anybody replaces it with a rounded final row.
            assertWithMessage("the principals do not repay the loan — %s", case)
                .that(rows.map { it.principal }.sum()).isEqualTo(loan.principal)

            // 3. The debt only ever falls, and reaches exactly zero. A schedule that ends at 3 paise
            //    is a loan the app would tell someone they still owe on.
            assertWithMessage("the loan does not close — %s", case)
                .that(rows.last().closingBalance).isEqualTo(Money.ZERO)
            rows.zipWithNext().forEach { (earlier, later) ->
                assertWithMessage("balance rose at instalment %s — %s", later.number, case)
                    .that(later.openingBalance < earlier.openingBalance).isTrue()
            }

            // 4. Interest is never negative and never exceeds the instalment. Both are how a sign
            //    slip or a misplaced `overPeriods` would show up: a monthly rate applied as an
            //    annual one puts the interest above the EMI on the first row of a long loan.
            rows.forEach { row ->
                assertWithMessage("implausible interest on instalment %s — %s", row.number, case)
                    .that(row.interest >= Money.ZERO && row.interest <= row.amount).isTrue()
            }

            // 5. The schedule never runs past the tenure it was sanctioned for. It may be shorter —
            //    a lender-stated EMI above the derived one closes the loan early — but a walk that
            //    ran long would mean the closing condition never fired.
            assertWithMessage("more instalments than the tenure — %s", case)
                .that(rows.size).isAtMost(loan.tenureMonths)
        }
    }

    /**
     * Input:  a zero-rate loan of an amount that does not divide evenly by its tenure.
     * Output: asserts the interest-free branch obeys the same identities — ₹10,00,000 over 7 months
     *         is ₹1,42,857.14 with two paise left over, and those two paise have to land on the last
     *         instalment rather than vanish.
     *
     * Split out rather than folded into the generator because a zero rate is the one input where
     * the formula itself is undefined (the denominator is `(1+0)^n − 1`, i.e. zero) and a generator
     * that happened to stop producing it would take this coverage with it silently.
     */
    @Test
    fun `an interest-free loan still repays exactly`() {
        val loan =
            Loan(
                accountId = "account:zero",
                principal = Money(100_000_000),
                annualRateBps = 0,
                tenureMonths = 7,
                firstEmiIsoDate = "2026-09-01",
            )

        val schedule = (engine.schedule(LoanTermsInput(loan)) as Ok).value

        assertThat(schedule.rows.map { it.principal }.sum()).isEqualTo(loan.principal)
        assertThat(schedule.totalInterest).isEqualTo(Money.ZERO)
        assertThat(schedule.rows.last().closingBalance).isEqualTo(Money.ZERO)
        assertThat(schedule.rows.map { it.interest }.toSet()).containsExactly(Money.ZERO)
    }

    /**
     * Input:  the same terms, scheduled twice.
     * Output: asserts byte-identical results — P-08's determinism requirement, and the one that
     *         would break if anybody swapped the pinned `MathContext` for an ambient one or reached
     *         for a `Double`.
     */
    @Test
    fun `the same terms give the same schedule every time`() {
        val loan = generated().first()

        val first = (engine.schedule(LoanTermsInput(loan, nowUtcMillis = 1L)) as Ok).value
        val second = (engine.schedule(LoanTermsInput(loan, nowUtcMillis = 1L)) as Ok).value

        assertThat(second).isEqualTo(first)
    }

    /**
     * Builds the generated loans.
     * Why:    the ranges are the real ones — ₹10,000 to ₹5 crore, 0% to 36%, 1 to 360 months, and a
     *         first instalment on any day of any month including the 29th, 30th and 31st, which is
     *         where the date arithmetic earns its keep. Every generated loan goes through the real
     *         `Loan` constructor, so the generator cannot produce terms the app would refuse.
     * Result: 500 loans, the same 500 on every run.
     * Input:  none. Output: `List<Loan>`.
     */
    private fun generated(): List<Loan> {
        val random = Random(SEED)
        val start = LocalDate.of(2026, 1, 1)
        return List(CASES) {
            Loan(
                accountId = "account:generated",
                principal = Money(random.nextLong(1_000_000L, 5_000_000_000L)),
                annualRateBps = random.nextInt(0, 3_600),
                tenureMonths = random.nextInt(1, 361),
                firstEmiIsoDate = start.plusDays(random.nextLong(0, 400)).toString(),
            )
        }
    }

    private companion object {
        /** Fixed so the five hundred cases are the same five hundred on every machine (P-08). */
        const val SEED = 62L
        const val CASES = 500
    }
}
