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
 * **Asset vs liability is not modelled here either.** The sign of the balance already carries it —
 * a credit card holds a negative amount — and FR-ACC-005 (net worth = assets − liabilities) is
 * issue 2.6's to decide, with the full picture of what it needs.
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
 *         live transaction against it; [currencyCode] — ISO-4217; [isArchived] — FR-ACC-007.
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
)
