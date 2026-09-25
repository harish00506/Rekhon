package com.aicfo.feature.advisor

import com.aicfo.core.common.AppError
import com.aicfo.core.common.Err
import com.aicfo.core.common.Ok
import com.aicfo.core.common.Result
import com.aicfo.core.model.EngineProvenance
import com.aicfo.core.model.Money
import com.aicfo.core.model.RuleCitation
import com.aicfo.data.repository.KeptVerdict
import com.aicfo.data.repository.PurchaseAdvisorRepository
import com.aicfo.domain.engines.purchase.Alternatives
import com.aicfo.domain.engines.purchase.GateFigure
import com.aicfo.domain.engines.purchase.GateId
import com.aicfo.domain.engines.purchase.GateOutcome
import com.aicfo.domain.engines.purchase.GateResult
import com.aicfo.domain.engines.purchase.ImpactStrip
import com.aicfo.domain.engines.purchase.PaymentMethod
import com.aicfo.domain.engines.purchase.PurchaseRequest
import com.aicfo.domain.engines.purchase.PurchaseVerdictCard
import com.aicfo.domain.engines.purchase.Urgency
import com.aicfo.domain.engines.purchase.Verdict
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * A stand-in for the advisor's data side (issue 10.1).
 *
 * Why:  the screen's tests are about what it draws and what it asks for — not about the gates,
 *       which are proven in `:domain:engines:purchase`, nor about the store, which is proven in
 *       `:data:repository`. A fake makes the card under test explicit.
 * Result: whatever card the test sets, and a record of what was asked.
 * Changelog: 2026-09-25 — Created for issue 10.1.
 */
class FakePurchaseAdvisorRepository : PurchaseAdvisorRepository {
    /** What the screen asked about, in order. */
    val asked: MutableList<PurchaseRequest> = mutableListOf()

    /** The card handed back; the default is a stretch with one warning. */
    var card: PurchaseVerdictCard = card(Verdict.STRETCH)

    /** When set, [advise] fails with it. */
    var failure: AppError? = null

    /** The history the screen lists. */
    val history: MutableStateFlow<List<KeptVerdict>> = MutableStateFlow(emptyList())

    override suspend fun advise(request: PurchaseRequest): Result<PurchaseVerdictCard, AppError> {
        asked += request
        return failure?.let { Err(it) } ?: Ok(card)
    }

    override fun observeRecent(limit: Int): Flow<List<KeptVerdict>> = history

    override suspend fun find(id: String): Result<PurchaseVerdictCard?, AppError> = failure?.let { Err(it) } ?: Ok(card)

    companion object {
        /** Result: three gates — a pass with two amounts, one with a ratio, and a warning with a count. */
        private fun gates(): List<GateResult> =
            listOf(
                GateResult(
                    gate = GateId.AFFORDABILITY,
                    outcome = GateOutcome.PASS,
                    figures =
                        listOf(
                            GateFigure("liquidBefore", amount = Money(1_00_000_00L)),
                            GateFigure("liquidAfter", amount = Money(70_000_00L)),
                        ),
                    citations = listOf(RuleCitation("RULE-PA-GATES", "1.0")),
                ),
                GateResult(
                    gate = GateId.OBLIGATIONS,
                    outcome = GateOutcome.PASS,
                    figures = listOf(GateFigure("obligationsAfter", bps = 3_000)),
                    citations = listOf(RuleCitation("RULE-EMI-40", "1.0")),
                ),
                GateResult(
                    gate = GateId.GOAL_IMPACT,
                    outcome = GateOutcome.WARN,
                    figures = listOf(GateFigure("goalDelayDays", count = 60)),
                    citations = listOf(RuleCitation("RULE-PA-GATES", "1.0")),
                ),
            )

        /** Result: a card a test can assert against, with a figure of every kind on it. */
        fun card(
            verdict: Verdict = Verdict.STRETCH,
            item: String = "Headphones",
        ): PurchaseVerdictCard =
            PurchaseVerdictCard(
                request = PurchaseRequest(item, Money(30_000_00L), PaymentMethod.CASH, Urgency.ROUTINE),
                verdict = verdict,
                gates = gates(),
                impact =
                    ImpactStrip(
                        liquidBefore = Money(1_00_000_00L),
                        liquidAfter = Money(70_000_00L),
                        runwayMonthsBeforeTenths = 24,
                        runwayMonthsAfterTenths = 17,
                        goalDelayDays = 60,
                    ),
                alternatives =
                    Alternatives(
                        comfortablePrice = Money(5_000_00L),
                        comfortableFrom = java.time.LocalDate.parse("2026-11-25"),
                        coolOffSuggested = true,
                    ),
                hardFail = false,
                provenance =
                    EngineProvenance(
                        engineId = "AI-PA",
                        engineVersion = "1.0",
                        computedAtUtcMillis = 1_790_000_000_000L,
                        evidence = listOf(RuleCitation("RULE-PA-GATES", "1.0")),
                    ),
            )
    }
}
