package com.aicfo.domain.engines.nature

import com.aicfo.core.model.AccountType
import com.aicfo.core.model.CategoryNature
import com.aicfo.core.model.RuleCitation

/**
 * The thresholds and steps §8.3.1 applies, copied from `ai/knowledge/classification-kb.json`.
 *
 * Why:  CLAUDE.md §6 says a classification rule is a **data row in `ai/`, never hardcoded logic** —
 *       and this file is six hardcoded steps and eight hardcoded numbers. The same deliberate,
 *       recorded deferral ADR-0005 made for `QuickSetupRules`, ADR-0014 for `CategorySeed` and
 *       ADR-0015 for `ClassificationRules`: nothing in the app loads `ai/` at runtime, so honouring
 *       §6 literally would mean building an asset pipeline and a JSON parser into a module that has
 *       no serialisation dependency by design (ARC-002). `NatureKbDriftTest` closes the gap that
 *       matters — edit a step or a threshold in the knowledge base and the build goes red until this
 *       file agrees.
 * What: one citation per `CLS-NAT-*` step, plus the `stage_nature` block.
 * Result: every nature this app assigns is attributable to a row a reviewer can open, and every
 *       threshold that decided how sure it was is one they can change (P-02, AI-ARC-006).
 * Changelog: 2026-08-10 — Created for issue 4.3 from classification-kb.json v1.3.
 *
 * **Injected rather than read, so a test can move a threshold** and assert the engine moves with it
 * — which is also the seam the real loader will use when it lands, with no change to the engine.
 *
 * **The account types are here, not in the engine, and that is the §6-shaped part of this issue.**
 * §8.3.1 steps 2 and 3 name "an investment/goal-linked account" and "an asset record"; this build
 * has neither a goals table nor a holdings table, so the signal is the account's *type* instead
 * (ADR-0016). Which types count as investment and which as asset is a financial judgement, so it
 * lives in a value a reviewer can read and a test can move, rather than inside a `when`.
 *
 * Input:  [minConfidenceBps] — `stage_nature.min_confidence_bps`, the floor under which a verdict is
 *         flagged for review (MNY-002, so no `Float` reaches the engine); [accountOverrideBps] —
 *         what steps 1–3 are worth, since an account's type is a fact rather than an inference;
 *         [categoryDefaultBps] — what step 5 is worth; [fallbackBps] — what a verdict with nothing
 *         to go on is worth, deliberately below the floor; [unusualAmountBps] — what step 6 drops a
 *         verdict to, also below the floor; [unusualAmountMultiple] — §8.3.1's `3x`;
 *         [minHistoryForMedian] — how many past transactions a category needs before its median is
 *         worth comparing against; [fallbackNature] — what an expense with no category becomes;
 *         [investmentAccountTypes] / [assetAccountTypes] — the step-2 and step-3 signals;
 *         [trueSpendNatures] — §8.3's `trueSpend` terms; [conversionNatures] — the two shown
 *         separately.
 * Output: an immutable value.
 */
data class NatureRules(
    /** `stage_nature.min_confidence_bps` — below this a verdict is flagged for review (§8.3). */
    val minConfidenceBps: Int = 7_000,
    /** `stage_nature.account_override_bps` — steps 1–3: an account type is a fact, not a guess. */
    val accountOverrideBps: Int = 10_000,
    /** `stage_nature.category_default_bps` — step 5. */
    val categoryDefaultBps: Int = 8_500,
    /** `stage_nature.fallback_bps` — nothing to go on. Below the floor on purpose. */
    val fallbackBps: Int = 3_000,
    /** `stage_nature.unusual_amount_bps` — what step 6 drops a verdict to. Below the floor. */
    val unusualAmountBps: Int = 5_000,
    /** `stage_nature.unusual_amount_multiple` — §8.3.1's `3x category median`. */
    val unusualAmountMultiple: Int = 3,
    /** `stage_nature.min_history_for_median` — fewer than this and a median means nothing. */
    val minHistoryForMedian: Int = 3,
    /** `stage_nature.fallback_nature` — §8.3.1 step 5's default when there is no category. */
    val fallbackNature: CategoryNature = CategoryNature.WANT,
    /** §8.3.1 step 2's signal, standing in for "investment/goal-linked account" (ADR-0016). */
    val investmentAccountTypes: Set<AccountType> = setOf(AccountType.INVESTMENT),
    /** §8.3.1 step 3's signal, standing in for "creates/updates an asset record" (ADR-0016). */
    val assetAccountTypes: Set<AccountType> =
        setOf(AccountType.GOLD, AccountType.PROPERTY, AccountType.VEHICLE, AccountType.CRYPTO),
    /** `stage_nature.true_spend_natures` — §8.3's `trueSpend = NEED + WANT + interest/fees`. */
    val trueSpendNatures: Set<CategoryNature> = setOf(CategoryNature.NEED, CategoryNature.WANT),
    /** `stage_nature.conversion_natures` — the two §8.3 shows separately from spend. */
    val conversionNatures: Set<CategoryNature> = setOf(CategoryNature.INVEST, CategoryNature.ASSET),
) {
    init {
        listOf(minConfidenceBps, accountOverrideBps, categoryDefaultBps, fallbackBps, unusualAmountBps)
            .forEach { bps ->
                require(bps in 0..BPS_FULL) { "Confidence is basis points in 0..$BPS_FULL (MNY-002), was $bps" }
            }
        // Both are *defined* as "below the floor" — they are the two ways §8.3's review flow gets
        // anything to review. Letting either drift above it would silently empty that flow while
        // every test that checks the nature itself kept passing.
        require(fallbackBps < minConfidenceBps) {
            "fallbackBps ($fallbackBps) must sit below minConfidenceBps ($minConfidenceBps): a verdict " +
                "with nothing to go on is exactly what §8.3 asks to be flagged"
        }
        require(unusualAmountBps < minConfidenceBps) {
            "unusualAmountBps ($unusualAmountBps) must sit below minConfidenceBps ($minConfidenceBps): " +
                "step 6 exists to raise a question, and above the floor it would raise none"
        }
        require(categoryDefaultBps >= minConfidenceBps) {
            "categoryDefaultBps ($categoryDefaultBps) is below the floor, which would flag every " +
                "ordinary categorised transaction and make the flag meaningless"
        }
        // A multiple of 1 makes every above-median amount unusual, which is half of them.
        require(unusualAmountMultiple > 1) {
            "unusualAmountMultiple must be greater than 1, was $unusualAmountMultiple"
        }
        require(minHistoryForMedian >= 1) { "minHistoryForMedian must be at least 1, was $minHistoryForMedian" }
        require(investmentAccountTypes.isNotEmpty()) { "step 2 needs at least one investment account type" }
        require(assetAccountTypes.isNotEmpty()) { "step 3 needs at least one asset account type" }
        require(trueSpendNatures.isNotEmpty()) { "§8.3's trueSpend needs at least one nature" }
        // The two sets must not overlap, or a rupee would be counted as spend and as a conversion.
        require(trueSpendNatures.intersect(conversionNatures).isEmpty()) {
            "a nature cannot be both true spend and a conversion — it would be counted twice"
        }
    }

    companion object {
        /** §8.3.1 step 1 — the transaction touches a loan account. */
        val LOAN_ACCOUNT = RuleCitation("CLS-NAT-001", "1.0")

        /** §8.3.1 step 2 — a transfer into an investment account. */
        val INVESTMENT_TRANSFER = RuleCitation("CLS-NAT-002", "1.0")

        /** §8.3.1 step 3 — a transfer into an asset account. */
        val ASSET_TRANSFER = RuleCitation("CLS-NAT-003", "1.0")

        /** §8.3.1 step 4 — the user's own past overrides for this merchant. */
        val USER_HISTORY = RuleCitation("CLS-NAT-004", "1.0")

        /** §8.3.1 step 5 — the category's default nature, and its no-category fallback. */
        val CATEGORY_DEFAULT = RuleCitation("CLS-NAT-005", "1.0")

        /** §8.3.1 step 6 — the modifier: unusually large for this category. */
        val UNUSUAL_AMOUNT = RuleCitation("CLS-NAT-006", "1.0")

        /** The knowledge-base file these steps and thresholds were copied from, as `_meta.version`. */
        const val KB_VERSION = "1.3"
    }
}

/** 10 000 bps = 100% (MNY-002). */
internal const val BPS_FULL = 10_000
