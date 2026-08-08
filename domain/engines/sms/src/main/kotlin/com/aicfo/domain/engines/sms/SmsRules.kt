package com.aicfo.domain.engines.sms

import com.aicfo.core.model.RuleCitation

/**
 * The gates this parser applies, copied from `ai/rules/rules-kb.json` (ADR-0005).
 *
 * Why:  CLAUDE.md §6 says a financial threshold is a **data row in `ai/`, never a hardcoded
 *       number** — and these are hardcoded numbers. The same deliberate, recorded deferral ADR-0005
 *       made for `QuickSetupRules`, `RecurringRules` and `ReceiptRules`: nothing in the app loads
 *       `ai/` yet, so honouring §6 literally would mean this issue first building an asset pipeline
 *       and a rulebook parser. `RulebookDriftTest` closes the gap that matters — edit a keyword in
 *       the rulebook and the build goes red until this file agrees.
 * What: one field per `RULE-SMS-PARSE` parameter, each carrying the rule id it came from.
 * Result: every word this parser trusts or refuses is attributable to a rulebook row a reviewer can
 *       open — which matters more here than for any other engine, because the keyword lists *are*
 *       the algorithm. Changing `ignore_keywords` changes what the app records as the user's money.
 * Changelog: 2026-08-07 — Created for issue 3.9 from rules-kb.json v1.8.0.
 *
 * **Injected rather than read, so a test can move a keyword** and assert the parser moves with it —
 * which is also the seam the real loader will use when it lands, with no change to the engine.
 *
 * **Every keyword here is matched as a whole word, never as a substring** (see
 * `DefaultSmsEngine.containsWord`). That is what lets [balanceKeywords] hold the single row `bal`
 * and cover `Avl Bal`, `Bal:`, `Bal Rs` and `A/c Bal` at once — a substring search for the same
 * three letters would find them inside `GLOBAL`, and a merchant of that name would turn its own
 * amount into a balance and vanish from the ledger. It is also why `unpaid` does not fire
 * [debitKeywords]' `paid`.
 *
 * Input:  [debitKeywords] / [creditKeywords] — the verbs an alert uses for money leaving and
 *         arriving, lower-cased; the earliest one to appear in the message decides the direction;
 *         [ignoreKeywords] — the words that disqualify a message outright, lower-cased, and the
 *         single most important list here (see [SmsEngine]); [accountMarkerKeywords] — the tokens a
 *         real alert uses to name the account it is about, lower-cased; a message that names no
 *         account is not an alert about one; [balanceKeywords] — the labels that mark the figure
 *         after them as the *balance* rather than the amount that moved, lower-cased;
 *         [rejectNumericSenders] — whether a sender consisting only of digits is refused, which is
 *         how a forwarded message or a scam text from a personal number is kept out;
 *         [lowConfidenceBps] — the floor below which the review screen flags a draft, in basis
 *         points (MNY-002, so no `Float` reaches the engine); [duplicateAmountTolerancePct] and
 *         [duplicateDateToleranceDays] — the band within which an alert is offered as a merge into
 *         an existing row instead of a second one, read by the repository rather than by this
 *         engine, but held here so both halves of the rule cite one row.
 * Output: an immutable value.
 */
data class SmsRules(
    /** RULE-SMS-PARSE `debit_keywords`. `sent` is how every UPI app words an outgoing payment. */
    val debitKeywords: List<String> = listOf("debited", "spent", "withdrawn", "paid", "purchase", "sent"),
    /** RULE-SMS-PARSE `credit_keywords`. */
    val creditKeywords: List<String> = listOf("credited", "received", "deposited", "refund"),
    /** RULE-SMS-PARSE `ignore_keywords` — the refusals; see [SmsEngine]. */
    val ignoreKeywords: List<String> =
        listOf(
            "otp",
            "one time password",
            "will be debited",
            "will be credited",
            "will be deducted",
            "pre-approved",
            "loan offer",
            "apply now",
            "offer ends",
            "congratulations",
            // A declined payment alert quotes the amount that did *not* move, beside a real
            // merchant and a real card. Without these three it is indistinguishable from a purchase.
            "declined",
            "failed",
            "unsuccessful",
        ),
    /** RULE-SMS-PARSE `account_marker_keywords`. */
    val accountMarkerKeywords: List<String> = listOf("a/c", "acct", "account", "ending", "xx"),
    /** RULE-SMS-PARSE `balance_keywords` — the figure after one of these is the balance, not the spend. */
    val balanceKeywords: List<String> = listOf("bal", "balance", "limit", "lmt", "due", "outstanding"),
    /** RULE-SMS-PARSE `reject_numeric_senders` — a bank sends from a DLT header, never a mobile number. */
    val rejectNumericSenders: Boolean = true,
    /** RULE-SMS-PARSE `low_confidence_bps` — below this the review screen flags the draft. */
    val lowConfidenceBps: Int = 6_000,
    /** RULE-SMS-PARSE `duplicate_amount_tolerance_pct` — matches RULE-RECEIPT-PARSE's ±1%. */
    val duplicateAmountTolerancePct: Int = 1,
    /** RULE-SMS-PARSE `duplicate_date_tolerance_days` — matches RULE-RECEIPT-PARSE's ±1 day. */
    val duplicateDateToleranceDays: Long = 1L,
) {
    init {
        // A parser with no direction words would have nothing to decide debit from credit by, and
        // the gate would fall through to whichever branch was written first — the rulebook editing
        // itself into a coin toss about which way the user's money went.
        require(debitKeywords.isNotEmpty()) { "RULE-SMS-PARSE needs at least one debit keyword" }
        require(creditKeywords.isNotEmpty()) { "RULE-SMS-PARSE needs at least one credit keyword" }
        // Emptying this list is the one edit that turns the feature from conservative to dangerous:
        // every OTP and every loan advert quoting a figure would become a draft. It is not a
        // threshold that can be "tuned to zero" — an empty list is a different feature.
        require(ignoreKeywords.isNotEmpty()) { "RULE-SMS-PARSE needs at least one ignore keyword" }
        require(accountMarkerKeywords.isNotEmpty()) { "RULE-SMS-PARSE needs at least one account marker" }
        require(lowConfidenceBps in 0..BPS_FULL) {
            "lowConfidenceBps is basis points in 0..$BPS_FULL, was $lowConfidenceBps"
        }
        // A tolerance of 100% would make every transaction in the ledger a duplicate of every other,
        // and the merge offer would start proposing that a coffee and a rent payment are one thing.
        require(duplicateAmountTolerancePct in 0 until PCT_TOTAL) {
            "duplicateAmountTolerancePct is a whole percent under $PCT_TOTAL, was $duplicateAmountTolerancePct"
        }
        require(duplicateDateToleranceDays >= 0L) {
            "duplicateDateToleranceDays cannot be negative, was $duplicateDateToleranceDays"
        }
    }

    companion object {
        /** The rule every draft cites as its evidence (P-02, AI-ARC-006). */
        val TRANSACTION_PARSE = RuleCitation("RULE-SMS-PARSE", "1.0")
    }
}

/** 10 000 bps = 100% (MNY-002). */
internal const val BPS_FULL = 10_000

/** The whole of an amount, as the percent a tolerance is bounded by. */
internal const val PCT_TOTAL = 100
