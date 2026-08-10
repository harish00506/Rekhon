package com.aicfo.domain.engines.nature

import com.aicfo.core.model.CategoryNature
import com.aicfo.core.model.Money
import com.aicfo.core.model.TransactionType

/**
 * One classified row, as the monthly fold needs it (issue 4.3; SRS §8.3).
 *
 * Why:  three fields, not a `Transaction` and not a [NatureVerdict]. The fold branches on the type
 *       and sums the amount, and a narrower input means it *cannot* start branching on a date, a
 *       category or a confidence — the same narrowing [NatureInput] applies to the classifier.
 * Result: the element type of [natureBreakdown].
 * Changelog: 2026-08-10 — Created for issue 4.3.
 *
 * Input:  [type] — expense, income, or which leg of a transfer; [amount] — signed paise (MNY-001),
 *         summed as a magnitude; [nature] — what [NatureEngine] decided this row's money became.
 * Output: an immutable value.
 */
data class NatureContribution(
    val type: TransactionType,
    val amount: Money,
    val nature: CategoryNature,
)

/**
 * A month's money, split by what it became (issue 4.3; SRS §8.3).
 *
 * Why:  §8.3's whole point is that "spent ₹60,000 this month" is a useless sentence if ₹25,000 of it
 *       went into an SIP and a gold purchase. The 50/30/20 rings, the savings rate and the
 *       emergency-fund target are all built on separating the three, and this is the separation.
 * Result: what the dashboard renders and what §14's health score will read.
 * Changelog: 2026-08-10 — Created for issue 4.3.
 *
 * **[trueSpend] is understated by exactly the interest on any EMI, and that is a stated defect
 * rather than a hidden one.** §8.3's formula is `NEED + WANT + interest/fees`; splitting an EMI into
 * principal and interest needs the amortisation row §8.3.1 step 1 names, and this build has no such
 * table (ADR-0016). So [liabilities] is reported in full and contributes **nothing** to [trueSpend],
 * which is the conservative direction — the app understates what the user consumed rather than
 * counting their debt principal as consumption. The screen that renders it says so.
 *
 * Input:  [needs], [wants] — §8.3's true-spend terms; [invested], [assets] — the conversions, shown
 *         separately because the money is still the user's; [liabilities] — debt service, reported
 *         and excluded from [trueSpend]; [trueSpend] — the sum of the true-spend natures. All are
 *         positive magnitudes in paise (MNY-001).
 * Output: an immutable value.
 */
data class NatureBreakdown(
    val needs: Money = Money.ZERO,
    val wants: Money = Money.ZERO,
    val invested: Money = Money.ZERO,
    val assets: Money = Money.ZERO,
    val liabilities: Money = Money.ZERO,
) {
    /**
     * §8.3's `trueSpend`, minus the interest term this build cannot compute.
     * Result: [needs] + [wants]. Input: none. Output: [Money].
     */
    val trueSpend: Money get() = needs + wants

    /**
     * Whether there is anything at all to render.
     * Why:    the dashboard must show an empty state rather than five zeroes — a bar of zeroes is
     *         indistinguishable from a real month of nothing, and showing one to a user with no
     *         transactions is a number the app made up (P-03), the same rule `SpendSplit` follows.
     * Result: `true` when every total is zero. Input: none. Output: [Boolean].
     */
    val isEmpty: Boolean
        get() = listOf(needs, wants, invested, assets, liabilities).all { it == Money.ZERO }
}

/**
 * Folds a month of classified rows into §8.3's split (issue 4.3).
 *
 * Why:    a free function rather than a second method on [NatureEngine], because it is arithmetic
 *         over already-classified rows and has no decision in it — putting it behind the interface
 *         would suggest an implementation could sum differently, which is exactly what MNY-001
 *         exists to prevent.
 *
 *         **Two exclusions carry all the risk, and both are about counting a rupee twice.**
 *
 *         *`TRANSFER_IN` never counts.* Every conversion is two legs — money leaving the bank and
 *         arriving in the investment account — and both legs classify as INVESTMENT, correctly,
 *         because both describe the same becoming. Summing both would double every SIP.
 *
 *         *A `TRANSFER_OUT` counts only when the accounts decided it.* Moving ₹10,000 between two
 *         bank accounts is not spending and not a conversion; nothing became anything. The engine
 *         still answers with a nature for it, because §8.3 requires one and the detail sheet has to
 *         render something — but that answer came from the fallback, and folding it in would add a
 *         self-transfer to the month's Wants. So the fold takes a transfer leg only when its nature
 *         is one an account-type step could have produced.
 *
 *         `INCOME` and `ADJUSTMENT` are excluded outright: §8.3 asks what money *became*, and neither
 *         is money leaving.
 * Result: the totals, as positive magnitudes. An empty list gives an all-zero [NatureBreakdown],
 *         which [NatureBreakdown.isEmpty] reports rather than the screen guessing.
 * Input:  [rows] — the month's classified transactions, in any order; [rules] — for which natures
 *         count as conversions.
 * Output: [NatureBreakdown].
 * Changelog: 2026-08-10 — Created for issue 4.3.
 */
fun natureBreakdown(
    rows: List<NatureContribution>,
    rules: NatureRules = NatureRules(),
): NatureBreakdown {
    val countable = rules.conversionNatures + CategoryNature.LIABILITY
    var breakdown = NatureBreakdown()
    rows.forEach { row ->
        val counts =
            when (row.type) {
                TransactionType.EXPENSE -> true
                TransactionType.TRANSFER_OUT -> row.nature in countable
                else -> false
            }
        if (counts) breakdown = breakdown.plus(row.nature, row.amount.magnitude())
    }
    return breakdown
}

/**
 * Adds one row's magnitude to the right total.
 * Why:    a `when` over the five natures in one place, so a sixth nature added to [CategoryNature]
 *         fails to compile here rather than being silently dropped from the month's totals.
 * Result: the breakdown with [amount] added to [nature]'s total.
 * Input:  the receiver; [nature]; [amount] — a positive magnitude. Output: [NatureBreakdown].
 * Changelog: 2026-08-10 — Created for issue 4.3.
 */
private fun NatureBreakdown.plus(
    nature: CategoryNature,
    amount: Money,
): NatureBreakdown =
    when (nature) {
        CategoryNature.NEED -> copy(needs = needs + amount)
        CategoryNature.WANT -> copy(wants = wants + amount)
        CategoryNature.INVEST -> copy(invested = invested + amount)
        CategoryNature.ASSET -> copy(assets = assets + amount)
        CategoryNature.LIABILITY -> copy(liabilities = liabilities + amount)
    }

/**
 * The amount without its sign.
 * Why:    an expense is stored negative (the sign is the direction, MNY-001), and a breakdown of
 *         negative totals would render as "you spent −₹42,000". Done here rather than by each
 *         caller so the fold has one notion of "how much".
 * Result: the magnitude. Input: the receiver. Output: [Money].
 * Changelog: 2026-08-10 — Created for issue 4.3.
 */
private fun Money.magnitude(): Money = if (this < Money.ZERO) Money.ZERO - this else this
