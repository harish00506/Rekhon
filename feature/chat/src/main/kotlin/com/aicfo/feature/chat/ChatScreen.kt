package com.aicfo.feature.chat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aicfo.core.designsystem.component.CfoButton
import com.aicfo.core.designsystem.component.CfoCard
import com.aicfo.core.designsystem.component.CfoSecondaryButton
import com.aicfo.core.designsystem.theme.CfoDimens
import com.aicfo.data.repository.ChatTurn
import com.aicfo.domain.engines.chat.ChatIntent
import com.aicfo.domain.engines.chat.RefusalReason

/**
 * §19's assistant, on screen (issue 10.5; P-02, P-03, P-07).
 *
 * Why:  the screen's job is to make clear where the words came from. Every answer carries the model
 *       that wrote it and the rules behind its figures, a refusal says which kind of refusal it is
 *       rather than hiding behind "sorry", and the screen ends by saying the assistant never moves
 *       money — because an app that answers questions about money in sentences is exactly the one a
 *       user might expect to act on them (P-07).
 * What: the conversation, the chips, the input, and the clear.
 * Result: an assistant a user can check.
 * Changelog: 2026-09-26 — Created for issue 10.5.
 *
 * Input:  [onDone]; [modifier]; [viewModel] — injected. Output: the screen.
 */
@Composable
fun ChatScreen(
    onDone: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: ChatViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    ChatContent(uiState, viewModel::onEvent, onDone, modifier)
}

/**
 * The screen as a function of its state (ARC-004).
 * Result: the rendered screen. Input: [uiState]; [onEvent]; [onDone]; [modifier]. Output: none.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ChatContent(
    uiState: ChatUiState,
    onEvent: (ChatEvent) -> Unit,
    onDone: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(CfoDimens.spaceMd),
        verticalArrangement = Arrangement.spacedBy(CfoDimens.spaceMd),
    ) {
        Text(stringResource(R.string.chat_title), style = MaterialTheme.typography.headlineSmall)
        Text(stringResource(R.string.chat_intro), style = MaterialTheme.typography.bodyMedium)
        if (uiState.turns.isEmpty()) {
            Text(stringResource(R.string.chat_empty), style = MaterialTheme.typography.bodyMedium)
        }
        uiState.turns.forEach { turn -> TurnCard(turn) }
        // A flow row, not a Row: three questions do not fit across a phone, and a chip off the edge
        // of the screen is untappable however well it tests (issue 10.2's lesson).
        FlowRow(horizontalArrangement = Arrangement.spacedBy(CfoDimens.spaceSm)) {
            uiState.chips.forEach { intent ->
                SuggestionChip(
                    onClick = { onEvent(ChatEvent.AskChip(intent)) },
                    label = { Text(stringResource(chipLabel(intent))) },
                )
            }
        }
        AskBox(uiState, onEvent, onDone)
    }
}

/**
 * The question box, the clear, and the standing promise underneath it.
 * Why:    split from the conversation above it because the two change for different reasons — and
 *         because the promise that the assistant never moves money belongs on screen, not in a
 *         help page nobody opens (P-07).
 * Result: the footer. Input: [uiState]; [onEvent]; [onDone]. Output: none.
 */
@Composable
private fun AskBox(
    uiState: ChatUiState,
    onEvent: (ChatEvent) -> Unit,
    onDone: () -> Unit,
) {
    OutlinedTextField(
        value = uiState.typed,
        onValueChange = { onEvent(ChatEvent.Typed(it)) },
        label = { Text(stringResource(R.string.chat_input_label)) },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
    CfoButton(
        text = stringResource(R.string.chat_send),
        onClick = { onEvent(ChatEvent.Ask) },
        enabled = uiState.canAsk,
    )
    if (uiState.turns.isNotEmpty()) {
        CfoSecondaryButton(
            text = stringResource(R.string.chat_clear),
            onClick = { onEvent(ChatEvent.Clear) },
        )
    }
    Text(stringResource(R.string.chat_nothing_moved), style = MaterialTheme.typography.labelSmall)
    CfoSecondaryButton(text = stringResource(R.string.chat_back), onClick = onDone)
}

/**
 * One exchange: what was asked, what was said, and where it came from.
 * Result: the card. Input: [turn]. Output: none.
 */
@Composable
private fun TurnCard(turn: ChatTurn) {
    CfoCard {
        Column(verticalArrangement = Arrangement.spacedBy(CfoDimens.spaceSm)) {
            Text(stringResource(R.string.chat_you), style = MaterialTheme.typography.labelMedium)
            Text(turn.question, style = MaterialTheme.typography.bodyMedium)
            val refusal = turn.reply.refusal
            if (refusal == null) {
                Text(turn.reply.text, style = MaterialTheme.typography.bodyLarge)
            } else {
                Text(stringResource(refusalLabel(refusal)), style = MaterialTheme.typography.bodyLarge)
            }
            if (turn.reply.citations.isNotEmpty()) {
                Text(
                    stringResource(
                        R.string.chat_evidence,
                        turn.reply.provenance.inputWindow.orEmpty(),
                        turn.reply.citations.joinToString(" · "),
                    ),
                    style = MaterialTheme.typography.labelSmall,
                )
            }
            if (turn.reply.verified.isNotEmpty()) {
                Text(
                    stringResource(
                        R.string.chat_evidence_checked,
                        pluralStringResource(
                            R.plurals.chat_figures,
                            turn.reply.verified.size,
                            turn.reply.verified.size,
                        ),
                    ),
                    style = MaterialTheme.typography.labelSmall,
                )
            }
        }
    }
}

/**
 * Result: the string resource for a refusal — §21.6 keeps the words here, not in an engine.
 * Why:    a `when`, so a new refusal reason fails to compile until it has words a user can read.
 * Input:  [reason]. Output: a resource id.
 */
internal fun refusalLabel(reason: RefusalReason): Int =
    when (reason) {
        RefusalReason.OUT_OF_SCOPE -> R.string.chat_refusal_out_of_scope
        RefusalReason.NOT_UNDERSTOOD -> R.string.chat_refusal_not_understood
        RefusalReason.NO_DATA -> R.string.chat_refusal_no_data
        RefusalReason.NO_MODEL -> R.string.chat_refusal_no_model
        RefusalReason.GUARDRAIL_BLOCKED -> R.string.chat_refusal_guardrail
    }

/**
 * Result: the words on a chip. Input: [intent]. Output: a resource id.
 * Why:    a `when` again — an intent added to the registry cannot ship without a question a person
 *         would actually type.
 */
internal fun chipLabel(intent: ChatIntent): Int =
    when (intent) {
        ChatIntent.SPEND -> R.string.chat_chip_spend
        ChatIntent.BALANCE -> R.string.chat_chip_balance
        ChatIntent.FORECAST -> R.string.chat_chip_forecast
        ChatIntent.BUDGET -> R.string.chat_chip_budget
        ChatIntent.GOALS -> R.string.chat_chip_goals
        ChatIntent.AFFORD -> R.string.chat_chip_afford
        ChatIntent.HEALTH -> R.string.chat_chip_health
        ChatIntent.DEBT -> R.string.chat_chip_debt
        ChatIntent.BUYLIST -> R.string.chat_chip_buylist
        ChatIntent.VEHICLE -> R.string.chat_chip_vehicle
    }
