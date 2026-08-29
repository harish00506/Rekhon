package com.aicfo.domain.engines.card

import com.aicfo.core.model.RuleCitation

/**
 * The thresholds this engine applies, copied from `ai/rules/rules-kb.json`.
 *
 * Why:  CLAUDE.md §6 says a financial threshold is a **data row in `ai/`, never a hardcoded
 *       number** — and this file is hardcoded numbers. The same deliberate, recorded deferral
 *       ADR-0005 made for `QuickSetupRules` and ADR-0017 restated for `BudgetRules`: nothing in the
 *       app loads `ai/` at runtime, so honouring §6 literally would mean building an asset pipeline
 *       and a JSON parser into a module that has no serialisation dependency by design (ARC-002).
 *       `RulebookDriftTest` closes the gap that matters — edit either row in the rulebook and the
 *       build goes red until this file agrees.
 *
 *       **Two rows, and only one of them is new.** `RULE-CC-UTIL` shipped long before this issue,
 *       already carrying `max_utilisation_pct: 30` and already naming `card_alerts` in its
 *       `consumed_by` — the rulebook was written expecting this engine. So the utilisation band is
 *       *read*, not authored, and the row is untouched. Only the date question needed a row of its
 *       own, `RULE-CC-DUE`, minted at v1.0. See ADR-0025.
 * What: one property per `params_json` key this engine applies, plus a citation per row.
 * Result: every reminder and every utilisation band is attributable to a row a reviewer can open,
 *       and every threshold that shaped it is one they can change (P-02, AI-ARC-006).
 * Changelog: 2026-08-17 — Created for issue 6.1 from rules-kb.json v1.13.0.
 *
 * **Injected rather than read, so a test can move a threshold** and assert the engine moves with it
 * — the seam the real loader will use when it lands, with no change to the engine.
 *
 * Input:  [maxUtilisationPct] — `RULE-CC-UTIL.max_utilisation_pct`, the whole percent of a limit at
 *         or above which the card is worth mentioning; [remindDaysBefore] —
 *         `RULE-CC-DUE.remind_days_before`, how many days ahead of the due date the first reminder
 *         fires; [remindOnDueDay] — `RULE-CC-DUE.remind_on_due_day`, whether a second reminder
 *         fires on the day itself; [skipWhenNothingDue] — `RULE-CC-DUE.skip_when_nothing_due`,
 *         whether a fully paid card is left alone.
 * Output: an immutable value.
 */
data class CardRules(
    /** `RULE-CC-UTIL.max_utilisation_pct` — 30% is the credit-discipline line the rulebook draws. */
    val maxUtilisationPct: Int = 30,
    /** `RULE-CC-DUE.remind_days_before` — three days, the smallest window that spans a weekend badly. */
    val remindDaysBefore: Int = 3,
    /** `RULE-CC-DUE.remind_on_due_day` — the three-day reminder can be dismissed and forgotten. */
    val remindOnDueDay: Boolean = true,
    /** `RULE-CC-DUE.skip_when_nothing_due` — a reminder to pay ₹0 teaches the user to mute this. */
    val skipWhenNothingDue: Boolean = true,
) {
    init {
        // At or below zero every card is permanently over the line, so the alert fires for everyone
        // on day one and means nothing. At or above 100 it can never fire at all, because a card
        // cannot exceed its own limit — either way every test asserting a *ratio* still passes while
        // the advice is worthless, which is precisely the failure a require() is for.
        require(maxUtilisationPct in 1..<FULL_PERCENT) {
            "maxUtilisationPct is a whole percent of the credit limit and must be in 1..${FULL_PERCENT - 1}, " +
                "was $maxUtilisationPct: at or below 0 every card is always over the line, and at or " +
                "above 100 no card can ever reach it"
        }
        // Negative would put the reminder *after* the due date, which is not a reminder. The ceiling
        // is a month because a card bills monthly: a 40-day warning fires before the previous
        // statement was even cut, so the user would be told to pay a bill that does not exist yet.
        require(remindDaysBefore in 0..MAX_REMIND_DAYS) {
            "remindDaysBefore must be in 0..$MAX_REMIND_DAYS, was $remindDaysBefore: a negative " +
                "window reminds after the money was due, and a window longer than a billing cycle " +
                "reminds about a statement that has not been cut"
        }
    }

    companion object {
        /** `RULE-CC-UTIL` — how much of a limit is in use. Shipped before this issue; read, not written. */
        val UTILISATION = RuleCitation("RULE-CC-UTIL", "1.0")

        /** `RULE-CC-DUE` — when to interrupt someone about a payment date. Minted by issue 6.1. */
        val DUE = RuleCitation("RULE-CC-DUE", "1.0")

        /** The rulebook file these thresholds were copied from, as `_meta.version`. */
        const val RULEBOOK_VERSION = "1.13.0"

        /** 100% — the ceiling [maxUtilisationPct] must stay under. */
        private const val FULL_PERCENT = 100

        /** A billing cycle is a month; a reminder cannot usefully precede the statement itself. */
        private const val MAX_REMIND_DAYS = 28
    }
}

/** 10 000 bps = 100% (MNY-002). The scale every ratio in this engine is expressed on. */
internal const val BPS_FULL = 10_000

/** 100 bps = 1%, so a whole-percent rule threshold becomes a bps threshold (MNY-002). */
internal const val BPS_PER_PERCENT = 100
