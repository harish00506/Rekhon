package com.aicfo.domain.engines.card

import com.aicfo.core.common.AppError
import com.aicfo.core.common.Ok
import com.aicfo.core.common.Result
import com.aicfo.core.model.EngineProvenance

/**
 * The production [CardEngine] (issue 6.1; FR-ACC-002, §5.7).
 *
 * Why:  three small pure calculators, and this class exists only to name them in one place and
 *       attach provenance — the same division `DefaultBudgetEngine` uses. Every operation is total:
 *       `CreditCard`'s own `init` has already refused a day outside 1..31 and a non-positive limit,
 *       so there is no input this can be handed that it must reject, which is why nothing here
 *       returns `Err`.
 * What: delegates to [BillingCycles], [CardUtilisations] and [CardAlerts].
 * Result: `Ok` in every case, carrying values reproducible from their inputs (P-08).
 * Changelog: 2026-08-17 — Created for issue 6.1.
 *
 * `internal` — callers hold the [CardEngine] interface, built by [CardEngineFactory] (ARC-003).
 */
internal class DefaultCardEngine : CardEngine {
    override fun cycle(input: CardCycleInput): Result<BillingCycle, AppError> =
        Ok(BillingCycles.of(input.today, input.statementDay, input.dueDay))

    /**
     * Assembles everything a card screen shows.
     *
     * **Both utilisations are computed, always.** The live one is what the user feels; the statement
     * one is what a bureau sees and what the alert acts on. Returning only one would force the
     * screen to choose, and whichever it chose would be an unlabelled number the user cannot check
     * (P-02).
     *
     * Result: `Ok(CardStatus)`. A card with no statement recorded gets a statement [Utilisation]
     *         with `null` amounts rather than zeros — absence is not "you owe nothing" (P-03).
     * Input:  [input]. Output: `Result<CardStatus, AppError>`.
     */
    override fun status(input: CardStatusInput): Result<CardStatus, AppError> {
        val card = input.card
        return Ok(
            CardStatus(
                accountId = card.accountId,
                creditLimit = card.creditLimit,
                live =
                    Utilisation(
                        basis = UtilisationBasis.LIVE,
                        used = input.outstanding,
                        ratioBps = CardUtilisations.ratioBps(input.outstanding, card.creditLimit),
                    ),
                statement =
                    Utilisation(
                        basis = UtilisationBasis.STATEMENT,
                        used = card.lastStatement,
                        ratioBps = CardUtilisations.ratioBps(card.lastStatement, card.creditLimit),
                    ),
                unbilled = CardUtilisations.unbilled(input.outstanding, card.lastStatement),
                available = CardUtilisations.available(card.creditLimit, input.outstanding),
                cycle = BillingCycles.of(input.today, card.statementDay, card.dueDay),
                minimumDue = card.minimumDue,
                // Both rows: the screen shows the utilisation line RULE-CC-UTIL draws *and* the
                // cycle dates RULE-CC-DUE's window is measured against, so a drill-down that cited
                // only one would leave half the card unexplained.
                provenance =
                    EngineProvenance(
                        engineId = ENGINE_ID,
                        engineVersion = ENGINE_VERSION,
                        computedAtUtcMillis = input.nowUtcMillis,
                        evidence = listOf(CardRules.UTILISATION, CardRules.DUE),
                    ),
            ),
        )
    }

    override fun alert(input: CardAlertInput): Result<List<CardAlert>, AppError> = Ok(CardAlerts.evaluate(input))

    private companion object {
        const val ENGINE_ID = "card-planner"
        const val ENGINE_VERSION = "1.0"
    }
}
