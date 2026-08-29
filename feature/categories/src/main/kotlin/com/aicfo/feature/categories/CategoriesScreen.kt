package com.aicfo.feature.categories

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aicfo.core.designsystem.component.CfoButton
import com.aicfo.core.designsystem.component.CfoCard
import com.aicfo.core.designsystem.component.CfoListRow
import com.aicfo.core.designsystem.component.CfoSecondaryButton
import com.aicfo.core.designsystem.theme.CfoDimens
import com.aicfo.core.model.Category

/**
 * The categories editor (issue 4.1; FR-SET-001, ARC-004).
 *
 * Why:  the screen that finally makes the `category` table the user's rather than the demo's. It
 *       follows the accounts list's shape exactly: a stateful entry point that collects the
 *       ViewModel, a stateless body that renders a state, and navigation as lambdas rather than a
 *       `NavController` — so this module never learns another feature exists (ARC-001).
 * What: the taxonomy grouped by parent, the add/edit sheet, the delete confirmation, the empty
 *       state and the error banner.
 * Result: FR-SET-001's "categories editor" is reachable, and every transaction screen has real
 *       categories to offer.
 * Changelog: 2026-08-08 — Created for issue 4.1.
 *
 * Input:  [modifier]; [viewModel]. Output: the rendered screen.
 */
@Composable
fun CategoriesScreen(
    modifier: Modifier = Modifier,
    viewModel: CategoriesViewModel = hiltViewModel(),
) {
    // collectAsStateWithLifecycle, not collectAsState: a backgrounded screen must stop collecting.
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    CategoriesContent(uiState = uiState, onEvent = viewModel::onEvent, modifier = modifier)
}

/**
 * The editor's body, with no dependencies of its own.
 * Why:    stateless, so a preview or a test can render any state — loading, empty, populated,
 *         error, sheet open, dialog open — without constructing a ViewModel.
 * Result: the rendered content.
 * Input:  [uiState]; [onEvent] — events up (ARC-004); [modifier]. Output: the composition.
 * Changelog: 2026-08-08 — Created for issue 4.1.
 */
@Composable
fun CategoriesContent(
    uiState: CategoriesUiState,
    onEvent: (CategoriesEvent) -> Unit,
    modifier: Modifier = Modifier,
) {
    // One scrolling Column, not a LazyColumn with a fixed header. Two reasons, and the first was
    // found by a test rather than by reading: with the list lazy and the sheet above it, **the sheet
    // itself could not scroll** — at 200% font the name field, five nature chips, the parent chips
    // and Save do not fit a phone screen, and Save was simply unreachable. The second is that
    // laziness buys nothing here: a taxonomy is tens of rows, not the thousands `:feature:transactions`
    // pages through, so composing them all costs less than the machinery to avoid it.
    Column(
        modifier =
            modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(CfoDimens.spaceMd),
        verticalArrangement = Arrangement.spacedBy(CfoDimens.spaceMd),
    ) {
        Text(
            text = stringResource(R.string.categories_title),
            style = MaterialTheme.typography.headlineSmall,
        )

        uiState.errorCode?.let { code ->
            ErrorBanner(code = code, onDismiss = { onEvent(CategoriesEvent.DismissError) })
        }

        CfoButton(
            text = stringResource(R.string.categories_add),
            onClick = { onEvent(CategoriesEvent.AddClicked) },
        )

        // Above the list rather than over it, for the reason `ReconcilePanel` states: the rows the
        // parent picker refers to stay visible and stay live underneath.
        uiState.editing?.let { editing ->
            CategoryEditorSheet(state = editing, uiState = uiState, onEvent = onEvent)
        }

        uiState.confirmingDelete?.let { confirming ->
            DeleteConfirmationCard(confirmation = confirming, onEvent = onEvent)
        }

        if (uiState.isEmpty) {
            EmptyState()
        } else {
            uiState.grouped.forEach { group ->
                CategoryGroupCard(group = group, onEvent = onEvent)
            }
        }
    }
}

/**
 * One top-level category and the categories nested under it.
 * Why:    a child is drawn inside its parent's card rather than as a sibling row, because the
 *         parent/child relationship is the only thing the indentation could mean and a flat list
 *         would make "Cabs" and "Transport" look unrelated.
 * Result: a card per group. Input: [group]; [onEvent]. Output: the composition.
 * Changelog: 2026-08-08 — Created for issue 4.1.
 */
@Composable
private fun CategoryGroupCard(
    group: CategoryGroup,
    onEvent: (CategoriesEvent) -> Unit,
) {
    CfoCard {
        Column(verticalArrangement = Arrangement.spacedBy(CfoDimens.spaceSm)) {
            CategoryRow(category = group.parent, onEvent = onEvent)
            group.children.forEach { child ->
                CategoryRow(
                    category = child,
                    onEvent = onEvent,
                    modifier = Modifier.padding(start = CfoDimens.spaceMd),
                )
            }
        }
    }
}

/**
 * One category, with its actions.
 * Why:    the nature is on the supporting line rather than shown as a colour — §8.3's five values
 *         drive the 50/30/20 bands, and a distinction carried only by hue is unreadable in
 *         greyscale and to a colour-blind user.
 * Result: a row with Edit and Delete. Input: [category]; [onEvent]; [modifier]. Output: the
 *         composition.
 * Changelog: 2026-08-08 — Created for issue 4.1.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CategoryRow(
    category: Category,
    onEvent: (CategoriesEvent) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(CfoDimens.spaceXs)) {
        CfoListRow(title = category.name, supporting = category.rowSupporting())
        // FlowRow, not Row: at 200% font two buttons and an indent do not fit one line, and a plain
        // Row would push Delete off the right edge with nothing to scroll — the lesson
        // `AccountsScreen` learned on a device rather than in a test.
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(CfoDimens.spaceSm),
            verticalArrangement = Arrangement.spacedBy(CfoDimens.spaceXs),
        ) {
            CfoSecondaryButton(
                text = stringResource(R.string.categories_edit),
                onClick = { onEvent(CategoriesEvent.EditClicked(category.id)) },
            )
            CfoSecondaryButton(
                // Offered on a seeded category too: `is_system` records where the row came from and
                // grants it no protection — it is the user's taxonomy, not the app's (P-07).
                text = stringResource(R.string.categories_delete),
                onClick = { onEvent(CategoriesEvent.DeleteClicked(category.id)) },
            )
        }
    }
}

/**
 * Result: the row's second line — the nature, and whether the row came from the defaults.
 *         Output: a [String].
 */
@Composable
private fun Category.rowSupporting(): String {
    val nature = stringResource(CategoryLabels.natureLabel(nature))
    return if (isSystem) "$nature · ${stringResource(R.string.categories_default_label)}" else nature
}

/**
 * What a user with no categories sees.
 * Why:    an empty list and a failed read must look different — one invites an action, the other
 *         reports a problem. See [CategoriesUiState.isEmpty] for why that distinction keeps growing.
 * Result: the composition. Input: none.
 * Changelog: 2026-08-08 — Created for issue 4.1.
 */
@Composable
private fun EmptyState() {
    CfoCard {
        Column(verticalArrangement = Arrangement.spacedBy(CfoDimens.spaceSm)) {
            Text(
                text = stringResource(R.string.categories_empty_title),
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                text = stringResource(R.string.categories_empty_body),
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}

/**
 * The error banner.
 * Why:    a polite live region, so a screen reader announces a rejected save without the user having
 *         to go looking for why nothing happened.
 * Result: the composition. Input: [code] — a code from `CategoriesViewModel`; [onDismiss].
 * Changelog: 2026-08-08 — Created for issue 4.1.
 */
@Composable
private fun ErrorBanner(
    code: String,
    onDismiss: () -> Unit,
) {
    CfoCard {
        Column(
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
            verticalArrangement = Arrangement.spacedBy(CfoDimens.spaceSm),
        ) {
            Text(
                text = stringResource(CategoryLabels.errorMessage(code)),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
            )
            CfoSecondaryButton(text = stringResource(R.string.categories_error_dismiss), onClick = onDismiss)
        }
    }
}
