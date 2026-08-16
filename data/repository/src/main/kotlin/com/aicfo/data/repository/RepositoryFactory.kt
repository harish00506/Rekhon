package com.aicfo.data.repository

import com.aicfo.core.common.Clock
import com.aicfo.core.common.DispatcherProvider
import com.aicfo.core.common.IdGenerator
import com.aicfo.core.crypto.ReceiptImageStore
import com.aicfo.core.database.CfoDatabase
import com.aicfo.core.datastore.ConsentStore
import com.aicfo.core.datastore.SettingsStore
import com.aicfo.data.sms.SmsInboxReader
import com.aicfo.domain.engines.budget.BudgetEngine
import com.aicfo.domain.engines.classification.ClassificationEngine
import com.aicfo.domain.engines.nature.NatureEngine
import com.aicfo.domain.engines.networth.NetWorthEngine
import com.aicfo.domain.engines.receipt.ReceiptEngine
import com.aicfo.domain.engines.recurring.RecurringEngine
import com.aicfo.domain.engines.safetospend.SafeToSpendEngine
import com.aicfo.domain.engines.sms.SmsEngine
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
 *
 * **Past detekt's `TooManyFunctions` ceiling, deliberately.** Issue 4.4's budget repository is the
 * twelfth, and the count here is simply the number of repositories the app has — splitting the
 * object would put half the repositories behind one name and half behind another with no rule for
 * which goes where. [CfoDatabase] carries the same suppression for the same reason: one accessor
 * per table, one factory function per repository.
 */
@Suppress("TooManyFunctions") // One factory function per repository (ARC-003) — see the note above.
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
     *         exists; [classifier] — issue 4.2's Stage-1 categoriser, taken rather than constructed
     *         for the reason [recurring] gives: the suggestion and the code that produced it stay
     *         assembled in the DI graph (ARC-003, P-03); [natureEngine] — issue 4.3's §8.3 decision
     *         order, taken for the same reason.
     * Output: [TransactionRepository].
     */
    @Suppress("LongParameterList")
    fun transactions(
        database: CfoDatabase,
        clock: Clock,
        ids: IdGenerator,
        dispatchers: DispatcherProvider,
        activeProfileId: Flow<String>,
        classifier: ClassificationEngine,
        natureEngine: NatureEngine,
    ): TransactionRepository =
        RoomTransactionRepository(database, clock, ids, dispatchers, activeProfileId, classifier, natureEngine)

    /**
     * Builds the category taxonomy store (issue 4.1, FR-SET-001).
     * Why:    takes the whole [database] because uniqueness and nesting are checked against rows the
     *         same statement then writes, and that pairing has to sit in one `withTransaction`.
     * Result: a [CategoryRepository] over the encrypted database.
     * Input:  [database]; [clock] — TIM-001, for the row stamps; [ids] — the injected id source
     *         (P-08), used for user-created categories only; a seeded one derives its id from its
     *         `CategorySeed` key so the seed is idempotent; [dispatchers]; [activeProfileId] — which
     *         profile owns the taxonomy, so the demo keeps its own twelve.
     * Output: [CategoryRepository].
     */
    fun categories(
        database: CfoDatabase,
        clock: Clock,
        ids: IdGenerator,
        dispatchers: DispatcherProvider,
        activeProfileId: Flow<String>,
    ): CategoryRepository = RoomCategoryRepository(database, clock, ids, dispatchers, activeProfileId)

    /**
     * Builds the per-category budget store (issue 4.4, FR-BUD-001/002/003).
     * Why:    takes the [engine] rather than constructing one, for the reason [netWorth] and
     *         [recurring] give — the number and the code that produced it stay assembled in the DI
     *         graph (ARC-003, P-03). Takes the whole [database] because a budget's status spans the
     *         budget, the month's transactions with their split lines, and the taxonomy.
     * Result: a [BudgetRepository] over the encrypted database.
     * Input:  [database]; [engine] — suggests amounts and computes pace; [clock] — TIM-001, and the
     *         budget month in the profile zone (TIM-002); [dispatchers]; [activeProfileId] — whose
     *         budgets, so the demo keeps its own. **No `IdGenerator`**: a budget's id is derived
     *         from the profile, category and period, so saving twice updates one row rather than
     *         minting two (see `categoryBudgetId`).
     * Output: [BudgetRepository].
     */
    fun budgets(
        database: CfoDatabase,
        engine: BudgetEngine,
        clock: Clock,
        dispatchers: DispatcherProvider,
        activeProfileId: Flow<String>,
    ): BudgetRepository = RoomBudgetRepository(database, engine, clock, dispatchers, activeProfileId)

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

    /**
     * Builds the SMS-draft store (issue 3.9; §18, §23, P-01).
     * Result: an [SmsRepository] over the encrypted database, the inbox and the parser.
     * Input:  [database]; [transactions] — writes the ledger row; [reader] — the inbox
     *         (`:data:sms`); [engine] — the parser (P-03); [consents] — the gate every path here
     *         passes through; [settings] — the scan cursor; [clock] — TIM-001, and the profile zone
     *         the received instant is converted in (TIM-002); [ids] — mints draft ids;
     *         [dispatchers]; [activeProfileId] — which profile is scanned into, so the demo gets its
     *         own drafts and loses them with it (ADR-0006).
     * Output: [SmsRepository].
     *
     * `@Suppress("LongParameterList")`: one argument per collaborator. See `RoomSmsRepository` —
     * that class exists precisely to be the one place the consent, the inbox and the parser meet.
     */
    @Suppress("LongParameterList")
    fun sms(
        database: CfoDatabase,
        transactions: TransactionRepository,
        reader: SmsInboxReader,
        engine: SmsEngine,
        consents: ConsentStore,
        settings: SettingsStore,
        clock: Clock,
        ids: IdGenerator,
        dispatchers: DispatcherProvider,
        activeProfileId: Flow<String>,
    ): SmsRepository =
        RoomSmsRepository(
            database = database,
            transactions = transactions,
            reader = reader,
            engine = engine,
            consents = consents,
            settings = settings,
            clock = clock,
            ids = ids,
            dispatchers = dispatchers,
            activeProfileId = activeProfileId,
        )

    /**
     * Builds the export/import archive store (issue 5.4; §5.10, §34, P-01).
     * Why:    takes the whole [database] rather than DAOs, for the reason [quickSetup] does — the
     *         import's all-or-nothing guarantee rests on `withTransaction`, which is a method on the
     *         database, and the archive spans every table anyway.
     * Result: an [ArchiveRepository] over the encrypted database.
     * Input:  [database]; [clock] — stamps the archive (TIM-001); [dispatchers];
     *         [activeProfileId] — which profile is exported and replaced, so the demo can be
     *         exported without ever touching the real one (ADR-0006).
     * Output: [ArchiveRepository].
     */
    fun archive(
        database: CfoDatabase,
        clock: Clock,
        dispatchers: DispatcherProvider,
        activeProfileId: Flow<String>,
    ): ArchiveRepository = RoomArchiveRepository(database, clock, dispatchers, activeProfileId)

    /**
     * Builds the Safe-to-Spend store (issue 5.2; §5.2, §14, AI-STS).
     * Why:    takes two repositories rather than more DAOs — the precedent [receipts] and [sms] set,
     *         and here it is what stops "what this month's money became" from having a second
     *         definition (see `RoomSafeToSpendRepository`).
     * Result: a [SafeToSpendRepository] over the budget, the ledger and the user's recurring rules.
     * Input:  [database] — for the recurring rules, the one term nothing else exposes;
     *         [transactions] — cash flow, the nature breakdown and the scheduled rows;
     *         [quickSetup] — the persisted envelopes, which are `RULE-STS`'s income basis;
     *         [engine] — computes the figure (P-03); [clock] — TIM-001, supplies the month;
     *         [dispatchers]; [activeProfileId] — so the demo gets its own figure (ADR-0006).
     * Output: [SafeToSpendRepository].
     *
     * `@Suppress("LongParameterList")`: one argument per collaborator, as [sms] above.
     */
    @Suppress("LongParameterList")
    fun safeToSpend(
        database: CfoDatabase,
        transactions: TransactionRepository,
        quickSetup: QuickSetupRepository,
        engine: SafeToSpendEngine,
        clock: Clock,
        dispatchers: DispatcherProvider,
        activeProfileId: Flow<String>,
    ): SafeToSpendRepository =
        RoomSafeToSpendRepository(
            database = database,
            transactions = transactions,
            quickSetup = quickSetup,
            engine = engine,
            clock = clock,
            dispatchers = dispatchers,
            activeProfileId = activeProfileId,
        )
}
