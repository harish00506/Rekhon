package com.aicfo.domain.engines.vehicle

import com.aicfo.core.common.AppError
import com.aicfo.core.common.Err
import com.aicfo.core.common.Ok
import com.aicfo.core.common.Result
import com.aicfo.core.model.Money
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What AI-VEH must get right (issue 10.4; SRS §12, P-02, P-03, P-08).
 *
 * Why:  every figure this engine produces is a claim about the future made from a handful of
 *       numbers the user typed on different days. The ways it can be quietly wrong are specific:
 *       a slope thrown by one mistyped odometer reading, a due date that ignores whichever limit
 *       actually arrives first, a cost range that pretends a national average is this household's
 *       price, and an alert that fires either too early to matter or too late to act on. These
 *       tests are one per way.
 * What: the slope, the due date and its basis, the personal cost index, the alerts, what the
 *       forecast is handed, the refusals, and determinism.
 * Result: a prediction a household can plan around.
 * Changelog: 2026-09-26 — Created for issue 10.4.
 */
class VehicleEngineTest {
    private val engine = VehicleEngineFactory.create()

    @Test
    fun `the distance covered each month is the median of the pairwise slopes`() {
        // Four readings thirty days apart, 1,000 km each time. A month here is thirty days — the
        // engine measures in days and scales, because two readings three weeks apart still have to
        // produce a rate (VEH-PREDICT).
        val honest = predict(readings = MONTHLY_1000).kmPerMonth

        assertEquals(1_000L, honest)
    }

    @Test
    fun `one mistyped reading does not become the estimate`() {
        // The third reading has a stray leading digit: 112,000 where 12,000 was meant. A mean of
        // the deltas would report about 1,000 km a month with a 100,000 km spike inside it; the
        // median of the pairwise slopes ignores it entirely. This is the whole reason for Theil-Sen.
        val withTypo =
            predict(
                readings =
                    listOf(
                        OdometerReading("2026-05-04", 10_000),
                        OdometerReading("2026-06-03", 11_000),
                        OdometerReading("2026-07-03", 112_000),
                        OdometerReading("2026-08-02", 13_000),
                    ),
            ).kmPerMonth

        assertEquals("the typo moved the estimate", 1_000L, withTypo)
    }

    @Test
    fun `a reading that goes backwards is tolerated rather than fatal`() {
        // Odometers do not run backwards, but fingers do slip. Refusing the whole prediction over
        // one bad row would punish the user for a typo the median already absorbs.
        val result =
            engine.predict(
                input(
                    readings =
                        listOf(
                            OdometerReading("2026-06-03", 44_000),
                            OdometerReading("2026-07-03", 40_000),
                            OdometerReading("2026-08-02", 45_000),
                        ),
                ),
            )

        assertTrue("a backwards reading was refused outright", result is Ok)
    }

    @Test
    fun `readings older than the knowledge base's window are not used`() {
        // VEH-PREDICT.slope_window_months is 12: a car driven hard three years ago and barely since
        // is not a car that is driven hard.
        val prediction =
            predict(
                readings =
                    listOf(
                        OdometerReading("2023-08-01", 10_000),
                        OdometerReading("2026-07-03", 51_000),
                        OdometerReading("2026-08-02", 51_300),
                    ),
            )

        assertEquals("only the two readings inside the window count", 300L, prediction.kmPerMonth)
    }

    @Test
    fun `with too few readings there is no slope, and the engine says so with a zero`() {
        val prediction = predict(readings = listOf(OdometerReading("2026-08-01", 51_000)))

        assertEquals(0L, prediction.kmPerMonth)
        assertEquals("and it falls back to the time interval", DueBasis.TIME, prediction.nextService.basis)
    }

    @Test
    fun `a car driven hard is due on distance before it is due on time`() {
        // A hatchback: 10,000 km or 12 months. At 2,000 km a month the distance arrives in five
        // months, long before the year is up.
        val prediction =
            predict(
                services = listOf(ServiceRecord("2026-06-01", 40_000, Money(5_00_000L))),
                readings =
                    listOf(
                        OdometerReading("2026-06-03", 40_000),
                        OdometerReading("2026-08-02", 44_000),
                    ),
            )

        // 2,000 km a month, 6,000 km still to run to the 50,000 km service: ninety days.
        assertEquals(DueBasis.DISTANCE, prediction.nextService.basis)
        assertEquals(50_000L, prediction.nextService.dueOdometerKm)
        assertEquals("2026-10-31", prediction.nextService.dueIsoDate)
    }

    @Test
    fun `a car that barely moves is due on time`() {
        val prediction =
            predict(
                services = listOf(ServiceRecord("2026-06-01", 40_000, Money(5_00_000L))),
                readings =
                    listOf(
                        OdometerReading("2026-06-03", 40_000),
                        OdometerReading("2026-08-02", 40_200),
                    ),
            )

        assertEquals(DueBasis.TIME, prediction.nextService.basis)
        assertEquals("2027-06-01", prediction.nextService.dueIsoDate)
        assertEquals("the odometer it is due at is still known", 50_000L, prediction.nextService.dueOdometerKm)
    }

    @Test
    fun `with no service history the clock starts at the first reading`() {
        val prediction =
            predict(
                services = emptyList(),
                readings =
                    listOf(
                        OdometerReading("2026-03-05", 0),
                        OdometerReading("2026-08-02", 1_000),
                    ),
            )

        assertEquals("2027-03-05", prediction.nextService.dueIsoDate)
    }

    @Test
    fun `the household's own prices move the range, and the engine shows by how much`() {
        // A hatchback's KB range is ₹4,000–7,000, midpoint ₹5,500. Two services at ₹11,000 make
        // this household twice the book price: 20,000 bps.
        val prediction =
            predict(
                services =
                    listOf(
                        ServiceRecord("2025-06-01", 30_000, Money(11_00_000L)),
                        ServiceRecord("2026-06-01", 40_000, Money(11_00_000L)),
                    ),
            )

        assertEquals(20_000, prediction.personalIndexBps)
        assertEquals(Money(8_00_000L), prediction.predictedCost.low)
        assertEquals(Money(14_00_000L), prediction.predictedCost.high)
    }

    @Test
    fun `one service is not a price history`() {
        // VEH-PREDICT.min_services_for_personal_index is 2. Below it the book range stands, and the
        // engine returns no index rather than a one-sample one.
        val prediction = predict(services = listOf(ServiceRecord("2026-06-01", 40_000, Money(20_00_000L))))

        assertNull(prediction.personalIndexBps)
        assertEquals(Money(4_00_000L), prediction.predictedCost.low)
        assertEquals(Money(7_00_000L), prediction.predictedCost.high)
    }

    @Test
    fun `one extraordinary bill cannot rewrite every future estimate`() {
        // A ₹60,000 clutch job is not what the next oil change costs. The index is clamped at the
        // KB's ceiling of 20,000 bps.
        val prediction =
            predict(
                services =
                    listOf(
                        ServiceRecord("2025-06-01", 30_000, Money(60_00_000L)),
                        ServiceRecord("2026-06-01", 40_000, Money(60_00_000L)),
                    ),
            )

        assertEquals(20_000, prediction.personalIndexBps)
    }

    @Test
    fun `a service inside the alert window is raised, with what it will cost`() {
        // VEH-ALERTS.service_due_days is 30. Due 2027-06-01 is far away; due in three weeks is not.
        val prediction =
            predict(
                todayIsoDate = "2027-05-15",
                services = listOf(ServiceRecord("2026-06-01", 40_000, Money(5_00_000L))),
                readings = listOf(OdometerReading("2026-06-03", 40_000)),
            )

        val alert = prediction.alerts.single { it.kind == VehicleAlertKind.SERVICE_DUE }
        assertEquals("2027-06-01", alert.dueIsoDate)
        assertEquals(17L, alert.daysAway)
        assertEquals(Money(5_50_000L), alert.amount)
    }

    @Test
    fun `a service the odometer has nearly reached is raised even when the date is far off`() {
        // VEH-ALERTS.service_due_km is 500: 49,700 against a 50,000 km service is 300 km away,
        // whatever the calendar says. One reading means no slope at all, so the date says June
        // next year — and the odometer still says now.
        val prediction =
            predict(
                todayIsoDate = "2026-08-02",
                services = listOf(ServiceRecord("2026-06-01", 40_000, Money(5_00_000L))),
                readings = listOf(OdometerReading("2026-08-02", 49_700)),
            )

        assertTrue(
            "the distance alert did not fire: ${prediction.alerts}",
            prediction.alerts.any { it.kind == VehicleAlertKind.SERVICE_DUE },
        )
    }

    @Test
    fun `a service that has come and gone is overdue, not due`() {
        val prediction =
            predict(
                todayIsoDate = "2027-08-01",
                services = listOf(ServiceRecord("2026-06-01", 40_000, Money(5_00_000L))),
                readings = listOf(OdometerReading("2026-06-03", 40_000)),
            )

        val alert = prediction.alerts.single { it.kind == VehicleAlertKind.SERVICE_OVERDUE }
        assertTrue("an overdue service is a negative number of days away", alert.daysAway < 0)
    }

    @Test
    fun `insurance and the PUC certificate are chased on their own cadences`() {
        // insurance is 12 months, PUC is 6 (VEH-KB.renewals), and the reminder window is 30 days.
        val prediction =
            predict(
                todayIsoDate = "2026-08-15",
                renewals =
                    listOf(
                        RenewalRecord(RenewalItem.INSURANCE, "2025-09-01", Money(12_00_000L)),
                        RenewalRecord(RenewalItem.PUC, "2026-08-01"),
                    ),
            )

        val renewal = prediction.alerts.single { it.kind == VehicleAlertKind.RENEWAL_DUE }
        assertEquals("2026-09-01", renewal.dueIsoDate)
        assertEquals("what it cost last year is the only honest estimate", Money(12_00_000L), renewal.amount)
    }

    @Test
    fun `a lapsed policy is called expired`() {
        val prediction =
            predict(
                todayIsoDate = "2026-10-01",
                renewals = listOf(RenewalRecord(RenewalItem.INSURANCE, "2025-09-01")),
            )

        assertEquals(
            VehicleAlertKind.RENEWAL_EXPIRED,
            prediction.alerts.single { it.kind == VehicleAlertKind.RENEWAL_EXPIRED }.kind,
        )
    }

    @Test
    fun `alerts arrive soonest first`() {
        val prediction =
            predict(
                todayIsoDate = "2026-08-15",
                services = listOf(ServiceRecord("2025-09-01", 40_000, Money(5_00_000L))),
                renewals = listOf(RenewalRecord(RenewalItem.PUC, "2026-02-20")),
                readings = listOf(OdometerReading("2025-09-01", 40_000)),
            )

        assertEquals("two alerts were expected: ${prediction.alerts}", 2, prediction.alerts.size)

        assertEquals(
            prediction.alerts.map { it.daysAway },
            prediction.alerts.map { it.daysAway }.sorted(),
        )
    }

    @Test
    fun `the forecast is handed the service and every renewal it has a price for`() {
        val prediction =
            predict(
                todayIsoDate = "2026-08-15",
                services = listOf(ServiceRecord("2026-06-01", 40_000, Money(5_00_000L))),
                readings = listOf(OdometerReading("2026-06-01", 40_000)),
                renewals =
                    listOf(
                        RenewalRecord(RenewalItem.INSURANCE, "2025-09-01", Money(12_00_000L)),
                        // No price recorded, so it can be chased but not forecast (P-03).
                        RenewalRecord(RenewalItem.PUC, "2026-08-01"),
                    ),
            )

        assertEquals(
            listOf(PredictedOutflowLabel.INSURANCE, PredictedOutflowLabel.SERVICE),
            prediction.scheduled.map { it.label },
        )
        assertEquals(
            "the service line carries the midpoint of the range",
            Money(5_50_000L),
            prediction.scheduled.single { it.label == PredictedOutflowLabel.SERVICE }.amount,
        )
    }

    @Test
    fun `an outflow that is already overdue is carried from today, not from the past`() {
        // A forecast cannot spend money yesterday. An overdue service is money about to go out.
        val prediction =
            predict(
                todayIsoDate = "2027-08-01",
                services = listOf(ServiceRecord("2026-06-01", 40_000, Money(5_00_000L))),
                readings = listOf(OdometerReading("2026-06-03", 40_000)),
            )

        assertEquals("2027-08-01", prediction.scheduled.single().isoDate)
    }

    @Test
    fun `every figure names the engine and the rows behind it`() {
        val provenance = predict().provenance

        assertEquals("AI-VEH", provenance.engineId)
        assertEquals("1.0", provenance.engineVersion)
        assertTrue(
            "the knowledge base is not cited: ${provenance.evidence}",
            provenance.evidence.any { it.ruleId == "VEH-KB.service" },
        )
    }

    @Test
    fun `impossible inputs are refused by field`() {
        assertEquals(
            AppError.Validation("vehicle.serviceCost"),
            error(input(services = listOf(ServiceRecord("2026-06-01", 40_000, Money(-1L))))),
        )
        assertEquals(
            AppError.Validation("vehicle.date"),
            error(input(readings = listOf(OdometerReading("not-a-date", 1)))),
        )
        assertEquals(
            AppError.Validation("vehicle.odometer"),
            error(input(readings = listOf(OdometerReading("2026-06-01", -1)))),
        )
    }

    @Test
    fun `a vehicle with nothing recorded at all is still answerable`() {
        // Someone who has just added a car and typed nothing else should get a date, not an error:
        // the KB's interval from today is the honest answer, on time, with no slope.
        val prediction = predict(readings = emptyList(), services = emptyList())

        assertEquals(0L, prediction.kmPerMonth)
        assertNull(prediction.latestOdometerKm)
        assertEquals(DueBasis.TIME, prediction.nextService.basis)
        assertEquals("2027-08-15", prediction.nextService.dueIsoDate)
    }

    @Test
    fun `the same vehicle predicts the same way twice`() {
        assertEquals(predict(), predict())
    }

    // --- fixtures ----------------------------------------------------------------------------------

    private fun input(
        readings: List<OdometerReading> = READINGS,
        services: List<ServiceRecord> = SERVICES,
        renewals: List<RenewalRecord> = emptyList(),
        todayIsoDate: String = TODAY,
    ) = VehicleInput(
        vehicle = Vehicle("vehicle:1", "Swift", VehicleClass.HATCHBACK),
        readings = readings,
        services = services,
        renewals = renewals,
        todayIsoDate = todayIsoDate,
        nowUtcMillis = NOW,
    )

    private fun predict(
        readings: List<OdometerReading> = READINGS,
        services: List<ServiceRecord> = SERVICES,
        renewals: List<RenewalRecord> = emptyList(),
        todayIsoDate: String = TODAY,
    ): VehiclePrediction = engine.predict(input(readings, services, renewals, todayIsoDate)).expectOk()

    private fun error(input: VehicleInput): AppError =
        when (val result = engine.predict(input)) {
            is Ok -> throw AssertionError("expected Err, got ${result.value.nextService}")
            is Err -> result.error
        }

    private fun <T> Result<T, AppError>.expectOk(): T =
        when (this) {
            is Ok -> value
            is Err -> throw AssertionError("expected Ok, got $error")
        }

    private companion object {
        const val TODAY = "2026-08-15"
        const val NOW = 1_786_000_000_000L

        val READINGS =
            listOf(
                OdometerReading("2026-06-03", 40_000),
                OdometerReading("2026-08-02", 41_000),
            )

        /** Four readings thirty days apart, a thousand kilometres each time. */
        val MONTHLY_1000 =
            listOf(
                OdometerReading("2026-05-04", 10_000),
                OdometerReading("2026-06-03", 11_000),
                OdometerReading("2026-07-03", 12_000),
                OdometerReading("2026-08-02", 13_000),
            )

        val SERVICES = listOf(ServiceRecord("2026-06-01", 40_000, Money(5_00_000L)))
    }
}
