package com.aicfo.domain.engines.loan

import com.aicfo.core.common.AppError
import com.aicfo.core.common.Err
import com.aicfo.core.common.Ok
import com.aicfo.core.model.Loan
import com.aicfo.core.model.Money
import com.google.common.truth.Truth.assertThat
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * The behaviour of [LoanEngine]'s three operations, decision by decision (issue 6.2; FR-ACC-003).
 *
 * Why:  the golden file proves the arithmetic and the property test proves the invariants. What is
 *       left is the *choices*: which instalment wins when the user has entered one, what happens at
 *       the edges of the instalment index, and which failure each bad input produces. Those are the
 *       parts a reader has to be able to check one at a time.
 * What: the derived/override decision, both provenance rules, every `Err` path, and the early-close
 *       behaviour a lender-stated EMI produces.
 * Result: the engine's contract holds where the two bulk tests do not look.
 * Changelog: 2026-08-19 — Created for issue 6.2.
 */
class LoanEngineTest {
    private val engine = LoanEngineFactory.create()

    // --- which instalment wins -----------------------------------------------------------------

    /**
     * Input:  terms with no entered EMI.
     * Output: asserts the derived instalment is used and labelled [EmiBasis.DERIVED], and that
     *         `derived` carries the same figure rather than being left empty.
     */
    @Test
    fun `with no entered EMI the derived one is used`() {
        val result = (engine.emi(LoanTermsInput(homeLoan())) as Ok).value

        assertThat(result.amount).isEqualTo(Money(2_603_470))
        assertThat(result.basis).isEqualTo(EmiBasis.DERIVED)
        assertThat(result.derived).isEqualTo(result.amount)
    }

    /**
     * Input:  the same terms plus the lender's own EMI, ₹1.30 higher.
     * Output: asserts the entered figure wins outright **and** the derived one is still carried —
     *         the editor shows both, so discarding either would leave a screen unable to explain the
     *         difference the user is looking at (P-02).
     */
    @Test
    fun `an entered EMI wins and the derived one is still reported`() {
        val lenders = Money(2_603_600)

        val result = (engine.emi(LoanTermsInput(homeLoan().copy(emiOverride = lenders))) as Ok).value

        assertThat(result.amount).isEqualTo(lenders)
        assertThat(result.basis).isEqualTo(EmiBasis.LENDER_STATED)
        assertThat(result.derived).isEqualTo(Money(2_603_470))
    }

    /**
     * Input:  a lender EMI far above the derived one.
     * Output: asserts the loan closes early — fewer instalments than the tenure, still ending at
     *         exactly zero, with no negative balance in between. A walk that only stopped at the
     *         tenure would keep charging a debt that no longer exists.
     */
    @Test
    fun `an EMI above the derived one closes the loan early`() {
        val doubled = Money(5_206_940)

        val schedule = (engine.schedule(LoanTermsInput(homeLoan().copy(emiOverride = doubled))) as Ok).value

        assertThat(schedule.rows.size).isLessThan(240)
        assertThat(schedule.rows.last().closingBalance).isEqualTo(Money.ZERO)
        assertThat(schedule.rows.none { it.closingBalance < Money.ZERO }).isTrue()
    }

    // --- failures ------------------------------------------------------------------------------

    /**
     * Input:  an entered EMI below the first month's interest.
     * Output: asserts `Err(Validation("emiOverride"))` — the field named is the one the user can
     *         actually fix, which is what a form needs to highlight the right box.
     */
    @Test
    fun `an EMI that cannot cover the interest is refused and names the field`() {
        val tooSmall = Money(2_000_000)

        val result = engine.schedule(LoanTermsInput(homeLoan().copy(emiOverride = tooSmall)))

        assertThat((result as Err).error).isEqualTo(AppError.Validation("emiOverride"))
    }

    /**
     * Input:  a 36% loan over 600 months, where the *derived* instalment is the one that cannot
     *         amortise — the closed form's answer approaches the monthly interest from above and
     *         rounds onto it at long tenures.
     * Output: asserts the failure names `annualRateBps`, not the override field the user left empty.
     */
    @Test
    fun `a derived EMI that cannot amortise blames the rate`() {
        val extreme =
            Loan(
                accountId = "account:extreme",
                principal = Money(1_000_000),
                annualRateBps = 3_600,
                tenureMonths = 600,
                firstEmiIsoDate = "2026-09-05",
            )

        val result = engine.emi(LoanTermsInput(extreme))

        assertThat((result as Err).error).isEqualTo(AppError.Validation("annualRateBps"))
    }

    /**
     * Input:  instalment numbers on both sides of the tenure.
     * Output: asserts 1 and the last are read, and 0 and tenure+1 are `Err(Validation("number"))`.
     *         A loan running for 240 months has no instalment 241 and no instalment 0.
     */
    @Test
    fun `an instalment outside the tenure is refused`() {
        val loan = homeLoan()

        assertThat(engine.instalment(LoanInstalmentInput(loan, 1))).isInstanceOf(Ok::class.java)
        assertThat(engine.instalment(LoanInstalmentInput(loan, 240))).isInstanceOf(Ok::class.java)
        assertThat((engine.instalment(LoanInstalmentInput(loan, 0)) as Err).error)
            .isEqualTo(AppError.Validation("number"))
        assertThat((engine.instalment(LoanInstalmentInput(loan, 241)) as Err).error)
            .isEqualTo(AppError.Validation("number"))
    }

    /**
     * Input:  an instalment inside the tenure that the loan never reaches, because a lender EMI
     *         closed it early.
     * Output: asserts `Err(NotFound)` rather than `Validation` — the caller asked for something
     *         reasonable and the answer is that this loan finished first. Two different situations
     *         deserve two different errors: one is a bug in the caller, the other is a fact.
     */
    @Test
    fun `an instalment the loan never reaches is not found`() {
        val early = homeLoan().copy(emiOverride = Money(5_206_940))

        val result = engine.instalment(LoanInstalmentInput(early, 239))

        assertThat((result as Err).error).isEqualTo(AppError.NotFound)
    }

    /**
     * Input:  a loan whose entered EMI cannot amortise, asked for one instalment.
     * Output: asserts the failure propagates out of [LoanEngine.instalment] too, rather than that
     *         path having its own opinion about a loan the schedule already refuses.
     */
    @Test
    fun `a loan that cannot amortise fails on the single-instalment path too`() {
        val result = engine.instalment(LoanInstalmentInput(homeLoan().copy(emiOverride = Money(1_000)), 1))

        assertThat((result as Err).error).isEqualTo(AppError.Validation("emiOverride"))
    }

    // --- provenance and dates ------------------------------------------------------------------

    /**
     * Input:  a schedule computed at a known instant.
     * Output: asserts the provenance names the engine and its version and carries the caller's
     *         timestamp — and that `evidence` is **empty**, which is this engine's deliberate
     *         difference from `CardStatus`: amortisation cites no rulebook row because it reads
     *         none, and inventing a citation would misstate where the number came from.
     */
    @Test
    fun `provenance names the engine and cites no rule`() {
        val schedule = (engine.schedule(LoanTermsInput(homeLoan(), nowUtcMillis = 1_767_312_000_000L)) as Ok).value

        assertThat(schedule.provenance.engineId).isEqualTo("loan-amortiser")
        assertThat(schedule.provenance.engineVersion).isEqualTo("1.0")
        assertThat(schedule.provenance.computedAtUtcMillis).isEqualTo(1_767_312_000_000L)
        assertThat(schedule.provenance.evidence).isEmpty()
    }

    /**
     * Input:  a loan whose first instalment falls on the 31st.
     * Output: asserts every due date keeps the 31st where the month has one and clamps where it does
     *         not — and, critically, that a short month does **not** move the following months. That
     *         is the bug stepping month-by-month from the previous date would produce: 31 Jan → 28
     *         Feb → 28 Mar, and the loan's EMI day silently becomes the 28th for ever.
     */
    @Test
    fun `a day-31 loan keeps its day after a short month`() {
        val loan =
            Loan(
                accountId = "account:day31",
                principal = Money(10_000_000),
                annualRateBps = 1_200,
                tenureMonths = 5,
                firstEmiIsoDate = "2027-01-31",
            )

        val dates = (engine.schedule(LoanTermsInput(loan)) as Ok).value.rows.map { it.dueIsoDate }

        assertThat(dates).containsExactly(
            "2027-01-31",
            "2027-02-28",
            "2027-03-31",
            "2027-04-30",
            "2027-05-31",
        ).inOrder()
    }

    /**
     * Input:  a row constructed by hand that does not balance.
     * Output: asserts [AmortisationRow] refuses it. The invariant lives on the type, not only in
     *         the property test, so a future caller building rows some other way cannot produce one
     *         whose parts do not sum to its amount.
     */
    @Test
    fun `a row whose parts do not sum to its amount cannot be built`() {
        assertThrows(IllegalArgumentException::class.java) {
            AmortisationRow(
                number = 1,
                dueIsoDate = "2026-09-05",
                amount = Money(1_000),
                principal = Money(400),
                interest = Money(500),
                openingBalance = Money(10_000),
                closingBalance = Money(9_600),
            )
        }
    }

    /**
     * Input:  a row whose balance does not fall by the principal paid.
     * Output: asserts the second `require` fires. The two checks are separate because they catch
     *         different mistakes: the first catches a bad split, this catches a bad walk.
     */
    @Test
    fun `a row whose balance does not fall by its principal cannot be built`() {
        assertThrows(IllegalArgumentException::class.java) {
            AmortisationRow(
                number = 1,
                dueIsoDate = "2026-09-05",
                amount = Money(1_000),
                principal = Money(400),
                interest = Money(600),
                openingBalance = Money(10_000),
                closingBalance = Money(9_000),
            )
        }
    }

    /**
     * Input:  an empty schedule and one that does not reach zero.
     * Output: asserts [AmortisationSchedule] refuses both — a schedule that leaves a balance is the
     *         exact failure the last-instalment remainder absorption exists to prevent, so the type
     *         says so rather than trusting every future walk to get it right.
     */
    @Test
    fun `a schedule that does not close the loan cannot be built`() {
        val emi = (engine.emi(LoanTermsInput(homeLoan())) as Ok).value
        val stranded =
            AmortisationRow(
                number = 1,
                dueIsoDate = "2026-09-05",
                amount = Money(1_000),
                principal = Money(400),
                interest = Money(600),
                openingBalance = Money(10_000),
                closingBalance = Money(9_600),
            )

        assertThrows(IllegalArgumentException::class.java) {
            AmortisationSchedule(emptyList(), emi, Money.ZERO, emi.provenance)
        }
        assertThrows(IllegalArgumentException::class.java) {
            AmortisationSchedule(listOf(stranded), emi, Money(600), emi.provenance)
        }
    }

    /**
     * The canonical loan every case above varies: ₹30,00,000 at 8.5% over 20 years.
     * Result: a [Loan]. Input: none. Output: [Loan].
     */
    private fun homeLoan() =
        Loan(
            accountId = "account:home",
            principal = Money(300_000_000),
            annualRateBps = 850,
            tenureMonths = 240,
            firstEmiIsoDate = "2026-09-05",
        )
}
