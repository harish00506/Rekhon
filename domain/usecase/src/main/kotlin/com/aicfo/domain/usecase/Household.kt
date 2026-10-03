package com.aicfo.domain.usecase

import com.aicfo.core.common.AppError
import com.aicfo.core.common.Err
import com.aicfo.core.common.Ok
import com.aicfo.core.common.Result
import com.aicfo.core.model.Money

/**
 * Whether household mode is on (issue 13.1; §27, §33, ADR-0069).
 *
 * Why:  Epic 13 is design-for work: AC2 ships "the scoping foundation behind a flag". The
 *       foundation — schema 30's `household` table and the DAO scoping gate — is inert data and a
 *       build-time check, so it ships unconditionally. What needs holding back is the arithmetic
 *       that would put two people's money on one screen, because the views are specified
 *       (ADR-0069 §3) and not built, and because **the fourteen id-keyed DAO queries are not yet
 *       safe for a second profile** (ADR-0069 §5). Read that section before changing this
 *       constant: flipping it without scoping those queries is the cross-leak the epic exists to
 *       prevent.
 * What: one constant, read by [HouseholdAggregation]'s default constructor.
 * Result: v1 behaves exactly as it did — single profile, no household surface — while the
 *         foundation underneath is tested and in place.
 * Changelog: 2026-10-03 — Created for issue 13.1.
 */
object HouseholdMode {
    /** False in v1. The precondition for turning it on is ADR-0069 §5. */
    const val IS_ENABLED: Boolean = false
}

/**
 * One member's share of a household figure (issue 13.1).
 *
 * Why:  P-02 says every output shows its inputs. A household total with no breakdown is precisely
 *       the black-box verdict the rule forbids, and it is also the shape that hides a
 *       double-counted member. Making the split a required part of the result rather than an
 *       optional extra means a caller cannot render the total without having the attribution in
 *       hand.
 * What: a profile id and the amount that profile contributed, as computed by that profile's own
 *       scoped engine run.
 * Result: the unit the household arithmetic adds up, and the unit the UI labels.
 * Changelog: 2026-10-03 — Created for issue 13.1.
 *
 * @property profileId the contributing profile; never blank (enforced by [HouseholdAggregation]).
 * @property amount that profile's figure in minor units (MNY-001); may be negative, since a member
 *   can be net-negative.
 */
data class MemberContribution(
    val profileId: String,
    val amount: Money,
)

/**
 * A household figure and the member split that produced it (issue 13.1).
 *
 * Why:  see [MemberContribution] — the total and its parts travel together so they cannot drift
 *       apart, and `HouseholdAggregationTest` pins that [amount] always equals the sum of
 *       [contributions].
 * What: the aggregate, plus the per-member rows in the order they were supplied.
 * Result: everything a household view needs, with nothing it would have to recompute.
 * Changelog: 2026-10-03 — Created for issue 13.1.
 *
 * @property amount the signed sum of every contribution, in minor units (MNY-001).
 * @property contributions the members, in the caller's order, so the UI shows "whose" without
 *   re-sorting.
 */
data class HouseholdTotal(
    val amount: Money,
    val contributions: List<MemberContribution>,
)

/**
 * Adds up per-profile figures into one household figure (issue 13.1; §27, §33, ADR-0069).
 *
 * Why:  the obvious way to build a household net-worth view is one SQL query without the
 *       `profile_id` clause. ADR-0069 §3 rejects that: it moves money arithmetic into SQL, away
 *       from the deterministic layer P-03 and P-08 govern, and it throws away the structural
 *       guarantee that no statement can ever read across profiles. So household figures are
 *       **composed**: each profile's existing engine runs under its own scope, and this class adds
 *       the results. The cost is a loop; the gain is that a bug here can produce a wrong total but
 *       can never leak one profile's rows into another's view.
 * What: the flag check, three refusals, and the signed sum. No clock, no randomness, no I/O — the
 *       whole of the household arithmetic, and therefore the whole of its risk, in one testable
 *       place.
 * Result: `Ok(HouseholdTotal)` with the total and its attribution, or `Err` saying which rule the
 *       caller broke. Never a number the caller did not earn: with the flag off it refuses rather
 *       than returning zero, because a household screen rendering "₹0" looks like an answer.
 * Changelog: 2026-10-03 — Created for issue 13.1.
 *
 * @property isEnabled whether household mode is on; defaults to [HouseholdMode.IS_ENABLED], so the
 *   app gets the shipped behaviour and only a test passes `true`.
 */
class HouseholdAggregation(
    private val isEnabled: Boolean = HouseholdMode.IS_ENABLED,
) {
    /**
     * Totals [contributions] into one household figure.
     *
     * Why:  see the class note — this is the composition step ADR-0069 §3 chose over cross-profile
     *       SQL.
     * What: refuses when the feature is off, when there are no members, when a member is
     *       unattributable, or when a profile appears twice; otherwise folds the amounts.
     * Result: `Ok` carrying the signed sum and the split, or `Err`:
     *   - [AppError.FeatureDisabled] — household mode is off (the shipped v1 path);
     *   - `Validation("members")` — an empty list, which is a caller bug rather than a household
     *     holding zero money, so it is refused instead of totalled as zero;
     *   - `Validation("profileId")` — a blank id (unattributable, breaking P-02) or a repeated one
     *     (the one mistake here that invents money).
     * Input:  [contributions] — one row per member profile, each already computed under that
     *   profile's own scope. Order is the caller's and is preserved.
     * Output: `Result<HouseholdTotal, AppError>`.
     */
    fun total(contributions: List<MemberContribution>): Result<HouseholdTotal, AppError> {
        if (!isEnabled) return Err(AppError.FeatureDisabled)
        if (contributions.isEmpty()) return Err(AppError.Validation("members"))
        if (contributions.any { it.profileId.isBlank() }) return Err(AppError.Validation("profileId"))
        val ids = contributions.map { it.profileId }
        if (ids.size != ids.toSet().size) return Err(AppError.Validation("profileId"))
        val amount = contributions.fold(Money.ZERO) { running, member -> running + member.amount }
        return Ok(HouseholdTotal(amount, contributions.toList()))
    }
}
