package com.aicfo.domain.engines.nature

import com.aicfo.core.common.AppError
import com.aicfo.core.common.Result
import com.aicfo.core.common.runCatchingToResult
import com.aicfo.core.model.AccountType
import com.aicfo.core.model.CategoryNature
import com.aicfo.core.model.EngineProvenance
import com.aicfo.core.model.Money
import com.aicfo.core.model.RuleCitation

/**
 * The production [NatureEngine] — §8.3.1's decision order, in order (issue 4.3).
 *
 * Why:  read top to bottom, [classify] is the SRS's own list and nothing else. The tempting
 *       implementation is to score every signal and take the best, and it fails on the case the
 *       order exists for: a loan repayment carries a category, a merchant and an amount, all of
 *       which have opinions, and **exactly one of them is entitled to decide**. An account's type is
 *       a fact the user entered; a category is a label; a merchant history is an inference. Ranking
 *       them by confidence would let a strong inference outvote a fact.
 *
 *       Step 6 is deliberately not in the ladder. §8.3.1 writes it as a *context modifier*, and the
 *       distinction is the whole of its behaviour: it never changes the nature, it lowers the
 *       confidence so the verdict reaches the review flow. An implementation that let it flip NEED
 *       to WANT would be the app answering the question it was supposed to ask.
 * What: loan account → investment transfer → asset transfer → learned override → category → the
 *       fallback, then the unusual-amount modifier on top of whichever fired.
 * Result: a nature for every transaction, naming the step that chose it. Nothing here writes (P-07).
 * Changelog: 2026-08-10 — Created for issue 4.3.
 *
 * `internal` per ARC-003 — constructed only by [NatureEngineFactory].
 *
 * **There is not a `Double` in this file** (MNY-001/MNY-002): every confidence is integer basis
 * points, the one division rounds *down* so a marginal history falls below the floor rather than
 * above it, and the step-6 comparison multiplies a [Money] by an `Int`.
 *
 * **Nothing here reads a clock** (TIM-001); the instant in provenance is the caller's.
 */
internal class DefaultNatureEngine : NatureEngine {
    override fun classify(input: NatureInput): Result<NatureVerdict, AppError> =
        runCatchingToResult {
            val decided = accountVerdict(input) ?: historyVerdict(input) ?: categoryVerdict(input)
            // Step 6 last, and applied to whatever the ladder decided rather than replacing it.
            decided.withUnusualAmountFlag(input)
        }

    /**
     * Steps 1–3 — what the accounts say (SRS §8.3.1).
     *
     * Why:    one function for three steps because they read the same two fields and share the same
     *         justification: an account's type is a fact the user entered on the accounts screen,
     *         not something inferred from a payee string, which is why all three are worth full
     *         confidence and why all three outrank everything below.
     *
     *         **All three look at both sides of the movement**, and the first draft of this function
     *         did not — steps 2–3 read only the counterpart, on the reasoning that money *arriving*
     *         in an investment account is only a conversion when it came from the user's own
     *         account. The golden file caught it: the arriving leg of an SIP fell past every account
     *         step to the category, so one half of every conversion was labelled a Want. The
     *         reasoning was not wrong, only misplaced — **[counterpartAccountType] being non-null is
     *         already "this is a transfer leg"**, so a dividend credited into an investment account
     *         is an `INCOME` row with no counterpart and never reaches this branch at all.
     *
     *         Both legs of a conversion therefore carry the same nature, which is what makes them
     *         *the same event* rather than two — and why `natureBreakdown` counts only one of them.
     *
     *         Step 1 keeps its own shape because §8.3.1 says "txn on a loan account", not "transfer":
     *         a fee booked directly in a loan account is debt service with no second leg at all.
     * Result: the verdict, or `null` when no account says anything.
     * Input:  [input]. Output: `NatureVerdict?`.
     * Changelog: 2026-08-10 — Created for issue 4.3.
     */
    private fun accountVerdict(input: NatureInput): NatureVerdict? {
        val rules = input.rules
        val counterpart = input.counterpartAccountType
        // Empty for anything that is not a transfer leg, which is what keeps income out of steps 2–3.
        val involved = if (counterpart == null) emptySet() else setOf(input.accountType, counterpart)
        val (nature, rule) =
            when {
                input.accountType == AccountType.LOAN || counterpart == AccountType.LOAN ->
                    CategoryNature.LIABILITY to NatureRules.LOAN_ACCOUNT

                involved.any { it in rules.investmentAccountTypes } ->
                    CategoryNature.INVEST to NatureRules.INVESTMENT_TRANSFER

                involved.any { it in rules.assetAccountTypes } ->
                    CategoryNature.ASSET to NatureRules.ASSET_TRANSFER

                else -> return null
            }
        return verdict(input, nature, listOf(rule), rules.accountOverrideBps)
    }

    /**
     * Step 4 — what the user has already decided about this merchant (SRS §8.3.1).
     *
     * Why:    confidence is the **share** of past overrides that agree, not the count: a merchant
     *         corrected three times the same way and one corrected thirty times the same way are
     *         equally settled questions, while two-out-of-three is not a decision at all. Identical
     *         to the history tier issue 4.2 built for categories, on purpose — one rule for a reader
     *         to learn, and the same yielding behaviour in both places.
     *
     *         **Yielding here means carrying on down the list**, which is where this differs from
     *         4.2's classifier: a category may be left unset, so there an unsettled history ends the
     *         search. Every transaction must end with a nature, so an unsettled history simply lets
     *         the category answer instead.
     * Result: the settled nature, or `null` when the user has not settled the question.
     * Input:  [input]. Output: `NatureVerdict?`.
     * Changelog: 2026-08-10 — Created for issue 4.3.
     */
    private fun historyVerdict(input: NatureInput): NatureVerdict? {
        val settled = input.merchantHistory.maxByOrNull { it.count } ?: return null
        val total = input.merchantHistory.sumOf { it.count }
        // Integer division on purpose (MNY-002): 2 of 3 is 6 666 bps, not 6 666.67, and rounding it
        // up is how a majority the user never agreed to would clear the floor.
        val confidenceBps = BPS_FULL * settled.count / total
        return if (confidenceBps < input.rules.minConfidenceBps) {
            null
        } else {
            verdict(input, settled.nature, listOf(NatureRules.USER_HISTORY), confidenceBps)
        }
    }

    /**
     * Step 5 and its fallback — what the category says, or the admission that nothing does.
     *
     * Why:    the fallback is not a sixth guess, it is the honest end of the list. §8.3 requires a
     *         nature on every transaction and the 50/30/20 rings cannot render a hole, so an
     *         uncategorised expense gets the rulebook's default — **below the confidence floor**, so
     *         it arrives in the review flow labelled as the default it is rather than sitting in the
     *         ledger looking decided (P-02).
     * Result: the verdict; never `null`, because this is where the ladder ends.
     * Input:  [input]. Output: [NatureVerdict].
     * Changelog: 2026-08-10 — Created for issue 4.3.
     */
    private fun categoryVerdict(input: NatureInput): NatureVerdict {
        val rules = input.rules
        val nature = input.categoryNature
        return verdict(
            input = input,
            nature = nature ?: rules.fallbackNature,
            evidence = listOf(NatureRules.CATEGORY_DEFAULT),
            confidenceBps = if (nature == null) rules.fallbackBps else rules.categoryDefaultBps,
        )
    }

    /**
     * Step 6 — the context modifier (SRS §8.3.1).
     *
     * Why:    it **keeps the nature and lowers the confidence**, which is the difference between
     *         asking a question and answering it. §8.3.1's own example is ₹9,400 at a grocery:
     *         festival stock-up (still a NEED) or a party (a WANT)? Only the user knows, so the
     *         engine's job is to notice and say so — and §8.3's `low_confidence_handling` is explicit
     *         that noticing never blocks the save.
     *
     *         Bounded three ways, each of which removes a class of false alarm: only in a **NEED**
     *         category, because a large WANT is not ambiguous about what it was; only when a median
     *         exists at all, so the second grocery run a user records is not compared against the
     *         first; and **strictly greater** than the multiple, so the boundary case reads as
     *         ordinary. The comparison is on the magnitude, since an expense's amount is negative.
     * Result: the same verdict, or a copy with the confidence dropped and `CLS-NAT-006` appended to
     *         its evidence — appended, not replacing, so the card can still say *what* it decided
     *         as well as *why it is asking*.
     * Input:  the receiver — what the ladder decided; [input]. Output: [NatureVerdict].
     * Changelog: 2026-08-10 — Created for issue 4.3.
     */
    private fun NatureVerdict.withUnusualAmountFlag(input: NatureInput): NatureVerdict {
        val rules = input.rules
        val median = input.categoryMedian
        val magnitude = if (input.amount < Money.ZERO) Money.ZERO - input.amount else input.amount
        val unusual =
            nature == CategoryNature.NEED &&
                median != null &&
                median > Money.ZERO &&
                magnitude > median * rules.unusualAmountMultiple
        return if (!unusual) {
            this
        } else {
            copy(
                provenance =
                    provenance.copy(
                        evidence = provenance.evidence + NatureRules.UNUSUAL_AMOUNT,
                        confidenceBps = rules.unusualAmountBps,
                    ),
            )
        }
    }

    /**
     * Builds one verdict (AI-ARC-003, AI-ARC-006, P-02).
     * Why:    one builder, so the engine id and version cannot differ between steps — a verdict whose
     *         provenance named a different engine depending on which rule fired would be
     *         unreproducible in exactly the way AI-ARC-006 exists to prevent.
     * Result: the verdict. Input: [input] — for the caller's instant and the rules; [nature];
     *         [evidence] — the step that fired; [confidenceBps]. Output: [NatureVerdict].
     * Changelog: 2026-08-10 — Created for issue 4.3.
     */
    private fun verdict(
        input: NatureInput,
        nature: CategoryNature,
        evidence: List<RuleCitation>,
        confidenceBps: Int,
    ): NatureVerdict =
        NatureVerdict(
            nature = nature,
            provenance =
                EngineProvenance(
                    engineId = ENGINE_ID,
                    engineVersion = ENGINE_VERSION,
                    computedAtUtcMillis = input.nowUtcMillis,
                    evidence = evidence,
                    confidenceBps = confidenceBps,
                ),
            rules = input.rules,
        )

    private companion object {
        /** Stable identifier stored with every verdict (AI-ARC-003). */
        const val ENGINE_ID = "nature-classifier"

        /**
         * Bump whenever the decision order changes (AI-ARC-006).
         *
         * Carried by every verdict, so a nature shown today can still be explained after the order
         * is rewritten — which is the whole reason the field exists.
         */
        const val ENGINE_VERSION = "1.0"
    }
}
