package com.aicfo.domain.engines.nature

import com.aicfo.core.common.AppError
import com.aicfo.core.common.Result
import com.aicfo.core.model.AccountType
import com.aicfo.core.model.CategoryNature
import com.aicfo.core.model.EngineProvenance
import com.aicfo.core.model.Money
import com.aicfo.core.model.TransactionType

/**
 * Answers "what did this money become?" for one transaction (issue 4.3; SRS §8.3, AI-CLS-N).
 *
 * Why:  §8.3 calls nature "the axis the advice layer is built on" — the 50/30/20 rings, the
 *       true-spend metric, the emergency-fund essentials and the Purchase Advisor's scrutiny ladder
 *       all branch on it. Issue 4.1 gave every *category* a nature and issue 4.2 gave every
 *       transaction a category, and the tempting conclusion is that nature is therefore already
 *       answered: read `category.nature` and stop.
 *
 *       **That is step 5 of six, and the five above it exist because it is wrong on its own.** An
 *       EMI paid into a loan account is debt service because of the *account*, whatever category it
 *       carries. A transfer into a gold account is a conversion, not spending, even when someone has
 *       tagged it Shopping. Money the user has already told this app is a Want stays a Want no
 *       matter what its category says. Classifying by category alone gets all three wrong, and gets
 *       them wrong in the direction that **inflates true spend** — which is the number the whole
 *       advice layer is calibrated against.
 * What: one method, §8.3.1's decision order, first match wins.
 * Result: a nature for every transaction, with the rule that decided it. Nothing here writes; the
 *       user can override any verdict and the override is what gets stored (P-07).
 * Changelog: 2026-08-10 — Created for issue 4.3.
 *
 * **This engine always answers**, which is the opposite of `ClassificationEngine`'s contract and
 * deliberately so. §8.3 says *every* transaction gets a nature; a hole in the taxonomy is not
 * something the 50/30/20 rings can render, and "uncategorised" is already a category rather than an
 * absence. Uncertainty is expressed as **low confidence**, not as `null` — a verdict below
 * [NatureRules.minConfidenceBps] is one the review flow should ask about, not one that is missing.
 *
 * Pure Kotlin (ARC-002), so the whole decision order is provable on the JVM.
 */
interface NatureEngine {
    /**
     * Applies §8.3.1's decision order to one transaction.
     *
     * Why:    `Result` matching every other engine in this codebase, though a well-formed input
     *         cannot fail — the `Err` branch is reserved for a malformed rule set, which is a bug in
     *         the caller or in the knowledge base rather than anything about the transaction.
     * What:   loan account → investment transfer → asset transfer → the user's own past decision →
     *         the category's nature → the rulebook fallback, then step 6 as a modifier on top.
     * Result: `Ok(verdict)` always, for a valid rule set.
     * Input:  [input] — the transaction's shape, its history and the rules to apply.
     * Output: `Result<NatureVerdict, AppError>`.
     */
    fun classify(input: NatureInput): Result<NatureVerdict, AppError>
}

/**
 * Everything the decision order needs about one transaction (issue 4.3).
 *
 * Why: this is a *shape*, not a `Transaction`. The engine is pure (ARC-002) and takes only the five
 *      facts §8.3.1 branches on, which means it cannot quietly start deciding on a note, a tag or a
 *      date — the same narrowing `RecurringCandidateRow` applies to the recurring detector, applied
 *      one layer up. Assembling it is the repository's job, because every one of these facts is a
 *      join it is the only class allowed to make (ARC-005).
 *
 *      [nowUtcMillis] is **passed in, never read**: TIM-001 bans wall-clock reads in domain code and
 *      `CfoWallClockInDomain` fails the build on one. It is also what makes the golden file
 *      reproducible (P-08).
 * Result: the argument to [NatureEngine.classify].
 * Changelog: 2026-08-10 — Created for issue 4.3.
 *
 * Input:  [accountType] — the type of the account this row moved in (step 1); [counterpartAccountType]
 *         — for a transfer leg, the type of the account on the other side, `null` for everything else
 *         (steps 1–3); [type] — expense, income or which leg of a transfer; [amount] — signed paise
 *         (MNY-001), read only by step 6; [merchantHistory] — how the user has *overridden* this same
 *         merchant before, aggregated by the caller, empty when they never have (step 4);
 *         [categoryNature] — the nature of the transaction's category, `null` when it has none
 *         (step 5); [categoryMedian] — the median magnitude of this category's past transactions,
 *         `null` when there is too little history for a median to mean anything (step 6);
 *         [nowUtcMillis] — the instant stamped into provenance (TIM-001); [rules] — the knowledge
 *         base's thresholds, injected so a test can move one (ADR-0016).
 * Output: an immutable value.
 */
data class NatureInput(
    val accountType: AccountType,
    val type: TransactionType,
    val amount: Money,
    val nowUtcMillis: Long,
    val counterpartAccountType: AccountType? = null,
    val merchantHistory: List<NatureHistoryRow> = emptyList(),
    val categoryNature: CategoryNature? = null,
    val categoryMedian: Money? = null,
    val rules: NatureRules = NatureRules(),
)

/**
 * How often the user has overridden this merchant to one nature (issue 4.3; §8.3.1 step 4).
 *
 * Why:  §8.3 says nature is "auto-assigned, user-correctable, **learned**", and the learning source
 *       is the corrections themselves — the `transactions.nature` column holds nothing but overrides,
 *       so counting them is counting decisions the user actually made. This is the same shape issue
 *       4.2 used for category history, kept identical on purpose: the two tiers behave the same way
 *       about an inconsistent history, and one reader can learn the rule once.
 * What: one nature and the number of times it was chosen for this merchant.
 * Result: the element type of [NatureInput.merchantHistory].
 * Changelog: 2026-08-10 — Created for issue 4.3.
 *
 * Input:  [nature] — what the user overrode to; [count] — how many live transactions with this
 *         merchant carry it, always at least one.
 * Output: an immutable value.
 */
data class NatureHistoryRow(
    val nature: CategoryNature,
    val count: Int,
) {
    init {
        // A zero-count row is not "the user never did this" — it is a caller that grouped wrongly,
        // and it would drag the confidence denominator up while contributing no evidence, quietly
        // pushing a settled decision below the floor.
        require(count > 0) { "A history row counts transactions that exist, was $count" }
    }
}

/**
 * What one transaction's money became, and which rule said so (issue 4.3; §8.3, P-02).
 *
 * Why: the confidence and the cited rule live in [provenance] rather than beside [nature], because
 *      that is the one shape every engine result in this codebase carries (AI-ARC-003) and
 *      duplicating either would let the displayed reason and the stored reason drift apart.
 *
 *      [isFlagged] is the exception, and it is derived rather than stored: §8.3's
 *      `low_confidence_handling` says an uncertain nature is "flagged for one-tap review, never
 *      blocking save", and every caller that renders a verdict needs that boolean. Computing it here
 *      from the rules that produced the verdict means no screen can invent its own threshold.
 * Result: what the detail sheet renders and what the monthly breakdown folds.
 * Changelog: 2026-08-10 — Created for issue 4.3.
 *
 * Input:  [nature] — §8.3's five natures; [provenance] — engine id and version, the caller's instant,
 *         the `CLS-NAT-*` row(s) that fired, and the confidence in basis points (MNY-002);
 *         [rules] — carried so [isFlagged] compares against the floor that was actually applied.
 * Output: an immutable value.
 */
data class NatureVerdict(
    val nature: CategoryNature,
    val provenance: EngineProvenance,
    val rules: NatureRules = NatureRules(),
) {
    init {
        require(provenance.confidenceBps != null) {
            "A nature verdict states how sure it is — §8.3 flags the uncertain ones rather than hiding them"
        }
        require(provenance.evidence.isNotEmpty()) {
            "A nature verdict names the CLS-NAT-* step that decided it (P-02, AI-ARC-006)"
        }
    }

    /**
     * Whether this verdict should be offered for review (§8.3 `low_confidence_handling`).
     * Result: `true` below [NatureRules.minConfidenceBps] — a fallback with nothing to go on, or a
     *         step-6 amount far above the category's normal. **Never blocks a save**; it is a label.
     * Input:  none. Output: [Boolean].
     */
    val isFlagged: Boolean get() = (provenance.confidenceBps ?: 0) < rules.minConfidenceBps
}

/**
 * Builds the engine for the DI graph (ARC-003).
 * Why:    the implementation is `internal`, so `:app`'s Hilt module cannot name it — the seam every
 *         engine in this codebase uses, kept identical on purpose.
 * Result: a [NatureEngine]. Input: none. Output: the engine.
 * Changelog: 2026-08-10 — Created for issue 4.3.
 */
object NatureEngineFactory {
    /** Result: the production engine. Input: none. Output: [NatureEngine]. */
    fun create(): NatureEngine = DefaultNatureEngine()
}
