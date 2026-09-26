package com.aicfo.domain.engines.vehicle

import com.aicfo.core.model.Money

/**
 * The vehicle knowledge base, as typed values the engine can read (issue 10.4; §12, §6, ADR-0017).
 *
 * Why:  `ai/knowledge/vehicle-maintenance-kb.json` is the source of truth for every interval and
 *       every price range, and CLAUDE.md §6 says those numbers live in `ai/` rather than in an
 *       engine. But an engine in this project is pure Kotlin with no file access (ARC-002), so it
 *       reads a mirror — and `VehicleKbDriftTest` fails the build the moment the mirror and the
 *       file disagree, which is the only thing that makes a mirror safe.
 * What: the service interval and cost range per class, the renewal cadences, the prediction
 *       parameters and the alert thresholds.
 * Result: the engine cites `VEH-KB` rows by name in its evidence, and a threshold is changed by
 *         editing the JSON and this mirror together.
 * Changelog: 2026-09-26 — Created for issue 10.4 from vehicle-maintenance-kb.json v1.1.
 *
 * **What is deliberately not mirrored:** the `consumables` rows. The KB gives them an interval but
 * no cost range, so a predicted tyre change could carry a date and never a rupee — which cannot
 * enter a forecast and would be an invented figure if it did (P-03). ADR-0052 records it as
 * deferred, with what it needs.
 *
 * Input:  [version] — the KB revision this copy was taken from; [classes] — one row per vehicle
 *         class; [renewals]; [prediction]; [alerts].
 * Output: an immutable value; [BUNDLED] is the one every caller should use.
 */
data class VehicleKnowledge(
    val version: String,
    val classes: Map<VehicleClass, ServiceSpec>,
    val renewals: Map<RenewalItem, RenewalSpec>,
    val prediction: PredictionSpec,
    val alerts: AlertSpec,
) {
    /**
     * Result: the service specification for a class. Input: [vehicleClass]. Output: [ServiceSpec].
     *
     * Every class in the enum is in the map, so this cannot fail — the drift test asserts that, and
     * an absent row would be a mirror bug rather than a user input error.
     */
    fun serviceFor(vehicleClass: VehicleClass): ServiceSpec =
        classes[vehicleClass] ?: error("no KB row for $vehicleClass — the mirror is incomplete")

    companion object {
        /** `renewals[insurance].cadence_months` — a motor policy runs a year. */
        private const val INSURANCE_MONTHS = 12

        /** `renewals[PUC].cadence_months` — a pollution certificate runs six months. */
        private const val PUC_MONTHS = 6

        /**
         * The bundled knowledge base, mirroring `vehicle-maintenance-kb.json` v1.1.
         * Result: the values the app ships with. Input: none. Output: [VehicleKnowledge].
         */
        val BUNDLED =
            VehicleKnowledge(
                version = "1.1",
                classes =
                    mapOf(
                        VehicleClass.TWO_WHEELER to ServiceSpec(3_000, 3, Money(50_000L), Money(1_20_000L)),
                        VehicleClass.HATCHBACK to ServiceSpec(10_000, 12, Money(4_00_000L), Money(7_00_000L)),
                        VehicleClass.SEDAN to ServiceSpec(10_000, 12, Money(6_00_000L), Money(10_00_000L)),
                        VehicleClass.SUV to ServiceSpec(10_000, 12, Money(8_00_000L), Money(15_00_000L)),
                        VehicleClass.ELECTRIC to ServiceSpec(15_000, 12, Money(3_00_000L), Money(7_00_000L)),
                    ),
                renewals =
                    mapOf(
                        RenewalItem.INSURANCE to RenewalSpec(cadenceMonths = INSURANCE_MONTHS),
                        RenewalItem.PUC to RenewalSpec(cadenceMonths = PUC_MONTHS),
                    ),
                prediction = PredictionSpec(),
                alerts = AlertSpec(),
            )
    }
}

/**
 * One class's service rule: how far, how long, and what it costs.
 * Input:  [intervalKm] — `service.interval_km`; [intervalMonths] — `service.interval_months`;
 *         [costLow]/[costHigh] — `service.cost_range_minor`, already paise (MNY-001).
 * Output: an immutable value.
 * Changelog: 2026-09-26 — Created for issue 10.4.
 */
data class ServiceSpec(
    val intervalKm: Long,
    val intervalMonths: Int,
    val costLow: Money,
    val costHigh: Money,
) {
    init {
        require(intervalKm > 0) { "a service interval of $intervalKm km would fall due for ever" }
        require(intervalMonths > 0) { "a service interval of $intervalMonths months is not a period" }
        require(costHigh >= costLow) { "cost range runs backwards: $costLow..$costHigh" }
    }

    /**
     * Result: the middle of the range — the single figure a forecast line needs.
     * Why:    the low end would flatter the forecast and the high end would frighten it; the
     *         midpoint is the only defensible point estimate of a range this wide.
     * Input:  none. Output: [Money].
     */
    val costMidpoint: Money get() = Money((costLow.minor + costHigh.minor) / 2)
}

/**
 * How often a piece of paperwork expires (`renewals[]`).
 * Input:  [cadenceMonths]. Output: an immutable value.
 * Changelog: 2026-09-26 — Created for issue 10.4.
 */
data class RenewalSpec(
    val cadenceMonths: Int,
) {
    init {
        require(cadenceMonths > 0) { "a renewal cadence of $cadenceMonths months never comes round" }
    }
}

/**
 * The KB's `prediction` block: how a handful of readings becomes a rate (VEH-PREDICT v1.0).
 * Input:  [slopeWindowMonths] — how far back readings are used; [minReadingsForSlope];
 *         [minServicesForPersonalIndex]; [personalIndexFloorBps]/[personalIndexCeilingBps] — the
 *         clamp that stops one unusual bill rewriting every future estimate.
 * Output: an immutable value.
 * Changelog: 2026-09-26 — Created for issue 10.4.
 */
data class PredictionSpec(
    val slopeWindowMonths: Int = 12,
    val minReadingsForSlope: Int = 2,
    val minServicesForPersonalIndex: Int = 2,
    val personalIndexFloorBps: Int = 5_000,
    val personalIndexCeilingBps: Int = 20_000,
)

/**
 * The KB's `alerts` block: when the app is allowed to speak (VEH-ALERTS v1.0).
 * Input:  [serviceDueDays]; [serviceDueKm] — either one crossing raises the alert;
 *         [renewalReminderDays] — descending, the first one crossed wins.
 * Output: an immutable value.
 * Changelog: 2026-09-26 — Created for issue 10.4.
 */
data class AlertSpec(
    val serviceDueDays: Long = 30,
    val serviceDueKm: Long = 500,
    val renewalReminderDays: List<Long> = DEFAULT_REMINDER_DAYS,
) {
    private companion object {
        /** `alerts.renewal_reminder_days` — a month's warning, then a week's. */
        val DEFAULT_REMINDER_DAYS = listOf(30L, 7L)
    }
}

/** The knowledge-base rows AI-VEH cites, so evidence names a row rather than a file (P-02). */
internal object VehicleCitations {
    const val SERVICE = "VEH-KB.service"
    const val RENEWAL = "VEH-KB.renewals"
    const val PREDICT = "VEH-PREDICT"
    const val ALERTS = "VEH-ALERTS"
}
