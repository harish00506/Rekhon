package com.aicfo.data.repository

import com.aicfo.core.common.Clock
import com.aicfo.core.common.DispatcherProvider
import com.aicfo.core.common.IdGenerator
import com.aicfo.core.common.getOrNull
import com.aicfo.core.crypto.BackupCipher
import com.aicfo.core.crypto.ReceiptImageStore
import com.aicfo.core.database.CfoDatabase
import com.aicfo.core.datastore.ConsentStore
import com.aicfo.core.datastore.SettingsStore
import com.aicfo.core.model.CategoryNature
import com.aicfo.core.model.Money
import com.aicfo.core.network.MarketDataApi
import com.aicfo.data.sms.SmsInboxReader
import com.aicfo.domain.engines.budget.BudgetEngine
import com.aicfo.domain.engines.card.CardEngine
import com.aicfo.domain.engines.classification.ClassificationEngine
import com.aicfo.domain.engines.emergencyfund.EmergencyFundEngine
import com.aicfo.domain.engines.forecast.ForecastEngine
import com.aicfo.domain.engines.goals.GoalEngine
import com.aicfo.domain.engines.goals.GoalWaterfallEngine
import com.aicfo.domain.engines.healthscore.HealthRules
import com.aicfo.domain.engines.healthscore.HealthScoreEngine
import com.aicfo.domain.engines.insight.InsightEngine
import com.aicfo.domain.engines.investment.InvestmentEngine
import com.aicfo.domain.engines.loan.LoanEngine
import com.aicfo.domain.engines.nature.NatureEngine
import com.aicfo.domain.engines.networth.NetWorthEngine
import com.aicfo.domain.engines.notification.NotificationPolicyEngine
import com.aicfo.domain.engines.orderofoperations.OrderOfOperationsEngine
import com.aicfo.domain.engines.purchase.PurchaseAdvisorEngine
import com.aicfo.domain.engines.purchase.PurchaseInterviewEngine
import com.aicfo.domain.engines.receipt.ReceiptEngine
import com.aicfo.domain.engines.recurring.RecurringEngine
import com.aicfo.domain.engines.safetospend.SafeToSpendEngine
import com.aicfo.domain.engines.seasonality.SeasonalityEngine
import com.aicfo.domain.engines.sms.SmsEngine
import com.aicfo.domain.engines.stream.StreamEngine
import com.aicfo.ml.ocr.ReceiptTextRecognizer
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine

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
     * Builds the credit-card store (issue 6.1, FR-ACC-002).
     * Why:    takes the whole [database] because one read spans `account`, `credit_card` and the
     *         correlated transaction sum behind the balance; and takes the [engine] rather than
     *         constructing one, for the reason [netWorth] gives — the figure and the code that
     *         produced it stay assembled in the DI graph (ARC-003, P-03).
     * Result: a [CreditCardRepository] over the encrypted database.
     * Input:  [database]; [engine]; [clock] — TIM-001, and the single clock read on this path;
     *         [ids] — mints the alert-claim ids from an injected source (P-08); [dispatchers];
     *         [activeProfileId] — so the demo profile gets its own cards.
     * Output: [CreditCardRepository].
     */
    @Suppress("LongParameterList") // One argument per collaborator, as the other factories here.
    fun creditCards(
        database: CfoDatabase,
        engine: CardEngine,
        clock: Clock,
        ids: IdGenerator,
        dispatchers: DispatcherProvider,
        activeProfileId: Flow<String>,
    ): CreditCardRepository = RoomCreditCardRepository(database, engine, clock, ids, dispatchers, activeProfileId)

    /**
     * Builds the loan store (issue 6.2, FR-ACC-003).
     * Why:    takes the whole [database] because a save spans `account` and `loan`; and takes the
     *         [engine] rather than constructing one, for the reason [netWorth] gives — the figure
     *         and the code that produced it stay assembled in the DI graph (ARC-003, P-03).
     * Result: a [LoanRepository] over the encrypted database.
     * Input:  [database]; [engine]; [clock] — TIM-001, and the single clock read on this path, which
     *         decides which instalment is next; [dispatchers]; [activeProfileId] — so the demo
     *         profile gets its own loans. **No `IdGenerator`**, unlike [creditCards]: a loan is keyed
     *         by its account and mints no rows of its own.
     * Output: [LoanRepository].
     */
    fun loans(
        database: CfoDatabase,
        engine: LoanEngine,
        clock: Clock,
        dispatchers: DispatcherProvider,
        activeProfileId: Flow<String>,
    ): LoanRepository = RoomLoanRepository(database, engine, clock, dispatchers, activeProfileId)

    /**
     * Builds the holdings store (issue 6.3, §11).
     * Why:    takes the whole [database] because a save spans `account`, `investment_holding` and
     *         `investment_lot`; and takes the [engine] rather than constructing one, for the reason
     *         [loans] gives — the figure and the code that produced it stay assembled in the DI
     *         graph (ARC-003, P-03).
     * Result: an [InvestmentRepository] over the encrypted database.
     * Input:  [database]; [engine]; [clock] — TIM-001, though only for stamps and provenance here:
     *         the return itself is dated by the day the price was observed, never by today;
     *         [ids] — holdings and lots mint their own keys, unlike a loan; [dispatchers];
     *         [activeProfileId] — so the demo profile gets its own holdings.
     * Output: an [InvestmentRepository].
     * Changelog: 2026-08-24 — Created for issue 6.3.
     */
    @Suppress("LongParameterList") // Six collaborators, each a distinct binding — as [creditCards].
    fun investments(
        database: CfoDatabase,
        engine: InvestmentEngine,
        clock: Clock,
        ids: IdGenerator,
        dispatchers: DispatcherProvider,
        activeProfileId: Flow<String>,
    ): InvestmentRepository = RoomInvestmentRepository(database, engine, clock, ids, dispatchers, activeProfileId)

    /**
     * The goals repository (issue 7.1; §15).
     * Result: a [GoalRepository] over Room and [GoalEngine].
     * Input:  [database]; [engine]; [clock]; [ids]; [dispatchers]; [activeProfileId].
     * Output: [GoalRepository].
     */
    @Suppress("LongParameterList") // Six collaborators, each a distinct binding — as [investments].
    fun goals(
        database: CfoDatabase,
        engine: GoalEngine,
        clock: Clock,
        ids: IdGenerator,
        dispatchers: DispatcherProvider,
        activeProfileId: Flow<String>,
    ): GoalRepository = RoomGoalRepository(database, engine, clock, ids, dispatchers, activeProfileId)

    /**
     * The goal-contribution repository (issue 7.4; §15, FR-GOAL-002, FR-GOAL-004).
     *
     * Why:    separate from [goals] because the two own different tables — `goal` there, the two
     *         link tables here — and ARC-005 asks that exactly one class touch a DAO, not that one
     *         class touch every DAO. It takes [transactions] and [accounts] as repositories rather
     *         than reaching for their DAOs, for the reason [emergencyFund] does: those rows already
     *         have owners, and a second reader of them would be a second answer.
     * Result: a [GoalContributionRepository] over Room and those two.
     * Input:  [database]; [transactions]; [accounts]; [clock]; [ids]; [dispatchers];
     *         [activeProfileId].
     * Output: [GoalContributionRepository].
     */
    @Suppress("LongParameterList") // Seven collaborators, each a distinct binding — as [goals].
    fun goalContributions(
        database: CfoDatabase,
        transactions: TransactionRepository,
        accounts: AccountRepository,
        clock: Clock,
        ids: IdGenerator,
        dispatchers: DispatcherProvider,
        activeProfileId: Flow<String>,
    ): GoalContributionRepository =
        RoomGoalContributionRepository(
            database = database,
            transactions = transactions,
            accounts = accounts,
            clock = clock,
            ids = ids,
            dispatchers = dispatchers,
            activeProfileId = activeProfileId,
        )

    /**
     * The emergency-fund repository (issue 7.2; §10.1).
     * Why:    **the only repository here built from other repositories rather than from the
     *         database.** Its three inputs already have owners — the classified ledger is
     *         [TransactionRepository]'s, the balances are [AccountRepository]'s, the onboarding
     *         envelopes are [QuickSetupRepository]'s — and reaching past them to the DAOs would put a
     *         second answer to "is this rupee a need?" in the codebase (ARC-005 is about who may
     *         touch a DAO, not about how many layers a repository may be).
     * Result: an [EmergencyFundRepository] over those three and [EmergencyFundEngine].
     * Input:  [transactions]; [accounts]; [quickSetup]; [engine]; [clock]; [dispatchers].
     * Output: [EmergencyFundRepository].
     */
    @Suppress("LongParameterList") // Six collaborators, each a distinct binding — as [goals].
    fun emergencyFund(
        transactions: TransactionRepository,
        accounts: AccountRepository,
        quickSetup: QuickSetupRepository,
        engine: EmergencyFundEngine,
        clock: Clock,
        dispatchers: DispatcherProvider,
    ): EmergencyFundRepository =
        RoomEmergencyFundRepository(transactions, accounts, quickSetup, engine, clock, dispatchers)

    /**
     * The goal-feasibility and contribution waterfall (issue 7.3; §15.1, FR-GOAL-003/005).
     * Why:    the second repository here built entirely from other repositories, for
     *         [emergencyFund]'s reason and one more: the goals are already projected by
     *         [GoalRepository] and the runway is already resolved by [EmergencyFundRepository], so
     *         recomputing either here would give the app two answers to one question.
     * Result: a [GoalWaterfallRepository] over those three and [GoalWaterfallEngine].
     * Input:  [goals]; [ranking] — §36's order, whose remainder after the buffer, high-interest debt
     *         and the emergency fund is what the goals may have (ADR-0038); [emergencyFund] —
     *         `RULE-EMERG-FIRST`'s runway; [engine]; [clock]; [dispatchers]. **No `activeProfileId`**:
     *         every source is already profile-scoped.
     * Output: [GoalWaterfallRepository].
     */
    @Suppress("LongParameterList") // Six collaborators, each a distinct binding — as [emergencyFund].
    fun goalWaterfall(
        goals: GoalRepository,
        ranking: OrderOfOperationsRepository,
        emergencyFund: EmergencyFundRepository,
        engine: GoalWaterfallEngine,
        clock: Clock,
        dispatchers: DispatcherProvider,
    ): GoalWaterfallRepository =
        RoomGoalWaterfallRepository(
            goals = goals,
            ranking = ranking,
            emergencyFund = emergencyFund,
            engine = engine,
            clock = clock,
            dispatchers = dispatchers,
        )

    /**
     * The month's surplus and where it came from (issue 7.3, extracted 2026-09-18).
     * Why:    both §15.1's waterfall and §36's ranking need it, and whichever owned the derivation
     *         would be depended on by the other. Extracting it lets AI-FOO be the base (ADR-0038).
     * Result: a [SurplusRepository]. Input: [transactions] — the closed-month ledger the median is
     *         taken over; [quickSetup] — the declared INVEST envelope, the fallback; [dispatchers].
     * Output: [SurplusRepository].
     * Changelog: 2026-09-18 — Extracted from `goalWaterfall`.
     */
    fun surplus(
        transactions: TransactionRepository,
        quickSetup: QuickSetupRepository,
        dispatchers: DispatcherProvider,
    ): SurplusRepository = RoomSurplusRepository(transactions, quickSetup, dispatchers)

    /**
     * The Financial Order of Operations (issue 7.5; §36, AI-FOO).
     * Why:    built mostly from other repositories: the surplus is [surplus]'s, the goals' need is
     *         [goals]' projections, the runway and shortfall are the emergency fund's, and resolving
     *         any of them again here would give the app two answers to one question. It takes the
     *         [database] only for the one thing nobody resolved before — each debt's rate. **This is
     *         the base of the pair**: [goalWaterfall] consumes what it leaves (ADR-0038).
     * Result: an [OrderOfOperationsRepository] over those and [OrderOfOperationsEngine].
     * Input:  [database] — accounts, cards and loans; [goals] — the projections Stage 5 sums;
     *         [surplus]; [emergencyFund]; [engine]; [clock]; [dispatchers]; [activeProfileId] — scopes
     *         the debt read, which is this repository's own.
     * Output: [OrderOfOperationsRepository].
     * Changelog: 2026-09-17 — Created for issue 7.5.
     */
    @Suppress("LongParameterList") // Seven collaborators, each a distinct binding — as [goalWaterfall].
    fun orderOfOperations(
        database: CfoDatabase,
        goals: GoalRepository,
        surplus: SurplusRepository,
        emergencyFund: EmergencyFundRepository,
        engine: OrderOfOperationsEngine,
        clock: Clock,
        dispatchers: DispatcherProvider,
        activeProfileId: Flow<String>,
    ): OrderOfOperationsRepository =
        RoomOrderOfOperationsRepository(
            database = database,
            goals = goals,
            surplus = surplus,
            emergencyFund = emergencyFund,
            engine = engine,
            clock = clock,
            dispatchers = dispatchers,
            activeProfileId = activeProfileId,
        )

    /**
     * Builds the market-price refresher (issue 6.5, FR-INV-004).
     * Why:    separate from [investments] because the two own different columns of the same table —
     *         see `MarketPriceRepository`'s class doc. Takes [api] rather than building one, for the
     *         same reason every other function here takes its engine: in a shipping build that
     *         argument is the unconfigured client, and swapping it for a live one is one binding in
     *         `:app` and no change at all in this module.
     * Result: a [MarketPriceRepository] over the encrypted database.
     * Input:  [database]; [api] — the market-data client; [engine] — decides what is due;
     *         [consents] — the MARKET_DATA gate (P-01); [clock] — TIM-001; [dispatchers];
     *         [activeProfileId]. **No `IdGenerator`**: this never creates a row, only updates
     *         four columns of rows that already exist.
     * Output: [MarketPriceRepository].
     */
    @Suppress("LongParameterList") // Seven collaborators, each a distinct binding — as [receipts].
    fun marketPrice(
        database: CfoDatabase,
        api: MarketDataApi,
        engine: InvestmentEngine,
        consents: ConsentStore,
        clock: Clock,
        dispatchers: DispatcherProvider,
        activeProfileId: Flow<String>,
    ): MarketPriceRepository =
        RoomMarketPriceRepository(database, api, engine, consents, clock, dispatchers, activeProfileId)

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
     * Builds the encrypted-backup store (issue 8.1; SEC-005, §23.3, P-01).
     * Why:    composes two things that already exist — the archive and the cipher — rather than
     *         reading the database a second way, so a backup can never carry different rows from an
     *         export. The consent ledger and the audit log are the two gates it adds.
     * Result: a [BackupRepository].
     * Input:  [archive] — what is sealed; [cipher] — Argon2id → AES-256-GCM; [consents] — the P-01
     *         ledger; [audit] — the security log; [dispatchers] — the KDF runs on `default`.
     * Output: [BackupRepository].
     * Changelog: 2026-09-18 — Created for issue 8.1.
     */
    fun backup(
        archive: ArchiveRepository,
        cipher: BackupCipher,
        consents: ConsentStore,
        audit: AuditLogRepository,
        dispatchers: DispatcherProvider,
    ): BackupRepository = EncryptedBackupRepository(archive, cipher, consents, audit, dispatchers)

    /**
     * Builds AI-CLS Stage 2 over the ledger (issue 9.1; §8.2).
     * Why:    takes the engine rather than building one (ARC-003, P-03): the repository joins, the
     *         engine scores.
     * Result: a [StreamRepository].
     * Input:  [database]; [engine]; [clock] — the six-closed-month window (TIM-001); [dispatchers];
     *         [activeProfileId] — follows the demo (ADR-0006).
     * Output: [StreamRepository].
     * Changelog: 2026-09-19 — Created for issue 9.1.
     */
    fun streams(
        database: CfoDatabase,
        engine: StreamEngine,
        clock: Clock,
        dispatchers: DispatcherProvider,
        activeProfileId: Flow<String>,
    ): StreamRepository = RoomStreamRepository(database, engine, clock, dispatchers, activeProfileId)

    /**
     * Builds the buy list (issue 10.2; §13.3).
     * Why:    the ladder weighs a price against income, and income is the same median the health
     *         score reads (`HealthSignals.obligations`) — one definition, so a wish never lands in a
     *         different band than the score would put it in.
     * Result: a [BuyListRepository].
     * Input:  [database]; [engine]; [advisor]; [transactions], [streams] and [loans] — the income
     *         signal; [clock]; [dispatchers]; [activeProfileId]; [idGenerator]. Output: the repository.
     * Changelog: 2026-09-26 — Created for issue 10.2.
     */
    @Suppress("LongParameterList") // the store, the engine, the advisor, three sources and four seams
    fun buyList(
        database: CfoDatabase,
        engine: PurchaseInterviewEngine,
        advisor: PurchaseAdvisorRepository,
        transactions: TransactionRepository,
        streams: StreamRepository,
        loans: LoanRepository,
        clock: Clock,
        dispatchers: DispatcherProvider,
        activeProfileId: Flow<String>,
        idGenerator: IdGenerator,
    ): BuyListRepository =
        StoredBuyListRepository(
            database = database,
            engine = engine,
            advisor = advisor,
            monthlyIncome = monthlyIncome(transactions, streams, loans),
            clock = clock,
            dispatchers = dispatchers,
            activeProfileId = activeProfileId,
            idGenerator = idGenerator,
        )

    /**
     * The month's income as the rest of the app reckons it.
     * Why:    `HealthSignals.obligations` already decides what a typical month earns — the median of
     *         the months that had income. Reusing it keeps the buy list's ladder and the health
     *         score speaking about the same household.
     * Result: a flow of the figure, `Money.ZERO` when there is not enough history. Input: the three
     *         sources. Output: `Flow<Money>`.
     */
    private fun monthlyIncome(
        transactions: TransactionRepository,
        streams: StreamRepository,
        loans: LoanRepository,
    ): Flow<Money> =
        combine(
            transactions.observeMonthlyLedger(HealthRules().lookbackMonths),
            streams.observeStreams(),
            loans.observeNextInstalments(),
            transactions.observeCategories(),
        ) { months, streamProfile, instalments, categories ->
            val liabilities = categories.filter { it.nature == CategoryNature.LIABILITY }.map { it.id }.toSet()
            HealthSignals.obligations(months, streamProfile.getOrNull(), instalments.values.toList(), liabilities)
                ?.income ?: Money.ZERO
        }

    /**
     * Builds the Purchase Advisor (issue 10.1; §13).
     * Why:    built over the repositories that publish the figures its gates judge, so a verdict
     *         cannot disagree with the screens the user can check it against (AI-ARC-001).
     * Result: a [PurchaseAdvisorRepository].
     * Input:  [database]; the seven owning repositories; [engine]; [clock]; [dispatchers];
     *         [activeProfileId]; [idGenerator]. Output: the repository.
     * Changelog: 2026-09-25 — Created for issue 10.1.
     */
    @Suppress("LongParameterList") // the store, seven sources, the engine and four seams
    fun purchaseAdvisor(
        database: CfoDatabase,
        emergencyFund: EmergencyFundRepository,
        safeToSpend: SafeToSpendRepository,
        forecasts: ForecastRepository,
        transactions: TransactionRepository,
        streams: StreamRepository,
        loans: LoanRepository,
        goals: GoalRepository,
        budgets: BudgetRepository,
        engine: PurchaseAdvisorEngine,
        clock: Clock,
        dispatchers: DispatcherProvider,
        activeProfileId: Flow<String>,
        idGenerator: IdGenerator,
    ): PurchaseAdvisorRepository =
        StoredPurchaseAdvisorRepository(
            database = database,
            sources =
                PurchaseSources(
                    emergency = emergencyFund.observeEmergencyFund(),
                    safeToSpend = safeToSpend.observeSafeToSpend(),
                    forecast = forecasts.observeForecast(),
                    ledger = transactions.observeMonthlyLedger(HealthRules().lookbackMonths),
                    streams = streams.observeStreams(),
                    instalments = loans.observeNextInstalments(),
                    categories = transactions.observeCategories(),
                    goals = goals.observeGoals(),
                    budgets = budgets.observeBudgets(),
                ),
            engine = engine,
            clock = clock,
            dispatchers = dispatchers,
            activeProfileId = activeProfileId,
            idGenerator = idGenerator,
        )

    /**
     * Builds the notification gate (issue 9.6; §17.2).
     * Why:    one gate for every worker that wants to post, so the caps are the app's caps and not
     *         each worker's (NTF-001). Only its own log is touched.
     * Result: a [NotificationRepository].
     * Input:  [database]; [engine]; [clock]; [dispatchers]; [activeProfileId]; [idGenerator].
     * Output: [NotificationRepository].
     * Changelog: 2026-09-20 — Created for issue 9.6.
     */
    @Suppress("LongParameterList") // the store, the engine and four seams
    fun notifications(
        database: CfoDatabase,
        engine: NotificationPolicyEngine,
        clock: Clock,
        dispatchers: DispatcherProvider,
        activeProfileId: Flow<String>,
        idGenerator: IdGenerator,
    ): NotificationRepository =
        RoomNotificationRepository(
            database,
            engine,
            clock,
            dispatchers,
            activeProfileId,
            idGenerator,
        )

    /**
     * Builds AI-ORCH over the engines beneath it and the feed it persists (issue 9.5; §7.2).
     * Why:    §7.2's pipeline is "run the layers, then persist"; the layers are already these
     *         repositories, so the orchestrator composes their published results rather than
     *         recomputing anything (AI-ARC-001). Only its own table is written here.
     * Result: an [InsightRepository].
     * Input:  [database] — for the `insight` table; the five sources; [engine]; [clock];
     *         [dispatchers]; [activeProfileId]; [idGenerator].
     * Output: [InsightRepository].
     * Changelog: 2026-09-20 — Created for issue 9.5.
     */
    @Suppress("LongParameterList") // the store, five sources, the engine and three seams
    fun insights(
        database: CfoDatabase,
        forecasts: ForecastRepository,
        health: HealthScoreRepository,
        emergencyFund: EmergencyFundRepository,
        budgets: BudgetRepository,
        goals: GoalRepository,
        engine: InsightEngine,
        clock: Clock,
        dispatchers: DispatcherProvider,
        activeProfileId: Flow<String>,
        idGenerator: IdGenerator,
    ): InsightRepository =
        RoomInsightRepository(
            database = database,
            sources =
                InsightSources(
                    forecast = forecasts.observeForecast(),
                    health = health.observeHealthScore(),
                    emergency = emergencyFund.observeEmergencyFund(),
                    budgets = budgets.observeBudgets(),
                    goals = goals.observeGoals(),
                ),
            engine = engine,
            clock = clock,
            dispatchers = dispatchers,
            activeProfileId = activeProfileId,
            idGenerator = idGenerator,
        )

    /**
     * Builds AI-FHS over the repositories that own its signals (issue 9.4; §14).
     * Why:    the score is other engines' answers put together, so it reads them from the same
     *         repositories the screens beside it read — a runway or a budget can never differ between
     *         the score and its own card (ADR-0007, ADR-0045). No DAO is touched here.
     * Result: a [HealthScoreRepository].
     * Input:  the eight owning repositories; [engine]; [clock]; [dispatchers].
     * Output: [HealthScoreRepository].
     * Changelog: 2026-09-19 — Created for issue 9.4.
     */
    @Suppress("LongParameterList") // eight sources, each one a signal the score reads
    fun healthScore(
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
        ComposedHealthScoreRepository(
            sources =
                HealthSources(
                    emergency = emergencyFund.observeEmergencyFund(),
                    ledger = transactions.observeMonthlyLedger(HealthRules().lookbackMonths),
                    streams = streams.observeStreams(),
                    instalments = loans.observeNextInstalments(),
                    cards = cards.observeCardStatuses(),
                    budgets = budgets.observeBudgets(),
                    goals = goals.observeGoals(),
                    categories = transactions.observeCategories(),
                ),
            engine = engine,
            clock = clock,
            dispatchers = dispatchers,
        )

    /**
     * Builds AI-FCT over the ledger (issue 9.2; §9).
     * Why:    takes the account and stream repositories rather than re-deriving balances or streams,
     *         so the forecast and the screens beside it agree on both (ADR-0007, ADR-0043).
     * Result: a [ForecastRepository].
     * Input:  [database]; [accounts]; [streams]; [engine]; [seasonality] — AI-SEAS, whose factor
     *         the forecast applies (issue 9.3); [clock]; [dispatchers]; [activeProfileId].
     * Output: [ForecastRepository].
     * Changelog: 2026-09-19 — Created for issue 9.2.
     *            2026-09-19 — [seasonality] added for issue 9.3.
     */
    @Suppress("LongParameterList") // eight sources, each one the forecast reads
    fun forecast(
        database: CfoDatabase,
        accounts: AccountRepository,
        streams: StreamRepository,
        engine: ForecastEngine,
        seasonality: SeasonalityEngine,
        clock: Clock,
        dispatchers: DispatcherProvider,
        activeProfileId: Flow<String>,
    ): ForecastRepository =
        RoomForecastRepository(
            database,
            accounts,
            streams,
            engine,
            seasonality,
            clock,
            dispatchers,
            activeProfileId,
        )

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
        goals: GoalRepository,
        engine: SafeToSpendEngine,
        clock: Clock,
        dispatchers: DispatcherProvider,
        activeProfileId: Flow<String>,
    ): SafeToSpendRepository =
        RoomSafeToSpendRepository(
            database = database,
            transactions = transactions,
            quickSetup = quickSetup,
            goals = goals,
            engine = engine,
            clock = clock,
            dispatchers = dispatchers,
            activeProfileId = activeProfileId,
        )
}
