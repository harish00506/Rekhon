package com.aicfo.feature.vehicle

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import com.aicfo.core.designsystem.component.CfoButton
import com.aicfo.core.designsystem.component.CfoCard
import com.aicfo.core.designsystem.component.CfoSecondaryButton
import com.aicfo.core.designsystem.theme.CfoDimens
import com.aicfo.core.model.MoneyFormatter
import com.aicfo.data.repository.VehicleEntry
import com.aicfo.domain.engines.vehicle.DueBasis
import com.aicfo.domain.engines.vehicle.RenewalItem
import com.aicfo.domain.engines.vehicle.VehicleAlert
import com.aicfo.domain.engines.vehicle.VehicleAlertKind
import kotlin.math.absoluteValue

// One vehicle's card, and the log that opens under it (issue 10.4; §12, P-02).
//
// Why:  the card has two halves that change for different reasons — what the engine concluded, and
//       the form that feeds it — so they live in their own file, away from the screen's scaffolding.
// What: the due date and why it is that date, the cost to expect, the alerts, the history behind
//       them, and the log.
// Result: a figure a user can disagree with by pointing at the step they disagree with.
// Changelog: 2026-09-26 — Created for issue 10.4.

/**
 * One vehicle: its prediction, its alerts, and the log.
 * Result: the card. Input: [entry]; [uiState]; [onEvent]. Output: none.
 */
@Composable
internal fun VehicleCard(
    entry: VehicleEntry,
    uiState: VehiclesUiState,
    onEvent: (VehiclesEvent) -> Unit,
) {
    val prediction = entry.prediction
    CfoCard {
        Column(verticalArrangement = Arrangement.spacedBy(CfoDimens.spaceSm)) {
            Text(entry.vehicle.label, style = MaterialTheme.typography.titleMedium)
            Text(dueSentence(entry), style = MaterialTheme.typography.bodyLarge)
            Text(costSentence(entry), style = MaterialTheme.typography.bodyMedium)
            Text(rateSentence(entry), style = MaterialTheme.typography.bodySmall)
            prediction.alerts.forEach { alert ->
                Text(alertSentence(alert), style = MaterialTheme.typography.bodyMedium)
            }
            Text(historySentence(entry), style = MaterialTheme.typography.bodySmall)
            Text(
                stringResource(
                    R.string.vehicles_evidence,
                    prediction.provenance.engineId,
                    prediction.provenance.engineVersion,
                    prediction.provenance.evidence.joinToString(" · ") { it.ruleId },
                ),
                style = MaterialTheme.typography.labelSmall,
            )
            CfoSecondaryButton(
                text =
                    stringResource(
                        if (uiState.openVehicleId == entry.vehicle.id) {
                            R.string.vehicles_log_hide
                        } else {
                            R.string.vehicles_log_action
                        },
                    ),
                onClick = { onEvent(VehiclesEvent.ToggleLog(entry.vehicle.id)) },
            )
            if (uiState.openVehicleId == entry.vehicle.id) {
                VehicleLog(entry, uiState, onEvent)
            }
        }
    }
}

/**
 * The form under an opened card: a reading, a service, the two renewals, and removal.
 * Result: the form. Input: [entry]; [uiState]; [onEvent]. Output: none.
 */
@Composable
private fun VehicleLog(
    entry: VehicleEntry,
    uiState: VehiclesUiState,
    onEvent: (VehiclesEvent) -> Unit,
) {
    val id = entry.vehicle.id
    Column(verticalArrangement = Arrangement.spacedBy(CfoDimens.spaceSm)) {
        NumberField(uiState.odometerKm, R.string.vehicles_odometer_km) {
            onEvent(VehiclesEvent.OdometerKmChanged(it))
        }
        TextField(uiState.odometerDate, R.string.vehicles_odometer_date) {
            onEvent(VehiclesEvent.OdometerDateChanged(it))
        }
        CfoButton(
            text = stringResource(R.string.vehicles_odometer_action),
            onClick = { onEvent(VehiclesEvent.LogOdometer(id)) },
            enabled = uiState.canLogOdometer,
        )
        ServiceFields(id, uiState, onEvent)
        CfoSecondaryButton(
            text = stringResource(R.string.vehicles_insurance_action),
            onClick = {
                onEvent(VehiclesEvent.SetRenewal(id, RenewalItem.INSURANCE, uiState.odometerDate, ""))
            },
        )
        CfoSecondaryButton(
            text = stringResource(R.string.vehicles_puc_action),
            onClick = { onEvent(VehiclesEvent.SetRenewal(id, RenewalItem.PUC, uiState.odometerDate, "")) },
        )
        CfoSecondaryButton(
            text = stringResource(R.string.vehicles_remove),
            onClick = { onEvent(VehiclesEvent.Remove(id)) },
        )
    }
}

/**
 * The three fields a service takes, and the button that files it.
 * Why:    a service moves both the next date and the expected price, so it is the one form worth
 *         its own three fields rather than a single amount.
 * Result: the fields. Input: [id]; [uiState]; [onEvent]. Output: none.
 */
@Composable
private fun ServiceFields(
    id: String,
    uiState: VehiclesUiState,
    onEvent: (VehiclesEvent) -> Unit,
) {
    NumberField(uiState.serviceCostRupees, R.string.vehicles_service_cost) {
        onEvent(VehiclesEvent.ServiceCostChanged(it))
    }
    NumberField(uiState.serviceOdometerKm, R.string.vehicles_service_km) {
        onEvent(VehiclesEvent.ServiceOdometerChanged(it))
    }
    TextField(uiState.serviceDate, R.string.vehicles_service_date) {
        onEvent(VehiclesEvent.ServiceDateChanged(it))
    }
    CfoButton(
        text = stringResource(R.string.vehicles_service_action),
        onClick = { onEvent(VehiclesEvent.LogService(id)) },
        enabled = uiState.canLogService,
    )
}

/** Result: the due-date sentence, naming which limit decided it (P-02). Input: [entry]. */
@Composable
private fun dueSentence(entry: VehicleEntry): String {
    val next = entry.prediction.nextService
    val odometer = next.dueOdometerKm
    return when {
        next.basis == DueBasis.DISTANCE && odometer != null ->
            stringResource(R.string.vehicles_due_distance, next.dueIsoDate, odometer.toString())
        odometer != null -> stringResource(R.string.vehicles_due_time, next.dueIsoDate, odometer.toString())
        else -> stringResource(R.string.vehicles_due_odometer_unknown, next.dueIsoDate)
    }
}

/** Result: the cost sentence, saying when it is the household's own figures. Input: [entry]. */
@Composable
private fun costSentence(entry: VehicleEntry): String {
    val cost = entry.prediction.predictedCost
    val low = MoneyFormatter.format(cost.low)
    val high = MoneyFormatter.format(cost.high)
    return if (entry.prediction.personalIndexBps == null) {
        stringResource(R.string.vehicles_expect, low, high)
    } else {
        stringResource(R.string.vehicles_expect_personal, low, high)
    }
}

/** Result: the rate sentence, or the honest admission that there is not one yet. Input: [entry]. */
@Composable
private fun rateSentence(entry: VehicleEntry): String =
    if (entry.prediction.kmPerMonth > 0L) {
        stringResource(R.string.vehicles_rate, entry.prediction.kmPerMonth.toString())
    } else {
        stringResource(R.string.vehicles_rate_unknown)
    }

/** Result: one alert in words, with a pluralised day count (§21.6). Input: [alert]. */
@Composable
private fun alertSentence(alert: VehicleAlert): String {
    val days =
        pluralStringResource(
            R.plurals.vehicles_days,
            alert.daysAway.absoluteValue.toInt(),
            alert.daysAway.absoluteValue.toInt(),
        )
    return when (alert.kind) {
        VehicleAlertKind.SERVICE_DUE -> stringResource(R.string.vehicles_alert_service_due, days)
        VehicleAlertKind.SERVICE_OVERDUE -> stringResource(R.string.vehicles_alert_service_overdue, days)
        VehicleAlertKind.RENEWAL_DUE -> stringResource(R.string.vehicles_alert_renewal_due, days)
        VehicleAlertKind.RENEWAL_EXPIRED -> stringResource(R.string.vehicles_alert_renewal_expired, days)
    }
}

/** Result: what the prediction was made from, so the user can judge it (P-02). Input: [entry]. */
@Composable
private fun historySentence(entry: VehicleEntry): String {
    val readings = pluralStringResource(R.plurals.vehicles_readings, entry.readings.size, entry.readings.size)
    val services = pluralStringResource(R.plurals.vehicles_services, entry.services.size, entry.services.size)
    return stringResource(R.string.vehicles_history, "$readings, $services")
}
