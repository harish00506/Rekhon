package com.aicfo.core.model

import java.time.LocalDate
import java.time.format.DateTimeParseException

/**
 * What a loan is, beyond being an account with a negative balance (issue 6.2; FR-ACC-003, §5.8).
 *
 * Why:  [Account]'s own doc comment has been promising this table since issue 2.5 — *"a loan's
 *       principal, rate and EMI (FR-ACC-003) … live in their own tables"* — and until it existed the
 *       app could show what a loan is worth today but never what any part of an EMI was for.
 *       ADR-0016 records the same absence from the other side: nature classification decides a loan
 *       payment on the account *type*, because there was no amortisation to split principal from
 *       interest with.
 *
 *       **These five fields are the whole input to the schedule.** That is deliberate: the schedule
 *       is a pure function of them, so it is derived on demand and never stored (ADR-0026), the same
 *       argument [CreditCard] makes for not storing current unbilled and ADR-0007 makes for not
 *       storing an account balance. A stored schedule is a cache that can disagree with the terms
 *       that produced it, and the disagreement would be invisible.
 *
 *       **No EMI day of its own**, unlike [CreditCard]'s statement and due days. A loan bills on the
 *       same day of the month as its first instalment, so a separate column would be a second copy
 *       of [firstEmiIsoDate]'s day and a chance for the two to disagree.
 * What: the sanctioned terms, and the instalment the user's bank actually charges when it differs.
 * Result: the input to every EMI figure and every principal/interest split in the app.
 * Changelog: 2026-08-19 — Created for issue 6.2 (FR-ACC-003).
 *
 * Input:  [accountId] — the [Account] this describes, one loan per account;
 *         [principal] — the sanctioned amount, `Long` paise (MNY-001), must be positive: a loan of
 *         nothing has no schedule, and a negative one is a deposit wearing a loan's clothes;
 *         [annualRateBps] — the annual interest rate in integer basis points (MNY-002), `0` is
 *         legitimate (an interest-free family loan, a 0% consumer-durable EMI) and negative is not;
 *         [tenureMonths] — the number of instalments, 1..[MAX_TENURE_MONTHS];
 *         [firstEmiIsoDate] — when the first instalment falls, ISO `yyyy-MM-dd` (TIM-002);
 *         [emiOverride] — the instalment the lender actually charges, `null` to derive it. Banks
 *         round the closed-form figure their own way and a schedule that disagrees with the user's
 *         own statement by ₹2 a month is a schedule they will not trust; when present it must be
 *         positive.
 * Output: an immutable value.
 */
data class Loan(
    val accountId: String,
    val principal: Money,
    val annualRateBps: Int,
    val tenureMonths: Int,
    val firstEmiIsoDate: String,
    val emiOverride: Money? = null,
) {
    init {
        require(accountId.isNotBlank()) { "A loan must belong to an account" }
        require(principal > Money.ZERO) {
            "A loan's principal must be positive — it is what the schedule amortises (was ${principal.minor} paise)"
        }
        require(annualRateBps >= 0) {
            "A loan rate is non-negative basis points (MNY-002), was $annualRateBps"
        }
        require(tenureMonths in MIN_TENURE_MONTHS..MAX_TENURE_MONTHS) {
            "A tenure is a whole number of instalments, $MIN_TENURE_MONTHS..$MAX_TENURE_MONTHS, was $tenureMonths"
        }
        // Parsed rather than pattern-matched: the schedule seeds every instalment date off this one
        // by adding months to it, so "2026-02-30" has to be refused here or it becomes a failure
        // per row, 240 rows later, with nothing left to point at the cause.
        require(isCalendarDate(firstEmiIsoDate)) {
            "The first EMI date is an ISO yyyy-MM-dd calendar date (TIM-002), was '$firstEmiIsoDate'"
        }
        require(emiOverride == null || emiOverride > Money.ZERO) {
            "An EMI the user has entered must be positive, was ${emiOverride?.minor} paise"
        }
    }
}

/**
 * Whether a string is a date this build can resolve.
 * Why:    `LocalDate.parse` signals failure by throwing, and a `require` needs a boolean. Keeping
 *         the `try` here rather than inline in `init` keeps the constructor readable and gives the
 *         one place that decides what "a date" means for a loan.
 * Result: `true` for an ISO `yyyy-MM-dd` that names a day that exists, `false` otherwise.
 * Input:  [text] — the candidate. Output: [Boolean].
 * Changelog: 2026-08-19 — Created for issue 6.2.
 */
private fun isCalendarDate(text: String): Boolean =
    try {
        LocalDate.parse(text)
        true
    } catch (_: DateTimeParseException) {
        false
    }

/**
 * The shortest and longest tenures a loan may carry.
 *
 * The ceiling is 50 years — longer than any Indian retail loan on offer, and low enough that a
 * mistyped tenure cannot ask the engine for a hundred thousand rows. File-level `const`s for the
 * same reason [CreditCard]'s day bounds are: a companion property compiles to a getter nothing
 * calls, which is one line the 100% money-math gate can never cover.
 */
private const val MIN_TENURE_MONTHS = 1

/** @see MIN_TENURE_MONTHS */
private const val MAX_TENURE_MONTHS = 600
