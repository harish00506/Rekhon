package com.aicfo.feature.goals

import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aicfo.core.designsystem.component.CfoCard
import com.aicfo.core.designsystem.component.CfoSecondaryButton
import com.aicfo.core.designsystem.theme.CfoDimens
import com.aicfo.core.designsystem.theme.CfoTheme
import com.aicfo.core.model.Money
import com.aicfo.core.model.MoneyFormatter
import com.aicfo.domain.engines.goals.GoalAllocation
import com.aicfo.domain.engines.goals.GoalProjection
import kotlin.math.roundToInt

/**
 * The goals screen (issue 7.1; §15, FR-GOAL, ARC-004).
 *
 * Why:  the screen that makes `GoalEngine` reachable. An engine nobody can put a goal into is an
 *       engine nobody can use — the exact shape of the bug issue 6.7 found in 6.5, where a whole
 *       market-data stack shipped with no field to fill in.
 * What: the list, an editor, and the disclaimer §11.1 requires.
 * Result: the composition.
 * Changelog: 2026-08-30 — Created for issue 7.1.
 *            2026-09-03 — Issue 7.3: the plan card above the list, the allocation and levers on
 *            each goal, and the drag that reorders the waterfall.
 */
@Composable
fun GoalsScreen(
    onDone: () -> Unit,
    viewModel: GoalsViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    GoalsContent(uiState = uiState, onEvent = viewModel::onEvent, onDone = onDone)
}

/**
 * The screen's body, with no ViewModel in sight.
 * Why:    separated so a test can drive every state directly — the reason every screen here splits
 *         this way (ARC-004).
 * Result: the composition. Input: [uiState]; [onEvent]; [onDone]. Output: none.
 * Changelog: 2026-08-30 — Created for issue 7.1.
 */
@Composable
internal fun GoalsContent(
    uiState: GoalsUiState,
    onEvent: (GoalsEvent) -> Unit,
    onDone: () -> Unit,
) {
    // A plain scrolling Column rather than a LazyColumn, for the reason HoldingsScreen gives: the
    // disclaimer sits below the list and must be reachable, so the whole screen scrolls as one, and
    // a LazyColumn inside a scrolling parent is measured with an infinite height constraint. A
    // person's goals number in the handful; there is nothing to virtualise.
    Column(
        modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(CfoDimens.spaceMd),
        verticalArrangement = Arrangement.spacedBy(CfoDimens.spaceMd),
    ) {
        Text(text = stringResource(R.string.goals_title), style = MaterialTheme.typography.headlineSmall)

        uiState.errorCode?.let {
            Text(text = stringResource(R.string.goals_error), color = CfoTheme.extendedColors.negative)
            CfoSecondaryButton(
                text = stringResource(R.string.goals_dismiss_error),
                onClick = { onEvent(GoalsEvent.DismissError) },
            )
        }

        val editor = uiState.editor
        if (editor != null) {
            GoalEditor(state = editor, onEvent = onEvent)
        } else {
            uiState.waterfall?.let { GoalWaterfallCard(waterfall = it) }
            GoalList(uiState = uiState, onEvent = onEvent)
            CfoSecondaryButton(text = stringResource(R.string.goals_add), onClick = { onEvent(GoalsEvent.AddGoal) })
            CfoSecondaryButton(text = stringResource(R.string.goals_editor_cancel), onClick = onDone)
        }

        Text(
            text = stringResource(R.string.goals_disclaimer),
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

/**
 * The list, or the reason it is empty.
 * Why:    split out to keep [GoalsContent] within the 40-line limit (§21.6).
 * Result: the composition. Input: [uiState]; [onEvent]. Output: none.
 */
@Composable
private fun GoalList(
    uiState: GoalsUiState,
    onEvent: (GoalsEvent) -> Unit,
) {
    if (uiState.isEmpty) {
        Text(text = stringResource(R.string.goals_empty), style = MaterialTheme.typography.bodyMedium)
        return
    }
    if (uiState.goals.size > 1) {
        Text(text = stringResource(R.string.goals_reorder_hint), style = MaterialTheme.typography.bodySmall)
    }
    uiState.goals.forEachIndexed { index, goal ->
        GoalCard(
            goal = goal,
            allocation = uiState.allocationFor(goal.goalId),
            modifier =
                Modifier.reorderable(
                    index = index,
                    goalId = goal.goalId,
                    canMoveUp = uiState.canMoveUp(goal.goalId),
                    canMoveDown = uiState.canMoveDown(goal.goalId),
                    moveUp = stringResource(R.string.goals_move_up),
                    moveDown = stringResource(R.string.goals_move_down),
                    onEvent = onEvent,
                ),
            onEvent = onEvent,
        )
    }
}

/**
 * Makes one card draggable, and reachable without a drag (issue 7.3; §15, FR-GOAL-005).
 *
 * Why:    §15 shows the waterfall as "a draggable plan", so a long-press drag is the affordance —
 *         but **a drag gesture is unusable with TalkBack and with a switch device**, and the
 *         accessibility scan in the Definition of Done would be right to fail it. So the same move
 *         is offered twice: as the gesture, and as two semantic custom actions. The actions are also
 *         what the Compose test drives, which means the test proves the accessible path works rather
 *         than proving a gesture nobody can perform.
 *
 *         Hand-rolled rather than a library: there is no reorderable dependency in the version
 *         catalog, adding one would need a `DECISIONS.md` row, and the list is a plain `Column` of a
 *         handful of cards with no virtualisation or autoscroll to fight.
 * What:   tracks the drag offset, converts it to a row delta at drop, and declares the two actions.
 * Result: the modifier. Rows are treated as a fixed [DRAG_ROW_HEIGHT] apart for that conversion —
 *         cards vary in height with their levers, so an exact hit-test would need a measurement pass
 *         to buy precision no thumb can express.
 * Input:  [index]; [goalId]; [canMoveUp]; [canMoveDown]; the two action labels; [onEvent].
 * Output: a [Modifier].
 * Changelog: 2026-09-03 — Created for issue 7.3.
 */
@Composable
@Suppress("LongParameterList") // Seven: the row's identity, its two bounds, two labels, and the sink.
private fun Modifier.reorderable(
    index: Int,
    goalId: String,
    canMoveUp: Boolean,
    canMoveDown: Boolean,
    moveUp: String,
    moveDown: String,
    onEvent: (GoalsEvent) -> Unit,
): Modifier {
    var dragOffset by remember(goalId) { mutableFloatStateOf(0f) }
    val settledIndex by remember(goalId, index) { mutableIntStateOf(index) }
    val rowHeightPx = with(LocalDensity.current) { DRAG_ROW_HEIGHT.toPx() }
    val actions = moveActions(goalId, canMoveUp, canMoveDown, moveUp, moveDown, onEvent)
    return this
        .graphicsLayer { translationY = dragOffset }
        // `mergeDescendants` makes the whole card one accessibility node rather than eleven
        // unrelated Text nodes with no actions between them. That is the right reading experience
        // for a list item that can be acted on — and it is what puts "Move up" within reach of the
        // goal it moves, instead of stranding it on a container TalkBack never lands on.
        .semantics(mergeDescendants = true) { customActions = actions }
        .pointerInput(goalId, index) {
            detectDragGesturesAfterLongPress(
                onDrag = { change, amount ->
                    change.consume()
                    dragOffset += amount.y
                },
                // The move is emitted on release, not per pixel: reordering mid-drag would move the
                // list under the finger and recompute the whole plan on every frame.
                onDragEnd = {
                    val target = settledIndex + (dragOffset / rowHeightPx).roundToInt()
                    dragOffset = 0f
                    onEvent(GoalsEvent.MoveGoal(settledIndex, target))
                },
                onDragCancel = { dragOffset = 0f },
            )
        }
}

/**
 * One goal, and the working behind its figure.
 *
 * Why:    P-02 — the required monthly is shown with the inputs it came from and the rule that
 *         shaped the advice beside it, the way the Safe-to-Spend card names `RULE-STS v1.0`. A bare
 *         "₹20,000 a month" with no explanation is the black-box verdict P-02 exists to forbid.
 * Result: the composition. Input: [goal]; [onEvent]. Output: none.
 */
@Composable
private fun GoalCard(
    goal: GoalProjection,
    allocation: GoalAllocation?,
    modifier: Modifier = Modifier,
    onEvent: (GoalsEvent) -> Unit,
) {
    CfoCard(modifier = modifier) {
        Text(text = goal.name, style = MaterialTheme.typography.titleMedium)
        Text(text = stringResource(GoalLabels.status(goal.status)))
        Text(
            text = stringResource(R.string.goals_required_monthly, MoneyFormatter.format(goal.requiredMonthly)),
            style = MaterialTheme.typography.headlineSmall,
        )
        if (!goal.onTrack) {
            Text(
                text = stringResource(R.string.goals_shortfall, MoneyFormatter.format(goal.shortfallMonthly)),
                color = CfoTheme.extendedColors.negative,
            )
        }
        // What the shared surplus can actually give this goal, which is a different question from
        // what the goal needs — and the one the user can act on (7.3, FR-GOAL-003).
        allocation?.let { GoalAllocationLines(line = it) }
        // The inputs, so the figure above is checkable rather than asserted.
        Text(
            text =
                stringResource(
                    R.string.goals_saved_of_target,
                    MoneyFormatter.format(goal.saved),
                    MoneyFormatter.format(goal.target),
                ),
        )
        Text(text = stringResource(R.string.goals_target_date, goal.targetDateIso))
        GoalEta(goal)
        Text(text = stringResource(GoalLabels.horizon(goal.horizon)), style = MaterialTheme.typography.bodySmall)
        Text(text = stringResource(R.string.goals_rule), style = MaterialTheme.typography.bodySmall)
        CfoSecondaryButton(
            text = stringResource(R.string.goals_edit),
            onClick = { onEvent(GoalsEvent.EditGoal(goal.goalId)) },
        )
        CfoSecondaryButton(
            text = stringResource(R.string.goals_editor_delete),
            onClick = { onEvent(GoalsEvent.DeleteGoal(goal.goalId)) },
        )
    }
}

/**
 * Where the user's own plan gets them.
 *
 * Why:    the absence is rendered rather than hidden. "No monthly plan set" is information; a blank
 *         line is not, and a made-up date would be worse than either (P-03).
 *
 *         **Three cases, not two.** The engine dates an already-funded goal *today*, which is true
 *         and reads absurdly beside a zero plan — "at ₹0.00 a month you get there 2026-08-30". Found
 *         by running the app, not by a test: every assertion about the figure passed. The engine is
 *         right; the sentence was wrong, so the branch is here rather than there.
 * Result: the composition. Input: [goal]. Output: none.
 * Changelog: 2026-08-30 — Created for issue 7.1.
 */
@Composable
private fun GoalEta(goal: GoalProjection) {
    val eta = goal.etaIsoDate
    val text =
        when {
            goal.remaining == Money.ZERO -> stringResource(R.string.goals_eta_reached)
            eta == null -> stringResource(R.string.goals_eta_never)
            else -> stringResource(R.string.goals_eta, MoneyFormatter.format(goal.plannedMonthly), eta)
        }
    Text(text = text, style = MaterialTheme.typography.bodySmall)
}

/**
 * The two moves a card offers when a drag is not available (issue 7.3).
 *
 * Why:    split from [reorderable] to keep it inside the 40-line limit (§21.6). It is also the one
 *         place that decides whether an action is *offered* — a "move up" on the first goal would be
 *         an action TalkBack announces and nothing happens when it is taken, which is worse than its
 *         absence.
 * Result: the actions, in reading order; empty for a single-goal list.
 * Input:  [goalId]; [canMoveUp]; [canMoveDown]; [moveUp] and [moveDown] — the labels; [onEvent].
 * Output: the custom actions.
 * Changelog: 2026-09-03 — Created for issue 7.3.
 */
@Suppress("LongParameterList") // Six: the row's identity, its two bounds, two labels, and the sink.
private fun moveActions(
    goalId: String,
    canMoveUp: Boolean,
    canMoveDown: Boolean,
    moveUp: String,
    moveDown: String,
    onEvent: (GoalsEvent) -> Unit,
): List<CustomAccessibilityAction> =
    buildList {
        if (canMoveUp) {
            add(
                CustomAccessibilityAction(moveUp) {
                    onEvent(GoalsEvent.MoveUp(goalId))
                    true
                },
            )
        }
        if (canMoveDown) {
            add(
                CustomAccessibilityAction(moveDown) {
                    onEvent(GoalsEvent.MoveDown(goalId))
                    true
                },
            )
        }
    }

/**
 * How far a drag must travel to count as one row.
 *
 * Not a design token: it is a **gesture calibration**, not a dimension anything is drawn at. Cards
 * vary in height with how many levers they carry, so converting a drag distance into a row delta is
 * approximate by nature, and a hit-test against real bounds would need a measurement pass to buy
 * precision no thumb can express. Roughly a card's height, which makes the gesture feel one-for-one.
 */
private val DRAG_ROW_HEIGHT = 96.dp
