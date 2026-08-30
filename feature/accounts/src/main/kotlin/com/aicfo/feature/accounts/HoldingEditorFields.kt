package com.aicfo.feature.accounts

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import com.aicfo.core.designsystem.component.CfoButton
import com.aicfo.core.designsystem.component.CfoCard
import com.aicfo.core.designsystem.component.CfoSecondaryButton
import com.aicfo.core.designsystem.theme.CfoDimens
import com.aicfo.core.model.AssetClass
import com.aicfo.core.model.LotKind

/**
 * The holding editor: what the instrument is, what it is worth, and every movement in it
 * (issue 6.3; §11, ARC-004).
 *
 * Why:  a sibling file rather than another section inside [AccountEditorScreen], the precedent
 *       `AccountEditorLoanFields.kt` set in issue 6.2 — the editor is already at detekt's function
 *       ceiling, and a holding's fields are a screen's worth on their own once the lots are in.
 *
 *       **The lots are edited inline, beneath the holding**, rather than on a screen of their own.
 *       A lot only means anything in the context of the instrument it belongs to, and a person
 *       entering a SIP has twelve of them to type; sending them through a separate screen twelve
 *       times would be the kind of flow that gets abandoned halfway.
 * What: the name, the asset class, the price and its date, and a repeating lot row.
 * Result: everything [HoldingsViewModel] needs to build a draft.
 * Changelog: 2026-08-24 — Created for issue 6.3.
 *
 * Input:  [state] — the editor's contents as typed; [onEvent]. Output: the composition.
 */
@Composable
internal fun HoldingEditorFields(
    state: HoldingEditorState,
    onEvent: (HoldingsEvent) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(CfoDimens.spaceSm)) {
        OutlinedTextField(
            value = state.name,
            onValueChange = { onEvent(HoldingsEvent.NameChanged(it)) },
            label = { Text(stringResource(R.string.holdings_name)) },
            singleLine = true,
            isError = state.fieldError == HoldingsViewModel.FIELD_HOLDING,
            modifier = Modifier.fillMaxWidth(),
        )

        AssetClassPicker(selected = state.assetClass, onEvent = onEvent)
        PriceFields(state = state, onEvent = onEvent)

        state.lots.forEachIndexed { index, lot ->
            LotEditorFields(
                index = index,
                lot = lot,
                isError = state.fieldError == HoldingsViewModel.FIELD_LOT,
                onEvent = onEvent,
            )
        }
        CfoSecondaryButton(
            text = stringResource(R.string.holdings_add_lot),
            onClick = { onEvent(HoldingsEvent.AddLot) },
        )

        CfoButton(
            text = stringResource(R.string.holdings_save),
            onClick = { onEvent(HoldingsEvent.SaveEditor) },
        )
        CfoSecondaryButton(
            text = stringResource(R.string.holdings_cancel),
            onClick = { onEvent(HoldingsEvent.CancelEditor) },
        )
    }
}

/**
 * The asset-class choice.
 * Why:    split out to keep [HoldingEditorFields] under the 40-line limit (§21.6). The default the
 *         editor arrives with comes from the account's own type, and this is where the user
 *         overrules it — which is the whole reason the column exists (ADR-0027).
 * Result: the picker. Input: [selected]; [onEvent]. Output: none.
 * Changelog: 2026-08-24 — Created for issue 6.3.
 */
@Composable
private fun AssetClassPicker(
    selected: AssetClass,
    onEvent: (HoldingsEvent) -> Unit,
) {
    Text(
        text = stringResource(R.string.holdings_asset_class),
        style = MaterialTheme.typography.labelLarge,
    )
    AssetClass.entries.forEach { option ->
        AssetClassOption(
            option = option,
            selected = option == selected,
            onSelect = { onEvent(HoldingsEvent.AssetClassChanged(option)) },
        )
    }
}

/**
 * The unit price, the day it was observed, and what the app may look it up as.
 * Why:    the first two move together, and are rendered together so that stays visible. Blank is
 *         legitimate and means "not valued yet" (P-03); a price with no date has no terminal flow
 *         date, so the return would change by the day for a holding nobody touched (P-08).
 *
 *         The price key sits with them because it decides who writes them. **Without it the whole
 *         market-data path is unreachable**: `distinctPriceKeys` returns nothing, the refresh
 *         returns `Ok(0)` before a request is built, and the proxy might as well not exist. Issue
 *         6.5 shipped the column, the DAO, the repository, the worker and the client, and no way for
 *         a person to fill it in — found while verifying 6.7 against a live proxy.
 * Result: the three fields. Input: [state]; [onEvent]. Output: none.
 * Changelog: 2026-08-24 — Created for issue 6.3.
 *            2026-08-30 — Issue 6.7: added the price key, without which nothing can be priced.
 */
@Composable
private fun PriceFields(
    state: HoldingEditorState,
    onEvent: (HoldingsEvent) -> Unit,
) {
    OutlinedTextField(
        value = state.unitPrice,
        onValueChange = { onEvent(HoldingsEvent.UnitPriceChanged(it)) },
        label = { Text(stringResource(R.string.holdings_unit_price)) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        modifier = Modifier.fillMaxWidth(),
    )
    OutlinedTextField(
        value = state.pricedOn,
        onValueChange = { onEvent(HoldingsEvent.PricedOnChanged(it)) },
        label = { Text(stringResource(R.string.holdings_priced_on_field)) },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
    OutlinedTextField(
        value = state.priceKey,
        onValueChange = { onEvent(HoldingsEvent.PriceKeyChanged(it)) },
        label = { Text(stringResource(R.string.holdings_price_key)) },
        supportingText = { Text(stringResource(R.string.holdings_price_key_help)) },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
}

/**
 * One asset-class choice.
 * Why:    `selectable` with `Role.RadioButton` on the whole row rather than on the control, so the
 *         label is part of the target — the 48dp minimum applies to what a person actually taps.
 * Result: the option row. Input: [option]; [selected]; [onSelect]. Output: none.
 * Changelog: 2026-08-24 — Created for issue 6.3.
 */
@Composable
private fun AssetClassOption(
    option: AssetClass,
    selected: Boolean,
    onSelect: () -> Unit,
) {
    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .selectable(selected = selected, role = Role.RadioButton, onClick = onSelect),
    ) {
        Text(text = stringResource(AccountLabels.assetClassLabel(option)))
        RadioButton(selected = selected, onClick = null)
    }
}

/**
 * One movement in the holding: what it was, when, how many units and how much cash.
 * Why:    the kind is a choice rather than a signed amount, for the reason the stored row keeps it
 *         that way — two spellings of "sold ten units" is one too many, and a minus sign typed into
 *         an amount field is the easiest of all to get wrong.
 * Result: the lot row. Input: [index]; [lot]; [isError]; [onEvent]. Output: none.
 * Changelog: 2026-08-24 — Created for issue 6.3.
 */
@Composable
private fun LotEditorFields(
    index: Int,
    lot: LotEditorState,
    isError: Boolean,
    onEvent: (HoldingsEvent) -> Unit,
) {
    CfoCard {
        Column(verticalArrangement = Arrangement.spacedBy(CfoDimens.spaceXs)) {
            LotKindPicker(index = index, lot = lot, onEvent = onEvent)

            OutlinedTextField(
                value = lot.day,
                onValueChange = { onEvent(HoldingsEvent.LotChanged(index, lot.copy(day = it))) },
                label = { Text(stringResource(R.string.holdings_lot_date)) },
                singleLine = true,
                isError = isError,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = lot.units,
                onValueChange = { onEvent(HoldingsEvent.LotChanged(index, lot.copy(units = it))) },
                label = { Text(stringResource(R.string.holdings_lot_units)) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = lot.amount,
                onValueChange = { onEvent(HoldingsEvent.LotChanged(index, lot.copy(amount = it))) },
                label = { Text(stringResource(R.string.holdings_lot_amount)) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                isError = isError,
                modifier = Modifier.fillMaxWidth(),
            )
            CfoSecondaryButton(
                text = stringResource(R.string.holdings_delete),
                onClick = { onEvent(HoldingsEvent.RemoveLot(index)) },
            )
        }
    }
}

/**
 * What one lot did: bought, sold, or paid out.
 * Why:    split out to keep [LotEditorFields] under the 40-line limit (§21.6). A choice rather than
 *         a signed amount, for the reason the stored row keeps it that way — two spellings of "sold
 *         ten units" is one too many, and a minus sign typed into an amount field is the easiest of
 *         all to get wrong.
 * Result: the picker. Input: [index]; [lot]; [onEvent]. Output: none.
 * Changelog: 2026-08-24 — Created for issue 6.3.
 */
@Composable
private fun LotKindPicker(
    index: Int,
    lot: LotEditorState,
    onEvent: (HoldingsEvent) -> Unit,
) {
    Text(
        text = stringResource(R.string.holdings_lot_kind),
        style = MaterialTheme.typography.labelLarge,
    )
    LotKind.entries.forEach { option ->
        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .selectable(
                        selected = option == lot.kind,
                        role = Role.RadioButton,
                        onClick = { onEvent(HoldingsEvent.LotChanged(index, lot.copy(kind = option))) },
                    ),
        ) {
            Text(text = stringResource(AccountLabels.lotKindLabel(option)))
            RadioButton(selected = option == lot.kind, onClick = null)
        }
    }
}
