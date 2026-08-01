package com.aicfo.data.repository

import com.aicfo.core.common.Clock
import com.aicfo.core.common.getOrNull
import com.aicfo.core.common.startOfDay
import com.aicfo.core.database.entity.AccountEntity
import com.aicfo.core.database.entity.BudgetEntity
import com.aicfo.core.database.entity.CategoryEntity
import com.aicfo.core.database.entity.ProfileEntity
import com.aicfo.core.database.entity.RecurringRuleEntity
import com.aicfo.core.database.entity.TransactionEntity
import com.aicfo.core.model.AccountType
import com.aicfo.core.model.Money
import com.aicfo.domain.engines.quicksetup.QuickSetupEngineFactory
import com.aicfo.domain.engines.quicksetup.QuickSetupInput
import com.aicfo.domain.engines.quicksetup.QuickSetupPlan
import java.time.LocalDate
import kotlin.random.Random

/**
 * The sample dataset demo mode loads (issue 2.4; FR-ONB-004, P-08, P-03).
 *
 * Why:  FR-ONB-004 asks for **realistic** sample data available without creating a profile, and
 *       "realistic" is the whole difficulty. A single hardcoded month would show an app with nothing
 *       to say; a random dataset would give two users different demos and make the whole thing
 *       untestable. So this is neither: a fixed backbone of monthly obligations — salary, rent, SIP,
 *       insurance — plus discretionary spending jittered by a **seeded** `Random` (P-08). Fixed
 *       clock plus fixed seed always produces byte-identical rows, which is what makes a golden test
 *       possible at all.
 * What: one pure function from a [Clock] to the rows of six tables, all scoped to the demo profile.
 * Result: roughly three months of an Indian salaried household's finances, internally consistent —
 *       every account's closing balance is its opening balance plus the transactions written against
 *       it, so the net-worth engine (issue 2.6) will find figures that add up rather than props.
 * Changelog: 2026-07-28 — Created for issue 2.4.
 *
 * **No number here is invented at read time (P-03).** The budget envelopes are not typed into this
 * file: they are computed by the real [com.aicfo.domain.engines.quicksetup.QuickSetupEngine] from
 * the demo income, rent and savings, so the demo dashboard shows a budget derived by the same rules
 * — and carrying the same rule citations — as a real user's. What is fabricated is the *input*, and
 * that is what the demo banner and `source = demo` exist to say.
 *
 * **Every amount is `Long` paise (MNY-001)** and every date-only field is an ISO string (TIM-002);
 * instants come from the injected [Clock], never the wall (TIM-001).
 */
internal object DemoDataset {
    /**
     * Builds the whole dataset.
     * Why:    takes a [Clock] rather than a date so the demo is anchored to *the user's today* in
     *         *their* zone — a demo whose most recent transaction is six months old looks broken,
     *         and one whose dates resolve in UTC would put a 23:30 IST spend on the wrong day.
     * What:   derives the three-month window, generates the transactions, sums them into account
     *         balances, and asks the quick-setup engine for the budget.
     * Result: a [DemoRows] whose every row carries [DemoModeRepository.DEMO_PROFILE_ID]. Identical
     *         for identical [clock] readings and [seed].
     * Input:  [clock] — supplies today, the profile zone and the stamped instants; [seed] — the
     *         jitter source, fixed by default so the dataset is reproducible (P-08).
     * Output: [DemoRows].
     */
    fun build(
        clock: Clock,
        seed: Long = DEMO_SEED,
    ): DemoRows {
        val today = clock.today()
        val now = clock.nowUtcMillis()
        val currentMonthStart = today.withDayOfMonth(1)
        // Oldest first, so the seeded Random is consumed in a fixed order and the generated ids run
        // forwards in time. Reversing this would change every amount in the dataset.
        val months = listOf(currentMonthStart.minusMonths(2), currentMonthStart.minusMonths(1), currentMonthStart)

        val transactions = buildTransactions(clock, months, today, now, Random(seed))
        val plan = budgetPlan(currentMonthStart, now)

        return DemoRows(
            profile = profile(now),
            accounts = accounts(transactions, now),
            categories = categories(now),
            transactions = transactions,
            budgets =
                plan.envelopes.map {
                    it.toEntity(DemoModeRepository.DEMO_PROFILE_ID, plan.periodStartIsoDate, now, SOURCE_DEMO)
                },
            recurring =
                plan.recurring.map { it.toEntity(DemoModeRepository.DEMO_PROFILE_ID, now, SOURCE_DEMO) },
        )
    }

    /**
     * The demo profile row.
     * Why:    a row of its own rather than reusing the user's, because every other row is scoped to
     *         a profile id and that scoping is the entire isolation mechanism (ADR-0006). Its zone
     *         and currency are fixed rather than copied from settings: a demo user has no settings —
     *         FR-ONB-004's "without creating a profile" — and P-06 makes this app India-first.
     * Result: the `profile` row demo data hangs off.
     * Input:  [nowUtcMillis] — from the injected Clock. Output: [ProfileEntity].
     */
    private fun profile(nowUtcMillis: Long): ProfileEntity =
        ProfileEntity(
            id = DemoModeRepository.DEMO_PROFILE_ID,
            // Not a person's name: this string is never shown (the banner is), and a plausible name
            // here would be the one piece of the demo that looks like it belongs to somebody.
            displayName = "",
            timeZoneId = DEMO_ZONE_ID,
            currencyCode = DEMO_CURRENCY_CODE,
            createdAtUtcMillis = nowUtcMillis,
            updatedAtUtcMillis = nowUtcMillis,
        )

    /**
     * The four accounts the sample household holds.
     * Why:    closing balances are **derived**, not typed — `opening + sum(transactions)` — so the
     *         demo is arithmetically consistent. A typed closing balance would disagree with the
     *         transaction list the moment issue 3.6 renders one, and the first thing a user would
     *         conclude is that the app cannot add up.
     * What:   a bank account, cash, a credit card carried as a negative balance, and an investment
     *         folio — enough shapes that the net-worth engine (2.6) has both assets and a liability.
     * Result: four [AccountEntity] rows.
     * Input:  [transactions] — already generated, so their sums can be applied; [nowUtcMillis].
     * Output: `List<AccountEntity>`.
     */
    private fun accounts(
        transactions: List<TransactionEntity>,
        nowUtcMillis: Long,
    ): List<AccountEntity> {
        val movement = transactions.groupBy { it.accountId }.mapValues { (_, rows) -> rows.sumOf { it.amountMinor } }
        return ACCOUNT_SPECS.map { spec ->
            AccountEntity(
                id = accountId(spec.key),
                profileId = DemoModeRepository.DEMO_PROFILE_ID,
                name = spec.name,
                type = spec.type.storedValue,
                openingBalanceMinor = spec.openingBalanceMinor,
                currentBalanceMinor = spec.openingBalanceMinor + (movement[accountId(spec.key)] ?: 0L),
                currencyCode = DEMO_CURRENCY_CODE,
                institution = spec.institution,
                createdAtUtcMillis = nowUtcMillis,
                updatedAtUtcMillis = nowUtcMillis,
            )
        }
    }

    /**
     * The spending categories, one per kind of expense in the dataset.
     * Why:    marked `isSystem = true` for the same reason the real seeded defaults will be — they
     *         were not created by the user, so a future editor (issue 4.1) must not offer to rename
     *         or delete them as though they were personal.
     * Result: twelve [CategoryEntity] rows, natures drawn from the same closed set
     *         `BudgetNature` uses, so the envelopes and the spending line up without a lookup table.
     * Input:  [nowUtcMillis]. Output: `List<CategoryEntity>`.
     */
    private fun categories(nowUtcMillis: Long): List<CategoryEntity> =
        CATEGORY_SPECS.map { spec ->
            CategoryEntity(
                id = categoryId(spec.key),
                profileId = DemoModeRepository.DEMO_PROFILE_ID,
                name = spec.name,
                nature = spec.nature,
                isSystem = true,
                createdAtUtcMillis = nowUtcMillis,
                updatedAtUtcMillis = nowUtcMillis,
            )
        }

    /**
     * Generates the three months of transactions.
     *
     * Why:    two kinds of row, because a real month has two kinds of spending. **Fixed** rows —
     *         salary, rent, the SIP, insurance — land on the same day each month for the same amount,
     *         which is what makes the recurring detector (issue 3.7) and the forecast have something
     *         true to find. **Jittered** rows vary in day and amount, because a dataset where
     *         groceries cost exactly ₹2,800 four times a month would make every variance figure in
     *         the app read zero.
     *
     *         The current month is **truncated at today**: a demo showing spending that has not
     *         happened yet would put the forecast engines in a state no real user can reach.
     * Result: the transactions, oldest first, ids assigned in that order so they are derived rather
     *         than generated (P-08).
     * Input:  [clock] — resolves each date's instant in the profile zone; [months] — the three month
     *         starts, oldest first; [today] — the truncation bound; [nowUtcMillis]; [random] — the
     *         seeded jitter source.
     * Output: `List<TransactionEntity>`.
     */
    private fun buildTransactions(
        clock: Clock,
        months: List<LocalDate>,
        today: LocalDate,
        nowUtcMillis: Long,
        random: Random,
    ): List<TransactionEntity> {
        val drafts = mutableListOf<DemoDraft>()
        for (month in months) {
            val lengthOfMonth = month.lengthOfMonth()
            for (spec in FIXED_SPECS) {
                drafts += DemoDraft(month.withDayOfMonth(minOf(spec.dayOfMonth, lengthOfMonth)), spec.amountMinor, spec)
            }
            for (spec in JITTERED_SPECS) {
                repeat(spec.perMonth) {
                    // Both draws happen for every occurrence, in this order, whether or not the row
                    // survives the truncation below — so the Random sequence, and therefore every
                    // other amount in the dataset, does not depend on today's date.
                    val day = random.nextInt(1, lengthOfMonth + 1)
                    val jitterRupees = random.nextLong(-spec.jitterRupees, spec.jitterRupees + 1)
                    val amount = spec.amountMinor + jitterRupees * PAISE_PER_RUPEE
                    drafts += DemoDraft(month.withDayOfMonth(day), amount, spec)
                }
            }
        }
        return drafts
            .filter { !it.date.isAfter(today) }
            .sortedWith(compareBy({ it.date }, { it.spec.key }))
            .mapIndexed { index, draft -> draft.toEntity(clock, index, nowUtcMillis) }
    }

    /**
     * Asks the real engine for the demo's budget (P-03, P-02).
     * Why:    the envelopes are **not** written by hand. Typing three amounts here would be the app
     *         showing a budget nothing derived, on a screen whose whole promise is that every figure
     *         can name the rule behind it. Running the actual engine means the demo's needs/wants/
     *         savings split carries the same `RULE-…` citations a real user's does.
     * Result: the [QuickSetupPlan] for the current month.
     * Input:  [periodStart] — the first day of the current month; [nowUtcMillis] — stamped into the
     *         engine's provenance. Output: [QuickSetupPlan].
     *
     * A failure here is a **programmer error**, not a user-facing one: the inputs are compile-time
     * constants, so the only way the engine rejects them is if someone edits them to something
     * invalid — which §21.6 says is exactly what a crash is for.
     */
    private fun budgetPlan(
        periodStart: LocalDate,
        nowUtcMillis: Long,
    ): QuickSetupPlan {
        val input =
            QuickSetupInput(
                monthlyIncome = Money(DEMO_MONTHLY_INCOME_MINOR),
                rentOrEmi = Money(DEMO_RENT_MINOR),
                typicalSavings = Money(DEMO_SAVINGS_MINOR),
                periodStartIsoDate = periodStart.toString(),
                nowUtcMillis = nowUtcMillis,
            )
        return QuickSetupEngineFactory.create().plan(input).getOrNull()
            ?: error("The demo seeds are constants; the quick-setup engine must never reject them.")
    }

    /** Result: the derived id for a demo account. Input: [key]. Output: a stable [String]. */
    private fun accountId(key: String): String = "${DemoModeRepository.DEMO_PROFILE_ID}:account:$key"

    /** Result: the derived id for a demo category. Input: [key]. Output: a stable [String]. */
    private fun categoryId(key: String): String = "${DemoModeRepository.DEMO_PROFILE_ID}:category:$key"

    /**
     * Converts one draft into its row.
     * Why:    the id is derived from the row's position in a deterministic sequence, never generated
     *         — the same rule `budgetId` follows, and what lets a re-entered demo update the same
     *         rows under REPLACE rather than accumulate a second copy of every transaction.
     * Result: a [TransactionEntity].
     * Input:  the receiver; [clock] — resolves the profile-zone instant; [index] — position in the
     *         sorted sequence; [nowUtcMillis]. Output: [TransactionEntity].
     */
    private fun DemoDraft.toEntity(
        clock: Clock,
        index: Int,
        nowUtcMillis: Long,
    ): TransactionEntity =
        TransactionEntity(
            id = "${DemoModeRepository.DEMO_PROFILE_ID}:txn:${index.toString().padStart(ID_DIGITS, '0')}",
            profileId = DemoModeRepository.DEMO_PROFILE_ID,
            accountId = accountId(spec.accountKey),
            amountMinor = amountMinor,
            currencyCode = DEMO_CURRENCY_CODE,
            // Midday in the profile zone, plus a per-row second so two rows on one day still order
            // deterministically. Midday and not midnight: a timestamp on a day boundary is the one
            // most likely to land on the wrong date if any caller ever re-derives it in UTC.
            occurredAtUtcMillis = clock.startOfDay(date) + MIDDAY_OFFSET_MILLIS + index * MILLIS_PER_SECOND,
            bookedOnIsoDate = date.toString(),
            categoryId = spec.categoryKey?.let(::categoryId),
            merchant = spec.merchant,
            note = null,
            source = SOURCE_DEMO,
            createdAtUtcMillis = nowUtcMillis,
            updatedAtUtcMillis = nowUtcMillis,
        )

    // --- The dataset's own figures -------------------------------------------------------------
    //
    // Rupee amounts for a plausible mid-career salaried household in an Indian metro. They are
    // constants rather than rulebook rows on purpose: CLAUDE.md §6 bans hardcoded *financial
    // thresholds* — numbers that decide advice — and none of these do. They are the fictional
    // household's circumstances, the equivalent of a test fixture, and the rules that act on them
    // (the 50/30/20 split, the runway) all live in the engine where §6 requires.

    /** What the sample household earns each month, in paise (MNY-001). */
    private const val DEMO_MONTHLY_INCOME_MINOR = 95_000_00L

    /** Their rent, in paise. Under RULE-EMI-40's warn band, so the demo shows a healthy verdict. */
    private const val DEMO_RENT_MINOR = 28_000_00L

    /** What they put aside each month, in paise. */
    private const val DEMO_SAVINGS_MINOR = 10_000_00L

    /** The IANA zone the demo resolves its dates in. India-first (P-06). */
    private const val DEMO_ZONE_ID = "Asia/Kolkata"

    /** ISO-4217 for the rupee. */
    private const val DEMO_CURRENCY_CODE = "INR"

    /**
     * The jitter seed.
     * Why:    fixed and named, because P-08 allows randomness only from an injected, seedable
     *         source. Changing this value changes every discretionary amount in the demo and will
     *         red the golden test — which is the point: the dataset is a fixture, and a fixture that
     *         can drift silently is not one.
     */
    const val DEMO_SEED: Long = 20_240_728L

    /** Paise in a rupee — jitter is drawn in whole rupees, because ₹2,847.63 for petrol reads as noise. */
    private const val PAISE_PER_RUPEE = 100L

    /** Millis from local midnight to midday. */
    private const val MIDDAY_OFFSET_MILLIS = 12L * 60L * 60L * 1_000L

    /** One second, in millis — the per-row nudge that keeps same-day ordering stable. */
    private const val MILLIS_PER_SECOND = 1_000L

    /** Zero-padding width for transaction ids, so they sort lexicographically as well as numerically. */
    private const val ID_DIGITS = 4

    /**
     * The obligations that repeat on the same day every month.
     *
     * Columns: `key`, `dayOfMonth`, `amountMinor`, `accountKey`, `categoryKey`, `merchant`.
     *
     * Signs follow the `transactions` convention: positive is an inflow, negative an outflow. The
     * salary carries **no category** — the closed nature set (`need`/`want`/`invest`/`asset`/
     * `liability`, §8.3) has no income member, and inventing one here would be this file deciding a
     * classification that issue 4.3's classifier owns.
     *
     * **`MagicNumber` is suppressed on these two tables deliberately.** They are a fixture: the
     * literals *are* the content, exactly as they are in a theme-token file or a test — the two
     * cases `config/detekt/detekt.yml` already exempts for the same reason. Hoisting each day and
     * amount into a named constant would produce twenty names used once apiece and would make the
     * table unreadable as a table. The numbers that actually decide advice live in the engine, where
     * CLAUDE.md §6 requires; none of these do.
     */
    @Suppress("MagicNumber") // See the note above FIXED_SPECS.
    private val FIXED_SPECS =
        listOf(
            FixedSpec("salary", 1, DEMO_MONTHLY_INCOME_MINOR, "savings", null, "Monthly salary"),
            FixedSpec("rent", 3, -DEMO_RENT_MINOR, "savings", "rent", "Landlord"),
            FixedSpec("sip", 5, -DEMO_SAVINGS_MINOR, "savings", "sip", "Index fund SIP"),
            FixedSpec("utilities", 7, -3_200_00L, "savings", "utilities", "Electricity board"),
            // The 31st on purpose: insurers debit at month end, and it is the one spec that makes
            // the length-of-month clamp in buildTransactions live code rather than a defensive
            // branch nothing exercises — in February this row lands on the 28th.
            FixedSpec("insurance", 31, -2_400_00L, "savings", "insurance", "Term cover premium"),
        )

    /**
     * The discretionary spending, varying in day and amount within a seeded band.
     *
     * Columns: `key`, `perMonth`, `amountMinor` (the centre of the band, signed paise),
     * `jitterRupees` (its half-width), `accountKey`, `categoryKey`, `merchant`.
     */
    @Suppress("MagicNumber") // See the note above FIXED_SPECS.
    private val JITTERED_SPECS =
        listOf(
            JitteredSpec("groceries", 5, -2_800_00L, 900L, "card", "groceries", "Supermarket"),
            JitteredSpec("fuel", 3, -2_400_00L, 600L, "card", "fuel", "Fuel station"),
            JitteredSpec("dining", 6, -900_00L, 400L, "card", "dining", "Restaurant"),
            JitteredSpec("transport", 5, -350_00L, 150L, "cash", "transport", "Auto/cab"),
            JitteredSpec("entertainment", 2, -600_00L, 300L, "card", "entertainment", "Streaming"),
            JitteredSpec("shopping", 2, -2_200_00L, 1_500L, "card", "shopping", "Retail"),
            JitteredSpec("health", 1, -1_500_00L, 500L, "cash", "health", "Pharmacy/clinic"),
        )

    /**
     * The accounts, with the opening balances the derived closing balances build on.
     *
     * Columns: `key`, `name`, `type` (an `AccountType`, so `"card"` can no longer be written where
     * `credit_card` was meant — issue 2.5), `openingBalanceMinor`, `institution`.
     */
    @Suppress("MagicNumber") // See the note above FIXED_SPECS.
    private val ACCOUNT_SPECS =
        listOf(
            AccountSpec("savings", "HDFC Savings", AccountType.BANK, 1_85_000_00L, "HDFC Bank"),
            // No institution: cash has no issuer, which is why the column is nullable.
            AccountSpec("cash", "Cash Wallet", AccountType.CASH, 5_000_00L, null),
            // Negative on purpose: a card is a liability, and the net-worth engine (2.6) subtracts it.
            AccountSpec("card", "ICICI Credit Card", AccountType.CREDIT_CARD, -18_000_00L, "ICICI Bank"),
            AccountSpec("sip", "Index Fund Folio", AccountType.INVESTMENT, 1_20_000_00L, "Zerodha Coin"),
        )

    /** The spending categories, natures drawn from the §8.3 closed set. */
    private val CATEGORY_SPECS =
        listOf(
            CategorySpec("rent", "Rent", nature = "need"),
            CategorySpec("groceries", "Groceries", nature = "need"),
            CategorySpec("utilities", "Utilities", nature = "need"),
            CategorySpec("fuel", "Fuel", nature = "need"),
            CategorySpec("transport", "Transport", nature = "need"),
            CategorySpec("health", "Health", nature = "need"),
            CategorySpec("insurance", "Insurance", nature = "need"),
            CategorySpec("dining", "Dining Out", nature = "want"),
            CategorySpec("entertainment", "Entertainment", nature = "want"),
            CategorySpec("shopping", "Shopping", nature = "want"),
            CategorySpec("travel", "Travel", nature = "want"),
            CategorySpec("sip", "Mutual Fund SIP", nature = "invest"),
        )
}

/**
 * The rows one demo load produces.
 * Why:    one value rather than six return channels, because they are written in a single
 *         transaction and are meaningless apart — a budget scoped to a profile that was never
 *         written is invisible to every query.
 * Result: the argument to the demo seeding transaction.
 * Input:  one list per table, plus the profile they are all scoped to. Output: an immutable value.
 * Changelog: 2026-07-28 — Created for issue 2.4.
 */
internal data class DemoRows(
    val profile: ProfileEntity,
    val accounts: List<AccountEntity>,
    val categories: List<CategoryEntity>,
    val transactions: List<TransactionEntity>,
    val budgets: List<BudgetEntity>,
    val recurring: List<RecurringRuleEntity>,
)

/**
 * What every generated transaction has in common, whatever produced it.
 * Why:    the fixed and jittered generators differ only in how they choose a day and an amount;
 *         everything downstream — the account, the category, the merchant — is the same question.
 *         One interface means `toEntity` is written once rather than twice, and adding a third kind
 *         of spending cannot forget a field.
 * Result: the shared shape of [FixedSpec] and [JitteredSpec].
 * Changelog: 2026-07-28 — Created for issue 2.4.
 */
internal sealed interface DemoSpec {
    /** A stable slug, used to break ties when two rows fall on the same day. */
    val key: String

    /** Which demo account the money moves through. */
    val accountKey: String

    /** Which demo category it belongs to, or `null` for income (see [DemoDataset]). */
    val categoryKey: String?

    /** The payee shown in a future transaction list. Fictional, like the rest of the dataset. */
    val merchant: String
}

/**
 * A monthly obligation: same day, same amount, every month.
 * Result: one transaction per month. Input: see the constructor. Output: an immutable value.
 * Changelog: 2026-07-28 — Created for issue 2.4.
 *
 * Input: [key]; [dayOfMonth] — clamped to the month's length, so the 31st does not vanish in
 *        February; [amountMinor] — signed paise (MNY-001); [accountKey]; [categoryKey]; [merchant].
 */
internal data class FixedSpec(
    override val key: String,
    val dayOfMonth: Int,
    val amountMinor: Long,
    override val accountKey: String,
    override val categoryKey: String?,
    override val merchant: String,
) : DemoSpec

/**
 * Discretionary spending: a fixed number of occurrences a month, on random days for jittered amounts.
 * Result: [perMonth] transactions per month. Input: see the constructor. Output: an immutable value.
 * Changelog: 2026-07-28 — Created for issue 2.4.
 *
 * Input: [key]; [perMonth] — how many times it happens; [amountMinor] — the signed centre of the
 *        band, in paise; [jitterRupees] — the half-width of the band, drawn in **whole rupees**
 *        because paise-level noise on a grocery bill reads as a bug; [accountKey]; [categoryKey];
 *        [merchant].
 */
internal data class JitteredSpec(
    override val key: String,
    val perMonth: Int,
    val amountMinor: Long,
    val jitterRupees: Long,
    override val accountKey: String,
    override val categoryKey: String?,
    override val merchant: String,
) : DemoSpec

/**
 * One account before its closing balance is derived.
 *
 * [type] is an [AccountType] rather than a string (issue 2.5): the demo used to write `"card"`,
 * which is not in FR-ACC-001's vocabulary, so it was an account no type-aware query would ever have
 * matched. The enum makes that unrepresentable.
 *
 * Input: [key], [name], [type], [openingBalanceMinor] — MNY-001 paise, signed;
 *        [institution] — null where there is no issuer. Output: an immutable value.
 */
internal data class AccountSpec(
    val key: String,
    val name: String,
    val type: AccountType,
    val openingBalanceMinor: Long,
    val institution: String?,
)

/** One category. Input: [key], [name], [nature] — a §8.3 code. Output: an immutable value. */
internal data class CategorySpec(
    val key: String,
    val name: String,
    val nature: String,
)

/**
 * A transaction after its day and amount are settled but before it has an id.
 * Why:    ids depend on the row's position in the final sorted order, which is not known until every
 *         month has been generated and the future has been trimmed. Splitting the two steps is what
 *         keeps the ids contiguous and derived rather than generated.
 * Result: the intermediate value `buildTransactions` sorts. Input: see the constructor.
 * Output: an immutable value.
 * Changelog: 2026-07-28 — Created for issue 2.4.
 */
internal data class DemoDraft(
    val date: LocalDate,
    val amountMinor: Long,
    val spec: DemoSpec,
)
