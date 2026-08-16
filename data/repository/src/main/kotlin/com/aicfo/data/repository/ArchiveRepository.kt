package com.aicfo.data.repository

import androidx.room.withTransaction
import com.aicfo.core.common.AppError
import com.aicfo.core.common.Clock
import com.aicfo.core.common.DispatcherProvider
import com.aicfo.core.common.Err
import com.aicfo.core.common.Ok
import com.aicfo.core.common.Result
import com.aicfo.core.common.runCatchingToResult
import com.aicfo.core.database.CfoDatabase
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
     *         `Err(AppError.Validation)` when the JSON will not parse or its `schemaVersion` is not
     *         this build's — **and in both cases nothing has been deleted**;
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
 *         replaced, so the demo can be exported without ever touching the real one (ADR-0006).
 * Output: a working repository.
 */
internal class RoomArchiveRepository(
    private val database: CfoDatabase,
    private val clock: Clock,
    private val dispatchers: DispatcherProvider,
    private val activeProfileId: Flow<String>,
) : ArchiveRepository {
    override suspend fun export(): Result<String, AppError> =
        withContext(dispatchers.io) {
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
                    ),
                )
            }
        }

    override suspend fun import(json: String): Result<ImportSummary, AppError> =
        withContext(dispatchers.io) {
            // Parsed and checked BEFORE anything is deleted. An archive that will not decode, or
            // that came from a schema this build cannot restore faithfully, must leave the user's
            // data exactly where it was — the failure mode this ordering exists to prevent is a
            // wipe followed by a parse error.
            val archive =
                when (val decoded = decode(json)) {
                    is Ok -> decoded.value
                    is Err -> return@withContext decoded
                }

            runCatchingToResult {
                val profileId = activeProfileId.first()
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
     * Result: `Ok(archive)`, or `Err(AppError.Validation)` naming which version was found.
     * Input:  [json]. Output: `Result<CfoArchive, AppError>`.
     */
    private fun decode(json: String): Result<CfoArchive, AppError> {
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
        demo.deleteBudgetAlerts(profileId)
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
 */
internal fun CfoArchive.rowCount(): Int =
    profiles.size + accounts.size + categories.size + transactions.size + transactionSplits.size +
        tags.size + transactionTags.size + budgets.size + budgetAlerts.size + budgetReviews.size +
        recurringRules.size + netWorthSnapshots.size + attachments.size + smsDrafts.size
