package com.aicfo.domain.engines.goals

import com.aicfo.core.model.EngineProvenance
import com.aicfo.core.model.Money
import com.aicfo.core.model.RuleCitation

/**
 * Builds a [GoalProjection] for a waterfall test (issue 7.3).
 *
 * Why:  `GoalProjection` has fourteen fields and the waterfall reads five of them —
 *       `goalId`, `name`, `requiredMonthly`, `remaining`, `monthsRemaining` and `saved`. Spelling
 *       the other nine out at every call site would bury the one number each test is about.
 *       [GoalWaterfallGoldenTest] deliberately does **not** use this: it runs the real
 *       [GoalEngine] so the composition is what is gated. This is for the unit and property tests,
 *       where the point is to reach a branch, not to reproduce a projection.
 * What: a projection with the terms the waterfall reads, and consistent filler for the rest.
 * Result: a [GoalProjection] that could plausibly have come out of [GoalEngine].
 * Changelog: 2026-09-03 — Created for issue 7.3.
 *
 * @param id the goal id, also used as the name so a failure message names something readable.
 * @param required `requiredMonthly` in paise — the claim this goal makes on the surplus.
 * @param remaining what is still to be saved, in paise. Defaults to twelve times [required], the
 *   relationship a year-out goal would actually have, so the levers compute something sensible.
 * @param months whole months to the target date; drives the date and target levers.
 * @param saved what is already set aside, in paise — the base the target lever reduces to.
 * @return the projection.
 */
internal fun projection(
    id: String,
    required: Long,
    remaining: Long = required * MONTHS_IN_A_YEAR,
    months: Int = MONTHS_IN_A_YEAR.toInt(),
    saved: Long = 0L,
): GoalProjection =
    GoalProjection(
        goalId = id,
        name = id,
        target = Money(saved + remaining),
        targetDateIso = "2027-09-03",
        saved = Money(saved),
        remaining = Money(remaining),
        monthsRemaining = months,
        requiredMonthly = Money(required),
        plannedMonthly = Money.ZERO,
        shortfallMonthly = Money(required),
        etaIsoDate = null,
        onTrack = false,
        horizon = Horizon.SHORT,
        status = GoalStatus.BEHIND,
    )

/** Twelve — the default horizon these fixtures assume, so `remaining` and `required` agree. */
private const val MONTHS_IN_A_YEAR = 12L

/**
 * A provenance for a hand-built result (issue 7.3).
 *
 * Why:  [GoalWaterfall] refuses to be constructed without evidence (P-02), so a test that builds one
 *       directly — to prove the *other* `require` fires — needs a valid one to get that far.
 * What: the minimum an [EngineProvenance] can be and still be accepted.
 * Result: a provenance object. Changelog: 2026-09-03 — Created for issue 7.3.
 */
internal object ProvenanceFixture {
    /**
     * Result: a provenance citing [citations]. Input: the rules to cite, at least one.
     * Output: [EngineProvenance].
     */
    fun of(vararg citations: RuleCitation): EngineProvenance =
        EngineProvenance(
            engineId = "AI-GOAL.waterfall",
            engineVersion = "1.0",
            computedAtUtcMillis = 0L,
            evidence = citations.toList(),
        )
}
