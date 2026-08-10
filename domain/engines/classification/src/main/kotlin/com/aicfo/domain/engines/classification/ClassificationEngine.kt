package com.aicfo.domain.engines.classification

import com.aicfo.core.common.AppError
import com.aicfo.core.common.Result
import com.aicfo.core.model.Category
import com.aicfo.core.model.EngineProvenance

/**
 * Proposes what a payment was *for* (issue 4.2; SRS §8.1 Stage 1, AI-CLS, P-02, P-03, P-07).
 *
 * Why:  `ai/knowledge/classification-kb.json` has held thirteen merchant→category rules since issue
 *       4.1 and **nothing resolved a single one of them** — ADR-0014 records that deliberately, and
 *       names this issue as the consumer. Until it exists, a category is only ever set by the user
 *       tapping a chip, so the taxonomy 4.1 seeded describes almost none of the ledger and every
 *       engine built on categories (budgets, 50/30/20, Safe-to-Spend) is reading a mostly-empty
 *       column.
 *
 *       §8.1 states Stage 1 as a precedence chain rather than a matcher, and the order is the whole
 *       design: `user rule > learned model (≥ 0.85) > knowledge base > "Uncategorised" prompt`. The
 *       user's own past corrections outrank anything shipped, because a merchant string that means
 *       one thing to this app's authors may mean another to the person spending the money — and
 *       because a suggestion that keeps overruling a correction is worse than no suggestion at all.
 *
 *       **This engine builds tiers (a) and (b).** Tier (c), the on-device TF-IDF + logistic model,
 *       is deferred with its reasons in ADR-0015; the interface takes no position on it beyond
 *       leaving the confidence a suggestion carries comparable across tiers.
 *
 *       The asymmetry that sets the threshold: a merchant this engine declines to classify costs the
 *       user one tap on a chip row that is already on screen. A merchant it classifies *wrongly*
 *       files money under a category the user never chose, quietly, into a budget they will later
 *       read as fact — and, unlike a missed suggestion, nothing about the screen invites them to
 *       look. So every ambiguous case resolves to "no suggestion" (P-03).
 * What: one method, from a merchant string to a category or to nothing.
 * Result: what the add-transaction screen pre-selects and explains; **never a saved value** (P-07) —
 *       the user can change it, and doing so is what teaches tier (a).
 * Changelog: 2026-08-10 — Created for issue 4.2.
 *
 * Pure Kotlin (ARC-002), so the precedence chain and the accuracy gate are provable on the JVM.
 */
interface ClassificationEngine {
    /**
     * Decides what category a merchant suggests.
     *
     * Why:    `Result` matching every other engine in this codebase, though the realistic outcome
     *         for an unfamiliar merchant is `Ok(null)` — **"I do not know" is the ordinary answer,
     *         not a failure**, and modelling it as an error would make the common path an exception
     *         path. The `Err` branch is reserved for a malformed rule set, which is a bug in the
     *         caller or in the knowledge base rather than anything about the merchant.
     * What:   tier (a) — what the user has filed under this merchant before — then tier (b), the
     *         knowledge base. The first tier to clear [ClassificationRules.minConfidenceBps] wins;
     *         a tie between two knowledge-base rules naming different categories wins nothing.
     * Result: `Ok(suggestion)` naming a category that is live on this profile, `Ok(null)` to defer
     *         to the user, `Err` for a caller bug.
     * Input:  [input] — the merchant, the profile's live categories, its correction history, the
     *         instant to stamp and the rules to apply.
     * Output: `Result<CategorySuggestion?, AppError>`.
     */
    fun suggest(input: ClassificationInput): Result<CategorySuggestion?, AppError>
}

/**
 * Everything the classifier needs, as one value (issue 4.2).
 *
 * Why: [nowUtcMillis] is **passed in, never read**: TIM-001 bans wall-clock reads in domain code and
 *      `CfoWallClockInDomain` fails the build on one. That is also what makes every case in the eval
 *      set reproducible (P-08) — the same merchant classified today and in a year gives byte-identical
 *      output, provenance included.
 *
 *      [categories] is the profile's **live** taxonomy rather than a name the engine invents, and
 *      that is what makes the contract "a suggestion always names a row that exists". The user may
 *      have deleted Dining; §8.1 does not get to re-create it, and neither does this engine (P-07).
 * Result: the argument to [ClassificationEngine.suggest].
 * Changelog: 2026-08-10 — Created for issue 4.2.
 *
 * Input:  [merchant] — the payee as captured, in whatever case and with whatever descriptor noise the
 *         bank or the user typed (`SWIGGY*ORDER 7781`); normalised here, so no caller has to;
 *         [categories] — the live categories of the active profile, in any order; [history] — how
 *         the user has categorised **this same normalised merchant** before, aggregated by the
 *         caller, empty when they never have; [nowUtcMillis] — the instant stamped into provenance
 *         (TIM-001); [rules] — the knowledge base's rows and thresholds, injected so a test can move
 *         one (ADR-0005, ADR-0015).
 * Output: an immutable value.
 */
data class ClassificationInput(
    val merchant: String,
    val categories: List<Category>,
    val nowUtcMillis: Long,
    val history: List<MerchantHistoryRow> = emptyList(),
    val rules: ClassificationRules = ClassificationRules(),
)

/**
 * How often the user filed this merchant under one category (issue 4.2; SRS §8.1(a)).
 *
 * Why:  §8.1(a) calls tier one "exact/normalised merchant-rule lookup from the user's **correction
 *       history**", and this codebase already stores that history — every categorised transaction is
 *       a correction the user made, so a separate `user_merchant_rule` table would be a second copy
 *       of a fact the ledger already holds, able to disagree with it. The aggregation is the
 *       caller's because only the caller may touch a DAO (ARC-005); the *decision* is the engine's.
 * What: one category and the number of times it was chosen for this merchant.
 * Result: the element type of [ClassificationInput.history].
 * Changelog: 2026-08-10 — Created for issue 4.2.
 *
 * Input:  [categoryId] — the category the user chose; [count] — how many live transactions with this
 *         merchant carry it, always at least one.
 * Output: an immutable value.
 */
data class MerchantHistoryRow(
    val categoryId: String,
    val count: Int,
) {
    init {
        require(categoryId.isNotBlank()) { "A history row must name a category" }
        // A zero-count row is not "the user never did this" — it is a caller that grouped wrongly,
        // and it would drag the confidence denominator up while contributing no evidence, quietly
        // pushing a good suggestion below the threshold.
        require(count > 0) { "A history row counts transactions that exist, was $count" }
    }
}

/**
 * What Stage 1 proposes for one merchant (issue 4.2; SRS §8.1, P-02).
 *
 * Why: there is no `confidence` or `ruleId` field here, and that is on purpose — both live in
 *      [provenance], which is the one shape every engine result in this codebase carries
 *      (AI-ARC-003). A screen that wants to say *why* reads `provenance.evidence`; a screen that
 *      wants to say *how sure* reads `provenance.confidenceBps`. Duplicating either here would let
 *      the displayed reason and the stored reason drift apart.
 *
 *      Nor is there a category **name**. The caller already holds [ClassificationInput.categories]
 *      and can look the id up; carrying a copy would be a second answer to "what is this category
 *      called" that a rename could falsify.
 * Result: what the add-transaction screen pre-selects, with the rule it can cite beside it.
 * Changelog: 2026-08-10 — Created for issue 4.2.
 *
 * Input:  [categoryId] — a category that was live in [ClassificationInput.categories];
 *         [provenance] — engine id and version, the instant from the input, the rule that fired, and
 *         the confidence in basis points (MNY-002), always at or above the configured floor.
 * Output: an immutable value.
 */
data class CategorySuggestion(
    val categoryId: String,
    val provenance: EngineProvenance,
) {
    init {
        require(categoryId.isNotBlank()) { "A suggestion must name a category (P-02)" }
        require(provenance.confidenceBps != null) {
            "A Stage-1 suggestion states how sure it is, or it defers instead (SRS §8.1)"
        }
    }
}

/**
 * Reduces a merchant string to the form both tiers compare (issue 4.2).
 *
 * Why:    **public, and public for one reason**: the caller has to look the correction history up by
 *         merchant, and if it normalised differently from this engine the two tiers would disagree
 *         about what "the same merchant" means — the user's own correction would stop being found
 *         while the knowledge base kept matching. One function, used by both sides, is the only way
 *         that cannot happen. `RoomTransactionRepository.suggestCategory` calls it to build its
 *         query argument.
 *
 *         Case and surrounding whitespace only. It deliberately does **not** strip the `*ORDER 7781`
 *         noise a card descriptor carries: that noise is what distinguishes one merchant from
 *         another for some banks, and the token matching in [ClassificationEngine] already sees past
 *         it without having to guess which part of the string is the name.
 * Result: the merchant, trimmed and lower-cased; an empty string for a blank input, which both tiers
 *         treat as "nothing to classify".
 * Input:  [raw] — the merchant as captured. Output: [String].
 * Changelog: 2026-08-10 — Created for issue 4.2.
 */
fun normaliseMerchant(raw: String): String = raw.trim().lowercase()

/**
 * Builds the engine for the DI graph (ARC-003).
 * Why:    the implementation is `internal`, so `:app`'s Hilt module cannot name it — the seam every
 *         engine in this codebase uses, kept identical on purpose.
 * Result: a [ClassificationEngine]. Input: none. Output: the engine.
 * Changelog: 2026-08-10 — Created for issue 4.2.
 */
object ClassificationEngineFactory {
    /** Result: the production engine. Input: none. Output: [ClassificationEngine]. */
    fun create(): ClassificationEngine = DefaultClassificationEngine()
}
