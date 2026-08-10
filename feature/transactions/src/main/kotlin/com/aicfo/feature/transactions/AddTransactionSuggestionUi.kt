package com.aicfo.feature.transactions

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import com.aicfo.core.common.AppError
import com.aicfo.core.common.Ok
import com.aicfo.core.common.Result
import com.aicfo.core.designsystem.theme.CfoDimens
import com.aicfo.domain.engines.classification.CategorySuggestion

// =============================================================================
// Everything the add screen does with a Stage-1 category suggestion (issue 4.2; SRS §8.1).
//
// Why:  a file of its own rather than more of AddTransactionScreen.kt and
//       AddTransactionViewModel.kt, both of which detekt already holds at its function ceiling —
//       and the split is the honest one anyway: the suggestion is a self-contained feature over
//       the category picker, with one state transition and one row of UI. It follows
//       TransactionsScheduledUi.kt's convention of a file per screen concern rather than per layer.
// Result: the screen and the ViewModel keep the shapes they had; this file is what a reader opens
//       to find out what a suggestion does.
// Changelog: 2026-08-10 — Created for issue 4.2.
// =============================================================================

/**
 * How long the merchant field must be still before the classifier is asked (issue 4.2).
 *
 * Why: 300 ms is the interval a person stops typing between words but not between letters, so a
 *      merchant typed straight through costs one query rather than one per character. It is a
 *      responsiveness constant, **not a financial threshold**, so CLAUDE.md §6 does not put it in
 *      `ai/` — nothing about the user's money changes if it moves.
 */
internal const val SUGGESTION_DEBOUNCE_MILLIS = 300L

/**
 * Applies whatever Stage 1 concluded about the current merchant (issue 4.2; SRS §8.1, P-02, P-07).
 *
 * Why:    the id the engine returns has to be resolved against [AddTransactionUiState.categories]
 *         here rather than in the composable, so the chip that is *selected* and the name in the
 *         suggestion row come from one lookup and cannot disagree. A suggestion naming a category
 *         the screen has not loaded is dropped rather than rendered as a blank chip — it can happen
 *         legitimately, in the moment between a category being deleted elsewhere and this screen's
 *         list re-emitting.
 *
 *         **An `Err` clears the suggestion instead of surfacing it.** See
 *         `AddTransactionViewModel.observeSuggestions` for why: the whole feature is optional
 *         convenience over a chip row that already works without it.
 * Result: the state with the suggestion set and the chip pre-selected, or with both cleared. The
 *         pre-selection is skipped once [AddTransactionUiState.isCategoryUserChosen] is true, which
 *         is what keeps a proposal from overruling a person.
 * Input:  the receiver; [outcome] — what the repository returned.
 * Output: [AddTransactionUiState].
 * Changelog: 2026-08-10 — Created for issue 4.2.
 */
internal fun AddTransactionUiState.withSuggestion(
    outcome: Result<CategorySuggestion?, AppError>,
): AddTransactionUiState {
    val suggested = (outcome as? Ok)?.value
    val category = categories.firstOrNull { it.id == suggested?.categoryId }
    return if (suggested == null || category == null) {
        copy(
            suggestion = null,
            // Cleared too, and only when the user has not chosen: while `isCategoryUserChosen` is
            // false the *only* thing that can have selected a chip is a previous suggestion, so
            // leaving it would strand the answer to a merchant that is no longer in the field.
            selectedCategoryId = if (isCategoryUserChosen) selectedCategoryId else null,
        )
    } else {
        copy(
            suggestion =
                CategorySuggestionUi(
                    categoryId = category.id,
                    categoryName = category.name,
                    // The engine always cites exactly one rule; `orEmpty` is for the shape of the
                    // list rather than for a case that happens, and the row hides a blank id.
                    ruleId = suggested.provenance.evidence.firstOrNull()?.ruleId.orEmpty(),
                ),
            selectedCategoryId = if (isCategoryUserChosen) selectedCategoryId else category.id,
        )
    }
}

/**
 * Says which rule pre-selected the chip, and offers to take it back (issue 4.2; P-02, P-07).
 *
 * Why:    the app has just filed the user's money somewhere they did not ask it to. **P-02 is not
 *         decoration in that situation** — the rule id is shown verbatim because it is a citation
 *         into `ai/knowledge/classification-kb.json` that a user (or a reviewer) can actually look
 *         up, and "we thought it looked like food" is not.
 *
 *         The dismiss action is the P-07 half: a suggestion the user cannot refuse is a decision.
 *         Tapping a different chip refuses it too, and either way it stays refused for the rest of
 *         the screen's life — see [AddTransactionUiState.isCategoryUserChosen].
 *
 *         `mergeDescendants` so a screen reader reads the sentence and its action as one thing
 *         rather than announcing a stray rule id after the chip row.
 * Result: the composition, or nothing when Stage 1 proposed nothing — which is the ordinary case
 *         for an unfamiliar merchant and must be silent, not an empty row.
 * Input:  [suggestion]; [onEvent]. Output: none.
 * Changelog: 2026-08-10 — Created for issue 4.2.
 */
@Composable
internal fun SuggestionNote(
    suggestion: CategorySuggestionUi?,
    onEvent: (AddTransactionEvent) -> Unit,
) {
    if (suggestion == null) return

    Row(
        modifier = Modifier.semantics(mergeDescendants = true) {},
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(CfoDimens.spaceXs),
    ) {
        Text(
            text = stringResource(R.string.add_txn_category_suggested, suggestion.categoryName, suggestion.ruleId),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        TextButton(onClick = { onEvent(AddTransactionEvent.SuggestionDismissed) }) {
            Text(stringResource(R.string.add_txn_category_suggestion_dismiss))
        }
    }
}
