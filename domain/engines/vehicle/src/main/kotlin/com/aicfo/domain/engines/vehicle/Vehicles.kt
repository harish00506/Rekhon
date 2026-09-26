package com.aicfo.domain.engines.vehicle

import com.aicfo.core.common.AppError
import com.aicfo.core.common.Result
import com.aicfo.core.model.EngineProvenance
import com.aicfo.core.model.Money

/**
 * AI-VEH — when the next service falls due, and what it will cost (issue 10.4; SRS §12).
 *
 * Why:  a car or a scooter is the second-largest thing most Indian households own and the one whose
 *       costs arrive as surprises: a service that was always coming, an insurance renewal nobody
 *       diarised, a set of tyres. Every one of those is predictable from two things the household
 *       already has — the odometer and what it paid last time — and predicting them is what turns a
 *       surprise into a line in the ninety-day forecast (§12, and 9.2's forecast).
 * What: one engine. It takes a vehicle, its odometer readings, its service history and its renewal
 *       dates, and returns the distance it covers each month, the date the next service is due and
 *       on what basis, the cost to expect as a range, the alerts worth raising, and the outflows the
 *       forecast should carry.
 * Result: dates and rupees a household can plan around, each carrying the knowledge-base row that
 *         produced it (P-02). It predicts and never books anything (P-07).
 * Changelog: 2026-09-26 — Created for issue 10.4.
 *
 * Pure (P-08): `today` and `nowUtcMillis` are inputs, so the same vehicle predicts the same way for
 * ever. No clock, no I/O, no Android (ARC-002).
 */
interface VehicleEngine {
    /**
     * Predicts one vehicle's next service, its cost, and what is worth saying about it.
     * Why:    the whole engine in one call, because every output depends on the same slope: the
     *         due date needs it, the alert needs the due date, and the forecast needs the alert's
     *         cost. Splitting them would mean computing the slope three times and inviting three
     *         answers.
     * Result: `Ok(VehiclePrediction)`; `Err(AppError.Validation)` naming the field for an input no
     *         prediction can be made from — an unknown class, a reading before the one before it,
     *         or a negative cost.
     * Input:  [input] — the vehicle and everything recorded about it.
     * Output: `Result<VehiclePrediction, AppError>`.
     */
    fun predict(input: VehicleInput): Result<VehiclePrediction, AppError>
}

/**
 * Builds the engine (ARC-003 — the implementation stays `internal`).
 * Result: an [VehicleEngine]. Input: none. Output: the engine.
 * Changelog: 2026-09-26 — Created for issue 10.4.
 */
object VehicleEngineFactory {
    /** Result: §12's maintenance predictor. Input: none. Output: [VehicleEngine]. */
    fun create(): VehicleEngine = TheilSenVehicleEngine()
}

/**
 * A vehicle as the engine sees it.
 * Why:    the class is what selects every interval and cost range in the knowledge base, so it is
 *         the one field a prediction cannot be made without.
 * Input:  [id] — the account or row this vehicle is; [label] — what the user calls it, for the
 *         alert text the screen writes; [vehicleClass] — the KB class.
 * Output: an immutable value.
 * Changelog: 2026-09-26 — Created for issue 10.4.
 */
data class Vehicle(
    val id: String,
    val label: String,
    val vehicleClass: VehicleClass,
)

/**
 * The knowledge base's vehicle classes (`vehicle_classes[].class`).
 * Why:    a typed class cannot be misspelt into a silent miss the way the KB's string could, and
 *         the compiler then forces every new class to be handled everywhere.
 * Changelog: 2026-09-26 — Created for issue 10.4 from vehicle-maintenance-kb.json v1.1.
 */
enum class VehicleClass {
    /** Two-wheeler: 3,000 km or 3 months. */
    TWO_WHEELER,

    /** Hatchback: 10,000 km or 12 months. */
    HATCHBACK,

    /** Sedan: 10,000 km or 12 months, dearer parts. */
    SEDAN,

    /** SUV: 10,000 km or 12 months, dearest. */
    SUV,

    /** Electric: 15,000 km or 12 months, and no oil to change. */
    ELECTRIC,
}

/**
 * One odometer reading.
 * Input:  [isoDate] — ISO `yyyy-MM-dd`, the day it was read (TIM-002); [km] — whole kilometres, as
 *         the dial shows them.
 * Output: an immutable value.
 * Changelog: 2026-09-26 — Created for issue 10.4.
 */
data class OdometerReading(
    val isoDate: String,
    val km: Long,
)

/**
 * One service the household actually paid for.
 * Why:    it is both the clock the next interval is measured from **and** the evidence for what a
 *         service costs this household, which is usually not the middle of a national range.
 * Input:  [isoDate]; [odometerKm] — the reading at the time; [cost] — what was paid (MNY-001).
 * Output: an immutable value.
 * Changelog: 2026-09-26 — Created for issue 10.4.
 */
data class ServiceRecord(
    val isoDate: String,
    val odometerKm: Long,
    val cost: Money,
)

/**
 * The paperwork that expires (`renewals[]` in the KB).
 * Changelog: 2026-09-26 — Created for issue 10.4.
 */
enum class RenewalItem {
    /** Motor insurance: twelve months. */
    INSURANCE,

    /** Pollution-under-control certificate: six months. */
    PUC,
}

/**
 * When a renewal was last done, and what it cost.
 * Why:    the knowledge base has no price for insurance or a PUC test, and it should not — a
 *         premium depends on the vehicle, the city and the claim history, and a national average
 *         would be an invented figure in a household's forecast (P-03). So the only honest number
 *         is the one this household paid last time: with it, the renewal joins the forecast; with
 *         [lastCost] null, it raises its date alert and nothing more.
 * Input:  [item]; [lastDoneIsoDate] — the day the current policy or certificate started;
 *         [lastCost] — what was paid for it, or `null` when the user has not recorded it.
 * Output: an immutable value.
 * Changelog: 2026-09-26 — Created for issue 10.4.
 */
data class RenewalRecord(
    val item: RenewalItem,
    val lastDoneIsoDate: String,
    val lastCost: Money? = null,
)

/**
 * Everything AI-VEH reads.
 * Why:    a prediction is only as honest as the history behind it, so the engine takes the history
 *         rather than a summary of it and derives every figure itself.
 * Input:  [vehicle]; [readings] — odometer readings in any order, deduplicated by date by the
 *         caller; [services] — what has been paid for, in any order; [renewals] — at most one row
 *         per item; [todayIsoDate] — the profile's today (TIM-001, injected, never read here);
 *         [nowUtcMillis] — stamped into provenance; [knowledge] — the KB mirror, overridable in
 *         tests only.
 * Output: an immutable value.
 * Changelog: 2026-09-26 — Created for issue 10.4.
 */
data class VehicleInput(
    val vehicle: Vehicle,
    val readings: List<OdometerReading>,
    val services: List<ServiceRecord>,
    val renewals: List<RenewalRecord>,
    val todayIsoDate: String,
    val nowUtcMillis: Long,
    val knowledge: VehicleKnowledge = VehicleKnowledge.BUNDLED,
)

/**
 * What the engine concluded.
 * Input:  [vehicleId]; [kmPerMonth] — the robust slope, `0` when there are too few readings to say;
 *         [latestOdometerKm] — the newest reading, or `null` when there is none; [nextService];
 *         [predictedCost] — the KB range moved by this household's own prices; [personalIndexBps] —
 *         what that move was, `null` when there is not enough history to compute one; [alerts] —
 *         ordered, soonest first; [scheduled] — what the forecast should carry (§12 into 9.2);
 *         [provenance].
 * Output: an immutable value.
 * Changelog: 2026-09-26 — Created for issue 10.4.
 */
data class VehiclePrediction(
    val vehicleId: String,
    val kmPerMonth: Long,
    val latestOdometerKm: Long?,
    val nextService: NextService,
    val predictedCost: CostRange,
    val personalIndexBps: Int?,
    val alerts: List<VehicleAlert>,
    val scheduled: List<PredictedOutflow>,
    val provenance: EngineProvenance,
)

/**
 * When the next service falls due, and why then.
 * Why:    a service is due at a distance **or** at an age, whichever comes first, and which of the
 *         two it was is the difference between "you have driven it hard" and "it has been sitting
 *         a year". The screen says which, so the number is not a bare date.
 * Input:  [dueIsoDate] — the earlier of the two; [dueOdometerKm] — the reading it is due at, or
 *         `null` when no distance estimate exists; [basis] — which of the two decided it;
 *         [daysAway] — from `todayIsoDate`, negative when it is already overdue.
 * Output: an immutable value.
 * Changelog: 2026-09-26 — Created for issue 10.4.
 */
data class NextService(
    val dueIsoDate: String,
    val dueOdometerKm: Long?,
    val basis: DueBasis,
    val daysAway: Long,
)

/**
 * Which limit the service date came from.
 * Changelog: 2026-09-26 — Created for issue 10.4.
 */
enum class DueBasis {
    /** The distance interval arrived first — this vehicle is driven a lot. */
    DISTANCE,

    /** The time interval arrived first, or there was no usable slope to measure distance with. */
    TIME,
}

/**
 * A cost as a range, never a single number.
 * Why:    §12's own wording is "expect ₹3,000–4,500". A single figure would be precision the app
 *         does not have, and P-02 prefers an honest range to a confident point.
 * Input:  [low]; [high] — inclusive, `high >= low` (MNY-001 paise).
 * Output: an immutable value.
 * Changelog: 2026-09-26 — Created for issue 10.4.
 */
data class CostRange(
    val low: Money,
    val high: Money,
) {
    init {
        require(high >= low) { "a cost range must not run backwards: $low..$high" }
    }
}

/**
 * Something worth telling the user about this vehicle.
 * Input:  [kind]; [dueIsoDate] — what the alert is about; [daysAway] — negative when overdue;
 *         [amount] — the cost to expect where there is one, else `null`; [citation] — the KB row or
 *         rule that raised it (P-02).
 * Output: an immutable value.
 * Changelog: 2026-09-26 — Created for issue 10.4.
 */
data class VehicleAlert(
    val kind: VehicleAlertKind,
    val dueIsoDate: String,
    val daysAway: Long,
    val amount: Money?,
    val citation: String,
)

/**
 * The kinds of alert §12 asks for.
 * Why:    the mileage-drop alert the KB also describes is **not** here: it needs litres per fill-up,
 *         and nothing in the app records a fuel volume yet. Naming it as absent is better than an
 *         enum entry that can never be produced (ADR-0052).
 * Changelog: 2026-09-26 — Created for issue 10.4.
 */
enum class VehicleAlertKind {
    /** The service is within the KB's lead time, by days or by distance. */
    SERVICE_DUE,

    /** The service date has passed. */
    SERVICE_OVERDUE,

    /** Insurance or the PUC certificate expires soon. */
    RENEWAL_DUE,

    /** It has already expired — for insurance, that is also illegal to drive on. */
    RENEWAL_EXPIRED,
}

/**
 * One predicted cost, dated, for the forecast to carry (§12 into 9.2's horizon).
 * Why:    the forecast's job is to show the worst day ahead. A service that everyone knows is
 *         coming, and that nobody put in the forecast, is exactly the kind of day it exists to
 *         warn about.
 * Input:  [isoDate]; [amount] — positive paise, the **midpoint** of the range, because a forecast
 *         line needs one number and the low end would flatter it; [label] — a key the screen turns
 *         into words (§21.6, never a sentence from an engine); [citation].
 * Output: an immutable value.
 * Changelog: 2026-09-26 — Created for issue 10.4.
 */
data class PredictedOutflow(
    val isoDate: String,
    val amount: Money,
    val label: PredictedOutflowLabel,
    val citation: String,
)

/**
 * What a predicted outflow is for. A key, not a string: §21.6 keeps user-visible words in
 * `strings.xml`, and an engine that returned "Service due" would have written one.
 * Changelog: 2026-09-26 — Created for issue 10.4.
 */
enum class PredictedOutflowLabel {
    /** The next periodic service. */
    SERVICE,

    /** The insurance premium. */
    INSURANCE,

    /** The PUC test fee. */
    PUC,
}
