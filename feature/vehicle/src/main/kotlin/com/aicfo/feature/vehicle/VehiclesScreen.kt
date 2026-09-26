package com.aicfo.feature.vehicle

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aicfo.core.designsystem.component.CfoButton
import com.aicfo.core.designsystem.component.CfoCard
import com.aicfo.core.designsystem.component.CfoSecondaryButton
import com.aicfo.core.designsystem.theme.CfoDimens
import com.aicfo.domain.engines.vehicle.VehicleClass

/**
 * §12's vehicles, on screen (issue 10.4; P-02, P-07).
 *
 * Why:  a vehicle's costs are the ones that arrive as surprises, and they are all predictable from
 *       two things the household already has — the odometer and the last bill. So the screen does
 *       two jobs at once: it collects those two things with as little friction as possible, and it
 *       shows what they imply, with the reason the date is the date.
 * What: the list, each vehicle's prediction and alerts, a log that opens under it, and an add form.
 * Result: dates and rupees to plan around. It ends by saying that nothing was booked, because a
 *         screen full of upcoming costs could otherwise read as one that arranges them (P-07).
 * Changelog: 2026-09-26 — Created for issue 10.4.
 *
 * Input:  [onDone]; [modifier]; [viewModel] — injected. Output: the screen.
 */
@Composable
fun VehiclesScreen(
    onDone: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: VehiclesViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    VehiclesContent(uiState, viewModel::onEvent, onDone, modifier)
}

/**
 * The screen as a function of its state (ARC-004).
 * Result: the rendered screen. Input: [uiState]; [onEvent]; [onDone]; [modifier]. Output: none.
 */
@Composable
internal fun VehiclesContent(
    uiState: VehiclesUiState,
    onEvent: (VehiclesEvent) -> Unit,
    onDone: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(CfoDimens.spaceMd),
        verticalArrangement = Arrangement.spacedBy(CfoDimens.spaceMd),
    ) {
        Text(stringResource(R.string.vehicles_title), style = MaterialTheme.typography.headlineSmall)
        Text(stringResource(R.string.vehicles_intro), style = MaterialTheme.typography.bodyMedium)
        if (uiState.errorCode != null) {
            Text(stringResource(R.string.vehicles_error), style = MaterialTheme.typography.bodyMedium)
        }
        if (uiState.isLoaded && uiState.vehicles.isEmpty()) {
            Text(stringResource(R.string.vehicles_empty), style = MaterialTheme.typography.bodyMedium)
        }
        uiState.vehicles.forEach { entry ->
            VehicleCard(entry = entry, uiState = uiState, onEvent = onEvent)
        }
        AddVehicleCard(uiState, onEvent)
        Text(stringResource(R.string.vehicles_nothing_booked), style = MaterialTheme.typography.labelSmall)
        CfoSecondaryButton(text = stringResource(R.string.vehicles_back), onClick = onDone)
    }
}

/** The form that records a vehicle: a name and a class, which selects every interval it uses. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AddVehicleCard(
    uiState: VehiclesUiState,
    onEvent: (VehiclesEvent) -> Unit,
) {
    CfoCard {
        Column(verticalArrangement = Arrangement.spacedBy(CfoDimens.spaceSm)) {
            Text(stringResource(R.string.vehicles_add_action), style = MaterialTheme.typography.titleSmall)
            OutlinedTextField(
                value = uiState.newLabel,
                onValueChange = { onEvent(VehiclesEvent.LabelChanged(it)) },
                label = { Text(stringResource(R.string.vehicles_add_label)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Text(stringResource(R.string.vehicles_add_class), style = MaterialTheme.typography.bodyMedium)
            // A flow row, not a Row: five chips do not fit across a phone, and a chip that is off
            // the edge of the screen is unselectable however well it tests (issue 10.2's lesson).
            FlowRow(horizontalArrangement = Arrangement.spacedBy(CfoDimens.spaceSm)) {
                VehicleClass.entries.forEach { vehicleClass ->
                    FilterChip(
                        selected = uiState.newClass == vehicleClass,
                        onClick = { onEvent(VehiclesEvent.ClassChanged(vehicleClass)) },
                        label = { Text(stringResource(labelFor(vehicleClass))) },
                    )
                }
            }
            CfoButton(
                text = stringResource(R.string.vehicles_add_action),
                onClick = { onEvent(VehiclesEvent.AddVehicle) },
                enabled = uiState.canAdd,
            )
        }
    }
}

/** A whole-number field; paise and kilometres are the ViewModel's business, not the user's. */
@Composable
internal fun NumberField(
    value: String,
    labelRes: Int,
    onChange: (String) -> Unit,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(stringResource(labelRes)) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        modifier = Modifier.fillMaxWidth(),
    )
}

/** A free-text field, for the ISO dates the log takes. */
@Composable
internal fun TextField(
    value: String,
    labelRes: Int,
    onChange: (String) -> Unit,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(stringResource(labelRes)) },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
}

/** Result: the string resource naming a vehicle class. Input: [vehicleClass]. Output: a res id. */
internal fun labelFor(vehicleClass: VehicleClass): Int =
    when (vehicleClass) {
        VehicleClass.TWO_WHEELER -> R.string.vehicles_class_two_wheeler
        VehicleClass.HATCHBACK -> R.string.vehicles_class_hatchback
        VehicleClass.SEDAN -> R.string.vehicles_class_sedan
        VehicleClass.SUV -> R.string.vehicles_class_suv
        VehicleClass.ELECTRIC -> R.string.vehicles_class_electric
    }
