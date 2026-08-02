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
 * One transaction, as everything above the data layer sees it (issue 3.1; FR-TXN-001, ARC-005).
 *
 * Why:  ARC-005 forbids a ViewModel from ever holding a Room type, so the transactions screens need
 *       a model of their own. The `transactions` table has existed since issue 1.6 with nothing but
 *       the demo dataset and issue 2.7's reconciliation adjustment writing to it; this is the type
 *       that lets the app read and create its own.
 * What: the fields FR-TXN-001 requires that issue 3.1 actually captures.
 * Result: a screen can render a transaction, and a test can construct one, without Room.
 * Changelog: 2026-08-02 — Created for issue 3.1 (FR-TXN-001).
 *
 * **[amount] is signed, and the sign is the direction** (MNY-001): negative is money leaving the
 * account, positive is money arriving. This mirrors `transactions.amount_minor` exactly, which is
 * what lets an account's balance be a plain `SUM` over its rows (DB-001, ADR-0007). There is no
 * separate `type` field for expense/income — the sign *is* the type, and storing both would let the
 * two disagree. Transfers (issue 3.2) are the case that needs more than a sign, and they get their
 * own representation rather than a third value here.
 *
 * **[occurredAtUtcMillis] and [bookedOn] are both stored, and they are not redundant.** The first is
 * the instant (TIM-001), which orders the list; the second is the profile-zone calendar day
 * (TIM-002), which decides which month's budget and which day's total the row belongs to. A spend at
 * 23:30 IST is 18:00Z on the same date — deriving one from the other at a call site without the
 * profile zone is how a day's spend lands in the wrong day, so both travel together.
 *
 * **FR-TXN-001's remaining fields are deliberately absent.** Tags and attachments have no table yet,
 * subcategory belongs to issue 4.1's taxonomy, and split lines are issue 3.3. Adding empty fields
 * for them now would be scaffolding no code reads.
 *
 * Input:  [id]; [accountId] — which account moved; [amount] — signed paise; [occurredAtUtcMillis];
 *         [bookedOn] — ISO `yyyy-MM-dd` in the profile zone; [categoryId] — `null` until the user
 *         has categories at all (issue 4.1) or chooses not to pick one; [merchant]; [note];
 *         [source] — FR-TXN-009.
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
