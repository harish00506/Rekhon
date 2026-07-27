package com.aicfo.domain.engines.quicksetup

import com.aicfo.core.common.AppError
import com.aicfo.core.common.Err
import com.aicfo.core.common.Ok
import com.aicfo.core.common.Result
import com.aicfo.core.model.EngineProvenance
import com.aicfo.core.model.Money
import com.aicfo.core.model.RuleCitation
import java.time.LocalDate
import java.time.format.DateTimeParseException

/**
 * The production [QuickSetupEngine] (issue 2.3; FR-ONB-002, MNY-001, MNY-002, P-02, P-03, P-08).
 *
 * Why:  the whole engine turns on one judgement — **what to do when the numbers do not fit**. A
 *       user whose rent is 80% of their income cannot be given a 50/30/20 budget, and the tempting
 *       fix is to keep stretching the needs band until the budget balances. That would balance it
 *       by cancelling their savings, and would then report the situation as normal. Instead the
 *       flex stops at the rulebook's metro ceiling, the savings floor is never touched, and the
 *       envelope is left visibly short beside a hard-fail verdict. The budget tells the truth even
 *       when the truth is that it does not fit.
 * What: validate, normalise, split, size the emergency target, rate the obligations, and record
 *       which rules fired.
 * Result: the plan the onboarding summary renders and the repository persists.
 * Changelog: 2026-07-27 — Created for issue 2.3.
 *
 * `internal` per ARC-003 — constructed only by [QuickSetupEngineFactory].
 *
 * Every amount is `Long` paise and every rate an integer basis point: there is not a `Double` in
 * this file, and `Money.allocate` does the one division, so the envelopes total the income exactly
 * (MNY-001, MNY-002).
 */
internal class DefaultQuickSetupEngine : QuickSetupEngine {
    override fun plan(input: QuickSetupInput): Result<QuickSetupPlan, AppError> {
        validate(input)?.let { return Err(it) }
        // checkNotNull, not `!!`: validate() has already established that this parses, so a null
        // here is a programmer error — and §21.6 reserves crashes for exactly those.
        val periodStart =
            checkNotNull(parseIsoDate(input.periodStartIsoDate)) { "validate() accepted an unparseable period" }

        // A stored zero means "not answered" (SettingsStore maps it that way on read), so it is
        // normalised to null here rather than at three separate call sites below.
        val income = input.monthlyIncome.takeIfAnswered()
        val rent = input.rentOrEmi.takeIfAnswered()
        val savings = input.typicalSavings.takeIfAnswered()

        val envelopes = income?.let { splitIncome(it, rent, input.rules) }.orEmpty()
        val needs = envelopes.firstOrNull { it.nature == BudgetNature.NEED }?.amount
        val emergencyTarget = needs?.times(input.rules.clampedRunwayMonths)
        val obligationBps = obligationLoadBps(income, rent)

        return Ok(
            QuickSetupPlan(
                periodStartIsoDate = input.periodStartIsoDate,
                envelopes = envelopes,
                recurring = recurringSeeds(income, rent, savings, periodStart.plusMonths(1).toString()),
                emergencyFundTarget = emergencyTarget,
                emergencyRunwayMonths = input.rules.clampedRunwayMonths,
                obligationLoadBps = obligationBps,
                obligationVerdict = verdictFor(obligationBps, input.rules),
                provenance =
                    EngineProvenance(
                        engineId = ENGINE_ID,
                        engineVersion = ENGINE_VERSION,
                        computedAtUtcMillis = input.nowUtcMillis,
                        evidence = evidenceFor(envelopes, emergencyTarget, obligationBps, input.rules),
                    ),
            ),
        )
    }

    /**
     * Rejects input that cannot be what the user meant.
     * Why:    a negative income is not a refund — it is a parse or UI fault, and `Money.allocate`
     *         would happily distribute it into negative envelopes that render as budgets the user
     *         owes back. A malformed period is the same shape of fault, and it would become both a
     *         stored column and a due-date calculation (TIM-002). Naming the field lets the screen
     *         point at it (§21.6: a validation error carries a field name, never a message).
     * What:   every check in one place, so `plan` has a single guard rather than a ladder of exits.
     * Result: the first problem found, or `null` when the input is usable — in which case the date
     *         is known to parse, which is what lets `plan` read it without a second failure path.
     * Input:  [input]. Output: `AppError.Validation?`.
     */
    private fun validate(input: QuickSetupInput): AppError.Validation? =
        when {
            input.monthlyIncome.isNegative() -> AppError.Validation("monthlyIncome")
            input.rentOrEmi.isNegative() -> AppError.Validation("rentOrEmi")
            input.typicalSavings.isNegative() -> AppError.Validation("typicalSavings")
            parseIsoDate(input.periodStartIsoDate) == null -> AppError.Validation("periodStartIsoDate")
            else -> null
        }

    /**
     * Splits an income into needs / wants / savings, flexing for a high fixed load.
     *
     * Why:    RULE-50-30-20 carries `auto_flex_to_fixed_load` precisely because a metro rent would
     *         otherwise put a whole city permanently in breach of the guideline. The flex raises
     *         needs to cover the rent and takes the difference from **wants**, capped at the
     *         metro preset — so the savings floor survives every input, which is the property the
     *         property tests assert.
     * What:   picks the three weights, then hands the division to `Money.allocate`, whose
     *         largest-remainder algorithm guarantees the shares total the income exactly. Computing
     *         `income * pct / 100` per share instead would drop the truncated paise.
     * Result: three envelopes in display order, totalling [income] to the paise.
     * Input:  [income] — positive; [rent] — the fixed load, or `null`; [rules].
     * Output: `List<BudgetEnvelope>`.
     */
    private fun splitIncome(
        income: Money,
        rent: Money?,
        rules: QuickSetupRules,
    ): List<BudgetEnvelope> {
        val baseNeedsShare = income.minor * rules.needsPctMax / PCT_TOTAL
        val needsPct =
            if (rent != null && rent.minor > baseNeedsShare) {
                // Round the required share *up*, so the envelope covers the rent rather than
                // landing a paise short of it, then stop at the rulebook's ceiling.
                val requiredPct = ceilDiv(Math.multiplyExact(rent.minor, PCT_TOTAL.toLong()), income.minor).toInt()
                requiredPct.coerceIn(rules.needsPctMax, rules.metroNeedsPctMax)
            } else {
                rules.needsPctMax
            }
        // QuickSetupRules guarantees metroNeedsPctMax + savingsPctMin <= 100, so this is never
        // negative and the three weights always total 100.
        val wantsPct = PCT_TOTAL - needsPct - rules.savingsPctMin

        val shares = income.allocate(listOf(needsPct, wantsPct, rules.savingsPctMin))
        return listOf(
            BudgetEnvelope(BudgetNature.NEED, shares[0], QuickSetupRules.BUDGET_SPLIT),
            BudgetEnvelope(BudgetNature.WANT, shares[1], QuickSetupRules.BUDGET_SPLIT),
            BudgetEnvelope(BudgetNature.INVEST, shares[2], QuickSetupRules.BUDGET_SPLIT),
        )
    }

    /**
     * Builds one recurring seed per answered figure.
     * Why:    the sign convention is decided here, once, to match `TransactionEntity.amountMinor` —
     *         positive is an inflow, negative an outflow. Leaving it to the repository would mean
     *         two places deciding whether rent is negative, and they would eventually disagree.
     * Result: the seeds, in the order the user was asked for them; empty when nothing was answered.
     * Input:  [income], [rent], [savings] — already normalised; [nextDueIsoDate].
     * Output: `List<RecurringSeed>`.
     */
    private fun recurringSeeds(
        income: Money?,
        rent: Money?,
        savings: Money?,
        nextDueIsoDate: String,
    ): List<RecurringSeed> =
        listOfNotNull(
            income?.let { RecurringSeed(RecurringKind.INCOME, it, nextDueIsoDate) },
            rent?.let { RecurringSeed(RecurringKind.RENT_EMI, Money(-it.minor), nextDueIsoDate) },
            savings?.let { RecurringSeed(RecurringKind.SAVINGS, Money(-it.minor), nextDueIsoDate) },
        )

    /**
     * Rent as a share of income, in basis points (MNY-002).
     * Why:    integer bps rather than a percentage `Double` — the same rule that keeps amounts off
     *         floating point applies to the rates computed from them. Truncated rather than
     *         rounded, so a user a hair over a threshold is never pushed into the worse band by the
     *         arithmetic itself.
     * Result: the ratio, or `null` when either figure is missing — never `0`, which would read as
     *         "no obligations" rather than "not answered" (P-03).
     * Input:  [income], [rent]. Output: `Int?` basis points.
     */
    private fun obligationLoadBps(
        income: Money?,
        rent: Money?,
    ): Int? {
        if (income == null || rent == null) return null
        return (Math.multiplyExact(rent.minor, BPS_FULL.toLong()) / income.minor).toInt()
    }

    /**
     * Places an obligation ratio in RULE-EMI-40's bands.
     * Why:    the rule reads "<= 40%; hard fail at 50%", so both boundaries are inclusive on the
     *         side the wording implies — 40% passes, 50% fails. Getting either off by one would
     *         misclassify exactly the users sitting on a threshold.
     * Result: the verdict; [ObligationVerdict.UNKNOWN] when the ratio is unknown.
     * Input:  [bps], [rules]. Output: [ObligationVerdict].
     */
    private fun verdictFor(
        bps: Int?,
        rules: QuickSetupRules,
    ): ObligationVerdict =
        when {
            bps == null -> ObligationVerdict.UNKNOWN
            bps >= rules.obligationFailPct * PCT_TO_BPS -> ObligationVerdict.HARD_FAIL
            bps > rules.obligationWarnPct * PCT_TO_BPS -> ObligationVerdict.ABOVE_LIMIT
            else -> ObligationVerdict.WITHIN_LIMIT
        }

    /**
     * Lists the rules that actually changed the answer (P-02).
     * Why:    evidence is what the user's drill-down shows, so it has to be the rules that *fired* —
     *         not every rule the engine knows. RULE-RUNWAY-M in particular is cited only when its
     *         clamp moved the runway; listing it when it did nothing would pad the reasoning card
     *         with rules that explain nothing, which teaches the user to stop reading it.
     * Result: the citations, in display order.
     * Input:  [envelopes], [emergencyTarget], [obligationBps], [rules].
     * Output: `List<RuleCitation>`.
     */
    private fun evidenceFor(
        envelopes: List<BudgetEnvelope>,
        emergencyTarget: Money?,
        obligationBps: Int?,
        rules: QuickSetupRules,
    ): List<RuleCitation> =
        buildList {
            if (envelopes.isNotEmpty()) add(QuickSetupRules.BUDGET_SPLIT)
            if (emergencyTarget != null) {
                add(QuickSetupRules.EMERGENCY_RUNWAY)
                if (rules.runwayWasClamped) add(QuickSetupRules.RUNWAY_CLAMP)
            }
            if (obligationBps != null) add(QuickSetupRules.OBLIGATION_LOAD)
        }

    private companion object {
        /** Stable id cited in every plan's provenance (AI-ARC-003). Never renamed. */
        const val ENGINE_ID = "quick-setup"

        /** Bumped whenever the formula changes, so old plans stay reproducible (AI-ARC-006). */
        const val ENGINE_VERSION = "1.0"

        /** 10 000 bps = 100% (MNY-002). */
        const val BPS_FULL = 10_000

        /** One whole percent in basis points. */
        const val PCT_TO_BPS = 100

        /** The whole of an income, as the percent the three bands sum to. */
        const val PCT_TOTAL = 100
    }
}

/** Result: `true` when this amount is present and below zero. Input: the receiver. Output: `Boolean`. */
private fun Money?.isNegative(): Boolean = this != null && this < Money.ZERO

/**
 * Result: the amount, or `null` when it is absent or zero — a zero seed is an unanswered one, the
 *         same reading `SettingsStore` applies when it loads the stored value back.
 * Input:  the receiver. Output: `Money?`.
 */
private fun Money?.takeIfAnswered(): Money? = this?.takeIf { it > Money.ZERO }

/**
 * Result: the ISO date, or `null` when the text is not one (TIM-002).
 * Input:  [text]. Output: `LocalDate?`.
 */
private fun parseIsoDate(text: String): LocalDate? =
    try {
        LocalDate.parse(text)
    } catch (malformed: DateTimeParseException) {
        null
    }

/**
 * Integer division that rounds up.
 * Why:    the needs band has to *cover* the rent, and `a / b` truncates — leaving the envelope one
 *         paise short of the obligation it was raised to cover, on exactly the inputs where that
 *         matters most.
 * Result: the ceiling of `dividend / divisor` for non-negative operands.
 * Input:  [dividend], [divisor] — both non-negative, divisor positive. Output: `Long`.
 */
private fun ceilDiv(
    dividend: Long,
    divisor: Long,
): Long = (dividend + divisor - 1L) / divisor
