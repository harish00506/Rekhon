package com.aicfo.data.repository

import com.aicfo.core.common.AppError
import com.aicfo.core.common.Clock
import com.aicfo.core.common.DispatcherProvider
import com.aicfo.core.common.Err
import com.aicfo.core.common.IdGenerator
import com.aicfo.core.common.Ok
import com.aicfo.core.common.Result
import com.aicfo.core.common.runCatchingToResult
import com.aicfo.core.database.CfoDatabase
import com.aicfo.core.database.entity.VehicleEntity
import com.aicfo.core.database.entity.VehicleOdometerEntity
import com.aicfo.core.database.entity.VehicleRenewalEntity
import com.aicfo.core.database.entity.VehicleServiceEntity
import com.aicfo.core.model.Money
import com.aicfo.domain.engines.vehicle.OdometerReading
import com.aicfo.domain.engines.vehicle.PredictedOutflow
import com.aicfo.domain.engines.vehicle.RenewalItem
import com.aicfo.domain.engines.vehicle.RenewalRecord
import com.aicfo.domain.engines.vehicle.ServiceRecord
import com.aicfo.domain.engines.vehicle.Vehicle
import com.aicfo.domain.engines.vehicle.VehicleClass
import com.aicfo.domain.engines.vehicle.VehicleEngine
import com.aicfo.domain.engines.vehicle.VehicleInput
import com.aicfo.domain.engines.vehicle.VehiclePrediction
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

/**
 * The vehicles, and what AI-VEH says about each (issue 10.4; §12, AI-ARC-001, ARC-005).
 *
 * Why:  AI-VEH is pure and knows nothing about this household. Something has to keep the readings
 *       and the service bills, hand them to the engine in the profile's own today, and turn what
 *       comes back into something a screen and the forecast can use — without either of them ever
 *       seeing a DAO.
 * What: the vehicles with their live predictions, the writes that record a reading, a service or a
 *       renewal, and the outflows the ninety-day forecast should carry.
 * Result: a ViewModel sees [VehicleEntry]s (ARC-005). Recording is the user's; **predicting is not
 *         booking** — nothing here spends a rupee (P-07).
 * Changelog: 2026-09-26 — Created for issue 10.4.
 */
interface VehicleRepository {
    /**
     * Every vehicle with its prediction, as things stand today.
     * Why:    the prediction is recomputed on every emission rather than stored. It depends on
     *         today's date, so a stored one is stale by morning — and the inputs it is made from
     *         are what the app keeps.
     * Result: re-emits on every write and on every day rollover. Input: none.
     * Output: `Flow<List<VehicleEntry>>`.
     */
    fun observeVehicles(): Flow<List<VehicleEntry>>

    /**
     * Records a vehicle.
     * Result: `Ok(id)`; `Err(Validation("vehicle.label"))` for a nameless one.
     * Input:  [label]; [vehicleClass]; [accountId] — an account to link, where there is one.
     * Output: `Result<String, AppError>`.
     */
    suspend fun addVehicle(
        label: String,
        vehicleClass: VehicleClass,
        accountId: String? = null,
    ): Result<String, AppError>

    /**
     * Records an odometer reading.
     * Why:    one row per reading, and one reading per day: correcting today's figure replaces it
     *         rather than adding a second, which would weight the median towards that day.
     * Result: `Ok(Unit)`; `Err(Validation("vehicle.odometer"))` for a negative distance.
     * Input:  [vehicleId]; [isoDate]; [km]. Output: `Result<Unit, AppError>`.
     */
    suspend fun logOdometer(
        vehicleId: String,
        isoDate: String,
        km: Long,
    ): Result<Unit, AppError>

    /**
     * Records a service that was paid for.
     * Why:    it is the clock the next interval runs from **and** the evidence for what a service
     *         costs this household, so it is one write that moves both the date and the price.
     * Result: `Ok(Unit)`; `Err(Validation("vehicle.serviceCost"))` for a negative bill.
     * Input:  [vehicleId]; [isoDate]; [odometerKm]; [cost]; [note].
     * Output: `Result<Unit, AppError>`.
     */
    @Suppress("LongParameterList") // one per column of a service record; a wrapper type would hide it
    suspend fun logService(
        vehicleId: String,
        isoDate: String,
        odometerKm: Long,
        cost: Money,
        note: String? = null,
    ): Result<Unit, AppError>

    /**
     * Records when a policy or certificate was last renewed, and what it cost.
     * Why:    the cost is the household's own figure and the only honest basis for forecasting the
     *         next one — the knowledge base has cadences, not premiums (P-03).
     * Result: `Ok(Unit)`; one row per item per vehicle, so this replaces rather than accumulates.
     * Input:  [vehicleId]; [item]; [lastDoneIsoDate]; [lastCost] — `null` when unknown.
     * Output: `Result<Unit, AppError>`.
     */
    suspend fun setRenewal(
        vehicleId: String,
        item: RenewalItem,
        lastDoneIsoDate: String,
        lastCost: Money?,
    ): Result<Unit, AppError>

    /**
     * Removes a vehicle from the list.
     * Result: `Ok(Unit)`; a **soft** delete (DB-002) — the history stays for the archive.
     * Input:  [vehicleId]. Output: `Result<Unit, AppError>`.
     */
    suspend fun removeVehicle(vehicleId: String): Result<Unit, AppError>

    /**
     * What the forecast should carry for every vehicle (§12 into 9.2's horizon).
     * Why:    a flow rather than a one-shot read, because the forecast subscribes: recording an
     *         odometer reading moves the next service, which moves the ninety-day picture, and the
     *         user should see that without leaving the screen.
     * Result: one entry per predicted service or renewal, each with the vehicle it belongs to.
     *         Empty when nothing is recorded.
     * Input:  none. Output: `Flow<List<VehicleOutflow>>`.
     */
    fun observePredictedOutflows(): Flow<List<VehicleOutflow>>
}

/**
 * One vehicle and what the engine says about it.
 * Input:  [vehicle] — as recorded; [prediction] — as of today; [readings] and [services] — the
 *         history behind it, so the screen can show the working (P-02).
 * Output: an immutable value.
 * Changelog: 2026-09-26 — Created for issue 10.4.
 */
data class VehicleEntry(
    val vehicle: Vehicle,
    val prediction: VehiclePrediction,
    val readings: List<OdometerReading>,
    val services: List<ServiceRecord>,
)

/**
 * One predicted cost, with the vehicle it belongs to.
 * Input:  [vehicleId]; [vehicleLabel] — for the forecast line's own label; [outflow].
 * Output: an immutable value.
 * Changelog: 2026-09-26 — Created for issue 10.4.
 */
data class VehicleOutflow(
    val vehicleId: String,
    val vehicleLabel: String,
    val outflow: PredictedOutflow,
)

/**
 * [VehicleRepository] over the four vehicle tables (issue 10.4).
 * Input:  [database]; [engine]; [clock] — the profile's own today (TIM-001); [dispatchers];
 *         [activeProfileId]; [idGenerator].
 * Output: the repository.
 * Changelog: 2026-09-26 — Created for issue 10.4.
 */
internal class StoredVehicleRepository(
    private val database: CfoDatabase,
    private val engine: VehicleEngine,
    private val clock: Clock,
    private val dispatchers: DispatcherProvider,
    private val activeProfileId: Flow<String>,
    private val idGenerator: IdGenerator,
) : VehicleRepository {
    override fun observeVehicles(): Flow<List<VehicleEntry>> =
        activeProfileId.flatMapLatest { profileId ->
            val dao = database.vehicleDao()
            combine(
                dao.observeVehicles(profileId),
                dao.observeOdometer(profileId),
                dao.observeServices(profileId),
                dao.observeRenewals(profileId),
            ) { vehicles, readings, services, renewals ->
                vehicles.mapNotNull { row ->
                    entryFor(
                        row = row,
                        readings = readings.filter { it.vehicleId == row.id },
                        services = services.filter { it.vehicleId == row.id },
                        renewals = renewals.filter { it.vehicleId == row.id },
                    )
                }
            }
        }

    override suspend fun addVehicle(
        label: String,
        vehicleClass: VehicleClass,
        accountId: String?,
    ): Result<String, AppError> =
        withContext(dispatchers.io) {
            if (label.isBlank()) {
                Err(AppError.Validation(FIELD_LABEL))
            } else {
                runCatchingToResult {
                    val now = clock.nowUtcMillis()
                    val id = idGenerator.newId("vehicle")
                    database.vehicleDao().upsertVehicle(
                        VehicleEntity(
                            id = id,
                            profileId = activeProfileId.first(),
                            label = label.trim(),
                            vehicleClass = vehicleClass.name,
                            accountId = accountId,
                            createdAtUtcMillis = now,
                            updatedAtUtcMillis = now,
                        ),
                    )
                    id
                }
            }
        }

    override suspend fun logOdometer(
        vehicleId: String,
        isoDate: String,
        km: Long,
    ): Result<Unit, AppError> =
        withContext(dispatchers.io) {
            if (km < 0L) {
                Err(AppError.Validation(FIELD_ODOMETER))
            } else {
                runCatchingToResult {
                    val now = clock.nowUtcMillis()
                    database.vehicleDao().upsertOdometer(
                        VehicleOdometerEntity(
                            id = idGenerator.newId("odometer"),
                            profileId = activeProfileId.first(),
                            vehicleId = vehicleId,
                            readIsoDate = isoDate,
                            km = km,
                            createdAtUtcMillis = now,
                            updatedAtUtcMillis = now,
                        ),
                    )
                }
            }
        }

    override suspend fun logService(
        vehicleId: String,
        isoDate: String,
        odometerKm: Long,
        cost: Money,
        note: String?,
    ): Result<Unit, AppError> =
        withContext(dispatchers.io) {
            if (cost < Money.ZERO) {
                Err(AppError.Validation(FIELD_SERVICE_COST))
            } else {
                runCatchingToResult {
                    val now = clock.nowUtcMillis()
                    database.vehicleDao().upsertService(
                        VehicleServiceEntity(
                            id = idGenerator.newId("service"),
                            profileId = activeProfileId.first(),
                            vehicleId = vehicleId,
                            servicedIsoDate = isoDate,
                            odometerKm = odometerKm,
                            costMinor = cost.minor,
                            note = note,
                            createdAtUtcMillis = now,
                            updatedAtUtcMillis = now,
                        ),
                    )
                }
            }
        }

    override suspend fun setRenewal(
        vehicleId: String,
        item: RenewalItem,
        lastDoneIsoDate: String,
        lastCost: Money?,
    ): Result<Unit, AppError> =
        withContext(dispatchers.io) {
            runCatchingToResult {
                val now = clock.nowUtcMillis()
                database.vehicleDao().upsertRenewal(
                    VehicleRenewalEntity(
                        id = idGenerator.newId("renewal"),
                        profileId = activeProfileId.first(),
                        vehicleId = vehicleId,
                        item = item.name,
                        lastDoneIsoDate = lastDoneIsoDate,
                        lastCostMinor = lastCost?.minor,
                        createdAtUtcMillis = now,
                        updatedAtUtcMillis = now,
                    ),
                )
            }
        }

    override suspend fun removeVehicle(vehicleId: String): Result<Unit, AppError> =
        withContext(dispatchers.io) {
            runCatchingToResult {
                database.vehicleDao().softDeleteVehicle(activeProfileId.first(), vehicleId, clock.nowUtcMillis())
            }
        }

    override fun observePredictedOutflows(): Flow<List<VehicleOutflow>> =
        observeVehicles().map { entries ->
            entries.flatMap { entry ->
                entry.prediction.scheduled.map { VehicleOutflow(entry.vehicle.id, entry.vehicle.label, it) }
            }
        }

    /**
     * Turns one vehicle's rows into an entry, or drops it when its class is not one the knowledge
     * base knows.
     * Why:    a class string that no longer maps to a KB row cannot be predicted from, and guessing
     *         one would put a fabricated interval into someone's calendar (P-03). Dropping it is
     *         visible — the vehicle disappears from the list — where a guess would not be.
     * Result: the entry, or `null`. Input: [row]; [readings]; [services]; [renewals].
     * Output: `VehicleEntry?`.
     */
    private fun entryFor(
        row: VehicleEntity,
        readings: List<VehicleOdometerEntity>,
        services: List<VehicleServiceEntity>,
        renewals: List<VehicleRenewalEntity>,
    ): VehicleEntry? {
        val vehicleClass = VehicleClass.entries.firstOrNull { it.name == row.vehicleClass } ?: return null
        val vehicle = Vehicle(row.id, row.label, vehicleClass)
        val readingValues = readings.map { OdometerReading(it.readIsoDate, it.km) }
        val serviceValues = services.map { ServiceRecord(it.servicedIsoDate, it.odometerKm, Money(it.costMinor)) }
        val prediction =
            engine.predict(
                VehicleInput(
                    vehicle = vehicle,
                    readings = readingValues,
                    services = serviceValues,
                    renewals = renewals.mapNotNull { renewalOf(it) },
                    todayIsoDate = today(),
                    nowUtcMillis = clock.nowUtcMillis(),
                ),
            )
        return when (prediction) {
            is Ok -> VehicleEntry(vehicle, prediction.value, readingValues, serviceValues)
            is Err -> null
        }
    }

    /** Result: a renewal row as the engine's value, or `null` for an unknown item. */
    private fun renewalOf(row: VehicleRenewalEntity): RenewalRecord? =
        RenewalItem.entries.firstOrNull { it.name == row.item }?.let { item ->
            RenewalRecord(item, row.lastDoneIsoDate, row.lastCostMinor?.let(::Money))
        }

    /**
     * Result: today as an ISO date in the profile's own zone (TIM-001/TIM-002).
     * Why:    a service due "today" must mean the user's today, not the device's UTC day.
     * Input:  none. Output: `yyyy-MM-dd`.
     */
    private fun today(): String = clock.today().toString()

    private companion object {
        const val FIELD_LABEL = "vehicle.label"
        const val FIELD_ODOMETER = "vehicle.odometer"
        const val FIELD_SERVICE_COST = "vehicle.serviceCost"
    }
}
