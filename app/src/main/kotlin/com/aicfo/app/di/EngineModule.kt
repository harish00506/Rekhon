package com.aicfo.app.di

import com.aicfo.domain.engines.budget.BudgetEngine
import com.aicfo.domain.engines.budget.BudgetEngineFactory
import com.aicfo.domain.engines.classification.ClassificationEngine
import com.aicfo.domain.engines.classification.ClassificationEngineFactory
import com.aicfo.domain.engines.nature.NatureEngine
import com.aicfo.domain.engines.nature.NatureEngineFactory
import com.aicfo.domain.engines.networth.NetWorthEngine
import com.aicfo.domain.engines.networth.NetWorthEngineFactory
import com.aicfo.domain.engines.quicksetup.QuickSetupEngine
import com.aicfo.domain.engines.quicksetup.QuickSetupEngineFactory
import com.aicfo.domain.engines.receipt.ReceiptEngine
import com.aicfo.domain.engines.receipt.ReceiptEngineFactory
import com.aicfo.domain.engines.recurring.RecurringEngine
import com.aicfo.domain.engines.recurring.RecurringEngineFactory
import com.aicfo.domain.engines.sms.SmsEngine
import com.aicfo.domain.engines.sms.SmsEngineFactory
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * The `:domain:engines:*` bindings (ARC-002, ARC-003, P-03).
 *
 * Why:  split out of [CoreModule] when adding the net-worth engine took that object to detekt's
 *       `TooManyFunctions` ceiling — the same pressure that produced [RepositoryModule] for issue
 *       2.4, and the same kind of seam rather than an arbitrary cut. Everything here is a
 *       **pure-Kotlin calculator**: no Android imports (ARC-002), no state, no dependencies, and by
 *       P-03 the only code in the app allowed to produce a financial figure. A reviewer asking
 *       "what computes the numbers?" now has one file to read.
 * What: one `@Provides` per engine, each built through its factory because the implementations are
 *       `internal` to their modules (ARC-003).
 * Result: features and repositories inject an interface and never name an implementation.
 * Changelog: 2026-08-01 — Created for issue 2.6, with the app's second engine.
 *            2026-08-11 — Issue 4.4: the budget engine took the object back to the ceiling, so the
 *            three device-backed collaborators that were never engines — the text recogniser, the
 *            inbox reader, the receipt store — moved to [PlatformModule]. The "no Android imports"
 *            claim above is now true of every binding here rather than most of them.
 *
 * **Singletons, safely.** Every engine here is stateless and deterministic — fixed input, fixed
 * output (P-08) — so one instance shared across the app has nothing to reset between screens and
 * no way for one caller's use to affect another's.
 */
@Module
@InstallIn(SingletonComponent::class)
object EngineModule {
    /**
     * The quick-setup engine (issue 2.3; FR-ONB-002).
     * Result: a [QuickSetupEngine]. Input: none. Output: the engine.
     * Changelog: 2026-07-27 — Created for issue 2.3, in CoreModule.
     *            2026-08-01 — Issue 2.6: moved here.
     */
    @Provides
    @Singleton
    fun provideQuickSetupEngine(): QuickSetupEngine = QuickSetupEngineFactory.create()

    /**
     * The net-worth engine (issue 2.6; FR-ACC-005).
     * Why:    the one place `assets − liabilities` is computed. The repository hands it balances and
     *         stores what it returns; the dashboard renders what was stored. Neither adds up a rupee
     *         of its own (P-03).
     * Result: a [NetWorthEngine]. Input: none. Output: the engine.
     * Changelog: 2026-08-01 — Created for issue 2.6.
     */
    @Provides
    @Singleton
    fun provideNetWorthEngine(): NetWorthEngine = NetWorthEngineFactory.create()

    /**
     * The recurring detector (issue 3.7; FR-TXN-006).
     * Why:    the one place the ledger is searched for a repeating pattern. It proposes and the user
     *         decides (P-07); the repository writes only what they confirmed, and computes nothing
     *         of its own (P-03).
     * Result: a [RecurringEngine]. Input: none. Output: the engine.
     * Changelog: 2026-08-05 — Created for issue 3.7.
     */
    @Provides
    @Singleton
    fun provideRecurringEngine(): RecurringEngine = RecurringEngineFactory.create()

    /**
     * The receipt parser (issue 3.8; FR-OCR-003, §18.1).
     * Why:    the one place recognised text becomes an amount, a date and a merchant. It reads text
     *         someone else recognised and computes every figure itself (P-03); the recogniser above
     *         it is Android-only and lives in `:ml:ocr`, which is why the two are separate bindings.
     * Result: a [ReceiptEngine]. Input: none. Output: the engine.
     * Changelog: 2026-08-06 — Created for issue 3.8.
     */
    @Provides
    @Singleton
    fun provideReceiptEngine(): ReceiptEngine = ReceiptEngineFactory.create()

    /**
     * The bank-alert parser (issue 3.9; §18, §23, P-03).
     * Why:    the one place a message becomes an amount and a direction. Pure Kotlin, so it holds no
     *         permission and touches no inbox — the reader below it is the Android half, which is
     *         why the two are separate bindings, exactly as the receipt pair above is.
     * Result: an [SmsEngine]. Input: none. Output: the engine.
     * Changelog: 2026-08-07 — Created for issue 3.9.
     */
    @Provides
    @Singleton
    fun provideSmsEngine(): SmsEngine = SmsEngineFactory.create()

    /**
     * The Stage-1 categoriser (issue 4.2; §8.1, P-02, P-03).
     * Why:    the one place a merchant becomes a category. Pure Kotlin, so it holds no database and
     *         reads no clock — `TransactionRepository` fetches the correction history and the live
     *         taxonomy and hands both in, which is what keeps the decision testable apart from the
     *         data (ARC-005).
     *
     *         **A singleton because the rule set is built once.** `ClassificationRules` validates
     *         thirteen rows and splits their literals at construction, and the add screen asks for a
     *         suggestion on every pause in typing.
     * Result: a [ClassificationEngine]. Input: none. Output: the engine.
     * Changelog: 2026-08-10 — Created for issue 4.2.
     */
    @Provides
    @Singleton
    fun provideClassificationEngine(): ClassificationEngine = ClassificationEngineFactory.create()

    /**
     * The nature classifier (issue 4.3; §8.3, AI-CLS-N, P-02).
     * Why:    the one place a transaction becomes a Need, a Want, an investment, an asset or debt
     *         service — the axis §8.3 says the whole advice layer is built on. Pure Kotlin, so it
     *         holds no database and reads no clock; `TransactionRepository` makes the five joins
     *         §8.3.1 branches on and hands the result in (ARC-005).
     *
     *         **A singleton because the rule set is validated once.** `NatureRules` checks eight
     *         thresholds against each other at construction, and the dashboard classifies a whole
     *         month on every emission.
     * Result: a [NatureEngine]. Input: none. Output: the engine.
     * Changelog: 2026-08-10 — Created for issue 4.3.
     */
    @Provides
    @Singleton
    fun provideNatureEngine(): NatureEngine = NatureEngineFactory.create()

    /**
     * What a category should cost, and whether the month is on track (issue 4.4; §5.5, FR-BUD-002/003).
     * Why:    provided through its factory like every engine here, because the implementation is
     *         `internal` to its module and `:app` cannot name it (ARC-003). Pure Kotlin, so it holds
     *         no database and reads no clock — `BudgetRepository` resolves the month in the profile
     *         zone and hands the engine two day counts (ARC-005, TIM-001).
     *
     *         **A singleton because the rule set is validated once.** `BudgetRules` checks its
     *         thresholds against each other at construction, and the budget screen recomputes every
     *         category's status on every emission.
     * Result: a [BudgetEngine]. Input: none. Output: the engine.
     * Changelog: 2026-08-11 — Created for issue 4.4.
     */
    @Provides
    @Singleton
    fun provideBudgetEngine(): BudgetEngine = BudgetEngineFactory.create()
}
