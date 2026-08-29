package com.aicfo.domain.engines.sms

import com.aicfo.core.common.AppError
import com.aicfo.core.common.Result
import com.aicfo.core.model.EngineProvenance
import com.aicfo.core.model.Money
import com.aicfo.core.model.SmsMessage

/**
 * Decides whether one SMS describes money moving (issue 3.9; SRS §18, §23, P-01, P-07).
 *
 * Why:  the inbox this reads is not a feed of transactions — it is a feed of *messages about money*,
 *       most of which are not transactions at all. An OTP quotes the amount it is authorising. A
 *       mandate reminder says what *will* be debited next Tuesday. A loan advert quotes a figure
 *       larger than anything the user has ever spent. A balance summary quotes the balance. So the
 *       engine's job is stated the other way round from the receipt parser's: **its purpose is to
 *       refuse**, and the parse is what is left when nothing has refused it.
 *
 *       The asymmetry that sets every threshold: a missed alert costs the user one manual entry,
 *       which they were already doing before this feature existed. A false positive puts money in
 *       their ledger that never moved — silently, on a screen they were told they could trust, in an
 *       app whose whole claim is that the numbers come from arithmetic (P-03). One of those is an
 *       inconvenience and the other is a lie, so every ambiguous case resolves to `null`.
 *
 *       It is a separate module from `:data:sms` for the reason `:domain:engines:receipt` is
 *       separate from `:ml:ocr`: everything in `:data:sms` needs a device, a `ContentResolver` and a
 *       Play-restricted permission, and none of that can be gated in CI. This takes a message value,
 *       so the refusals can be frozen into a fixture and asserted on every build.
 * What: one method, from a message to a draft or to nothing.
 * Result: what the review screen offers the user; **never a saved row** (P-07).
 * Changelog: 2026-08-07 — Created for issue 3.9.
 *
 * Pure Kotlin (ARC-002), so the whole gate is provable on the JVM.
 */
interface SmsEngine {
    /**
     * Reads one message.
     * Why:    `Result` matching every other engine, though the realistic outcome for most messages
     *         is `Ok(null)` — "this is not a transaction" is the *ordinary* answer, not a failure,
     *         and modelling it as an error would make the common path an exception path. The `Err`
     *         branch is reserved for a malformed [SmsInput.receivedOnIsoDate], which is a bug in the
     *         caller rather than anything about the message.
     * What:   runs the gates in [SmsRules] order — sender, ignore words, direction, account marker,
     *         amount — and builds a draft only if every one of them passes.
     * Result: `Ok(fields)` for a bank alert, `Ok(null)` for everything else, `Err` for a caller bug.
     * Input:  [input] — the message, the rulebook thresholds and the caller's dates.
     * Output: `Result<SmsDraftFields?, AppError>`.
     */
    fun parse(input: SmsInput): Result<SmsDraftFields?, AppError>
}

/**
 * Everything the parser needs, as one value (issue 3.9).
 *
 * Why: [receivedOnIsoDate] and [nowUtcMillis] are **passed in, never read**: TIM-001 bans wall-clock
 *      reads in domain code and `CfoWallClockInDomain` fails the build on one. That is also what
 *      makes every case in the eval set reproducible (P-08) — the same message parsed today and in a
 *      year gives byte-identical output, provenance included.
 *
 *      The date is the day the *phone received the message*, converted in the profile zone by the
 *      caller, rather than a date parsed out of the body. Bank alerts arrive within seconds of the
 *      transaction, so the two agree in all but pathological cases; and a date read out of the text
 *      would be one more thing that can be read wrong, on the field that decides which month a
 *      budget is charged. See ENGINE.md for what this gives up.
 * Result: the argument to [SmsEngine.parse].
 * Changelog: 2026-08-07 — Created for issue 3.9.
 *
 * Input:  [message] — one message from the inbox; [receivedOnIsoDate] — the day it arrived in the
 *         profile zone, ISO `yyyy-MM-dd` (TIM-002); [nowUtcMillis] — the instant stamped into
 *         provenance (TIM-001); [rules] — the rulebook thresholds, injected so a test can move one
 *         (ADR-0005).
 * Output: an immutable value.
 */
data class SmsInput(
    val message: SmsMessage,
    val receivedOnIsoDate: String,
    val nowUtcMillis: Long,
    val rules: SmsRules = SmsRules(),
)

/**
 * What the parser concluded one alert describes (issue 3.9; FR-TXN-009).
 *
 * Why: [amount] and [direction] are **not** nullable while [counterparty] and [accountTail] are,
 *         and that split is the engine's contract rather than an accident. A draft without an amount
 *         or a direction is not a partial reading of a transaction — it is not a transaction, and
 *         the honest return for it is `null` from [SmsEngine.parse]. The other two are decoration
 *         the user can supply better than any regex can, so a missing payee costs a label, not a row.
 *
 *         Contrast with `ReceiptFields`, where every field is independently nullable: FR-OCR-003
 *         requires a partly-read receipt to still surrender its total, because the alternative is
 *         throwing away a photograph the user already took. There is no equivalent cost here — the
 *         message stays in the inbox and can be re-read for free.
 * Result: one row on the SMS review screen, pre-filled and fully editable (P-07).
 * Changelog: 2026-08-07 — Created for issue 3.9.
 *
 * Input:  [amount] — a **positive magnitude** in paise (MNY-001); the expense/income sign is the
 *         repository's to apply from [direction], exactly as the capture screen applies it for a
 *         typed transaction; [direction] — whether the account was debited or credited;
 *         [bookedOn] — the received day, ISO `yyyy-MM-dd` (TIM-002); [counterparty] — the payee or
 *         payer as printed, `null` when the alert named none; [accountTail] — the masked account or
 *         card digits the alert quoted (`4521`), `null` when it quoted none; [provenance] — engine
 *         id, version, instant, the rule cited, and the overall confidence in basis points.
 * Output: an immutable value.
 */
data class SmsDraftFields(
    val amount: Money,
    val direction: SmsDirection,
    val bookedOn: String,
    val counterparty: String?,
    val accountTail: String?,
    val provenance: EngineProvenance,
) {
    init {
        require(amount > Money.ZERO) { "A draft amount is a positive magnitude; the sign comes from direction" }
    }
}

/**
 * Which way the money went, as the alert stated it (issue 3.9).
 *
 * Why:  deliberately **not** `TransactionType`. An alert says the account was debited; it does not
 *       say whether that debit was spending or the outgoing leg of a transfer to the user's own
 *       second account, and `TransactionType` forces that distinction. Choosing it here would mean
 *       the parser silently classifying a self-transfer as an expense, which would double-count it
 *       against a budget. The mapping to a `TransactionType` is the repository's, where the user's
 *       accounts are actually known.
 * Result: the type of [SmsDraftFields.direction].
 * Changelog: 2026-08-07 — Created for issue 3.9.
 */
enum class SmsDirection {
    /** Money left the account the alert is about. */
    DEBIT,

    /** Money arrived in the account the alert is about. */
    CREDIT,
}

/**
 * Builds the engine for the DI graph (ARC-003).
 * Why:    the implementation is `internal`, so `:app`'s Hilt module cannot name it — the seam every
 *         engine in this codebase uses, kept identical on purpose.
 * Result: a [SmsEngine]. Input: none. Output: the engine.
 * Changelog: 2026-08-07 — Created for issue 3.9.
 */
object SmsEngineFactory {
    /** Result: the production engine. Input: none. Output: [SmsEngine]. */
    fun create(): SmsEngine = DefaultSmsEngine()
}
