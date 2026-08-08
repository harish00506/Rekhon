package com.aicfo.domain.engines.sms

import com.aicfo.core.common.AppError
import com.aicfo.core.common.Result
import com.aicfo.core.common.runCatchingToResult
import com.aicfo.core.model.EngineProvenance
import com.aicfo.core.model.Money
import com.aicfo.core.model.MoneyFormatter
import java.time.LocalDate

/**
 * The production [SmsEngine] — a ladder of refusals (issue 3.9; SRS §18, §23, MNY-001).
 *
 * Why:  read top to bottom, [parse] is five reasons to return `null` followed by one way to return a
 *       draft, and that order is the design rather than a style choice. The tempting implementation
 *       is the opposite shape — find an amount, find a verb, build a draft, then try to filter out
 *       the bad ones — and it fails on the message that matters most: `Your OTP for a transaction of
 *       Rs 5,000 on card XX4521 is 448210`. That message has an amount, a card, and (in `transaction`)
 *       something that looks like a verb. Only a gate that runs *before* any of that is looked at
 *       keeps it out, which is why [ignored] is checked second, ahead of everything expensive.
 *
 *       The one judgement the whole parser turns on: **the first currency-marked amount that is not
 *       a balance is the amount that moved.** Indian alerts are written in one order — the figure,
 *       then the account, then the balance — and taking the largest figure instead (the receipt
 *       parser's rule) would read `Avl Bal Rs.45,320.10` as a ₹45,320 purchase on almost every
 *       message the app sees. A receipt's largest number usually *is* the total; an alert's largest
 *       number is usually the balance. Same problem, opposite answer.
 * What: sender gate → ignore gate → direction gate → account-marker gate → amount gate → draft.
 * Result: a proposal for the review screen, or nothing. Nothing here writes (P-07).
 * Changelog: 2026-08-07 — Created for issue 3.9.
 *
 * `internal` per ARC-003 — constructed only by [SmsEngineFactory].
 *
 * **There is not a `Double` in this file** (MNY-001). Every amount comes out of
 * [MoneyFormatter.parse], which refuses anything not exactly representable in paise rather than
 * rounding it, and every confidence is integer basis points (MNY-002).
 *
 * **Nothing here reads a clock** (TIM-001). The booking date is the received date the caller
 * converted, which is what makes the eval set reproducible (P-08).
 */
internal class DefaultSmsEngine : SmsEngine {
    override fun parse(input: SmsInput): Result<SmsDraftFields?, AppError> =
        runCatchingToResult {
            // Parsed first and outside every gate: a caller that supplied a malformed date is a
            // programming error, and it must fail as one rather than silently producing a draft
            // booked on a day nobody chose. Parsed for its exception, then used as text (TIM-002).
            val bookedOn = LocalDate.parse(input.receivedOnIsoDate).toString()
            val rules = input.rules
            val body = input.message.body
            val lowered = body.lowercase()

            if (!senderLooksLikeABank(input.message.sender, rules)) {
                null
            } else if (ignored(lowered, rules)) {
                null
            } else {
                val direction = direction(lowered, rules)
                val spendable = spendableAmounts(body, lowered, rules)
                if (direction == null || !namesAnAccount(lowered, rules) || spendable.isEmpty()) {
                    null
                } else {
                    draft(input, bookedOn, direction, spendable, lowered)
                }
            }
        }

    /**
     * Assembles the draft once every gate has passed.
     * Why:    split out of [parse] so the gate ladder reads as a ladder — detekt's 40-line ceiling
     *         (§21.6) would otherwise be met by squeezing the gates together, which is the one part
     *         of this file that must stay legible.
     * Result: the proposal, with the confidence the ambiguity of the message earned it.
     * Input:  [input]; [bookedOn] — the validated ISO day; [direction]; [spendable] — the non-balance
     *         amounts, in the order printed, guaranteed non-empty; [lowered] — the lower-cased body.
     * Output: [SmsDraftFields].
     * Changelog: 2026-08-07 — Created for issue 3.9.
     */
    private fun draft(
        input: SmsInput,
        bookedOn: String,
        direction: SmsDirection,
        spendable: List<Money>,
        lowered: String,
    ): SmsDraftFields {
        // One figure that is not the balance is a clean read. Several means the alert also quoted a
        // fee, a cashback or a second leg, and which of them "the" transaction is becomes a guess —
        // so the draft is offered *below the rulebook's flag floor*, and the review screen marks it.
        val confidence = if (spendable.size == 1) CLEAR_CONFIDENCE else AMBIGUOUS_AMOUNT_CONFIDENCE
        return SmsDraftFields(
            amount = spendable.first(),
            direction = direction,
            bookedOn = bookedOn,
            counterparty = counterparty(input.message.body),
            accountTail = ACCOUNT_TAIL.find(lowered)?.groupValues?.get(1),
            provenance =
                EngineProvenance(
                    engineId = ENGINE_ID,
                    engineVersion = ENGINE_VERSION,
                    computedAtUtcMillis = input.nowUtcMillis,
                    evidence = listOf(SmsRules.TRANSACTION_PARSE),
                    inputWindow = bookedOn,
                    confidenceBps = confidence,
                ),
        )
    }

    /**
     * The cheapest gate, and the one no wording change can defeat (issue 3.9).
     * Why:    a bank in India sends from a registered alphabetic DLT header — `VM-HDFCBK`,
     *         `AD-ICICIB`, `JD-SBIINB`. A ten-digit number is a person, and a message from a person
     *         that reads like a bank alert is either a forward or a fraud; either way it is not
     *         evidence that the user's account moved. Checking this first also means a scam text
     *         crafted to hit every keyword never reaches the keyword code at all.
     * Result: `true` when the sender could be a bank. Governed by `reject_numeric_senders`, so the
     *         rulebook can turn the gate off for a user whose bank does something unusual.
     * Input:  [sender] — the originating address; [rules]. Output: `Boolean`.
     * Changelog: 2026-08-07 — Created for issue 3.9.
     */
    private fun senderLooksLikeABank(
        sender: String,
        rules: SmsRules,
    ): Boolean {
        if (sender.isBlank()) return false
        if (!rules.rejectNumericSenders) return true
        // The separators a dialled number is written with, so `+91 98765 43210` is still numeric.
        val bare = sender.filterNot { it == '+' || it == '-' || it == ' ' }
        return bare.any { it.isLetter() }
    }

    /**
     * The refusal gate (issue 3.9).
     * Why:    every word in `ignore_keywords` names a message that mentions an amount without one
     *         having moved. `otp` is the important one — an OTP text quotes the amount it authorises
     *         *and* the card it is for, so it passes every other gate in this file. `will be
     *         debited` is the second: a mandate reminder is a forecast, and recording it as a
     *         transaction would book money that has not left yet and will be booked again when it
     *         does.
     * Result: `true` when the message must be refused whatever else it contains.
     * Input:  [lowered] — the lower-cased body; [rules]. Output: `Boolean`.
     * Changelog: 2026-08-07 — Created for issue 3.9.
     */
    private fun ignored(
        lowered: String,
        rules: SmsRules,
    ): Boolean = rules.ignoreKeywords.any { lowered.containsWord(it) }

    /**
     * Decides which way the money went (issue 3.9).
     * Why:    **the earliest keyword wins**, because an alert states what happened before it
     *         elaborates: `Rs.500 debited ... towards your credit card payment` is a debit that
     *         mentions a card, not a credit. Scanning for "any debit word" and "any credit word"
     *         and preferring one of them by fiat would decide that message by the order the lists
     *         happen to be written in — a rulebook edit could then silently reverse the sign on the
     *         user's transaction.
     * Result: the direction, or `null` when the message names no movement at all, which is the
     *         gate that keeps a balance summary or a statement notice out.
     * Input:  [lowered] — the lower-cased body; [rules]. Output: `SmsDirection?`.
     * Changelog: 2026-08-07 — Created for issue 3.9.
     */
    private fun direction(
        lowered: String,
        rules: SmsRules,
    ): SmsDirection? {
        val debit = rules.debitKeywords.earliestIndexIn(lowered)
        val credit = rules.creditKeywords.earliestIndexIn(lowered)
        return when {
            debit == null && credit == null -> null
            credit == null -> SmsDirection.DEBIT
            debit == null -> SmsDirection.CREDIT
            debit <= credit -> SmsDirection.DEBIT
            else -> SmsDirection.CREDIT
        }
    }

    /**
     * Requires the message to name the account it is about (issue 3.9).
     * Why:    a real alert always does — `A/c XX4521`, `card ending 8890` — because the bank has to
     *         tell a customer with four accounts which one moved. A promotional message almost never
     *         does, because the sender does not know. It is a weak signal on its own and a good one
     *         in combination, which is the only way it is used here.
     * Result: `true` when the message names an account. Input: [lowered]; [rules]. Output: `Boolean`.
     * Changelog: 2026-08-07 — Created for issue 3.9.
     */
    private fun namesAnAccount(
        lowered: String,
        rules: SmsRules,
    ): Boolean = rules.accountMarkerKeywords.any { lowered.containsWord(it) }

    /**
     * Finds the amounts that moved, discarding the ones that are balances (issue 3.9).
     *
     * Why:    this is the function that stops the app recording the user's bank balance as a
     *         purchase, which is the single most damaging thing it could do — it is the largest
     *         figure in the message, it appears in nearly every alert, and a reviewer skimming a
     *         draft list would see a plausible number rather than an obvious mistake.
     *
     *         **An amount is a balance if a balance keyword sits between it and the previous
     *         amount** (or the start of the message, for the first one). That window is derived from
     *         the text rather than being a fixed number of characters back: `Rs.1,250.00 debited
     *         from A/c XX4521. Avl Bal: Rs.45,320.10` and `Rs.500 spent on card XX12 Avl Bal
     *         Rs.4,500` have the label at completely different distances, and both are read
     *         correctly by asking "what was said since the last figure?".
     *
     *         Only **currency-marked** amounts are candidates. Unlike a receipt, an alert always
     *         prints `Rs`/`INR`/`₹` beside a real amount, so there is no need for the receipt
     *         parser's bare-integer tier — and dropping it removes the whole class of mistake where
     *         a reference number or an OTP becomes money.
     * Result: the spendable amounts in printed order; empty when the message quoted only a balance
     *         or no money at all, which both mean "not a transaction".
     * Input:  [body] — the message as received, for the match positions; [lowered] — the same text
     *         lower-cased, so the keyword search is case-insensitive without re-lowering per match;
     *         [rules]. Output: `List<Money>`.
     * Changelog: 2026-08-07 — Created for issue 3.9.
     */
    private fun spendableAmounts(
        body: String,
        lowered: String,
        rules: SmsRules,
    ): List<Money> {
        var windowStart = 0
        val spendable = mutableListOf<Money>()
        MARKED_AMOUNT.findAll(body).forEach { match ->
            val window = lowered.substring(windowStart, match.range.first)
            val amount = MoneyFormatter.parse(match.groupValues[1])
            if (rules.balanceKeywords.none { window.containsWord(it) } && amount != null && amount > Money.ZERO) {
                spendable += amount
            }
            windowStart = match.range.last + 1
        }
        return spendable
    }

    /**
     * Reads the payee or payer, best-effort (issue 3.9).
     * Why:    the alert names it in one of a handful of shapes — `to SWIGGY`, `at BIG BAZAAR`,
     *         `VPA merchant@okhdfcbank`, `credited by ACME PAYROLL` — and stops at a reference
     *         number or a date. **Best-effort on purpose**: the counterparty is the field the user
     *         can supply better than any regex, and unlike the amount, getting it wrong costs a
     *         label rather than a false record. So this refuses rather than reaches: no match means
     *         an empty field the review screen asks about, not a guess it presents as read.
     * Result: the counterparty as printed, or `null`.
     * Input:  [body] — the message as received, case preserved because the name is shown to the user.
     * Output: `String?`.
     * Changelog: 2026-08-07 — Created for issue 3.9.
     */
    private fun counterparty(body: String): String? =
        COUNTERPARTY.find(body)
            ?.groupValues
            ?.get(1)
            ?.trim()
            ?.trimEnd('.', ',', ';', '-')
            ?.takeIf { it.length >= MIN_COUNTERPARTY_LENGTH }

    private companion object {
        /** Stable identifier stored with every draft (AI-ARC-003). */
        const val ENGINE_ID = "sms-parser"

        /**
         * Bump whenever the gates change (AI-ARC-006).
         *
         * Stored with every draft, so a transaction accepted from an alert today can still be
         * explained after the parser is rewritten — which is the whole reason the field exists.
         */
        const val ENGINE_VERSION = "1.0"

        /** One figure, and it was not the balance: the alert said what moved. */
        const val CLEAR_CONFIDENCE = 9_000

        /**
         * The alert quoted more than one non-balance figure — a fee, a cashback, a second leg.
         *
         * **Deliberately below `SmsRules.lowConfidenceBps` (6 000)**, so the review screen flags it.
         * A guess presented as a reading is the failure mode this whole issue is arranged to avoid.
         */
        const val AMBIGUOUS_AMOUNT_CONFIDENCE = 5_500

        /** Below this, a captured name is punctuation or a stray word rather than a payee. */
        const val MIN_COUNTERPARTY_LENGTH = 3
    }
}

/**
 * The first position at which any of these keywords appears (issue 3.9).
 * Why: [DefaultSmsEngine.direction] compares "how early is the earliest debit word" against the
 *         same for credit, and doing that inline twice would put the tie-breaking rule in two
 *         places — the one piece of logic here that decides the *sign* of the user's transaction.
 * Result: the lowest index, or `null` when none of them appears.
 * Input:  the receiver — the keywords, lower-cased; [text] — the lower-cased body. Output: `Int?`.
 * Changelog: 2026-08-07 — Created for issue 3.9.
 */
private fun List<String>.earliestIndexIn(text: String): Int? = mapNotNull { text.indexOfWord(it) }.minOrNull()

/**
 * Whether the text contains this keyword **as a whole word** (issue 3.9).
 *
 * Why:    a plain `in` would be shorter and is wrong in both directions. It finds `bal` inside
 *         `GLOBAL`, so a real purchase at a shop of that name would have its amount discarded as a
 *         balance and never reach the user. It finds `paid` inside `unpaid`, turning a dunning
 *         notice into a payment. Every keyword list in [SmsRules] is matched through here, so the
 *         rule is stated once — and so a rulebook editor can add a short word like `bal` without
 *         having to reason about which longer words contain it.
 *
 *         A word here ends at anything that is not a letter, which is deliberately looser than
 *         `\b`: `a/c` and `pre-approved` are single keywords containing punctuation, and digits are
 *         a boundary so `xx` matches `XX4521`.
 * Result: `true` when the keyword appears bounded by non-letters on both sides.
 * Input:  the receiver — the lower-cased haystack; [keyword] — a lower-cased keyword.
 * Output: `Boolean`.
 * Changelog: 2026-08-07 — Created for issue 3.9.
 */
private fun String.containsWord(keyword: String): Boolean = indexOfWord(keyword) != null

/**
 * Where this keyword first appears as a whole word (issue 3.9).
 * Why: [DefaultSmsEngine.direction] needs the *position* and [String.containsWord] needs only
 *         whether there is one, and both must apply the identical boundary rule — a direction
 *         decided by a looser match than the gates use would be a sign flip nobody could reproduce.
 * Result: the index, or `null` when the keyword does not appear as a word.
 * Input:  the receiver — the lower-cased haystack; [keyword] — a lower-cased keyword.
 * Output: `Int?`.
 * Changelog: 2026-08-07 — Created for issue 3.9.
 */
private fun String.indexOfWord(keyword: String): Int? {
    if (keyword.isEmpty()) return null
    var from = 0
    while (true) {
        val at = indexOf(keyword, from)
        if (at < 0) return null
        val end = at + keyword.length
        val openLeft = at == 0 || !this[at - 1].isLetter()
        val openRight = end == length || !this[end].isLetter()
        if (openLeft && openRight) return at
        from = at + 1
    }
}

/**
 * An amount with a currency marker: `Rs.1,250.00`, `INR 1250`, `₹1,23,456.78`.
 *
 * Why:  the marker is not optional in a bank alert, so — unlike the receipt parser — this is the
 *       *only* amount pattern here. That single restriction removes reference numbers, OTP codes,
 *       card digits, dates and percentages from consideration in one step, without any of the
 *       lookarounds `DECIMAL_AMOUNT` needs on a receipt.
 *
 *       `rs\.?` matches the `Rs.` that Indian alerts overwhelmingly print, and the `(?<![a-z])`
 *       guard is what keeps it from firing inside a word — without it, `hours` would end `rs` and a
 *       following number would become money. Up to two fraction digits, because `MoneyFormatter`
 *       refuses anything finer than paise and a third digit means the match was not an amount.
 * Changelog: 2026-08-07 — Created for issue 3.9.
 */
private val MARKED_AMOUNT =
    Regex("""(?<![a-z])(?:₹|rs\.?|inr)\s*([0-9][0-9,]*(?:\.[0-9]{1,2})?)(?![0-9])""", RegexOption.IGNORE_CASE)

/**
 * The masked digits an alert quotes for the account or card: `A/c XX4521`, `card ending 8890`.
 * Why:  shown to the user so they can tell which of their accounts the draft belongs to, and used by
 *       nothing else — the app does not store account numbers, so this is never matched against one.
 *       Matched on the lower-cased body, hence the lower-case `x`.
 */
private val ACCOUNT_TAIL = Regex("""(?:x{2,}|ending\s+)([0-9]{2,6})(?![0-9])""")

/**
 * The payee, in the shapes an alert prints it (issue 3.9).
 * Why:  four prepositions cover almost every Indian bank and UPI template, and the trailing
 *       alternation is where the name *ends* — at a reference number, a date, a balance label or a
 *       sentence break. Stopping deliberately rather than running to the end of the message is what
 *       keeps `SWIGGY on 07-08-26 Ref 123456` from becoming a payee name nobody would recognise.
 *       The character class excludes digits at the start so `to 4521` is not read as a merchant.
 */
private val COUNTERPARTY =
    Regex(
        """\b(?:to|at|vpa|from)\s+([A-Za-z][A-Za-z0-9@&.'* -]{2,40}?)""" +
            """(?=\s+(?:on|ref|upi|txn|imps|neft|avl|avbl|bal|a/c|not|info|dated|from|via|using|towards)\b|[.;]|$)""",
        RegexOption.IGNORE_CASE,
    )
