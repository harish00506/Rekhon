package com.aicfo.domain.engines.appliance

import com.aicfo.core.model.Money

/**
 * The appliance knowledge base, as typed values the engine can read (issue 13.2; §12, §6, ADR-0070).
 *
 * Why:  `ai/knowledge/appliance-maintenance-kb.json` is the source of truth for every interval,
 *       price range and power figure, and CLAUDE.md §6 says those numbers live in `ai/` rather than
 *       in an engine. But an engine here is pure Kotlin with no file access (ARC-002), so it reads a
 *       mirror — and `ApplianceKbDriftTest` fails the build the moment the mirror and the file
 *       disagree, which is the only thing that makes a mirror safe. The same arrangement AI-VEH
 *       uses, for the same reason.
 * What: the service cadence and seasonal anchor per class, the cost ranges, the warranty lengths,
 *       the consumables with their own ranges, the power draw, and the prediction and alert
 *       parameters.
 * Result: the engine cites `APP-*` rows by name in its evidence, and a threshold is changed by
 *         editing the JSON and this mirror together.
 * Changelog: 2026-10-03 — Created for issue 13.2 from appliance-maintenance-kb.json v1.0.
 *
 * Input:  [version] — the KB revision this copy was taken from; [classes] — one row per class;
 *         [prediction]; [alerts].
 * Output: an immutable value; [BUNDLED] is the one every caller should use.
 */
data class ApplianceKnowledge(
    val version: String,
    val classes: Map<ApplianceClass, ApplianceSpec>,
    val prediction: AppliancePredictionSpec,
    val alerts: ApplianceAlertSpec,
) {
    /**
     * Result: the specification for a class. Input: [applianceClass]. Output: [ApplianceSpec].
     *
     * Every class in the enum is in the map — the drift test asserts it — so an absent row would be
     * a mirror bug rather than a user input error, which is why this errors rather than returning
     * null for a caller to handle.
     */
    fun specFor(applianceClass: ApplianceClass): ApplianceSpec =
        classes[applianceClass] ?: error("no KB row for $applianceClass — the mirror is incomplete")

    companion object {
        /**
         * The bundled knowledge base, mirroring `appliance-maintenance-kb.json` v1.0.
         * Result: the values the app ships with. Input: none. Output: [ApplianceKnowledge].
         */
        val BUNDLED =
            ApplianceKnowledge(
                version = "1.0",
                classes =
                    mapOf(
                        ApplianceClass.AC to
                            ApplianceSpec(
                                intervalMonths = 12,
                                seasonalMonth = 3,
                                costLow = Money(60_000L),
                                costHigh = Money(1_20_000L),
                                warrantyMonths = 12,
                                ratedWatts = 1_500,
                                minutesPerDay = 300,
                                consumables =
                                    listOf(
                                        ConsumableSpec("air_filter", 6, Money(20_000L), Money(50_000L)),
                                        ConsumableSpec("gas_refill", 36, Money(2_00_000L), Money(3_50_000L)),
                                    ),
                            ),
                        ApplianceClass.WATER_PURIFIER to
                            ApplianceSpec(
                                intervalMonths = 12,
                                seasonalMonth = null,
                                costLow = Money(50_000L),
                                costHigh = Money(90_000L),
                                warrantyMonths = 12,
                                ratedWatts = 60,
                                minutesPerDay = 60,
                                consumables =
                                    listOf(
                                        ConsumableSpec("filter_cartridge", 6, Money(1_20_000L), Money(2_50_000L)),
                                        ConsumableSpec("uv_lamp", 12, Money(60_000L), Money(1_20_000L)),
                                    ),
                            ),
                        ApplianceClass.REFRIGERATOR to
                            ApplianceSpec(
                                intervalMonths = 24,
                                seasonalMonth = null,
                                costLow = Money(50_000L),
                                costHigh = Money(1_00_000L),
                                warrantyMonths = 12,
                                ratedWatts = 200,
                                minutesPerDay = 480,
                                consumables = emptyList(),
                            ),
                        ApplianceClass.WASHING_MACHINE to
                            ApplianceSpec(
                                intervalMonths = 12,
                                seasonalMonth = null,
                                costLow = Money(40_000L),
                                costHigh = Money(90_000L),
                                warrantyMonths = 24,
                                ratedWatts = 500,
                                minutesPerDay = 60,
                                consumables = emptyList(),
                            ),
                        ApplianceClass.GEYSER to
                            ApplianceSpec(
                                intervalMonths = 12,
                                seasonalMonth = 10,
                                costLow = Money(30_000L),
                                costHigh = Money(70_000L),
                                warrantyMonths = 24,
                                ratedWatts = 2_000,
                                minutesPerDay = 40,
                                consumables =
                                    listOf(ConsumableSpec("anode_rod", 24, Money(50_000L), Money(90_000L))),
                            ),
                    ),
                prediction = AppliancePredictionSpec(),
                alerts = ApplianceAlertSpec(),
            )
    }
}

/**
 * One class's rules: how often, when, what it costs, how long it is covered, and what it draws.
 * Input:  [intervalMonths] — `service.interval_months`; [seasonalMonth] — `service.seasonal_month`,
 *   1–12, or `null` when the service is a rolling cadence; [costLow]/[costHigh] —
 *   `service.cost_range_minor`, already paise (MNY-001); [warrantyMonths]; [ratedWatts] and
 *   [minutesPerDay] — `power.*`; [consumables].
 * Output: an immutable value.
 * Changelog: 2026-10-03 — Created for issue 13.2.
 */
data class ApplianceSpec(
    val intervalMonths: Int,
    val seasonalMonth: Int?,
    val costLow: Money,
    val costHigh: Money,
    val warrantyMonths: Int,
    val ratedWatts: Int,
    val minutesPerDay: Int,
    val consumables: List<ConsumableSpec>,
) {
    init {
        require(intervalMonths > 0) { "a service interval must be positive, was $intervalMonths" }
        require(seasonalMonth == null || seasonalMonth in 1..MONTHS_IN_YEAR) {
            "a seasonal month must be 1..12, was $seasonalMonth"
        }
        require(costHigh >= costLow) { "a cost range must not run backwards: $costLow..$costHigh" }
        require(warrantyMonths >= 0) { "a warranty cannot be negative, was $warrantyMonths" }
        require(ratedWatts > 0) { "an appliance must draw something, was $ratedWatts W" }
        require(minutesPerDay in 0..MINUTES_IN_DAY) {
            "minutes per day must be 0..1440, was $minutesPerDay"
        }
    }

    private companion object {
        const val MONTHS_IN_YEAR = 12
        const val MINUTES_IN_DAY = 1_440
    }
}

/**
 * One consumable's rule.
 * Input:  [item] — the KB key, used verbatim in evidence so a citation is greppable;
 *   [intervalMonths]; [costLow]/[costHigh] — paise.
 * Output: an immutable value.
 * Changelog: 2026-10-03 — Created for issue 13.2.
 */
data class ConsumableSpec(
    val item: String,
    val intervalMonths: Int,
    val costLow: Money,
    val costHigh: Money,
) {
    init {
        require(item.isNotBlank()) { "a consumable needs a key" }
        require(intervalMonths > 0) { "a consumable interval must be positive, was $intervalMonths" }
        require(costHigh >= costLow) { "a cost range must not run backwards: $costLow..$costHigh" }
    }
}

/**
 * `APP-PREDICT` — the parameters every class shares.
 * Input:  [daysPerMonth] — the month a running cost is charged over, 30 by the KB, so the figure is
 *   comparable between months rather than swinging with February; [defaultTariffPaisePerKwh] — what
 *   a unit costs when the household has not said; [seasonalDueDayOfMonth] — which day of the
 *   seasonal month the service is dated to.
 * Output: an immutable value; the defaults are `appliance-maintenance-kb.json` v1.0.
 * Changelog: 2026-10-03 — Created for issue 13.2.
 */
data class AppliancePredictionSpec(
    val daysPerMonth: Int = 30,
    val defaultTariffPaisePerKwh: Int = 800,
    val seasonalDueDayOfMonth: Int = 1,
)

/**
 * `APP-ALERTS` — when to speak.
 * Input:  [serviceDueDays] — matches AI-VEH's 30 deliberately, so two engines do not disagree about
 *   what "due soon" means; [consumableDueDays]; [warrantyReminderDays] — descending.
 * Output: an immutable value; the defaults are `appliance-maintenance-kb.json` v1.0.
 * Changelog: 2026-10-03 — Created for issue 13.2.
 */
data class ApplianceAlertSpec(
    val serviceDueDays: Int = 30,
    val consumableDueDays: Int = 14,
    val warrantyReminderDays: List<Int> = listOf(OUTER_WARRANTY_LEAD_DAYS, INNER_WARRANTY_LEAD_DAYS),
) {
    private companion object {
        /** The first nudge, while the window to buy an extension is still open. */
        const val OUTER_WARRANTY_LEAD_DAYS = 60

        /** The last one, when it is nearly shut. */
        const val INNER_WARRANTY_LEAD_DAYS = 14
    }
}
