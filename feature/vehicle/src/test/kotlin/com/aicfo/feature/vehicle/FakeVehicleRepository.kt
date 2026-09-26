package com.aicfo.feature.vehicle

import com.aicfo.core.common.AppError
import com.aicfo.core.common.Ok
import com.aicfo.core.common.Result
import com.aicfo.core.model.EngineProvenance
import com.aicfo.core.model.Money
import com.aicfo.core.model.RuleCitation
import com.aicfo.data.repository.VehicleEntry
import com.aicfo.data.repository.VehicleOutflow
import com.aicfo.data.repository.VehicleRepository
import com.aicfo.domain.engines.vehicle.CostRange
import com.aicfo.domain.engines.vehicle.DueBasis
import com.aicfo.domain.engines.vehicle.NextService
import com.aicfo.domain.engines.vehicle.OdometerReading
import com.aicfo.domain.engines.vehicle.RenewalItem
import com.aicfo.domain.engines.vehicle.ServiceRecord
import com.aicfo.domain.engines.vehicle.Vehicle
import com.aicfo.domain.engines.vehicle.VehicleAlert
import com.aicfo.domain.engines.vehicle.VehicleAlertKind
import com.aicfo.domain.engines.vehicle.VehicleClass
import com.aicfo.domain.engines.vehicle.VehiclePrediction
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map

/**
 * A recording [VehicleRepository] for this module's tests (issue 10.4).
 *
 * Why:  the screen's job is to render a prediction and forward what the user typed. A hand-written
 *       fake says exactly what was forwarded — and fails loudly on a method nobody expected to be
 *       called, which a mock configured to return nothing would not.
 * What: an editable list, plus a log of every write.
 * Result: a test can assert the paise a rupee field turned into, without a database.
 * Changelog: 2026-09-26 — Created for issue 10.4.
 */
internal class FakeVehicleRepository : VehicleRepository {
    /** The list the screen renders. */
    val entries = MutableStateFlow(listOf(entry()))

    /** Every write, in order, as `"method:arguments"`. */
    val writes = mutableListOf<String>()

    override fun observeVehicles(): Flow<List<VehicleEntry>> = entries

    override suspend fun addVehicle(
        label: String,
        vehicleClass: VehicleClass,
        accountId: String?,
    ): Result<String, AppError> {
        writes += "add:$label:$vehicleClass"
        return Ok("vehicle:new")
    }

    override suspend fun logOdometer(
        vehicleId: String,
        isoDate: String,
        km: Long,
    ): Result<Unit, AppError> {
        writes += "odometer:$vehicleId:$isoDate:$km"
        return Ok(Unit)
    }

    override suspend fun logService(
        vehicleId: String,
        isoDate: String,
        odometerKm: Long,
        cost: Money,
        note: String?,
    ): Result<Unit, AppError> {
        writes += "service:$vehicleId:$isoDate:$odometerKm:${cost.minor}"
        return Ok(Unit)
    }

    override suspend fun setRenewal(
        vehicleId: String,
        item: RenewalItem,
        lastDoneIsoDate: String,
        lastCost: Money?,
    ): Result<Unit, AppError> {
        writes += "renewal:$vehicleId:$item:$lastDoneIsoDate:${lastCost?.minor ?: "-"}"
        return Ok(Unit)
    }

    override suspend fun removeVehicle(vehicleId: String): Result<Unit, AppError> {
        writes += "remove:$vehicleId"
        return Ok(Unit)
    }

    override fun observePredictedOutflows(): Flow<List<VehicleOutflow>> = entries.map { emptyList() }

    companion object {
        /** Result: one vehicle with a prediction the screen can render. Input: none. */
        fun entry(
            label: String = "Swift",
            alerts: List<VehicleAlert> = listOf(ALERT),
            personalIndexBps: Int? = null,
        ): VehicleEntry =
            VehicleEntry(
                vehicle = Vehicle(VEHICLE_ID, label, VehicleClass.HATCHBACK),
                prediction =
                    VehiclePrediction(
                        vehicleId = VEHICLE_ID,
                        kmPerMonth = 2_000,
                        latestOdometerKm = 44_000,
                        nextService = NextService("2026-10-31", 50_000, DueBasis.DISTANCE, 77),
                        predictedCost = CostRange(Money(4_00_000L), Money(7_00_000L)),
                        personalIndexBps = personalIndexBps,
                        alerts = alerts,
                        scheduled = emptyList(),
                        provenance =
                            EngineProvenance(
                                engineId = "AI-VEH",
                                engineVersion = "1.0",
                                computedAtUtcMillis = 1_786_000_000_000L,
                                evidence = listOf(RuleCitation("VEH-KB.service", "1.1")),
                            ),
                    ),
                readings = listOf(OdometerReading("2026-08-02", 44_000)),
                services = listOf(ServiceRecord("2026-06-01", 40_000, Money(5_00_000L))),
            )

        const val VEHICLE_ID = "vehicle:1"

        private val ALERT =
            VehicleAlert(VehicleAlertKind.SERVICE_DUE, "2026-10-31", 12, Money(5_50_000L), "VEH-ALERTS")
    }
}
