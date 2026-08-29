package com.aicfo.core.model

/**
 * What a credit card is, beyond being an account with a balance (issue 6.1; FR-ACC-002, §5.7).
 *
 * Why:  [Account] has held eleven types and one balance since issue 2.5, and its own doc comment
 *       has been promising this table since then: *"A credit card's limit and statement day
 *       (FR-ACC-002) … live in their own tables."* Adding these six fields to `account` instead
 *       would put six columns on the ten types that can never use them, and would do it again for
 *       every loan and holding field Epic 6 adds.
 *
 *       **The days are days-of-month, not dates, and that is the SRS's own shape** — FR-ACC-002
 *       says "statement day, due day". A card bills on the 5th every month; storing a date would
 *       mean rewriting a row twelve times a year and still having no answer for next February.
 *       Turning a day into a date is the card engine's job, not this type's — it is the part that
 *       has to clamp a day-31 card to the length of a short month.
 *
 *       **Current unbilled is absent on purpose**, though FR-ACC-002 names it. It is
 *       `outstanding − lastStatement`, and storing it would be a second version of a number the
 *       ledger already knows — the same argument `budget_alert` makes for storing no amounts. The
 *       engine derives it.
 * What: the card's terms and its last statement, per profile-scoped account.
 * Result: the input to every utilisation figure and every payment reminder in the app.
 * Changelog: 2026-08-17 — Created for issue 6.1 (FR-ACC-002).
 *
 * Input:  [accountId] — the [Account] this describes, one card per account;
 *         [creditLimit] — the sanctioned limit, `Long` paise (MNY-001), must be positive: a card
 *         with no limit has no utilisation, and zero would be a division by zero dressed as data;
 *         [statementDay], [dueDay] — 1..31, clamped to a short month when resolved;
 *         [lastStatement] — the amount on the most recent statement, `null` until one is recorded;
 *         [lastStatementIsoDate] — when that statement was cut, ISO `yyyy-MM-dd` (TIM-002);
 *         [minimumDue] — the minimum payment on that statement, `null` when unknown;
 *         [aprBps] — the purchase APR in integer basis points (MNY-002), `null` when not entered.
 * Output: an immutable value.
 */
data class CreditCard(
    val accountId: String,
    val creditLimit: Money,
    val statementDay: Int,
    val dueDay: Int,
    val lastStatement: Money? = null,
    val lastStatementIsoDate: String? = null,
    val minimumDue: Money? = null,
    val aprBps: Int? = null,
) {
    init {
        require(accountId.isNotBlank()) { "A card must belong to an account" }
        require(creditLimit > Money.ZERO) {
            "A credit limit must be positive — utilisation divides by it (was ${creditLimit.minor} paise)"
        }
        require(statementDay in FIRST_DAY_OF_MONTH..LAST_DAY_OF_MONTH) {
            "statementDay is a day of the month, 1..31, was $statementDay"
        }
        require(dueDay in FIRST_DAY_OF_MONTH..LAST_DAY_OF_MONTH) {
            "dueDay is a day of the month, 1..31, was $dueDay"
        }
        // A negative statement is a credit balance, which is real (a refund landed after the cut).
        // A negative *minimum due* is not: it would mean the card is asking to be paid a negative
        // amount, and it would reach a notification as "pay -₹500".
        require(minimumDue == null || minimumDue >= Money.ZERO) {
            "A minimum due cannot be negative, was ${minimumDue?.minor} paise"
        }
        require(aprBps == null || aprBps >= 0) {
            "APR is a non-negative rate in basis points (MNY-002), was $aprBps"
        }
    }
}

/**
 * The first and last day a card can bill on. Every month has at least 28 days and none has more
 * than 31; a card set to the 31st is clamped to the month's length by the engine, never here.
 *
 * File-level `const`s rather than a companion `val` range: a companion property compiles to a
 * getter that nothing ever calls (Kotlin reads the backing field directly), which is one line the
 * 100 % money-math gate can never cover.
 */
private const val FIRST_DAY_OF_MONTH = 1

/** @see FIRST_DAY_OF_MONTH */
private const val LAST_DAY_OF_MONTH = 31
