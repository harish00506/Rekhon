package com.aicfo.domain.engines.appliance

import com.aicfo.core.common.AppError
import com.aicfo.core.common.Err
import com.aicfo.core.common.Ok
import com.aicfo.core.common.Result
import com.aicfo.core.model.EngineProvenance
import com.aicfo.core.model.Money
import com.aicfo.core.model.RuleCitation

/**
 * AI-APP's implementation — the knowledge base applied to one appliance (issue 13.2; §12).
 *
 * Why:  `internal` per ARC-003, reached through [ApplianceEngineFactory]. Every number it produces
 *       comes from a KB row and every row it used is cited back (P-02, P-03): the engine decides
 *       *when* and *whether*, and the file decides *how much* and *how often*.
 * What: validates, then answers five questions in order — when is the service, what will it cost,
 *       where does the warranty stand, which consumables are due, and what does this thing cost to
 *       run — and turns the answers into alerts and forecast lines.
 * Result: an [AppliancePrediction], or an `Err` naming the field that made one impossible.
 * Changelog: 2026-10-03 — Created for issue 13.2.
 *
 * Deliberately holds no state and reads no clock: `todayIsoDate` and `nowUtcMillis` arrive on the
 * input, so the same appliance predicts identically for ever (P-08).
 */
internal class KbApplianceEngine(
    private val knowledge: ApplianceKnowledge = ApplianceKnowledge.BUNDLED,
) : ApplianceEngine {
    override fun predict(input: ApplianceInput): Result<AppliancePrediction, AppError> {
        validate(input)?.let { return Err(it) }

        val spec = knowledge.specFor(input.appliance.applianceClass)
        val today = input.todayIsoDate
        val service = nextService(input, spec)
        val cost = ApplianceCostRange(spec.costLow, spec.costHigh)
        val warranty = warranty(input, spec)
        val consumables = consumablesDue(input, spec)
        val alerts = alerts(service, cost, warranty, consumables)

        return Ok(
            AppliancePrediction(
                applianceId = input.appliance.id,
                nextService = service,
                predictedCost = cost,
                warranty = warranty,
                runningCostPerMonth = runningCost(input, spec),
                consumablesDue = consumables,
                alerts = alerts.sortedBy { it.daysAway },
                scheduled = scheduled(service, cost, consumables, today),
                provenance = provenance(input),
            ),
        )
    }

    /**
     * Rejects an input no prediction can honestly be made from.
     * Why:    these are the four states where a figure would be invented rather than computed — an
     *         unparseable date, a service dated before the appliance existed, a negative power
     *         figure, or a free tariff. Each is a caller bug, so each names its field (P-03).
     * Result: the [AppError] to return, or `null` when the input is usable.
     * Input:  [input]. Output: `AppError?`.
     */
    private fun validate(input: ApplianceInput): AppError? {
        val purchased = input.appliance.purchasedOnIsoDate
        val usage = input.usage
        val dates = listOf(purchased, input.todayIsoDate)

        // A list rather than a stack of guard clauses: the rules then read as a table of what is
        // rejected and why, and adding one is a row rather than another exit from the function.
        val broken =
            listOf<Pair<Boolean, String>>(
                dates.any { !ApplianceMath.isIsoDate(it) } to "isoDate",
                input.services.any { !ApplianceMath.isIsoDate(it.onIsoDate) } to "services",
                input.consumables.any { !ApplianceMath.isIsoDate(it.onIsoDate) } to "consumables",
                input.services.any { it.onIsoDate < purchased } to "services",
                input.consumables.any { it.onIsoDate < purchased } to "consumables",
                (usage.ratedWatts != null && usage.ratedWatts <= 0) to "ratedWatts",
                (usage.minutesPerDay != null && usage.minutesPerDay !in 0..MINUTES_IN_DAY) to "minutesPerDay",
                (usage.tariffPaisePerKwh != null && usage.tariffPaisePerKwh <= 0) to "tariffPaisePerKwh",
            ).firstOrNull { (isBroken, _) -> isBroken }

        return broken?.let { (_, field) -> AppError.Validation(field) }
    }

    /**
     * When the next service falls due, and on what basis.
     * Why:    three rules, in priority order, and which one fired is part of the answer (P-02). A
     *         seasonal class ignores the service history on purpose: an AC serviced in November is
     *         still due again before summer, because the point was never "twelve months since" but
     *         "before it gets hot".
     * Result: an [ApplianceDue].
     * Input:  [input]; [spec]. Output: [ApplianceDue].
     */
    private fun nextService(
        input: ApplianceInput,
        spec: ApplianceSpec,
    ): ApplianceDue {
        val today = input.todayIsoDate
        val lastService = input.services.maxByOrNull { it.onIsoDate }?.onIsoDate

        val (due, basis) =
            when {
                spec.seasonalMonth != null ->
                    ApplianceMath.nextSeasonal(
                        today,
                        spec.seasonalMonth,
                        knowledge.prediction.seasonalDueDayOfMonth,
                    ) to ApplianceDueBasis.SEASONAL

                lastService != null ->
                    ApplianceMath.plusMonths(lastService, spec.intervalMonths) to ApplianceDueBasis.CADENCE

                else ->
                    ApplianceMath.plusMonths(input.appliance.purchasedOnIsoDate, spec.intervalMonths) to
                        ApplianceDueBasis.SINCE_PURCHASE
            }
        return ApplianceDue(due, basis, ApplianceMath.daysBetween(today, due))
    }

    /**
     * Where the manufacturer's warranty stands.
     * Result: a [WarrantyStatus]; `inWarranty` is true on the expiry day itself, because cover that
     *         runs "to" a date includes it.
     * Input:  [input]; [spec]. Output: [WarrantyStatus].
     */
    private fun warranty(
        input: ApplianceInput,
        spec: ApplianceSpec,
    ): WarrantyStatus {
        val expires = ApplianceMath.plusMonths(input.appliance.purchasedOnIsoDate, spec.warrantyMonths)
        val daysAway = ApplianceMath.daysBetween(input.todayIsoDate, expires)
        return WarrantyStatus(expires, daysAway, inWarranty = daysAway >= 0L)
    }

    /**
     * Each consumable's next replacement, soonest first.
     * Why:    a consumable's clock starts at its own last replacement and falls back to the
     *         purchase date, because an appliance ships with its first filter in it.
     * Result: one [ConsumableDue] per KB row for the class.
     * Input:  [input]; [spec]. Output: the list, sorted by how soon.
     */
    private fun consumablesDue(
        input: ApplianceInput,
        spec: ApplianceSpec,
    ): List<ConsumableDue> =
        spec.consumables
            .map { consumable ->
                val from =
                    input.consumables
                        .filter { it.item == consumable.item }
                        .maxByOrNull { it.onIsoDate }
                        ?.onIsoDate
                        ?: input.appliance.purchasedOnIsoDate
                val due = ApplianceMath.plusMonths(from, consumable.intervalMonths)
                ConsumableDue(
                    item = consumable.item,
                    due =
                        ApplianceDue(
                            due,
                            ApplianceDueBasis.CADENCE,
                            ApplianceMath.daysBetween(input.todayIsoDate, due),
                        ),
                    cost = ApplianceCostRange(consumable.costLow, consumable.costHigh),
                )
            }.sortedBy { it.due.daysAway }

    /**
     * What this appliance costs to run for a month.
     * Result: paise, from the household's own figures where it gave them and the KB's otherwise.
     * Input:  [input]; [spec]. Output: [Money].
     */
    private fun runningCost(
        input: ApplianceInput,
        spec: ApplianceSpec,
    ): Money =
        ApplianceMath.runningCostPerMonth(
            watts = input.usage.ratedWatts ?: spec.ratedWatts,
            minutesPerDay = input.usage.minutesPerDay ?: spec.minutesPerDay,
            daysPerMonth = knowledge.prediction.daysPerMonth,
            tariffPaisePerKwh = input.usage.tariffPaisePerKwh ?: knowledge.prediction.defaultTariffPaisePerKwh,
        )

    /**
     * Everything worth saying, from what was already decided.
     * Why:    built from the predictions rather than recomputed, so an alert cannot disagree with
     *         the date on the screen beside it.
     * Result: the alerts, unsorted (the caller orders them).
     * Input:  [service]; [cost]; [warranty]; [consumables]. Output: the list.
     */
    private fun alerts(
        service: ApplianceDue,
        cost: ApplianceCostRange,
        warranty: WarrantyStatus,
        consumables: List<ConsumableDue>,
    ): List<ApplianceAlert> {
        val raised = mutableListOf<ApplianceAlert>()

        raised +=
            dueOrOverdue(
                due = service,
                amount = ApplianceMath.midpoint(cost),
                window = knowledge.alerts.serviceDueDays,
                overdue = ApplianceAlertKind.SERVICE_OVERDUE,
                soon = ApplianceAlertKind.SERVICE_DUE,
            )
        consumables.forEach { consumable ->
            raised +=
                dueOrOverdue(
                    due = consumable.due,
                    amount = ApplianceMath.midpoint(consumable.cost),
                    window = knowledge.alerts.consumableDueDays,
                    overdue = ApplianceAlertKind.CONSUMABLE_OVERDUE,
                    soon = ApplianceAlertKind.CONSUMABLE_DUE,
                )
        }
        raised += warrantyAlert(warranty)
        return raised
    }

    /**
     * Raises the warranty alert, if there is one.
     * Why:    **no amount, ever.** The KB holds no price for an extension, and §12 asks only that
     *         the user be told the window is closing. A figure here would be invented (P-03).
     * Result: a list of nought or one alert.
     * Input:  [warranty]. Output: the alerts.
     */
    private fun warrantyAlert(warranty: WarrantyStatus): List<ApplianceAlert> {
        val due = ApplianceDue(warranty.expiresOnIsoDate, ApplianceDueBasis.CADENCE, warranty.daysAway)
        return when {
            !warranty.inWarranty -> listOf(alert(ApplianceAlertKind.WARRANTY_EXPIRED, due, null, CITE_ALERTS))
            knowledge.alerts.warrantyReminderDays.any { warranty.daysAway <= it } ->
                listOf(alert(ApplianceAlertKind.WARRANTY_EXPIRING, due, null, CITE_ALERTS))
            else -> emptyList()
        }
    }

    /**
     * The dated costs a forecast should carry.
     * Why:    only what is still ahead. A service that was due last month is an alert, not a
     *         forecast line — the forecast's horizon starts today, and dating a past cost into it
     *         would move money the household has either already spent or decided not to.
     * Result: service and consumables, soonest first.
     * Input:  [service]; [cost]; [consumables]; [todayIsoDate]. Output: the list.
     */
    private fun scheduled(
        service: ApplianceDue,
        cost: ApplianceCostRange,
        consumables: List<ConsumableDue>,
        todayIsoDate: String,
    ): List<ApplianceOutflow> {
        val lines = mutableListOf<ApplianceOutflow>()
        if (service.dueIsoDate >= todayIsoDate) {
            lines +=
                ApplianceOutflow(
                    service.dueIsoDate,
                    ApplianceMath.midpoint(cost),
                    ApplianceOutflowLabel.SERVICE,
                    CITE_SERVICE,
                )
        }
        consumables
            .filter { it.due.dueIsoDate >= todayIsoDate }
            .forEach {
                lines +=
                    ApplianceOutflow(
                        it.due.dueIsoDate,
                        ApplianceMath.midpoint(it.cost),
                        ApplianceOutflowLabel.CONSUMABLE,
                        CITE_SERVICE,
                    )
            }
        return lines.sortedBy { it.isoDate }
    }

    /**
     * The provenance every result carries (AI-ARC-003/006).
     * Result: the engine's id and version, when it ran, and the three KB rows it read.
     * Input:  [input]. Output: [EngineProvenance].
     */
    private fun provenance(input: ApplianceInput): EngineProvenance =
        EngineProvenance(
            engineId = ENGINE_ID,
            engineVersion = ENGINE_VERSION,
            computedAtUtcMillis = input.nowUtcMillis,
            evidence =
                listOf(
                    RuleCitation(CITE_SERVICE, knowledge.version),
                    RuleCitation(CITE_PREDICT, knowledge.version),
                    RuleCitation(CITE_ALERTS, knowledge.version),
                ),
            // Confidence is lower when the household has never said what this appliance draws:
            // the dates are still exact, but the running cost is then the book's and not theirs.
            confidenceBps =
                if (input.usage.ratedWatts != null && input.usage.minutesPerDay != null) {
                    FULL_CONFIDENCE_BPS
                } else {
                    BOOK_FIGURES_CONFIDENCE_BPS
                },
        )

    private companion object {
        const val ENGINE_ID = "AI-APP"
        const val ENGINE_VERSION = "1.0"
        const val MINUTES_IN_DAY = 1_440
        const val FULL_CONFIDENCE_BPS = 10_000
        const val BOOK_FIGURES_CONFIDENCE_BPS = 7_000
    }
}

/** The knowledge-base rows AI-APP cites in its evidence (P-02). File-level so the helpers below
 * and the engine above read the same three strings. */
private const val CITE_SERVICE = "APP-KB.service"

/** `APP-PREDICT` — the shared prediction parameters. */
private const val CITE_PREDICT = "APP-PREDICT"

/** `APP-ALERTS` — when to speak. */
private const val CITE_ALERTS = "APP-ALERTS"

/**
 * Builds one alert from a due date.
 *
 * Why:  file-level rather than a member of [KbApplianceEngine], because it is a constructor call
 *       with a shorter name and not one of the engine's decisions — and because the engine is at
 *       detekt's eleven-function limit, which is a fair signal that a pure shorthand does not
 *       belong among the rules.
 * Result: an [ApplianceAlert].
 * Input:  [kind]; [due] — supplies the date and the days away; [amount] — the midpoint to expect,
 *   or `null` where naming one would invent a figure (P-03); [citation] — the KB row.
 * Output: [ApplianceAlert].
 * Changelog: 2026-10-03 — Created for issue 13.2.
 */
private fun alert(
    kind: ApplianceAlertKind,
    due: ApplianceDue,
    amount: Money?,
    citation: String,
) = ApplianceAlert(kind, due.dueIsoDate, due.daysAway, amount, citation)

/**
 * Raises the overdue alert, the due-soon alert, or neither.
 *
 * Why:  the service and every consumable follow the same two-step rule with different thresholds
 *       and different kinds, so it is written once — writing it twice is how the two drift into
 *       disagreeing about whether "today" counts as due. File-level for the same reason [alert] is:
 *       it depends on nothing but its arguments.
 * Result: a list of nought or one alert.
 * Input:  [due]; [amount] — the midpoint to expect; [window] — the KB's lead in days; [overdue] and
 *   [soon] — the kinds to raise. The citation is always the service row.
 * Output: the alerts.
 * Changelog: 2026-10-03 — Created for issue 13.2.
 */
private fun dueOrOverdue(
    due: ApplianceDue,
    amount: Money,
    window: Int,
    overdue: ApplianceAlertKind,
    soon: ApplianceAlertKind,
): List<ApplianceAlert> =
    when {
        due.daysAway < 0L -> listOf(alert(overdue, due, amount, CITE_SERVICE))
        due.daysAway <= window -> listOf(alert(soon, due, amount, CITE_SERVICE))
        else -> emptyList()
    }
