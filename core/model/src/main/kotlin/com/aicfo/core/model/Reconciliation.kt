package com.aicfo.core.model

/**
 * The outcome of aligning an account with a real statement (issue 2.7; FR-ACC-006, P-02).
 *
 * Why:  FR-ACC-006 requires a balance correction to be posted as an adjustment *transaction*,
 *       "never silently mutated" — so the interesting result of reconciling is not the new balance
 *       but **the three figures that explain it**. P-02 says every output shows its inputs and the
 *       rule that fired; a return type of `Unit` would leave the screen re-deriving the delta it
 *       just asked the store to compute, which is the classic way the number shown and the number
 *       written come to disagree.
 * What: the balance before, the statement the user typed, the difference between them, and the id
 *       of the row that was written to close the gap.
 * Result: a screen can say "₹92,500 → ₹93,000, adjustment +₹500" without doing any money maths of
 *       its own (P-03).
 * Changelog: 2026-08-02 — Created for issue 2.7 (FR-ACC-006).
 *
 * **[delta] is `statement − before`, always.** Positive is an inflow, negative an outflow, matching
 * `transactions.amount_minor`'s sign convention. Computed with [Money]'s overflow-checked
 * arithmetic, so an absurd statement throws rather than wrapping to a fortune (MNY-001).
 *
 * **[adjustmentId] is `null` when [delta] is zero**, because nothing was written. A zero-amount
 * transaction is not a record of anything — it is noise every later engine would have to filter,
 * and it would make "has this account ever been reconciled?" unanswerable from the amount alone.
 *
 * **The rule that fired is the adjustment's `source`, not prose.** The written row carries
 * `source = "reconciliation"` and no note: a stored English sentence would be un-localised and would
 * duplicate an amount already in the column beside it.
 *
 * Input:  [accountId] — the account that was reconciled; [before] — its derived balance at the
 *         moment of reconciling (DB-001: `opening + SUM(live transactions)`, never the cached
 *         column); [statement] — what the user read off their statement; [delta] — the adjustment's
 *         signed amount, MNY-001 paise; [adjustmentId] — the transaction written, or `null`;
 *         [bookedOnIsoDate] — the profile-zone day it was booked on, ISO `yyyy-MM-dd` (TIM-002).
 * Output: an immutable value.
 */
data class Reconciliation(
    val accountId: String,
    val before: Money,
    val statement: Money,
    val delta: Money,
    val adjustmentId: String?,
    val bookedOnIsoDate: String,
) {
    /** Whether the app already agreed with the statement, so nothing needed writing. */
    val isAlreadyInStep: Boolean get() = adjustmentId == null
}
