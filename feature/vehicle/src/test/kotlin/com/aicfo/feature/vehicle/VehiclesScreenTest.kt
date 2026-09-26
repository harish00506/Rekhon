package com.aicfo.feature.vehicle

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.aicfo.core.model.Money
import com.aicfo.domain.engines.vehicle.VehicleAlert
import com.aicfo.domain.engines.vehicle.VehicleAlertKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * What §12's screen must actually put in front of a person (issue 10.4; P-02, P-07, §21.6).
 *
 * Why:  the figures are the engine's and are tested there. What only this layer can get wrong is
 *       what reaches the eye: a due date shown without the reason it is that date, an overdue
 *       service worded as though it were still coming, a cost range that hides that it came from
 *       the household's own bills, or a screen of upcoming costs that reads as though the app had
 *       arranged them (P-07).
 * What: the prediction's sentences, the alert wording, the evidence line, the log, and the standing
 *       promise that nothing was booked.
 * Result: a screen a user can disagree with by pointing at a step.
 * Changelog: 2026-09-26 — Created for issue 10.4.
 */
@RunWith(RobolectricTestRunner::class)
class VehiclesScreenTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun `the due date says which limit decided it`() {
        render(VehiclesUiState(vehicles = listOf(FakeVehicleRepository.entry()), isLoaded = true))

        compose.onNodeWithText("Next service around 2026-10-31, at about 50000 km — you are driving it there.")
            .performScrollTo()
            .assertIsDisplayed()
    }

    @Test
    fun `an overdue service is not worded as one still coming`() {
        val overdue =
            FakeVehicleRepository.entry(
                alerts =
                    listOf(
                        VehicleAlert(
                            VehicleAlertKind.SERVICE_OVERDUE,
                            "2026-06-01",
                            -75,
                            Money(5_50_000L),
                            "VEH-ALERTS",
                        ),
                    ),
            )

        render(VehiclesUiState(vehicles = listOf(overdue), isLoaded = true))

        compose.onNodeWithText("Service overdue by 75 days.").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun `a cost drawn from the household's own bills says so`() {
        render(
            VehiclesUiState(
                vehicles = listOf(FakeVehicleRepository.entry(personalIndexBps = 20_000)),
                isLoaded = true,
            ),
        )

        compose.onNodeWithText("Expect ₹4,000.00 to ₹7,000.00, based on what you have paid before.")
            .performScrollTo()
            .assertIsDisplayed()
    }

    @Test
    fun `every figure names the engine and the rows behind it`() {
        render(VehiclesUiState(vehicles = listOf(FakeVehicleRepository.entry()), isLoaded = true))

        compose.onNodeWithText("From AI-VEH v1.0 · VEH-KB.service").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun `the screen says nothing was booked`() {
        render(VehiclesUiState(vehicles = listOf(FakeVehicleRepository.entry()), isLoaded = true))

        compose.onNodeWithText("Nothing has been booked or paid — these are predictions.")
            .performScrollTo()
            .assertIsDisplayed()
    }

    @Test
    fun `an empty list explains itself rather than looking broken`() {
        render(VehiclesUiState(vehicles = emptyList(), isLoaded = true))

        compose.onNodeWithText("No vehicles yet. Add one and the app starts predicting from the first reading.")
            .performScrollTo()
            .assertIsDisplayed()
    }

    @Test
    fun `opening the log asks for a reading, and saves the one that was typed`() {
        val events = mutableListOf<VehiclesEvent>()
        render(
            VehiclesUiState(
                vehicles = listOf(FakeVehicleRepository.entry()),
                isLoaded = true,
                openVehicleId = FakeVehicleRepository.VEHICLE_ID,
                odometerKm = "44000",
                odometerDate = "2026-08-15",
            ),
            onEvent = events::add,
        )

        compose.onNodeWithText("Odometer now (km)").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Save reading").performScrollTo().performClick()

        assertEquals(
            listOf(VehiclesEvent.LogOdometer(FakeVehicleRepository.VEHICLE_ID)),
            events,
        )
    }

    @Test
    fun `an empty log cannot be saved`() {
        // The guard is on the button, not only in the state holder: a disabled control is the
        // honest way to say "this is not ready", and it is what stops a blank reading being filed.
        val events = mutableListOf<VehiclesEvent>()
        render(
            VehiclesUiState(
                vehicles = listOf(FakeVehicleRepository.entry()),
                isLoaded = true,
                openVehicleId = FakeVehicleRepository.VEHICLE_ID,
                odometerKm = "",
                odometerDate = "",
            ),
            onEvent = events::add,
        )

        compose.onNodeWithText("Save reading").performScrollTo().performClick()

        assertTrue("an empty reading must not be filed: $events", events.isEmpty())
    }

    @Test
    fun `every vehicle class can be chosen, not just the ones that fit across a phone`() {
        // Issue 10.2's lesson: a chip clipped off the edge of the screen is unselectable however
        // well it tests, which is why these wrap rather than sitting in a row.
        val events = mutableListOf<VehiclesEvent>()
        render(VehiclesUiState(isLoaded = true), onEvent = events::add)

        listOf("Two-wheeler", "Hatchback", "Sedan", "SUV", "Electric").forEach { label ->
            compose.onNodeWithText(label).performScrollTo().performClick()
        }

        assertTrue("every class must be reachable: $events", events.size == 5)
    }

    private fun render(
        uiState: VehiclesUiState,
        onEvent: (VehiclesEvent) -> Unit = {},
    ) {
        compose.setContent {
            VehiclesContent(uiState = uiState, onEvent = onEvent, onDone = {})
        }
    }
}
