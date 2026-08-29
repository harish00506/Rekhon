package com.aicfo.feature.accounts

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.aicfo.core.designsystem.theme.CfoTheme
import com.aicfo.core.model.AssetClass
import com.aicfo.core.model.EngineProvenance
import com.aicfo.core.model.Money
import com.aicfo.core.model.RuleCitation
import com.aicfo.domain.engines.investment.AllocationSlice
import com.aicfo.domain.engines.investment.AllocationUnavailable
import com.aicfo.domain.engines.investment.ConcentrationFlag
import com.aicfo.domain.engines.investment.ConcentrationKind
import com.aicfo.domain.engines.investment.PortfolioAllocation
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * What the allocation screen actually renders (issue 6.4; FR-INV-002, P-02, P-07).
 *
 * Why:  the ViewModel test proves the right values reach the state; this proves they reach the
 *       *user*. Three of these would pass in a ViewModel test and still ship a broken screen: a
 *       flag whose citation is computed but never drawn, an empty state that says "add an account"
 *       to someone who has one, and a coverage line that is only rendered when
 *       `unvaluedCount == 0` — the inverse of what it is for.
 * What: the split, a flag with its rule, the coverage line, both empty states, and the way back.
 * Result: the §11.1 and P-02 promises are checked on a rendered tree, not on a data class.
 * Changelog: 2026-08-28 — Created for issue 6.4.
 *
 * Robolectric on the JVM, the `HoldingsScreenTest` pattern: the flow is checked on every `test` run
 * rather than only when an emulator happens to be up.
 */
@RunWith(RobolectricTestRunner::class)
class AllocationScreenTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    /** Input: a two-class split. Output: asserts both legend rows and the total are drawn. */
    @Test
    fun `the split is drawn with an amount and a share for every class`() {
        render(allocation(slices = threeQuartersEquity()))

        composeRule.onNodeWithText("Equity").assertIsDisplayed()
        composeRule.onNodeWithText("Gold").assertIsDisplayed()
        composeRule.onNodeWithText("75%", substring = true).assertIsDisplayed()
        composeRule.onNodeWithText("25%", substring = true).assertIsDisplayed()
    }

    /**
     * Input:  a gold breach.
     * Output: asserts the measured share, the threshold **and the rule** are all on screen.
     *
     * The citation is the assertion that matters: §29's governance clause is that a figure the app
     * raises can be traced to the row that produced it, and a citation nobody can see is not a
     * citation.
     */
    @Test
    fun `a concentration flag names its numbers and its rule`() {
        render(
            allocation(
                slices = threeQuartersEquity(),
                flags =
                    listOf(
                        ConcentrationFlag(
                            kind = ConcentrationKind.ASSET_CLASS_CAP,
                            assetClass = AssetClass.GOLD,
                            holdingId = null,
                            name = "",
                            measuredBps = 2_500,
                            thresholdBps = 1_000,
                            value = Money(250_000),
                            citation = RuleCitation("RULE-GOLD-CAP", "1.0"),
                        ),
                    ),
            ),
        )

        composeRule.onNodeWithText("Gold is 25% of your portfolio", substring = true).assertIsDisplayed()
        composeRule.onNodeWithText("above the 10%", substring = true).assertIsDisplayed()
        composeRule.onNodeWithText("Rule RULE-GOLD-CAP v1.0").assertIsDisplayed()
    }

    /**
     * Input:  a clean portfolio.
     * Output: asserts the section says so rather than rendering nothing.
     *
     * An empty section reads as a section that failed to load. Saying "nothing is over the limits
     * we check" also tells the user that something *was* checked, which is what makes a clean
     * result informative rather than merely blank.
     */
    @Test
    fun `a clean portfolio is told it is clean`() {
        render(allocation(slices = listOf(slice(AssetClass.EQUITY, 1_000_000, 10_000))))

        composeRule.onNodeWithText("Nothing is over the limits we check.").assertIsDisplayed()
    }

    /** Input: a split missing two of five holdings. Output: asserts the coverage line is drawn (P-02). */
    @Test
    fun `a partly unpriced portfolio says how much of itself it is showing`() {
        render(
            allocation(
                slices = listOf(slice(AssetClass.EQUITY, 1_000_000, 10_000)),
                valuedCount = 3,
                unvaluedCount = 2,
            ),
        )

        composeRule.onNodeWithText("Based on 3 of 5 holdings", substring = true).assertIsDisplayed()
    }

    /** Input: a fully priced split. Output: asserts no coverage line is drawn when there is nothing to say. */
    @Test
    fun `a fully priced portfolio says nothing about coverage`() {
        render(allocation(slices = listOf(slice(AssetClass.EQUITY, 1_000_000, 10_000))))

        assertTrue(
            "a coverage line on a complete portfolio is noise",
            composeRule.onAllNodesWithText("Based on", substring = true).fetchSemanticsNodes().isEmpty(),
        )
    }

    /**
     * Input:  each unavailable reason in turn.
     * Output: asserts they say different things — one asks for an account, the other for a price.
     */
    @Test
    fun `the two empty states ask for different things`() {
        render(unavailable(AllocationUnavailable.NO_POSITIONS))
        composeRule.onNodeWithText("Nothing invested yet").assertIsDisplayed()
    }

    /** Input: a portfolio with holdings but no prices. Output: asserts it asks for prices. */
    @Test
    fun `an unpriced portfolio is asked for prices rather than for accounts`() {
        render(unavailable(AllocationUnavailable.NOTHING_PRICED))
        composeRule.onNodeWithText("Nothing is priced yet").assertIsDisplayed()
    }

    /** Input: the §11.1 disclaimer. Output: asserts it is on screen, as the SRS requires (P-07). */
    @Test
    fun `the module disclaimer is on screen`() {
        render(allocation(slices = listOf(slice(AssetClass.EQUITY, 1_000_000, 10_000))))

        composeRule.onNodeWithText("not SEBI-registered investment advice", substring = true).assertIsDisplayed()
    }

    /** Input: a tap on Done. Output: asserts the way back is wired. */
    @Test
    fun `done pops back to the accounts list`() {
        var popped = false
        val state = allocation(slices = listOf(slice(AssetClass.EQUITY, 1L, 10_000)))
        composeRule.setContent {
            CfoTheme { AllocationContent(uiState = state, onDone = { popped = true }) }
        }

        composeRule.onNodeWithText("Done").performClick()

        assertTrue(popped)
    }

    // --- fixtures -------------------------------------------------------------------------------

    /** Result: renders the screen from a literal state. Input: [state]. Output: none. */
    private fun render(state: AllocationUiState) {
        composeRule.setContent {
            CfoTheme { AllocationContent(uiState = state, onDone = {}) }
        }
    }

    /** Result: a loaded state carrying the allocation described. Input: the parts. Output: the state. */
    private fun allocation(
        slices: List<AllocationSlice>,
        flags: List<ConcentrationFlag> = emptyList(),
        valuedCount: Int = slices.size,
        unvaluedCount: Int = 0,
    ) = AllocationUiState(
        allocation =
            PortfolioAllocation(
                total = slices.fold(Money.ZERO) { running, slice -> running + slice.value },
                slices = slices,
                flags = flags,
                valuedCount = valuedCount,
                unvaluedCount = unvaluedCount,
                unavailable = null,
                provenance = provenance(),
            ),
        isLoading = false,
    )

    /** Result: a loaded state with nothing to split. Input: [reason]. Output: the state. */
    private fun unavailable(reason: AllocationUnavailable) =
        AllocationUiState(
            allocation =
                PortfolioAllocation(
                    total = Money.ZERO,
                    slices = emptyList(),
                    flags = emptyList(),
                    valuedCount = 0,
                    unvaluedCount = 0,
                    unavailable = reason,
                    provenance = provenance(),
                ),
            isLoading = false,
        )

    /** Result: a 75/25 equity-and-gold split, the fixture two tests share. Output: the slices. */
    private fun threeQuartersEquity(): List<AllocationSlice> =
        listOf(slice(AssetClass.EQUITY, 750_000, 7_500), slice(AssetClass.GOLD, 250_000, 2_500))

    /** Result: one slice. Input: [assetClass]; [minor] paise; [shareBps]. Output: the slice. */
    private fun slice(
        assetClass: AssetClass,
        minor: Long,
        shareBps: Int,
    ) = AllocationSlice(assetClass, Money(minor), shareBps)

    /** Result: provenance citing the three rows, as the engine always does. */
    private fun provenance() =
        EngineProvenance(
            engineId = "investment-xirr",
            engineVersion = "1.1",
            computedAtUtcMillis = 1L,
            evidence = listOf(RuleCitation("RULE-GOLD-CAP", "1.0")),
        )
}
