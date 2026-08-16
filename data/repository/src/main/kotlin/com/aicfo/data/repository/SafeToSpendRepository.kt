package com.aicfo.data.repository

import com.aicfo.core.common.Clock
import com.aicfo.core.common.DispatcherProvider
import com.aicfo.core.common.getOrNull
import com.aicfo.core.database.CfoDatabase
import com.aicfo.core.database.entity.RecurringRuleEntity
import com.aicfo.core.model.Money
import com.aicfo.core.model.Transaction
import com.aicfo.core.model.TransactionType
import com.aicfo.domain.engines.nature.NatureBreakdown
import com.aicfo.domain.engines.quicksetup.BudgetEnvelope
import com.aicfo.domain.engines.quicksetup.BudgetNature
import com.aicfo.domain.engines.safetospend.SafeToSpend
import com.aicfo.domain.engines.safetospend.SafeToSpendEngine
import com.aicfo.domain.engines.safetospend.SafeToSpendInput
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOn

/**
 * Assembles what Safe-to-Spend is computed from (issue 5.2; §5.2, §14, ARC-005).
 *
 * Why:  `RULE-STS`'s formula is a subtraction, and [SafeToSpendEngine] owns it. What it cannot own
 *       is **which rows belong in each term** — that is five different storage questions, and every
 *       one of them is a place the figure can go quietly wrong:
 *
 *       - *Where does income come from?* `RULE-STS.income_basis` says the quick-setup envelopes,
 *         falling back to the ledger. A month-to-date figure alone would read deeply negative for
 *         twenty-seven days and jump on payday — it would measure the salary calendar, not the user.
 *       - *Which scheduled rows count?* Only expenses, and only ones falling before month end. A
 *         scheduled salary credit is not a commitment, and `observeUpcoming` reaches ninety days.
 *       - *Which recurring rules count?* Only confirmed outflows. The quick-setup `income` seed is a
 *         recurring rule with a **positive** amount, and counting it as a bill would subtract the
 *         user's salary from their own spending money.
 *       - *Is that bill already counted?* A user who scheduled the rent transaction **and** confirmed
 *         a rent rule has told the app the same thing twice. See `deduplicatedAgainst`.
 *       - *Is there an income basis at all?* A profile with neither declares nothing, and the answer
 *         is an absence, not a confident ₹0 (P-03).
 * What: one observed read, folding five existing reads into one engine call.
 * Result: the home screen's headline figure, with the breakdown that explains it.
 * Changelog: 2026-08-16 — Created for issue 5.2.
 *
 * **It computes nothing itself (P-03).** Every rupee of arithmetic is [SafeToSpendEngine]'s; this
 * class only sums the rows it decided belong in each term, and folding a set of rows is the same
 * work `BudgetRepository` does before calling its own engine.
 */
interface SafeToSpendRepository {
    /**
     * Observes what is still safe to spend this month, for whichever profile is active.
     *
     * Why:    a Flow rather than a one-off read, because every term moves under the screen — an
     *         expense added, a bill scheduled, a budget edited. The same `activeProfileId` seam the
     *         repositories beside it use, so the demo gets its own figure and the dashboard follows
     *         without knowing demo mode exists (issue 2.4).
     * Result: emits on every change to the budget, the ledger or the user's recurring rules.
     *         **`null` when the profile has no income basis at all** — no quick-setup envelopes and
     *         nothing credited this month — which the screen renders as "not worked out yet" rather
     *         than as ₹0. A confident zero would be a figure the app made up (P-03), and it is
     *         indistinguishable from a real month with nothing left. `null` again if the engine
     *         cannot compute (arithmetic that will not fit a `Long`), for the same reason
     *         `RecurringRepository` degrades a detector failure to an empty list: one card showing an
     *         absence beats the whole home screen showing an error.
     * Input:  none (the month comes from the injected `Clock`, TIM-001).
     * Output: `Flow<SafeToSpend?>`.
     */
    fun observeSafeToSpend(): Flow<SafeToSpend?>
}

/**
 * The Room-backed [SafeToSpendRepository].
 * Why:    ARC-003 — one public interface, an internal implementation, assembled by the DI graph.
 * Result: the implementation injected into the dashboard ViewModel.
 * Changelog: 2026-08-16 — Created for issue 5.2.
 *
 * **Mostly built on other repositories, deliberately.** Three of its five terms are already exposed
 * by [TransactionRepository] and [QuickSetupRepository], and `observeNatureBreakdown()` in
 * particular is a five-way join plus a per-transaction engine call (issue 4.3). Re-deriving that
 * here to keep every read a direct DAO call would be a second definition of "what this month's money
 * became", and the two would drift. The seam is not new — `RoomReceiptRepository` (3.8) and
 * `RoomSmsRepository` (3.9) both take a [TransactionRepository] for the same reason. ARC-005 is
 * untouched: every DAO read still happens inside a repository, and no entity escapes this file.
 *
 * Input:  [database] — for the one read nothing else exposes, the profile's recurring rules;
 *         [transactions] — cash flow, the nature breakdown and the scheduled rows;
 *         [quickSetup] — the persisted budget envelopes; [engine] — computes every figure (P-03);
 *         [clock] — supplies the month, never a wall-clock read (TIM-001); [dispatchers];
 *         [activeProfileId] — which profile the recurring read resolves to.
 * Output: a working repository.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@Suppress("LongParameterList") // Seven collaborators, each the one legitimate source of one term.
internal class RoomSafeToSpendRepository(
    private val database: CfoDatabase,
    private val transactions: TransactionRepository,
    private val quickSetup: QuickSetupRepository,
    private val engine: SafeToSpendEngine,
    private val clock: Clock,
    private val dispatchers: DispatcherProvider,
    private val activeProfileId: Flow<String>,
) : SafeToSpendRepository {
    override fun observeSafeToSpend(): Flow<SafeToSpend?> =
        // flatMapLatest, not map: entering or leaving the demo must *switch* which queries are being
        // observed, cancelling the previous ones — as the repositories beside it do.
        activeProfileId
            .flatMapLatest { profileId ->
                // Inside the lambda, not captured outside: the flow may outlive midnight, and
                // re-reading is what makes the window roll over with the profile's day (TIM-001).
                val month = MonthWindow.current(clock.today())
                val todayIsoDate = clock.today().toString()
                combine(
                    quickSetup.observeLatestEnvelopes(profileId),
                    transactions.observeMonthCashFlow(),
                    transactions.observeNatureBreakdown(),
                    transactions.observeUpcoming(),
                    database.recurringRuleDao().observeForProfile(profileId),
                ) { envelopes, cashFlow, nature, upcoming, recurringRules ->
                    val income = incomeBasis(envelopes, cashFlow.income)
                    val scheduled = upcoming.scheduledCommitments(month.endIsoDate)
                    val bills = recurringRules.billsDue(todayIsoDate, month.endIsoDate)
                    if (income == null) {
                        null
                    } else {
                        figure(
                            month = month,
                            income = income,
                            nature = nature,
                            envelopes = envelopes,
                            scheduled = scheduled.total(),
                            recurring = bills.deduplicatedAgainst(scheduled),
                        )
                    }
                }
            }.flowOn(dispatchers.io)

    /**
     * Runs the engine over one month's resolved terms.
     * Why:    split out so the folding above reads as five decisions about rows and this reads as one
     *         engine call — and so `LongMethod` never tempts anyone to inline the sums into the
     *         `combine` block, where a term could be quietly dropped.
     * Result: the figure, or `null` when the engine cannot compute one.
     * Input:  [month] — the window; [income] — resolved per `RULE-STS.income_basis`; [nature] — this
     *         month's classified totals (issue 4.3); [envelopes] — the persisted budget, for the
     *         savings target; [scheduled], [recurring] — the commitments still due, as magnitudes.
     * Output: `SafeToSpend?`.
     */
    private fun figure(
        month: MonthWindow,
        income: Money,
        nature: NatureBreakdown,
        envelopes: List<BudgetEnvelope>,
        scheduled: Money,
        recurring: Money,
    ): SafeToSpend? =
        engine.compute(
            SafeToSpendInput(
                income = income,
                spentToDate = nature.trueSpend,
                scheduled = scheduled,
                recurringDue = recurring,
                goalContributionsRemaining = envelopes.savingsPlanned(),
                inputWindow = "${month.startIsoDate}..${month.endIsoDate}",
                nowUtcMillis = clock.nowUtcMillis(),
            ),
        ).getOrNull()
}

/**
 * Resolves `RULE-STS.income_basis` — the declared budget, else the ledger (issue 5.2).
 *
 * Why:    the envelopes are what quick setup split the user's stated monthly income into, so their
 *         total *is* that income and it is stable from the 1st. The ledger's month-to-date income is
 *         the fallback for a profile that skipped quick setup — less stable, but true, and better
 *         than nothing. **Neither being positive is an absence, not a zero**: a figure computed from
 *         no income at all would be a confident negative number built on a fact nobody supplied
 *         (P-03), so the card shows its pending state instead.
 * Result: the income to use, or `null` when there is no basis.
 * Input:  [envelopes] — the persisted budget; [actualIncome] — this month's posted income, a
 *         non-negative magnitude. Output: `Money?`.
 * Changelog: 2026-08-16 — Created for issue 5.2.
 */
private fun incomeBasis(
    envelopes: List<BudgetEnvelope>,
    actualIncome: Money,
): Money? {
    val declared = envelopes.fold(Money.ZERO) { running, envelope -> running + envelope.amount }
    val income = if (declared > Money.ZERO) declared else actualIncome
    return income.takeIf { it > Money.ZERO }
}

/**
 * The month's savings envelope, in full (issue 5.2; §5.2).
 *
 * Why:    §5.2 names "goal contributions" as a term, and the goals engine (issue 7.1) does not exist
 *         in this build. This is the closest true statement available: the user's own declared
 *         monthly saving.
 *
 *         **In full, deliberately — not netted against what has already been saved.** Netting looks
 *         obviously right and is wrong, because §8.3's `trueSpend` is `NEED + WANT` and therefore
 *         *already excludes* every conversion. Money that went into an SIP is in neither
 *         `spentToDate` nor here-minus-what-was-saved, so netting would leave it deducted from
 *         nothing at all — Safe-to-Spend would rise by exactly the amount the user had just saved,
 *         which is the opposite of the truth. Taking the envelope whole counts each planned rupee
 *         exactly once, whether it has moved yet or not, and keeps the figure steady across the
 *         month instead of stepping up on the day the SIP debits.
 *
 *         It also makes the conversion's *mechanism* irrelevant: an SIP booked as an expense row and
 *         one booked as a transfer to a mutual-fund account are both excluded from `trueSpend` by
 *         §8.3, so both are covered here once.
 * Result: the envelope, or `Money.ZERO` when the user has no savings envelope at all.
 * Input:  the receiver — the persisted envelopes. Output: [Money].
 * Changelog: 2026-08-16 — Created for issue 5.2.
 */
private fun List<BudgetEnvelope>.savingsPlanned(): Money =
    firstOrNull { it.nature == BudgetNature.INVEST }?.amount ?: Money.ZERO

/**
 * The confirmed bills falling due inside the window (issue 5.2; FR-TXN-006).
 *
 * Why:    three filters, and dropping any one of them breaks the figure in a different direction.
 *         **Confirmed only** — an unconfirmed row is a proposal the user has not answered, and P-07
 *         says a proposal binds nobody. **Outflows only** — quick setup writes the user's *salary*
 *         as a recurring rule with a positive amount (issue 2.3), and treating it as a bill would
 *         subtract their income from their spending money. **Strictly after today** — a rule due
 *         today either has already been paid, in which case the transaction is in `spentToDate`, or
 *         has not, in which case one day's error is the smaller mistake than counting it twice; the
 *         boundary also abuts `observeUpcoming`'s "tomorrow" exactly, so no day falls in both windows
 *         or in neither.
 * Result: the rules, unsummed, so [deduplicated] can still see their names and dates.
 * Input:  the receiver — the profile's live rules; [todayIsoDate] — exclusive lower bound;
 *         [endIsoDate] — inclusive upper bound (TIM-002). Output: `List<RecurringRuleEntity>`.
 * Changelog: 2026-08-16 — Created for issue 5.2.
 */
private fun List<RecurringRuleEntity>.billsDue(
    todayIsoDate: String,
    endIsoDate: String,
): List<RecurringRuleEntity> =
    filter { rule ->
        rule.isConfirmed &&
            rule.amountMinor < 0L &&
            rule.nextDueIsoDate > todayIsoDate &&
            rule.nextDueIsoDate <= endIsoDate
    }

/**
 * Totals the bills, dropping any the user has already scheduled as a transaction (issue 5.2).
 *
 * Why:    the double-count this whole file is most likely to get wrong. A user who scheduled the
 *         rent transaction for the 28th **and** confirmed a rent recurring rule due the 28th has
 *         told the app the same thing twice, and a naive sum would take rent off Safe-to-Spend
 *         twice — quietly, and by an amount large enough to make the figure useless.
 *
 *         Matched on **name and date together**, not name alone: a merchant billed twice in one
 *         month (a top-up and a subscription) is two real commitments, and matching on the merchant
 *         alone would silently discard the second. Normalised the same way `recurringRuleId` and
 *         `observeDecidedNames` normalise, so case alone cannot defeat the match.
 * Result: the total as a positive magnitude, `Money.ZERO` when everything was already scheduled.
 * Input:  the receiver — the bills due; [scheduled] — the scheduled rows already counted, as
 *         `commitmentsBefore` filtered them. Output: [Money].
 * Changelog: 2026-08-16 — Created for issue 5.2.
 */
private fun List<RecurringRuleEntity>.deduplicatedAgainst(scheduled: List<Transaction>): Money {
    val alreadyCounted =
        scheduled.mapTo(mutableSetOf()) { row -> row.merchant.orEmpty().normalised() to row.bookedOn }
    return filterNot { rule -> (rule.name.orEmpty().normalised() to rule.nextDueIsoDate) in alreadyCounted }
        .fold(Money.ZERO) { running, rule -> running + Money(rule.amountMinor).magnitude() }
}

/**
 * The scheduled rows that are commitments inside the window (issue 5.2; FR-TXN-010).
 *
 * Why:    **expenses only, and only before month end.** `observeUpcoming` reaches ninety days and
 *         carries every type, because the forecast (issue 9.2) wants all of it; Safe-to-Spend is a
 *         question about *this* month, and a scheduled salary credit or an internal transfer is not
 *         something the month has claimed. Summing the flow as it arrives would make next quarter's
 *         insurance premium reduce what is safe to spend today.
 *
 *         Returns the rows rather than their total because [deduplicatedAgainst] needs their
 *         merchants and dates. One definition of "a scheduled commitment", shared by both callers —
 *         two would let a row be summed by one and unmatched by the other, which is the double-count
 *         the deduplication exists to prevent.
 * Result: the qualifying rows, empty when nothing is scheduled.
 * Input:  the receiver — the upcoming rows; [endIsoDate] — the window's last day, inclusive
 *         (TIM-002; ISO dates compare correctly as strings). Output: `List<Transaction>`.
 * Changelog: 2026-08-16 — Created for issue 5.2.
 */
private fun List<Transaction>.scheduledCommitments(endIsoDate: String): List<Transaction> =
    filter { it.type == TransactionType.EXPENSE && it.bookedOn <= endIsoDate }

/**
 * Totals scheduled commitments as magnitudes.
 * Result: the total, `Money.ZERO` for an empty list. Input: the receiver. Output: [Money].
 * Changelog: 2026-08-16 — Created for issue 5.2.
 */
private fun List<Transaction>.total(): Money = fold(Money.ZERO) { running, row -> running + row.amount.magnitude() }

/**
 * Lower-cases and trims a merchant or rule name for matching.
 * Why:    the same normalisation `recurringRuleId` and `RecurringRuleDao.observeDecidedNames` apply,
 *         so "Netflix" scheduled and "NETFLIX" ruled are one commitment rather than two.
 * Result: the key. Input: the receiver. Output: [String].
 * Changelog: 2026-08-16 — Created for issue 5.2.
 */
private fun String.normalised(): String = trim().lowercase()

/**
 * The amount without its sign.
 * Why:    an expense is stored negative (the sign is the direction, MNY-001), and every term
 *         `SafeToSpendInput` takes is a magnitude — the direction lives in the component, not the
 *         number. Done here rather than by each caller so this file has one notion of "how much".
 * Result: the magnitude. Input: the receiver. Output: [Money].
 * Changelog: 2026-08-16 — Created for issue 5.2.
 */
private fun Money.magnitude(): Money = if (this < Money.ZERO) Money.ZERO - this else this
