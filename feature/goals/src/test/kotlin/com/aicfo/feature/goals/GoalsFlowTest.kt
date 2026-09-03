package com.aicfo.feature.goals

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import com.aicfo.core.common.Ok
import com.aicfo.core.designsystem.theme.CfoTheme
import com.aicfo.core.model.Money
import com.aicfo.domain.engines.goals.GoalEngineFactory
import com.aicfo.domain.engines.goals.GoalPlanInput
import com.aicfo.domain.engines.goals.GoalProjection
import com.aicfo.domain.engines.goals.GoalSpec
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.LocalDate

/**
 * Compose tests for the goals screen (issue 7.1; §21.5's "critical flows", §15).
 *
 * Why:  [GoalsViewModelTest] proves what the state does; these prove the screen renders it and
 *       routes the taps back. Four things only a rendered test catches:
 *
 *       - **the required monthly reaching the screen as a sentence a person can read**, which a
 *         state test cannot distinguish from a blank label;
 *       - **the working shown beside it** — the saved-of-target figures and the rule citation. P-02
 *         forbids a black-box verdict, and a card showing only "₹20,000 a month" would be one;
 *       - **the shortfall appearing only when there is one**, so an on-track goal is not nagged at;
 *       - **the editor existing at all.** Issue 6.7 found a whole market-data stack whose every
 *         layer shipped and whose editor field did not, so nothing could ever be priced. A rendered
 *         test is what would have caught it.
 * What: the list, the working, the empty state, the editor and the disclaimer.
 * Result: the screen is exercised on every `test` run, not only when a device is attached.
 * Changelog: 2026-08-30 — Created for issue 7.1.
 *
 * On the JVM via Robolectric, following `:feature:budgets`' `BudgetsFlowTest`.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w400dp-h1600dp")
class GoalsFlowTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun `a goal shows the required monthly as a readable figure`() {
        setContent(state(goals = listOf(behindGoal())))

        compose.onNodeWithText(string(R.string.goals_required_monthly, "₹20,000.00"))
            .performScrollTo().assertIsDisplayed()
    }

    @Test
    fun `the card shows its inputs and cites its rule, not just the verdict`() {
        // P-02: the figure above is checkable rather than asserted.
        setContent(state(goals = listOf(behindGoal())))

        compose.onNodeWithText(string(R.string.goals_saved_of_target, "₹1,00,000.00", "₹5,00,000.00"))
            .performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(string(R.string.goals_target_date, "2028-04-30")).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(string(R.string.goals_rule)).performScrollTo().assertIsDisplayed()
    }

    @Test
    fun `a goal that is behind says by how much`() {
        setContent(state(goals = listOf(behindGoal())))

        compose.onNodeWithText(string(R.string.goals_shortfall, "₹5,000.00")).performScrollTo().assertIsDisplayed()
    }

    @Test
    fun `a goal on track is not nagged at with a zero shortfall`() {
        // `setContent` may only be called once per test, so the pair of this and the case above are
        // two tests rather than one — the shortfall line is the thing being contrasted.
        setContent(state(goals = listOf(onTrackGoal())))

        compose.onNodeWithText(string(R.string.goals_status_on_track)).performScrollTo().assertIsDisplayed()
        compose.onAllNodesWithText(string(R.string.goals_shortfall, "₹0.00")).assertCountEquals(0)
    }

    @Test
    fun `a goal with no monthly plan says so rather than showing a blank line`() {
        // The absence is information; a made-up date would be worse than either (P-03).
        setContent(state(goals = listOf(noPlanGoal())))

        compose.onNodeWithText(string(R.string.goals_eta_never)).performScrollTo().assertIsDisplayed()
    }

    @Test
    fun `a fully funded goal is not told it gets there at zero a month`() {
        // Found by running the app: the engine dates an already-funded goal today, which is true and
        // reads as "At ₹0.00 a month you get there 2026-08-30". Every assertion about the figure
        // passed. The engine is right; the sentence was wrong.
        setContent(state(goals = listOf(fundedGoal())))

        compose.onNodeWithText(string(R.string.goals_eta_reached)).performScrollTo().assertIsDisplayed()
        compose.onAllNodesWithText(string(R.string.goals_eta, "₹0.00", "2026-08-30")).assertCountEquals(0)
    }

    @Test
    fun `the button on a goal card edits it, and does not say Save`() {
        // Also found by running the app: the card reused the editor's Save string, so a list of
        // goals showed a column of Save buttons that saved nothing.
        val events = mutableListOf<GoalsEvent>()
        setContent(state(goals = listOf(behindGoal())), onEvent = events::add)

        compose.onNodeWithText(string(R.string.goals_edit)).performScrollTo().performClick()

        assertEquals(GoalsEvent.EditGoal("g1"), events.single())
    }

    @Test
    fun `an empty list explains itself`() {
        setContent(state(goals = emptyList(), isLoading = false))

        compose.onNodeWithText(string(R.string.goals_empty)).performScrollTo().assertIsDisplayed()
    }

    @Test
    fun `the editor exists, and typing in it reaches the event stream`() {
        // The test issue 6.7 wished 6.5 had written.
        val events = mutableListOf<GoalsEvent>()
        setContent(state(editor = GoalEditorState()), onEvent = events::add)

        compose.onNodeWithText(string(R.string.goals_editor_name)).performScrollTo().performTextInput("Kerala trip")

        assertEquals(GoalsEvent.NameChanged("Kerala trip"), events.single())
    }

    @Test
    fun `the editor offers every field a goal needs`() {
        setContent(state(editor = GoalEditorState()))

        listOf(
            R.string.goals_editor_name,
            R.string.goals_editor_target,
            R.string.goals_editor_target_date,
            R.string.goals_editor_saved,
            R.string.goals_editor_planned,
        ).forEach { field ->
            compose.onNodeWithText(string(field)).performScrollTo().assertIsDisplayed()
        }
    }

    @Test
    fun `adding a goal is a tap the user makes, not something that happened already`() {
        // P-07: the app recommends and the user decides.
        val events = mutableListOf<GoalsEvent>()
        setContent(state(goals = emptyList(), isLoading = false), onEvent = events::add)

        compose.onNodeWithText(string(R.string.goals_add)).performScrollTo().performClick()

        assertEquals(GoalsEvent.AddGoal, events.single())
    }

    @Test
    fun `the screen carries the disclaimer section 11-1 requires`() {
        setContent(state(goals = listOf(behindGoal())))

        compose.onNodeWithText(string(R.string.goals_disclaimer)).performScrollTo().assertIsDisplayed()
    }

    // --- helpers ---------------------------------------------------------------------------------

    /** Renders the screen's body with the given state. */
    private fun setContent(
        uiState: GoalsUiState,
        onEvent: (GoalsEvent) -> Unit = {},
    ) {
        compose.setContent {
            CfoTheme { GoalsContent(uiState = uiState, onEvent = onEvent, onDone = {}) }
        }
    }

    private fun state(
        goals: List<GoalProjection> = emptyList(),
        editor: GoalEditorState? = null,
        isLoading: Boolean = false,
    ) = GoalsUiState(goals = goals, editor = editor, isLoading = isLoading)

    /**
     * Result: a string from this module's resources, resolved the way the screen resolves it.
     *
     * The empty-args branch matters: `getString(id, *args)` runs `String.format` even with nothing
     * to substitute, which turns a literal `%` into an invalid conversion and throws — while
     * `stringResource` in the composable never formats an argument-less string. The same care
     * `BudgetsFlowTest` takes.
     */
    private fun string(
        id: Int,
        vararg args: Any,
    ): String = compose.activity.let { if (args.isEmpty()) it.getString(id) else it.getString(id, *args) }

    /** A real projection from the real engine — never a hand-written one (P-03). */
    private fun project(spec: GoalSpec): GoalProjection =
        ((GoalEngineFactory.create().plan(GoalPlanInput(goals = listOf(spec), today = TODAY))) as Ok)
            .value.goals.single()

    /** ₹4,00,000 over 20 months is ₹20,000 a month, against a ₹15,000 plan. */
    private fun behindGoal() =
        project(
            GoalSpec(
                id = "g1",
                name = "Kerala trip",
                target = Money(5_00_000_00),
                targetDate = LocalDate.parse("2028-04-30"),
                saved = Money(1_00_000_00),
                plannedMonthly = Money(15_000_00),
            ),
        )

    private fun onTrackGoal() = project(behindSpec().copy(plannedMonthly = Money(20_000_00)))

    private fun noPlanGoal() = project(behindSpec().copy(plannedMonthly = Money.ZERO))

    /** Saved more than the target: remaining is zero, so the ETA line has a third case. */
    private fun fundedGoal() = project(behindSpec().copy(saved = Money(6_00_000_00), plannedMonthly = Money.ZERO))

    private fun behindSpec() =
        GoalSpec(
            id = "g1",
            name = "Kerala trip",
            target = Money(5_00_000_00),
            targetDate = LocalDate.parse("2028-04-30"),
            saved = Money(1_00_000_00),
            plannedMonthly = Money(15_000_00),
        )

    private companion object {
        val TODAY: LocalDate = LocalDate.parse("2026-08-30")
    }
}
