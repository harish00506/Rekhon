package com.aicfo.data.repository

import com.aicfo.core.common.Clock
import com.aicfo.core.common.DispatcherProvider
import com.aicfo.core.common.IdGenerator
import com.aicfo.core.crypto.ReceiptImageStore
import com.aicfo.core.database.CfoDatabase
import com.aicfo.core.datastore.SettingsStore
import com.aicfo.domain.engines.networth.NetWorthEngine
import com.aicfo.domain.engines.receipt.ReceiptEngine
import com.aicfo.domain.engines.recurring.RecurringEngine
import com.aicfo.ml.ocr.ReceiptTextRecognizer
import kotlinx.coroutines.flow.Flow

/**
 * Assembles the repositories for the DI graph (issue 2.2; ARC-003, ARC-005).
 *
 * Why:  ARC-003 keeps repository implementations `internal`, so `:app`'s Hilt module cannot
 *       construct one directly — something inside this module has to. This is the same seam
 *       `CfoDatabaseFactory` and `CfoDataStoreFactory` already use, kept deliberately identical so
 *       there is one pattern in the codebase rather than three.
 * What: one factory function per repository, taking only what the implementation needs.
 * Result: `:app` binds interfaces without ever naming an implementation.
 * Changelog: 2026-07-26 — Created for issue 2.2, with the module's first repository.
 *
 * As `:data:repository` fills out (2.5 accounts, 3.x transactions), each new repository adds a
 * function here rather than becoming public.
 */
object RepositoryFactory {
    /**
     * Builds the security event log (§21.6).
     * Result: an [AuditLogRepository] over the encrypted database.
     * Input:  [database] — the open, encrypted database; [clock] — TIM-001; [dispatchers].
     * Output: [AuditLogRepository].
     */
    fun auditLog(
        database: CfoDatabase,
        clock: Clock,
        dispatchers: DispatcherProvider,
    ): AuditLogRepository = RoomAuditLogRepository(database.auditLogDao(), clock, dispatchers)

    /**
     * Builds the quick-setup store (issue 2.3, FR-ONB-002).
     * Why:    takes the whole [database] rather than DAOs, because its atomicity guarantee rests on
     *         `withTransaction`, which is a method on the database.
     * Result: a [QuickSetupRepository] over the encrypted database.
     * Input:  [database] — the open, encrypted database; [clock] — TIM-001; [dispatchers];
     *         [activeProfileId] — which profile the no-argument reads resolve to, so the dashboard
     *         follows the demo without knowing demo mode exists (issue 2.4).
     * Output: [QuickSetupRepository].
     */
    fun quickSetup(
        database: CfoDatabase,
        clock: Clock,
        dispatchers: DispatcherProvider,
        activeProfileId: Flow<String>,
    ): QuickSetupRepository = RoomQuickSetupRepository(database, clock, dispatchers, activeProfileId)

    /**
     * Builds the demo-mode store (issue 2.4, FR-ONB-004).
     * Why:    takes the whole [database] for the same reason quick setup does — both transitions are
     *         all-or-nothing, and `withTransaction` is a method on the database.
     * Result: a [DemoModeRepository] over the encrypted database.
     * Input:  [database]; [settingsStore] — holds the demo flag; [clock] — TIM-001; [dispatchers].
     * Output: [DemoModeRepository].
     */
    fun demoMode(
        database: CfoDatabase,
        settingsStore: SettingsStore,
        clock: Clock,
        dispatchers: DispatcherProvider,
    ): DemoModeRepository = RoomDemoModeRepository(database, settingsStore, clock, dispatchers)

    /**
     * Builds the accounts store (issue 2.5, FR-ACC-001).
     * Result: an [AccountRepository] over the encrypted database.
     * Input:  [database]; [clock] — TIM-001; [ids] — mints account ids from an injected source
     *         rather than `UUID.randomUUID()`, so the write stays reproducible in a test (P-08);
     *         [dispatchers]; [activeProfileId] — which profile the no-argument reads and every
     *         write resolve to, so the accounts list follows the demo without knowing it exists.
     * Output: [AccountRepository].
     */
    fun accounts(
        database: CfoDatabase,
        clock: Clock,
        ids: IdGenerator,
        dispatchers: DispatcherProvider,
        activeProfileId: Flow<String>,
    ): AccountRepository = RoomAccountRepository(database, clock, ids, dispatchers, activeProfileId)

    /**
     * Builds the net-worth store (issue 2.6, FR-ACC-005).
     * Why:    takes the whole [database] because the backfill writes several days in one
     *         `withTransaction`, and takes the [engine] rather than constructing one so the figure
     *         and the code that produced it stay assembled in the DI graph (ARC-003, P-03).
     * Result: a [NetWorthRepository] over the encrypted database.
     * Input:  [database]; [engine] — computes every figure; [clock] — TIM-001; [dispatchers];
     *         [activeProfileId] — which profile is read and snapshotted, so the demo gets its own.
     * Output: [NetWorthRepository].
     */
    fun netWorth(
        database: CfoDatabase,
        engine: NetWorthEngine,
        clock: Clock,
        dispatchers: DispatcherProvider,
        activeProfileId: Flow<String>,
    ): NetWorthRepository = RoomNetWorthRepository(database, engine, clock, dispatchers, activeProfileId)

    /**
     * Builds the transactions store (issue 3.1, FR-TXN-002).
     * Why:    takes the whole [database] like the four above it — `create` reads `account` and writes
     *         `transactions`, and the recent list reads `transactions` and `category`, so three DAOs
     *         are already in play and a DAO-per-argument signature would grow with every Epic-3 issue.
     * Result: a [TransactionRepository] over the encrypted database.
     * Input:  [database]; [clock] — TIM-001, and the profile-zone day the row is booked on (TIM-002);
     *         [ids] — mints transaction ids from an injected source rather than `UUID.randomUUID()`,
     *         so the write stays reproducible in a test (P-08); [dispatchers]; [activeProfileId] —
     *         which profile the reads resolve to, so the list follows the demo without knowing it
     *         exists.
     * Output: [TransactionRepository].
     */
    fun transactions(
        database: CfoDatabase,
        clock: Clock,
        ids: IdGenerator,
        dispatchers: DispatcherProvider,
        activeProfileId: Flow<String>,
    ): TransactionRepository = RoomTransactionRepository(database, clock, ids, dispatchers, activeProfileId)

    /**
     * Builds the recurring-series store (issue 3.7, FR-TXN-006).
     * Why:    takes the [engine] rather than constructing one, for the same reason [netWorth] does —
     *         the proposal and the code that produced it stay assembled in the DI graph (ARC-003,
     *         P-03) — and takes the whole [database] because the read spans `transactions` and
     *         `recurring_rule`.
     * Result: a [RecurringRepository] over the encrypted database.
     * Input:  [database]; [engine] — finds every pattern; [clock] — TIM-001, and the lookback
     *         window in the profile zone (TIM-002); [dispatchers]; [activeProfileId] — which
     *         profile is proposed to, so the demo gets its own. **No `IdGenerator`**: a rule's id
     *         is derived from the profile and the merchant, so confirming twice updates one row
     *         rather than minting two (see `recurringRuleId`).
     * Output: [RecurringRepository].
     */
    fun recurring(
        database: CfoDatabase,
        engine: RecurringEngine,
        clock: Clock,
        dispatchers: DispatcherProvider,
        activeProfileId: Flow<String>,
    ): RecurringRepository = RoomRecurringRepository(database, engine, clock, dispatchers, activeProfileId)

    /**
     * Builds the receipt pipeline (issue 3.8, FR-OCR-001..006).
     * Why:    takes the [recognizer], the [engine] and the [images] store rather than constructing
     *         any of them, for the same reason [recurring] does — each is behind its own factory and
     *         its implementation is `internal` to its module (ARC-003). It also takes
     *         [transactions], because writing a transaction is already that repository's job and a
     *         second copy of the stamping and validation rules would drift from the first.
     * Result: a [ReceiptRepository] over the encrypted database and the encrypted blob store.
     * Input:  [database]; [transactions] — writes the ledger row; [recognizer] — on-device OCR
     *         (FR-OCR-002); [engine] — the parser (P-03); [images] — FR-OCR-005's encrypted store;
     *         [clock] — TIM-001, and the parser's "today" in the profile zone (TIM-002); [ids] —
     *         mints attachment ids; [dispatchers]; [activeProfileId] — which profile is scanned
     *         into, so the demo gets its own receipts and loses them with it (ADR-0006).
     * Output: [ReceiptRepository].
     *
     * `@Suppress("LongParameterList")`: one argument per collaborator. See `RoomReceiptRepository` —
     * that class exists precisely to be the one place the four halves of FR-OCR meet.
     */
    @Suppress("LongParameterList")
    fun receipts(
        database: CfoDatabase,
        transactions: TransactionRepository,
        recognizer: ReceiptTextRecognizer,
        engine: ReceiptEngine,
        images: ReceiptImageStore,
        clock: Clock,
        ids: IdGenerator,
        dispatchers: DispatcherProvider,
        activeProfileId: Flow<String>,
    ): ReceiptRepository =
        RoomReceiptRepository(
            database = database,
            transactions = transactions,
            recognizer = recognizer,
            engine = engine,
            images = images,
            clock = clock,
            ids = ids,
            dispatchers = dispatchers,
            activeProfileId = activeProfileId,
        )
}
