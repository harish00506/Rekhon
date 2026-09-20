package com.aicfo.data.repository

import com.aicfo.core.database.CfoDatabase
import com.aicfo.core.database.entity.AccountEntity
import com.aicfo.core.database.entity.AttachmentEntity
import com.aicfo.core.database.entity.BudgetAlertEntity
import com.aicfo.core.database.entity.BudgetEntity
import com.aicfo.core.database.entity.BudgetReviewEntity
import com.aicfo.core.database.entity.CardAlertEntity
import com.aicfo.core.database.entity.CategoryEntity
import com.aicfo.core.database.entity.CreditCardEntity
import com.aicfo.core.database.entity.GoalContributionEntity
import com.aicfo.core.database.entity.GoalEntity
import com.aicfo.core.database.entity.GoalFundingAccountEntity
import com.aicfo.core.database.entity.InsightEntity
import com.aicfo.core.database.entity.InvestmentHoldingEntity
import com.aicfo.core.database.entity.InvestmentLotEntity
import com.aicfo.core.database.entity.LoanEntity
import com.aicfo.core.database.entity.NetWorthSnapshotEntity
import com.aicfo.core.database.entity.ProfileEntity
import com.aicfo.core.database.entity.RecurringRuleEntity
import com.aicfo.core.database.entity.SmsDraftEntity
import com.aicfo.core.database.entity.TagEntity
import com.aicfo.core.database.entity.TransactionEntity
import com.aicfo.core.database.entity.TransactionSplitEntity
import com.aicfo.core.database.entity.TransactionTagEntity

/**
 * One row in every profile-scoped table, with every nullable column set (issue 8.3; §21.5, DRL-001).
 *
 * Why:  the restore drill proves a backup brings back **everything**, and it can only prove that
 *       for rows that exist. A fixture that leaves a table empty passes identically against an
 *       archive that forgets the table — which is exactly how `goal` went missing from every export
 *       between issues 7.1 and 7.4. The drill therefore asserts that this fixture fills every table
 *       it can discover, so a table added later fails the drill until someone seeds it here.
 *
 *       In `sharedTest` so the JVM drill and the instrumented one seed the same rows. Issue 5.4's
 *       `ArchiveRepositoryTest` keeps its own fixture; this one is its superset — it adds the five
 *       tables that fixture never reached (`investment_holding`, `investment_lot`, `goal`,
 *       `goal_contribution`, `goal_funding_account`).
 * What: [seed] writes the rows; the constants name them.
 * Result: a profile the drill can back up, restore and compare table by table.
 * Changelog: 2026-09-19 — Created for issue 8.3.
 */
object DrillFixture {
    const val ACCOUNT = "account:1"
    const val CATEGORY = "category:groceries"
    const val TAG = "tag:1"
    const val BUDGET = "budget:1"
    const val PARENT_CATEGORY = "category:food"
    const val HOLDING = "holding:1"
    const val GOAL = "goal:1"

    /** The row whose every nullable column is populated. */
    const val RICH_TXN = "txn:rich"
    const val NOW = 1_786_000_000_000L

    /** A tombstone the backup must carry as a tombstone. */
    const val DELETED_AT = 1_786_000_500_000L

    /**
     * Seeds every profile-scoped table for [profileId].
     * Why:    see the object's doc — the drill covers only what this fills.
     * Result: one profile row, and at least one row in every table that carries `profile_id`.
     * Input:  [database] — any `CfoDatabase`, encrypted or in-memory; [profileId] — whose rows.
     * Output: none (suspends).
     */
    @Suppress("LongMethod") // One insert per table; the length is the schema's.
    suspend fun seed(
        database: CfoDatabase,
        profileId: String,
    ) {
        val dao = database.archiveDao()
        dao.insertProfiles(listOf(profile(profileId)))
        dao.insertAccounts(listOf(account(profileId)))
        dao.insertCategories(
            listOf(
                CategoryEntity(
                    id = CATEGORY,
                    profileId = profileId,
                    name = "Groceries",
                    parentId = PARENT_CATEGORY,
                    nature = "need",
                    isSystem = true,
                    createdAtUtcMillis = NOW,
                    updatedAtUtcMillis = NOW,
                    deletedAtUtcMillis = null,
                ),
            ),
        )
        dao.insertTransactions(listOf(transaction(profileId)))
        dao.insertTransactionSplits(
            listOf(
                TransactionSplitEntity(
                    id = "split:1",
                    profileId = profileId,
                    transactionId = RICH_TXN,
                    amountMinor = -1_200_00L,
                    categoryId = CATEGORY,
                    note = "produce",
                    createdAtUtcMillis = NOW,
                    updatedAtUtcMillis = NOW,
                    deletedAtUtcMillis = DELETED_AT,
                ),
            ),
        )
        dao.insertTags(
            listOf(
                TagEntity(
                    id = TAG,
                    profileId = profileId,
                    name = "reimbursable",
                    createdAtUtcMillis = NOW,
                    updatedAtUtcMillis = NOW,
                    deletedAtUtcMillis = DELETED_AT,
                ),
            ),
        )
        dao.insertTransactionTags(
            listOf(
                TransactionTagEntity(
                    id = "txntag:1",
                    profileId = profileId,
                    transactionId = RICH_TXN,
                    tagId = TAG,
                    createdAtUtcMillis = NOW,
                    updatedAtUtcMillis = NOW,
                    deletedAtUtcMillis = null,
                ),
            ),
        )
        dao.insertBudgets(
            listOf(
                BudgetEntity(
                    id = BUDGET,
                    profileId = profileId,
                    categoryId = CATEGORY,
                    nature = "need",
                    periodStartIsoDate = "2026-08-01",
                    amountMinor = 10_000_00L,
                    rolloverEnabled = true,
                    source = "manual",
                    ruleId = "RULE-BUD-SUGGEST",
                    ruleVersion = "1.0",
                    createdAtUtcMillis = NOW,
                    updatedAtUtcMillis = NOW,
                ),
            ),
        )
        dao.insertBudgetAlerts(
            listOf(
                BudgetAlertEntity(
                    id = "alert:1",
                    profileId = profileId,
                    budgetId = BUDGET,
                    categoryId = CATEGORY,
                    monthStartIsoDate = "2026-08-01",
                    band = "warn",
                    ruleId = "RULE-BUD-ALERT",
                    ruleVersion = "1.0",
                    notifiedAtUtcMillis = NOW,
                ),
            ),
        )
        dao.insertCreditCards(
            listOf(
                CreditCardEntity(
                    accountId = ACCOUNT,
                    profileId = profileId,
                    creditLimitMinor = 20_000_000L,
                    statementDay = 5,
                    dueDay = 25,
                    lastStatementMinor = 7_000_000L,
                    lastStatementIsoDate = "2026-03-05",
                    minimumDueMinor = 350_000L,
                    aprBps = 4_200,
                    createdAtUtcMillis = NOW,
                    updatedAtUtcMillis = NOW,
                ),
            ),
        )
        dao.insertLoans(
            listOf(
                // Five columns and no schedule: the amortisation rows are derived on read
                // (ADR-0026), so a round trip that carried them would be round-tripping a cache.
                LoanEntity(
                    accountId = ACCOUNT,
                    profileId = profileId,
                    principalMinor = 300_000_000L,
                    annualRateBps = 850,
                    tenureMonths = 240,
                    firstEmiIsoDate = "2026-09-05",
                    emiOverrideMinor = null,
                    createdAtUtcMillis = NOW,
                    updatedAtUtcMillis = NOW,
                ),
            ),
        )
        dao.insertCardAlerts(
            listOf(
                CardAlertEntity(
                    id = "card-alert:1",
                    profileId = profileId,
                    accountId = ACCOUNT,
                    cycleStartIsoDate = "2026-03-05",
                    kind = "DUE_SOON",
                    ruleId = "RULE-CC-DUE",
                    ruleVersion = "1.0",
                    notifiedAtUtcMillis = NOW,
                ),
            ),
        )
        dao.insertBudgetReviews(
            listOf(
                BudgetReviewEntity(
                    id = "review:1",
                    profileId = profileId,
                    monthStartIsoDate = "2026-07-01",
                    ruleId = "RULE-BUD-REVIEW",
                    ruleVersion = "1.0",
                    totalBudgetedMinor = 30_000_00L,
                    totalActualMinor = 28_450_00L,
                    reviewedAtUtcMillis = NOW,
                ),
            ),
        )
        dao.insertRecurringRules(
            listOf(
                RecurringRuleEntity(
                    id = "recurring:1",
                    profileId = profileId,
                    accountId = ACCOUNT,
                    categoryId = CATEGORY,
                    name = "Netflix",
                    seedKind = "income",
                    amountMinor = -649_00L,
                    cadence = "monthly",
                    nextDueIsoDate = "2026-09-03",
                    source = "detected",
                    isConfirmed = true,
                    dismissedAtUtcMillis = NOW,
                    createdAtUtcMillis = NOW,
                    updatedAtUtcMillis = NOW,
                ),
            ),
        )
        dao.insertNetWorthSnapshots(
            listOf(
                NetWorthSnapshotEntity(
                    id = "snapshot:1",
                    profileId = profileId,
                    asOfIsoDate = "2026-08-15",
                    assetsMinor = 3_00_000_00L,
                    liabilitiesMinor = 50_000_00L,
                    netWorthMinor = 2_50_000_00L,
                    engineId = "net-worth",
                    engineVersion = "1.0",
                    computedAtUtcMillis = NOW,
                    createdAtUtcMillis = NOW,
                    updatedAtUtcMillis = NOW,
                    deletedAtUtcMillis = null,
                ),
            ),
        )
        dao.insertAttachments(
            listOf(
                AttachmentEntity(
                    id = "attachment:1",
                    profileId = profileId,
                    transactionId = RICH_TXN,
                    kind = "receipt",
                    fileName = "attachment-1.bin",
                    mimeType = "image/jpeg",
                    byteSize = 42_000L,
                    createdAtUtcMillis = NOW,
                    updatedAtUtcMillis = NOW,
                    deletedAtUtcMillis = DELETED_AT,
                ),
            ),
        )
        dao.insertSmsDrafts(
            listOf(
                SmsDraftEntity(
                    id = "draft:1",
                    profileId = profileId,
                    smsId = 4_242L,
                    sender = "HDFCBK",
                    amountMinor = -450_00L,
                    direction = "debit",
                    bookedOn = "2026-08-15",
                    counterparty = "SWIGGY",
                    accountTail = "1234",
                    confidenceBps = 8_500,
                    engineVersion = "1.0",
                    ruleVersion = "1.0",
                    status = "pending",
                    transactionId = RICH_TXN,
                    createdAtUtcMillis = NOW,
                    updatedAtUtcMillis = NOW,
                ),
            ),
        )
        dao.insertInvestmentHoldings(
            listOf(
                InvestmentHoldingEntity(
                    id = HOLDING,
                    profileId = profileId,
                    accountId = ACCOUNT,
                    name = "Nifty 50 Index Fund",
                    assetClass = "equity",
                    unitPriceMinor = 245_67L,
                    pricedOnIsoDate = "2026-08-14",
                    priceKey = "amfi:120716",
                    priceFetchedAtUtcMillis = NOW,
                    createdAtUtcMillis = NOW,
                    updatedAtUtcMillis = NOW,
                    deletedAtUtcMillis = DELETED_AT,
                ),
            ),
        )
        dao.insertInvestmentLots(
            listOf(
                InvestmentLotEntity(
                    id = "lot:1",
                    profileId = profileId,
                    holdingId = HOLDING,
                    kind = "buy",
                    transactedOnIsoDate = "2026-08-01",
                    quantityNano = 12_345_678_901L,
                    amountMinor = -30_000_00L,
                    createdAtUtcMillis = NOW,
                    updatedAtUtcMillis = NOW,
                    deletedAtUtcMillis = DELETED_AT,
                ),
            ),
        )
        dao.insertGoals(
            listOf(
                GoalEntity(
                    id = GOAL,
                    profileId = profileId,
                    name = "Emergency top-up",
                    targetMinor = 3_00_000_00L,
                    targetDateIso = "2027-03-31",
                    savedMinor = 45_000_01L,
                    plannedMonthlyMinor = 12_500_00L,
                    sortOrder = 2,
                    createdAtUtcMillis = NOW,
                    updatedAtUtcMillis = NOW,
                    deletedAtUtcMillis = DELETED_AT,
                ),
            ),
        )
        dao.insertGoalContributions(
            listOf(
                GoalContributionEntity(
                    id = "contribution:1",
                    profileId = profileId,
                    goalId = GOAL,
                    transactionId = RICH_TXN,
                    createdAtUtcMillis = NOW,
                    updatedAtUtcMillis = NOW,
                    deletedAtUtcMillis = DELETED_AT,
                ),
            ),
        )
        dao.insertInsights(
            listOf(
                InsightEntity(
                    id = "insight:1",
                    profileId = profileId,
                    fingerprint = "BUDGET_OVERSPENT|$CATEGORY|2026-09",
                    type = "BUDGET_OVERSPENT",
                    severity = "WARNING",
                    subject = CATEGORY,
                    subjectLabel = "Dining",
                    period = "2026-09",
                    amountMinor = 125_000L,
                    secondaryMinor = null,
                    dateIso = null,
                    quantity = null,
                    confidenceBps = 10_000,
                    citations = "RULE-BUD-ALERT v1.0",
                    sourceEngineId = "budget-planner",
                    sourceEngineVersion = "1.0",
                    // Dismissed, not active: the verdict is the part of this row a restore must carry.
                    status = "dismissed",
                    suppressedUntilIsoDate = "2026-09-27",
                    createdAtUtcMillis = NOW,
                    updatedAtUtcMillis = NOW,
                ),
            ),
        )
        dao.insertGoalFundingAccounts(
            listOf(
                GoalFundingAccountEntity(
                    id = "funding:1",
                    profileId = profileId,
                    goalId = GOAL,
                    accountId = ACCOUNT,
                    linkedFromIsoDate = "2026-08-01",
                    createdAtUtcMillis = NOW,
                    updatedAtUtcMillis = NOW,
                    deletedAtUtcMillis = DELETED_AT,
                ),
            ),
        )
    }

    /** Result: the profile row. Input: [id]. */
    private fun profile(id: String) =
        ProfileEntity(
            id = id,
            displayName = "Harish",
            timeZoneId = "Asia/Kolkata",
            currencyCode = "INR",
            createdAtUtcMillis = NOW,
            updatedAtUtcMillis = NOW,
            deletedAtUtcMillis = null,
        )

    /** Result: the account every other row hangs off, archived so that column is carried too. */
    private fun account(profileId: String) =
        AccountEntity(
            id = ACCOUNT,
            profileId = profileId,
            name = "HDFC Savings",
            type = "bank",
            openingBalanceMinor = 5_000_00L,
            currentBalanceMinor = 4_000_00L,
            currencyCode = "INR",
            institution = "HDFC Bank",
            includeInNetWorth = true,
            archivedAtUtcMillis = DELETED_AT,
            createdAtUtcMillis = NOW,
            updatedAtUtcMillis = NOW,
            deletedAtUtcMillis = null,
        )

    /** Result: the rich transaction — every nullable column set, a tombstone, an odd paisa. */
    private fun transaction(profileId: String) =
        TransactionEntity(
            id = RICH_TXN,
            profileId = profileId,
            accountId = ACCOUNT,
            amountMinor = -1_234_57L,
            currencyCode = "INR",
            occurredAtUtcMillis = NOW,
            bookedOnIsoDate = "2026-08-15",
            categoryId = CATEGORY,
            merchant = "Big Bazaar",
            note = "weekly shop",
            source = "manual",
            type = "expense",
            transferId = "transfer:1",
            postedAtUtcMillis = NOW,
            nature = "need",
            createdAtUtcMillis = NOW,
            updatedAtUtcMillis = NOW,
            deletedAtUtcMillis = DELETED_AT,
        )
}
