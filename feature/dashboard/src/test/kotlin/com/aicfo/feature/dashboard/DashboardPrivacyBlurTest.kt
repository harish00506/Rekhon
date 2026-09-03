package com.aicfo.feature.dashboard

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.text.AnnotatedString
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.aicfo.core.designsystem.component.LocalPrivacyBlur
import com.aicfo.core.designsystem.theme.CfoTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The privacy blur, asserted against a real composition (issue 5.3; §23, FR-PRIV-*, P-01).
 *
 * Why:  §5.3's acceptance criterion is "a Compose UI test asserts amounts are hidden", and the
 *       reason it asks for a *UI* test rather than a unit test is that this feature's failure mode
 *       is a **missed call site**, not a broken function. `PrivacyBlurTest` in `:core:designsystem`
 *       proves the mask hides what it should; it cannot prove that the dashboard actually uses it.
 *       Amounts reach this screen by two paths — `CfoAmountText` and `maskedAmount(...)` inside a
 *       `stringResource` — and the second was, until this issue, a plain `MoneyFormatter.format`.
 *       One missed conversion leaves a real figure on the screen with everything around it masked,
 *       which is the exact shape of bug a reviewer skims past.
 *
 *       So the assertion is deliberately **not** "the masked strings are present". It is *no
 *       rendered text anywhere in the tree contains a rupee figure* — a whole-screen sweep that a
 *       new card added later fails automatically unless its author used the helper.
 * What: renders the fully populated dashboard blurred and unblurred, and sweeps every semantics node.
 * Result: an amount that escapes the blur fails the build, naming the text that escaped.
 * Changelog: 2026-08-16 — Created for issue 5.3.
 *
 * Robolectric, so it runs on every `./gradlew unitTests` rather than only when a device is attached
 * — the argument `:feature:onboarding`'s `OnboardingFlowTest` records, and this repo's CI has never
 * had an emulator.
 */
@RunWith(AndroidJUnit4::class)
class DashboardPrivacyBlurTest {
    @get:Rule
    val compose = createComposeRule()

    /**
     * The assertion this file exists for.
     * Input:  the fully populated dashboard, blurred.
     * Output: fails naming any node still rendering a rupee figure.
     */
    @Test
    fun `no amount survives the blur anywhere on the screen`() {
        compose.setContent { Dashboard(blurred = true) }

        val leaked = compose.renderedTexts().filter { it.containsAmount() }

        assertTrue("these amounts escaped the privacy blur: $leaked", leaked.isEmpty())
    }

    /**
     * The control, and it is load-bearing.
     * Input:  the same dashboard, unblurred.
     * Output: asserts amounts **are** on screen.
     *
     * Why:    without it the test above passes perfectly against a screen that renders no figures at
     *         all — a fixture that lost its data, a composable that early-returned, a `Dashboard`
     *         helper that silently drew an empty state. "Nothing leaked" is only meaningful if there
     *         was something to leak.
     */
    @Test
    fun `amounts are on screen when the blur is off`() {
        compose.setContent { Dashboard(blurred = false) }

        assertTrue(
            "the fixture rendered no amounts at all, so the blurred case proves nothing",
            compose.renderedTexts().any { it.containsAmount() },
        )
    }

    /**
     * Input:  the blurred dashboard.
     * Output: asserts the masked amounts are announced as hidden rather than read aloud.
     *
     * Why:    a blur that leaves the screen reader saying "Safe to spend this month, ₹34,600" has
     *         moved the leak from the screen to the speaker, which in an open-plan office is not an
     *         improvement. `CfoAmountText` discards the caller's content description while blurred
     *         for exactly this reason, and this is what holds it there.
     */
    @Test
    fun `masked amounts are announced as hidden, not read aloud`() {
        compose.setContent { Dashboard(blurred = true) }

        val descriptions = compose.contentDescriptions()
        assertTrue("no amount was announced as hidden", descriptions.any { it == AMOUNT_HIDDEN })
        assertTrue(
            "a content description read an amount out loud: $descriptions",
            descriptions.none { it.containsAmount() },
        )
    }

    // --- fixture ------------------------------------------------------------------------------

    /**
     * Renders the dashboard with every section populated.
     * Why:    the same fixture the screenshot test uses, so the two agree on what "a full screen"
     *         means and neither can drift into asserting a screen the other never renders.
     * Result: the composition. Input: [blurred]. Output: none.
     */
    @Composable
    private fun Dashboard(blurred: Boolean) {
        CompositionLocalProvider(LocalPrivacyBlur provides blurred) {
            CfoTheme {
                DashboardContent(
                    uiState = populatedDashboardState(),
                    onEvent = {},
                    actions =
                        DashboardActions(
                            onNavigateToTransactions = {},
                            onNavigateToAccounts = {},
                            onNavigateToBudgets = {},
                            onNavigateToGoals = {},
                            onNavigateToSettings = {},
                        ),
                )
            }
        }
    }

    private companion object {
        /** Matches `core/designsystem`'s `cfo_amount_hidden`. */
        const val AMOUNT_HIDDEN = "Amount hidden"

        /**
         * A rupee sign followed by a digit — the shape of every figure this app renders.
         * Why: matching on `₹` alone would flag the mask itself, and on a digit alone would flag
         *      dates, percentages and category names.
         */
        val AMOUNT = Regex("""₹\s*\d""")
    }

    /** Result: `true` when this text contains a rendered rupee figure. */
    private fun String.containsAmount(): Boolean = AMOUNT.containsMatchIn(this)

    /** Result: every string the tree actually draws. */
    private fun ComposeContentTestRule.renderedTexts(): List<String> =
        onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.Text))
            .fetchSemanticsNodes()
            .flatMap { node -> node.config.getOrNull(SemanticsProperties.Text).orEmpty() }
            .map(AnnotatedString::text)

    /** Result: every content description in the tree — what a screen reader would announce. */
    private fun ComposeContentTestRule.contentDescriptions(): List<String> =
        onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.ContentDescription))
            .fetchSemanticsNodes()
            .flatMap { node -> node.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty() }
}
