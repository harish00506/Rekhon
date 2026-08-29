package com.aicfo.core.model

/**
 * What kind of thing a holding is, for allocation and diversification (issue 6.3; §11.2, AI-INV).
 *
 * Why:  §11.2 measures allocation "across equity / debt / gold / real-estate / cash / crypto", and
 *       [AccountType] cannot express that. One `INVESTMENT` account — a broker, an MF folio —
 *       legitimately holds an equity fund *and* a liquid debt fund, so deriving the class from the
 *       account would put both in one bucket and make issue 6.4's headline number structurally
 *       wrong, not merely imprecise. `RULE-AGE-EQUITY` needs equity separated from debt or it has
 *       nothing to compare its band against.
 * What: the closed set, each carrying the exact string `investment_holding.asset_class` stores,
 *       plus [defaultFor] — the mapping issue 6.4 uses for accounts that hold value without being
 *       lot-tracked (a savings balance, a property).
 * Result: a holding's kind is a compile-time value everywhere above the database.
 * Changelog: 2026-08-24 — Created for issue 6.3 (§11.2). See ADR-0027 for why this is a column on
 *            the holding rather than a derivation from the account type.
 *
 * **[storedValue] is the persisted contract, not [name].** The column has no CHECK constraint, so
 * nothing but this enum stops a typo becoming a row every later query silently misses — the trap
 * [AccountType]'s doc comment describes, and the reason a free-text column was rejected.
 *
 * **Two of these strings are already load-bearing.** `ai/rules/rules-kb.json` shipped
 * `RULE-GOLD-CAP` with `params_json.asset_class == "gold"` and `RULE-CRYPTO-CAP` with `"crypto"`
 * before any Kotlin consumed them. `AssetClassTest` pins both so issue 6.4 cannot discover a
 * mismatch at the point it tries to cap a class that matches no holding.
 */
enum class AssetClass(val storedValue: String) {
    /** Shares and equity mutual funds — the growth engine `RULE-AGE-EQUITY` bands. */
    EQUITY("equity"),

    /** FD, RD, PPF, EPF, bonds, debt and liquid funds — anything with a stated return. */
    DEBT("debt"),

    /** Physical, digital or SGB gold (FR-ACC-004). Capped by `RULE-GOLD-CAP`. */
    GOLD("gold"),

    /** Crypto holdings. Capped by `RULE-CRYPTO-CAP`. */
    CRYPTO("crypto"),

    /** Bank balances and cash — the denominator's liquid share, and §10's runway. */
    CASH("cash"),

    /** Property, valued rather than transacted. */
    REAL_ESTATE("real_estate"),

    /** A vehicle, a receivable — value the user owns that fits no investment class. */
    OTHER("other"),
    ;

    companion object {
        /**
         * Resolves a stored string back to a class.
         * Why:    forward compatibility, for the reason [AccountType.fromStored] gives: a row
         *         written by a newer build must be skipped, never crash the list reading it.
         * Result: the matching class, or `null` when this build does not know the value.
         * Input:  [stored] — the raw `asset_class` column value. Output: [AssetClass] or `null`.
         * Changelog: 2026-08-24 — Created for issue 6.3.
         */
        fun fromStored(stored: String): AssetClass? = entries.firstOrNull { it.storedValue == stored }

        /**
         * The class an account's own type implies.
         * Why:    two callers need this and neither should own it. The account editor needs a
         *         sensible default when the user adds a holding, and issue 6.4's allocation engine
         *         needs a class for accounts that hold value without lots at all — a savings
         *         balance is cash, a property is real estate. Left to each caller, the editor and
         *         the engine would drift and the allocation percentages would stop summing to what
         *         the account list shows.
         * What:   a total mapping over all eleven [AccountType]s. For `INVESTMENT` it is a
         *         *default the user can change*, not a derivation — which is precisely why the
         *         holding carries its own column.
         * Result: the implied class, or `null` for a liability. `null` is not "unknown": a debt has
         *         no asset class and must be excluded from the allocation denominator rather than
         *         filed under [OTHER], which would understate every other class's share.
         * Input:  [type] — the account's kind. Output: [AssetClass] or `null`.
         * Changelog: 2026-08-24 — Created for issue 6.3, as issue 6.4's groundwork.
         */
        fun defaultFor(type: AccountType): AssetClass? =
            when (type) {
                AccountType.BANK, AccountType.CASH -> CASH
                AccountType.INVESTMENT -> EQUITY
                AccountType.GOLD -> GOLD
                AccountType.CRYPTO -> CRYPTO
                AccountType.PROPERTY -> REAL_ESTATE
                AccountType.VEHICLE, AccountType.RECEIVABLE -> OTHER
                AccountType.CREDIT_CARD, AccountType.LOAN, AccountType.PAYABLE -> null
            }
    }
}
