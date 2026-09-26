package com.aicfo.domain.engines.vehicle

import com.aicfo.core.common.AppError
import com.aicfo.core.common.Err
import com.aicfo.core.common.Ok
import com.aicfo.core.common.Result
import com.aicfo.core.model.Money
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import kotlin.random.Random

/**
 * AI-VEH's promises, over many generated vehicles (issue 10.4; §21.5, P-08).
 *
 * Why:  the example tests pin particular scooters and particular hatchbacks. These pin what must
 *       hold for **every** vehicle: that a service is never predicted later than its own time
 *       limit, that a distance-based date is only ever claimed when there is a distance to base it
 *       on, that the alert's day count and the date it names agree, and that nothing the forecast
 *       is handed sits in the past. Each of those, broken, produces a prediction that still looks
 *       reasonable on screen.
 * What: six properties, 300 seeded cases each.
 * Result: a broken promise names the case that broke it.
 * Changelog: 2026-09-26 — Created for issue 10.4.
 */
class VehiclePropertyTest {
    private val engine = VehicleEngineFactory.create()

    @Test
    fun `a service is never predicted after its own time limit`() {
        // The date is the earlier of two limits, so it can be sooner than the calendar interval but
        // never later. Later would mean the distance limit had somehow overruled the time one.
        repeat(CASES) { case ->
            val input = vehicle(Random(case))
            val prediction = engine.predict(input).expectOk()
            val spec = VehicleKnowledge.BUNDLED.serviceFor(input.vehicle.vehicleClass)
            val since =
                input.services.maxByOrNull { it.isoDate }?.isoDate
                    ?: input.readings.minByOrNull { it.isoDate }?.isoDate
                    ?: input.todayIsoDate
            val limit = LocalDate.parse(since).plusMonths(spec.intervalMonths.toLong())

            assertTrue(
                "case $case: due ${prediction.nextService.dueIsoDate} is after the time limit $limit",
                !LocalDate.parse(prediction.nextService.dueIsoDate).isAfter(limit),
            )
        }
    }

    @Test
    fun `a distance basis is only claimed when there is a distance to base it on`() {
        repeat(CASES) { case ->
            val prediction = engine.predict(vehicle(Random(case))).expectOk()

            if (prediction.nextService.basis == DueBasis.DISTANCE) {
                assertTrue("case $case: distance basis with no slope", prediction.kmPerMonth > 0L)
                assertTrue("case $case: distance basis with no odometer", prediction.latestOdometerKm != null)
                assertTrue("case $case: distance basis with no target", prediction.nextService.dueOdometerKm != null)
            }
        }
    }

    @Test
    fun `every alert's day count agrees with the date it names`() {
        // The number and the date are shown side by side; if they can disagree, one of them is a lie.
        repeat(CASES) { case ->
            val input = vehicle(Random(case))
            val today = LocalDate.parse(input.todayIsoDate)

            engine.predict(input).expectOk().alerts.forEach { alert ->
                assertEquals(
                    "case $case: ${alert.kind}",
                    ChronoUnit.DAYS.between(today, LocalDate.parse(alert.dueIsoDate)),
                    alert.daysAway,
                )
            }
        }
    }

    @Test
    fun `nothing handed to the forecast is dated in the past or worth nothing`() {
        repeat(CASES) { case ->
            val input = vehicle(Random(case))

            engine.predict(input).expectOk().scheduled.forEach { outflow ->
                assertTrue(
                    "case $case: ${outflow.label} on ${outflow.isoDate}",
                    outflow.isoDate >= input.todayIsoDate,
                )
                assertTrue("case $case: ${outflow.label} costs nothing", outflow.amount > Money.ZERO)
            }
        }
    }

    @Test
    fun `paying more for services never predicts a cheaper one`() {
        // The personal index exists to move the book range towards what this household pays. If a
        // dearer history could produce a cheaper prediction, the index would be worse than nothing.
        repeat(CASES) { case ->
            val random = Random(case)
            val cheaper = vehicle(random)
            val dearer = cheaper.copy(services = cheaper.services.map { it.copy(cost = it.cost + Money(1_00_000L)) })

            assertTrue(
                "case $case",
                engine.predict(dearer).expectOk().predictedCost.high >=
                    engine.predict(cheaper).expectOk().predictedCost.high,
            )
        }
    }

    @Test
    fun `the same vehicle predicts the same way every time`() {
        repeat(CASES) { case ->
            val input = vehicle(Random(case))

            assertEquals("case $case", engine.predict(input).expectOk(), engine.predict(input).expectOk())
        }
    }

    // --- generator ---------------------------------------------------------------------------------

    /**
     * One plausible vehicle: a class, up to six readings over a year and a half, up to three
     * services, and sometimes paperwork.
     * Result: an input the engine must cope with. Input: [random] — seeded. Output: [VehicleInput].
     */
    private fun vehicle(random: Random): VehicleInput {
        val start = LocalDate.parse("2025-01-01").plusDays(random.nextLong(0, 200))
        val kmPerMonth = random.nextLong(0, 4_000)
        var odometer = random.nextLong(0, 90_000)
        var date = start
        val readings =
            (0 until random.nextInt(0, 7)).map {
                date = date.plusDays(random.nextLong(5, 90))
                odometer += kmPerMonth * random.nextLong(0, 3)
                OdometerReading(date.toString(), odometer)
            }
        val services =
            (0 until random.nextInt(0, 4)).map { index ->
                ServiceRecord(
                    isoDate = start.plusMonths(index * 6L).toString(),
                    odometerKm = random.nextLong(0, 90_000),
                    cost = Money(random.nextLong(50_000, 30_00_000)),
                )
            }
        return VehicleInput(
            vehicle =
                Vehicle(
                    id = "vehicle:$kmPerMonth",
                    label = "Generated",
                    vehicleClass = VehicleClass.entries[random.nextInt(VehicleClass.entries.size)],
                ),
            readings = readings,
            services = services,
            renewals = renewals(random, start),
            todayIsoDate = TODAY,
            nowUtcMillis = NOW,
        )
    }

    /**
     * Result: nought to two renewal rows, half of them with a price recorded.
     * Input:  [random] — seeded; [start] — the vehicle's first date. Output: the rows.
     */
    private fun renewals(
        random: Random,
        start: LocalDate,
    ): List<RenewalRecord> =
        RenewalItem.entries.filter { random.nextBoolean() }.map { item ->
            RenewalRecord(
                item = item,
                lastDoneIsoDate = start.plusDays(random.nextLong(0, 300)).toString(),
                lastCost = if (random.nextBoolean()) Money(random.nextLong(5_000, 40_00_000)) else null,
            )
        }

    private fun <T> Result<T, AppError>.expectOk(): T =
        when (this) {
            is Ok -> value
            is Err -> throw AssertionError("expected Ok, got $error")
        }

    private companion object {
        const val CASES = 300
        const val TODAY = "2026-08-15"
        const val NOW = 1_786_000_000_000L
    }
}
