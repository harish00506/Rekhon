package com.aicfo.domain.engines.card

import com.aicfo.core.model.EngineProvenance
import com.aicfo.core.model.Money

/**
 * The only decision in this engine that can wake a person up (issue 6.1; §17.1, FR-ACC-002).
 *
 * Why:  held in its own unit for the reason `BudgetAlertBands` is: the whole "should the user be
 *       told?" rule should be readable in one screen, and keeping it out of [DefaultCardEngine]
 *       stops that file becoming where every card concern lands.
 *
 *       **The two alerts are genuinely different and are not merged.** §17.1 classes a card payment
 *       as a *Critical money event* — exempt from the notification budget, allowed past quiet hours
 *       — while utilisation is discipline. They cite different rules, they go to different Android
 *       channels (NTF-006), and one can fire without the other. So this returns a list, and the
 *       caller claims and posts each independently.
 * What: the due-date window, the utilisation line, and the provenance naming which row fired.
 * Result: zero, one or two [CardAlert]s, reproducible from the inputs alone (P-08).
 * Changelog: 2026-08-17 — Created for issue 6.1.
 *
 * `internal` — the public seam is [CardEngine.alert] (ARC-003). **No clock** (TIM-001).
 */
internal object CardAlerts {
    /** The engine that owns these decisions; repeated so provenance reads the same either way. */
    private const val ENGINE_ID = "card-planner"

    /** Bump when the alert arithmetic changes (AI-ARC-006). */
    private const val ENGINE_VERSION = "1.0"

    /**
     * Decides what is worth saying about this card today.
     *
     * **The due alert.** Fires inside the window `0..remindDaysBefore` — that is, from three days
     * out through the due date itself, which is what `remind_on_due_day` asks for. It deliberately
     * does **not** fire once the date has passed: an overdue card needs a different message than a
     * reminder, and inventing one here would mean shipping copy no rule row authorised. Suppressed
     * entirely when nothing is owed, per `skip_when_nothing_due` — a reminder to pay ₹0 is the
     * notification that teaches a user to mute the channel that must still work in eleven months.
     *
     * **The utilisation alert.** Fires on the **statement** figure, never the live one. A live
     * figure moves with every swipe, so an alert on it would either nag or be claimed once and then
     * be wrong for the rest of the cycle; the statement figure is fixed until the next statement,
     * which is what makes "once per cycle" a promise the claim key can keep. A card with no
     * statement recorded therefore has no utilisation alert, which is correct — nothing has been
     * reported about it yet.
     *
     * Result: the alerts, in a stable order (due first, because it is the critical one) so a caller
     *         iterating them notifies in a deterministic sequence.
     * Input:  [input] — the card, today, and the live outstanding.
     * Output: `List<CardAlert>`; empty is the ordinary answer.
     */
    fun evaluate(input: CardAlertInput): List<CardAlert> {
        val cycle = BillingCycles.of(input.today, input.card.statementDay, input.card.dueDay)
        val cycleStart = cycle.statementDate.toString()
        val alerts = mutableListOf<CardAlert>()

        dueAlert(input, cycle, cycleStart)?.let(alerts::add)
        utilisationAlert(input, cycle, cycleStart)?.let(alerts::add)
        return alerts
    }

    /**
     * The payment reminder (`RULE-CC-DUE`).
     * Why:    the four suppressions are named and then tested together, rather than as a ladder of
     *         early returns. They are one rule — "remind about a real, unpaid bill inside the
     *         window" — and reading them as four lines beside each other is how a reviewer sees that
     *         the window is inclusive at both ends and that `daysUntilDue < 0` is excluded by it.
     * Result: a [CardAlert], or `null` outside the window or when the card is settled.
     * Input:  [input]; [cycle]; [cycleStart] — the claim key. Output: [CardAlert]?.
     */
    private fun dueAlert(
        input: CardAlertInput,
        cycle: BillingCycle,
        cycleStart: String,
    ): CardAlert? {
        val rules = input.rules
        // What is actually owed for this cycle. The statement is the bill; with none recorded there
        // is nothing to remind about, whatever has been swiped since.
        val owed = input.card.lastStatement
        val insideWindow = cycle.daysUntilDue in 0..rules.remindDaysBefore
        val dueDaySuppressed = cycle.daysUntilDue == 0 && !rules.remindOnDueDay
        val settled = rules.skipWhenNothingDue && input.outstanding <= Money.ZERO
        // Named as one thing, because it is one question: is there any reason to stay quiet today?
        val staySilent = !insideWindow || dueDaySuppressed || settled

        if (owed == null || staySilent) return null

        return CardAlert(
            kind = CardAlertKind.DUE_SOON,
            accountId = input.card.accountId,
            cycleStartIsoDate = cycleStart,
            amount = owed,
            creditLimit = input.card.creditLimit,
            minimumDue = input.card.minimumDue,
            daysUntilDue = cycle.daysUntilDue,
            ratioBps = null,
            provenance = provenance(input.nowUtcMillis, CardRules.DUE),
        )
    }

    /**
     * The utilisation warning (`RULE-CC-UTIL`).
     * Result: a [CardAlert], or `null` below the line or with no statement to judge.
     * Input:  [input]; [cycle]; [cycleStart]. Output: [CardAlert]?.
     */
    private fun utilisationAlert(
        input: CardAlertInput,
        cycle: BillingCycle,
        cycleStart: String,
    ): CardAlert? {
        val statement = input.card.lastStatement ?: return null
        val ratioBps = CardUtilisations.ratioBps(statement, input.card.creditLimit) ?: return null
        // `>=`, because the rule says 30%, not "past 30%" — the boundary is inside the band.
        if (ratioBps < input.rules.maxUtilisationPct * BPS_PER_PERCENT) return null

        return CardAlert(
            kind = CardAlertKind.UTILISATION,
            accountId = input.card.accountId,
            cycleStartIsoDate = cycleStart,
            amount = statement,
            creditLimit = input.card.creditLimit,
            minimumDue = null,
            daysUntilDue = cycle.daysUntilDue,
            ratioBps = ratioBps,
            provenance = provenance(input.nowUtcMillis, CardRules.UTILISATION),
        )
    }

    /**
     * Provenance for one alert (AI-ARC-003).
     * Why:    one citation, not both. Each alert was decided by exactly one row, and citing the
     *         other would point the user's "why am I seeing this?" at a rule that had no part in it
     *         — the argument `BudgetAlertBands` records for not citing `RULE-BUD-PACE`.
     * Result: engine id/version, the caller's instant, and the row that fired.
     * Input:  [nowUtcMillis]; [citation]. Output: [EngineProvenance].
     */
    private fun provenance(
        nowUtcMillis: Long,
        citation: com.aicfo.core.model.RuleCitation,
    ): EngineProvenance =
        EngineProvenance(
            engineId = ENGINE_ID,
            engineVersion = ENGINE_VERSION,
            computedAtUtcMillis = nowUtcMillis,
            evidence = listOf(citation),
        )
}
