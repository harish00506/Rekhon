package com.aicfo.feature.transactions

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import com.aicfo.core.designsystem.theme.CfoDimens
import com.aicfo.core.model.MoneyFormatter
import com.aicfo.core.model.TransactionType
import com.aicfo.data.repository.TransactionFilter

/**
 * The filter sheet — every facet FR-TXN-007 names except the search text (issue 3.6).
 *
 * Why:    one sheet rather than a row of chips per facet, because five facets of chips would be
 *         taller than the list they filter. It renders nothing until opened, so the screen a user
 *         sees by default is unchanged from before this issue.
 *
 *         **Clear is always offered, even when nothing is set.** A user who cannot find their
 *         transactions reaches for it first, and requiring them to work out *which* facet is hiding
 *         the rows before they can undo it is the opposite of help.
 * Result: the composition, or nothing when the sheet is shut.
 * Input:  [uiState], [onEvent]. Output: none.
 * Changelog: 2026-08-04 — Created for issue 3.6 (FR-TXN-007).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun TransactionFilterSheet(
    uiState: TransactionsUiState,
    onEvent: (TransactionsEvent) -> Unit,
) {
    if (!uiState.isFilterSheetOpen) return
    ModalBottomSheet(onDismissRequest = { onEvent(FilterEvent.Dismissed) }) {
        TransactionFilterContent(uiState = uiState, onEvent = onEvent)
    }
}

/**
 * The filter sheet's body, with no sheet around it (issue 3.6).
 * Why:    stateless and separate for the reason `TransactionDetailContent` is: a Compose test can
 *         render it directly rather than fighting Robolectric over sheet animation.
 * Result: the composition. Input: [uiState], [onEvent]. Output: none.
 * Changelog: 2026-08-04 — Created for issue 3.6.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun TransactionFilterContent(
    uiState: TransactionsUiState,
    onEvent: (TransactionsEvent) -> Unit,
) {
    val filter = uiState.filter
    Column(
        modifier = Modifier.fillMaxWidth().padding(CfoDimens.spaceMd),
        verticalArrangement = Arrangement.spacedBy(CfoDimens.spaceSm),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(R.string.transactions_filter),
                style = MaterialTheme.typography.titleMedium,
            )
            TextButton(onClick = { onEvent(FilterEvent.Cleared) }) {
                Text(stringResource(R.string.transactions_filter_clear))
            }
        }

        FilterChipFacets(uiState = uiState, onEvent = onEvent)

        AmountRangeFields(filter = filter, onEvent = onEvent)
    }
}

/**
 * The three chip facets — account, type and tag (issue 3.6; FR-TXN-007).
 * Why:    extracted from [TransactionFilterContent] at detekt's 40-line ceiling (§21.6). The seam is
 *         real: these three are chips over a closed set, while the amount range is a pair of text
 *         fields with parsing of its own.
 *
 *         **Every chip toggles.** Tapping the selected one clears that facet rather than doing
 *         nothing — the behaviour the source chips already have, and it saves a trip to Clear.
 * Result: the composition. Input: [uiState], [onEvent]. Output: none.
 * Changelog: 2026-08-04 — Created for issue 3.6.
 */
@Composable
private fun FilterChipFacets(
    uiState: TransactionsUiState,
    onEvent: (TransactionsEvent) -> Unit,
) {
    val filter = uiState.filter
    FilterFacet(labelId = R.string.transactions_filter_account) {
        uiState.accountNames.forEach { (id, name) ->
            FilterChip(
                selected = filter.accountId == id,
                onClick = {
                    onEvent(FilterEvent.Changed(filter.copy(accountId = id.takeIf { it != filter.accountId })))
                },
                label = { Text(name) },
            )
        }
    }

    FilterFacet(labelId = R.string.transactions_filter_type) {
        // §20.2's five stored types, but only the three a user thinks in. The two transfer legs are
        // a consequence of saving a transfer, not a kind of thing anyone goes looking for — and
        // filtering to one leg would show half of every transfer.
        FILTERABLE_TYPES.forEach { type ->
            FilterChip(
                selected = filter.type == type,
                onClick = {
                    onEvent(FilterEvent.Changed(filter.copy(type = type.takeIf { it != filter.type })))
                },
                label = { Text(stringResource(TransactionLabels.typeName(type))) },
            )
        }
    }

    // Hidden rather than shown empty: tags are opt-in, so most profiles have none, and an empty
    // chip row reads as a broken picker.
    if (uiState.availableTags.isNotEmpty()) {
        FilterFacet(labelId = R.string.transactions_filter_tag) {
            uiState.availableTags.forEach { tag ->
                FilterChip(
                    selected = filter.tagId == tag.id,
                    onClick = {
                        onEvent(FilterEvent.Changed(filter.copy(tagId = tag.id.takeIf { it != filter.tagId })))
                    },
                    label = { Text(tag.name) },
                )
            }
        }
    }
}

/**
 * The transaction types the filter offers (issue 3.6; FR-TXN-007, §20.2).
 *
 * Three of the five stored values. Named rather than inlined so the omission is greppable from the
 * test that pins it — a future author adding `TRANSFER_OUT` here would make every transfer render
 * as half of itself.
 */
private val FILTERABLE_TYPES =
    listOf(TransactionType.EXPENSE, TransactionType.INCOME, TransactionType.ADJUSTMENT)

/**
 * One labelled row of filter chips (issue 3.6).
 * Why:    four facets rendered identically; writing the label-plus-`FlowRow` shape once keeps them
 *         aligned and keeps [TransactionFilterContent] under detekt's 40-line ceiling (§21.6).
 *         `FlowRow` so a profile with nine accounts wraps rather than clipping at 200% font.
 * Result: the composition. Input: [labelId]; [chips] — the facet's chips. Output: none.
 * Changelog: 2026-08-04 — Created for issue 3.6.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FilterFacet(
    @StringRes labelId: Int,
    chips: @Composable () -> Unit,
) {
    val label = stringResource(labelId)
    Text(text = label, style = MaterialTheme.typography.labelLarge)
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(CfoDimens.spaceSm),
        // The chips are individually labelled; this says what the group is for, which a screen
        // reader would otherwise have to infer from a row of bare nouns.
        modifier = Modifier.fillMaxWidth().semantics { contentDescription = label },
    ) {
        // A plain `@Composable () -> Unit` rather than `FlowRowScope.() -> Unit`: the chips need no
        // scope receiver, and naming the experimental scope in this signature would tie the file's
        // API to a type that has changed shape between Compose releases.
        chips()
    }
}

/**
 * The two amount bounds (issue 3.6; FR-TXN-007's "amount range", MNY-001).
 *
 * Why:    text, not [com.aicfo.core.model.Money], for the reason every amount field in this module
 *         is: `"1."` is a legitimate thing to have on screen mid-typing and is not an amount yet.
 *         Parsed once by `MoneyFormatter.parse`, which returns `null` for anything it cannot
 *         represent exactly rather than rounding it — and a `null` bound simply switches that half
 *         of the range off, so a half-typed figure narrows nothing rather than excluding everything.
 *
 *         **The bounds are magnitudes**, which is why neither field offers a sign: "between ₹100 and
 *         ₹500" is a statement about size, and under a signed comparison it would exclude every
 *         expense in the range.
 * Result: the composition. Input: [filter], [onEvent]. Output: none.
 * Changelog: 2026-08-04 — Created for issue 3.6.
 */
@Composable
private fun AmountRangeFields(
    filter: TransactionFilter,
    onEvent: (TransactionsEvent) -> Unit,
) {
    var minText by remember { mutableStateOf(filter.minAmount?.let { MoneyFormatter.format(it) }.orEmpty()) }
    var maxText by remember { mutableStateOf(filter.maxAmount?.let { MoneyFormatter.format(it) }.orEmpty()) }
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(CfoDimens.spaceSm),
    ) {
        OutlinedTextField(
            value = minText,
            onValueChange = {
                minText = it
                onEvent(FilterEvent.Changed(filter.copy(minAmount = MoneyFormatter.parse(it))))
            },
            label = { Text(stringResource(R.string.transactions_filter_min_amount)) },
            singleLine = true,
            modifier = Modifier.weight(1f),
        )
        OutlinedTextField(
            value = maxText,
            onValueChange = {
                maxText = it
                onEvent(FilterEvent.Changed(filter.copy(maxAmount = MoneyFormatter.parse(it))))
            },
            label = { Text(stringResource(R.string.transactions_filter_max_amount)) },
            singleLine = true,
            modifier = Modifier.weight(1f),
        )
    }
}

/**
 * Adds a tag name to what the user has typed, without duplicating it (issue 3.6).
 * Why:    tapping an existing chip is a shortcut for typing its name, and tapping it twice should
 *         not produce `travel, travel` — the repository would de-duplicate it anyway, but a field
 *         showing a doubled label reads as the app having misheard.
 * Result: the text with [name] appended, or unchanged when it is already there.
 * Input:  [current] — what is in the field; [name] — the tapped tag. Output: [String].
 * Changelog: 2026-08-04 — Created for issue 3.6.
 */
internal fun appendTag(
    current: String,
    name: String,
): String {
    val existing = current.splitTags()
    if (existing.any { it.equals(name, ignoreCase = true) }) return current
    return (existing + name).joinToString(TAG_SEPARATOR_TEXT)
}

/**
 * Splits a typed tag field into labels (issue 3.6).
 * Why:    one field holding several tags needs a separator, and a comma is the one every user
 *         already reaches for. Blank entries are dropped rather than becoming a nameless tag, which
 *         is what a trailing comma would otherwise produce on every keystroke.
 * Result: the trimmed, non-blank labels; empty for an empty field — which the repository reads as
 *         "remove every tag".
 * Input:  the receiver. Output: `List<String>`.
 * Changelog: 2026-08-04 — Created for issue 3.6.
 */
internal fun String.splitTags(): List<String> = split(TAG_SEPARATOR).map { it.trim() }.filter { it.isNotBlank() }

/** The character a typed tag field is split on. */
private const val TAG_SEPARATOR = ','

/** What [appendTag] joins with — the separator plus the space a reader expects after it. */
private const val TAG_SEPARATOR_TEXT = ", "
