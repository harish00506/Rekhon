package com.aicfo.domain.engines.investment

import com.aicfo.core.model.AssetClass
import com.aicfo.core.model.RuleCitation

/**
 * One asset class's ceiling, and the rulebook row that drew it (issue 6.4; §29.3).
 *
 * Why:  `RULE-GOLD-CAP` and `RULE-CRYPTO-CAP` are two rows sharing one `formula_id`
 *       (`asset_class_cap`) and differing only in `asset_class` and `cap_pct`. Pairing the number
 *       with its citation here — rather than keeping two scalar fields and a lookup that decides
 *       which row to blame — is what lets a breach cite *the row that decided it* and nothing else
 *       (P-02). It is also what makes a third cap a data change rather than a code change.
 * What: a whole-percent ceiling and the row it was copied from.
 * Result: the unit [InvestmentRules.assetClassCaps] is keyed by.
 * Changelog: 2026-08-28 — Created for issue 6.4 from rules-kb.json v1.13.0.
 *
 * Input:  [capPct] — the row's `cap_pct`, a whole percent of the portfolio above which the class is
 *         worth mentioning; [citation] — that row's id and version, cited verbatim in evidence.
 * Output: an immutable value.
 */
data class AssetClassCap(
    val capPct: Int,
    val citation: RuleCitation,
) {
    init {
        // At or below zero every portfolio holding any of the class is permanently over the line,
        // so the flag fires for everyone on day one and means nothing. At or above 100 it can never
        // fire, because a class cannot exceed the whole portfolio. Either way every test asserting
        // a *share* still passes while the advice is worthless — which is what a require() is for.
        require(capPct in 1..<FULL_PERCENT) {
            "capPct is a whole percent of the portfolio and must be in 1..${FULL_PERCENT - 1}, was " +
                "$capPct: at or below 0 every portfolio is always over the line, and at or above " +
                "100 no class can ever reach it"
        }
    }

    private companion object {
        /** 100% — the ceiling [capPct] must stay under. */
        const val FULL_PERCENT = 100
    }
}

/**
 * The thresholds this engine's allocation analysis applies, copied from `ai/rules/rules-kb.json`.
 *
 * Why:  CLAUDE.md §6 says a financial threshold is a **data row in `ai/`, never a hardcoded
 *       number** — and this file is hardcoded numbers. The same deliberate, recorded deferral
 *       ADR-0005 made for `QuickSetupRules` and ADR-0017 restated for `BudgetRules`: nothing in the
 *       app loads `ai/` at runtime, so honouring §6 literally would mean building an asset pipeline
 *       and a JSON parser into a module that has no serialisation dependency by design (ARC-002).
 *       `RulebookDriftTest` closes the gap that matters — edit any of the three rows in the
 *       rulebook and the build goes red until this file agrees.
 *
 *       **Three rows, and none of them is new.** `RULE-GOLD-CAP`, `RULE-CRYPTO-CAP` and
 *       `RULE-CONC-15-70` all shipped when this file was created, all already naming
 *       `AI-INV.diversification` in their `consumed_by` — the rulebook was written expecting this
 *       engine. So every threshold here is *read*, not authored, and no row is touched. That is
 *       what keeps issue 6.4 clear of ADR-0017's trigger 3, exactly as issue 6.1 stayed clear of it
 *       by leaving `RULE-CC-UTIL` alone. `_meta.version` does not move.
 *
 *       **Two AI-INV rows are deliberately absent.** `RULE-AGE-EQUITY` needs the user's age and
 *       `RULE-5-25` needs a target band to drift from; neither age nor the §11.2 risk profile
 *       (Conservative/Balanced/Growth) exists anywhere in this app yet. Mirroring a threshold this
 *       engine cannot evaluate would put a number here that no test could pin to behaviour, and
 *       citing a rule that decided nothing is worse than citing none.
 * What: one property per `params_json` key this engine applies, plus a citation per row.
 * Result: every slice and every flag is attributable to a row a reviewer can open, and every
 *       threshold that shaped it is one they can change (P-02, AI-ARC-006).
 * Changelog: 2026-08-28 — Created for issue 6.4 from rules-kb.json v1.13.0.
 *
 * **Injected rather than read, so a test can move a threshold** and assert the engine moves with it
 * — the seam the real loader will use when it lands, with no change to the engine.
 *
 * Input:  [assetClassCaps] — `RULE-GOLD-CAP` and `RULE-CRYPTO-CAP`, keyed by the class each caps;
 *         [singleHoldingPct] — `RULE-CONC-15-70.single_holding_pct`, the share of the portfolio one
 *         position may reach before it is flagged; [singleClassPct] —
 *         `RULE-CONC-15-70.single_class_pct`, the same for one asset class.
 * Output: an immutable value.
 */
data class InvestmentRules(
    /** `RULE-GOLD-CAP` and `RULE-CRYPTO-CAP` — ballast and volatility, the two the rulebook bounds. */
    val assetClassCaps: Map<AssetClass, AssetClassCap> =
        mapOf(
            AssetClass.GOLD to AssetClassCap(GOLD_CAP_PCT, GOLD_CAP),
            AssetClass.CRYPTO to AssetClassCap(CRYPTO_CAP_PCT, CRYPTO_CAP),
        ),
    /** `RULE-CONC-15-70.single_holding_pct` — one position becoming the portfolio. */
    val singleHoldingPct: Int = 15,
    /** `RULE-CONC-15-70.single_class_pct` — one asset class becoming the portfolio. */
    val singleClassPct: Int = 70,
) {
    init {
        // The same argument as AssetClassCap's, for the two concentration ceilings. A zero here
        // would flag every holding in every portfolio, including a perfectly diversified one; a
        // hundred could never flag anything, including a portfolio that is one stock.
        require(singleHoldingPct in 1..<FULL_PERCENT) {
            "singleHoldingPct is a whole percent of the portfolio and must be in " +
                "1..${FULL_PERCENT - 1}, was $singleHoldingPct: at or below 0 every holding is " +
                "always flagged, and at or above 100 a portfolio that is one holding never is"
        }
        require(singleClassPct in 1..<FULL_PERCENT) {
            "singleClassPct is a whole percent of the portfolio and must be in " +
                "1..${FULL_PERCENT - 1}, was $singleClassPct: at or below 0 every class is always " +
                "flagged, and at or above 100 a single-class portfolio never is"
        }
        // A class ceiling below a holding ceiling is incoherent: a class contains its holdings, so
        // the class share is always at least the largest holding's share. Configured the wrong way
        // round, the class flag would fire on every portfolio that trips the holding flag and the
        // two would stop meaning different things.
        require(singleClassPct >= singleHoldingPct) {
            "singleClassPct ($singleClassPct) must not be below singleHoldingPct " +
                "($singleHoldingPct): a class contains its holdings, so its share is never smaller"
        }
    }

    companion object {
        /** `RULE-GOLD-CAP` — gold as ballast, not a core growth engine. Read, not written. */
        val GOLD_CAP = RuleCitation("RULE-GOLD-CAP", "1.0")

        /** `RULE-CRYPTO-CAP` — bounds exposure to a highly volatile asset. Read, not written. */
        val CRYPTO_CAP = RuleCitation("RULE-CRYPTO-CAP", "1.0")

        /** `RULE-CONC-15-70` — concentration risk, the fastest way to a permanent loss. */
        val CONCENTRATION = RuleCitation("RULE-CONC-15-70", "1.0")

        /** `RULE-PRICE-STALE` — how often to re-fetch a price, and when to call one old. */
        val PRICE_STALE = RuleCitation("RULE-PRICE-STALE", "1.0")

        /**
         * Every row the **allocation** analysis cites, in the order a drill-down should show them.
         *
         * `PRICE_STALE` is deliberately absent: it decides nothing about how a portfolio is spread,
         * and a clean allocation citing a staleness rule would be pointing the user at a row that
         * had no part in the decision.
         */
        val CITATIONS = listOf(GOLD_CAP, CRYPTO_CAP, CONCENTRATION)

        /** The rulebook file these thresholds were copied from, as `_meta.version`. */
        const val RULEBOOK_VERSION = "1.15.0"

        /** `RULE-GOLD-CAP.cap_pct`. */
        private const val GOLD_CAP_PCT = 10

        /** `RULE-CRYPTO-CAP.cap_pct`. */
        private const val CRYPTO_CAP_PCT = 5

        /** 100% — the ceiling every whole-percent threshold here must stay under. */
        private const val FULL_PERCENT = 100
    }
}

/** 10 000 bps = 100% (MNY-002). The scale every share in this engine is expressed on. */
internal const val BPS_FULL = 10_000

/** 100 bps = 1%, so a whole-percent rule threshold becomes a bps threshold (MNY-002). */
internal const val BPS_PER_PERCENT = 100
