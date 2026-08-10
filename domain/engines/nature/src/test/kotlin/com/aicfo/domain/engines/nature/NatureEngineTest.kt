package com.aicfo.domain.engines.nature

import com.aicfo.core.common.Ok
import com.aicfo.core.model.AccountType
import com.aicfo.core.model.CategoryNature
import com.aicfo.core.model.Money
import com.aicfo.core.model.TransactionType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * §8.3.1's decision order, step by step and — more importantly — step *against* step (issue 4.3).
 *
 * Why:  a test per step proves each one works; it does not prove the **order** is the order, and the
 *       order is the whole reason five steps sit above the obvious one. Every case below that
 *       matters is a conflict: a loan payment whose category says NEED, a gold purchase tagged
 *       Shopping, a merchant the user has overruled that the category still disagrees with. Read on
 *       their own, all three have a plausible wrong answer; read against the order, only one answer
 *       is defensible.
 *
 *       The direction of the error is what makes it worth this much test: every one of those wrong
 *       answers **inflates true spend**, which is the figure Safe-to-Spend, the health score and the
 *       Purchase Advisor are all calibrated against.
 * What: each step in isolation, each adjacent pair in conflict, the fallback, the step-6 modifier,
 *       and the invariants that keep the review flow from emptying itself.
 * Result: a reordering of §8.3.1 fails the build rather than a review.
 * Changelog: 2026-08-10 — Created for issue 4.3.
 */
class NatureEngineTest {
    private val engine = NatureEngineFactory.create()

    // --- step 1: the loan account ------------------------------------------------------------------

    /**
     * Input:  a payment arriving in a loan account — how an EMI is modelled once the loan is an
     *         account rather than a note.
     * Output: LIABILITY, citing `CLS-NAT-001`, at full confidence. An account's type is a fact the
     *         user entered, not an inference, which is why steps 1–3 are worth 100%.
     */
    @Test
    fun `a payment into a loan account is debt service`() {
        val verdict =
            classify(
                accountType = AccountType.LOAN,
                type = TransactionType.TRANSFER_IN,
                counterpart = AccountType.BANK,
            )

        assertEquals(CategoryNature.LIABILITY, verdict.nature)
        assertEquals(listOf("CLS-NAT-001"), verdict.cited())
        assertEquals(NatureRules().accountOverrideBps, verdict.provenance.confidenceBps)
    }

    /**
     * Input:  the *other* leg of the same EMI — money leaving the bank account towards the loan.
     * Output: LIABILITY as well. §8.3.1 says "txn on a loan account", and in a two-leg transfer both
     *         legs are that transaction; labelling only one would leave the leg the user actually
     *         looks at — the one in their bank account — reading as ordinary spending.
     */
    @Test
    fun `the bank leg of the same EMI is debt service too`() {
        val verdict =
            classify(
                accountType = AccountType.BANK,
                type = TransactionType.TRANSFER_OUT,
                counterpart = AccountType.LOAN,
            )

        assertEquals(CategoryNature.LIABILITY, verdict.nature)
        assertEquals(listOf("CLS-NAT-001"), verdict.cited())
    }

    /**
     * The conflict that makes step 1 worth having above step 5.
     * Input:  a loan payment whose category is one the taxonomy calls a NEED.
     * Output: LIABILITY. Taking the category would count debt principal as consumption and inflate
     *         true spend by the whole EMI.
     */
    @Test
    fun `the loan account beats the category`() {
        val verdict =
            classify(
                accountType = AccountType.LOAN,
                type = TransactionType.TRANSFER_IN,
                counterpart = AccountType.BANK,
                categoryNature = CategoryNature.NEED,
            )

        assertEquals(CategoryNature.LIABILITY, verdict.nature)
    }

    // --- steps 2 and 3: conversions ---------------------------------------------------------------

    /** Input: a transfer into an investment account. Output: INVESTMENT, citing `CLS-NAT-002`. */
    @Test
    fun `a transfer into an investment account is investing`() {
        val verdict =
            classify(
                accountType = AccountType.BANK,
                type = TransactionType.TRANSFER_OUT,
                counterpart = AccountType.INVESTMENT,
            )

        assertEquals(CategoryNature.INVEST, verdict.nature)
        assertEquals(listOf("CLS-NAT-002"), verdict.cited())
    }

    /**
     * The half of a conversion the first draft of this engine missed.
     * Input:  the *arriving* leg of the same SIP — a transfer landing in the investment account.
     * Output: INVESTMENT as well. Both legs describe the same becoming, and labelling only the
     *         outgoing one left every SIP half-classified: the arriving leg fell past every account
     *         step to the category and read as a Want. Caught by the golden file, not by this test,
     *         which is why the golden file fixes the *cited rule* and not only the answer.
     *
     *         A dividend credited into the same account cannot reach this branch: it is an `INCOME`
     *         row with no counterpart, and the counterpart being non-null is what "transfer leg"
     *         means here.
     */
    @Test
    fun `the arriving leg of the same transfer is investing too`() {
        val verdict =
            classify(
                accountType = AccountType.INVESTMENT,
                type = TransactionType.TRANSFER_IN,
                counterpart = AccountType.BANK,
            )

        assertEquals(CategoryNature.INVEST, verdict.nature)
        assertEquals(listOf("CLS-NAT-002"), verdict.cited())
    }

    /**
     * Input:  a dividend credited straight into the investment account — income, no counterpart.
     * Output: **not** classified as a fresh investment. Counting it would turn money arriving from
     *         outside into money the user saved, and inflate the savings rate by the whole dividend.
     */
    @Test
    fun `income credited into an investment account is not a fresh investment`() {
        val verdict =
            classify(
                accountType = AccountType.INVESTMENT,
                type = TransactionType.INCOME,
                amount = Money(4_200_00L),
            )

        assertEquals(listOf("CLS-NAT-005"), verdict.cited())
    }

    /**
     * Input:  a transfer into each of the four account types this build treats as an asset record.
     * Output: ASSET for all of them, citing `CLS-NAT-003`. Asserted as a set rather than one case,
     *         because the *list* is the rule here and dropping a type from it would be invisible.
     */
    @Test
    fun `a transfer into an asset account is a conversion`() {
        NatureRules().assetAccountTypes.forEach { assetType ->
            val verdict =
                classify(
                    accountType = AccountType.BANK,
                    type = TransactionType.TRANSFER_OUT,
                    counterpart = assetType,
                )

            assertEquals("$assetType", CategoryNature.ASSET, verdict.nature)
            assertEquals("$assetType", listOf("CLS-NAT-003"), verdict.cited())
        }
    }

    /**
     * The conflict that makes step 3 worth having.
     * Input:  a gold purchase somebody tagged Shopping — a WANT.
     * Output: ASSET. §8.3 excludes a conversion from true spend precisely because the money is still
     *         the user's; counting a ₹80,000 gold purchase as discretionary spending would wreck the
     *         month's numbers and the advice built on them.
     */
    @Test
    fun `an asset transfer beats a category that calls it shopping`() {
        val verdict =
            classify(
                accountType = AccountType.BANK,
                type = TransactionType.TRANSFER_OUT,
                counterpart = AccountType.GOLD,
                categoryNature = CategoryNature.WANT,
            )

        assertEquals(CategoryNature.ASSET, verdict.nature)
    }

    /**
     * Input:  an ordinary expense at a merchant, with no counterpart account at all.
     * Output: falls past steps 2–3 to the category. Without this, an engine that read
     *         `counterpartAccountType` carelessly would classify every uncategorised expense as
     *         whatever the null branch happened to do.
     */
    @Test
    fun `an ordinary expense is not a conversion`() {
        val verdict = classify(categoryNature = CategoryNature.NEED)

        assertEquals(CategoryNature.NEED, verdict.nature)
        assertEquals(listOf("CLS-NAT-005"), verdict.cited())
    }

    // --- step 4: what the user has already decided -------------------------------------------------

    /**
     * Input:  a merchant the user has overridden to WANT twice, whose category says NEED.
     * Output: WANT, citing `CLS-NAT-004`. §8.3 says nature is "auto-correctable, **learned**", and a
     *         suggestion that keeps reversing a correction the user has already made twice is not
     *         learning, it is arguing.
     */
    @Test
    fun `a merchant the user has overridden keeps their answer`() {
        val verdict =
            classify(
                categoryNature = CategoryNature.NEED,
                history = listOf(NatureHistoryRow(CategoryNature.WANT, count = 2)),
            )

        assertEquals(CategoryNature.WANT, verdict.nature)
        assertEquals(listOf("CLS-NAT-004"), verdict.cited())
        assertEquals(BPS_FULL, verdict.provenance.confidenceBps)
    }

    /**
     * Input:  a merchant overridden two ways, two to one, whose category says NEED.
     * Output: NEED — the history is not settled (6 666 bps, under the floor), so it yields to the
     *         category rather than imposing a majority the user never agreed to. **This is the one
     *         place the nature engine differs from issue 4.2's category engine**, and deliberately:
     *         there, an unsettled history ends Stage 1, because a category may be left unset. Here
     *         every transaction must end with a nature, so yielding means carrying on down the list.
     */
    @Test
    fun `an unsettled override history yields to the category`() {
        val verdict =
            classify(
                categoryNature = CategoryNature.NEED,
                history =
                    listOf(
                        NatureHistoryRow(CategoryNature.WANT, count = 2),
                        NatureHistoryRow(CategoryNature.NEED, count = 1),
                    ),
            )

        assertEquals(CategoryNature.NEED, verdict.nature)
        assertEquals(listOf("CLS-NAT-005"), verdict.cited())
    }

    /**
     * Input:  a merchant the user overrode, on a transfer into a gold account.
     * Output: ASSET. The account-type steps sit **above** the learned one because an account's type
     *         is a fact and a past override is an inference from other transactions — and because a
     *         user who once called a jeweller a WANT did not thereby decide that buying gold is
     *         consumption.
     */
    @Test
    fun `an account conversion beats the user's merchant history`() {
        val verdict =
            classify(
                accountType = AccountType.BANK,
                type = TransactionType.TRANSFER_OUT,
                counterpart = AccountType.GOLD,
                history = listOf(NatureHistoryRow(CategoryNature.WANT, count = 5)),
            )

        assertEquals(CategoryNature.ASSET, verdict.nature)
    }

    // --- step 5 and the fallback -------------------------------------------------------------------

    /** Input: an expense with a category. Output: the category's nature, citing `CLS-NAT-005`. */
    @Test
    fun `a categorised expense takes its category's nature`() {
        assertEquals(CategoryNature.WANT, classify(categoryNature = CategoryNature.WANT).nature)
    }

    /**
     * Input:  an expense with no category at all — every transaction on a profile before issue 4.2,
     *         and every one whose merchant Stage 1 declined to classify.
     * Output: the rulebook's fallback nature, **flagged**. §8.3 needs a nature for every transaction
     *         and there is nothing here to derive one from, so the honest answer is a default that
     *         says out loud that it is a default (P-02) rather than one that looks decided.
     */
    @Test
    fun `an uncategorised expense gets the fallback, and says so`() {
        val verdict = classify(categoryNature = null)

        assertEquals(NatureRules().fallbackNature, verdict.nature)
        assertEquals(NatureRules().fallbackBps, verdict.provenance.confidenceBps)
        assertTrue("a verdict with nothing to go on must be flagged for review", verdict.isFlagged)
    }

    /** Input: an ordinary categorised expense. Output: not flagged — the flag has to mean something. */
    @Test
    fun `an ordinary categorised expense is not flagged`() {
        assertFalse(classify(categoryNature = CategoryNature.NEED).isFlagged)
    }

    // --- step 6: the modifier ----------------------------------------------------------------------

    /**
     * Input:  ₹9,400 in a NEED category whose median is ₹2,000 — §8.3.1's own example.
     * Output: **still NEED**, and flagged, citing both the step that decided the nature and
     *         `CLS-NAT-006`. Step 6 is a modifier, not a step: it raises a question ("festival
     *         stock-up or party?") and §8.3 is explicit that it never blocks the save. Changing the
     *         nature here would be the app answering its own question.
     */
    @Test
    fun `an unusually large need keeps its nature and raises a question`() {
        val verdict =
            classify(
                amount = Money(-9_400_00L),
                categoryNature = CategoryNature.NEED,
                categoryMedian = Money(2_000_00L),
            )

        assertEquals(CategoryNature.NEED, verdict.nature)
        assertEquals(listOf("CLS-NAT-005", "CLS-NAT-006"), verdict.cited())
        assertTrue(verdict.isFlagged)
    }

    /** Input: a NEED just under 3× the median. Output: unflagged — the boundary is not inclusive. */
    @Test
    fun `a need at exactly three times the median is not yet unusual`() {
        val verdict =
            classify(
                amount = Money(-6_000_00L),
                categoryNature = CategoryNature.NEED,
                categoryMedian = Money(2_000_00L),
            )

        assertFalse(verdict.isFlagged)
        assertEquals(listOf("CLS-NAT-005"), verdict.cited())
    }

    /**
     * Input:  an unusually large **WANT**.
     * Output: unflagged. §8.3.1 step 6 says "in a NEED category" and means it: a large discretionary
     *         spend is not ambiguous about what it was, it is just large — the Purchase Advisor's
     *         problem, not the classifier's.
     */
    @Test
    fun `an unusually large want raises no question`() {
        val verdict =
            classify(
                amount = Money(-9_400_00L),
                categoryNature = CategoryNature.WANT,
                categoryMedian = Money(2_000_00L),
            )

        assertFalse(verdict.isFlagged)
    }

    /**
     * Input:  a large NEED on a category with too little history for a median.
     * Output: unflagged. Comparing against a median of one past transaction would flag the second
     *         grocery run a user ever recorded, which is noise dressed as insight.
     */
    @Test
    fun `no median means no question`() {
        val verdict =
            classify(
                amount = Money(-9_400_00L),
                categoryNature = CategoryNature.NEED,
                categoryMedian = null,
            )

        assertFalse(verdict.isFlagged)
    }

    // --- provenance and determinism ----------------------------------------------------------------

    /**
     * Input:  any verdict.
     * Output: the engine names itself and its version and stamps the instant it was *given* rather
     *         than one it read — TIM-001, and what makes the golden file reproducible.
     */
    @Test
    fun `provenance identifies the engine and uses the caller's instant`() {
        val provenance = classify(categoryNature = CategoryNature.NEED).provenance

        assertEquals(NOW, provenance.computedAtUtcMillis)
        assertTrue("the engine did not name itself", provenance.engineId.isNotBlank())
        assertTrue("the engine did not carry a version (AI-ARC-006)", provenance.engineVersion.isNotBlank())
    }

    /** Input: the same transaction twice. Output: byte-identical verdicts (P-08). */
    @Test
    fun `the same input always produces the same verdict`() {
        assertEquals(classify(categoryNature = CategoryNature.NEED), classify(categoryNature = CategoryNature.NEED))
    }

    // --- the rules cannot be configured into silence ------------------------------------------------

    /**
     * Why:    both `fallbackBps` and `unusualAmountBps` are *defined* as "below the floor" — they are
     *         the only two ways anything reaches §8.3's review flow. A rule set that let either drift
     *         above it would empty that flow while every test asserting the nature itself kept
     *         passing, which is the shape of a gate that checks nothing.
     * Input:  each of the two raised to the floor. Output: the rule set refuses to be built.
     */
    @Test
    fun `a rule set that would empty the review flow is refused`() {
        val floor = NatureRules().minConfidenceBps
        assertThrows(IllegalArgumentException::class.java) { NatureRules(fallbackBps = floor) }
        assertThrows(IllegalArgumentException::class.java) { NatureRules(unusualAmountBps = floor) }
    }

    /**
     * Input:  a rule set counting a nature as both true spend and a conversion.
     * Output: refused. §8.3 shows conversions *separately from* spend, so an overlap would count the
     *         same rupee twice and make the breakdown's own totals disagree with each other.
     */
    @Test
    fun `a nature cannot be both spend and a conversion`() {
        assertThrows(IllegalArgumentException::class.java) {
            NatureRules(conversionNatures = setOf(CategoryNature.WANT))
        }
    }

    // --- helpers -----------------------------------------------------------------------------------

    /**
     * Runs the engine over one transaction shape.
     * Result: the verdict. Fails the test on an `Err`, which no well-formed input can produce.
     * Input:  every field of [NatureInput], defaulted to an ordinary uncategorised bank expense.
     * Output: [NatureVerdict].
     */
    @Suppress("LongParameterList")
    private fun classify(
        accountType: AccountType = AccountType.BANK,
        type: TransactionType = TransactionType.EXPENSE,
        amount: Money = Money(-500_00L),
        counterpart: AccountType? = null,
        history: List<NatureHistoryRow> = emptyList(),
        categoryNature: CategoryNature? = null,
        categoryMedian: Money? = null,
    ): NatureVerdict {
        val outcome =
            engine.classify(
                NatureInput(
                    accountType = accountType,
                    type = type,
                    amount = amount,
                    nowUtcMillis = NOW,
                    counterpartAccountType = counterpart,
                    merchantHistory = history,
                    categoryNature = categoryNature,
                    categoryMedian = categoryMedian,
                ),
            )
        assertTrue("the engine errored on a well-formed input", outcome is Ok)
        return (outcome as Ok).value
    }

    /** Result: the rule ids this verdict cites, in order. Input: the receiver. Output: `List<String>`. */
    private fun NatureVerdict.cited(): List<String> = provenance.evidence.map { it.ruleId }

    private companion object {
        /** Fixed, so every assertion here is reproducible (P-08, TIM-001). */
        const val NOW = 1_786_082_400_000L
    }
}
