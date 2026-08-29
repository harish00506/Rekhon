package com.aicfo.core.model

/**
 * The identifier a market-data proxy resolves to a price (issue 6.5; §16 EXT-003, §22.2).
 *
 * Why:  a holding's *name* is the user's own label — "Parag Parikh Flexi Cap", "my gold" — and is
 *       useless as a lookup key. Something stable and machine-readable has to name the instrument,
 *       and `Entities.kt` reserved the column for exactly this: *"No `symbol` or ISIN. Issue 6.5
 *       needs a price key and adds it with its own migration."*
 *
 *       **The character set is the privacy control, not decoration.** EXT-003 says a market-data
 *       request carries "zero personal financial data — only instrument identifiers". A free-text
 *       column could carry a note, a nickname, an amount, or anything else the user typed, and any
 *       of those would then be transmitted. Refusing everything outside `[a-z0-9._:-]` makes that
 *       impossible to do by accident: what leaves the device is provably an identifier, because
 *       nothing else can be constructed. A rule enforced by a type is one nobody has to remember.
 * What: a validated lowercase identifier, namespaced by what kind of thing it prices.
 * Result: the value stored in `investment_holding.price_key` and the only thing a price request
 *         may contain.
 * Changelog: 2026-08-29 — Created for issue 6.5 (§16, FR-ACC-004).
 *
 * Namespaced `<kind>:<identifier>` by convention so a reader can tell at a glance what a key prices
 * and so two namespaces cannot collide:
 *
 * ```
 * gold:inr.gram.24k     one gram of 24-carat gold, in rupees
 * crypto:btc.inr        one bitcoin, in rupees
 * mf:INF109K01Z48       a mutual fund, by ISIN
 * ```
 *
 * The convention is deliberately **not** enforced beyond the character set. A namespace list here
 * would have to change every time the proxy learns a new instrument type, and this class has no way
 * to know what the proxy supports — an unknown key simply returns no quote, which is the right
 * failure and needs no validation to produce.
 *
 * @property value the identifier, lowercase, 1–64 characters of `a-z`, `0-9`, `.`, `_`, `:` or `-`.
 */
@JvmInline
value class PriceKey(val value: String) {
    init {
        require(value.matches(PATTERN)) {
            "A price key is 1-$MAX_LENGTH characters of a-z, 0-9, '.', '_', ':' or '-' — anything " +
                "else could carry something the user typed about themselves, and a price request " +
                "must contain only instrument identifiers (EXT-003). Was '$value'"
        }
    }

    /** Result: the identifier itself, so logging or joining a key cannot leak a wrapper. */
    override fun toString(): String = value

    companion object {
        /** The longest key accepted. An ISIN is 12 characters; 64 is room without being unbounded. */
        const val MAX_LENGTH = 64

        /**
         * Lowercase alphanumerics and the four separators the namespacing convention uses.
         *
         * No uppercase, so two keys cannot differ only by case and silently miss each other in the
         * `DISTINCT price_key` query. No spaces, so a key cannot be a sentence.
         */
        private val PATTERN = Regex("[a-z0-9._:-]{1,$MAX_LENGTH}")
    }
}
