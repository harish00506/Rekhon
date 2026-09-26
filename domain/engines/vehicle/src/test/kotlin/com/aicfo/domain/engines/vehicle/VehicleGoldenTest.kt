package com.aicfo.domain.engines.vehicle

import com.aicfo.core.common.Err
import com.aicfo.core.common.Ok
import com.aicfo.core.model.Money
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The golden-file gate for AI-VEH (issue 10.4; §21.5, and the acceptance criterion "golden-file
 * test").
 *
 * Why:  a prediction is a chain — readings become a slope, a slope becomes a date, a history
 *       becomes an index, an index becomes a price — and an error anywhere in it still produces a
 *       plausible-looking date. Five fixed households are compared line for line with an
 *       **independent** oracle (`golden/vehicle_oracle.py`) that re-implements §12 from the
 *       knowledge base in Python, using Python's own date and decimal arithmetic.
 * What: a scooter serviced on time, a hatchback driven hard enough for the distance to arrive
 *       first, an SUV whose owner pays far above the book, a brand-new EV with one reading, and a
 *       sedan that is overdue with lapsed paperwork.
 * Result: any change to the slope, the basis choice, the index or the alert windows fails here,
 *         naming the household it changed.
 * Changelog: 2026-09-26 — Created for issue 10.4.
 *
 * Regenerate with `python3 vehicle_oracle.py > vehicle.txt` from the golden directory — but only
 * after deciding that the *oracle* is right, never to make this test pass.
 */
class VehicleGoldenTest {
    private val golden: List<String> by lazy {
        (
            javaClass.classLoader.getResource("golden/vehicle.txt")?.readText()
                ?: throw AssertionError("golden/vehicle.txt is missing")
        ).lines().filter { it.isNotBlank() && !it.startsWith("#") }
    }

    @Test
    fun `every fixed household comes out exactly as the oracle says`() {
        assertEquals(golden, SCENARIOS.map(::line))
    }

    /** Result: one golden line for a scenario. Input: [scenario]. Output: [String]. */
    private fun line(scenario: Scenario): String {
        val prediction =
            when (val result = VehicleEngineFactory.create().predict(scenario.input)) {
                is Ok -> result.value
                is Err -> throw AssertionError("${scenario.name}: ${result.error}")
            }
        val next = prediction.nextService
        val alerts =
            prediction.alerts
                .joinToString("|") { "${it.kind}:${it.daysAway}" }
                .ifEmpty { "-" }
        return "${scenario.name} class=${scenario.kbClass} km/mo=${prediction.kmPerMonth} " +
            "due=${next.dueIsoDate}/${next.basis}@${next.dueOdometerKm ?: "-"} days=${next.daysAway} " +
            "cost=${prediction.predictedCost.low.minor}..${prediction.predictedCost.high.minor} " +
            "index=${prediction.personalIndexBps ?: "-"} alerts=$alerts"
    }

    /**
     * One household as both this test and the oracle describe it.
     * Input:  [name]; [kbClass] — the knowledge base's own class string, printed so a golden line
     *         names the row it used; [input].
     * Output: an immutable value.
     */
    private data class Scenario(
        val name: String,
        val kbClass: String,
        val input: VehicleInput,
    )

    private companion object {
        const val NOW = 1_786_000_000_000L
        const val TODAY = "2026-08-15"

        val SCENARIOS =
            listOf(
                Scenario(
                    "scooter_regular",
                    "2W",
                    vehicleInput(
                        VehicleClass.TWO_WHEELER,
                        readings =
                            listOf(
                                OdometerReading("2026-05-04", 12_000),
                                OdometerReading("2026-06-03", 12_400),
                                OdometerReading("2026-07-03", 12_800),
                                OdometerReading("2026-08-02", 13_200),
                            ),
                        services = listOf(ServiceRecord("2026-05-04", 12_000, Money(90_000L))),
                        renewals = listOf(RenewalRecord(RenewalItem.INSURANCE, "2026-03-01", Money(4_50_000L))),
                    ),
                ),
                Scenario(
                    "hatchback_hard",
                    "hatch",
                    vehicleInput(
                        VehicleClass.HATCHBACK,
                        readings =
                            listOf(
                                OdometerReading("2026-06-03", 40_000),
                                OdometerReading("2026-07-03", 42_000),
                                OdometerReading("2026-08-02", 44_000),
                            ),
                        services = listOf(ServiceRecord("2026-06-01", 40_000, Money(5_00_000L))),
                        renewals = listOf(RenewalRecord(RenewalItem.PUC, "2026-06-01", Money(12_000L))),
                    ),
                ),
                Scenario(
                    "suv_expensive",
                    "SUV",
                    vehicleInput(
                        VehicleClass.SUV,
                        readings =
                            listOf(
                                OdometerReading("2026-02-01", 20_000),
                                OdometerReading("2026-08-02", 26_000),
                            ),
                        services =
                            listOf(
                                ServiceRecord("2025-08-01", 14_000, Money(22_00_000L)),
                                ServiceRecord("2026-02-01", 20_000, Money(24_00_000L)),
                            ),
                        renewals = listOf(RenewalRecord(RenewalItem.INSURANCE, "2025-09-10", Money(35_00_000L))),
                    ),
                ),
                Scenario(
                    "ev_fresh",
                    "EV",
                    vehicleInput(
                        VehicleClass.ELECTRIC,
                        readings = listOf(OdometerReading("2026-08-10", 1_200)),
                        services = emptyList(),
                        renewals = emptyList(),
                    ),
                ),
                Scenario(
                    "sedan_overdue",
                    "sedan",
                    vehicleInput(
                        VehicleClass.SEDAN,
                        readings =
                            listOf(
                                OdometerReading("2025-01-10", 60_000),
                                OdometerReading("2025-03-11", 61_000),
                            ),
                        services = listOf(ServiceRecord("2025-01-10", 60_000, Money(7_50_000L))),
                        renewals =
                            listOf(
                                RenewalRecord(RenewalItem.INSURANCE, "2025-02-01", Money(28_00_000L)),
                                RenewalRecord(RenewalItem.PUC, "2025-06-01", Money(9_000L)),
                            ),
                    ),
                ),
            )

        private fun vehicleInput(
            vehicleClass: VehicleClass,
            readings: List<OdometerReading>,
            services: List<ServiceRecord>,
            renewals: List<RenewalRecord>,
        ) = VehicleInput(
            vehicle = Vehicle("vehicle:golden", "Golden", vehicleClass),
            readings = readings,
            services = services,
            renewals = renewals,
            todayIsoDate = TODAY,
            nowUtcMillis = NOW,
        )
    }
}
