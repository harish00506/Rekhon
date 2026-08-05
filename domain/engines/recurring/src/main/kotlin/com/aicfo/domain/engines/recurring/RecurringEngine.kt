package com.aicfo.domain.engines.recurring

import com.aicfo.core.common.AppError
import com.aicfo.core.common.Result
import com.aicfo.core.model.EngineProvenance
import com.aicfo.core.model.Money

/**
 * Spots the bills and subscriptions already sitting in the ledger (issue 3.7; FR-TXN-006).
 *
 * Why:  FR-TXN-006 is a MUST — *"Recurring detection MUST propose recurring series when ≥ 2 similar
 *       transactions match on merchant/amount/cadence; user confirms to create a Recurring Rule."*
 *       A user who has paid the same rent on the same day for six months has told the app
 *       everything it needs to know; until this engine existed the app had the rows and said
 *       nothing. P-03 puts the pattern-finding in deterministic math rather than in a model, and
 *       P-08 makes it reproducible: same rows in, same proposals out, every time.
 * What: one method, from a flat list of transactions to the series it can defend.
 * Result: proposals the user confirms or rejects — **it proposes only** (P-07). Nothing here
 *       creates a rule, posts a transaction or moves a rupee; the repository writes only what the
 *       user has said yes to.
 * Changelog: 2026-08-05 — Created for issue 3.7.
 *
 * Pure Kotlin (ARC-002), so the whole detector is provable on the JVM.
 */
interface RecurringEngine {
    /**
     * Finds every series in one profile's transactions.
     * Why:    a `Result` rather than a throw, matching every other engine. The realistic failure is
     *         a malformed date string reaching a pure-Kotlin engine that cannot itself validate the
     *         database — `java.time` throws on one, and an engine that let that escape would crash
     *         a screen rather than degrade it (§21.6: no exceptions across layer boundaries).
     * What:   groups by normalised merchant, classifies each group's cadence from its median gap,
     *         and keeps the groups whose gaps and amounts are all within the rulebook's tolerances.
     * Result: `Ok(series)` — including `Ok(emptyList())`, which is the normal answer for a new
     *         profile and is not an error — or `Err(AppError.Unexpected)` when a date will not
     *         parse. Series are ordered by merchant so the proposal list is stable between runs
     *         (P-08: a list that reshuffles on every read is not deterministic output).
     * Input:  [input] — the candidate rows, the thresholds, and the caller's clock reading.
     * Output: `Result<List<RecurringSeries>, AppError>`.
     */
    fun detect(input: RecurringInput): Result<List<RecurringSeries>, AppError>
}

/**
 * One transaction the detector may fold into a series (issue 3.7).
 *
 * Why:    deliberately **not** `com.aicfo.core.model.Transaction`. Four fields state exactly what
 *         the detector reads: it does not know or care about accounts, categories, splits,
 *         transfers or provenance, and a projection this narrow is the argument `AccountBalance`
 *         already makes in the net-worth engine. It also keeps the engine honest — it cannot start
 *         matching on a field it was never given.
 * Result: the element type of [RecurringInput.candidates].
 * Changelog: 2026-08-05 — Created for issue 3.7.
 *
 * Input:  [transactionId] — carried so a proposal can point at the very rows that produced it,
 *         which is the evidence P-02 requires; [merchant] — the payee as typed, normalised by the
 *         engine rather than the caller so the normalisation has one definition; [amount] —
 *         **signed** paise (MNY-001), as stored; [bookedOn] — ISO `yyyy-MM-dd` in the profile zone
 *         (TIM-002), never a midnight timestamp.
 * Output: an immutable value.
 */
data class RecurringCandidate(
    val transactionId: String,
    val merchant: String,
    val amount: Money,
    val bookedOn: String,
)

/**
 * Everything the engine needs, as one value.
 *
 * Why: [nowUtcMillis] is **passed in, never read**: TIM-001 bans wall-clock reads in domain code
 *         and `CfoWallClockInDomain` fails the build on one — which is also what makes every case
 *         in the test suite reproducible (P-08).
 * Result: the argument to [RecurringEngine.detect].
 * Changelog: 2026-08-05 — Created for issue 3.7.
 *
 * Input:  [candidates] — the rows to search, in any order; the caller has already excluded
 *         soft-deleted rows, transfers and scheduled (not-yet-posted) rows, because *which* rows
 *         count is a storage question and this engine's job is only the pattern;
 *         [rules] — the rulebook thresholds, injected so a test can move one (ADR-0005);
 *         [nowUtcMillis] — the instant stamped into provenance (TIM-001).
 * Output: an immutable value.
 */
data class RecurringInput(
    val candidates: List<RecurringCandidate>,
    val rules: RecurringRules = RecurringRules(),
    val nowUtcMillis: Long,
)

/**
 * One proposal: a merchant the ledger says repeats (AI-ARC-003).
 *
 * Why:    every field here exists to be *shown*, because P-02 says a recommendation shows its
 *         inputs and the rule that fired. "We think you have a Netflix subscription" is a verdict
 *         the user has to take on trust; "₹649 to NETFLIX, monthly, on 03 Jun, 03 Jul and 02 Aug"
 *         is one they can check against their own memory in a second. That is why [occurrences]
 *         holds transaction ids rather than a count — the card can link to the rows themselves.
 * Result: what the transactions list proposes and, on confirmation, what a `recurring_rule` row is
 *         built from.
 * Changelog: 2026-08-05 — Created for issue 3.7.
 *
 * Input:  [merchant] — the payee **as the user typed it** on the most recent occurrence, not the
 *         lower-cased key the engine matched on: the key is an implementation detail and showing it
 *         would render `NETFLIX` as `netflix`; [cadence] — how often it repeats; [medianAmount] —
 *         the series' representative amount, signed paise (MNY-001), the median rather than the
 *         mean so one unusual month does not move the figure the user is asked to confirm;
 *         [nextDueIsoDate] — last occurrence + one period, ISO `yyyy-MM-dd` (TIM-002);
 *         [occurrences] — the rows that matched, oldest first; [provenance] — engine id, version,
 *         instant, the rule cited, the date window read, and the confidence.
 * Output: an immutable value.
 */
data class RecurringSeries(
    val merchant: String,
    val cadence: Cadence,
    val medianAmount: Money,
    val nextDueIsoDate: String,
    val occurrences: List<RecurringOccurrence>,
    val provenance: EngineProvenance,
)

/**
 * One row that matched, as the proposal's evidence (P-02).
 *
 * Why:    an id **and** a date, because the two serve different readers. The date is what the card
 *         shows — "3 Jun, 3 Jul, 3 Aug" is a claim the user can check against their own memory in a
 *         second, where "3 payments" is one they have to take on trust. The id is what a future
 *         drill-down navigates to. Carrying both here is also what keeps the repository free of a
 *         parallel DTO that existed only to re-attach the dates the engine had already read.
 * Result: the element type of [RecurringSeries.occurrences].
 * Changelog: 2026-08-05 — Created for issue 3.7.
 *
 * Input:  [transactionId] — the row's id; [bookedOn] — ISO `yyyy-MM-dd` (TIM-002).
 * Output: an immutable value.
 */
data class RecurringOccurrence(
    val transactionId: String,
    val bookedOn: String,
)

/**
 * Builds the engine for the DI graph (ARC-003).
 * Why:    ARC-003 keeps engine implementations `internal`, so `:app`'s Hilt module cannot name one.
 *         The same seam [com.aicfo.domain.engines.recurring.RecurringEngine]'s siblings already
 *         use, kept deliberately identical so the codebase has one pattern rather than three.
 * Result: a [RecurringEngine]. Input: none. Output: the engine.
 * Changelog: 2026-08-05 — Created for issue 3.7.
 */
object RecurringEngineFactory {
    /** Result: the production engine. Input: none. Output: [RecurringEngine]. */
    fun create(): RecurringEngine = DefaultRecurringEngine()
}
