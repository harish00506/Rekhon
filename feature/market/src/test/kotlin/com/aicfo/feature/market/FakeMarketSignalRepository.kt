package com.aicfo.feature.market

import com.aicfo.core.common.AppError
import com.aicfo.core.common.Ok
import com.aicfo.core.common.Result
import com.aicfo.core.model.EngineProvenance
import com.aicfo.core.model.Money
import com.aicfo.core.model.RuleCitation
import com.aicfo.data.repository.MarketSignalRepository
import com.aicfo.data.repository.OpportunityView
import com.aicfo.domain.engines.marketsignal.AssessmentOutcome
import com.aicfo.domain.engines.marketsignal.CapacityGate
import com.aicfo.domain.engines.marketsignal.HitRate
import com.aicfo.domain.engines.marketsignal.Instrument
import com.aicfo.domain.engines.marketsignal.MarketKnowledge
import com.aicfo.domain.engines.marketsignal.OpportunityAssessment
import com.aicfo.domain.engines.marketsignal.OpportunityBand
import com.aicfo.domain.engines.marketsignal.SignalContribution
import com.aicfo.domain.engines.marketsignal.Staleness
import com.aicfo.domain.engines.marketsignal.TranchePlan
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.flow

/**
 * A stand-in for the opportunity data side, for the state holder's tests (issue 10.7).
 *
 * Why:  `:feature:market` must not reach a database to be tested, and what the state holder is
 *       being tested for is that it changes nothing it is handed. A fake that emits a known
 *       assessment makes "changed nothing" assertable figure by figure — and a fake that can fail
 *       on demand makes the error path reachable, which a real repository would not.
 * What: an emittable stream of views, and a failure mode.
 * Result: the ViewModel's own behaviour, with no engine and no database in the way.
 * Changelog: 2026-09-27 — Created for issue 10.7.
 *
 * Input:  [failure] — when set, the stream throws it instead of emitting. Output: the fake.
 */
internal class FakeMarketSignalRepository(
    private val failure: Throwable? = null,
) : MarketSignalRepository {
    private val emissions = MutableSharedFlow<List<OpportunityView>>(replay = 1)

    /**
     * Result: the stream the ViewModel subscribes to. Input: none.
     * Output: `Flow<List<OpportunityView>>` — one that throws when [failure] is set.
     */
    override fun observeOpportunities(): Flow<List<OpportunityView>> =
        failure?.let { thrown -> flow<List<OpportunityView>> { throw thrown } } ?: emissions

    /** Result: `Ok`, unused here. Input: [priceKey]; [isoDate]; [close]. Output: `Result`. */
    override suspend fun recordClose(
        priceKey: String,
        isoDate: String,
        close: Money,
    ): Result<Unit, AppError> = Ok(Unit)

    /** Result: the views the screen will see next. Input: [views]. Output: none. */
    suspend fun emit(views: List<OpportunityView>) {
        emissions.emit(views)
    }

    internal companion object {
        const val NOW = 1_790_000_000_000L

        /**
         * One scored instrument, with every figure the screen shows set to a distinct value.
         * Result: a view a test can check field by field. Input: none. Output: [OpportunityView].
         */
        fun view() =
            OpportunityView(
                assessment =
                    OpportunityAssessment(
                        instrument = Instrument("NSE:NIFTYBEES", "Nifty BeES"),
                        outcome = AssessmentOutcome.SCORED,
                        score = 41,
                        possibleScore = 60,
                        band = OpportunityBand.GOOD_DAY,
                        signals =
                            listOf(
                                SignalContribution(MarketKnowledge.VALUATION, 0, 25, evaluated = false),
                                SignalContribution(MarketKnowledge.DRAWDOWN, 15, 20, true, measuredBps = -1_300),
                            ),
                        hitRate = HitRate(samples = 44, hits = 28, ratePct = 64, horizonDays = 90),
                        tranches = TranchePlan(suggested = 2, gates = listOf(CapacityGate("RULE-IDLE-CASH", true))),
                        staleness = Staleness("2026-09-27", 0, isStale = false),
                        provenance =
                            EngineProvenance(
                                engineId = "AI-MKT",
                                engineVersion = "1.0",
                                computedAtUtcMillis = NOW,
                                evidence = listOf(RuleCitation("MKT-SIGNALS", "1.1")),
                            ),
                    ),
                holdingLabel = "Nifty BeES",
            )
    }
}
