package com.aicfo.app.di

import com.aicfo.core.common.Clock
import com.aicfo.core.common.DispatcherProvider
import com.aicfo.core.common.IdGenerator
import com.aicfo.core.crypto.ReceiptImageStore
import com.aicfo.core.database.CfoDatabase
import com.aicfo.core.datastore.ConsentStore
import com.aicfo.core.datastore.SettingsStore
import com.aicfo.data.repository.AccountRepository
import com.aicfo.data.repository.DemoModeRepository
import com.aicfo.data.repository.NetWorthRepository
import com.aicfo.data.repository.QuickSetupRepository
import com.aicfo.data.repository.ReceiptRepository
import com.aicfo.data.repository.RecurringRepository
import com.aicfo.data.repository.RepositoryFactory
import com.aicfo.data.repository.SmsRepository
import com.aicfo.data.repository.TransactionRepository
import com.aicfo.data.sms.SmsInboxReader
import com.aicfo.domain.engines.networth.NetWorthEngine
import com.aicfo.domain.engines.receipt.ReceiptEngine
import com.aicfo.domain.engines.recurring.RecurringEngine
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
 *
 * **Every binding here takes the gated [CfoDatabase], never `@AuditDatabase`.** These hold the
 * user's financial data, which is exactly what the app lock exists to gate; the audit log's
 * exemption is for security events written *while* locked, and nothing here has that excuse.
 */
@Module
@InstallIn(SingletonComponent::class)
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
     *         (P-08), [dispatchers], [demoMode]. Output: the repository.
     * Changelog: 2026-08-02 — Created for issue 3.1.
     */
    @Provides
    @Singleton
    fun provideTransactionRepository(
        database: CfoDatabase,
        clock: Clock,
        ids: IdGenerator,
        dispatchers: DispatcherProvider,
        demoMode: DemoModeRepository,
    ): TransactionRepository =
        RepositoryFactory.transactions(database, clock, ids, dispatchers, demoMode.activeProfileId)

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
