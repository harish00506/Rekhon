package com.aicfo.domain.engines.vehicle

import com.aicfo.core.common.AppError
import com.aicfo.core.common.Err
import com.aicfo.core.common.Ok
import com.aicfo.core.common.Result
import com.aicfo.core.model.EngineProvenance
import com.aicfo.core.model.Money
import com.aicfo.core.model.RuleCitation
import java.time.LocalDate
import java.time.format.DateTimeParseException
import java.time.temporal.ChronoUnit

/**
 * AI-VEH 1.0 — the maintenance predictor (issue 10.4; SRS §12).
 *
 * Why:  §12 asks for "service due ~Aug 20, expect ₹3,000–4,500" from an odometer and a service
 *       history. Two limits decide the date — a distance and an age — and whichever arrives first
 *       is the answer; which one it was is as useful to the user as the date itself. The cost comes
 *       from the knowledge base, moved by what this household actually pays.
 * What: the robust slope, the due date and its basis, the adjusted cost range, the alerts, and the
 *       outflows the forecast should carry.
 * Result: a [VehiclePrediction] carrying the knowledge-base rows behind every figure (P-02).
 * Changelog: 2026-09-26 — Created for issue 10.4.
 *
 * `internal` per ARC-003; pure, with no clock and no I/O (P-08, ARC-002).
 */
@Suppress("TooManyFunctions") // one per step of the prediction; each is named and separately tested
internal class TheilSenVehicleEngine : VehicleEngine {
    override fun predict(input: VehicleInput): Result<VehiclePrediction, AppError> {
        val history = parse(input) ?: return Err(AppError.Validation(FIELD_DATE))
        return validate(input)?.let { Err(it) } ?: Ok(predictFrom(input, history))
    }

    /**
     * Turns the input's ISO strings into dates, or reports that one of them is not a date.
     * Why:    dates arrive as ISO strings (TIM-002) and a malformed one is a user-input error, not
     *         a programmer error — so it becomes a refusal rather than an exception crossing a
     *         layer boundary (§21.6).
     * Result: the parsed history, or `null` when any date is unparseable.
     * Input:  [input]. Output: `History?`.
     */
    private fun parse(input: VehicleInput): History? =
        try {
            History(
                today = LocalDate.parse(input.todayIsoDate),
                readings = input.readings.map { DatedReading(LocalDate.parse(it.isoDate), it.km) },
                services = input.services.map { DatedService(LocalDate.parse(it.isoDate), it.odometerKm, it.cost) },
                renewals =
                    input.renewals.map {
                        DatedRenewal(
                            it.item,
                            LocalDate.parse(it.lastDoneIsoDate),
                            it.lastCost,
                        )
                    },
            )
        } catch (_: DateTimeParseException) {
            null
        }

    /**
     * The inputs no prediction can be made from.
     * Why:    a negative odometer or a negative bill is a typo, and predicting from it would carry
     *         the typo into a forecast. A *backwards* reading is deliberately not here: odometers
     *         do not run backwards but fingers slip, and the median slope already absorbs one bad
     *         row — refusing the whole vehicle over it would punish the user for a fixable mistake.
     * Result: the first refusal by field, or `null`. Input: [input]. Output: `AppError.Validation?`.
     */
    private fun validate(input: VehicleInput): AppError.Validation? =
        when {
            input.readings.any { it.km < 0L } -> AppError.Validation(FIELD_ODOMETER)
            input.services.any { it.odometerKm < 0L } -> AppError.Validation(FIELD_ODOMETER)
            input.services.any { it.cost < Money.ZERO } -> AppError.Validation(FIELD_SERVICE_COST)
            input.renewals.any { it.lastCost != null && it.lastCost < Money.ZERO } ->
                AppError.Validation(FIELD_RENEWAL_COST)
            else -> null
        }

    /**
     * Result: the whole prediction. Input: [input]; [history] — the same data, dates parsed.
     * Output: [VehiclePrediction].
     */
    private fun predictFrom(
        input: VehicleInput,
        history: History,
    ): VehiclePrediction {
        val spec = input.knowledge.serviceFor(input.vehicle.vehicleClass)
        val slope =
            VehicleMath.kmPerMonth(
                readings = history.readings,
                windowStart = history.today.minusMonths(input.knowledge.prediction.slopeWindowMonths.toLong()),
                minimumReadings = input.knowledge.prediction.minReadingsForSlope,
            )
        val index =
            VehicleMath.personalIndexBps(
                history.services.map {
                    it.cost
                },
                spec.costMidpoint,
                input.knowledge.prediction,
            )
        val cost = costRange(spec, index)
        val next = nextService(history, spec, slope)
        val alerts = alerts(input, history, next, cost)
        return VehiclePrediction(
            vehicleId = input.vehicle.id,
            kmPerMonth = slope,
            latestOdometerKm = history.latest?.km,
            nextService = next,
            predictedCost = cost,
            personalIndexBps = index,
            alerts = alerts,
            scheduled = scheduled(input, history, next, cost),
            provenance = provenance(input, slope, index),
        )
    }

    /**
     * When the next service falls due, and on which limit.
     * Why:    the distance limit and the time limit are both real, and a prediction that used only
     *         one would be wrong for half the country — a commercial driver hits 10,000 km in four
     *         months, a weekend car takes three years.
     * Result: the earlier of the two, with the basis that decided it.
     * Input:  [history]; [spec] — the class's interval; [slope] — km per month, `0` when unknown.
     * Output: [NextService].
     */
    private fun nextService(
        history: History,
        spec: ServiceSpec,
        slope: Long,
    ): NextService {
        val since = history.lastService?.date ?: history.earliest?.date ?: history.today
        val baseOdometer = history.lastService?.odometerKm ?: history.earliest?.km
        val dueOdometer = baseOdometer?.plus(spec.intervalKm)
        val byTime = since.plusMonths(spec.intervalMonths.toLong())
        val byDistance =
            history.latest?.let { latest ->
                dueOdometer?.let { due ->
                    VehicleMath.daysToCover(due - latest.km, slope)?.let { latest.date.plusDays(it) }
                }
            }
        val onDistance = byDistance != null && byDistance.isBefore(byTime)
        val due = if (onDistance) requireNotNull(byDistance) else byTime
        return NextService(
            dueIsoDate = due.toString(),
            dueOdometerKm = dueOdometer,
            basis = if (onDistance) DueBasis.DISTANCE else DueBasis.TIME,
            daysAway = ChronoUnit.DAYS.between(history.today, due),
        )
    }

    /**
     * The knowledge base's range, moved by what this household pays.
     * Result: the range shown to the user. Input: [spec]; [indexBps] — `null` leaves the range as
     *         the book has it. Output: [CostRange].
     */
    private fun costRange(
        spec: ServiceSpec,
        indexBps: Int?,
    ): CostRange =
        if (indexBps == null) {
            CostRange(spec.costLow, spec.costHigh)
        } else {
            CostRange(spec.costLow.percentOf(indexBps), spec.costHigh.percentOf(indexBps))
        }

    /**
     * What is worth saying about this vehicle today, soonest first.
     * Why:    an alert that arrives on the day is useless and one that arrives three months early
     *         is noise, so both windows come from the knowledge base rather than from taste.
     * Result: the alerts, ordered by how close they are. Input: [input]; [history]; [next]; [cost].
     * Output: `List<VehicleAlert>`.
     */
    private fun alerts(
        input: VehicleInput,
        history: History,
        next: NextService,
        cost: CostRange,
    ): List<VehicleAlert> =
        (serviceAlert(input, history, next, cost) + history.renewals.mapNotNull { renewalAlert(input, history, it) })
            .sortedWith(compareBy({ it.daysAway }, { it.kind.name }))

    /**
     * Result: the service alert, or nothing when the service is neither close nor past.
     * Input:  [input]; [history]; [next]; [cost]. Output: a list of zero or one alert.
     *
     * The distance test matters most when there is no slope at all: one reading 300 km short of a
     * service produces a date a year away and an odometer that says now.
     */
    private fun serviceAlert(
        input: VehicleInput,
        history: History,
        next: NextService,
        cost: CostRange,
    ): List<VehicleAlert> {
        val alertSpec = input.knowledge.alerts
        val kmAway = next.dueOdometerKm?.let { due -> history.latest?.let { due - it.km } }
        val kind =
            when {
                next.daysAway < 0L -> VehicleAlertKind.SERVICE_OVERDUE
                next.daysAway <= alertSpec.serviceDueDays -> VehicleAlertKind.SERVICE_DUE
                kmAway != null && kmAway <= alertSpec.serviceDueKm -> VehicleAlertKind.SERVICE_DUE
                else -> null
            } ?: return emptyList()
        return listOf(
            VehicleAlert(
                kind = kind,
                dueIsoDate = next.dueIsoDate,
                daysAway = next.daysAway,
                amount = midpoint(cost),
                citation = VehicleCitations.ALERTS,
            ),
        )
    }

    /**
     * Result: a renewal alert when the paperwork is close or lapsed, else `null`.
     * Input:  [input]; [history]; [renewal]. Output: `VehicleAlert?`.
     */
    private fun renewalAlert(
        input: VehicleInput,
        history: History,
        renewal: DatedRenewal,
    ): VehicleAlert? {
        val cadence = input.knowledge.renewals[renewal.item] ?: return null
        val due = renewal.lastDone.plusMonths(cadence.cadenceMonths.toLong())
        val daysAway = ChronoUnit.DAYS.between(history.today, due)
        val window = input.knowledge.alerts.renewalReminderDays.maxOrNull() ?: 0L
        val kind =
            when {
                daysAway < 0L -> VehicleAlertKind.RENEWAL_EXPIRED
                daysAway <= window -> VehicleAlertKind.RENEWAL_DUE
                else -> return null
            }
        return VehicleAlert(kind, due.toString(), daysAway, renewal.lastCost, VehicleCitations.RENEWAL)
    }

    /**
     * What the forecast should carry (§12 into 9.2's horizon).
     * Why:    a cost the app knows is coming and does not forecast is exactly the crunch day the
     *         forecast exists to warn about. An overdue item is dated **today** rather than in the
     *         past, because a forecast cannot spend money yesterday.
     * Result: the outflows, earliest first. A renewal with no recorded price is left out — the
     *         knowledge base has no premium and inventing one would be a fabricated figure (P-03).
     * Input:  [input] — for the knowledge base's cadences; [history]; [next]; [cost].
     * Output: `List<PredictedOutflow>`.
     */
    private fun scheduled(
        input: VehicleInput,
        history: History,
        next: NextService,
        cost: CostRange,
    ): List<PredictedOutflow> {
        val service =
            PredictedOutflow(
                isoDate = notBeforeToday(LocalDate.parse(next.dueIsoDate), history.today),
                amount = midpoint(cost),
                label = PredictedOutflowLabel.SERVICE,
                citation = VehicleCitations.SERVICE,
            )
        val renewals =
            history.renewals.mapNotNull { renewal ->
                val cadence = input.knowledge.renewals[renewal.item] ?: return@mapNotNull null
                renewal.lastCost?.let { paid ->
                    PredictedOutflow(
                        isoDate =
                            notBeforeToday(
                                renewal.lastDone.plusMonths(cadence.cadenceMonths.toLong()),
                                history.today,
                            ),
                        amount = paid,
                        label = labelOf(renewal.item),
                        citation = VehicleCitations.RENEWAL,
                    )
                }
            }
        return (listOf(service) + renewals).sortedWith(compareBy({ it.isoDate }, { it.label }))
    }

    /** Result: the outflow label for a renewal item. Input: [item]. Output: [PredictedOutflowLabel]. */
    private fun labelOf(item: RenewalItem): PredictedOutflowLabel =
        when (item) {
            RenewalItem.INSURANCE -> PredictedOutflowLabel.INSURANCE
            RenewalItem.PUC -> PredictedOutflowLabel.PUC
        }

    /**
     * Result: how sure the engine is, in basis points (AI-ARC-003). Input: [input]; [slope];
     *         [index]. Output: [EngineProvenance].
     *
     * Confidence is not a feeling: it is what is missing. No slope costs a quarter, no price
     * history costs a quarter, and the window it read is named so a reader can judge for themselves.
     */
    private fun provenance(
        input: VehicleInput,
        slope: Long,
        index: Int?,
    ): EngineProvenance =
        EngineProvenance(
            engineId = ENGINE_ID,
            engineVersion = ENGINE_VERSION,
            computedAtUtcMillis = input.nowUtcMillis,
            evidence =
                listOf(
                    RuleCitation(VehicleCitations.SERVICE, input.knowledge.version),
                    RuleCitation(VehicleCitations.PREDICT, PREDICT_VERSION),
                    RuleCitation(VehicleCitations.ALERTS, ALERTS_VERSION),
                ),
            inputWindow = "${input.knowledge.prediction.slopeWindowMonths}m",
            confidenceBps =
                FULL_CONFIDENCE_BPS - (if (slope > 0L) 0 else CONFIDENCE_PENALTY_BPS) -
                    (if (index != null) 0 else CONFIDENCE_PENALTY_BPS),
        )

    /** Result: the middle of a range, which is what a single forecast line needs. */
    private fun midpoint(cost: CostRange): Money = Money((cost.low.minor + cost.high.minor) / 2)

    /** Result: [date], or today when it has already passed. Input: [date]; [today]. Output: ISO. */
    private fun notBeforeToday(
        date: LocalDate,
        today: LocalDate,
    ): String = (if (date.isBefore(today)) today else date).toString()

    /** The input with its dates parsed, and the three views of it every step needs. */
    private data class History(
        val today: LocalDate,
        val readings: List<DatedReading>,
        val services: List<DatedService>,
        val renewals: List<DatedRenewal>,
    ) {
        /** The newest reading, or `null` when the user has recorded none. */
        val latest: DatedReading? = readings.maxByOrNull { it.date }

        /** The oldest reading — where the clock starts when there is no service history. */
        val earliest: DatedReading? = readings.minByOrNull { it.date }

        /** The most recent service: the clock every interval is measured from. */
        val lastService: DatedService? = services.maxByOrNull { it.date }
    }

    /** A service with its date parsed. */
    private data class DatedService(
        val date: LocalDate,
        val odometerKm: Long,
        val cost: Money,
    )

    /** A renewal with its date parsed. */
    private data class DatedRenewal(
        val item: RenewalItem,
        val lastDone: LocalDate,
        val lastCost: Money?,
    )

    private companion object {
        const val ENGINE_ID = "AI-VEH"
        const val ENGINE_VERSION = "1.0"
        const val PREDICT_VERSION = "1.0"
        const val ALERTS_VERSION = "1.0"
        const val FIELD_DATE = "vehicle.date"
        const val FIELD_ODOMETER = "vehicle.odometer"
        const val FIELD_SERVICE_COST = "vehicle.serviceCost"
        const val FIELD_RENEWAL_COST = "vehicle.renewalCost"
        const val FULL_CONFIDENCE_BPS = 10_000
        const val CONFIDENCE_PENALTY_BPS = 2_500
    }
}
