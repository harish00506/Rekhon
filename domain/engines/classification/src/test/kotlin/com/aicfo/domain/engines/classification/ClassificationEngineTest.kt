package com.aicfo.domain.engines.classification

import com.aicfo.core.common.Ok
import com.aicfo.core.model.Category
import com.aicfo.core.model.CategorySeed
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The behaviour of Stage-1 auto-categorisation (issue 4.2; SRS §8.1).
 *
 * Why:  the tests are grouped the way the requirement is written — one group per tier, one for the
 *       precedence *between* tiers, and one for every way the engine is required to say nothing.
 *       That last group is the largest, and deliberately: §8.1's fourth precedence step is the
 *       `"Uncategorised" prompt`, so refusing is a specified outcome rather than a fallback, and a
 *       classifier is judged as much by what it declines as by what it gets right.
 * What: the tiers in isolation, their precedence, the boundary either side of the confidence floor,
 *       and the substring traps the knowledge base's short literals set.
 * Result: a rule edit that starts misfiling money fails the build.
 * Changelog: 2026-08-10 — Created for issue 4.2.
 */
class ClassificationEngineTest {
    private val engine = ClassificationEngineFactory.create()

    // --- tier (b): the knowledge base -------------------------------------------------------------

    /** Input: a merchant that is exactly a rule's literal. Output: the category, at the exact rate. */
    @Test
    fun `a merchant that is the rule's literal matches at the exact rate`() {
        val suggestion = suggest("swiggy")

        assertEquals(id("dining"), suggestion?.categoryId)
        assertEquals(ClassificationRules().exactMatchBps, suggestion?.provenance?.confidenceBps)
    }

    /**
     * Input:  a real card descriptor, with the merchant buried in noise and punctuation.
     * Output: the same category, at the lower word rate — this is the shape almost every merchant
     *         actually arrives in, so a rule that only fired on the bare name would be near-useless.
     */
    @Test
    fun `a merchant inside a descriptor matches at the word rate`() {
        val suggestion = suggest("SWIGGY*ORDER 7781")

        assertEquals(id("dining"), suggestion?.categoryId)
        assertEquals(ClassificationRules().wordMatchBps, suggestion?.provenance?.confidenceBps)
    }

    /** Input: an alternative from a multi-literal row. Output: that row's category, cited by id. */
    @Test
    fun `any alternative of a regex row matches, and the row is what gets cited`() {
        val suggestion = suggest("UPI/BESCOM BILL PAY/9876")

        assertEquals(id("utilities"), suggestion?.categoryId)
        assertEquals(
            listOf("CLS-MER-004"),
            suggestion?.provenance?.evidence?.map { it.ruleId },
        )
    }

    /** Input: a merchant matching no rule at all. Output: nothing — §8.1's "Uncategorised" prompt. */
    @Test
    fun `an unknown merchant proposes nothing`() {
        assertNull(suggest("SHARMA GENERAL STORE"))
    }

    // --- the substring traps ----------------------------------------------------------------------

    /**
     * The test this engine exists to keep passing.
     *
     * Why:    `CLS-MER-010` matches on the literal `lic`. Licious is a large Indian meat-delivery
     *         service, and a substring match files every order from it under **Insurance** — a
     *         recurring food spend disappearing into a NEED category the user would never think to
     *         audit. `publicis` and `delicious` are the same bug wearing different words.
     * Input:  three merchants that contain `lic` and are not insurers.
     * Output: no suggestion for any of them.
     */
    @Test
    fun `a literal inside a longer word is not a match`() {
        listOf("LICIOUS", "PUBLICIS INDIA", "DELICIOUS BAKES").forEach { merchant ->
            assertNull("'$merchant' was classified by a substring match", suggest(merchant))
        }
    }

    /**
     * Input:  `GARLIC & GREENS KITCHEN`, which ends a word with the literal `lic`.
     * Output: nothing. Asserted apart from the case above because it is the *other* boundary — the
     *         literal at the end of a word rather than at its start — and a one-sided check would
     *         pass one of these two tests and fail the other.
     */
    @Test
    fun `a literal at the end of a longer word is not a match`() {
        assertNull(suggest("GARLIC & GREENS KITCHEN"))
    }

    /** Input: `LIC OF INDIA`. Output: Insurance — the same literal, this time as a real word. */
    @Test
    fun `the same literal as a whole word still matches`() {
        assertEquals(id("insurance"), suggest("LIC OF INDIA PREMIUM")?.categoryId)
    }

    /**
     * The regression guard on a knowledge-base row this issue had to fix.
     *
     * Why:    `CLS-MER-011` shipped in 4.1 matching the bare literal `coin`, and 4.2 is the first
     *         code that reads it. As a whole word it files a laundromat, a coin dealer and a coin
     *         collector under **Investment** — money the 50/30/20 view would then count as saving.
     *         The fix was a data row, not a special case in this file: `coin` was dropped at
     *         version 1.1, since `zerodha` already covers every real descriptor for Zerodha's Coin.
     * Input:  a merchant that is a coin business and not an investment.
     * Output: nothing.
     */
    @Test
    fun `the dropped coin literal no longer classifies a laundromat as an investment`() {
        assertNull(suggest("COIN LAUNDRY SERVICES"))
        assertEquals(id("investment"), suggest("ZERODHA BROKING LTD")?.categoryId)
    }

    // --- refusals ---------------------------------------------------------------------------------

    /**
     * Input:  a merchant naming two rules that point at different categories.
     * Output: nothing. Picking the first would make the knowledge base's *file order* decide where
     *         the user's money is filed, which is not a decision an ordering should be making.
     */
    @Test
    fun `two rules naming different categories propose nothing`() {
        assertNull(suggest("AMAZON PAY NETFLIX RECHARGE"))
    }

    /**
     * Input:  a merchant whose matched category is not in the profile's taxonomy — the user deleted
     *         Dining, or renamed it before ever categorising a Swiggy order.
     * Output: nothing. §8.1 gets to suggest a category; it does not get to re-create one the user
     *         removed (P-07).
     */
    @Test
    fun `a category the profile does not have proposes nothing`() {
        val withoutDining = liveCategories.filterNot { it.id == id("dining") }

        assertNull(suggest("swiggy", categories = withoutDining))
    }

    /** Input: blank and whitespace-only merchants. Output: nothing, and no crash. */
    @Test
    fun `a blank merchant proposes nothing`() {
        listOf("", "   ", "\t").forEach { assertNull(suggest(it)) }
    }

    /**
     * The floor cannot be set where it would silently switch the knowledge base off.
     *
     * Why:    a floor above [ClassificationRules.wordMatchBps] would leave the knowledge-base tier
     *         firing only on merchants typed with no descriptor at all — almost none of them — while
     *         every test that scores it against bare names kept passing. The tier would look alive
     *         and classify nothing. Refusing the configuration is the only way that shows up.
     * Input:  a floor one basis point above what a word match is worth.
     * Output: the rule set refuses to be built.
     */
    @Test
    fun `a floor above the word rate is refused rather than silently disabling the tier`() {
        assertThrows(IllegalArgumentException::class.java) {
            ClassificationRules(minConfidenceBps = ClassificationRules().wordMatchBps + 1)
        }
    }

    /**
     * Input:  a floor raised to just under the exact rate, and a merchant that matches exactly.
     * Output: still Dining — the floor is compared against the confidence the match earned, not
     *         against a constant, so a raised floor narrows what fires rather than stopping it.
     */
    @Test
    fun `a raised floor still admits a match that clears it`() {
        val strict = ClassificationRules(minConfidenceBps = 9_000, wordMatchBps = 9_000)

        assertEquals(id("dining"), suggest("swiggy", rules = strict)?.categoryId)
    }

    // --- tier (a): the user's own corrections ------------------------------------------------------

    /**
     * Input:  a merchant the knowledge base has never heard of, filed once by the user.
     * Output: the category they chose, at full confidence, citing `CLS-USER-HISTORY`. One correction
     *         is a user rule — §8.1(a) says "from the user's correction history", not "from a
     *         pattern in it".
     */
    @Test
    fun `one past correction is already a rule`() {
        val suggestion =
            suggest(
                "SHARMA GENERAL STORE",
                history = listOf(MerchantHistoryRow(id("groceries"), count = 1)),
            )

        assertEquals(id("groceries"), suggestion?.categoryId)
        assertEquals(BPS_FULL, suggestion?.provenance?.confidenceBps)
        assertEquals(listOf("CLS-USER-HISTORY"), suggestion?.provenance?.evidence?.map { it.ruleId })
    }

    /**
     * Input:  a merchant the user has filed under two categories, two times to one.
     * Output: nothing. 6 667 bps is under the floor, so an inconsistent history defers rather than
     *         imposing a majority the user never agreed to — and, crucially, it does **not** fall
     *         through to the knowledge base either, because the user has demonstrably formed their
     *         own opinion about this merchant.
     */
    @Test
    fun `an inconsistent history proposes nothing`() {
        val suggestion =
            suggest(
                "PVR CINEMAS",
                history =
                    listOf(
                        MerchantHistoryRow(id("entertainment"), count = 2),
                        MerchantHistoryRow(id("dining"), count = 1),
                    ),
            )

        assertNull(suggestion)
    }

    /**
     * Input:  a history row naming a category that has since been deleted.
     * Output: nothing — the same rule the knowledge-base tier follows, for the same reason.
     */
    @Test
    fun `a history row naming a deleted category proposes nothing`() {
        val suggestion =
            suggest("SHARMA GENERAL STORE", history = listOf(MerchantHistoryRow("category:gone", count = 4)))

        assertNull(suggestion)
    }

    // --- precedence between the tiers ---------------------------------------------------------------

    /**
     * §8.1's precedence, stated as a test.
     * Input:  Swiggy, which the knowledge base files under Dining, and which this user has twice
     *         filed under Groceries — they order instamart, not dinner.
     * Output: Groceries. **The user's correction outranks anything shipped**, and a suggestion that
     *         kept overruling their own past decision would be worse than no suggestion at all.
     */
    @Test
    fun `the user's own filing beats the knowledge base`() {
        val suggestion =
            suggest("SWIGGY*ORDER 7781", history = listOf(MerchantHistoryRow(id("groceries"), count = 2)))

        assertEquals(id("groceries"), suggestion?.categoryId)
        assertEquals(listOf("CLS-USER-HISTORY"), suggestion?.provenance?.evidence?.map { it.ruleId })
    }

    /**
     * Input:  a history too inconsistent to fire, on a merchant the knowledge base *does* know.
     * Output: nothing. The tier that owns the merchant keeps it: falling through to the knowledge
     *         base here would propose Dining to a user who has already shown they disagree with it.
     */
    @Test
    fun `an inconsistent history does not fall through to the knowledge base`() {
        val suggestion =
            suggest(
                "swiggy",
                history =
                    listOf(
                        MerchantHistoryRow(id("groceries"), count = 1),
                        MerchantHistoryRow(id("dining"), count = 1),
                    ),
            )

        assertNull(suggestion)
    }

    /**
     * Input:  a history whose only rows name categories that no longer exist, on a known merchant.
     * Output: Dining. An unusable history is not an opinion — there is nothing left to overrule the
     *         knowledge base with, so Stage 1 carries on down §8.1's list.
     */
    @Test
    fun `an unusable history falls through to the knowledge base`() {
        val suggestion =
            suggest("swiggy", history = listOf(MerchantHistoryRow("category:gone", count = 3)))

        assertEquals(id("dining"), suggestion?.categoryId)
    }

    // --- provenance -------------------------------------------------------------------------------

    /**
     * Input:  any successful classification.
     * Output: asserts the engine names itself and its version, and stamps the instant it was
     *         *given* rather than one it read — TIM-001, and what makes the eval set reproducible.
     */
    @Test
    fun `provenance identifies the engine and uses the caller's instant`() {
        val provenance = suggest("zomato")!!.provenance

        assertEquals(NOW, provenance.computedAtUtcMillis)
        assertTrue("the engine did not name itself", provenance.engineId.isNotBlank())
        assertTrue("the engine did not carry a version (AI-ARC-006)", provenance.engineVersion.isNotBlank())
    }

    /**
     * Input:  the same merchant classified twice.
     * Output: byte-identical results (P-08). The engine reads no clock and holds no state, so this
     *         is the property that lets a stored suggestion still be explained in a year.
     */
    @Test
    fun `the same input always produces the same output`() {
        assertEquals(suggest("IOCL PETROL PUMP NO 4"), suggest("IOCL PETROL PUMP NO 4"))
    }

    // --- helpers -----------------------------------------------------------------------------------

    /**
     * Runs the engine over one merchant.
     * Result: the suggestion, or `null` when Stage 1 deferred. Fails the test on an `Err`, which no
     *         well-formed input can produce.
     * Input:  [merchant]; [history] — defaults to none; [categories] — defaults to the seeded
     *         taxonomy; [rules] — defaults to the knowledge base. Output: `CategorySuggestion?`.
     */
    private fun suggest(
        merchant: String,
        history: List<MerchantHistoryRow> = emptyList(),
        categories: List<Category> = liveCategories,
        rules: ClassificationRules = ClassificationRules(),
    ): CategorySuggestion? {
        val outcome =
            engine.suggest(
                ClassificationInput(
                    merchant = merchant,
                    categories = categories,
                    nowUtcMillis = NOW,
                    history = history,
                    rules = rules,
                ),
            )
        assertTrue("the engine errored on a well-formed input: $merchant", outcome is Ok)
        return (outcome as Ok).value
    }

    private companion object {
        /** Fixed, so every assertion here is reproducible (P-08, TIM-001). */
        const val NOW = 1_786_082_400_000L

        /**
         * The seeded taxonomy as a profile actually holds it.
         * Why:    built from [CategorySeed] rather than hand-listed, so a category renamed in the
         *         knowledge base cannot leave this test asserting against a name the app no longer
         *         seeds — the failure would look like an engine bug and be a fixture bug.
         */
        val liveCategories: List<Category> =
            CategorySeed.rows.map { Category(id = id(it.key), name = it.name, nature = it.nature, isSystem = true) }

        /** The id shape `RoomCategoryRepository.seededId` produces, for one seed key. */
        fun id(key: String): String = "profile-1:category:$key"
    }
}
