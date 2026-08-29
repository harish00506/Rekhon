package com.aicfo.app.di

import com.aicfo.domain.engines.card.CardEngine
import com.aicfo.domain.engines.card.CardEngineFactory
import com.aicfo.domain.engines.investment.InvestmentEngine
import com.aicfo.domain.engines.investment.InvestmentEngineFactory
import com.aicfo.domain.engines.loan.LoanEngine
import com.aicfo.domain.engines.loan.LoanEngineFactory
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Epic 6's wealth engines (ARC-002, ARC-003, P-03).
 *
 * Why:  split out of [EngineModule] when issue 6.2's loan engine took that object to detekt's
 *       `TooManyFunctions` ceiling — the third such split, and the same rule each time: cut on a
 *       real seam rather than wherever the count happens to fall. Epic 6 is the app's wealth epic,
 *       and a card, a loan and a holding are what it models. They also share a property none of the
 *       engines next door do: **both compute a schedule from terms rather than from history**, so
 *       neither reads a clock and both are reproducible from a handful of stored numbers (P-08).
 * What: one `@Provides` per engine, each built through its factory because the implementations are
 *       `internal` to their modules (ARC-003).
 * Result: features and repositories inject an interface and never name an implementation.
 * Changelog: 2026-08-20 — Created for issue 6.2, carrying issue 6.1's card engine across unchanged.
 *
 * **Singletons, safely** — both are stateless and deterministic, so one shared instance has nothing
 * to reset between screens and no way for one caller's use to affect another's.
 */
@Module
@InstallIn(SingletonComponent::class)
object WealthEngineModule {
    /**
     * A credit card's billing cycle, utilisation and reminders (issue 6.1; §5.7, FR-ACC-002).
     * Why:    stateless and pure like every engine here — it reads no clock, so the repository hands
     *         it today's date (TIM-001) and two amounts, and every answer is reproducible from them.
     *         `@Singleton` for the reason the others are: there is nothing to keep per caller.
     * Result: a [CardEngine]. Input: none. Output: the engine.
     * Changelog: 2026-08-17 — Created for issue 6.1, in [EngineModule].
     *            2026-08-20 — Issue 6.2: moved here.
     */
    @Provides
    @Singleton
    fun provideCardEngine(): CardEngine = CardEngineFactory.create()

    /**
     * A loan's EMI and its principal/interest split (issue 6.2; §5.8, FR-ACC-003).
     * Why:    stateless and pure like the card engine beside it, and clockless for the same reason —
     *         every instalment date comes from the loan's own first-EMI date, so a twenty-year
     *         schedule is reproducible from five numbers alone (P-08). `@Singleton` because there is
     *         nothing to keep per caller.
     * Result: a [LoanEngine]. Input: none. Output: the engine.
     * Changelog: 2026-08-19 — Created for issue 6.2, in [EngineModule].
     *            2026-08-20 — Issue 6.2: moved here when that object hit the function ceiling.
     */
    @Provides
    @Singleton
    fun provideLoanEngine(): LoanEngine = LoanEngineFactory.create()

    /**
     * A holding's value, gain and money-weighted return (issue 6.3; §11, AI-INV).
     * Why:    the third engine with this object's shared property, and the most literally so: it is
     *         handed a list of dated amounts and reads no clock at all, because a holding's closing
     *         cash flow is dated by the day its price was observed rather than by today. That is
     *         what makes a twelve-year SIP's return reproducible from stored rows alone (P-08).
     *         `@Singleton` because there is nothing to keep per caller.
     * Result: an [InvestmentEngine]. Input: none. Output: the engine.
     * Changelog: 2026-08-24 — Created for issue 6.3.
     */
    @Provides
    @Singleton
    fun provideInvestmentEngine(): InvestmentEngine = InvestmentEngineFactory.create()
}
