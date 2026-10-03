package com.aicfo.domain.engines.appliance

import com.aicfo.core.common.AppError
import com.aicfo.core.common.Result
import com.aicfo.core.model.EngineProvenance
import com.aicfo.core.model.Money

/**
 * AI-APP — an appliance's next service, its consumables, its warranty and what it costs to run
 * (issue 13.2; SRS §12's closing clause).
 *
 * Why:  §12 ends with one line — "Appliances (Phase 4): same engine, different knowledge base (AC
 *       service pre-summer, water-purifier filters, extended-warranty expiry)." A household's
 *       appliances take money in three ways that are all predictable and all usually forgotten: the
 *       service that is always coming, the consumable that expires silently, and the electricity
 *       drawn every single day. The third is the one nobody budgets: a 2 kW geyser run forty
 *       minutes a day is a standing monthly cost that never appears as a bill of its own.
 * What: one engine, mirroring AI-VEH's shape. It takes an appliance, what has been done to it and
 *       today's date, and returns the next service and why then, the cost to expect as a range, the
 *       warranty's standing, each consumable's due date, the running cost per month, the alerts
 *       worth raising, and the outflows a forecast should carry.
 * Result: dates and rupees a household can plan around, each carrying the knowledge-base row that
 *         produced it (P-02). It predicts and never books anything (P-07).
 * Changelog: 2026-10-03 — Created for issue 13.2.
 *
 * **Why this is a sibling of AI-VEH and not the same class.** §12 says "same engine". The shape is
 * the same and is copied deliberately — KB-driven, pure, a cost *range* rather than a point, alerts
 * that cite the row that raised them. The arithmetic is not: a vehicle's prediction turns on a
 * robust slope through odometer readings, and an appliance has no odometer. Folding appliances into
 * `VehicleEngine` would mean an engine whose principal input is always empty and whose principal
 * algorithm never runs. ADR-0070 records the deviation and what would merge them.
 *
 * Pure (P-08): `todayIsoDate` and `nowUtcMillis` are inputs, so the same appliance predicts the
 * same way for ever. No clock, no I/O, no Android (ARC-002).
 */
interface ApplianceEngine {
    /**
     * Predicts one appliance's maintenance, warranty and running cost.
     * Why:    one call, because the outputs share inputs — the alert needs the due date, and the
     *         forecast line needs the alert's cost. Splitting them would compute the same dates
     *         more than once and invite more than one answer.
     * Result: `Ok(AppliancePrediction)`; `Err(AppError.Validation)` naming the field for an input no
     *         prediction can be made from — a malformed date, a service recorded before the
     *         appliance was bought, or a negative power figure.
     * Input:  [input] — the appliance and everything recorded about it.
     * Output: `Result<AppliancePrediction, AppError>`.
     */
    fun predict(input: ApplianceInput): Result<AppliancePrediction, AppError>
}

/**
 * Builds the engine (ARC-003 — the implementation stays `internal`).
 * Result: an [ApplianceEngine]. Input: none. Output: the engine.
 * Changelog: 2026-10-03 — Created for issue 13.2.
 */
object ApplianceEngineFactory {
    /** Result: §12's appliance predictor. Input: none. Output: [ApplianceEngine]. */
    fun create(): ApplianceEngine = KbApplianceEngine()
}

/**
 * Whether the appliance surface is on (issue 13.2; ADR-0070).
 *
 * Why:  Epic 13 is design-for work and the issue's AC2 says "feature-flagged". The engine itself is
 *       pure and inert — it computes only when called — so what the flag holds back is the decision
 *       to *show* appliances: no screen, no repository, and nothing injected into the forecast. The
 *       engine ships tested so that turning it on is a wiring job rather than a design job.
 * What: one constant.
 * Result: v1 behaves exactly as it did.
 * Changelog: 2026-10-03 — Created for issue 13.2.
 */
object ApplianceMode {
    /** False in v1. ADR-0070 §5 lists what must be true before it changes. */
    const val IS_ENABLED: Boolean = false
}

/**
 * The appliance classes the knowledge base has rows for.
 *
 * Why:  the class selects every interval, cost range and power figure, so it is the one field a
 *       prediction cannot be made without. A closed enum rather than a free string: an unknown
 *       class would have no KB row, and failing at the type rather than at a map lookup is the
 *       difference between a compile error and a runtime one.
 * Changelog: 2026-10-03 — Created for issue 13.2 from appliance-maintenance-kb.json v1.0.
 */
enum class ApplianceClass {
    /** §12's named example: serviced before summer, not on a rolling anniversary. */
    AC,

    /** §12's other named example — the filter is the cost, not the machine. */
    WATER_PURIFIER,

    /** Runs continuously, so its running cost dominates its maintenance cost. */
    REFRIGERATOR,

    /** Washing machine. */
    WASHING_MACHINE,

    /** A water heater — serviced before winter, the mirror of the AC. */
    GEYSER,
}

/**
 * An appliance as the engine sees it.
 * Input:  [id] — the row this appliance is; [label] — what the user calls it, for the screen's
 *   words; [applianceClass] — the KB class; [purchasedOnIsoDate] — ISO `yyyy-MM-dd` (TIM-002), the
 *   anchor for the warranty and for the first service when nothing has been serviced yet.
 * Output: an immutable value.
 * Changelog: 2026-10-03 — Created for issue 13.2.
 */
data class Appliance(
    val id: String,
    val label: String,
    val applianceClass: ApplianceClass,
    val purchasedOnIsoDate: String,
)

/**
 * What this household's own appliance does, where it differs from the book.
 *
 * Why:  the KB holds a typical figure for every class, and a typical figure is wrong for somebody.
 *       A 1.5-ton inverter AC run two hours a day and an old window unit run ten are the same KB
 *       row and nowhere near the same bill. Overrides are **separate from [Appliance]** so that the
 *       absence of one is visible: a `null` here means "we used the book", which is what the
 *       evidence line has to be able to say (P-02).
 * Input:  [ratedWatts] — the plate rating; [minutesPerDay] — how long it runs, in **minutes**,
 *   because a fraction of an hour would put a decimal into money arithmetic (MNY-001);
 *   [tariffPaisePerKwh] — what this household pays for a unit. Each `null` falls back to the KB.
 * Output: an immutable value.
 * Changelog: 2026-10-03 — Created for issue 13.2.
 */
data class ApplianceUsage(
    val ratedWatts: Int? = null,
    val minutesPerDay: Int? = null,
    val tariffPaisePerKwh: Int? = null,
)

/**
 * A service this appliance has actually had.
 * Input:  [onIsoDate] — ISO `yyyy-MM-dd`; [cost] — what was paid, or `null` when the user recorded
 *   the visit but not the bill.
 * Output: an immutable value.
 * Changelog: 2026-10-03 — Created for issue 13.2.
 */
data class ApplianceServiceRecord(
    val onIsoDate: String,
    val cost: Money? = null,
)

/**
 * A consumable this appliance has actually had replaced.
 * Input:  [item] — the KB's `consumables[].item` key; [onIsoDate] — ISO `yyyy-MM-dd`.
 * Output: an immutable value.
 * Changelog: 2026-10-03 — Created for issue 13.2.
 */
data class ConsumableRecord(
    val item: String,
    val onIsoDate: String,
)

/**
 * Everything the engine is given.
 * Input:  [appliance]; [services] — in any order; [consumables] — replacements, in any order;
 *   [usage] — this household's overrides; [todayIsoDate] — the date every "days away" is measured
 *   from, an input so the engine stays pure (TIM-001, P-08); [nowUtcMillis] — for provenance only.
 * Output: an immutable value.
 * Changelog: 2026-10-03 — Created for issue 13.2.
 */
data class ApplianceInput(
    val appliance: Appliance,
    val todayIsoDate: String,
    val nowUtcMillis: Long,
    val services: List<ApplianceServiceRecord> = emptyList(),
    val consumables: List<ConsumableRecord> = emptyList(),
    val usage: ApplianceUsage = ApplianceUsage(),
)

/**
 * What the engine decided about one appliance.
 * Input:  [applianceId]; [nextService]; [predictedCost] — the KB range, moved by what this
 *   household has actually paid; [warranty]; [runningCostPerMonth] — paise (MNY-001);
 *   [consumablesDue] — soonest first; [alerts] — soonest first; [scheduled] — what a forecast
 *   should carry; [provenance].
 * Output: an immutable value.
 * Changelog: 2026-10-03 — Created for issue 13.2.
 */
data class AppliancePrediction(
    val applianceId: String,
    val nextService: ApplianceDue,
    val predictedCost: ApplianceCostRange,
    val warranty: WarrantyStatus,
    val runningCostPerMonth: Money,
    val consumablesDue: List<ConsumableDue>,
    val alerts: List<ApplianceAlert>,
    val scheduled: List<ApplianceOutflow>,
    val provenance: EngineProvenance,
)

/**
 * When something falls due, and why then.
 * Input:  [dueIsoDate]; [basis] — which KB rule decided it; [daysAway] — from `todayIsoDate`,
 *   negative when already overdue.
 * Output: an immutable value.
 * Changelog: 2026-10-03 — Created for issue 13.2.
 */
data class ApplianceDue(
    val dueIsoDate: String,
    val basis: ApplianceDueBasis,
    val daysAway: Long,
)

/**
 * Which KB rule produced a due date.
 *
 * Why:  "due in March" and "due twelve months after the last one" are different promises, and the
 *       screen says which — a seasonal date does not move when the user services the unit early,
 *       and a cadence date does. Telling them apart is the difference between a date the user can
 *       argue with and one they cannot.
 * Changelog: 2026-10-03 — Created for issue 13.2.
 */
enum class ApplianceDueBasis {
    /** The class has a `seasonal_month`: the service is anchored to the calendar (AC before summer). */
    SEASONAL,

    /** `interval_months` after the last service. */
    CADENCE,

    /** Nothing has been serviced yet, so the cadence runs from the purchase date. */
    SINCE_PURCHASE,
}

/**
 * A cost as a range, never a single number.
 * Why:    §12's own wording is "expect ₹3,000–4,500". A single figure would be precision the app
 *         does not have, and P-02 prefers an honest range to a confident point. Deliberately this
 *         engine's own type rather than AI-VEH's: nothing consumes both yet, and ADR-0070 records
 *         unifying them as the moment something does.
 * Input:  [low]; [high] — inclusive, `high >= low` (MNY-001 paise).
 * Output: an immutable value.
 * Changelog: 2026-10-03 — Created for issue 13.2.
 */
data class ApplianceCostRange(
    val low: Money,
    val high: Money,
) {
    init {
        require(high >= low) { "a cost range must not run backwards: $low..$high" }
    }
}

/**
 * Where the manufacturer's warranty stands.
 * Why:    §12 names "extended-warranty expiry" specifically, and the reason is commercial: the
 *         window to buy an extension closes when the original does. A notice on the day it expires
 *         is worthless, which is why the KB's lead time is sixty days.
 * Input:  [expiresOnIsoDate]; [daysAway] — negative once expired; [inWarranty].
 * Output: an immutable value.
 * Changelog: 2026-10-03 — Created for issue 13.2.
 */
data class WarrantyStatus(
    val expiresOnIsoDate: String,
    val daysAway: Long,
    val inWarranty: Boolean,
)

/**
 * A consumable and when it next needs replacing.
 * Input:  [item] — the KB key; [due]; [cost] — the KB range for this item.
 * Output: an immutable value.
 * Changelog: 2026-10-03 — Created for issue 13.2.
 */
data class ConsumableDue(
    val item: String,
    val due: ApplianceDue,
    val cost: ApplianceCostRange,
)

/**
 * Something worth telling the user about this appliance.
 * Input:  [kind]; [dueIsoDate]; [daysAway] — negative when overdue; [amount] — the midpoint to
 *   expect where there is a cost, else `null`; [citation] — the KB row that raised it (P-02).
 * Output: an immutable value.
 * Changelog: 2026-10-03 — Created for issue 13.2.
 */
data class ApplianceAlert(
    val kind: ApplianceAlertKind,
    val dueIsoDate: String,
    val daysAway: Long,
    val amount: Money?,
    val citation: String,
)

/**
 * The kinds of alert §12's appliance clause asks for.
 *
 * Why:  there is no running-cost alert here, and that is deliberate. "This geyser costs ₹480 a
 *       month" is a fact, not an event — it is true every day, so an alert would fire for ever or
 *       need a threshold the KB does not have. It belongs on a screen, which is what
 *       [AppliancePrediction.runningCostPerMonth] is for.
 * Changelog: 2026-10-03 — Created for issue 13.2.
 */
enum class ApplianceAlertKind {
    /** The service is within `alerts.service_due_days`. */
    SERVICE_DUE,

    /** The service date has passed. */
    SERVICE_OVERDUE,

    /** A consumable is within `alerts.consumable_due_days`. */
    CONSUMABLE_DUE,

    /** A consumable is past its interval. */
    CONSUMABLE_OVERDUE,

    /** The warranty is within one of `alerts.warranty_reminder_days`. */
    WARRANTY_EXPIRING,

    /** The warranty has run out. */
    WARRANTY_EXPIRED,
}

/**
 * One predicted cost, dated, for a forecast to carry (§12 into 9.2's horizon).
 * Why:    a service everyone knows is coming, that nobody put in the forecast, is exactly the kind
 *         of day the forecast exists to warn about.
 * Input:  [isoDate]; [amount] — positive paise, the **midpoint** of the range, because a forecast
 *   line needs one number and the low end would flatter it; [label] — a key the screen turns into
 *   words (§21.6, never a sentence from an engine); [citation].
 * Output: an immutable value.
 * Changelog: 2026-10-03 — Created for issue 13.2.
 */
data class ApplianceOutflow(
    val isoDate: String,
    val amount: Money,
    val label: ApplianceOutflowLabel,
    val citation: String,
)

/**
 * What a predicted appliance outflow is for. A key, not a string (§21.6).
 *
 * The running cost is **not** here: it is a monthly standing cost the everyday-spending pool
 * already contains, and adding it as a dated outflow would count the same electricity twice
 * (the mistake ADR-0043 names for scheduled payments).
 * Changelog: 2026-10-03 — Created for issue 13.2.
 */
enum class ApplianceOutflowLabel {
    /** The next periodic service. */
    SERVICE,

    /** A filter, a lamp, an anode rod. */
    CONSUMABLE,
}
