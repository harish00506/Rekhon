package com.aicfo.core.model

/**
 * How a transaction came to exist (issue 3.1; FR-TXN-009).
 *
 * Why:  FR-TXN-009 is a MUST — "every transaction MUST record its source: manual, ocr, sms, import,
 *       recurring-auto". The column has held a free-text string since issue 1.6 and, as with
 *       [AccountType], nothing but a closed enum stops a typo becoming a row every later query
 *       silently misses. Issue 3.5 builds the surfacing and filtering on top of this; 3.1 writes
 *       only [MANUAL], but the closed set has to exist before anything reads the column.
 * What: the sources the schema documents, each carrying the exact string the column stores.
 * Result: a transaction's provenance is a compile-time value everywhere above the database.
 * Changelog: 2026-08-02 — Created for issue 3.1 (FR-TXN-009).
 *
 * **[storedValue] is the persisted contract, not [name]** — the same rule [AccountType] states, and
 * for the same reason: `name.lowercase()` would break the day someone renames a constant.
 *
 * **Two values here are not in FR-TXN-009's list, and both are load-bearing.** The requirement names
 * the sources a *user's* transaction can have; the app writes two more of its own, and a reader that
 * did not know them would silently drop those rows:
 * - [RECONCILIATION] — issue 2.7's FR-ACC-006 balance adjustment. A real transaction the user can
 *   see and delete, and neither manual nor imported.
 * - [DEMO] — issue 2.4's sample dataset. **This one was found the hard way:** the first draft of
 *   this enum omitted it, so every demo transaction failed to parse and the recent-transactions
 *   list rendered empty on a demo profile whose account balances were plainly derived from those
 *   very rows. `the demo's own history is inside the window` in `TransactionRepositoryTest` is the
 *   regression test. The lesson generalises — **grep what the app actually writes to a column
 *   before modelling that column as a closed set**, because the SRS lists what the *feature* needs,
 *   not what the codebase already stores.
 */
enum class TransactionSource(val storedValue: String) {
    /** The user typed it in — issue 3.1's add flow. */
    MANUAL("manual"),

    /** Extracted from a receipt photo on-device (issue 3.8; FR-OCR-002). */
    OCR("ocr"),

    /** Parsed from a bank SMS on-device, under an explicit opt-in (issue 3.9; FR-ONB-003). */
    SMS("sms"),

    /** Brought in by a file import or a restore (issue 5.4). */
    IMPORT("import"),

    /** Written by reconciliation to align an account with a statement (issue 2.7; FR-ACC-006). */
    RECONCILIATION("reconciliation"),

    /**
     * Part of the sample dataset (issue 2.4; FR-ONB-004).
     *
     * Never the user's own money. The demo banner is what tells them so on screen (P-02); this is
     * how the row itself says it, and it is what `DemoDao` wipes on exit (ADR-0006).
     */
    DEMO("demo"),
    ;

    companion object {
        /**
         * Parses a stored source string.
         * Why:    a row written by a newer build may carry a source this one has never heard of, and
         *         the honest response is to skip that row rather than crash the list — the same
         *         forward-compatible shape [AccountType.fromStored] uses. **An old build reading a
         *         newer database shows fewer rows, not an exception.**
         * Result: the matching source, or `null` when the value is unknown or malformed.
         * Input:  [stored] — the value from `transactions.source`.
         * Output: `TransactionSource?`.
         * Changelog: 2026-08-02 — Created for issue 3.1.
         */
        fun fromStored(stored: String): TransactionSource? = entries.firstOrNull { it.storedValue == stored }
    }
}

/**
 * What kind of movement a transaction is (issue 3.2; §20.2, FR-TXN-003, FR-TXN-001).
 *
 * Why:  a transfer leg has to be excluded from income and expense totals, and the amount's sign
 *       cannot tell a `TRANSFER_OUT` from an `EXPENSE` — both are negative. §20.2's `type` column is
 *       the SRS's own answer, so this adopts it rather than inventing a parallel convention.
 * What: §20.2's five values, each carrying the exact string the column stores.
 * Result: "money the user actually spent" is `type IN ('expense','income')`, a filter that cannot be
 *       got wrong the way a sign-plus-null-check convention can.
 * Changelog: 2026-08-02 — Created for issue 3.2 (FR-TXN-003).
 *
 * **Direction is now recorded twice — here and in the amount's sign — and that is a real hazard.**
 * The rule is [matches], and three things keep the two in step: nothing outside `:data:repository`
 * supplies a type (it is derived at one mapping site from the amount and the write path), [matches]
 * states the rule exactly once, and a test asserts it holds for every row every write path produces.
 * §20.2 puts a `CHECK` constraint on this column; SQLite's `ALTER TABLE … ADD COLUMN` cannot add
 * one, so on an upgraded database that constraint lives in the test suite instead of in the schema.
 */
enum class TransactionType(val storedValue: String) {
    /** Money leaving an account and leaving the user's control. Negative. */
    EXPENSE("expense"),

    /** Money arriving from outside. Positive. */
    INCOME("income"),

    /** The outgoing leg of a transfer (FR-TXN-003). Negative, and **not** spending. */
    TRANSFER_OUT("transfer_out"),

    /** The incoming leg of a transfer (FR-TXN-003). Positive, and **not** income. */
    TRANSFER_IN("transfer_in"),

    /**
     * A balance correction the app posted (issue 2.7; FR-ACC-006).
     *
     * The only type whose sign is genuinely free: an adjustment closes the gap between the derived
     * balance and a statement, and that gap runs in whichever direction the user's records were
     * wrong in.
     */
    ADJUSTMENT("adjustment"),
    ;

    /** Whether this type is one leg of a transfer, and so must be kept out of spend totals. */
    val isTransfer: Boolean get() = this == TRANSFER_OUT || this == TRANSFER_IN

    /**
     * Whether an amount's sign agrees with this type.
     *
     * Why:    the single statement of the invariant that stops the `type` column and the amount's
     *         sign drifting apart. Every write path is asserted against it, so a future author who
     *         builds an inflow and labels it `EXPENSE` fails a test rather than shipping a row that
     *         two different readers disagree about.
     *
     *         Zero belongs to no direction and is rejected for every type except [ADJUSTMENT] — the
     *         repository refuses zero-amount rows anyway, and a zero adjustment writes nothing.
     * Result: `true` when the sign is the one this type requires.
     * Input:  [amount] — the signed amount, MNY-001 paise. Output: [Boolean].
     * Changelog: 2026-08-02 — Created for issue 3.2.
     */
    fun matches(amount: Money): Boolean =
        when (this) {
            EXPENSE, TRANSFER_OUT -> amount < Money.ZERO
            INCOME, TRANSFER_IN -> amount > Money.ZERO
            ADJUSTMENT -> amount != Money.ZERO
        }

    companion object {
        /**
         * Parses a stored type string.
         * Why:    the same forward-compatible shape [TransactionSource.fromStored] and
         *         [AccountType.fromStored] use — an old build reading a database a newer one wrote
         *         shows fewer rows rather than throwing.
         * Result: the matching type, or `null` when the value is unknown or malformed.
         * Input:  [stored] — the value from `transactions.type`. Output: `TransactionType?`.
         * Changelog: 2026-08-02 — Created for issue 3.2.
         */
        fun fromStored(stored: String): TransactionType? = entries.firstOrNull { it.storedValue == stored }
    }
}

/**
 * One transaction, as everything above the data layer sees it (issue 3.1; FR-TXN-001, ARC-005).
 *
 * Why:  ARC-005 forbids a ViewModel from ever holding a Room type, so the transactions screens need
 *       a model of their own. The `transactions` table has existed since issue 1.6 with nothing but
 *       the demo dataset and issue 2.7's reconciliation adjustment writing to it; this is the type
 *       that lets the app read and create its own.
 * What: the fields FR-TXN-001 requires that issue 3.1 actually captures.
 * Result: a screen can render a transaction, and a test can construct one, without Room.
 * Changelog: 2026-08-02 — Created for issue 3.1 (FR-TXN-001).
 *            2026-08-02 — Issue 3.2: [type] and [transferId], so a transfer is one logical record
 *            across two rows (FR-TXN-003) and can be kept out of spend totals.
 *            2026-08-03 — Issue 3.4: [postedAtUtcMillis] and [isScheduledOn], so a future-dated row
 *            can be told from an actual one (FR-TXN-010).
 *
 * **[amount] is signed, and the sign is the direction** (MNY-001): negative is money leaving the
 * account, positive is money arriving. This mirrors `transactions.amount_minor` exactly, which is
 * what lets an account's balance be a plain `SUM` over its rows (DB-001, ADR-0007).
 *
 * **[type] records that direction a second time**, because the sign alone cannot separate a transfer
 * leg from ordinary spending, and FR-TXN-003 requires exactly that separation. Issue 3.1 deliberately
 * had no such field; 3.2 adopts §20.2's column and pays for it with [TransactionType.matches], which
 * is asserted over every write path so the two can never disagree. **For arithmetic, [amount] is
 * still the authority** — nothing should ever compute a figure by branching on [type].
 *
 * **[occurredAtUtcMillis] and [bookedOn] are both stored, and they are not redundant.** The first is
 * the instant (TIM-001), which orders the list; the second is the profile-zone calendar day
 * (TIM-002), which decides which month's budget and which day's total the row belongs to. A spend at
 * 23:30 IST is 18:00Z on the same date — deriving one from the other at a call site without the
 * profile zone is how a day's spend lands in the wrong day, so both travel together.
 *
 * **FR-TXN-001's remaining fields are deliberately absent.** Tags and attachments have no table yet
 * and subcategory belongs to issue 4.1's taxonomy. Adding empty fields for them now would be
 * scaffolding no code reads.
 *
 * Input:  [id]; [accountId] — which account moved; [amount] — signed paise; [occurredAtUtcMillis];
 *         [bookedOn] — ISO `yyyy-MM-dd` in the profile zone; [categoryId] — `null` until the user
 *         has categories at all (issue 4.1) or chooses not to pick one; [merchant]; [note];
 *         [source] — FR-TXN-009; [type] — §20.2; [transferId] — the id both legs of a transfer
 *         share, `null` on every other row.
 * Output: an immutable value.
 */
data class Transaction(
    val id: String,
    val accountId: String,
    val amount: Money,
    val occurredAtUtcMillis: Long,
    val bookedOn: String,
    val categoryId: String?,
    val merchant: String?,
    val note: String?,
    val source: TransactionSource,
    val type: TransactionType,
    val transferId: String? = null,
    /**
     * How this one amount is attributed across categories (issue 3.3; FR-TXN-004).
     *
     * Empty for an ordinary transaction, and **empty is the normal case**. When it is not empty the
     * lines sum to exactly [amount] — the repository refuses to write any other combination — so
     * this never changes what the transaction is worth, only what it is *about*.
     */
    val splits: List<TransactionSplit> = emptyList(),
    /**
     * When the app recorded this row's booked day as having arrived (issue 3.4; FR-TXN-010).
     * `null` while it is still future-dated.
     *
     * **There is deliberately no `isScheduled` property here, and it must stay that way.**
     * Answering "is this scheduled?" means comparing [bookedOn] with *today*, and today is a
     * profile-zone question only the injected `Clock` can answer — which `:core:model` may not
     * read (TIM-001, and `CfoWallClockInDomain` fails the build on it). The comparison lives in
     * `TransactionsViewModel`, which has the `Clock`.
     *
     * **Nor is this field that comparison.** Balances bound on [bookedOn], so a row starts counting
     * the moment its date arrives whether or not `ScheduledTransactionWorker` has run; between
     * midnight and the worker's next run this is still `null` on a row that is already in the
     * user's balance. Read it as "the post was recorded", never as "this is scheduled" —
     * `docs/adr/0010-future-dated-posting.md`.
     */
    val postedAtUtcMillis: Long? = null,
) {
    /** Whether this transaction's amount is attributed across more than one category. */
    val isSplit: Boolean get() = splits.isNotEmpty()

    /**
     * Whether this transaction is booked in the future, given today's date (issue 3.4; FR-TXN-010).
     *
     * Why:    FR-TXN-010 splits the list in two — actuals and scheduled — and the split has to be
     *         made against the *same* value the balance queries use, which is [bookedOn], not
     *         [postedAtUtcMillis]. Taking today as a parameter is what keeps that rule stated once
     *         here while the clock stays outside this module (TIM-001).
     *
     *         **String comparison is correct here, not a shortcut.** ISO `yyyy-MM-dd` orders
     *         lexicographically exactly as it orders chronologically — the reason TIM-002 mandates
     *         that format — and it is the same comparison SQLite performs in the balance queries.
     *         Parsing to `LocalDate` first would give the identical answer more slowly, and would
     *         throw on a malformed stored value where this simply reports "not scheduled".
     * Result: `true` when this row's day has not arrived yet; `false` on the day itself.
     * Input:  [todayIsoDate] — today in the profile zone, ISO `yyyy-MM-dd`, from `Clock.today()`.
     * Output: [Boolean].
     * Changelog: 2026-08-03 — Created for issue 3.4 (FR-TXN-010).
     */
    fun isScheduledOn(todayIsoDate: String): Boolean = bookedOn > todayIsoDate
}

/**
 * One line of a split transaction (issue 3.3; FR-TXN-004).
 *
 * Why:  FR-TXN-004 requires "N lines with independent categories" whose amounts "sum exactly to the
 *       parent amount". A single `categoryId` on [Transaction] cannot say that a ₹1,000 supermarket
 *       trip was ₹600 of groceries and ₹400 of household, so the lines get their own type.
 * What: an amount and the category it belongs to.
 * Result: a screen can show what a purchase was actually made of, and budgets (issue 4.4) can count
 *       each part against the right envelope.
 * Changelog: 2026-08-02 — Created for issue 3.3 (FR-TXN-004).
 *
 * **[amount] is signed the same way as the parent**, not a magnitude. A line of an expense is
 * negative like the expense. That is what makes "the lines sum to the parent" a plain comparison of
 * two signed [Money] values rather than a rule about absolute values and directions — and it is the
 * comparison the repository validates before writing anything.
 *
 * **A line does not move money.** The parent holds the amount and is what every balance sums
 * (DB-001, ADR-0007); these only divide it. Summing lines *and* parents would double-count every
 * split — see `docs/adr/0009-splits-as-a-child-table.md`.
 *
 * Input:  [id]; [transactionId] — the parent; [amount] — signed paise; [categoryId] — `null` until
 *         issue 4.1 gives a real profile any categories; [note] — optional free text.
 * Output: an immutable value.
 */
data class TransactionSplit(
    val id: String,
    val transactionId: String,
    val amount: Money,
    val categoryId: String? = null,
    val note: String? = null,
)

/**
 * The total of a set of split lines.
 * Why:    "do these lines sum to their parent?" is asked by the repository before every split write
 *         and by the add screen on every keystroke, and both must compute it the same way. It reuses
 *         [Iterable.sum], so the arithmetic is [Money]'s overflow-checked addition (MNY-001) and not
 *         a second implementation that could round differently.
 * Result: the signed total; [Money.ZERO] for an empty list, which is what makes "no lines yet" read
 *         as a full remainder rather than as a balanced split.
 * Input:  the receiver. Output: [Money].
 * Changelog: 2026-08-02 — Created for issue 3.3.
 */
fun Iterable<TransactionSplit>.total(): Money = map { it.amount }.sum()

/**
 * A transfer, as one logical record (issue 3.2; FR-TXN-003).
 *
 * Why:  FR-TXN-003 calls a transfer "a single logical record affecting two accounts". It is *stored*
 *       as two rows, because that is what makes each account's balance a plain `SUM` over its own
 *       transactions (DB-001) — but nothing above the data layer should have to know that. This is
 *       the shape the list renders and the shape a caller gets back from creating one.
 * What: the pair, collapsed: where the money came from, where it went, and how much.
 * Result: a screen can show "HDFC Savings → Cash Wallet ₹5,000" without pairing rows itself.
 * Changelog: 2026-08-02 — Created for issue 3.2.
 *
 * **[amount] is positive, always.** It is the size of the movement, not a signed entry — the signs
 * live on the two legs, where they belong. Asking a screen to render `-₹5,000` for a transfer would
 * be asking which leg it was looking at, which is the question this type exists to remove.
 *
 * **Not a stored row.** §20.1 lists a `transfers` table; this app does not have one, because a parent
 * row would carry no fact the legs do not already hold — see `docs/adr/0008-transfers-as-linked-legs.md`.
 *
 * Input:  [id] — the shared `transfer_id`; [fromAccountId]; [toAccountId]; [amount] — positive paise;
 *         [bookedOn] — the profile-zone day **both** legs share (TIM-002); [note] — optional, and
 *         held on both legs so either one explains itself.
 * Output: an immutable value.
 */
data class Transfer(
    val id: String,
    val fromAccountId: String,
    val toAccountId: String,
    val amount: Money,
    val bookedOn: String,
    val note: String? = null,
)

/**
 * A category, as far as issue 3.1 needs one (issue 3.1; FR-TXN-002).
 *
 * Why:  FR-TXN-002's three taps are "amount → category suggestion → save", so the add screen has to
 *       be able to *offer* categories. It cannot own them: the taxonomy editor and the merchant-rule
 *       knowledge base are issue 4.1, and auto-categorisation is 4.2 — both of which sit after this
 *       one in the dependency order (4.1 → 3.5 → 3.1). Today only demo mode seeds any categories, so
 *       a real profile shows an empty list and saves `categoryId = null`, which the column has always
 *       allowed.
 * What: an id and a display name — what a chip needs and nothing more.
 * Result: the add screen can render whatever categories the profile has without pre-empting 4.1.
 * Changelog: 2026-08-02 — Created for issue 3.1 (FR-TXN-002).
 *
 * **No `nature` and no `parentId`, though the column and the table have both.** Need/Want/Invest
 * classification is issue 4.3's and the parent/child taxonomy is 4.1's; a field here that no screen
 * reads would be a second, drifting definition of a model those issues are going to own properly.
 * Widening this class is the cheap change; unpicking a wrong one is not.
 *
 * Input:  [id]; [name] — the user-facing label, already the user's own words.
 * Output: an immutable value.
 */
data class Category(
    val id: String,
    val name: String,
)
