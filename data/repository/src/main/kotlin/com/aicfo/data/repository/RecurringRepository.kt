package com.aicfo.data.repository

import com.aicfo.core.common.AppError
import com.aicfo.core.common.Clock
import com.aicfo.core.common.DispatcherProvider
import com.aicfo.core.common.Err
import com.aicfo.core.common.Ok
import com.aicfo.core.common.Result
import com.aicfo.core.common.getOrNull
import com.aicfo.core.common.runCatchingToResult
import com.aicfo.core.database.CfoDatabase
import com.aicfo.core.database.dao.RecurringCandidateRow
import com.aicfo.core.database.entity.RecurringRuleEntity
import com.aicfo.core.model.Money
import com.aicfo.domain.engines.recurring.RecurringCandidate
import com.aicfo.domain.engines.recurring.RecurringEngine
import com.aicfo.domain.engines.recurring.RecurringInput
import com.aicfo.domain.engines.recurring.RecurringSeries
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext

/**
 * Proposes recurring series, and records what the user decided (issue 3.7; FR-TXN-006, ARC-005).
 *
 * Why:  FR-TXN-006 has two halves and this class is the second one. The engine finds the pattern;
 *       this decides **which rows it may look at** and **which proposals the user has already
 *       answered**. That second half is the load-bearing one: a detector that re-proposes a series
 *       the user rejected last week is the app arguing with them, and the acceptance criterion
 *       *"decisions feed back as data, not code"* is satisfied here — by a row in `recurring_rule`,
 *       read back on the next emission — rather than by a flag somewhere in the UI.
 * What: one observed read for the proposals, and two writes for confirm and dismiss.
 * Result: the transactions list can offer a series and never offer it twice.
 * Changelog: 2026-08-05 — Created for issue 3.7.
 *
 * **The only class in this issue allowed to touch a DAO (ARC-005).** The engine above it is pure
 * Kotlin and never sees Room; the ViewModel below it sees [RecurringSeries] and never sees an entity.
 *
 * **It detects nothing itself (P-03)** and **creates nothing on its own (P-07)**: every proposal
 * comes from [RecurringEngine], and a `recurring_rule` row is written only from [confirm] or
 * [dismiss] — both of which are user actions.
 */
interface RecurringRepository {
    /**
     * Observes the series worth proposing to whichever profile is active.
     *
     * Why:    re-run on every ledger change, because the whole point is that the *second* rent
     *         payment turns a one-off into a proposal — a snapshot taken at app start would show
     *         nothing until the next launch. Cheap enough to re-run: the query is four columns over
     *         a bounded window and the detector is a group-and-sort.
     * Result: emits on every change to the ledger **or** to the user's decisions. Empty for a new
     *         profile, and empty when a read fails — a screen section that renders nothing is the
     *         right degradation for a feature the user did not ask for (P-04).
     * Input:  none (the window comes from the injected `Clock`, TIM-001).
     * Output: `Flow<List<RecurringSeries>>`, ordered by merchant.
     */
    fun observeSuggestions(): Flow<List<RecurringSeries>>

    /**
     * Records that the user accepted a proposal (FR-TXN-006's "user confirms").
     * Why:    a confirmed rule is what issue 9.2's forecast projects from, so it stores the amount,
     *         the cadence and the next due date the engine derived rather than re-deriving them —
     *         AI-ARC-006's point exactly: the figure and the engine version that produced it are
     *         stored together, so the rule stays explainable after the detector is rewritten.
     * Result: `Ok(Unit)` and the series leaves [observeSuggestions] — **it does not post a
     *         transaction** (P-07); a rule predicts, it does not move money. `Err(Storage)` on a
     *         write failure, with nothing written.
     * Input:  [series] — one proposal, as emitted. Output: `Result<Unit, AppError>`.
     */
    suspend fun confirm(series: RecurringSeries): Result<Unit, AppError>

    /**
     * Records that the user rejected a proposal.
     * Why:    P-07 — "no" has to be as durable as "yes". A dismissal writes the same row as
     *         [confirm] with `dismissed_at_utc_millis` stamped, so the merchant is remembered as
     *         *decided* while the rule itself stays inactive and out of every read.
     * Result: `Ok(Unit)` and the series leaves [observeSuggestions] **and does not come back**.
     *         `Err(Storage)` on a write failure.
     * Input:  [series] — one proposal, as emitted. Output: `Result<Unit, AppError>`.
     */
    suspend fun dismiss(series: RecurringSeries): Result<Unit, AppError>

    companion object {
        /**
         * How far back the detector reads, in days.
         *
         * Why: **two years, because that is the shortest window a yearly cadence can be seen in.**
         *      FR-TXN-006 needs two occurrences, and two yearly occurrences span a year plus a
         *      tolerance — anything shorter would make the yearly branch dead code. It is also a
         *      cap, not an ideal: a window that grew without bound would make the detector's cost
         *      grow with the age of the profile, on a query that re-runs on every ledger change.
         */
        const val LOOKBACK_DAYS = 730L
    }
}

/**
 * The Room-backed [RecurringRepository].
 * Why:    ARC-003 — one public interface, an internal implementation, assembled by the DI graph.
 * Result: the implementation injected into the transactions ViewModel.
 * Changelog: 2026-08-05 — Created for issue 3.7.
 *
 * Input:  [database] — taken whole because the read spans `transactions` and `recurring_rule`;
 *         [engine] — finds every pattern (P-03); [clock] — supplies the window and every stamped
 *         instant, never a wall-clock read (TIM-001); [dispatchers]; [activeProfileId] — which
 *         profile to read and write, so the demo gets its own proposals.
 * Output: a working repository.
 */
@OptIn(ExperimentalCoroutinesApi::class)
internal class RoomRecurringRepository(
    private val database: CfoDatabase,
    private val engine: RecurringEngine,
    private val clock: Clock,
    private val dispatchers: DispatcherProvider,
    private val activeProfileId: Flow<String>,
) : RecurringRepository {
    override fun observeSuggestions(): Flow<List<RecurringSeries>> =
        // flatMapLatest, not map: entering or leaving the demo must *switch* which queries are
        // being observed, cancelling the previous ones — as the repositories beside it do.
        activeProfileId
            .flatMapLatest { profileId ->
                // clock.today() inside the lambda, not captured outside: the flow may outlive
                // midnight, and re-reading is what makes the window roll over with the profile's day.
                val today = clock.today()
                val from = today.minusDays(RecurringRepository.LOOKBACK_DAYS).toString()
                combine(
                    database.transactionDao().observeRecurringCandidates(profileId, from, today.toString()),
                    database.recurringRuleDao().observeDecidedNames(profileId),
                ) { rows, decided -> suggest(rows, decided.toSet()) }
            }
            .flowOn(dispatchers.io)

    override suspend fun confirm(series: RecurringSeries): Result<Unit, AppError> = save(series, dismissed = false)

    override suspend fun dismiss(series: RecurringSeries): Result<Unit, AppError> = save(series, dismissed = true)

    /**
     * Runs the detector and drops anything the user has already answered.
     * Why:    the exclusion is applied **after** detection rather than by filtering the candidate
     *         rows before it. Filtering first would be cheaper and wrong: the rows behind a decided
     *         merchant are still part of the ledger, and removing them could change the cadence the
     *         detector reads for a *different* merchant if the two ever shared a normalised name.
     *         Doing it here also keeps the engine free of any notion of a "decision" at all.
     *
     *         **A detector failure yields an empty list, not a crash.** The realistic cause is a
     *         malformed date on one row, and a proposal section that quietly renders nothing is the
     *         right degradation for a feature nobody asked for — losing the whole transactions
     *         screen to it would not be.
     * Result: the series to propose.
     * Input:  [rows] — the candidate projection; [decided] — lower-cased merchant names the user
     *         has already confirmed, dismissed or deleted a rule for. Output: `List<RecurringSeries>`.
     */
    private fun suggest(
        rows: List<RecurringCandidateRow>,
        decided: Set<String>,
    ): List<RecurringSeries> {
        val candidates =
            rows.map { row ->
                RecurringCandidate(
                    transactionId = row.id,
                    // Non-null by the query's WHERE; the column is nullable, so Kotlin needs the fallback.
                    merchant = row.merchant.orEmpty(),
                    amount = Money(row.amountMinor),
                    bookedOn = row.bookedOnIsoDate,
                )
            }
        val detected = engine.detect(RecurringInput(candidates, nowUtcMillis = clock.nowUtcMillis())).getOrNull()
        return detected.orEmpty().filterNot { it.merchant.trim().lowercase() in decided }
    }

    /**
     * Writes the user's decision as a `recurring_rule` row.
     * Why:    one function for both answers, because confirm and dismiss differ in exactly two
     *         fields. Writing them separately would be two nearly identical bodies whose drift
     *         nobody would notice — and the field that must not drift is `name`, which is what
     *         `observeDecidedNames` matches on to stop the proposal coming back.
     * Result: `Ok(Unit)` once the row is present, or `Err(Storage)` with nothing written.
     * Input:  [series] — the proposal; [dismissed] — `true` when the user said "not recurring".
     * Output: `Result<Unit, AppError>`.
     */
    private suspend fun save(
        series: RecurringSeries,
        dismissed: Boolean,
    ): Result<Unit, AppError> =
        withContext(dispatchers.io) {
            runCatchingToResult {
                val now = clock.nowUtcMillis()
                val profileId = activeProfileId.first()
                database.recurringRuleDao().upsertAll(
                    listOf(
                        RecurringRuleEntity(
                            id = recurringRuleId(profileId, series.merchant),
                            profileId = profileId,
                            name = series.merchant,
                            amountMinor = series.medianAmount.minor,
                            // Lower-cased: the column holds a code, not copy. The UI maps it to a
                            // word through `TransactionLabels`, so a translation never reaches here.
                            cadence = series.cadence.name.lowercase(),
                            nextDueIsoDate = series.nextDueIsoDate,
                            // FR-TXN-009 provenance: this rule came from the detector, not from
                            // quick setup and not from the user typing it in.
                            source = SOURCE_DETECTED,
                            isConfirmed = !dismissed,
                            dismissedAtUtcMillis = now.takeIf { dismissed },
                            createdAtUtcMillis = now,
                            updatedAtUtcMillis = now,
                        ),
                    ),
                )
            }.let { result -> if (result is Err) result else Ok(Unit) }
        }

    private companion object {
        /** `recurring_rule.source` for a row the detector proposed (FR-TXN-009). */
        const val SOURCE_DETECTED = "detected"
    }
}

/**
 * The id of the rule one profile holds for one merchant (issue 3.7).
 *
 * Why:    **derived, not minted** — the mechanism `netWorthSnapshotId` already uses. It makes the
 *         write idempotent under `REPLACE`: a user who confirms a proposal, deletes the rule and is
 *         proposed it again updates one row rather than accumulating three rules for one landlord.
 *         It also removes an `IdGenerator` from the repository — a derived key is reproducible in a
 *         test by construction (P-08), with nothing to inject.
 * Result: a stable id. Input: [profileId]; [merchant] — normalised to the same lower-cased key
 *         `observeDecidedNames` matches on, so case alone cannot mint a second rule.
 * Output: [String].
 * Changelog: 2026-08-05 — Created for issue 3.7.
 */
internal fun recurringRuleId(
    profileId: String,
    merchant: String,
): String = "$profileId:recurring:${merchant.trim().lowercase()}"
