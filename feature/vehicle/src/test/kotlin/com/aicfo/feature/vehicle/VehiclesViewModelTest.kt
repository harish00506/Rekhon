package com.aicfo.feature.vehicle

import com.aicfo.core.common.FakeClock
import com.aicfo.domain.engines.vehicle.RenewalItem
import com.aicfo.domain.engines.vehicle.VehicleClass
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.Instant
import java.time.ZoneId

/**
 * What the vehicle screen's state holder must get right (issue 10.4; ARC-004, MNY-001).
 *
 * Why:  this class does exactly one piece of arithmetic — rupees a person typed become paise — and
 *       one job besides: forwarding what was typed without editing it. Both are easy to get wrong
 *       quietly: a factor of a hundred in the wrong direction records a ₹5,000 service as ₹50, and
 *       a form that does not clear invites the same reading twice.
 * What: the subscription, the rupee conversion, the date defaulting to the profile's today, the
 *       guards on empty forms, the log toggle, and the refusal surfacing.
 * Result: the screen shows the engine's figures and the user's own words, and nothing invented.
 * Changelog: 2026-09-26 — Created for issue 10.4.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class VehiclesViewModelTest {
    private val dispatcher = UnconfinedTestDispatcher()
    private val repository = FakeVehicleRepository()
    private val clock = FakeClock(Instant.parse("2026-08-15T04:30:00Z").toEpochMilli(), ZoneId.of("Asia/Kolkata"))

    /** Input: none. Output: the main dispatcher is the test one. */
    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    /** Input: none. Output: the main dispatcher is restored. */
    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `the list arrives, and the screen knows it has loaded`() =
        runTest(dispatcher) {
            val state = viewModel().uiState.first()

            assertTrue(state.isLoaded)
            assertEquals("Swift", state.vehicles.single().vehicle.label)
        }

    @Test
    fun `the date fields start at the profile's today, not the device's UTC day`() =
        runTest(dispatcher) {
            // 04:30 UTC on the 15th is already the 15th in Kolkata (TIM-001).
            val state = viewModel().uiState.first()

            assertEquals("2026-08-15", state.odometerDate)
            assertEquals("2026-08-15", state.serviceDate)
        }

    @Test
    fun `rupees typed become paise, once`() =
        runTest(dispatcher) {
            val model = viewModel()
            model.onEvent(VehiclesEvent.ServiceCostChanged("5000"))
            model.onEvent(VehiclesEvent.ServiceOdometerChanged("44000"))
            model.onEvent(VehiclesEvent.ServiceDateChanged("2026-08-02"))

            model.onEvent(VehiclesEvent.LogService(FakeVehicleRepository.VEHICLE_ID))

            assertEquals(
                listOf("service:vehicle:1:2026-08-02:44000:500000"),
                repository.writes,
            )
        }

    @Test
    fun `a reading is forwarded as typed, and its field clears`() =
        runTest(dispatcher) {
            val model = viewModel()
            model.onEvent(VehiclesEvent.OdometerKmChanged("44000"))

            model.onEvent(VehiclesEvent.LogOdometer(FakeVehicleRepository.VEHICLE_ID))

            assertEquals(listOf("odometer:vehicle:1:2026-08-15:44000"), repository.writes)
            assertEquals(
                "a field that does not clear invites the same reading twice",
                "",
                model.uiState.first().odometerKm,
            )
        }

    @Test
    fun `anything but digits is ignored in a number field`() =
        runTest(dispatcher) {
            val model = viewModel()

            model.onEvent(VehiclesEvent.OdometerKmChanged("44,000 km"))

            assertEquals("44000", model.uiState.first().odometerKm)
        }

    @Test
    fun `an empty form does nothing at all`() =
        runTest(dispatcher) {
            val model = viewModel()

            model.onEvent(VehiclesEvent.AddVehicle)
            model.onEvent(VehiclesEvent.LogOdometer(FakeVehicleRepository.VEHICLE_ID))
            model.onEvent(VehiclesEvent.LogService(FakeVehicleRepository.VEHICLE_ID))

            assertTrue("an empty form must not write anything", repository.writes.isEmpty())
        }

    @Test
    fun `adding a vehicle forwards its class and clears the name`() =
        runTest(dispatcher) {
            val model = viewModel()
            model.onEvent(VehiclesEvent.LabelChanged("Activa"))
            model.onEvent(VehiclesEvent.ClassChanged(VehicleClass.TWO_WHEELER))

            model.onEvent(VehiclesEvent.AddVehicle)

            assertEquals(listOf("add:Activa:TWO_WHEELER"), repository.writes)
            assertEquals("", model.uiState.first().newLabel)
        }

    @Test
    fun `a renewal with no price recorded is forwarded without one`() =
        runTest(dispatcher) {
            val model = viewModel()

            model.onEvent(
                VehiclesEvent.SetRenewal(FakeVehicleRepository.VEHICLE_ID, RenewalItem.PUC, "2026-08-15", ""),
            )

            assertEquals(listOf("renewal:vehicle:1:PUC:2026-08-15:-"), repository.writes)
        }

    @Test
    fun `the log opens for one vehicle and closes again`() =
        runTest(dispatcher) {
            val model = viewModel()

            model.onEvent(VehiclesEvent.ToggleLog(FakeVehicleRepository.VEHICLE_ID))
            assertEquals(FakeVehicleRepository.VEHICLE_ID, model.uiState.first().openVehicleId)

            model.onEvent(VehiclesEvent.ToggleLog(FakeVehicleRepository.VEHICLE_ID))
            assertEquals(null, model.uiState.first().openVehicleId)
        }

    @Test
    fun `removing a vehicle is forwarded and nothing is deleted here`() =
        runTest(dispatcher) {
            val model = viewModel()

            model.onEvent(VehiclesEvent.Remove(FakeVehicleRepository.VEHICLE_ID))

            assertEquals(listOf("remove:vehicle:1"), repository.writes)
            assertFalse("the screen does not edit its own list", model.uiState.first().vehicles.isEmpty())
        }

    private fun viewModel() = VehiclesViewModel(repository, clock)
}
