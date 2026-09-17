package com.aicfo.domain.engines.orderofoperations

import com.aicfo.core.model.Money
import com.aicfo.core.model.RuleCitation

/**
 * The stage thresholds AI-FOO applies, copied from `ai/rules/financial-order-of-operations.json`
 * (issue 7.5; SRS §36, FOO-003).
 *
 * Why:  FOO-003 says "stage thresholds are rulebook rows — editable, versioned, cited", and the FOO
 *       file is where §36's rows already live: each stage carries an `id`, a `params_json` and the
 *       file's `version`. Nothing in the app loads `ai/` at runtime (ADR-0005, ADR-0017), so this is
 *       the same deliberate, recorded deferral every other engine makes — a typed mirror, held to
 *       the file by `OrderOfOperationsRulesDriftTest`. Edit a threshold in the file and the build
 *       goes red until this class agrees.
 * What: one property per stage parameter the engine **applies**, converted to the units this
 *       codebase computes in — paise (MNY-001) and basis points (MNY-002). The file writes
 *       `apr_threshold_pct: 13.5`; this class holds `1350`.
 * Result: every stage boundary the engine draws is attributable to a row a reviewer can open.
 * Changelog: 2026-09-17 — Created for issue 7.5 from financial-order-of-operations.json v1.0.
 *
 * **Only the parameters the engine reads are mirrored** (ADR-0034's rule). Three it could hold are
 * deliberately absent, and the drift test asserts each is still what this KDoc says it is:
 *
 * - `nps_1b_inr` (Stage 4). Stage 4 is always skipped — it needs §38's regime comparator, issue
 *   13.4 — so a copy of its number would be a figure nothing reads.
 * - `apr_range_pct[1] = 12` (Stage 6). §36 writes the grey zone as "APR ~10–12%", and the tilde is
 *   doing work: taken as a hard ceiling, a 12.5% loan would fall into neither Stage 2 (≥ 13.5%) nor
 *   Stage 6, and land in Stage 7's "genuine toss-up" by accident. So the band runs from
 *   [greyAprMinBps] up to, not including, [fireAprThresholdBps]. ADR-0037 records the reading.
 * - `RULE-EMERG-FIRST.min_runway_months` (Stage 3's `gate_rule`). Already mirrored once, by
 *   `QuickSetupRules`; a second copy is ADR-0017's trigger 2. It arrives as
 *   `OrderOfOperationsInput.emergencyGateMonths`, as it does for `GoalWaterfallEngine` (ADR-0035).
 *
 * Input:  [starterCap] — Stage 0's `cap_inr`, in paise; [starterEssentialsMonths] — Stage 0's
 *         `or_months_essentials`; [fireAprThresholdBps] — Stage 2's `apr_threshold_pct` in bps;
 *         [greyAprMinBps] — Stage 6's `apr_range_pct[0]` in bps; [equityNominalBps] — Stage 6's
 *         `equity_nominal_pct` in bps, the figure a grey-zone debt is compared against.
 * Output: an immutable value.
 */
data class OrderOfOperationsRules(
    /** Stage 0 `cap_inr` — ₹50,000, the most a *starter* buffer asks for. */
    val starterCap: Money = Money(STARTER_CAP_MINOR),
    /** Stage 0 `or_months_essentials` — or one month of essentials, whichever is smaller. */
    val starterEssentialsMonths: Int = 1,
    /** Stage 2 `apr_threshold_pct` — 13.5% as basis points. At or above this, a debt is fire debt. */
    val fireAprThresholdBps: Int = 1_350,
    /** Stage 6 `apr_range_pct[0]` — 10% as basis points. Below this, a debt is low-rate (Stage 7). */
    val greyAprMinBps: Int = 1_000,
    /** Stage 6 `equity_nominal_pct` — 12% as basis points, shown beside a grey-zone debt's rate. */
    val equityNominalBps: Int = 1_200,
) {
    init {
        // An inverted or empty band silently swallows a stage: with the grey floor above the fire
        // threshold, no debt could ever reach Stage 6, and every test on an *amount* would still pass.
        require(starterCap >= Money.ZERO) { "Stage 0's cap is a magnitude, was $starterCap" }
        require(starterEssentialsMonths >= 0) {
            "Stage 0's month count must not be negative, was $starterEssentialsMonths"
        }
        require(greyAprMinBps in 0..fireAprThresholdBps) {
            "The grey band starts at $greyAprMinBps bps, which must lie between 0 and the fire " +
                "threshold ($fireAprThresholdBps bps) — otherwise Stage 6 is unreachable"
        }
        require(equityNominalBps >= 0) { "An expected return must not be negative, was $equityNominalBps" }
    }

    companion object {
        /** Stage 0 `cap_inr` in paise: ₹50,000 × 100. */
        private const val STARTER_CAP_MINOR = 5_000_000L

        /**
         * The FOO file these thresholds were copied from, as `_meta.version`.
         *
         * Every stage is cited at this version: the file versions its rows together, so a stored
         * ranking stays reproducible by naming it (AI-ARC-006).
         */
        const val FILE_VERSION = "1.0"

        /**
         * §36 — "operationalises RULE-EMERG-FIRST as a stage gate" (Stage 3's `gate_rule`).
         *
         * **A citation, deliberately not a mirror** — see the class KDoc and ADR-0035.
         */
        val EMERGENCY_FIRST = RuleCitation("RULE-EMERG-FIRST", "1.0")

        /** §36 Stage 5's `bucket_rule` — which bucket a goal's money belongs in. Cited, not applied. */
        val HORIZON = RuleCitation("RULE-HORIZON", "1.0")

        /**
         * §36 Stage 7 — "home loan prepay vs invest → existing simulator".
         *
         * The simulator is issue 10.3 and does not exist yet, so Stage 7 cites the rule and carries
         * no amount. The row already names `AI-FOO.stage7` in its `consumed_by`.
         */
        val PREPAY_VS_INVEST = RuleCitation("RULE-PREPAY-VS-INVEST", "1.0")

        /**
         * The citation for one stage of the FOO file.
         *
         * Why:    FOO-003 says the stage thresholds are "cited", and they have no `RULE-*` id — the
         *         stage's own `id` is its public name. A dotted `FOO.` prefix follows the house
         *         convention for a facet id (`AI-GOAL.waterfall`, `AI-FOO.stage7`) and keeps a stage
         *         citation from ever colliding with a rulebook row.
         * Result: `FOO.<STAGE_ID>` at [FILE_VERSION]. Input: [stage]. Output: [RuleCitation].
         */
        fun citationFor(stage: FooStage): RuleCitation = RuleCitation("FOO.${stage.fileId}", FILE_VERSION)
    }
}
