package com.aicfo.domain.usecase

import com.aicfo.core.common.AppError
import com.aicfo.core.common.Err
import com.aicfo.core.common.Ok
import com.aicfo.core.common.SeededCases
import com.aicfo.core.model.Money
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The household aggregation contract (issue 13.1; §27, §33, ADR-0069).
 *
 * Why:  a household figure is the one place in this app where two people's money meets, so the
 *       ways it can be wrong are worse than an off-by-one: a total that cannot be broken back down
 *       hides whose money it was (P-02), and a member counted twice reports money that does not
 *       exist. ADR-0069 decided aggregation **composes scoped reads** rather than running one
 *       cross-profile query, which makes this class the whole of the arithmetic and therefore the
 *       whole of the risk.
 * What: the flag's default, the three refusals, the sum, and the two properties that must hold for
 *       any input — the total equals its parts, and order does not change it.
 * Result: the foundation ships provably additive and provably attributed, with the feature off.
 * Changelog: 2026-10-03 — Created for issue 13.1.
 */
class HouseholdAggregationTest {
    // --- the flag -------------------------------------------------------------------------

    /**
     * Input:  the shipped constant.
     * Output: asserts household mode is off in v1. AC2 ships the foundation *behind a flag*; a
     *         default of `true` would ship the unspecified feature itself.
     */
    @Test
    fun `household mode is off in v1`() {
        assertFalse(HouseholdMode.IS_ENABLED)
    }

    /**
     * Input:  the default-constructed aggregation — i.e. what the app would get.
     * Output: asserts it refuses, and refuses with the *disabled* error rather than a wrong number.
     *         Returning `Money.ZERO` here would be the dangerous failure: a household screen would
     *         render "₹0" and look like an answer.
     */
    @Test
    fun `the default aggregation refuses because the flag is off`() {
        val result = HouseholdAggregation().total(listOf(contribution("p1", 100)))
        assertEquals(Err(AppError.FeatureDisabled), result)
    }

    // --- the refusals ---------------------------------------------------------------------

    /**
     * Input:  an empty member list, flag on.
     * Output: asserts a refusal. An empty household is not a household with zero money, it is a
     *         caller bug — the same reasoning issue 12.2 used to refuse `0/0` rather than report
     *         0%.
     */
    @Test
    fun `an empty household is refused rather than totalled as zero`() {
        val result = enabled().total(emptyList())
        assertEquals(Err(AppError.Validation("members")), result)
    }

    /**
     * Input:  the same profile twice, flag on.
     * Output: asserts a refusal. This is the one arithmetic mistake that invents money, and it is
     *         easy to make: two scoped reads for the same profile, appended to one list.
     */
    @Test
    fun `a profile counted twice is refused`() {
        val result = enabled().total(listOf(contribution("p1", 100), contribution("p1", 100)))
        assertEquals(Err(AppError.Validation("profileId")), result)
    }

    /**
     * Input:  a blank profile id, flag on.
     * Output: asserts a refusal — an unattributable contribution breaks P-02 by construction.
     */
    @Test
    fun `a contribution with no profile id is refused`() {
        val result = enabled().total(listOf(contribution("  ", 100)))
        assertEquals(Err(AppError.Validation("profileId")), result)
    }

    // --- the sum --------------------------------------------------------------------------

    /**
     * Input:  one member holding ₹1,23,456.79, flag on.
     * Output: asserts the total is that amount and the member survives in the split. A deliberately
     *         non-round figure, so a rounding bug cannot hide behind a tidy number.
     */
    @Test
    fun `a single member's total is that member's amount, still attributed`() {
        val one = contribution("p1", 12_345_679)
        val result = enabled().total(listOf(one))
        val total = (result as Ok).value
        assertEquals(Money(12_345_679), total.amount)
        assertEquals(listOf(one), total.contributions)
    }

    /**
     * Input:  three members, one of them negative (a member can be net-negative — a loan larger
     *         than their assets), flag on.
     * Output: asserts the total is the signed sum, and that the split is returned in input order so
     *         the UI can show "whose" without re-sorting.
     */
    @Test
    fun `members are summed with sign and returned in order`() {
        val members =
            listOf(
                contribution("p1", 500_00),
                contribution("p2", -200_00),
                contribution("p3", 1_00),
            )
        val total = (enabled().total(members) as Ok).value
        assertEquals(Money(301_00), total.amount)
        assertEquals(listOf("p1", "p2", "p3"), total.contributions.map { it.profileId })
    }

    // --- the properties -------------------------------------------------------------------

    /**
     * Input:  200 seeded households of 1–6 members with amounts across ±₹10 lakh.
     * Output: asserts the total always equals the sum of the contributions it reports. This is the
     *         additive half of ADR-0069 §3: there is no household figure that its own split does
     *         not account for.
     */
    @Test
    fun `the total always equals the sum of the parts it reports`() {
        SeededCases(seed = 13_001L, count = 200).forEach { random ->
            val members = randomHousehold(random)
            val total = (enabled().total(members) as Ok).value
            val fromParts = total.contributions.fold(Money.ZERO) { running, member -> running + member.amount }
            assertEquals(fromParts, total.amount)
        }
    }

    /**
     * Input:  the same seeded households, each totalled in given order and in reverse.
     * Output: asserts the amount is identical either way. Addition over `Long` paise is exact, so
     *         any order dependence would mean a real bug — a running remainder, or a fold that
     *         treated the first member differently.
     */
    @Test
    fun `the order members arrive in cannot change the total`() {
        SeededCases(seed = 13_002L, count = 200).forEach { random ->
            val members = randomHousehold(random)
            val forward = (enabled().total(members) as Ok).value.amount
            val backward = (enabled().total(members.reversed()) as Ok).value.amount
            assertEquals(forward, backward)
        }
    }

    /**
     * Input:  two identical runs over the same seed.
     * Output: asserts byte-identical results (P-08). The class holds no clock and no randomness, so
     *         this pins that it stays that way.
     */
    @Test
    fun `aggregation is deterministic`() {
        val members = listOf(contribution("p1", 7), contribution("p2", 11))
        assertEquals(enabled().total(members), enabled().total(members))
    }

    /**
     * Input:  a household whose members sum to zero.
     * Output: asserts zero is reported as a *success*, not as the empty refusal. A real household
     *         can net to zero, and conflating it with "no members" would hide a true answer.
     */
    @Test
    fun `a household that nets to zero succeeds`() {
        val result = enabled().total(listOf(contribution("p1", 500), contribution("p2", -500)))
        assertTrue(result is Ok)
        assertEquals(Money.ZERO, (result as Ok).value.amount)
    }

    // --- helpers --------------------------------------------------------------------------

    /** An aggregation with the flag forced on, which is the only way v1 exercises the arithmetic. */
    private fun enabled() = HouseholdAggregation(isEnabled = true)

    /** A contribution of [minor] paise attributed to [profileId]. */
    private fun contribution(
        profileId: String,
        minor: Long,
    ) = MemberContribution(profileId, Money(minor))

    /** 1–6 distinctly-named members with amounts across ±₹10 lakh, from a seeded source. */
    private fun randomHousehold(random: kotlin.random.Random) =
        (1..random.nextInt(1, 7)).map { index ->
            contribution("p$index", random.nextLong(-100_000_000L, 100_000_000L))
        }
}
