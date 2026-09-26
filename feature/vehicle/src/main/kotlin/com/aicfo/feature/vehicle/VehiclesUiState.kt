package com.aicfo.feature.vehicle

import com.aicfo.data.repository.VehicleEntry
import com.aicfo.domain.engines.vehicle.RenewalItem
import com.aicfo.domain.engines.vehicle.VehicleClass

/**
 * What §12's vehicle screen shows (issue 10.4; ARC-004).
 *
 * Why:  one immutable data class per screen as a `StateFlow`, so the screen is a pure function of
 *       it and a test can assert the whole sequence including loading and error.
 * What: the vehicles with their predictions, the fields of the add form, which vehicle's log is
 *       open, and the last refusal.
 * Result: the screen renders this and nothing else.
 * Changelog: 2026-09-26 — Created for issue 10.4.
 *
 * Input:  [vehicles] — each with the prediction as of today; [isLoaded] — false until the first
 *         emission, so an empty list is not mistaken for "nothing recorded"; [newLabel] and
 *         [newClass] — the add form; [openVehicleId] — whose log is expanded, or `null`;
 *         [odometerKm] and [odometerDate] — the reading being entered; [serviceCostRupees],
 *         [serviceOdometerKm] and [serviceDate] — the service being entered; [errorCode] — the
 *         field a refusal named, or `null`.
 * Output: an immutable value.
 */
data class VehiclesUiState(
    val vehicles: List<VehicleEntry> = emptyList(),
    val isLoaded: Boolean = false,
    val newLabel: String = "",
    val newClass: VehicleClass = VehicleClass.HATCHBACK,
    val openVehicleId: String? = null,
    val odometerKm: String = "",
    val odometerDate: String = "",
    val serviceCostRupees: String = "",
    val serviceOdometerKm: String = "",
    val serviceDate: String = "",
    val errorCode: String? = null,
) {
    /** Whether the add button does anything: a vehicle needs a name. */
    val canAdd: Boolean get() = newLabel.isNotBlank()

    /** Whether a reading can be recorded: a distance and a date. */
    val canLogOdometer: Boolean get() = odometerKm.isNotBlank() && odometerDate.isNotBlank()

    /** Whether a service can be recorded: a cost, a reading and a date. */
    val canLogService: Boolean
        get() = serviceCostRupees.isNotBlank() && serviceOdometerKm.isNotBlank() && serviceDate.isNotBlank()
}

/**
 * Everything the user can do on this screen (ARC-004: events flow up a sealed interface).
 * Changelog: 2026-09-26 — Created for issue 10.4.
 */
sealed interface VehiclesEvent {
    /** The name of a vehicle being added. */
    data class LabelChanged(val label: String) : VehiclesEvent

    /** The class of a vehicle being added — it selects every interval and price in the KB. */
    data class ClassChanged(val vehicleClass: VehicleClass) : VehiclesEvent

    /** Record the vehicle. */
    data object AddVehicle : VehiclesEvent

    /** Open or close one vehicle's log. */
    data class ToggleLog(val vehicleId: String) : VehiclesEvent

    /** The odometer reading being entered. */
    data class OdometerKmChanged(val km: String) : VehiclesEvent

    /** The day that reading was taken. */
    data class OdometerDateChanged(val isoDate: String) : VehiclesEvent

    /** Record the reading. */
    data class LogOdometer(val vehicleId: String) : VehiclesEvent

    /** What the service cost, in rupees as typed. */
    data class ServiceCostChanged(val rupees: String) : VehiclesEvent

    /** The odometer at the time of the service. */
    data class ServiceOdometerChanged(val km: String) : VehiclesEvent

    /** The day of the service. */
    data class ServiceDateChanged(val isoDate: String) : VehiclesEvent

    /** Record the service — which moves both the next date and the expected price. */
    data class LogService(val vehicleId: String) : VehiclesEvent

    /** Record when a policy or certificate was last renewed, and what it cost. */
    data class SetRenewal(
        val vehicleId: String,
        val item: RenewalItem,
        val isoDate: String,
        val costRupees: String,
    ) : VehiclesEvent

    /** Take a vehicle off the list; its history is kept (DB-002). */
    data class Remove(val vehicleId: String) : VehiclesEvent
}
