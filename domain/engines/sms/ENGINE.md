# SmsEngine — opt-in bank-alert parsing

**SRS:** §18, §23 · **Pipeline layer:** L3 (rules) · **Module:** `:domain:engines:sms`
**Version:** 1.0 · **Status:** active

## Why this engine exists

Issue 3.9 asks for "bank alerts → draft transactions". The trap is in the arrow. The inbox is not
a feed of transactions; it is a feed of *messages about money*, and most of them describe money
that did not move:

- an OTP quotes the amount it is authorising, **and** the card it is for;
- a mandate reminder says what *will* be debited next Tuesday;
- a loan advert quotes a figure larger than anything the user has ever spent;
- a balance alert quotes the balance — usually the largest number in the message;
- a declined-payment notice quotes a real merchant, a real card and an amount that was refused.

So the engine's job is stated the other way round from the receipt parser's: **its purpose is to
refuse**, and a parse is what is left when nothing has refused it. Read top to bottom,
`DefaultSmsEngine.parse` is five gates and one constructor.

The asymmetry that sets every threshold: **a missed alert costs the user one manual entry**, which
is what they were doing before this feature existed. **A false positive puts money in their ledger
that never moved** — silently, on a screen they were told to trust, in an app whose whole claim is
that its numbers come from arithmetic (P-03). One of those is an inconvenience and the other is a
lie, so every ambiguous case resolves to `null`.

It is a separate module from `:data:sms` for the reason `:domain:engines:receipt` is separate from
`:ml:ocr`: everything in `:data:sms` needs a device, a `ContentResolver` and a Play-restricted
permission (see [ADR-0013](../../../docs/adr/0013-read-sms-play-policy-and-the-gated-inbox.md)),
and none of that can be gated in CI. This takes a message *value*, so the refusals can be frozen
into a fixture and asserted on every build.

## Contract

```
interface SmsEngine {
    fun parse(input: SmsInput): Result<SmsDraftFields?, AppError>
}
```

- **Input** — `SmsInput`:
  - `message: SmsMessage` — `id`, `sender`, `body`, `receivedAtUtcMillis`. Lives in `:core:model`
    so a pure-Kotlin engine can name it (ARC-002).
  - `receivedOnIsoDate: String` — the day the phone received it, in the profile zone (TIM-002),
    **converted by the caller and passed in, never read** (TIM-001).
  - `nowUtcMillis: Long` — stamped into provenance.
  - `rules: SmsRules` — the rulebook gates, **injected** (ADR-0005).
- **Output** — `SmsDraftFields?`: `amount: Money` (positive magnitude), `direction: DEBIT|CREDIT`,
  `bookedOn` (ISO `yyyy-MM-dd`), `counterparty: String?`, `accountTail: String?`, and `provenance`
  (`engineId = sms-parser`, `engineVersion`, `computedAtUtcMillis`, `evidence = [RULE-SMS-PARSE@1.0]`,
  `inputWindow = bookedOn`, `confidenceBps`).

**`Ok(null)` is the ordinary answer.** Most messages are not transactions, and modelling that as an
error would make the common path an exception path. The only `Err` is a malformed
`receivedOnIsoDate` — a caller bug, not a message.

**`amount` and `direction` are non-null while `counterparty` and `accountTail` are nullable.** A
draft without an amount or a direction is not a partial reading of a transaction — it is not a
transaction. This is deliberately unlike `ReceiptFields`, where every field is independently
nullable because FR-OCR-003 requires a partly-read receipt to still surrender its total: throwing
away a photograph the user already took has a real cost, whereas a message stays in the inbox and
can be re-read for free.

`amount` is a **positive magnitude**. The expense/income sign, and the choice of `TransactionType`,
belong to the repository — which is also why `direction` is `SmsDirection` and not
`TransactionType`. An alert says the account was debited; it does not say whether that debit was
spending or the outgoing leg of a transfer to the user's own second account, and only the layer
that knows the user's accounts can decide.

## Formula / algorithm

Five gates, in this order. The order is load-bearing.

1. **Sender.** A bank in India sends from a registered alphabetic DLT header (`VM-HDFCBK`,
   `AD-ICICIB`). A ten-digit number is a person. Checked **first**, so a scam text crafted to hit
   every keyword never reaches the keyword code. Governed by `reject_numeric_senders`.
2. **Ignore words.** Checked **second**, ahead of everything expensive, because the message that
   matters most — `Your OTP for a transaction of Rs 5,000 on card XX4521 is 448210` — passes every
   other gate in the file. Any `ignore_keywords` word disqualifies the message outright.
3. **Direction.** The **earliest** `debit_keywords` or `credit_keywords` word wins, because an alert
   states what happened before it elaborates: `Rs.5,000 debited from A/c XX4521 and credited to
   A/c XX9999` is a debit. Preferring one list by fiat would decide that message by the order the
   lists happen to be written in — a rulebook edit could then silently reverse the sign on the
   user's transaction. No direction word at all means the message is a statement notice or a
   balance summary, and it is refused.
4. **Account marker.** A real alert names the account it is about (`A/c XX4521`, `card ending 8890`)
   because the bank has to tell a customer with four accounts which one moved. A promotion almost
   never does, because the sender does not know. A weak signal alone, a good one in combination.
5. **Amount.** **The first currency-marked figure that is not a balance.** Two judgements here:
   - *Currency-marked only.* An alert always prints `Rs`/`INR`/`₹` beside a real amount, so unlike
     the receipt parser there is no bare-integer tier — which removes reference numbers, OTP codes,
     card digits and dates from consideration in one step.
   - *First, not largest.* The receipt parser takes the largest candidate; here that would read
     `Avl Bal Rs.45,320.10` as a ₹45,320 purchase on almost every message the app sees. A receipt's
     largest number usually *is* the total; an alert's largest number is usually the balance. It
     also gets `Rs.10.00 debited … as fee for a transfer of Rs.5,000.00` right: the alert is about
     the fee it leads with.
   - *Balance exclusion.* A figure is a balance if a `balance_keywords` word sits **between it and
     the previous figure** (or the start of the message). The window comes from the text rather than
     a fixed character count, because `… A/c XX4521. Avl Bal: Rs.45,320.10` and `… card XX12 Avl Bal
     Rs.4,500` put the label at completely different distances.

**Every keyword is matched as a whole word**, not as a substring. That is what lets `balance_keywords`
hold the single row `bal` and cover `Avl Bal`, `Bal:`, `Bal Rs` and `A/c Bal` at once, without a
merchant named `GLOBAL FOODS` turning its own amount into a balance; and it is why `unpaid` does
not fire `paid`.

**Confidence** is `9000` for a single spendable figure, and `5500` — *deliberately below
`low_confidence_bps`* — when the alert quoted more than one, so the review screen flags a message
whose fee, cashback or second leg makes the choice a guess.

**Counterparty and account tail are best-effort** and score nothing. The counterparty regex reads
`to X`, `at X`, `vpa X`, `from X` and stops at a reference number, a date, a balance label or a
sentence break; no match means an empty field the review screen asks about, not a guess it presents
as read.

## Assumptions & guardrails

- Money is `Long` paise in `Money`; confidence is integer basis points (MNY-002). **There is not a
  `Double` in the module.** Every amount comes from `MoneyFormatter.parse`, which refuses anything
  not exactly representable in paise and never rounds (MNY-001).
- The clock is passed in (TIM-001) — `CfoWallClockInDomain` fails the build on a wall-clock read
  here — and dates are ISO strings (TIM-002).
- **The booking date is the day the message arrived, not a date parsed out of the body.** Bank
  alerts arrive within seconds of the transaction, so the two agree except in pathological cases
  (a delayed SMS across midnight, a batch alert for yesterday's card settlement). A date read out
  of the text would be one more thing that can be read wrong, on the field that decides which month
  a budget is charged — and unlike a receipt, the message carries a trustworthy timestamp of its
  own. The user can back-date the draft on the review screen (ADR-0012).
- **English templates only.** Indian bank alerts are sent in English even to customers who bank in
  another language; a Hindi or Tamil template would be refused rather than misread, which is the
  correct failure.
- **No merchant knowledge base.** `merchant_id` and the alias table are issue 4.1's; the
  counterparty is passed through as printed.
- It produces figures read off a message, never prose (P-03) and never orders (P-07). **Nothing
  here writes.**

## Rules / knowledge consumed

| ID / file | What it provides |
|-----------|------------------|
| `RULE-SMS-PARSE` (`ai/rules/rules-kb.json`) | `debit_keywords`, `credit_keywords`, `ignore_keywords`, `account_marker_keywords`, `balance_keywords`, `reject_numeric_senders: true`, `low_confidence_bps: 6000`, `duplicate_amount_tolerance_pct: 1`, `duplicate_date_tolerance_days: 1` |

Mirrored as `SmsRules` per **ADR-0005** because nothing in the app loads `ai/` yet. This matters
more here than for any other engine that copies a rulebook row: elsewhere a drifted number moves a
threshold, but here **the keyword lists are the algorithm**. Delete `otp` from the rulebook without
deleting it from `SmsRules` and the app's behaviour is unchanged while its published rulebook says
otherwise — the app would be recording the user's money by a rule they cannot read.

`RulebookDriftTest` fails the build if the two disagree, verified on 2026-08-07 against five
separate mutations (a moved threshold, a flipped flag, a removed keyword, a widened duplicate band,
a disabled rule). **The same run found that the gate was not connected to the build**: the rulebook
was not a declared Gradle input, so editing `ai/rules/rules-kb.json` alone left every drift test
`UP-TO-DATE` and green. Fixed in this module's `build.gradle.kts` and in `:quicksetup`, `:recurring`
and `:receipt`, which had the same hole.

The last two parameters are read by `SmsRepository` rather than by this engine, and are held equal
to `RULE-RECEIPT-PARSE`'s so FR-OCR-006's merge offer is symmetric — an alert for a receipt already
scanned and a receipt for an alert already drafted are the same event. A drift test asserts the two
rules agree.

## Evidence shown to the user (P-02)

The review screen shows what was read, flags a draft below `low_confidence_bps` as a word and a
content description (not only a colour), and makes every field editable — the correction is what
gets saved. The cited rule is `RULE-SMS-PARSE@1.0`.

## Tests

- **Refusals**, which are listed first in `SmsEngineTest` because they are the tests that matter:
  an OTP quoting the amount it authorises; a mandate reminder; a loan advert; a balance summary; an
  alert from a personal number; a message naming no account; a message with no direction word; an
  `unpaid` notice that must not fire `paid`.
- **Readings:** a UPI debit reading amount, direction, payee, account and date; a salary credit; a
  card purchase with no paise; a rupee sign; an alert with no payee.
- **The two that a mutation run added**, because the originals passed for the wrong reason: an
  alert whose *only* figure is a balance (the ordering cannot save that one), and a fee notice whose
  larger second figure must not be taken.
- **Direction:** a transfer alert naming both verbs; a refund for a purchase — the case that catches
  a tie-break written backwards.
- **Confidence:** a single clear figure is unflagged; two spendable figures fall below the floor.
- **The rulebook seam:** moving a debit keyword moves the parse; removing a balance keyword lets the
  balance through; turning off the sender gate admits a numeric sender.
- **Determinism:** the same message twice gives an identical result, provenance included (P-08).
- **The eval gate** (`SmsEvalTest` over `src/test/resources/eval/sms.txt`): fifty anonymised
  messages — thirty real movements across bank, card, UPI and wallet templates, and twenty that
  merely mention money. Current scores: **amount 100%, direction 100%, refusals 20/20, confident
  share 100%.**
- Coverage: engine ≥ 85% (gate).

### The thresholds are ours, not the SRS's

§18.1 gives the receipt parser a number — *total-amount accuracy ≥ 95%* — and the SRS gives SMS
parsing **no equivalent figure**. The two gates here were chosen for this issue and are deliberately
lopsided:

- **amount and direction ≥ 95%** on the transactional half, scored separately because a reversed
  sign is a different bug from a misread figure and would be invisible in a combined score;
- **100% rejection** of the non-transactional half — no budget at all, stated as a count rather
  than a percentage precisely because a percentage invites one.

### What the eval set does and does not prove

It scores 100% on amount and direction, and that is **not** an independent estimate of how the app
performs against a real inbox: the fixtures were written alongside the parser, so they are the
shapes it was built to handle. Twice during that writing the fixtures drove a change rather than
confirming one — `sent` was missing from the debit keywords, so every UPI payment was invisible;
and a declined-payment alert parsed as a purchase until `declined`/`failed` were added — which is
the set doing its job, but also a demonstration that the templates it does not contain are the
ones that will be wrong.

What the gate provides is **regression protection**: a keyword edit that starts admitting adverts,
or stops reading UPI payments, fails the build. Bank wording also drifts, and a template that
changes next year is covered by no fixture written this year. The independent measurements are the
emulator run against messages injected with `adb emu sms send`, and — in the end — the user's own
review screen, which is why nothing here is ever saved without a tap (P-07).

## Version log

| Version | Date | Change |
|---------|------|--------|
| 1.0 | 2026-08-07 | Initial implementation for issue 3.9 from SRS §18/§23. `sent` and the `declined`/`failed` refusals were added within this version, before release, when the eval set was written against real templates. |
