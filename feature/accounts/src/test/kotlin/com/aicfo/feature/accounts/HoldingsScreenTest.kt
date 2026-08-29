package com.aicfo.feature.accounts

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollTo
import com.aicfo.core.designsystem.theme.CfoTheme
import com.aicfo.core.model.AssetClass
import com.aicfo.core.model.EngineProvenance
import com.aicfo.core.model.Money
import com.aicfo.core.model.Quantity
import com.aicfo.domain.engines.investment.HoldingPerformance
import com.aicfo.domain.engines.investment.XirrUnavailable
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Compose tests for the holdings screen (issue 6.3; §11, P-02, P-03, P-07).
 *
 * Why:  three claims here can only be checked once something is rendered, and each fails silently
 *       in a different direction:
 *
 *       - **the §11.1 disclaimer**, which the SRS says MUST appear in the module footer. Nothing in
 *         Gradle or the state class can enforce a string being on screen, so this is the only place
 *         it is enforced at all — and a footer that quietly disappears in a refactor is exactly the
 *         kind of omission nobody notices until it matters (P-07).
 *       - **an unpriced holding showing a prompt rather than ₹0.** The state carries `null`, but a
 *         composable that reached for `?: Money.ZERO` would render a total loss, and the state test
 *         would still pass.
 *       - **a rate rendered from integer basis points.** 1567 bps must read as 15.6%, and a
 *         negative rate under one percent must keep its sign — `-50 / 100` is `0` in `Int`
 *         division, so a small loss is one careless expression away from rendering as a gain.
 * What: the disclaimer, the empty state, a priced row, an unpriced row, and the rate rendering.
 * Result: the first screen showing a money-weighted return is exercised on every run.
 * Changelog: 2026-08-24 — Created for issue 6.3.
 *
 * On the JVM via Robolectric, following `AccountEditorLoanFieldsTest` beside it.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w400dp-h1200dp")
class HoldingsScreenTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    /** Result: a string from this module's resources. Input: [id]; [args]. Output: the text. */
    private fun text(
        id: Int,
        vararg args: Any,
    ): String = compose.activity.getString(id, *args)

    /**
     * One priced holding.
     * Result: the performance. Input: the fields that vary. Output: [HoldingPerformance].
     */
    private fun performance(
        name: String = "Parag Parikh Flexi Cap",
        currentValue: Money? = Money(941_160),
        gain: Money? = Money(53_160),
        xirrBps: Int? = 1_567,
        unavailable: XirrUnavailable? = null,
    ) = HoldingPerformance(
        holdingId = "holding:ppfas",
        name = name,
        accountId = "account:zerodha",
        assetClass = AssetClass.EQUITY,
        netQuantity = Quantity(120 * Quantity.SCALE),
        currentValue = currentValue,
        invested = Money(1_150_000),
        realised = Money(262_000),
        gain = gain,
        xirrBps = xirrBps,
        xirrUnavailable = unavailable,
        provenance =
            EngineProvenance(
                engineId = "investment-xirr",
                engineVersion = "1.0",
                computedAtUtcMillis = 1L,
            ),
    )

    /** Renders the screen in the app's theme with [uiState]. */
    private fun render(uiState: HoldingsUiState) {
        compose.setContent {
            CfoTheme {
                HoldingsContent(uiState = uiState, onEvent = {}, onDone = {})
            }
        }
    }

    /**
     * Input:  a screen with holdings on it.
     * Output: asserts §11.1's wording is on screen.
     *
     * The only enforcement this requirement has anywhere in the build.
     */
    @Test
    fun `the SEBI disclaimer is on screen whenever a figure is`() {
        render(HoldingsUiState(holdings = listOf(performance()), isLoading = false))

        compose.onNodeWithText(text(R.string.holdings_disclaimer)).performScrollTo().assertIsDisplayed()
    }

    /**
     * Input:  a screen with the editor open.
     * Output: asserts the disclaimer is still there.
     *
     * A footer that comes and goes is one the reader learns to stop seeing, and the editor is
     * exactly where a person is deciding what to enter.
     */
    @Test
    fun `the disclaimer stays while the editor is open`() {
        render(HoldingsUiState(editor = HoldingEditorState(), isLoading = false))

        compose.onNodeWithText(text(R.string.holdings_disclaimer)).performScrollTo().assertIsDisplayed()
    }

    /** Input: no holdings and nothing loading. Output: asserts the invitation rather than a list. */
    @Test
    fun `an account with nothing in it invites the user to add something`() {
        render(HoldingsUiState(isLoading = false))

        compose.onNodeWithText(text(R.string.holdings_empty)).performScrollTo().assertIsDisplayed()
    }

    /** Input: a priced holding. Output: asserts its name, value, cost and gain are all shown. */
    @Test
    fun `a priced holding shows its value beside what it cost`() {
        render(HoldingsUiState(holdings = listOf(performance()), isLoading = false))

        compose.onNodeWithText("Parag Parikh Flexi Cap").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(text(R.string.holdings_value, "₹9,411.60")).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(text(R.string.holdings_cost, "₹11,500.00")).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(text(R.string.holdings_gain, "₹531.60")).performScrollTo().assertIsDisplayed()
    }

    /**
     * Input:  a holding that has never been priced.
     * Output: asserts the prompt, and that no zero value is rendered.
     *
     * The case that would report the user's whole cost as a loss if the composable substituted zero
     * for an absent price (P-03).
     */
    @Test
    fun `an unpriced holding prompts for a price instead of showing zero`() {
        render(
            HoldingsUiState(
                holdings =
                    listOf(
                        performance(
                            currentValue = null,
                            gain = null,
                            xirrBps = null,
                            unavailable = XirrUnavailable.SAME_SIGN,
                        ),
                    ),
                isLoading = false,
            ),
        )

        compose.onNodeWithText(text(R.string.holdings_unpriced)).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(text(R.string.holdings_return_same_sign)).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(text(R.string.holdings_value, "₹0.00")).assertDoesNotExist()
    }

    /** Input: a gain of 15.67%. Output: asserts it renders from integer bps, to one decimal. */
    @Test
    fun `a rate renders from basis points without floating point`() {
        render(HoldingsUiState(holdings = listOf(performance(xirrBps = 1_567)), isLoading = false))

        val percent = text(R.string.holdings_return_percent, "", 15, 6)
        compose.onNodeWithText(text(R.string.holdings_return, percent)).performScrollTo().assertIsDisplayed()
    }

    /**
     * Input:  a loss of half a percent.
     * Output: asserts the sign survives.
     *
     * `-50 / 100` is `0` in `Int` division, so building the sign from the whole-percent part alone
     * would render this small loss as a gain — true-looking, and wrong.
     */
    @Test
    fun `a loss smaller than one percent keeps its minus sign`() {
        render(HoldingsUiState(holdings = listOf(performance(xirrBps = -50)), isLoading = false))

        val percent = text(R.string.holdings_return_percent, "-", 0, 5)
        compose.onNodeWithText(text(R.string.holdings_return, percent)).performScrollTo().assertIsDisplayed()
    }
}
