package com.aicfo.app.di

import com.aicfo.core.common.Clock
import com.aicfo.core.common.DispatcherProvider
import com.aicfo.core.common.IdGenerator
import com.aicfo.core.crypto.BackupCipherFactory
import com.aicfo.core.crypto.ReceiptImageStore
import com.aicfo.core.database.CfoDatabase
import com.aicfo.core.datastore.ConsentStore
import com.aicfo.core.datastore.SettingsStore
import com.aicfo.data.repository.AccountRepository
import com.aicfo.data.repository.ArchiveRepository
import com.aicfo.data.repository.AuditLogRepository
import com.aicfo.data.repository.BackupRepository
import com.aicfo.data.repository.BudgetRepository
import com.aicfo.data.repository.CategoryRepository
import com.aicfo.data.repository.CreditCardRepository
import com.aicfo.data.repository.DemoModeRepository
import com.aicfo.data.repository.EmergencyFundRepository
import com.aicfo.data.repository.ForecastRepository
import com.aicfo.data.repository.GoalContributionRepository
import com.aicfo.data.repository.GoalRepository
import com.aicfo.data.repository.GoalWaterfallRepository
import com.aicfo.data.repository.HealthScoreRepository
import com.aicfo.data.repository.InsightRepository
import com.aicfo.data.repository.InvestmentRepository
import com.aicfo.data.repository.LoanRepository
import com.aicfo.data.repository.NetWorthRepository
import com.aicfo.data.repository.OrderOfOperationsRepository
import com.aicfo.data.repository.QuickSetupRepository
import com.aicfo.data.repository.ReceiptRepository
import com.aicfo.data.repository.RecurringRepository
import com.aicfo.data.repository.RepositoryFactory
import com.aicfo.data.repository.SafeToSpendRepository
import com.aicfo.data.repository.SmsRepository
import com.aicfo.data.repository.StreamRepository
import com.aicfo.data.repository.SurplusRepository
import com.aicfo.data.repository.TransactionRepository
import com.aicfo.data.sms.SmsInboxReader
import com.aicfo.domain.engines.budget.BudgetEngine
import com.aicfo.domain.engines.card.CardEngine
import com.aicfo.domain.engines.classification.ClassificationEngine
import com.aicfo.domain.engines.emergencyfund.EmergencyFundEngine
import com.aicfo.domain.engines.forecast.ForecastEngine
import com.aicfo.domain.engines.forecast.ForecastEngineFactory
import com.aicfo.domain.engines.goals.GoalEngine
import com.aicfo.domain.engines.goals.GoalWaterfallEngine
import com.aicfo.domain.engines.healthscore.HealthScoreEngine
import com.aicfo.domain.engines.healthscore.HealthScoreEngineFactory
import com.aicfo.domain.engines.insight.InsightEngine
import com.aicfo.domain.engines.insight.InsightEngineFactory
import com.aicfo.domain.engines.investment.InvestmentEngine
import com.aicfo.domain.engines.loan.LoanEngine
import com.aicfo.domain.engines.nature.NatureEngine
import com.aicfo.domain.engines.networth.NetWorthEngine
import com.aicfo.domain.engines.orderofoperations.OrderOfOperationsEngine
import com.aicfo.domain.engines.receipt.ReceiptEngine
import com.aicfo.domain.engines.recurring.RecurringEngine
import com.aicfo.domain.engines.safetospend.SafeToSpendEngine
import com.aicfo.domain.engines.seasonality.SeasonalityEngine
import com.aicfo.domain.engines.seasonality.SeasonalityEngineFactory
import com.aicfo.domain.engines.sms.SmsEngine
import com.aicfo.domain.engines.stream.StreamEngine
import com.aicfo.domain.engines.stream.StreamEngineFactory
import com.aicfo.ml.ocr.ReceiptTextRecognizer
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * The `:data:repository` bindings (ARC-003, ARC-005, SEC-002).
 *
 * Why:  split out of [CoreModule] when that object reached detekt's `TooManyFunctions` ceiling. The
 *       seam is a real one rather than an arbitrary cut: everything left in [CoreModule] is a
 *       platform primitive — a clock, a dispatcher, a database handle — while everything here is a
 *       repository, the only kind of class allowed to touch a DAO (ARC-005). A reviewer asking
 *       "what can read the user's data?" now has one file to read.
 * What: one `@Provides` per repository, each built through `RepositoryFactory` because the
 *       implementations are `internal` to their module (ARC-003).
 * Result: features inject an interface and never name an implementation.
 * Changelog: 2026-07-28 — Created for issue 2.4.
 *            2026-08-16 — Issue 5.2's binding took this object past detekt's `TooManyFunctions`
 *            ceiling; suppressed rather than split, for the reason below.
 *
 * **Every binding here takes the gated [CfoDatabase], never `@AuditDatabase`.** These hold the
 * user's financial data, which is exactly what the app lock exists to gate; the audit log's
 * exemption is for security events written *while* locked, and nothing here has that excuse.
 *
 * **Past `TooManyFunctions`, deliberately.** The count here is simply the number of repositories the
 * app has, and splitting the object would put half of them behind one name and half behind another
 * with no rule for which goes where — the argument `RepositoryFactory` and [CfoDatabase] already
 * make for the same suppression. The seam that *would* be real, [CoreModule]'s platform primitives
 * versus these data readers, has already been cut.
 */
@Module
@InstallIn(SingletonComponent::class)
@Suppress("TooManyFunctions") // One binding per repository (ARC-003) — see the note above.
object RepositoryModule {
    /**
     * The quick-setup store (issue 2.3; FR-ONB-002).
     * Why:    takes its active profile from [DemoModeRepository], which is what makes every
     *         no-argument read follow the demo without the reading screen knowing demo mode exists
     *         (issue 2.4). The dependency runs demo → quick setup and never back.
     * Result: a [QuickSetupRepository]. Input: [database], [clock], [dispatchers], [demoMode].
     * Output: the repository.
     * Changelog: 2026-07-27 — Created for issue 2.3, in CoreModule.
     *            2026-07-28 — Issue 2.4: moved here, and given the active profile.
     */
    @Provides
    @Singleton
    fun provideQuickSetupRepository(
        database: CfoDatabase,
        clock: Clock,
        dispatchers: DispatcherProvider,
        demoMode: DemoModeRepository,
    ): QuickSetupRepository = RepositoryFactory.quickSetup(database, clock, dispatchers, demoMode.activeProfileId)

    /**
     * Demo mode (issue 2.4; FR-ONB-004).
     * Why:    it both seeds and erases rows in the shared tables, so it goes through the same lock
     *         gate as everything else — a wipe path reachable before the first unlock would be a
     *         delete outside the security perimeter.
     * Result: a [DemoModeRepository]. Input: [database], [settingsStore] — holds the flag; [clock];
     *         [dispatchers]. Output: the repository.
     * Changelog: 2026-07-28 — Created for issue 2.4.
     */
    @Provides
    @Singleton
    fun provideDemoModeRepository(
        database: CfoDatabase,
        settingsStore: SettingsStore,
        clock: Clock,
        dispatchers: DispatcherProvider,
    ): DemoModeRepository = RepositoryFactory.demoMode(database, settingsStore, clock, dispatchers)

    /**
     * The accounts store (issue 2.5; FR-ACC-001, FR-ACC-007).
     * Why:    takes its active profile from [DemoModeRepository] for the same reason quick setup
     *         does — the accounts list must show the demo's four sample accounts while a demo is
     *         loaded, and the user's own the moment it is left, without the screen knowing either.
     * Result: an [AccountRepository]. Input: [database], [clock], [ids] — the injected id source
     *         (P-08), [dispatchers], [demoMode]. Output: the repository.
     * Changelog: 2026-07-28 — Created for issue 2.5.
     */
    @Provides
    @Singleton
    fun provideAccountRepository(
        database: CfoDatabase,
        clock: Clock,
        ids: IdGenerator,
        dispatchers: DispatcherProvider,
        demoMode: DemoModeRepository,
    ): AccountRepository = RepositoryFactory.accounts(database, clock, ids, dispatchers, demoMode.activeProfileId)

    /**
     * The net-worth store (issue 2.6; FR-ACC-005).
     * Why:    takes the engine rather than building one, so the figure and the code that produced it
     *         are assembled in the graph rather than by the repository (ARC-003, P-03). Follows the
     *         demo like everything else here: exploring the sample data shows the sample net worth.
     * Result: a [NetWorthRepository]. Input: [database], [engine], [clock], [dispatchers], [demoMode].
     * Output: the repository.
     * Changelog: 2026-08-01 — Created for issue 2.6.
     */
    @Provides
    @Singleton
    fun provideNetWorthRepository(
        database: CfoDatabase,
        engine: NetWorthEngine,
        clock: Clock,
        dispatchers: DispatcherProvider,
        demoMode: DemoModeRepository,
    ): NetWorthRepository = RepositoryFactory.netWorth(database, engine, clock, dispatchers, demoMode.activeProfileId)

    /**
     * The transactions store (issue 3.1; FR-TXN-002, FR-TXN-009).
     * Why:    follows the demo like every binding above it, so a transaction added while exploring
     *         the sample data lands under the demo profile and leaves with it (ADR-0006).
     * Result: a [TransactionRepository]. Input: [database], [clock], [ids] — the injected id source
     *         (P-08), [dispatchers], [demoMode], [classifier] — issue 4.2's Stage-1 categoriser,
     *         bound beside the store that feeds it its history.
     * Output: the repository.
     * Changelog: 2026-08-02 — Created for issue 3.1.
     *            2026-08-10 — Issue 4.2: gained the classifier, so `suggestCategory` has an engine.
     */
    @Provides
    @Singleton
    @Suppress("LongParameterList")
    fun provideTransactionRepository(
        database: CfoDatabase,
        clock: Clock,
        ids: IdGenerator,
        dispatchers: DispatcherProvider,
        demoMode: DemoModeRepository,
        classifier: ClassificationEngine,
        natureEngine: NatureEngine,
    ): TransactionRepository =
        RepositoryFactory.transactions(
            database,
            clock,
            ids,
            dispatchers,
            demoMode.activeProfileId,
            classifier,
            natureEngine,
        )

    /**
     * The category taxonomy store (issue 4.1; FR-SET-001, AI-CLSN-001).
     * Why:    follows the demo like every binding above it, so the sample profile keeps the twelve
     *         categories `DemoDataset` writes and never gets the fifteen seeded defaults on top
     *         (ADR-0006).
     * Result: a [CategoryRepository]. Input: [database], [clock], [ids] — the injected id source
     *         (P-08), [dispatchers], [demoMode]. Output: the repository.
     * Changelog: 2026-08-08 — Created for issue 4.1.
     */
    @Provides
    @Singleton
    fun provideCategoryRepository(
        database: CfoDatabase,
        clock: Clock,
        ids: IdGenerator,
        dispatchers: DispatcherProvider,
        demoMode: DemoModeRepository,
    ): CategoryRepository = RepositoryFactory.categories(database, clock, ids, dispatchers, demoMode.activeProfileId)

    /**
     * The per-category budget store (issue 4.4; FR-BUD-001/002/003).
     * Why:    takes the engine rather than building one, for the same reason the net-worth and
     *         recurring bindings do (ARC-003, P-03). Follows the demo like every binding above it,
     *         so the sample profile's budgets and suggestions leave with it (ADR-0006).
     *
     *         **No `IdGenerator`**, unlike the category binding above: a budget's id is derived from
     *         the profile, category and period, so re-saving updates one row instead of minting a
     *         second (P-08).
     * Result: a [BudgetRepository]. Input: [database], [engine], [clock], [dispatchers], [demoMode].
     *         Output: the repository.
     * Changelog: 2026-08-11 — Created for issue 4.4.
     */
    @Provides
    @Singleton
    fun provideBudgetRepository(
        database: CfoDatabase,
        engine: BudgetEngine,
        clock: Clock,
        dispatchers: DispatcherProvider,
        demoMode: DemoModeRepository,
    ): BudgetRepository = RepositoryFactory.budgets(database, engine, clock, dispatchers, demoMode.activeProfileId)

    /**
     * The credit-card store (issue 6.1; §5.7, FR-ACC-002).
     * Why:    takes the gated [CfoDatabase] like every binding here, so a card's limit is no more
     *         readable before an unlock than a transaction is. Follows the demo (ADR-0006), so the
     *         sample dataset can carry its own cards without touching the real profile's.
     * Result: a [CreditCardRepository]. Input: the graph's shared dependencies.
     * Output: the repository.
     * Changelog: 2026-08-17 — Created for issue 6.1.
     */
    @Provides
    @Singleton
    @Suppress("LongParameterList") // Hilt reads the signature; each argument is one binding.
    fun provideCreditCardRepository(
        database: CfoDatabase,
        engine: CardEngine,
        clock: Clock,
        ids: IdGenerator,
        dispatchers: DispatcherProvider,
        demoMode: DemoModeRepository,
    ): CreditCardRepository =
        RepositoryFactory.creditCards(database, engine, clock, ids, dispatchers, demoMode.activeProfileId)

    /**
     * The loan store (issue 6.2; §5.8, FR-ACC-003).
     * Why:    takes the gated [CfoDatabase] like every binding here, so a loan's principal is no more
     *         readable before an unlock than a transaction is. Follows the demo (ADR-0006), so the
     *         sample dataset can carry its own loans without touching the real profile's. **No
     *         `IdGenerator`**, unlike [provideCreditCardRepository]: a loan is keyed by its account
     *         and mints no rows of its own.
     * Result: a [LoanRepository]. Input: the graph's shared dependencies. Output: the repository.
     * Changelog: 2026-08-19 — Created for issue 6.2.
     */
    @Provides
    @Singleton
    fun provideLoanRepository(
        database: CfoDatabase,
        engine: LoanEngine,
        clock: Clock,
        dispatchers: DispatcherProvider,
        demoMode: DemoModeRepository,
    ): LoanRepository = RepositoryFactory.loans(database, engine, clock, dispatchers, demoMode.activeProfileId)

    /**
     * The holdings store (issue 6.3; §11, AI-INV).
     * Why:    takes the gated [CfoDatabase] like every binding here, so what a person owns is no
     *         more readable before an unlock than a transaction is. Follows the demo (ADR-0006), so
     *         the sample dataset can carry its own holdings without touching the real profile's.
     *         Takes an [IdGenerator], unlike [provideLoanRepository]: holdings and lots are 1:N and
     *         mint their own keys.
     * Result: an [InvestmentRepository]. Input: the graph's shared dependencies. Output: the
     *         repository.
     * Changelog: 2026-08-24 — Created for issue 6.3.
     */
    @Provides
    @Singleton
    @Suppress("LongParameterList") // Hilt reads the signature; each argument is one binding.
    fun provideInvestmentRepository(
        database: CfoDatabase,
        engine: InvestmentEngine,
        clock: Clock,
        ids: IdGenerator,
        dispatchers: DispatcherProvider,
        demoMode: DemoModeRepository,
    ): InvestmentRepository =
        RepositoryFactory.investments(database, engine, clock, ids, dispatchers, demoMode.activeProfileId)

    /**
     * The goals store (issue 7.1; §15, AI-GOAL).
     * Why:    takes the gated [CfoDatabase] like every binding here, so a locked app cannot read a
     *         goal (SEC-002). It runs the engine on the way out rather than storing a required
     *         monthly, so the figure can never outlive the goal that produced it.
     * Result: a [GoalRepository].
     * Input:  [database]; [engine]; [clock]; [ids]; [dispatchers]; [demoMode].
     * Output: [GoalRepository].
     * Changelog: 2026-08-30 — Created for issue 7.1.
     */
    @Provides
    @Singleton
    @Suppress("LongParameterList") // Hilt reads the signature; each argument is one binding.
    fun provideGoalRepository(
        database: CfoDatabase,
        engine: GoalEngine,
        clock: Clock,
        ids: IdGenerator,
        dispatchers: DispatcherProvider,
        demoMode: DemoModeRepository,
    ): GoalRepository = RepositoryFactory.goals(database, engine, clock, ids, dispatchers, demoMode.activeProfileId)

    /**
     * The links that make goal progress evidence rather than a claim (issue 7.4; FR-GOAL-004).
     * Why:    a binding of its own because it owns different tables from [provideGoalRepository] —
     *         `goal_contribution` and `goal_funding_account` — and takes the transaction and account
     *         repositories rather than their DAOs, so the ledger keeps one owner (ARC-005).
     * Result: a [GoalContributionRepository].
     * Input:  [database]; [transactions]; [accounts]; [clock]; [ids]; [dispatchers]; [demoMode].
     * Output: [GoalContributionRepository].
     * Changelog: 2026-09-06 — Created for issue 7.4.
     */
    @Provides
    @Singleton
    @Suppress("LongParameterList") // Hilt reads the signature; each argument is one binding.
    fun provideGoalContributionRepository(
        database: CfoDatabase,
        transactions: TransactionRepository,
        accounts: AccountRepository,
        clock: Clock,
        ids: IdGenerator,
        dispatchers: DispatcherProvider,
        demoMode: DemoModeRepository,
    ): GoalContributionRepository =
        RepositoryFactory.goalContributions(
            database = database,
            transactions = transactions,
            accounts = accounts,
            clock = clock,
            ids = ids,
            dispatchers = dispatchers,
            activeProfileId = demoMode.activeProfileId,
        )

    /**
     * The emergency-fund assessment (issue 7.2; §10.1, AI-EMF).
     * Why:    **built from three repositories rather than from the database**, which is why it
     *         takes no [CfoDatabase] of its own: the classified ledger, the balances and the
     *         onboarding envelopes each already have an owner, and every one of those owners is
     *         already gated on the locked database (SEC-002), so this inherits the gate rather
     *         than opening a second door to it.
     * Result: an [EmergencyFundRepository].
     * Input:  [transactions]; [accounts]; [quickSetup]; [engine]; [clock]; [dispatchers].
     * Output: [EmergencyFundRepository].
     * Changelog: 2026-09-02 — Created for issue 7.2.
     */
    @Provides
    @Singleton
    @Suppress("LongParameterList") // Hilt reads the signature; each argument is one binding.
    fun provideEmergencyFundRepository(
        transactions: TransactionRepository,
        accounts: AccountRepository,
        quickSetup: QuickSetupRepository,
        engine: EmergencyFundEngine,
        clock: Clock,
        dispatchers: DispatcherProvider,
    ): EmergencyFundRepository =
        RepositoryFactory.emergencyFund(transactions, accounts, quickSetup, engine, clock, dispatchers)

    /**
     * The goal-feasibility and contribution plan (issue 7.3; §15.1, FR-GOAL-003/005).
     * Why:    **built from three repositories rather than from the database**, like
     *         [provideEmergencyFundRepository] and for the same reasons plus one: the goals are
     *         already projected and the runway is already resolved, so recomputing either here
     *         would give the app two answers to one question. It inherits the SEC-002 gate through
     *         its sources rather than opening a second door to the database.
     * Result: a [GoalWaterfallRepository].
     * Input:  [goals]; [transactions]; [emergencyFund]; [quickSetup]; [engine]; [clock];
     *         [dispatchers].
     * Output: [GoalWaterfallRepository].
     * Changelog: 2026-09-03 — Created for issue 7.3.
     */
    @Provides
    @Singleton
    @Suppress("LongParameterList") // Hilt reads the signature; each argument is one binding.
    fun provideGoalWaterfallRepository(
        goals: GoalRepository,
        ranking: OrderOfOperationsRepository,
        emergencyFund: EmergencyFundRepository,
        engine: GoalWaterfallEngine,
        clock: Clock,
        dispatchers: DispatcherProvider,
    ): GoalWaterfallRepository =
        RepositoryFactory.goalWaterfall(
            goals = goals,
            ranking = ranking,
            emergencyFund = emergencyFund,
            engine = engine,
            clock = clock,
            dispatchers = dispatchers,
        )

    /**
     * The month's surplus, shared by §36's ranking and §15.1's goal split (extracted 2026-09-18).
     * Why:    whichever of the two derived it would be depended on by the other; extracting it lets
     *         AI-FOO be the base and the goal waterfall consume its remainder (ADR-0038).
     * Result: a [SurplusRepository]. Input: the graph's shared dependencies. Output: the repository.
     * Changelog: 2026-09-18 — Created.
     */
    @Provides
    @Singleton
    fun provideSurplusRepository(
        transactions: TransactionRepository,
        quickSetup: QuickSetupRepository,
        dispatchers: DispatcherProvider,
    ): SurplusRepository = RepositoryFactory.surplus(transactions, quickSetup, dispatchers)

    /**
     * The Financial Order of Operations (issue 7.5; §36, AI-FOO).
     * Why:    built on [provideSurplusRepository], [provideGoalRepository] and
     *         [provideEmergencyFundRepository] — resolving any of those again would give the dashboard
     *         and the goals screen two answers to one question. **This binding is the base of the
     *         pair**: [provideGoalWaterfallRepository] consumes what this ranking leaves (ADR-0038).
     *         Takes the gated [CfoDatabase] only for what nobody resolved before, each debt's rate,
     *         so a card's APR is no more readable before
     *         an unlock than its balance is (SEC-002). Follows the demo (ADR-0006) like every
     *         profile-scoped binding here.
     * Result: an [OrderOfOperationsRepository].
     * Input:  [database]; [waterfall]; [emergencyFund]; [engine]; [clock]; [dispatchers]; [demoMode].
     * Output: [OrderOfOperationsRepository].
     * Changelog: 2026-09-17 — Created for issue 7.5.
     */
    @Provides
    @Singleton
    @Suppress("LongParameterList") // Hilt reads the signature; each argument is one binding.
    fun provideOrderOfOperationsRepository(
        database: CfoDatabase,
        goals: GoalRepository,
        surplus: SurplusRepository,
        emergencyFund: EmergencyFundRepository,
        engine: OrderOfOperationsEngine,
        clock: Clock,
        dispatchers: DispatcherProvider,
        demoMode: DemoModeRepository,
    ): OrderOfOperationsRepository =
        RepositoryFactory.orderOfOperations(
            database = database,
            goals = goals,
            surplus = surplus,
            emergencyFund = emergencyFund,
            engine = engine,
            clock = clock,
            dispatchers = dispatchers,
            activeProfileId = demoMode.activeProfileId,
        )

    /**
     * The export/import archive store (issue 5.4; §5.10, §34, P-01).
     * Why:    takes the gated [CfoDatabase] like every binding here — an archive is *all* of the
     *         user's financial data at once, so it is the last thing that should be readable before
     *         an unlock. Follows the demo (ADR-0006), so exporting inside the demo writes the sample
     *         data and importing there cannot reach the real profile.
     * Result: an [ArchiveRepository]. Input: [database], [clock], [dispatchers], [demoMode].
     *         Output: the repository.
     * Changelog: 2026-08-16 — Created for issue 5.4.
     */
    @Provides
    @Singleton
    fun provideArchiveRepository(
        database: CfoDatabase,
        clock: Clock,
        dispatchers: DispatcherProvider,
        demoMode: DemoModeRepository,
    ): ArchiveRepository = RepositoryFactory.archive(database, clock, dispatchers, demoMode.activeProfileId)

    /**
     * AI-CLS Stage 2 (issue 9.1; §8.2).
     * Why:    stateless and pure — it reads no clock and holds no threshold beyond its typed
     *         knowledge-base mirror — so one instance serves every caller (ARC-003).
     * Result: a [StreamEngine]. Input: none. Output: the engine.
     * Changelog: 2026-09-19 — Created for issue 9.1.
     */
    @Provides
    @Singleton
    fun provideStreamEngine(): StreamEngine = StreamEngineFactory.create()

    /**
     * The profile's expense streams, classified (issue 9.1; §8.2).
     * Why:    reads the gated [CfoDatabase] like every binding here, and follows the demo
     *         (ADR-0006), so the sample household's rent is never classified as the user's.
     * Result: a [StreamRepository]. Input: [database]; [engine]; [clock]; [dispatchers]; [demoMode].
     * Output: the repository.
     * Changelog: 2026-09-19 — Created for issue 9.1.
     */
    @Provides
    @Singleton
    fun provideStreamRepository(
        database: CfoDatabase,
        engine: StreamEngine,
        clock: Clock,
        dispatchers: DispatcherProvider,
        demoMode: DemoModeRepository,
    ): StreamRepository = RepositoryFactory.streams(database, engine, clock, dispatchers, demoMode.activeProfileId)

    /**
     * AI-FCT (issue 9.2; §9).
     * Why:    stateless and pure, so one instance serves every caller (ARC-003).
     * Result: a [ForecastEngine]. Input: none. Output: the engine.
     * Changelog: 2026-09-19 — Created for issue 9.2.
     */
    @Provides
    @Singleton
    fun provideForecastEngine(): ForecastEngine = ForecastEngineFactory.create()

    /**
     * AI-SEAS, §9.3's seasonal index (issue 9.3).
     * Why:    pure and stateless, so one instance serves every forecast; built by its factory
     *         because the implementation is `internal` to its module (ARC-003).
     * Result: a [SeasonalityEngine]. Input: none. Output: the engine.
     * Changelog: 2026-09-19 — Created for issue 9.3.
     */
    @Provides
    @Singleton
    fun provideSeasonalityEngine(): SeasonalityEngine = SeasonalityEngineFactory.create()

    /**
     * AI-FHS, §14's Financial Health Score (issue 9.4).
     * Why:    pure and stateless, so one instance serves every read; built by its factory because the
     *         implementation is `internal` to its module (ARC-003).
     * Result: a [HealthScoreEngine]. Input: none. Output: the engine.
     * Changelog: 2026-09-19 — Created for issue 9.4.
     */
    @Provides
    @Singleton
    fun provideHealthScoreEngine(): HealthScoreEngine = HealthScoreEngineFactory.create()

    /**
     * AI-ORCH, §7.2's Insight Orchestrator (issue 9.5).
     * Why:    pure and stateless, so one instance serves every refresh; built by its factory because
     *         the implementation is `internal` to its module (ARC-003).
     * Result: an [InsightEngine]. Input: none. Output: the engine.
     * Changelog: 2026-09-20 — Created for issue 9.5.
     */
    @Provides
    @Singleton
    fun provideInsightEngine(): InsightEngine = InsightEngineFactory.create()

    /**
     * The persisted insight feed (issue 9.5; §7.2, AI-ARC-005).
     * Why:    built over the repositories that publish the results it ranks, so the feed cannot
     *         disagree with the cards beside it; the only table it writes is its own.
     * Result: an [InsightRepository].
     * Input:  [database]; the five sources; [engine]; [clock]; [dispatchers]; [demoMode];
     *         [idGenerator]. Output: the repository.
     * Changelog: 2026-09-20 — Created for issue 9.5.
     */
    @Provides
    @Singleton
    @Suppress("LongParameterList") // the store, five sources, the engine and three seams
    fun provideInsightRepository(
        database: CfoDatabase,
        forecasts: ForecastRepository,
        health: HealthScoreRepository,
        emergencyFund: EmergencyFundRepository,
        budgets: BudgetRepository,
        goals: GoalRepository,
        engine: InsightEngine,
        clock: Clock,
        dispatchers: DispatcherProvider,
        demoMode: DemoModeRepository,
        idGenerator: IdGenerator,
    ): InsightRepository =
        RepositoryFactory.insights(
            database = database,
            forecasts = forecasts,
            health = health,
            emergencyFund = emergencyFund,
            budgets = budgets,
            goals = goals,
            engine = engine,
            clock = clock,
            dispatchers = dispatchers,
            activeProfileId = demoMode.activeProfileId,
            idGenerator = idGenerator,
        )

    /**
     * The Financial Health Score (issue 9.4; §14).
     * Why:    built over the repositories that own its signals — each already follows the demo
     *         (ADR-0006) — so the score cannot disagree with the emergency fund, card, budget or goal
     *         screens it summarises (ADR-0045).
     * Result: a [HealthScoreRepository].
     * Input:  the seven owning repositories; [engine]; [clock]; [dispatchers]. Output: the repository.
     * Changelog: 2026-09-19 — Created for issue 9.4.
     */
    @Provides
    @Singleton
    @Suppress("LongParameterList") // ten: seven sources, the engine, the clock, the dispatchers
    fun provideHealthScoreRepository(
        emergencyFund: EmergencyFundRepository,
        transactions: TransactionRepository,
        streams: StreamRepository,
        loans: LoanRepository,
        cards: CreditCardRepository,
        budgets: BudgetRepository,
        goals: GoalRepository,
        engine: HealthScoreEngine,
        clock: Clock,
        dispatchers: DispatcherProvider,
    ): HealthScoreRepository =
        RepositoryFactory.healthScore(
            emergencyFund,
            transactions,
            streams,
            loans,
            cards,
            budgets,
            goals,
            engine,
            clock,
            dispatchers,
        )

    /**
     * The 90-day cash-flow forecast (issue 9.2; §9).
     * Why:    built over the account and stream repositories, so the forecast's opening balance is
     *         the accounts screen's and its FIXED streams are the dashboard's (ADR-0007, ADR-0043);
     *         follows the demo (ADR-0006).
     * Result: a [ForecastRepository].
     * Input:  [database]; [accounts]; [streams]; [engine]; [seasonality]; [clock]; [dispatchers];
     *         [demoMode].
     * Output: the repository.
     * Changelog: 2026-09-19 — Created for issue 9.2.
     *            2026-09-19 — [seasonality] added for issue 9.3.
     */
    @Provides
    @Singleton
    @Suppress("LongParameterList") // eight, each one source the forecast reads
    fun provideForecastRepository(
        database: CfoDatabase,
        accounts: AccountRepository,
        streams: StreamRepository,
        engine: ForecastEngine,
        seasonality: SeasonalityEngine,
        clock: Clock,
        dispatchers: DispatcherProvider,
        demoMode: DemoModeRepository,
    ): ForecastRepository =
        RepositoryFactory.forecast(
            database,
            accounts,
            streams,
            engine,
            seasonality,
            clock,
            dispatchers,
            demoMode.activeProfileId,
        )

    /**
     * The end-to-end-encrypted backup store (issue 8.1; SEC-005, §23.3, P-01).
     * Why:    built over the [ArchiveRepository] binding rather than the database, so a backup
     *         inherits everything the archive already guarantees — the unlock gate, and following
     *         the demo (ADR-0006) — and can never carry different rows from an export. The cipher
     *         needs no Android context, because its key is derived from the passphrase, never stored.
     * Result: a [BackupRepository].
     * Input:  [archive]; [consents] — the P-01 gate; [audit] — the security log; [dispatchers].
     * Output: the repository.
     * Changelog: 2026-09-18 — Created for issue 8.1.
     */
    @Provides
    @Singleton
    fun provideBackupRepository(
        archive: ArchiveRepository,
        consents: ConsentStore,
        audit: AuditLogRepository,
        dispatchers: DispatcherProvider,
    ): BackupRepository =
        RepositoryFactory.backup(
            archive = archive,
            cipher = BackupCipherFactory.create(),
            consents = consents,
            audit = audit,
            dispatchers = dispatchers,
        )

    /**
     * The Safe-to-Spend store (issue 5.2; §5.2, §14, AI-STS).
     * Why:    takes the engine rather than building one, for the same reason every binding above it
     *         does (ARC-003, P-03). It also takes two **repositories** — the seam the receipt and
     *         SMS bindings already use — because three of `RULE-STS`'s five terms are reads those
     *         repositories already own, and a second definition of "what this month's money became"
     *         is the one thing that would make the headline figure disagree with the section under
     *         it. Follows the demo like every binding above it (ADR-0006).
     * Result: a [SafeToSpendRepository]. Input: [database], [transactions], [quickSetup], [engine],
     *         [clock], [dispatchers], [demoMode]. Output: the repository.
     * Changelog: 2026-08-16 — Created for issue 5.2.
     */
    @Provides
    @Singleton
    @Suppress("LongParameterList") // One argument per collaborator; see RepositoryFactory.safeToSpend.
    fun provideSafeToSpendRepository(
        database: CfoDatabase,
        transactions: TransactionRepository,
        quickSetup: QuickSetupRepository,
        // Issue 7.1: the real goal-contributions term, replacing ADR-0021's INVEST-envelope stand-in.
        goals: GoalRepository,
        engine: SafeToSpendEngine,
        clock: Clock,
        dispatchers: DispatcherProvider,
        demoMode: DemoModeRepository,
    ): SafeToSpendRepository =
        RepositoryFactory.safeToSpend(
            database = database,
            transactions = transactions,
            quickSetup = quickSetup,
            goals = goals,
            engine = engine,
            clock = clock,
            dispatchers = dispatchers,
            activeProfileId = demoMode.activeProfileId,
        )

    /**
     * The recurring-series store (issue 3.7; FR-TXN-006).
     * Why:    takes the engine rather than building one, for the same reason the net-worth binding
     *         does (ARC-003, P-03). Follows the demo like every binding above it, so the sample data
     *         proposes its own series and they leave with it (ADR-0006).
     * Result: a [RecurringRepository]. Input: [database], [engine], [clock], [dispatchers],
     *         [demoMode]. Output: the repository.
     * Changelog: 2026-08-05 — Created for issue 3.7.
     */
    @Provides
    @Singleton
    fun provideRecurringRepository(
        database: CfoDatabase,
        engine: RecurringEngine,
        clock: Clock,
        dispatchers: DispatcherProvider,
        demoMode: DemoModeRepository,
    ): RecurringRepository = RepositoryFactory.recurring(database, engine, clock, dispatchers, demoMode.activeProfileId)

    /**
     * The receipt pipeline (issue 3.8; FR-OCR-001..006).
     * Why:    takes [transactions] rather than writing the ledger row itself, so the stamping and
     *         validation rules FR-TXN-010 needs have one home. Follows the demo like every binding
     *         above it, so a scan taken in the demo leaves with it — including its encrypted image
     *         (ADR-0006).
     * Result: a [ReceiptRepository]. Input: [database], [transactions], [recognizer], [engine],
     *         [images], [clock], [ids], [dispatchers], [demoMode]. Output: the repository.
     * Changelog: 2026-08-06 — Created for issue 3.8.
     */
    @Provides
    @Singleton
    @Suppress("LongParameterList") // Hilt reads the signature; each argument is one binding.
    fun provideReceiptRepository(
        database: CfoDatabase,
        transactions: TransactionRepository,
        recognizer: ReceiptTextRecognizer,
        engine: ReceiptEngine,
        images: ReceiptImageStore,
        clock: Clock,
        ids: IdGenerator,
        dispatchers: DispatcherProvider,
        demoMode: DemoModeRepository,
    ): ReceiptRepository =
        RepositoryFactory.receipts(
            database = database,
            transactions = transactions,
            recognizer = recognizer,
            engine = engine,
            images = images,
            clock = clock,
            ids = ids,
            dispatchers = dispatchers,
            activeProfileId = demoMode.activeProfileId,
        )

    /**
     * The SMS pipeline (issue 3.9; §18, §23, P-01).
     * Why:    takes [transactions] rather than writing the ledger row itself, for the reason the
     *         receipt binding above gives. Follows the demo like every binding above it, so drafts
     *         parsed under the demo profile leave with it (ADR-0006) — which also means the demo
     *         cannot show a draft drawn from the real user's inbox.
     * Result: an [SmsRepository]. Input: [database], [transactions], [reader], [engine], [consents],
     *         [settings], [clock], [ids], [dispatchers], [demoMode]. Output: the repository.
     * Changelog: 2026-08-07 — Created for issue 3.9.
     */
    @Provides
    @Singleton
    @Suppress("LongParameterList") // Hilt reads the signature; each argument is one binding.
    fun provideSmsRepository(
        database: CfoDatabase,
        transactions: TransactionRepository,
        reader: SmsInboxReader,
        engine: SmsEngine,
        consents: ConsentStore,
        settings: SettingsStore,
        clock: Clock,
        ids: IdGenerator,
        dispatchers: DispatcherProvider,
        demoMode: DemoModeRepository,
    ): SmsRepository =
        RepositoryFactory.sms(
            database = database,
            transactions = transactions,
            reader = reader,
            engine = engine,
            consents = consents,
            settings = settings,
            clock = clock,
            ids = ids,
            dispatchers = dispatchers,
            activeProfileId = demoMode.activeProfileId,
        )
}
