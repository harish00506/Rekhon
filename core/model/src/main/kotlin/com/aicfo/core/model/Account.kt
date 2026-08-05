package com.aicfo.core.model

/**
 * The kinds of place money can sit (issue 2.5; FR-ACC-001, §20.2).
 *
 * Why:  FR-ACC-001 is a MUST and it names eleven types, not the six `AccountEntity`'s doc comment
 *       guessed at in issue 1.6. The guess also included `wallet`, which appears nowhere in the SRS.
 *       Getting this list right matters more than it looks: `account.type` is a plain TEXT column
 *       with no CHECK constraint in Room, so nothing but this enum stops a typo becoming a row that
 *       every later query silently misses.
 * What: the closed set, each carrying the exact string §20.2's DDL stores.
 * Result: an account's kind is a compile-time value everywhere above the database.
 * Changelog: 2026-07-28 — Created for issue 2.5 (FR-ACC-001).
 *
 * **[storedValue] is the persisted contract, not [name].** They differ for [CREDIT_CARD], and
 * writing `name.lowercase()` would store `credit_card` today and break the day someone renames the
 * constant. The column holds [storedValue]; nothing else.
 *
 * **Type-specific detail is deliberately absent.** A credit card's limit and statement day
 * (FR-ACC-002) and a loan's principal, rate and EMI (FR-ACC-003) live in their own tables, built by
 * issues 3.1 and 3.2. This issue gives every type a row it can hang off.
 *
 * **Asset vs liability is [isLiability]** (added by issue 2.6, which 2.5 deferred it to).
 */
enum class AccountType(val storedValue: String) {
    /** Savings or current account at a bank. */
    BANK("bank"),

    /** Physical cash the user is tracking. */
    CASH("cash"),

    /** A credit card. A liability: its balance is what is owed, so it is held negative. */
    CREDIT_CARD("credit_card"),

    /** Home, vehicle, personal or education loan (FR-ACC-003). Also a liability. */
    LOAN("loan"),

    /** MF, stocks, FD, RD, PPF, EPF, NPS — anything with units and a market value. */
    INVESTMENT("investment"),

    /** Physical, digital or SGB gold (FR-ACC-004). */
    GOLD("gold"),

    /** Crypto holdings. */
    CRYPTO("crypto"),

    /** Property, valued rather than transacted. */
    PROPERTY("property"),

    /** A vehicle, as an asset. Its running costs are §12's, not this row's. */
    VEHICLE("vehicle"),

    /** Informal lending: money owed **to** the user. */
    RECEIVABLE("receivable"),

    /** Informal lending: money the user owes. */
    PAYABLE("payable"),
    ;

    /**
     * Whether this kind of account is something the user **owes** (issue 2.6; FR-ACC-005).
     *
     * Why:    FR-ACC-005 is "net worth MUST equal assets − liabilities", and that sentence needs a
     *         rule for which side each account falls on. **The arithmetic does not:** balances are
     *         already signed, so a plain sum of every balance is already assets − liabilities.
     *         What this exists for is P-02 — a screen that can only say "₹3.1L" and not "assets
     *         ₹3.3L, you owe ₹18k" cannot show its work, and the two subtotals need the partition.
     *
     *         **Classification is by type, never by sign.** An overdrawn bank account is an asset
     *         with a negative value; a credit card paid past zero is a liability with a positive
     *         one. Reading the sign instead would silently reclassify both, which is the bug this
     *         doc comment exists to prevent — `NetWorthEngineTest` pins both cases.
     *
     *         [RECEIVABLE] is an **asset**: it is money owed *to* the user. Only [PAYABLE] is the
     *         other direction.
     * Result: `true` for the three types the user owes on, `false` for the other eight.
     * Changelog: 2026-08-01 — Created for issue 2.6 (FR-ACC-005), which 2.5 deferred it to.
     */
    val isLiability: Boolean
        get() = this == CREDIT_CARD || this == LOAN || this == PAYABLE

    companion object {
        /**
         * Parses a stored type string.
         * Why:    a row written by a newer build may carry a type this one has never heard of, and
         *         the honest response is to skip that account rather than crash the list. Same
         *         forward-compatible shape as `BudgetEntity.toEnvelope()` in `:data:repository`:
         *         **an old build reading a newer database shows fewer rows, not an exception.**
         * Result: the matching type, or `null` when the value is unknown or malformed.
         * Input:  [stored] — the value from `account.type`.
         * Output: `AccountType?`.
         * Changelog: 2026-07-28 — Created for issue 2.5.
         */
        fun fromStored(stored: String): AccountType? = entries.firstOrNull { it.storedValue == stored }
    }
}

/**
 * One account, as everything above the data layer sees it (issue 2.5; ARC-005, FR-ACC-001).
 *
 * Why:  ARC-005 forbids a ViewModel from ever holding a Room type, so the accounts screen needs a
 *       model of its own. It is not a copy of `AccountEntity`: the entity stores a
 *       `current_balance_minor` column, and this carries [balance] **derived** from the opening
 *       balance plus the account's live transactions. DB-001 is explicit that the current balance
 *       is *derivable* and "never mutated ad hoc", so the derived figure is the truth and the
 *       column is a cache. Recorded in
 *       `docs/adr/0007-account-balances-derived-not-stored.md`.
 * What: the identity, kind, and both money figures a screen needs.
 * Result: a screen can render an account, and a test can construct one, without Room.
 * Changelog: 2026-07-28 — Created for issue 2.5.
 *
 * **[isArchived] rather than an `archivedAt` instant.** FR-ACC-007 asks for archived accounts to
 * retain history and drop out of active totals; *when* it happened is stored, but no screen has a
 * use for the timestamp, and exposing it would invite someone to do calendar arithmetic on it
 * outside the injected `Clock` (TIM-001).
 *
 * Input:  [id]; [profileId] — the scope every row carries; [name] — the user's own label;
 *         [type]; [institution] — the bank or issuer, optional and free text; [openingBalance] —
 *         what the account held when the user started tracking it; [balance] — that plus every
 *         live transaction against it; [currencyCode] — ISO-4217; [isArchived] — FR-ACC-007;
 *         [includeInNetWorth] — FR-ACC-005, issue 2.6.
 * Output: an immutable value.
 */
data class Account(
    val id: String,
    val profileId: String,
    val name: String,
    val type: AccountType,
    val institution: String?,
    val openingBalance: Money,
    val balance: Money,
    val currencyCode: String,
    val isArchived: Boolean,
    /**
     * Whether this account counts towards net worth (issue 2.6; FR-ACC-005).
     *
     * Separate from [isArchived]: an archived account is closed, this one is open and transacting
     * but is not the user's to count — a company card, or an account held for someone else.
     */
    val includeInNetWorth: Boolean = true,
)
