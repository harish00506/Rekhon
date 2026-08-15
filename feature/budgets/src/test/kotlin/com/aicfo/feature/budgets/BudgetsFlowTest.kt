package com.aicfo.feature.budgets

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import com.aicfo.core.designsystem.theme.CfoTheme
import com.aicfo.core.model.Money
import com.aicfo.domain.engines.budget.BudgetAlertBand
import com.aicfo.domain.engines.budget.VarianceDirection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Compose tests for the budgets screen (issue 4.4; §21.5's "critical flows", FR-BUD-001/002/003).
 *
 * Why:  the ViewModel tests prove what the state does; these prove the screen actually renders it and
 *       routes the taps back. Four things only a rendered test catches: **the four FR-BUD-003 figures
 *       reaching the screen as sentences a user can read**, which a state test cannot distinguish
 *       from a blank label; **a withheld projection saying so rather than showing nothing**;
 *       **a suggestion showing its reasoning and its rule** (P-02), which is the requirement's whole
 *       substance and is invisible in the state; and **Accept being a button** rather than something
 *       that happened already (P-07).
 * What: the planned list, the unplanned section, the suggestion card, the amount sheet, the empty
 *       state and the error banner.
 * Result: the screen is exercised on every `test` run, not only when a device is attached.
 * Changelog: 2026-08-11 — Created for issue 4.4.
 *            2026-08-15 — Issue 4.6: added the monthly-review cases.
 *
 * On the JVM via Robolectric, following `:feature:categories`'s `CategoriesFlowTest`.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w400dp-h1200dp")
class BudgetsFlowTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    /**
     * Result: a string from this module's resources, resolved the way the screen resolves it.
     *
     * The empty-args branch is not a nicety. `getString(id, *args)` runs `String.format` even with
     * nothing to substitute, which turns a literal `%` into an invalid conversion and throws —
     * while `stringResource` in the composable never formats an argument-less string. Without the
     * branch this helper and the screen disagree about what a string containing a percent sign is,
     * and the test fails for a reason that has nothing to do with the screen.
     *
     * Input:  [id]; [args]. Output: the text.
     * Changelog: 2026-08-13 — Issue 4.5: split the no-argument case out; the band labels are the
     *            first strings here that contain a percent sign and take no arguments.
     */
    private fun text(
        id: Int,
        vararg args: Any,
    ): String = if (args.isEmpty()) compose.activity.getString(id) else compose.activity.getString(id, *args)

    // --- FR-BUD-003's figures ---------------------------------------------------------------------

    @Test
    fun `a budget renders spent, remaining, pace and projection`() {
        setContent(
            BudgetsUiState(
                isLoading = false,
                budgets =
                    listOf(
                        budgetRow(
                            name = "Groceries",
                            budgeted = Money(1_000_000L),
                            spent = Money(400_000L),
                            safePace = Money(500_000L),
                            projected = Money(800_000L),
                        ),
                    ),
            ),
        )

        compose.onNodeWithText("Groceries").assertIsDisplayed()
        compose.onNodeWithText(text(R.string.budgets_remaining, "₹6,000.00", "₹10,000.00")).assertIsDisplayed()
        // Stated as a comparison: "₹5,000" alone does not answer the question the user is asking.
        compose.onNodeWithText(text(R.string.budgets_pace_within, "₹5,000.00")).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.budgets_projection_within, "₹8,000.00")).assertIsDisplayed()
    }

    @Test
    fun `spending faster than the plan says so, and says where the month is heading`() {
        setContent(
            BudgetsUiState(
                isLoading = false,
                budgets =
                    listOf(
                        budgetRow(
                            name = "Dining",
                            budgeted = Money(500_000L),
                            spent = Money(400_000L),
                            safePace = Money(250_000L),
                            projected = Money(800_000L),
                        ),
                    ),
            ),
        )

        compose.onNodeWithText(text(R.string.budgets_pace_ahead, "₹2,500.00")).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.budgets_projection_over, "₹8,000.00")).assertIsDisplayed()
    }

    @Test
    fun `a month too young to project says so rather than showing nothing`() {
        setContent(
            BudgetsUiState(
                isLoading = false,
                budgets = listOf(budgetRow(name = "Groceries", projected = null)),
            ),
        )

        // An absent projection is a statement, not a gap. Rendering nothing would read as a bug, and
        // rendering a run rate from two days would be a number the app invented (P-03).
        compose.onNodeWithText(text(R.string.budgets_projection_pending)).assertIsDisplayed()
    }

    @Test
    fun `a rollover budget says on its face what was carried in`() {
        setContent(
            BudgetsUiState(
                isLoading = false,
                budgets = listOf(budgetRow(name = "Groceries", carriedOver = Money(150_000L), rollover = true)),
            ),
        )

        // A budget larger than the number the user typed must say why without being opened (P-02).
        compose.onNodeWithText(text(R.string.budgets_planned_with_rollover, "₹1,500.00")).assertIsDisplayed()
    }

    // --- FR-BUD-002's suggestions -----------------------------------------------------------------

    @Test
    fun `a suggestion shows the amount, the reasoning and the rule that produced it`() {
        setContent(
            BudgetsUiState(
                isLoading = false,
                suggestions =
                    listOf(
                        suggestionRow(
                            name = "Shopping",
                            amount = Money(690_000L),
                            median = Money(500_000L),
                            eventId = "diwali",
                            indexBps = 13_800,
                        ),
                    ),
            ),
        )

        compose.onNodeWithText("Shopping").assertIsDisplayed()
        // P-02 in one assertion: the number never appears without the reasoning behind it, and the
        // festival is named as a person would say it rather than as the id the knowledge base uses.
        val reason = text(R.string.budgets_reason_seasonal, "₹5,000.00", 38, text(R.string.budgets_season_diwali))
        compose.onNodeWithText(reason).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.budgets_reason_rule, "RULE-BUD-SUGGEST", "1.0")).assertIsDisplayed()
    }

    @Test
    fun `an unadjusted suggestion states the median without inventing a season`() {
        setContent(
            BudgetsUiState(isLoading = false, suggestions = listOf(suggestionRow(median = Money(500_000L)))),
        )

        compose.onNodeWithText(text(R.string.budgets_reason_median, "₹5,000.00")).assertIsDisplayed()
    }

    @Test
    fun `a suggestion is not applied until it is tapped`() {
        val events = mutableListOf<BudgetsEvent>()
        setContent(
            BudgetsUiState(isLoading = false, suggestions = listOf(suggestionRow(name = "Shopping"))),
            onEvent = { events += it },
        )

        // Nothing has happened yet, and the screen says so.
        assertTrue(events.isEmpty())
        compose.onNodeWithText(text(R.string.budgets_suggested_intro)).assertIsDisplayed()

        compose.onNodeWithText(text(R.string.budgets_suggestion_accept)).performScrollTo().performClick()

        assertEquals(listOf(BudgetsEvent.AcceptSuggestion("category:Shopping")), events)
    }

    // --- the sheet --------------------------------------------------------------------------------

    @Test
    fun `typing an amount that is not money leaves Save disabled`() {
        setContent(
            BudgetsUiState(
                isLoading = false,
                budgets = listOf(budgetRow(name = "Groceries")),
                editing = BudgetEditorState("category:Groceries", "Groceries"),
            ),
        )

        compose.onNodeWithText(text(R.string.budget_editor_save)).assertIsNotEnabled()
    }

    @Test
    fun `the sheet routes typing and the rollover choice back up`() {
        val events = mutableListOf<BudgetsEvent>()
        setContent(
            BudgetsUiState(
                isLoading = false,
                budgets = listOf(budgetRow(name = "Groceries")),
                editing = BudgetEditorState("category:Groceries", "Groceries", amountText = "1000"),
            ),
            onEvent = { events += it },
        )

        compose.onNodeWithText(text(R.string.budget_editor_title, "Groceries")).assertIsDisplayed()
        // The help line is what makes rollover unambiguous: it says a deficit is not carried.
        compose.onNodeWithText(text(R.string.budget_editor_rollover_help)).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.budget_editor_save)).assertIsEnabled().performClick()

        assertEquals(listOf(BudgetsEvent.Save), events)
    }

    @Test
    fun `the amount field accepts typing`() {
        val events = mutableListOf<BudgetsEvent>()
        setContent(
            BudgetsUiState(
                isLoading = false,
                budgets = listOf(budgetRow(name = "Groceries")),
                editing = BudgetEditorState("category:Groceries", "Groceries"),
            ),
            onEvent = { events += it },
        )

        compose.onNodeWithText(text(R.string.budget_editor_amount)).performTextInput("7500")

        assertEquals(listOf(BudgetsEvent.AmountChanged("7500")), events)
    }

    // --- the other states -------------------------------------------------------------------------

    @Test
    fun `a category with spending and no plan is offered a budget`() {
        val events = mutableListOf<BudgetsEvent>()
        setContent(
            BudgetsUiState(
                isLoading = false,
                budgets =
                    listOf(budgetRow(id = null, name = "Cabs", budgeted = Money.ZERO, spent = Money(300_000L))),
            ),
            onEvent = { events += it },
        )

        compose.onNodeWithText(text(R.string.budgets_section_unplanned)).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.budgets_set)).performScrollTo().performClick()

        assertEquals(listOf(BudgetsEvent.EditClicked("category:Cabs")), events)
    }

    @Test
    fun `a user with nothing planned is invited rather than shown an empty list`() {
        setContent(BudgetsUiState(isLoading = false))

        compose.onNodeWithText(text(R.string.budgets_empty_title)).assertIsDisplayed()
    }

    @Test
    fun `a failed read shows the banner and not the invitation`() {
        setContent(BudgetsUiState(isLoading = false, errorCode = "storage"))

        compose.onNodeWithText(text(R.string.budgets_error_storage)).assertIsDisplayed()
        // A database that would not open must not read as a user who has planned nothing.
        compose.onNodeWithText(text(R.string.budgets_empty_title)).assertDoesNotExist()
    }

    // --- FR-BUD-004: the band on the screen --------------------------------------------------------

    @Test
    fun `a warned category says so in words, not only in colour`() {
        // The band is announced as text on purpose. A chip that only changed colour would be
        // invisible to a colour-blind user, in a greyscale screenshot, and to this test.
        setContent(
            BudgetsUiState(
                isLoading = false,
                budgets = listOf(budgetRow(name = "Groceries", budgeted = Money(1_000_000L), spent = Money(800_000L))),
                alerts = listOf(alertRow(name = "Groceries", band = BudgetAlertBand.WARN)),
            ),
        )

        compose.onNodeWithText(text(R.string.budgets_band_warn)).performScrollTo().assertIsDisplayed()
    }

    @Test
    fun `an overspent category reads as over budget`() {
        setContent(
            BudgetsUiState(
                isLoading = false,
                budgets =
                    listOf(budgetRow(name = "Dining", budgeted = Money(1_000_000L), spent = Money(1_010_000L))),
                alerts = listOf(alertRow(name = "Dining", band = BudgetAlertBand.EXCEEDED)),
            ),
        )

        compose.onNodeWithText(text(R.string.budgets_band_exceeded)).performScrollTo().assertIsDisplayed()
    }

    @Test
    fun `the banner counts each band separately`() {
        // One overspend and two warnings must read as exactly that. A single count would tell the
        // user three categories are over budget, which is untrue of two of them.
        setContent(
            BudgetsUiState(
                isLoading = false,
                budgets = listOf(budgetRow(name = "Groceries")),
                alerts =
                    listOf(
                        alertRow(name = "Dining", band = BudgetAlertBand.EXCEEDED),
                        alertRow(name = "Groceries", band = BudgetAlertBand.WARN),
                        alertRow(name = "Fuel", band = BudgetAlertBand.WARN),
                    ),
            ),
        )

        compose.onNodeWithText(plural(R.plurals.budgets_alert_banner_exceeded, 1)).assertIsDisplayed()
        compose.onNodeWithText(plural(R.plurals.budgets_alert_banner_warned, 2)).assertIsDisplayed()
    }

    @Test
    fun `a budget inside its plan shows no band at all`() {
        // The absence is the assertion. A chip that rendered for every row would make the two that
        // matter unfindable, which is the same failure as not showing them.
        setContent(
            BudgetsUiState(
                isLoading = false,
                budgets = listOf(budgetRow(name = "Groceries", budgeted = Money(1_000_000L), spent = Money(400_000L))),
            ),
        )

        compose.onNodeWithText(text(R.string.budgets_band_warn)).assertDoesNotExist()
        compose.onNodeWithText(text(R.string.budgets_band_exceeded)).assertDoesNotExist()
    }

    @Test
    fun `the band renders even though no notification could have been sent`() {
        // The denied-permission case, as the screen experiences it: this state is exactly what a
        // device with notifications switched off produces, and it must be complete on its own.
        setContent(
            BudgetsUiState(
                isLoading = false,
                budgets =
                    listOf(budgetRow(name = "Dining", budgeted = Money(1_000_000L), spent = Money(1_010_000L))),
                alerts = listOf(alertRow(name = "Dining", band = BudgetAlertBand.EXCEEDED)),
                requestNotificationPermission = false,
            ),
        )

        compose.onNodeWithText(plural(R.plurals.budgets_alert_banner_exceeded, 1)).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.budgets_band_exceeded)).performScrollTo().assertIsDisplayed()
    }

    // --- issue 4.6: the monthly review -------------------------------------------------------------

    @Test
    fun `a material finding shows the variance, both rules and the proposed amount`() {
        val proposal = suggestionRow(name = "Groceries", amount = Money(850_000L), median = Money(850_000L)).suggestion
        setContent(
            BudgetsUiState(
                isLoading = false,
                review =
                    reviewRow(
                        name = "Groceries",
                        direction = VarianceDirection.OVER,
                        budgeted = Money(1_000_000L),
                        actual = Money(1_300_000L),
                        proposal = proposal,
                    ),
            ),
        )

        compose.onNodeWithText(text(R.string.budgets_review_title)).assertIsDisplayed()
        val summary = text(R.string.budgets_review_summary, "₹10,000.00", "₹13,000.00")
        compose.onNodeWithText(summary).assertIsDisplayed()
        // P-02 in one screen: the finding never appears without both rules that stand behind it —
        // RULE-BUD-REVIEW decided this month was worth reviewing, RULE-BUD-SUGGEST priced the row.
        val variance = text(R.string.budgets_review_over, "₹13,000.00", "₹10,000.00", 30)
        compose.onNodeWithText(variance).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(text(R.string.budgets_reason_rule, "RULE-BUD-REVIEW", "1.0")).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.budgets_reason_rule, "RULE-BUD-SUGGEST", "1.0")).assertIsDisplayed()
        // The assertion this test was **named** for and did not make until 2026-08-16. Without it,
        // the card rendered the variance, the rules and an Accept button that wrote `proposal.amount`
        // — a number the user was never shown (P-02, P-07) — and every test here still passed.
        // ₹8,500.00 is the proposal; ₹13,000.00 above is what the month actually cost, so this can
        // only pass if the proposal itself is on screen.
        val proposed = text(R.string.budgets_review_proposal, "₹8,500.00")
        compose.onNodeWithText(proposed).performScrollTo().assertIsDisplayed()
    }

    @Test
    fun `a finding with too little history to price says so rather than showing nothing`() {
        setContent(
            BudgetsUiState(isLoading = false, review = reviewRow(name = "Groceries", proposal = null)),
        )

        compose.onNodeWithText(text(R.string.budgets_review_no_history)).performScrollTo().assertIsDisplayed()
    }

    @Test
    fun `an on-plan month renders no review card at all`() {
        setContent(BudgetsUiState(isLoading = false, review = null))

        compose.onNodeWithText(text(R.string.budgets_review_title)).assertDoesNotExist()
    }

    @Test
    fun `accepting a review proposal is a tap, not automatic`() {
        val proposal = suggestionRow(name = "Groceries", amount = Money(850_000L)).suggestion
        val events = mutableListOf<BudgetsEvent>()
        setContent(
            BudgetsUiState(isLoading = false, review = reviewRow(name = "Groceries", proposal = proposal)),
            onEvent = { events += it },
        )

        assertTrue(events.isEmpty())
        compose.onNodeWithText(text(R.string.budgets_suggestion_accept)).performScrollTo().performClick()

        assertEquals(listOf(BudgetsEvent.AcceptReviewProposal("category:Groceries")), events)
    }

    @Test
    fun `dismissing the review sends exactly that event`() {
        val events = mutableListOf<BudgetsEvent>()
        setContent(
            BudgetsUiState(isLoading = false, review = reviewRow(name = "Groceries")),
            onEvent = { events += it },
        )

        compose.onNodeWithText(text(R.string.budgets_review_dismiss)).performScrollTo().performClick()

        assertEquals(listOf(BudgetsEvent.DismissReview), events)
    }

    /** Result: a plural string from this module's resources. Input: [id]; [count]. Output: the text. */
    private fun plural(
        id: Int,
        count: Int,
    ): String = compose.activity.resources.getQuantityString(id, count, count)

    /**
     * Renders the screen's body in the app's theme.
     * Result: the composition. Input: [state]; [onEvent] — defaults to ignoring events, for the tests
     *         that only assert what is drawn. Output: none.
     */
    private fun setContent(
        state: BudgetsUiState,
        onEvent: (BudgetsEvent) -> Unit = {},
    ) {
        compose.setContent {
            CfoTheme { BudgetsContent(uiState = state, onEvent = onEvent) }
        }
    }

    /** Result: passes when nothing on screen has this text. Output: none. */
    private fun androidx.compose.ui.test.SemanticsNodeInteraction.assertDoesNotExist() {
        assertDoesNotExist()
    }
}
