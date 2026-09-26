package com.aicfo.data.repository

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
import com.aicfo.core.database.entity.InterviewAnswerEntity
import com.aicfo.core.database.entity.InvestmentHoldingEntity
import com.aicfo.core.database.entity.InvestmentLotEntity
import com.aicfo.core.database.entity.LoanEntity
import com.aicfo.core.database.entity.NetWorthSnapshotEntity
import com.aicfo.core.database.entity.NotificationLogEntity
import com.aicfo.core.database.entity.ProfileEntity
import com.aicfo.core.database.entity.PurchaseTraceEntity
import com.aicfo.core.database.entity.PurchaseTraceGateEntity
import com.aicfo.core.database.entity.RecurringRuleEntity
import com.aicfo.core.database.entity.SmsDraftEntity
import com.aicfo.core.database.entity.TagEntity
import com.aicfo.core.database.entity.TransactionEntity
import com.aicfo.core.database.entity.TransactionSplitEntity
import com.aicfo.core.database.entity.TransactionTagEntity
import com.aicfo.core.database.entity.VehicleEntity
import com.aicfo.core.database.entity.VehicleOdometerEntity
import com.aicfo.core.database.entity.VehicleRenewalEntity
import com.aicfo.core.database.entity.VehicleServiceEntity
import com.aicfo.core.database.entity.WishlistItemEntity
import kotlinx.serialization.Serializable

/**
 * The on-disk shape of §5.10's export archive (issue 5.4; §34, P-01).
 *
 * Why:  a file the user owns and can read. The design spec calls the local JSON archive "the
 *       user-owned backup; there is no cloud copy by default", and the point of writing it in plain
 *       JSON rather than something compact is that a person can open it, see their own data, and
 *       move it somewhere else. A privacy-first app that will not hand back what it holds is asking
 *       for more trust than it earns.
 *
 *       **This is not Epic 8's backup.** Issue 8.1 builds the encrypted archive (Argon2id →
 *       AES-256-GCM) for disaster recovery; this one is plaintext and portable, for the user's own
 *       use. Different artefacts, different threat models — see ADR-0023 for why they stay separate.
 * What: an envelope naming the format and the schema it came from, plus one list per table.
 * Result: what `ArchiveRepository.export` writes and `import` reads.
 * Changelog: 2026-08-16 — Created for issue 5.4.
 *   2026-09-06 — Issue 7.4 added [goals], [goalContributions] and [goalFundingAccounts].
 *   2026-09-20 — Issue 9.5 added [insights]. Issue 9.6 added [notificationLog].
 *   2026-09-25 — Issue 10.1 added [purchaseTraces] and [purchaseTraceGates].
 *   2026-09-26 — Issue 10.2 added [wishlistItems] and [interviewAnswers].
 *   2026-09-26 — Issue 10.4 added [vehicles], [vehicleOdometer], [vehicleServices] and
 *   [vehicleRenewals]: a restored phone keeps the readings, because they *are* the prediction.
 *
 * **`goal` was missing from here for two issues, and the warning below is what it disproved.**
 * The argument for holding entities directly is that "a new column is in the archive the moment it
 * is in the table" — true of a *column*, and false of a *table*. Issue 7.1 added `goal`, nothing
 * added a field here, and every export taken between 7.1 and 7.4 silently dropped the user's goals.
 * The failure mode this class was designed to avoid arrived one level up from where it was watched.
 *
 * **[archiveVersion] stays 1.** The envelope's structure did not change in a way any reader has to
 * know about: the three new lists default to empty, so an archive written before them still decodes,
 * and `Json` is configured with `ignoreUnknownKeys` so a newer archive does not break an older
 * build's parser either. The gate that actually refuses an archive this build cannot restore is
 * [schemaVersion], which moves 21 → 22 on its own.
 *
 * **It holds the Room entities directly, and that is the safer choice.** The alternative — fourteen
 * hand-written DTOs and twenty-eight mappers — has exactly one failure mode, and it is silent:
 * somebody adds a column, forgets the DTO, and every export from then on quietly drops that data
 * with no test able to see it. Here a new column is in the archive the moment it is in the table.
 * The cost is that a Kotlin property rename changes the file format, which `ArchiveFormatTest` pins
 * deliberately: this is a contract with files already sitting on users' phones.
 *
 * **No `audit_log`, and no image bytes.** The audit table has no `profile_id` to scope by, and
 * receipts stay encrypted on the device rather than being decrypted into a file the user may email
 * to themselves. Both are argued in ADR-0023.
 *
 * Input:  [archiveVersion] — this file format's own version, bumped when the envelope changes;
 *         [schemaVersion] — `CfoDatabase.VERSION` at export time, so an import can refuse an archive
 *         it cannot faithfully restore; [exportedAtUtcMillis] — from the injected `Clock`
 *         (TIM-001), for the user's benefit rather than the importer's; the rest — one list per
 *         profile-scoped table, in the order a restore must insert them.
 * Output: an immutable value, serialisable to JSON.
 */
@Serializable
data class CfoArchive(
    val archiveVersion: Int,
    val schemaVersion: Int,
    val exportedAtUtcMillis: Long,
    val profiles: List<ProfileEntity> = emptyList(),
    val accounts: List<AccountEntity> = emptyList(),
    val categories: List<CategoryEntity> = emptyList(),
    val transactions: List<TransactionEntity> = emptyList(),
    val transactionSplits: List<TransactionSplitEntity> = emptyList(),
    val tags: List<TagEntity> = emptyList(),
    val transactionTags: List<TransactionTagEntity> = emptyList(),
    val budgets: List<BudgetEntity> = emptyList(),
    val budgetAlerts: List<BudgetAlertEntity> = emptyList(),
    val budgetReviews: List<BudgetReviewEntity> = emptyList(),
    val recurringRules: List<RecurringRuleEntity> = emptyList(),
    val netWorthSnapshots: List<NetWorthSnapshotEntity> = emptyList(),
    val attachments: List<AttachmentEntity> = emptyList(),
    val smsDrafts: List<SmsDraftEntity> = emptyList(),
    val creditCards: List<CreditCardEntity> = emptyList(),
    val cardAlerts: List<CardAlertEntity> = emptyList(),
    val loans: List<LoanEntity> = emptyList(),
    val investmentHoldings: List<InvestmentHoldingEntity> = emptyList(),
    val investmentLots: List<InvestmentLotEntity> = emptyList(),
    val goals: List<GoalEntity> = emptyList(),
    val goalContributions: List<GoalContributionEntity> = emptyList(),
    val goalFundingAccounts: List<GoalFundingAccountEntity> = emptyList(),
    /** Issue 9.5: the insight feed, with each card's status — what the user said, not just what was said to them. */
    val insights: List<InsightEntity> = emptyList(),
    /** Issue 9.6: what was sent — so a restored phone keeps its caps and never re-sends an alert. */
    val notificationLog: List<NotificationLogEntity> = emptyList(),
    val purchaseTraces: List<PurchaseTraceEntity> = emptyList(),
    val purchaseTraceGates: List<PurchaseTraceGateEntity> = emptyList(),
    val wishlistItems: List<WishlistItemEntity> = emptyList(),
    val interviewAnswers: List<InterviewAnswerEntity> = emptyList(),
    /** Issue 10.4: the vehicles, and the history every prediction about them is made from. */
    val vehicles: List<VehicleEntity> = emptyList(),
    val vehicleOdometer: List<VehicleOdometerEntity> = emptyList(),
    val vehicleServices: List<VehicleServiceEntity> = emptyList(),
    val vehicleRenewals: List<VehicleRenewalEntity> = emptyList(),
) {
    companion object {
        /**
         * The archive format's version — **not** the schema's.
         *
         * Why: two numbers because they change for different reasons. The schema moves whenever a
         *      column is added; the envelope moves only when the file's own structure does. An
         *      importer that conflated them would refuse archives it could read perfectly well.
         */
        const val VERSION = 1
    }
}

/**
 * What an import did, for the screen to report (issue 5.4; P-02).
 *
 * Why:  "Imported" on its own is a claim the user has to take on trust after an operation that
 *       replaced everything they had. A row count is something they can check against the archive
 *       they picked, and against the dashboard a second later.
 * Result: what `ArchiveRepository.import` returns.
 * Changelog: 2026-08-16 — Created for issue 5.4.
 *
 * Input:  [rowsImported] — every row written, across every table; [exportedAtUtcMillis] — when the
 *         archive was taken, so the screen can say *which* backup was restored.
 * Output: an immutable value.
 */
data class ImportSummary(
    val rowsImported: Int,
    val exportedAtUtcMillis: Long,
)
