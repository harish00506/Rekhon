package com.aicfo.app.di

import com.aicfo.domain.engines.emergencyfund.EmergencyFundEngine
import com.aicfo.domain.engines.emergencyfund.EmergencyFundEngineFactory
import com.aicfo.domain.engines.goals.GoalEngine
import com.aicfo.domain.engines.goals.GoalEngineFactory
import com.aicfo.domain.engines.goals.GoalWaterfallEngine
import com.aicfo.domain.engines.goals.GoalWaterfallEngineFactory
import com.aicfo.domain.engines.orderofoperations.OrderOfOperationsEngine
import com.aicfo.domain.engines.orderofoperations.OrderOfOperationsEngineFactory
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Epic 7's planning engines (ARC-002, ARC-003, P-03).
 *
 * Why:  a new object rather than a `@Provides` added to [WealthEngineModule], for the two reasons
 *       every previous split had. `EngineModule` is already at detekt's `TooManyFunctions` ceiling,
 *       so something has to give — and the cut is made on a real seam rather than wherever the count
 *       happens to fall. Epic 6's engines describe **what the user already has**: a card's cycle, a
 *       loan's schedule, a holding's return. Epic 7's describe **what they are trying to reach**.
 *       Those are different questions, and 7.2's emergency-fund engine and 7.5's order-of-operations
 *       belong beside this one rather than beside a billing cycle.
 * What: one `@Provides` per engine, built through its factory because the implementation is
 *       `internal` to its module (ARC-003).
 * Result: features and repositories inject an interface and never name an implementation.
 * Changelog: 2026-08-30 — Created for issue 7.1.
 *            2026-09-02 — Issue 7.2: the emergency-fund engine, which this module's own KDoc had
 *            already named as belonging here.
 *            2026-09-03 — Issue 7.3: the waterfall, which shares one surplus between the two.
 *            2026-09-17 — Issue 7.5: the order of operations, which the KDoc above had already
 *            placed here — it ranks the other three's answers across the whole household.
 *
 * **Singleton, safely** — the engine is stateless and deterministic, so one shared instance has
 * nothing to reset between screens and no way for one caller's use to affect another's.
 */
@Module
@InstallIn(SingletonComponent::class)
object GoalEngineModule {
    /**
     * A goal's required monthly contribution, its ETA and its horizon (issue 7.1; §15, AI-GOAL).
     * Why:    stateless and pure like every engine in this graph — it reads no clock, so the
     *         repository hands it today's date (TIM-001) and the goals, and every answer is
     *         reproducible from them. `@Singleton` because there is nothing to keep per caller.
     * Result: a [GoalEngine]. Input: none. Output: the engine.
     * Changelog: 2026-08-30 — Created for issue 7.1.
     */
    @Provides
    @Singleton
    fun provideGoalEngine(): GoalEngine = GoalEngineFactory.create()

    /**
     * The emergency fund's target, runway and multiplier (issue 7.2; §10.1, AI-EMF).
     * Why:    stateless and pure like every engine in this graph — it reads no clock, so the
     *         repository hands it today's date (TIM-001) and the resolved figures, and every
     *         answer is reproducible from them. `@Singleton` because there is nothing to keep
     *         per caller.
     * Result: an [EmergencyFundEngine]. Input: none. Output: the engine.
     * Changelog: 2026-09-02 — Created for issue 7.2.
     */
    @Provides
    @Singleton
    fun provideEmergencyFundEngine(): EmergencyFundEngine = EmergencyFundEngineFactory.create()

    /**
     * Goal feasibility and the contribution waterfall (issue 7.3; §15.1, FR-GOAL-003/005).
     * Why:    the engine that makes the other two in this module talk to each other — the goals ask
     *         for a monthly each, the emergency fund claims the top of the surplus, and this decides
     *         who actually gets what. Stateless and pure like the rest: it reads no clock and holds
     *         no threshold, so `@Singleton` keeps nothing per caller.
     * Result: a [GoalWaterfallEngine]. Input: none. Output: the engine.
     * Changelog: 2026-09-03 — Created for issue 7.3.
     */
    @Provides
    @Singleton
    fun provideGoalWaterfallEngine(): GoalWaterfallEngine = GoalWaterfallEngineFactory.create()

    /**
     * The Financial Order of Operations (issue 7.5; §36, AI-FOO).
     * Why:    the engine that ranks the other three's answers — buffer, debt, emergency fund, goals —
     *         into one "next best rupee". Stateless and pure like the rest: it reads no clock, and the
     *         one threshold it does not mirror arrives as an input, so `@Singleton` keeps nothing per
     *         caller.
     * Result: an [OrderOfOperationsEngine]. Input: none. Output: the engine.
     * Changelog: 2026-09-17 — Created for issue 7.5.
     */
    @Provides
    @Singleton
    fun provideOrderOfOperationsEngine(): OrderOfOperationsEngine = OrderOfOperationsEngineFactory.create()
}
