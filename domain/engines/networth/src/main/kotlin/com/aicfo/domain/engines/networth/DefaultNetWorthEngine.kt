package com.aicfo.domain.engines.networth

import com.aicfo.core.common.AppError
import com.aicfo.core.common.Result
import com.aicfo.core.common.runCatchingToResult
import com.aicfo.core.model.EngineProvenance
import com.aicfo.core.model.Money

/**
 * The production [NetWorthEngine] (issue 2.6; FR-ACC-005, MNY-001, P-02, P-03, P-08).
 *
 * Why:  the whole engine turns on one judgement — **an account's side is decided by its type, never
 *       by the sign of its balance**. The tempting shortcut is to call every negative balance a
 *       liability, which reads as sensible and is wrong twice over: an overdrawn bank account would
 *       be reported as a debt the user had taken on, and a credit card paid past zero would be
 *       reported as savings. Both are real situations, neither is what the sign says, and the net
 *       worth is *identical* either way — so the error would never show up in the headline figure,
 *       only in the two subtotals the user checks it against.
 * What: partition by [com.aicfo.core.model.AccountType.isLiability], sum each side, subtract.
 * Result: the figure the dashboard shows and `net_worth_snapshot` stores.
 * Changelog: 2026-08-01 — Created for issue 2.6.
 *
 * `internal` per ARC-003 — constructed only by [NetWorthEngineFactory].
 *
 * Every amount is `Long` paise added with `Money`'s overflow-checked arithmetic: there is not a
 * `Double` in this file, and a total too large to represent throws rather than wrapping into a
 * negative fortune (MNY-001). There is no division, so there is nothing to round.
 */
internal class DefaultNetWorthEngine : NetWorthEngine {
    override fun compute(input: NetWorthInput): Result<NetWorthResult, AppError> =
        runCatchingToResult {
            val (liabilityRows, assetRows) = input.balances.partition { it.type.isLiability }

            val assets = assetRows.total()
            // Negated: the stored sign says "you owe", and a screen should be able to render this as
            // "₹18,000" without re-deciding what the minus meant. The subtraction below puts it back.
            val liabilities = Money.ZERO - liabilityRows.total()

            NetWorthResult(
                asOfIsoDate = input.asOfIsoDate,
                assets = assets,
                liabilities = liabilities,
                netWorth = assets - liabilities,
                provenance =
                    EngineProvenance(
                        engineId = ENGINE_ID,
                        engineVersion = ENGINE_VERSION,
                        computedAtUtcMillis = input.nowUtcMillis,
                        // Empty on purpose: this is arithmetic, not a rulebook threshold, so there
                        // is no rule to cite (CLAUDE.md §6 has nothing to bite on here).
                        evidence = emptyList(),
                        inputWindow = input.asOfIsoDate,
                    ),
            )
        }

    private companion object {
        /** Stable identifier stored with every snapshot (AI-ARC-003). */
        const val ENGINE_ID = "net-worth"

        /**
         * Bump whenever the formula changes (AI-ARC-006).
         *
         * Stored on every `net_worth_snapshot` row, so a figure computed today can still be
         * explained after the engine is rewritten — which is the whole reason the column exists.
         */
        const val ENGINE_VERSION = "1.0"
    }
}

/**
 * Sums a side of the balance sheet.
 * Why:    `fold` with [Money.plus] rather than `sumOf { it.balance.minor }`, so the addition goes
 *         through `Math.addExact` and an impossible total fails loudly instead of wrapping
 *         (MNY-001). An empty list totals zero, which is a real answer for a user with no accounts
 *         on that side — not a missing one.
 * Result: the exact total in paise.
 * Input:  the receiver — the rows on one side. Output: [Money].
 * Changelog: 2026-08-01 — Created for issue 2.6.
 */
private fun List<AccountBalance>.total(): Money = fold(Money.ZERO) { running, row -> running + row.balance }
