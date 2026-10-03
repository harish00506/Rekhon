package com.aicfo.data.repository

import androidx.room.withTransaction
import com.aicfo.core.common.AppError
import com.aicfo.core.common.Clock
import com.aicfo.core.common.DispatcherProvider
import com.aicfo.core.common.Err
import com.aicfo.core.common.Ok
import com.aicfo.core.common.Result
import com.aicfo.core.common.flatMap
import com.aicfo.core.common.runCatchingToResult
import com.aicfo.core.database.CfoDatabase
import com.aicfo.core.database.dao.ArchiveDao
import com.aicfo.core.datastore.ConsentFeature
import com.aicfo.core.datastore.ConsentState
import com.aicfo.core.datastore.ConsentStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json

/**
 * Writes and restores §5.10's local JSON archive (issue 5.4; §34, P-01, ARC-005).
 *
 * Why:  portability. Everything this app knows lives in one encrypted database on one phone, and
 *       until this existed there was no way to get any of it out — not to move devices, not to keep
 *       a copy, not to check what the app is actually holding. For an app whose first principle is
 *       "your data stays on your device", refusing to hand it back is the wrong half of that
 *       promise.
 * What: one read that serialises every profile-scoped table, and one write that replaces them.
 * Result: a file the user owns, can read, and can restore.
 * Changelog: 2026-08-16 — Created for issue 5.4.
 *
 * **Local only. There is no network path here and there must never be one** (P-01, P-04): the
 * repository hands back a `String`, and the screen writes it wherever the user pointed the system
 * file picker. Where the file goes after that is the user's decision, which is the entire point of
 * a portability feature and the reason this archive is not encrypted — Epic 8's backup is.
 *
 * **The only class in this issue allowed to touch a DAO (ARC-005).** Nothing above it sees a Room
 * entity: `export` returns text and `import` takes text.
 */
interface ArchiveRepository {
    /**
     * Serialises the active profile into an archive.
     *
     * Why:    every row, including tombstones and future-dated transactions — a "lossless" archive
     *         that dropped the user's deletions would restore rows they had removed, which is worse
     *         than not restoring at all.
     * Result: `Ok(json)` — pretty-printed, because a file the user is meant to be able to read is
     *         not one to minify. `Err(AppError.Storage)` if a read fails, with nothing written.
     * Input:  none — the active profile (so the demo exports itself, never the real profile).
     * Output: `Result<String, AppError>`.
     */
    suspend fun export(): Result<String, AppError>

    /**
     * Replaces the active profile's data with an archive's.
     *
     * Why:    **replace, not merge**, and it is the destructive choice on purpose. "Restore my
     *         backup" means the device ends up in the state the archive describes; a merge would
     *         resurrect rows the user deleted after taking it and would make "lossless" untestable.
     *         The screen confirms before calling this (ADR-0023).
     *
     *         **One transaction around the wipe and the insert**, so a malformed archive that fails
     *         halfway leaves the database exactly as it was. Importing is the one operation in this
     *         app that can destroy everything, and a half-applied one would be unrecoverable.
     * Result: `Ok(summary)` with the row count and the archive's own timestamp;
     *         `Err(AppError.Validation)` when the JSON will not parse, its `schemaVersion` is not
     *         this build's, or it belongs to another profile — **and in every case nothing has been
     *         deleted**;
     *         `Err(AppError.Storage)` if the write fails, rolled back.
     * Input:  [json] — an archive as [export] wrote it. Output: `Result<ImportSummary, AppError>`.
     */
    suspend fun import(json: String): Result<ImportSummary, AppError>
}

/**
 * The Room-backed [ArchiveRepository].
 * Why:    ARC-003 — one public interface, an internal implementation, assembled by the DI graph.
 * Result: the implementation injected into the dashboard ViewModel.
 * Changelog: 2026-08-16 — Created for issue 5.4.
 *
 * Input:  [database] — taken whole, because the archive spans every table and the import's
 *         atomicity rests on `withTransaction`, which is a method on the database; [clock] — stamps
 *         the archive (TIM-001); [dispatchers]; [activeProfileId] — which profile is exported and
 *         replaced, so the demo can be exported without ever touching the real one (ADR-0006);
 *         [consents] — the Proto DataStore consent ledger (issue 11.5), read on **export only**.
 * Output: a working repository.
 *
 * The ledger is injected rather than reached through the database because that is not where it
 * lives: issue 1.9 put the consent record in Proto DataStore, and an export covering every Room
 * table while omitting it would hand a user their data and keep the record of what they had agreed
 * to — the half of DPDP's right of access that is easiest to miss (ADR-0061).
 */
internal class RoomArchiveRepository(
    private val database: CfoDatabase,
    private val clock: Clock,
    private val dispatchers: DispatcherProvider,
    private val activeProfileId: Flow<String>,
    private val consents: ConsentStore,
) : ArchiveRepository {
    override suspend fun export(): Result<String, AppError> =
        withContext(dispatchers.io) {
            // Read before the tables, and `flatMap` because it may fail the export (`consentRecord`).
            consentRecord().flatMap { record ->
                runCatchingToResult {
                    val profileId = activeProfileId.first()
                    val dao = database.archiveDao()
                    JSON.encodeToString(
                        CfoArchive(
                            archiveVersion = CfoArchive.VERSION,
                            schemaVersion = CfoDatabase.VERSION,
                            exportedAtUtcMillis = clock.nowUtcMillis(),
                            profiles = dao.profiles(profileId),
                            accounts = dao.accounts(profileId),
                            categories = dao.categories(profileId),
                            transactions = dao.transactions(profileId),
                            transactionSplits = dao.transactionSplits(profileId),
                            tags = dao.tags(profileId),
                            transactionTags = dao.transactionTags(profileId),
                            budgets = dao.budgets(profileId),
                            budgetAlerts = dao.budgetAlerts(profileId),
                            budgetReviews = dao.budgetReviews(profileId),
                            recurringRules = dao.recurringRules(profileId),
                            netWorthSnapshots = dao.netWorthSnapshots(profileId),
                            attachments = dao.attachments(profileId),
                            smsDrafts = dao.smsDrafts(profileId),
                            creditCards = dao.creditCards(profileId),
                            cardAlerts = dao.cardAlerts(profileId),
                            loans = dao.loans(profileId),
                            investmentHoldings = dao.investmentHoldings(profileId),
                            investmentLots = dao.investmentLots(profileId),
                            goals = dao.goals(profileId),
                            goalContributions = dao.goalContributions(profileId),
                            goalFundingAccounts = dao.goalFundingAccounts(profileId),
                            insights = dao.insights(profileId),
                            notificationLog = dao.notificationLog(profileId),
                        ).withHousehold(dao, profileId).withAdvisor(dao, profileId).withConsentRecord(record),
                    )
                }
            }
        }

    /**
     * Reads the consent ledger for the export (issue 11.5; §32, DPDP).
     *
     * Why:    its own function for two reasons. `export` was at detekt's length limit, and this is
     *         not a table read like the forty around it — it is the one input that comes from
     *         outside the database and the one whose failure stops the export.
     *
     *         **Allowed to fail the whole export**, deliberately. An archive written with an empty
     *         consent list would read as "this app was granted nothing", and a document whose
     *         purpose is to be an authoritative account of the app's permissions must fail loudly
     *         rather than quietly understate what the app was permitted to do.
     * Result: `Ok` with one row per declared consent, or `Err` carrying the ledger's own failure.
     * Input:  none. Output: `Result<List<ConsentRecord>, AppError>`.
     * Changelog: 2026-10-01 — Created for issue 11.5.
     */
    private suspend fun consentRecord(): Result<List<ConsentRecord>, AppError> =
        when (val ledger = consents.observeAll().first()) {
            is Ok -> Ok(ledger.value.toConsentRecords())
            is Err -> ledger
        }

    /**
     * Adds the household to an archive (issue 13.1; ADR-0069).
     * Why:    its own function for the same reason `withAdvisor` is — one more read inside `export`
     *         took it past detekt's 40-line limit — and because it is the one read in the export
     *         that is **not** scoped by a `profile_id` column. `household` sits above the profile,
     *         so the DAO reaches it through the profile's `household_id` instead, and keeping that
     *         here rather than in the list of forty says so.
     * Result: the archive with the household. Input: [dao]; [profileId]. Output: [CfoArchive].
     */
    private suspend fun CfoArchive.withHousehold(
        dao: ArchiveDao,
        profileId: String,
    ): CfoArchive = copy(households = dao.households(profileId))

    /**
     * Adds the advisor's and the buy list's tables to an archive (issues 10.1, 10.2).
     * Why:    four more reads inside `export` would take it past detekt's 40-line limit, and these
     *         four belong together anyway — a kept verdict without its gates, or a wish without its
     *         answers, is half a record.
     * Result: the archive with them. Input: [dao]; [profileId]. Output: [CfoArchive].
     */
    private suspend fun CfoArchive.withAdvisor(
        dao: ArchiveDao,
        profileId: String,
    ): CfoArchive =
        copy(
            purchaseTraces = dao.purchaseTraces(profileId),
            purchaseTraceGates = dao.purchaseTraceGates(profileId),
            wishlistItems = dao.wishlistItems(profileId),
            interviewAnswers = dao.interviewAnswers(profileId),
            vehicles = dao.vehicles(profileId),
            vehicleOdometer = dao.vehicleOdometer(profileId),
            vehicleServices = dao.vehicleServices(profileId),
            vehicleRenewals = dao.vehicleRenewals(profileId),
            marketCloses = dao.marketCloses(profileId),
        )

    /**
     * Attaches the consent record to an archive (issue 11.5; §32, DPDP).
     * Why:    chained rather than passed into the constructor, for the reason [withAdvisor] gives —
     *         `export` sits at detekt's length limit — and because this genuinely is not a table
     *         read: it is the one list in the archive that does not come from a DAO, and the one a
     *         restore deliberately ignores (ADR-0061).
     * Result: the archive with its permissions recorded.
     * Input:  the receiver; [record] — one row per declared consent. Output: [CfoArchive].
     * Changelog: 2026-10-01 — Created for issue 11.5.
     */
    private fun CfoArchive.withConsentRecord(record: List<ConsentRecord>): CfoArchive = copy(consents = record)

    override suspend fun import(json: String): Result<ImportSummary, AppError> =
        withContext(dispatchers.io) {
            // Parsed and checked BEFORE anything is deleted. An archive that will not decode, or
            // that came from a schema this build cannot restore faithfully, must leave the user's
            // data exactly where it was — the failure mode this ordering exists to prevent is a
            // wipe followed by a parse error.
            val profileId = activeProfileId.first()
            val archive =
                when (val decoded = decode(json, profileId)) {
                    is Ok -> decoded.value
                    is Err -> return@withContext decoded
                }

            runCatchingToResult {
                database.withTransaction {
                    wipe(profileId)
                    restore(archive)
                }
                ImportSummary(rowsImported = archive.rowCount(), exportedAtUtcMillis = archive.exportedAtUtcMillis)
            }
        }

    /**
     * Parses an archive and checks it belongs to this build.
     * Why:    split out so the ordering above is obvious — this runs, and only then does anything
     *         get deleted. A `Validation` error rather than `Storage`: nothing went wrong with the
     *         device, the file is simply not one this build can restore.
     *
     *         **The archive must belong to the profile it is replacing** (issue 8.2). The wipe
     *         clears the *active* profile and the insert writes whatever profile the file carries, so
     *         a demo archive imported into the real profile used to delete `local`, write rows under
     *         `demo`, and report success over an app that then showed nothing. An archive with no
     *         profile row at all (exported before one existed) carries no other profile's data and
     *         is allowed.
     * Result: `Ok(archive)`, or `Err(AppError.Validation)` naming what was wrong.
     * Input:  [json]; [profileId] — the active profile, the one about to be replaced.
     * Output: `Result<CfoArchive, AppError>`.
     * Changelog: 2026-08-16 — Created for issue 5.4.
     *   2026-09-18 — Issue 8.2 added the profile check.
     */
    private fun decode(
        json: String,
        profileId: String,
    ): Result<CfoArchive, AppError> {
        val archive =
            try {
                JSON.decodeFromString<CfoArchive>(json)
            } catch (_: IllegalArgumentException) {
                // kotlinx wraps every malformed-input case in SerializationException, which extends
                // IllegalArgumentException. Caught narrowly rather than by `Exception`, so a bug in
                // this repository still surfaces as a crash rather than as "bad file".
                null
            }
        return when {
            archive == null -> Err(AppError.Validation(field = FIELD_UNREADABLE))
            archive.schemaVersion != CfoDatabase.VERSION -> Err(AppError.Validation(field = FIELD_WRONG_SCHEMA))
            archive.profiles.any { it.id != profileId } -> Err(AppError.Validation(field = FIELD_WRONG_PROFILE))
            else -> Ok(archive)
        }
    }

    /**
     * Clears the profile the archive is about to replace.
     * Why:    **reuses [com.aicfo.core.database.dao.DemoDao]**, which already deletes every
     *         profile-scoped table in FK-safe order and is guarded by `countRowsFor`. A second wipe
     *         written here would be one that drifts from it, and the table it forgot would be a row
     *         the restore silently kept from the *old* data — a merge nobody asked for, hiding
     *         inside a replace.
     * Result: no rows remain for [profileId]. Input: [profileId]. Output: none (suspends).
     */
    private suspend fun wipe(profileId: String) {
        val demo = database.demoDao()
        // Children before parents, exactly as DemoModeRepository.exit() orders them.
        demo.deleteInsights(profileId)
        demo.deleteNotificationLog(profileId)
        demo.deleteBudgetAlerts(profileId)
        demo.deleteCardAlerts(profileId)
        demo.deleteBudgets(profileId)
        demo.deleteBudgetReviews(profileId)
        demo.deleteNetWorthSnapshots(profileId)
        demo.deleteRecurringRules(profileId)
        demo.deleteTransactionSplits(profileId)
        demo.deleteAttachments(profileId)
        demo.deleteSmsDrafts(profileId)
        demo.deleteTransactionTags(profileId)
        demo.deleteTags(profileId)
        demo.deleteTransactions(profileId)
        demo.deleteCategories(profileId)
        demo.deleteCreditCards(profileId)
        demo.deleteInvestmentLots(profileId)
        demo.deleteInvestmentHoldings(profileId)
        demo.deleteGoalContributions(profileId)
        demo.deleteGoalFundingAccounts(profileId)
        demo.deleteGoals(profileId)
        demo.deleteLoans(profileId)
        demo.deleteAccounts(profileId)
        demo.deleteProfile(profileId)
    }

    /**
     * Inserts an archive's rows.
     * Why:    parents before children — the mirror of [wipe]'s order. The schema declares no foreign
     *         keys (issue 1.6 chose application-level integrity), so this is not enforced by SQLite;
     *         it is ordered anyway so the sequence stays correct if they are ever added, and so a
     *         reader can see the shape of the data.
     * Result: every row in [archive] is present. Input: [archive]. Output: none (suspends).
     */
    private suspend fun restore(archive: CfoArchive) {
        val dao = database.archiveDao()
        // Issue 13.1: the household first — the profile row points at it. Deliberately not in
        // `wipe`: `household` is not profile-scoped, so a per-profile restore has no business
        // deleting a row another profile may belong to. REPLACE makes the re-insert enough.
        dao.insertHouseholds(archive.households)
        dao.insertProfiles(archive.profiles)
        dao.insertAccounts(archive.accounts)
        dao.insertCategories(archive.categories)
        dao.insertTransactions(archive.transactions)
        dao.insertTransactionSplits(archive.transactionSplits)
        dao.insertTags(archive.tags)
        dao.insertTransactionTags(archive.transactionTags)
        dao.insertBudgets(archive.budgets)
        dao.insertBudgetAlerts(archive.budgetAlerts)
        dao.insertBudgetReviews(archive.budgetReviews)
        dao.insertRecurringRules(archive.recurringRules)
        dao.insertNetWorthSnapshots(archive.netWorthSnapshots)
        dao.insertAttachments(archive.attachments)
        dao.insertSmsDrafts(archive.smsDrafts)
        dao.insertCreditCards(archive.creditCards)
        dao.insertCardAlerts(archive.cardAlerts)
        dao.insertLoans(archive.loans)
        dao.insertInvestmentHoldings(archive.investmentHoldings)
        dao.insertInvestmentLots(archive.investmentLots)
        dao.insertGoals(archive.goals)
        dao.insertGoalContributions(archive.goalContributions)
        dao.insertGoalFundingAccounts(archive.goalFundingAccounts)
        dao.insertInsights(archive.insights)
        dao.insertNotificationLog(archive.notificationLog)
        dao.insertPurchaseTraces(archive.purchaseTraces)
        dao.insertPurchaseTraceGates(archive.purchaseTraceGates)
        dao.insertWishlistItems(archive.wishlistItems)
        dao.insertInterviewAnswers(archive.interviewAnswers)
        dao.insertVehicles(archive.vehicles)
        dao.insertVehicleOdometer(archive.vehicleOdometer)
        dao.insertVehicleServices(archive.vehicleServices)
        dao.insertVehicleRenewals(archive.vehicleRenewals)
        dao.insertMarketCloses(archive.marketCloses)
    }

    private companion object {
        /**
         * The two ways an archive can be refused, as stable codes the screen maps to copy.
         *
         * Why: **codes, not sentences.** `AppError.Validation` carries a field name and no message,
         *      which is right — the wording belongs in the feature's `strings.xml` (§21.6), and a
         *      serialisation exception's own message can quote the offending JSON, which is the
         *      user's financial data and must not travel in an error that might be logged.
         *      Two codes rather than one because the user's next step differs: a file that will not
         *      parse is the wrong file, while a schema mismatch is the right file and the wrong
         *      app version.
         */
        const val FIELD_UNREADABLE = "archive.unreadable"
        const val FIELD_WRONG_SCHEMA = "archive.schemaVersion"

        /** Issue 8.2: the archive is another profile's — in practice, one taken inside the demo. */
        const val FIELD_WRONG_PROFILE = "archive.profile"

        /**
         * The archive's JSON settings.
         *
         * `prettyPrint` because §5.10's archive is meant to be **readable** — a user who opens it
         * should see their own data, not one line of six megabytes. `encodeDefaults` so an empty
         * table is written as `[]` rather than omitted: a restore reading an older archive should
         * see the table exists and is empty, not have to infer it. `ignoreUnknownKeys` so an archive
         * from a build with an extra column still decodes — the `schemaVersion` gate is what decides
         * whether it *may*, and a parse failure would be the wrong error for it.
         */
        val JSON =
            Json {
                prettyPrint = true
                encodeDefaults = true
                ignoreUnknownKeys = true
            }
    }
}

/**
 * Every row the archive carries.
 * Why:    what [ImportSummary] reports — a count the user can check against the file they picked,
 *         rather than a bare "done" after an operation that replaced everything.
 * Result: the total. Input: the receiver. Output: [Int].
 * Changelog: 2026-08-16 — Created for issue 5.4.
 *   2026-09-06 — Issue 7.4 added the eight lists this had never counted. Five of them
 *   (`credit_card`, `card_alert`, `loan`, `investment_holding`, `investment_lot`) were exported and
 *   restored correctly and simply not reported, so the user was told a smaller number than the file
 *   held. The other three are 7.4's own. A count that omits a table under-reports a restore the
 *   user is being asked to check.
 */
internal fun CfoArchive.rowCount(): Int =
    profiles.size + accounts.size + categories.size + transactions.size + transactionSplits.size +
        tags.size + transactionTags.size + budgets.size + budgetAlerts.size + budgetReviews.size +
        recurringRules.size + netWorthSnapshots.size + attachments.size + smsDrafts.size +
        creditCards.size + cardAlerts.size + loans.size + investmentHoldings.size +
        investmentLots.size + goals.size + goalContributions.size + goalFundingAccounts.size +
        insights.size + notificationLog.size + purchaseTraces.size + purchaseTraceGates.size +
        wishlistItems.size + interviewAnswers.size + vehicles.size + vehicleOdometer.size +
        vehicleServices.size + vehicleRenewals.size + marketCloses.size

/**
 * Turns the consent ledger into the export's rows (issue 11.5; §32, DPDP).
 *
 * Why:  the ledger holds a row only for a feature somebody has answered for, and the export must
 *       list **every** feature regardless — "never asked" is a fact about the user's choices, and a
 *       record showing only the answered ones would read as a shorter list of permissions than the
 *       app actually has. That is the exact shape of the vacuous test issue 11.3 was caught on, so
 *       this iterates `ConsentFeature.entries` rather than the map.
 * What: one row per declared feature, filling absences with `NOT_GRANTED` and inventing no dates.
 * Result: a complete, ordered account of the app's permissions.
 * Input:  the receiver — what the ledger has recorded, which is empty on a fresh install.
 * Output: one [ConsentRecord] per [ConsentFeature], in the enum's own order.
 * Changelog: 2026-10-01 — Created for issue 11.5.
 */
private fun Map<ConsentFeature, ConsentState>.toConsentRecords(): List<ConsentRecord> =
    ConsentFeature.entries.map { feature ->
        val state = this[feature] ?: ConsentState.NOT_GRANTED
        ConsentRecord(
            featureId = feature.id,
            granted = state.granted,
            grantedAtUtcMillis = state.grantedAtUtcMillis,
            revokedAtUtcMillis = state.revokedAtUtcMillis,
        )
    }
