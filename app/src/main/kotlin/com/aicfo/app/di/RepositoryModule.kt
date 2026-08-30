package com.aicfo.app.di

import com.aicfo.core.common.Clock
import com.aicfo.core.common.DispatcherProvider
import com.aicfo.core.common.IdGenerator
import com.aicfo.core.crypto.ReceiptImageStore
import com.aicfo.core.database.CfoDatabase
import com.aicfo.core.datastore.ConsentStore
import com.aicfo.core.datastore.SettingsStore
import com.aicfo.data.repository.AccountRepository
import com.aicfo.data.repository.ArchiveRepository
import com.aicfo.data.repository.BudgetRepository
import com.aicfo.data.repository.CategoryRepository
import com.aicfo.data.repository.CreditCardRepository
import com.aicfo.data.repository.DemoModeRepository
import com.aicfo.data.repository.GoalRepository
import com.aicfo.data.repository.InvestmentRepository
import com.aicfo.data.repository.LoanRepository
import com.aicfo.data.repository.NetWorthRepository
import com.aicfo.data.repository.QuickSetupRepository
import com.aicfo.data.repository.ReceiptRepository
import com.aicfo.data.repository.RecurringRepository
import com.aicfo.data.repository.RepositoryFactory
import com.aicfo.data.repository.SafeToSpendRepository
import com.aicfo.data.repository.SmsRepository
import com.aicfo.data.repository.TransactionRepository
import com.aicfo.data.sms.SmsInboxReader
import com.aicfo.domain.engines.budget.BudgetEngine
import com.aicfo.domain.engines.card.CardEngine
import com.aicfo.domain.engines.classification.ClassificationEngine
import com.aicfo.domain.engines.goals.GoalEngine
import com.aicfo.domain.engines.investment.InvestmentEngine
import com.aicfo.domain.engines.loan.LoanEngine
import com.aicfo.domain.engines.nature.NatureEngine
import com.aicfo.domain.engines.networth.NetWorthEngine
import com.aicfo.domain.engines.receipt.ReceiptEngine
import com.aicfo.domain.engines.recurring.RecurringEngine
import com.aicfo.domain.engines.safetospend.SafeToSpendEngine
import com.aicfo.domain.engines.sms.SmsEngine
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
        engine: SafeToSpendEngine,
        clock: Clock,
        dispatchers: DispatcherProvider,
        demoMode: DemoModeRepository,
    ): SafeToSpendRepository =
        RepositoryFactory.safeToSpend(
            database = database,
            transactions = transactions,
            quickSetup = quickSetup,
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
