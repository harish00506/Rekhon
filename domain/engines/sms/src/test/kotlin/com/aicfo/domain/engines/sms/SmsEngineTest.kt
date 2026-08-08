package com.aicfo.domain.engines.sms

import com.aicfo.core.common.Err
import com.aicfo.core.common.Ok
import com.aicfo.core.common.Result
import com.aicfo.core.model.Money
import com.aicfo.core.model.SmsMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Proves the SMS parser reads what moved and refuses everything else (issue 3.9; §18, §23).
 *
 * Why:  the refusals are the tests that matter, and they are listed first for that reason. Each one
 *       is a message that really arrives in an Indian inbox, really mentions an amount, and would
 *       really have become a transaction the user never made under a parser written the obvious way
 *       round (find an amount, find a verb, build a draft). A missed alert costs a manual entry; a
 *       false positive puts money in the ledger that never moved.
 * What: the gates one at a time, then the readings, then determinism and the rulebook seam.
 * Result: every branch of `DefaultSmsEngine` is pinned by a message a bank actually sends.
 * Changelog: 2026-08-07 — Created for issue 3.9.
 *
 * All fixtures are **anonymised templates**: real wording, invented account tails, merchants and
 * reference numbers. Nothing here came from a person's phone (P-01).
 */
class SmsEngineTest {
    private val engine = SmsEngineFactory.create()

    // --- the refusals -----------------------------------------------------------------------

    @Test
    fun `an OTP quoting the amount it authorises is not a transaction`() {
        // The message this whole engine is shaped around: it has an amount, a card, and a word that
        // reads like a verb. Only a gate that runs before any of that keeps it out.
        val draft =
            parse("Your OTP for a transaction of Rs 5,000 on card XX4521 is 448210. Do not share it with anyone.")

        assertNull(draft)
    }

    @Test
    fun `a mandate reminder is a forecast, not a debit`() {
        // "will be debited" — booking this would record money that has not left, and it would be
        // recorded again by the real alert when it does.
        val draft = parse("Rs.1,499.00 will be debited from A/c XX4521 on 12-08-26 towards NETFLIX SUBSCRIPTION.")

        assertNull(draft)
    }

    @Test
    fun `a loan advert quoting a figure is not a transaction`() {
        val draft =
            parse("Congratulations! You have a pre-approved loan offer of Rs 5,00,000 on your account. Apply now.")

        assertNull(draft)
    }

    @Test
    fun `a balance summary quotes only a balance`() {
        val draft = parse("Dear Customer, the available balance in your A/c XX4521 is Rs.45,320.10 as on 07-08-26.")

        assertNull(draft)
    }

    @Test
    fun `an alert from a personal number is refused whatever it says`() {
        // A forward or a fraud. Refused before a single keyword is looked at.
        val draft =
            parse(
                "Rs.1,250.00 debited from A/c XX4521 to SWIGGY.",
                sender = "+91 98765 43210",
            )

        assertNull(draft)
    }

    @Test
    fun `a message naming no account is not an alert about one`() {
        val draft = parse("Rs.2,000.00 debited towards your monthly plan. Thank you for banking with us.")

        assertNull(draft)
    }

    @Test
    fun `a message with no direction word is not a movement`() {
        val draft = parse("Statement for A/c XX4521 generated. Total spends Rs.12,400.00 this cycle.")

        assertNull(draft)
    }

    @Test
    fun `a promotional message with no amount at all is refused`() {
        assertNull(parse("Your A/c XX4521 statement is ready. Download it from our app."))
    }

    @Test
    fun `an unpaid notice does not fire the paid keyword`() {
        // Whole-word matching: `paid` inside `unpaid` would turn a dunning notice into a payment.
        val draft = parse("Your bill for A/c XX4521 remains unpaid. Rs.1,200.00 outstanding.")

        assertNull(draft)
    }

    // --- the readings -----------------------------------------------------------------------

    @Test
    fun `a UPI debit reads its amount, direction, payee and account`() {
        val draft =
            parse(
                "Rs.1,250.00 debited from A/c XX4521 on 07-08-26 to SWIGGY. " +
                    "Ref 522100123456. Avl Bal Rs.45,320.10. Not you? Call 18002586161",
            )!!

        assertEquals(Money(125_000L), draft.amount)
        assertEquals(SmsDirection.DEBIT, draft.direction)
        assertEquals("SWIGGY", draft.counterparty)
        assertEquals("4521", draft.accountTail)
        assertEquals("2026-08-07", draft.bookedOn)
        // The reference number, the date and the helpline are all longer digit strings than the
        // amount. Asserting the draft is *unflagged* is what proves none of them was read as money:
        // a second spendable figure would have pushed it below the floor.
        assertTrue(draft.provenance.confidenceBps!! > SmsRules().lowConfidenceBps)
    }

    @Test
    fun `a salary credit reads as a credit`() {
        val draft =
            parse("INR 85,000.00 credited to A/c XX8890 on 01-08-26 by ACME PAYROLL. Avl Bal INR 1,45,320.10.")!!

        assertEquals(Money(8_500_000L), draft.amount)
        assertEquals(SmsDirection.CREDIT, draft.direction)
    }

    @Test
    fun `a card purchase with no paise is read exactly`() {
        val draft = parse("Rs 499 spent on your Card ending 1234 at BIG BAZAAR on 06-08-26. Avl Lmt Rs 12,000")!!

        assertEquals(Money(49_900L), draft.amount)
        assertEquals("BIG BAZAAR", draft.counterparty)
        assertEquals("1234", draft.accountTail)
    }

    @Test
    fun `the balance is never taken as the amount, however much larger it is`() {
        // The single most damaging mistake available to this parser: the balance is the biggest
        // figure in almost every alert, and a plausible-looking one.
        val draft = parse("Rs.350.00 debited from A/c XX9012 to ZOMATO. Bal Rs.98,450.00")!!

        assertEquals(Money(35_000L), draft.amount)
        // Asserted as well as the amount, and the mutation run is why. Dropping the balance guard
        // leaves the amount correct here — it is simply the *first* figure — so the amount alone
        // proves nothing about the guard. What changes is that the balance becomes a second
        // spendable figure and the draft drops below the floor. This is the assertion that bites.
        assertTrue(draft.provenance.confidenceBps!! > SmsRules().lowConfidenceBps)
    }

    @Test
    fun `an alert whose only figure is a balance is not a transaction`() {
        // The case the ordering cannot save: a direction word, an account, and nothing but a
        // balance to read. Without the balance guard this is a ₹45,320 credit that never happened.
        assertNull(parse("Payment received. A/c XX4521 Avl Bal Rs.45,320.10"))
    }

    @Test
    fun `a credit-card limit is a balance too`() {
        val draft = parse("Rs.2,499.00 spent on Card ending 8890 at AMAZON. Avl Lmt Rs.1,47,501.00")!!

        assertEquals(Money(249_900L), draft.amount)
        assertTrue(draft.provenance.confidenceBps!! > SmsRules().lowConfidenceBps)
    }

    @Test
    fun `the figure the alert leads with is the one it is about`() {
        // A fee notice quotes the fee first and the transfer it relates to second. Taking the
        // largest amount — the receipt parser's rule — would book ₹5,000 the bank never charged.
        val draft = parse("Rs.10.00 debited from A/c XX4521 as fee for a transfer of Rs.5,000.00.")!!

        assertEquals(Money(1_000L), draft.amount)
    }

    @Test
    fun `a transfer alert takes its direction from the account it is sent to`() {
        // Both keywords are present and real. The alert goes to the *sending* account's owner, so
        // the earliest verb is the one describing what happened to their money.
        val draft = parse("Rs.5,000.00 debited from A/c XX4521 and credited to A/c XX9999.")!!

        assertEquals(SmsDirection.DEBIT, draft.direction)
    }

    @Test
    fun `a refund for a purchase is a credit, not the purchase`() {
        // The mirror case, and the one that catches a tie-break written backwards: `refund` leads,
        // `purchase` follows, and reading the last keyword instead of the first would book the
        // money as leaving the account it just arrived in.
        val draft = parse("Refund of Rs.500.00 credited to A/c XX4521 for your purchase at ACME. Bal Rs.9,500.00")!!

        assertEquals(SmsDirection.CREDIT, draft.direction)
        assertEquals(Money(50_000L), draft.amount)
    }

    @Test
    fun `a rupee sign is read like Rs and INR`() {
        val draft = parse("₹1,23,456.78 debited from A/c XX4521 to LANDLORD. Bal ₹10,000.00")!!

        assertEquals(Money(12_345_678L), draft.amount)
    }

    @Test
    fun `a merchant whose name contains bal keeps its amount`() {
        // Whole-word matching, the other direction: a substring search finds `bal` inside `GLOBAL`,
        // reads it as a balance label, and discards the amount that follows — so the user's real
        // purchase never reaches them. The merchant is named *before* the figure deliberately: with
        // the name after it, the substring would land outside the window and the test would pass
        // whatever the matcher did.
        val draft = parse("Purchase at GLOBAL FOODS: Rs.200.00 debited from A/c XX4521.")!!

        assertEquals(Money(20_000L), draft.amount)
    }

    @Test
    fun `an alert naming no payee still yields a draft`() {
        // The amount and the direction are the transaction; the payee is a label the user can add.
        val draft = parse("Rs.700.00 withdrawn from A/c XX4521. Bal Rs.9,300.00")!!

        assertEquals(Money(70_000L), draft.amount)
        assertNull(draft.counterparty)
    }

    // --- confidence -------------------------------------------------------------------------

    @Test
    fun `a single clear figure is offered above the flag floor`() {
        val draft = parse("Rs.350.00 debited from A/c XX9012 to ZOMATO. Bal Rs.8,450.00")!!

        assertTrue(draft.provenance.confidenceBps!! > SmsRules().lowConfidenceBps)
    }

    @Test
    fun `an alert quoting two spendable figures is flagged for review`() {
        // A fee alongside the spend: which one is "the" transaction is a guess, so the draft is
        // offered below the rulebook's floor and the review screen marks it.
        val draft = parse("Rs.500.00 debited from A/c XX4521 to ACME. Fee Rs.10.00 applied. Bal Rs.4,490.00")!!

        assertTrue(draft.provenance.confidenceBps!! < SmsRules().lowConfidenceBps)
    }

    // --- provenance, determinism and the rulebook seam ---------------------------------------

    @Test
    fun `every draft cites the rule that produced it`() {
        val draft = parse("Rs.350.00 debited from A/c XX9012 to ZOMATO. Bal Rs.8,450.00")!!

        assertEquals("sms-parser", draft.provenance.engineId)
        assertEquals("1.0", draft.provenance.engineVersion)
        assertEquals(listOf(SmsRules.TRANSACTION_PARSE), draft.provenance.evidence)
        assertEquals(NOW, draft.provenance.computedAtUtcMillis)
    }

    @Test
    fun `the same message twice gives an identical result`() {
        val body = "Rs.1,250.00 debited from A/c XX4521 to SWIGGY. Avl Bal Rs.45,320.10"

        assertEquals(parse(body), parse(body))
    }

    @Test
    fun `moving a debit keyword in the rulebook moves the parse`() {
        val body = "Rs.900.00 splurged from A/c XX4521 to ACME. Bal Rs.100.00"

        assertNull(parse(body))
        assertEquals(
            Money(90_000L),
            parse(body, rules = SmsRules(debitKeywords = listOf("splurged")))!!.amount,
        )
    }

    @Test
    fun `removing a balance keyword lets the balance through`() {
        // Proves the balance guard is the rulebook's, not a constant hidden in the engine.
        val draft =
            parse(
                "Rs.350.00 debited from A/c XX9012 to ZOMATO. Bal Rs.8,450.00",
                rules = SmsRules(balanceKeywords = listOf("outstanding")),
            )!!

        assertTrue(draft.provenance.confidenceBps!! < SmsRules().lowConfidenceBps)
    }

    @Test
    fun `turning off the sender gate admits a numeric sender`() {
        val draft =
            parse(
                "Rs.350.00 debited from A/c XX9012 to ZOMATO. Bal Rs.8,450.00",
                sender = "9876543210",
                rules = SmsRules(rejectNumericSenders = false),
            )!!

        assertEquals(Money(35_000L), draft.amount)
    }

    @Test
    fun `a blank sender is refused even with the gate off`() {
        assertNull(
            parse(
                "Rs.350.00 debited from A/c XX9012 to ZOMATO.",
                sender = "  ",
                rules = SmsRules(rejectNumericSenders = false),
            ),
        )
    }

    // --- the caller's contract ---------------------------------------------------------------

    @Test
    fun `a malformed date is the caller's bug, not the message's`() {
        val result =
            engine.parse(
                SmsInput(
                    message = SmsMessage(1L, SENDER, "Rs.350.00 debited from A/c XX9012.", RECEIVED_AT),
                    receivedOnIsoDate = "07/08/2026",
                    nowUtcMillis = NOW,
                ),
            )

        assertTrue("a malformed ISO date must not be reported as 'not a transaction'", result is Err)
    }

    // --- helpers -----------------------------------------------------------------------------

    /**
     * Parses one message with the fixture's defaults.
     * Why:    every test differs in the body alone, so the ceremony of building an [SmsInput] would
     *         otherwise be repeated thirty times and hide what each case is actually about.
     * Result: the draft, or `null` when the parser refused the message. Fails the test on an `Err`,
     *         which no message can legitimately produce — only a malformed caller date can.
     * Input:  [body]; [sender] — defaults to a DLT header; [rules] — defaults to the rulebook's.
     * Output: `SmsDraftFields?`.
     */
    private fun parse(
        body: String,
        sender: String = SENDER,
        rules: SmsRules = SmsRules(),
    ): SmsDraftFields? {
        val result: Result<SmsDraftFields?, *> =
            engine.parse(
                SmsInput(
                    message = SmsMessage(id = 1L, sender = sender, body = body, receivedAtUtcMillis = RECEIVED_AT),
                    receivedOnIsoDate = RECEIVED_ON,
                    nowUtcMillis = NOW,
                    rules = rules,
                ),
            )
        assertTrue("the parser failed on a message it should have judged: $result", result is Ok)
        return (result as Ok).value
    }

    private companion object {
        /** A DLT header of the shape Indian banks register. */
        const val SENDER = "VM-HDFCBK"
        const val RECEIVED_ON = "2026-08-07"
        const val RECEIVED_AT = 1_786_082_400_000L
        const val NOW = 1_786_082_401_000L
    }
}
