package com.aicfo.domain.engines.networth

import com.aicfo.core.common.AppError
import com.aicfo.core.common.Result
import com.aicfo.core.model.AccountType
import com.aicfo.core.model.EngineProvenance
import com.aicfo.core.model.Money

/**
 * Adds the user's accounts up: assets − liabilities (issue 2.6; FR-ACC-005).
 *
 * Why:  FR-ACC-005 is a MUST — *"Net worth MUST equal assets − liabilities"* — and P-03 says every
 *       number comes from deterministic math rather than from a model. This is that math, and it is
 *       deliberately the only place it lives: the dashboard shows what this returns, the snapshot
 *       table stores what this returns, and neither adds up a rupee of its own. Pure Kotlin
 *       (ARC-002), so the whole thing is provable on the JVM.
 * What: one method, from a list of balances to the two subtotals and their difference, with the
 *       provenance that says which engine and version produced them.
 * Result: the app's headline figure has a derivation anyone can check.
 * Changelog: 2026-08-01 — Created for issue 2.6.
 *
 * **It is arithmetic, not a rule.** There is no threshold here, so [EngineProvenance.evidence] is
 * empty and CLAUDE.md §6's "never hardcode a financial number" does not apply — there is no number
 * to hardcode. What provenance carries instead is the engine version, so a snapshot stored today
 * stays reproducible after the formula changes (AI-ARC-006).
 *
 * **It advises nothing and writes nothing (P-07).** The caller decides what to do with the figure.
 */
interface NetWorthEngine {
    /**
     * Computes net worth for one set of balances.
     * Why:    a `Result` rather than a throw, matching every other engine — though the only way to
     *         fail here is an overflow so large it cannot be a real portfolio, which `Money`'s
     *         checked arithmetic turns into an error rather than a wrapped negative fortune
     *         (MNY-001).
     * What:   partitions by [AccountType.isLiability], sums each side, and subtracts.
     * Result: `Ok(result)` — including for an empty input, which is a legitimate zero rather than an
     *         error — or `Err(AppError.Unexpected)` when the totals cannot be represented.
     * Input:  [input] — the balances plus the caller's clock reading.
     * Output: `Result<NetWorthResult, AppError>`.
     */
    fun compute(input: NetWorthInput): Result<NetWorthResult, AppError>
}

/**
 * One account's contribution to net worth (issue 2.6).
 *
 * Why:    deliberately **not** `com.aicfo.core.model.Account`. The backfill feeds this engine
 *         *historical* balances — what an account held on some past day — and an `Account` whose
 *         `balance` field holds a past figure would be a lie in a type the rest of the app trusts.
 *         Three fields also state exactly what the engine reads: it does not know or care about an
 *         account's name, institution or opening balance.
 * Result: the element type of [NetWorthInput.balances].
 * Changelog: 2026-08-01 — Created for issue 2.6.
 *
 * Input:  [accountId] — carried so a future drill-down can name which account contributed what
 *         (P-02); [type] — decides which side it lands on; [balance] — **signed** paise (MNY-001),
 *         negative for a card the user owes on.
 * Output: an immutable value.
 */
data class AccountBalance(
    val accountId: String,
    val type: AccountType,
    val balance: Money,
)

/**
 * Everything the engine needs, as one value.
 *
 * Why: [nowUtcMillis] is **passed in, never read**: TIM-001 bans wall-clock reads in domain code
 *         and `CfoWallClockInDomain` fails the build on one — which is also what makes every case in
 *         the test suite reproducible (P-08).
 * Result: the argument to [NetWorthEngine.compute].
 * Changelog: 2026-08-01 — Created for issue 2.6.
 *
 * Input:  [balances] — one entry per account counted; the caller has already excluded archived,
 *         soft-deleted and opted-out accounts, because *which* accounts count is a storage question
 *         (FR-ACC-007) and this engine's job is only the arithmetic; [asOfIsoDate] — the day this
 *         figure describes, ISO `yyyy-MM-dd` in the profile zone (TIM-002); [nowUtcMillis] — the
 *         instant stamped into provenance (TIM-001).
 * Output: an immutable value.
 */
data class NetWorthInput(
    val balances: List<AccountBalance>,
    val asOfIsoDate: String,
    val nowUtcMillis: Long,
)

/**
 * What the engine derived (AI-ARC-003).
 *
 * Why:    three figures rather than one, because P-02 requires the working to be visible. "Your net
 *         worth is ₹3,12,000" is a number the user has to take on trust; "assets ₹3,30,000, you owe
 *         ₹18,000" is one they can check against their own accounts.
 * Result: what the dashboard renders and `net_worth_snapshot` stores.
 * Changelog: 2026-08-01 — Created for issue 2.6.
 *
 * Input:  [asOfIsoDate] — echoed from the input so the result is self-describing: a snapshot row
 *         should not depend on the caller remembering which day it asked about; [assets] — the sum
 *         of asset-type balances, signed, so an overdrawn account correctly reduces it;
 *         [liabilities] — a **positive magnitude**, negated from the stored signs, so a screen can
 *         say "you owe ₹18,000" without re-deciding what the minus meant; [netWorth] — `assets −
 *         liabilities`; [provenance] — engine id, version and the instant.
 * Output: an immutable value.
 */
data class NetWorthResult(
    val asOfIsoDate: String,
    val assets: Money,
    val liabilities: Money,
    val netWorth: Money,
    val provenance: EngineProvenance,
)

/**
 * Builds the engine for the DI graph (ARC-003).
 * Why:    ARC-003 keeps engine implementations `internal`, so `:app`'s Hilt module cannot name one.
 *         The same seam `QuickSetupEngineFactory` and `RepositoryFactory` already use, kept
 *         deliberately identical so the codebase has one pattern rather than three.
 * Result: a [NetWorthEngine]. Input: none. Output: the engine.
 * Changelog: 2026-08-01 — Created for issue 2.6.
 */
object NetWorthEngineFactory {
    /** Result: the production engine. Input: none. Output: [NetWorthEngine]. */
    fun create(): NetWorthEngine = DefaultNetWorthEngine()
}
