package com.aicfo.domain.engines.classification

import com.aicfo.core.common.AppError
import com.aicfo.core.common.Result
import com.aicfo.core.common.runCatchingToResult
import com.aicfo.core.model.EngineProvenance
import com.aicfo.core.model.RuleCitation

/**
 * The production [ClassificationEngine] — §8.1's precedence chain, in order (issue 4.2).
 *
 * Why:  read top to bottom, [suggest] is the SRS's own list: what the user has decided about this
 *       merchant, then what the knowledge base says about it, then nothing. The tempting
 *       implementation is the opposite shape — collect every candidate from every source, score
 *       them, take the best — and it fails on the case the precedence exists for. A user who files
 *       Swiggy under Groceries because they only ever order instamart would be out-scored by a
 *       shipped rule that names Dining with high confidence, and the app would spend forever
 *       correcting a correction. **Precedence is not a tie-break; the earlier tier is allowed to
 *       decide, including to decide nothing.**
 *
 *       That last clause is [historySuggestion]'s only surprise: a merchant the user has filed
 *       inconsistently proposes nothing *and does not fall through*. They have demonstrably formed
 *       their own opinion; the knowledge base has no standing to overrule an opinion just because it
 *       is a confused one.
 * What: history tier → knowledge-base tier → `null`.
 * Result: a category the profile actually has, or the "Uncategorised" prompt §8.1 ends on. Nothing
 *       here writes (P-07).
 * Changelog: 2026-08-10 — Created for issue 4.2.
 *
 * `internal` per ARC-003 — constructed only by [ClassificationEngineFactory].
 *
 * **There is not a `Double` in this file** (MNY-001/MNY-002): every confidence is integer basis
 * points, and the one division — a share of past transactions — is integer arithmetic that rounds
 * *down*, so a marginal history falls below the floor rather than above it.
 *
 * **Nothing here reads a clock** (TIM-001). The instant stamped into provenance is the caller's,
 * which is what makes the eval set reproducible (P-08).
 */
internal class DefaultClassificationEngine : ClassificationEngine {
    override fun suggest(input: ClassificationInput): Result<CategorySuggestion?, AppError> =
        runCatchingToResult {
            val merchant = normaliseMerchant(input.merchant)
            if (merchant.isEmpty()) {
                null
            } else {
                // `resolvable` is computed once and passed down: both tiers have to answer "is this
                // category still on the profile?", and asking it twice against two different lists is
                // how a suggestion ends up naming a row the user deleted.
                val live = input.categories.map { it.id }.toSet()
                when {
                    input.history.any { it.categoryId in live } -> historySuggestion(input, live)
                    else -> knowledgeBaseSuggestion(merchant, input)
                }
            }
        }

    /**
     * Tier (a) — what the user has already decided about this merchant (SRS §8.1(a)).
     *
     * Why:    confidence is the **share** of past filings that agree, not the count: a merchant
     *         filed three times the same way and one filed thirty times the same way are equally
     *         settled questions, while three-out-of-five is not a rule at all. Rows naming a
     *         category that no longer exists are dropped *before* the share is taken — a deleted
     *         category is not a dissenting vote, it is an absent one, and counting it would let a
     *         tidy-up in the categories editor silently mute every rule the user had taught.
     *
     *         Called only when at least one row resolves, so the "no opinion" case never reaches
     *         here and the caller's `when` keeps the fall-through rule visible in one place.
     * Result: the category the user settled on, or `null` when they have not settled — and `null`
     *         here ends Stage 1 rather than deferring to the knowledge base (see the class doc).
     * Input:  [input]; [live] — the ids of the profile's live categories.
     * Output: `CategorySuggestion?`.
     * Changelog: 2026-08-10 — Created for issue 4.2.
     */
    private fun historySuggestion(
        input: ClassificationInput,
        live: Set<String>,
    ): CategorySuggestion? {
        val resolvable = input.history.filter { it.categoryId in live }
        val settled = resolvable.maxByOrNull { it.count } ?: return null
        val total = resolvable.sumOf { it.count }
        // Integer division on purpose (MNY-002): 2 of 3 is 6 666 bps, not 6 666.67, and rounding it
        // up is how a majority the user never agreed to would clear the floor.
        val confidenceBps = BPS_FULL * settled.count / total
        val settledEnough =
            settled.count >= input.rules.historyMinOccurrences && confidenceBps >= input.rules.minConfidenceBps
        return if (!settledEnough) {
            null
        } else {
            CategorySuggestion(
                categoryId = settled.categoryId,
                provenance =
                    provenance(
                        input = input,
                        evidence = listOf(input.rules.userHistory),
                        confidenceBps = confidenceBps,
                        inputWindow = "${settled.count} of $total prior transactions",
                    ),
            )
        }
    }

    /**
     * Tier (b) — the shipped knowledge base (SRS §8.1(b)).
     *
     * Why:    the ambiguity check runs **before** the best match is taken, and that order is the
     *         point. `AMAZON PAY NETFLIX` matches Shopping and Subscriptions at identical
     *         confidence, so "take the best" would resolve it by whichever row the file happens to
     *         list first — the knowledge base's *ordering* deciding where the user's money goes,
     *         which is not a decision an ordering should be making. Two categories, no suggestion.
     *
     *         The category is resolved **by name against the live taxonomy**, so a rule can only ever
     *         propose a row that exists. `ponytail:` that also means a seeded category renamed before
     *         its merchant was ever categorised stops matching, and Stage 1 defers until the user
     *         files it once and tier (a) takes over permanently. Resolving through the seed key
     *         instead would mean teaching this module the repository's `"$profileId:category:$key"`
     *         id scheme — a storage detail leaking into a pure engine to buy back one tap.
     * Result: the category the rules name, or `null`.
     * Input:  [merchant] — normalised; [input]. Output: `CategorySuggestion?`.
     * Changelog: 2026-08-10 — Created for issue 4.2.
     */
    private fun knowledgeBaseSuggestion(
        merchant: String,
        input: ClassificationInput,
    ): CategorySuggestion? {
        val rules = input.rules
        val matches = rules.merchantRules.mapNotNull { rule -> rule.confidenceFor(merchant, rules)?.let { rule to it } }
        // Also covers "nothing matched": a set of no categories is not a set of one.
        if (matches.mapTo(mutableSetOf()) { it.first.category.lowercase() }.size != 1) return null
        val best = matches.maxByOrNull { it.second }
        val category = input.categories.firstOrNull { it.name.equals(best?.first?.category, ignoreCase = true) }
        return if (best == null || category == null || best.second < rules.minConfidenceBps) {
            null
        } else {
            CategorySuggestion(
                categoryId = category.id,
                provenance = provenance(input, listOf(best.first.citation), best.second, inputWindow = null),
            )
        }
    }

    /**
     * Stamps a suggestion with where it came from (AI-ARC-003, AI-ARC-006, P-02).
     * Why:    one builder, so the engine id and version cannot differ between the two tiers — a
     *         suggestion whose provenance named a different engine depending on which rule fired
     *         would be unreproducible in exactly the way AI-ARC-006 exists to prevent.
     * Result: the provenance to carry. Input: [input] — for the caller's instant; [evidence] — the
     *         rule that fired; [confidenceBps]; [inputWindow] — what was read, or `null` when the
     *         tier reads no history. Output: [EngineProvenance].
     * Changelog: 2026-08-10 — Created for issue 4.2.
     */
    private fun provenance(
        input: ClassificationInput,
        evidence: List<RuleCitation>,
        confidenceBps: Int,
        inputWindow: String?,
    ): EngineProvenance =
        EngineProvenance(
            engineId = ENGINE_ID,
            engineVersion = ENGINE_VERSION,
            computedAtUtcMillis = input.nowUtcMillis,
            evidence = evidence,
            inputWindow = inputWindow,
            confidenceBps = confidenceBps,
        )

    private companion object {
        /** Stable identifier stored with every suggestion (AI-ARC-003). */
        const val ENGINE_ID = "auto-categoriser"

        /**
         * Bump whenever the precedence or the matching changes (AI-ARC-006).
         *
         * Stored with every suggestion, so a category accepted today can still be explained after
         * the engine is rewritten — which is the whole reason the field exists.
         */
        const val ENGINE_VERSION = "1.0"
    }
}

/**
 * What this rule is worth on this merchant, or nothing (issue 4.2).
 * Why:    the best of the row's alternatives rather than the first, so `uber|ola|rapido` behaves the
 *         same whichever alternative the merchant happens to be — a rule whose confidence depended on
 *         the order its literals were typed in would be a rule nobody could reason about.
 * Result: [ClassificationRules.exactMatchBps] when the merchant *is* one of the literals,
 *         [ClassificationRules.wordMatchBps] when a literal appears as a whole word inside it, and
 *         `null` when none of them appears at all.
 * Input:  the receiver — one rule; [merchant] — normalised; [rules] — for the two rates.
 * Output: `Int?`.
 * Changelog: 2026-08-10 — Created for issue 4.2.
 */
private fun MerchantRule.confidenceFor(
    merchant: String,
    rules: ClassificationRules,
): Int? =
    tokens.mapNotNull { token ->
        when {
            merchant == token -> rules.exactMatchBps
            merchant.containsWord(token) -> rules.wordMatchBps
            else -> null
        }
    }.maxOrNull()

/**
 * Whether the merchant contains this literal **as a whole word** (issue 4.2).
 *
 * Why:    a plain `in` would be shorter and is wrong in a way that misfiles real money. `CLS-MER-010`
 *         matches on `lic`, so a substring search files every **Licious** order — a meat delivery
 *         service most Indian users of this app will have in their ledger — under *Insurance*, where
 *         it becomes a NEED, joins the emergency-fund essentials, and is the last place anyone would
 *         look for it. `coin` inside `bitcoin`, `ola` inside `cola`, and `lic` inside `publicis` are
 *         the same bug. Every literal in the knowledge base is matched through here, so the rule is
 *         stated once — and so a rulebook editor can add a short literal without having to reason
 *         about which longer words contain it.
 *
 *         A word here ends at anything that is not a letter, which is deliberately looser than `\b`:
 *         `hp petrol` and `et money` are single literals containing a space, and digits are a
 *         boundary so `iocl` matches `IOCL1234` and `1mg` matches `NETMEDS 1MG`. Kept identical to
 *         `:domain:engines:sms`'s rule rather than merged with it, because the two modules share no
 *         code by design (ARC-002) and a shared text utility would be the first thing to drag one
 *         engine into the other's dependency graph.
 * Result: `true` when the literal appears bounded by non-letters on both sides.
 * Input:  the receiver — the normalised merchant; [literal] — a lower-cased literal.
 * Output: `Boolean`.
 * Changelog: 2026-08-10 — Created for issue 4.2.
 */
private fun String.containsWord(literal: String): Boolean {
    if (literal.isEmpty()) return false
    var from = 0
    while (true) {
        val at = indexOf(literal, from)
        if (at < 0) return false
        val end = at + literal.length
        val openLeft = at == 0 || !this[at - 1].isLetter()
        val openRight = end == length || !this[end].isLetter()
        if (openLeft && openRight) return true
        from = at + 1
    }
}
