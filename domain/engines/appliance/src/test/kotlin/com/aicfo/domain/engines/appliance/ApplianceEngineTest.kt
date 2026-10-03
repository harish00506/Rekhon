package com.aicfo.domain.engines.appliance

import com.aicfo.core.common.AppError
import com.aicfo.core.common.Err
import com.aicfo.core.common.Ok
import com.aicfo.core.model.Money
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [ApplianceEngine]'s decisions, one at a time (issue 13.2; §12, AC1).
 *
 * Why:  the golden file walks whole scenarios and is the stronger gate, but a scenario asserts a
 *       dozen things at once — so when it goes red it says *that* something moved, not *which*
 *       rule. These are the boundaries: the day an alert starts firing, the day cover ends, the
 *       inputs a prediction cannot honestly be made from. Each is one assertion about one rule, so
 *       a failure names it.
 * What: the four refusals, the alert windows at their exact edges, what the forecast is handed,
 *       the household's figures beating the book's, provenance, and determinism.
 * Result: every branch in `KbApplianceEngine` has a test that fails if it is removed.
 * Changelog: 2026-10-03 — Created for issue 13.2.
 */
class ApplianceEngineTest {
    private val engine = ApplianceEngineFactory.create()

    // --- the flag ----------------------------------------------------------------------------------

    /**
     * Input:  the shipped constant.
     * Output: asserts the appliance surface is off in v1 (AC2). The engine is pure and inert, so
     *         what the flag holds back is the decision to show appliances at all.
     */
    @Test
    fun `the appliance surface is off in v1`() {
        assertFalse(ApplianceMode.IS_ENABLED)
    }

    // --- the refusals ------------------------------------------------------------------------------

    /** Input: a purchase date that is not a date. Output: asserts a refusal naming the field. */
    @Test
    fun `an unparseable date is refused`() {
        val result = engine.predict(input(purchased = "15/06/2023"))
        assertEquals(Err(AppError.Validation("isoDate")), result)
    }

    /**
     * Input:  a service recorded before the appliance was bought.
     * Output: asserts a refusal. Accepting it would date the next service from a visit that cannot
     *         have happened, and the resulting date would look perfectly ordinary.
     */
    @Test
    fun `a service before the appliance existed is refused`() {
        val result =
            engine.predict(
                input(purchased = "2026-01-01", services = listOf(ApplianceServiceRecord("2025-12-31"))),
            )
        assertEquals(Err(AppError.Validation("services")), result)
    }

    /** Input: an appliance that draws nothing. Output: asserts a refusal, not a zero running cost. */
    @Test
    fun `a non-positive wattage is refused rather than costed as zero`() {
        val result = engine.predict(input(usage = ApplianceUsage(ratedWatts = 0)))
        assertEquals(Err(AppError.Validation("ratedWatts")), result)
    }

    /** Input: more minutes than a day holds. Output: asserts a refusal. */
    @Test
    fun `more than a day's running is refused`() {
        val result = engine.predict(input(usage = ApplianceUsage(minutesPerDay = 1_441)))
        assertEquals(Err(AppError.Validation("minutesPerDay")), result)
    }

    /** Input: free electricity. Output: asserts a refusal — a zero tariff is a missing figure (P-03). */
    @Test
    fun `a non-positive tariff is refused`() {
        val result = engine.predict(input(usage = ApplianceUsage(tariffPaisePerKwh = 0)))
        assertEquals(Err(AppError.Validation("tariffPaisePerKwh")), result)
    }

    // --- the alert windows, at their edges ---------------------------------------------------------

    /**
     * Input:  a washing machine whose service falls exactly `service_due_days` away, and a day
     *         further out.
     * Output: asserts the alert fires **on** the threshold and not one day before it. An off-by-one
     *         here is a day's silence or a day's noise, and neither shows up anywhere else.
     */
    @Test
    fun `the service alert fires on the threshold day and not before it`() {
        // WASHING_MACHINE: 12-month cadence, no seasonal anchor. Bought 2025-10-03, so due 2026-10-03.
        val onThreshold = predictionFor(purchased = "2025-10-03", today = "2026-09-03")
        val dayEarlier = predictionFor(purchased = "2025-10-03", today = "2026-09-02")

        assertEquals(30L, onThreshold.nextService.daysAway)
        assertTrue("30 days out must alert", onThreshold.alerts.any { it.kind == ApplianceAlertKind.SERVICE_DUE })
        assertEquals(31L, dayEarlier.nextService.daysAway)
        assertFalse("31 days out must not", dayEarlier.alerts.any { it.kind == ApplianceAlertKind.SERVICE_DUE })
    }

    /** Input: a service one day past. Output: asserts it turns from DUE into OVERDUE. */
    @Test
    fun `a service one day past is overdue, not due`() {
        val prediction = predictionFor(purchased = "2025-10-03", today = "2026-10-04")

        assertEquals(-1L, prediction.nextService.daysAway)
        assertTrue(prediction.alerts.any { it.kind == ApplianceAlertKind.SERVICE_OVERDUE })
        assertFalse(prediction.alerts.any { it.kind == ApplianceAlertKind.SERVICE_DUE })
    }

    /**
     * Input:  a warranty exactly 60 days out, and 61.
     * Output: asserts the first reminder fires on the KB's outer lead and not before. Sixty days is
     *         there so the window to buy an extension is still open; a reminder on the expiry day
     *         would be worthless.
     */
    @Test
    fun `the warranty reminder fires on the outer lead day and not before`() {
        // WASHING_MACHINE: 24-month warranty. Bought 2024-10-03, so expiry 2026-10-03.
        val onLead = predictionFor(purchased = "2024-10-03", today = "2026-08-04")
        val dayEarlier = predictionFor(purchased = "2024-10-03", today = "2026-08-03")

        assertEquals(60L, onLead.warranty.daysAway)
        assertTrue(onLead.alerts.any { it.kind == ApplianceAlertKind.WARRANTY_EXPIRING })
        assertEquals(61L, dayEarlier.warranty.daysAway)
        assertFalse(dayEarlier.alerts.any { it.kind == ApplianceAlertKind.WARRANTY_EXPIRING })
    }

    /**
     * Input:  a warranty on its last day, and one day later.
     * Output: asserts cover includes the expiry day — "valid to 3 October" means the 3rd is covered.
     */
    @Test
    fun `cover includes the expiry day itself`() {
        assertTrue(predictionFor(purchased = "2024-10-03", today = "2026-10-03").warranty.inWarranty)
        assertFalse(predictionFor(purchased = "2024-10-03", today = "2026-10-04").warranty.inWarranty)
    }

    /**
     * Input:  any prediction with a warranty alert.
     * Output: asserts it carries **no amount**. The KB holds no price for an extension, so a figure
     *         here would be invented rather than computed (P-03).
     */
    @Test
    fun `a warranty alert never carries an amount`() {
        val prediction = predictionFor(purchased = "2024-10-03", today = "2026-10-04")
        val warranty = prediction.alerts.first { it.kind == ApplianceAlertKind.WARRANTY_EXPIRED }

        assertNull("a warranty alert must not invent a price", warranty.amount)
    }

    // --- what the forecast is handed ---------------------------------------------------------------

    /**
     * Input:  an appliance whose service is long overdue.
     * Output: asserts the past-dated service is an alert but **not** a forecast line. The forecast's
     *         horizon starts today; dating a past cost into it would move money the household has
     *         either already spent or decided not to.
     */
    @Test
    fun `an overdue service is an alert but not a forecast line`() {
        val prediction = predictionFor(purchased = "2023-01-01", today = "2026-10-03")

        assertTrue(prediction.alerts.any { it.kind == ApplianceAlertKind.SERVICE_OVERDUE })
        assertTrue(
            "nothing dated before today may enter the forecast",
            prediction.scheduled.none { it.isoDate < "2026-10-03" },
        )
    }

    /**
     * Input:  an appliance with a service ahead of it.
     * Output: asserts the forecast line carries the **midpoint** of the range, not the low end.
     */
    @Test
    fun `a forecast line carries the midpoint of the range`() {
        val prediction = predictionFor(purchased = "2026-01-03", today = "2026-10-03")
        val service = prediction.scheduled.first { it.label == ApplianceOutflowLabel.SERVICE }

        // WASHING_MACHINE: 40 000..90 000 paise, midpoint 65 000.
        assertEquals(Money(65_000L), service.amount)
    }

    // --- the household's own figures ---------------------------------------------------------------

    /**
     * Input:  the same appliance with and without the household's own power figures.
     * Output: asserts the overrides are used, and that saying nothing falls back to the book.
     */
    @Test
    fun `the household's own figures beat the book's`() {
        val book = predictionFor(purchased = "2026-01-03", today = "2026-10-03")
        val theirs =
            predictionFor(
                purchased = "2026-01-03",
                today = "2026-10-03",
                usage = ApplianceUsage(ratedWatts = 1_000, minutesPerDay = 120, tariffPaisePerKwh = 900),
            )

        // Book: 500 W x 60 min x 30 d x 800 p / 60 000 = 12 000 paise.
        assertEquals(Money(12_000L), book.runningCostPerMonth)
        // Theirs: 1 000 W x 120 min x 30 d x 900 p / 60 000 = 54 000 paise.
        assertEquals(Money(54_000L), theirs.runningCostPerMonth)
    }

    // --- provenance and determinism ----------------------------------------------------------------

    /**
     * Input:  a prediction.
     * Output: asserts it names the engine, its version and the three KB rows it read (AI-ARC-003).
     */
    @Test
    fun `every prediction carries its engine and the rows it read`() {
        val provenance = predictionFor(purchased = "2026-01-03", today = "2026-10-03").provenance

        assertEquals("AI-APP", provenance.engineId)
        assertEquals("1.0", provenance.engineVersion)
        assertEquals(
            listOf("APP-KB.service", "APP-PREDICT", "APP-ALERTS"),
            provenance.evidence.map { it.ruleId },
        )
    }

    /**
     * Input:  the same appliance, with and without the household's own power figures.
     * Output: asserts confidence is lower when the running cost came from the book. The dates are
     *         exact either way; it is the rupees that are someone else's average.
     */
    @Test
    fun `confidence is lower when the running cost is the book's`() {
        val book = predictionFor(purchased = "2026-01-03", today = "2026-10-03").provenance
        val theirs =
            predictionFor(
                purchased = "2026-01-03",
                today = "2026-10-03",
                usage = ApplianceUsage(ratedWatts = 1_000, minutesPerDay = 120),
            ).provenance

        assertEquals(10_000, theirs.confidenceBps)
        assertEquals(7_000, book.confidenceBps)
    }

    /** Input: the same input twice. Output: asserts identical results (P-08). */
    @Test
    fun `the same appliance predicts identically every time`() {
        val input = input(purchased = "2026-01-03")
        assertEquals(engine.predict(input), engine.predict(input))
    }

    /**
     * Input:  a class the KB gives no consumables.
     * Output: asserts an empty list rather than a failure — "nothing to replace" is an answer.
     */
    @Test
    fun `a class with no consumables predicts an empty list, not an error`() {
        val prediction = predictionFor(purchased = "2026-01-03", today = "2026-10-03")

        assertTrue(prediction.consumablesDue.isEmpty())
    }

    // --- helpers -----------------------------------------------------------------------------------

    /** Result: the prediction for a washing machine — the class with no seasonal anchor and no consumables. */
    private fun predictionFor(
        purchased: String,
        today: String = "2026-10-03",
        usage: ApplianceUsage = ApplianceUsage(),
    ): AppliancePrediction = (engine.predict(input(purchased = purchased, today = today, usage = usage)) as Ok).value

    private fun input(
        purchased: String = "2026-01-03",
        today: String = "2026-10-03",
        services: List<ApplianceServiceRecord> = emptyList(),
        usage: ApplianceUsage = ApplianceUsage(),
    ) = ApplianceInput(
        appliance = Appliance("appliance:1", "Washer", ApplianceClass.WASHING_MACHINE, purchased),
        todayIsoDate = today,
        nowUtcMillis = 1_790_000_000_000L,
        services = services,
        usage = usage,
    )
}
