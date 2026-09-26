package com.aicfo.data.repository

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.aicfo.core.common.AppError
import com.aicfo.core.common.Err
import com.aicfo.core.common.FakeClock
import com.aicfo.core.common.Ok
import com.aicfo.core.common.Result
import com.aicfo.core.common.TestDispatchers
import com.aicfo.core.common.UuidIdGenerator
import com.aicfo.core.database.CfoDatabase
import com.aicfo.core.model.Money
import com.aicfo.domain.engines.vehicle.DueBasis
import com.aicfo.domain.engines.vehicle.PredictedOutflowLabel
import com.aicfo.domain.engines.vehicle.RenewalItem
import com.aicfo.domain.engines.vehicle.VehicleAlertKind
import com.aicfo.domain.engines.vehicle.VehicleClass
import com.aicfo.domain.engines.vehicle.VehicleEngineFactory
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.Instant
import java.time.ZoneId

/**
 * The vehicles against a real database (issue 10.4; §12, ADR-0052).
 *
 * Why:  the prediction is proven in `:domain:engines:vehicle`. What only this layer can get wrong
 *       is the memory and the framing: a reading that does not survive, a correction that adds a
 *       second row instead of replacing one — which would quietly weight the median towards that
 *       day — a prediction made in the wrong time zone's today, or a vehicle from another profile
 *       appearing in this one's list.
 * What: recording a vehicle and its history, the prediction that comes back, correcting a reading,
 *       the profile scope, the soft delete, the refusals, and what the forecast is handed.
 * Result: a list that is about this household, and a prediction made in its own today.
 * Changelog: 2026-09-26 — Created for issue 10.4.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class VehicleRepositoryTest {
    private lateinit var database: CfoDatabase
    private lateinit var repository: VehicleRepository

    private val dispatcher = UnconfinedTestDispatcher()

    /** 15 August 2026, 04:30 UTC — which is already the 15th in Kolkata, and the 14th in London. */
    private val clock = FakeClock(Instant.parse("2026-08-15T04:30:00Z").toEpochMilli(), ZoneId.of("Asia/Kolkata"))
    private val activeProfileId = MutableStateFlow(PROFILE)

    /** Input: none. Output: an in-memory database and the vehicle repository over it. */
    @Before
    fun setUp() {
        database =
            Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), CfoDatabase::class.java)
                .allowMainThreadQueries()
                .build()
        repository =
            StoredVehicleRepository(
                database = database,
                engine = VehicleEngineFactory.create(),
                clock = clock,
                dispatchers = TestDispatchers(dispatcher),
                activeProfileId = activeProfileId,
                idGenerator = UuidIdGenerator(),
            )
    }

    /** Input: none. Output: closed. */
    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun `a vehicle with no history still has a date`() =
        runTest(dispatcher) {
            repository.addVehicle("Swift", VehicleClass.HATCHBACK).expectOk()

            val entry = repository.observeVehicles().first().single()

            assertEquals("Swift", entry.vehicle.label)
            assertEquals(0L, entry.prediction.kmPerMonth)
            assertEquals(DueBasis.TIME, entry.prediction.nextService.basis)
            assertEquals("2027-08-15", entry.prediction.nextService.dueIsoDate)
        }

    @Test
    fun `readings become a rate, and the rate becomes a date`() =
        runTest(dispatcher) {
            val id = repository.addVehicle("Swift", VehicleClass.HATCHBACK).expectOk()
            repository.logService(id, "2026-06-01", 40_000, Money(5_00_000L)).expectOk()
            repository.logOdometer(id, "2026-06-03", 40_000).expectOk()
            repository.logOdometer(id, "2026-08-02", 44_000).expectOk()

            val prediction = repository.observeVehicles().first().single().prediction

            assertEquals("2,000 km a month", 2_000L, prediction.kmPerMonth)
            assertEquals(DueBasis.DISTANCE, prediction.nextService.basis)
            assertEquals("2026-10-31", prediction.nextService.dueIsoDate)
            assertEquals(44_000L, prediction.latestOdometerKm)
        }

    @Test
    fun `correcting today's reading replaces it rather than adding a second`() =
        runTest(dispatcher) {
            // Two readings for one day would weight the median slope towards that day — which is a
            // storage promise, kept by the unique index rather than by whoever writes the row.
            val id = repository.addVehicle("Swift", VehicleClass.HATCHBACK).expectOk()
            repository.logOdometer(id, "2026-08-02", 44_000).expectOk()
            repository.logOdometer(id, "2026-08-02", 45_000).expectOk()

            val entry = repository.observeVehicles().first().single()

            assertEquals(1, entry.readings.size)
            assertEquals(45_000L, entry.readings.single().km)
        }

    @Test
    fun `what the household pays moves the expected cost`() =
        runTest(dispatcher) {
            val id = repository.addVehicle("Swift", VehicleClass.HATCHBACK).expectOk()
            repository.logService(id, "2025-06-01", 30_000, Money(11_00_000L)).expectOk()
            repository.logService(id, "2026-06-01", 40_000, Money(11_00_000L)).expectOk()

            val prediction = repository.observeVehicles().first().single().prediction

            assertEquals(20_000, prediction.personalIndexBps)
            assertEquals(Money(8_00_000L), prediction.predictedCost.low)
        }

    @Test
    fun `a renewal is chased, and enters the forecast only with a price`() =
        runTest(dispatcher) {
            val id = repository.addVehicle("Swift", VehicleClass.HATCHBACK).expectOk()
            repository.setRenewal(id, RenewalItem.INSURANCE, "2025-09-01", Money(12_00_000L)).expectOk()
            repository.setRenewal(id, RenewalItem.PUC, "2026-08-01", null).expectOk()

            val entry = repository.observeVehicles().first().single()
            val outflows = repository.observePredictedOutflows().first()

            assertTrue(
                "the insurance renewal was not chased",
                entry.prediction.alerts.any { it.kind == VehicleAlertKind.RENEWAL_DUE },
            )
            assertEquals(
                "a renewal with no recorded price cannot be forecast (P-03)",
                listOf(PredictedOutflowLabel.INSURANCE, PredictedOutflowLabel.SERVICE),
                outflows.map { it.outflow.label },
            )
            assertEquals("Swift", outflows.first().vehicleLabel)
        }

    @Test
    fun `changing a renewal's date replaces the row it is about`() =
        runTest(dispatcher) {
            val id = repository.addVehicle("Swift", VehicleClass.HATCHBACK).expectOk()
            repository.setRenewal(id, RenewalItem.INSURANCE, "2025-09-01", Money(12_00_000L)).expectOk()
            repository.setRenewal(id, RenewalItem.INSURANCE, "2026-09-01", Money(13_00_000L)).expectOk()

            val outflow =
                repository.observePredictedOutflows().first().first {
                    it.outflow.label == PredictedOutflowLabel.INSURANCE
                }

            assertEquals("2027-09-01", outflow.outflow.isoDate)
            assertEquals(Money(13_00_000L), outflow.outflow.amount)
        }

    @Test
    fun `the prediction is made in the profile's own today, not the device's UTC day`() =
        runTest(dispatcher) {
            // 04:30 UTC on the 15th is 10:00 on the 15th in Kolkata but still the 14th in London.
            // A service predicted a day early is a small error; a due-date alert that fires a day
            // late is not (TIM-001).
            val id = repository.addVehicle("Swift", VehicleClass.HATCHBACK).expectOk()

            val kolkata = repository.observeVehicles().first().single().prediction.nextService.dueIsoDate

            assertEquals("2027-08-15", kolkata)
        }

    @Test
    fun `another profile's vehicles are not this profile's`() =
        runTest(dispatcher) {
            repository.addVehicle("Swift", VehicleClass.HATCHBACK).expectOk()
            activeProfileId.value = "profile:other"

            assertTrue(repository.observeVehicles().first().isEmpty())
        }

    @Test
    fun `removing a vehicle takes it off the list and keeps its history`() =
        runTest(dispatcher) {
            val id = repository.addVehicle("Swift", VehicleClass.HATCHBACK).expectOk()
            repository.logOdometer(id, "2026-08-02", 44_000).expectOk()

            repository.removeVehicle(id).expectOk()

            assertTrue(repository.observeVehicles().first().isEmpty())
            assertEquals(
                "a soft delete keeps the row for the archive (DB-002)",
                1,
                database.vehicleDao().allOdometer(PROFILE).size,
            )
        }

    @Test
    fun `impossible inputs are refused by field`() =
        runTest(dispatcher) {
            val id = repository.addVehicle("Swift", VehicleClass.HATCHBACK).expectOk()

            assertEquals(AppError.Validation("vehicle.label"), error(repository.addVehicle("  ", VehicleClass.SUV)))
            assertEquals(AppError.Validation("vehicle.odometer"), error(repository.logOdometer(id, "2026-08-02", -1)))
            assertEquals(
                AppError.Validation("vehicle.serviceCost"),
                error(repository.logService(id, "2026-08-02", 44_000, Money(-1L))),
            )
        }

    @Test
    fun `a vehicle whose class is no longer known is left out rather than guessed at`() =
        runTest(dispatcher) {
            // A class string that maps to no knowledge-base row cannot be predicted from, and a
            // guessed interval would put a fabricated date in someone's calendar (P-03).
            repository.addVehicle("Swift", VehicleClass.HATCHBACK).expectOk()
            database.vehicleDao().allVehicles(PROFILE).single().let { row ->
                database.vehicleDao().upsertVehicle(row.copy(vehicleClass = "HOVERCRAFT"))
            }

            assertTrue(repository.observeVehicles().first().isEmpty())
        }

    @Test
    fun `nothing recorded means nothing for the forecast to carry`() =
        runTest(dispatcher) {
            assertTrue(repository.observePredictedOutflows().first().isEmpty())
            assertNull(repository.observeVehicles().first().firstOrNull())
        }

    // --- helpers ----------------------------------------------------------------------------------

    private fun <T> Result<T, AppError>.expectOk(): T =
        when (this) {
            is Ok -> value
            is Err -> throw AssertionError("expected Ok, got $error")
        }

    private fun <T> error(result: Result<T, AppError>): AppError =
        when (result) {
            is Ok -> throw AssertionError("expected Err, got ${result.value}")
            is Err -> result.error
        }

    private companion object {
        const val PROFILE = "local"
    }
}
