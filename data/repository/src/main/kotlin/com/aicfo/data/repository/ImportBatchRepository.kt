package com.aicfo.data.repository

import com.aicfo.core.common.AppError
import com.aicfo.core.common.Clock
import com.aicfo.core.common.DispatcherProvider
import com.aicfo.core.common.Err
import com.aicfo.core.common.IdGenerator
import com.aicfo.core.common.Result
import com.aicfo.core.common.runCatchingToResult
import com.aicfo.core.database.CfoDatabase
import com.aicfo.core.database.entity.ImportBatchEntity
import com.aicfo.core.model.ImportBatch
import com.aicfo.core.model.TransactionSource
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

/**
 * Where imported transactions came from (§20.1, §33, ADR-0078).
 *
 * Why:  §33's forward-compatibility table promises "`import_batches` supports statement-grade
 *       provenance", and §20.1 lists the table. ADR-0074 measured both and found nothing: when AA
 *       ingest landed there would be nowhere to record which fetch a row came from, so a duplicate
 *       or partial import could not be traced to the pull that caused it, and a user who imported
 *       twice would have no way to tell the app which copy to keep.
 * What: records a run, lists a profile's runs, and resolves a transaction back to its run.
 * Result: the question §33 is about — "which import brought this row in, and what did it cover?" —
 *         becomes answerable. **Nothing calls `record` yet**: AA ingest is designed and stubbed
 *         (ADR-0074) and no file import exists. That is the same forward-compatibility shape
 *         `TransactionSource.ACCOUNT_AGGREGATOR` ships in, and for the same reason — the history is
 *         what cannot be recovered later, so the place to put it has to exist first.
 * Changelog: 2026-10-10 — Created (ADR-0078).
 *
 * ARC-003: one public interface, an internal implementation, assembled by [RepositoryFactory].
 */
interface ImportBatchRepository {
    /**
     * Records that an import ran.
     * Why:    the counts and the window are taken from the caller rather than derived, because they
     *         describe **what the source offered**, which the database cannot see. Deriving
     *         `acceptedCount` from `transactions` would make a provenance record change whenever
     *         the user deletes a row — evidence that moves is not evidence.
     * Result: `Ok(ImportBatch)` with its minted id, already written; `Err(Validation)` for counts
     *         that cannot be true; `Err` from [runCatchingToResult] if the write fails.
     * Input:  [source] — how the data arrived, a [TransactionSource]; [fetchedAtUtcMillis] — when
     *         the data was true at the source (TIM-001), `null` for a file;
     *         [windowStartIsoDate], [windowEndIsoDate] — the period covered, ISO `yyyy-MM-dd`
     *         (TIM-002), `null` when unstated; [complete] — false when the window was truncated;
     *         [lineCount] — rows offered; [acceptedCount] — rows kept.
     * Output: `Result<ImportBatch, AppError>`.
     */
    @Suppress("LongParameterList") // Seven facts about one run; a parameter object would be the row.
    suspend fun record(
        source: TransactionSource,
        fetchedAtUtcMillis: Long? = null,
        windowStartIsoDate: String? = null,
        windowEndIsoDate: String? = null,
        complete: Boolean = true,
        lineCount: Int = 0,
        acceptedCount: Int = 0,
    ): Result<ImportBatch, AppError>

    /**
     * Observes the active profile's import history, newest first.
     * Why:    the profile is not a parameter, for the reason every read here omits it — demo mode
     *         puts sample data under a second profile id, and "which profile?" has one right answer
     *         at any moment.
     * Result: emits again whenever a batch is written. Empty is the normal state today.
     * Input:  none. Output: `Flow<List<ImportBatch>>`.
     */
    fun observeBatches(): Flow<List<ImportBatch>>

    /**
     * The run one transaction arrived in — §33's question, asked directly.
     * Result: the batch, or `null` when the row was not imported. **`null` means not imported**,
     *         never "imported, origin unknown": [TransactionRepository] refuses to write a batch id
     *         that names nothing, so that state cannot exist.
     * Input:  [transactionId]. Output: `ImportBatch?`.
     */
    suspend fun batchFor(transactionId: String): ImportBatch?
}

/**
 * The Room-backed [ImportBatchRepository].
 * Why:    ARC-003 — the implementation is internal and assembled by the DI graph.
 * Result: the repository injected wherever provenance is read.
 * Changelog: 2026-10-10 — Created (ADR-0078).
 *
 * Input:  [database]; [clock] — stamps the run (TIM-001, never the wall clock); [ids] — mints the
 *         batch id (P-08); [dispatchers] — database I/O off the caller's thread; [activeProfileId].
 * Output: a working repository.
 */
internal class RoomImportBatchRepository(
    private val database: CfoDatabase,
    private val clock: Clock,
    private val ids: IdGenerator,
    private val dispatchers: DispatcherProvider,
    private val activeProfileId: Flow<String>,
) : ImportBatchRepository {
    @Suppress("LongParameterList") // Matches the interface, whose own comment explains the count.
    override suspend fun record(
        source: TransactionSource,
        fetchedAtUtcMillis: Long?,
        windowStartIsoDate: String?,
        windowEndIsoDate: String?,
        complete: Boolean,
        lineCount: Int,
        acceptedCount: Int,
    ): Result<ImportBatch, AppError> {
        // Refused here rather than left to ImportBatch's `require`, so an impossible count is a
        // Result the caller handles and not a crash (§21.6: exceptions are for programmer errors).
        if (lineCount < 0) return Err(AppError.Validation("lineCount"))
        if (acceptedCount < 0 || acceptedCount > lineCount) return Err(AppError.Validation("acceptedCount"))

        val profileId = activeProfileId.first()
        val now = clock.nowUtcMillis()
        val entity =
            ImportBatchEntity(
                id = ids.newId(ID_PREFIX),
                profileId = profileId,
                source = source.storedValue,
                startedAtUtcMillis = now,
                fetchedAtUtcMillis = fetchedAtUtcMillis,
                windowStartIsoDate = windowStartIsoDate,
                windowEndIsoDate = windowEndIsoDate,
                complete = complete,
                lineCount = lineCount,
                acceptedCount = acceptedCount,
                createdAtUtcMillis = now,
                updatedAtUtcMillis = now,
            )
        return withContext(dispatchers.io) {
            runCatchingToResult {
                database.importBatchDao().upsert(entity)
                entity.toDomain()
            }
        }
    }

    override fun observeBatches(): Flow<List<ImportBatch>> =
        activeProfileId
            .flatMapLatest { profileId -> database.importBatchDao().observeFor(profileId) }
            .map { rows -> rows.map(ImportBatchEntity::toDomain) }
            .flowOn(dispatchers.io)

    override suspend fun batchFor(transactionId: String): ImportBatch? =
        withContext(dispatchers.io) {
            database.importBatchDao().forTransaction(transactionId)?.toDomain()
        }

    private companion object {
        /** Mirrors every other id prefix here: short, stable, and visible in a stored row. */
        const val ID_PREFIX = "imp"
    }
}

/**
 * Maps the row to the domain model (ARC-005: a ViewModel never sees a Room type).
 * Why:    the tombstone and the audit timestamps are the database's business, not part of an answer
 *         to "where did this come from", so they stop here.
 * Result: an [ImportBatch]. A `source` this build does not recognise falls back to
 *         [TransactionSource.IMPORT] rather than dropping the row — the batch's *window and counts*
 *         are still a true answer, and losing them because a newer build wrote an unfamiliar label
 *         would discard more than it protects.
 * Input:  the entity. Output: [ImportBatch].
 * Changelog: 2026-10-10 — Created (ADR-0078).
 */
private fun ImportBatchEntity.toDomain(): ImportBatch =
    ImportBatch(
        id = id,
        source = TransactionSource.fromStored(source) ?: TransactionSource.IMPORT,
        startedAtUtcMillis = startedAtUtcMillis,
        fetchedAtUtcMillis = fetchedAtUtcMillis,
        windowStartIsoDate = windowStartIsoDate,
        windowEndIsoDate = windowEndIsoDate,
        complete = complete,
        lineCount = lineCount,
        acceptedCount = acceptedCount,
    )
