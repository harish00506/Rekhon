package com.aicfo.data.repository

import androidx.room.withTransaction
import com.aicfo.core.common.AppError
import com.aicfo.core.common.Clock
import com.aicfo.core.common.DispatcherProvider
import com.aicfo.core.common.Result
import com.aicfo.core.common.runCatchingToResult
import com.aicfo.core.database.CfoDatabase
import com.aicfo.core.database.entity.BudgetEntity
import com.aicfo.core.database.entity.ProfileEntity
import com.aicfo.core.database.entity.RecurringRuleEntity
import com.aicfo.core.model.Money
import com.aicfo.core.model.RuleCitation
import com.aicfo.domain.engines.quicksetup.BudgetEnvelope
import com.aicfo.domain.engines.quicksetup.BudgetNature
import com.aicfo.domain.engines.quicksetup.QuickSetupPlan
import com.aicfo.domain.engines.quicksetup.RecurringSeed
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map

/**
 * Persists what the quick-setup engine derived (issue 2.3; FR-ONB-002, ARC-005, DB-003).
 *
 * Why:  this is the first code in the app that writes the user's *financial* data, and two
 *       properties decide whether that is safe to do at the end of onboarding. It is **atomic** —
 *       the profile, the envelopes and the recurring rules land together or not at all, because a
 *       budget row scoped to a profile that was never written is an orphan no query will ever
 *       return. And it is **idempotent** — ids are derived from the profile, the kind and the
 *       period rather than generated, so running onboarding twice updates the same rows instead of
 *       leaving the user with six envelopes for one month.
 * What: one transactional write, and a read of the persisted envelopes for the dashboard.
 * Result: a first-run user has a profile, a budget and their recurring figures on day one.
 * Changelog: 2026-07-27 — Created for issue 2.3.
 *
 * **The only class in this issue allowed to touch a DAO (ARC-005).** The onboarding and dashboard
 * ViewModels call this interface and never see a Room type.
 *
 * **What it deliberately does not do:** it never invents a row. An empty plan — the skipped step —
 * writes nothing at all, not even the profile, because a profile created for a user who declined
 * to answer anything is fabricated data (P-03) that would then make the app look onboarded.
 */
interface QuickSetupRepository {
    /**
     * Writes a plan and its profile in one transaction.
     * Why:    the caller is the last step of onboarding, where a partial write is the worst
     *         outcome — the user has no way back to the screen that would fix it.
     * What:   upserts the profile, then the budget envelopes, then the recurring rules, all inside
     *         `withTransaction`.
     * Result: `Ok(Unit)` when everything landed, or when [plan] was empty and there was nothing to
     *         write; `Err(Storage)` with **nothing** written otherwise.
     * Input:  [plan] — what the engine derived; [profile] — who it belongs to.
     * Output: `Result<Unit, AppError>`.
     */
    suspend fun applySeeds(
        plan: QuickSetupPlan,
        profile: ProfileSeed,
    ): Result<Unit, AppError>

    /**
     * Observes the envelopes of the most recent budget period, for whichever profile is active.
     *
     * Why:    **the caller does not get to choose, and that is the point** (issue 2.4). Demo mode
     *         puts its sample data under a second profile id, so "which profile?" is a question with
     *         one right answer at any moment and several screens that would each have to get it
     *         right. Answering it here means the dashboard follows the demo without importing it,
     *         and every reader added later inherits the same behaviour instead of forgetting it.
     * Result: emits on every change, **including when the demo is entered or left** — the split
     *         swaps to the sample budget and back. An empty list when there is no budget, which the
     *         screen must render as an empty state, never as three zero envelopes.
     * Input:  none. Output: `Flow<List<BudgetEnvelope>>`.
     * Changelog: 2026-07-28 — Issue 2.4: split from the defaulted-parameter form below.
     */
    fun observeLatestEnvelopes(): Flow<List<BudgetEnvelope>>

    /**
     * Observes one named profile's most recent budget period.
     * Why:    the dashboard needs a needs/wants/savings split before anything has told it which
     *         month to ask about. "The newest period that exists" is the honest answer while the
     *         only budgets in the app came from onboarding; issue 5.1 narrows it to the current
     *         month once the dashboard reads the profile clock.
     *
     *         Kept alongside the no-argument form for callers that genuinely mean one profile —
     *         tests asserting isolation, and the future household screen (issue 13.1).
     * Result: emits on every change; an empty list when that profile has no budget.
     * Input:  [profileId]. Output: `Flow<List<BudgetEnvelope>>`.
     */
    fun observeLatestEnvelopes(profileId: String): Flow<List<BudgetEnvelope>>

    companion object {
        /**
         * The id of the one local profile, until something generates ids.
         *
         * Why:    every row in this app is scoped to a profile and nothing has ever created one.
         *         A generated `UUID.randomUUID()` would make the write non-deterministic (P-08) and
         *         would silently create a second profile on every re-run, orphaning the first
         *         user's data. A constant makes the write idempotent by construction. Issue 13.1
         *         (household mode) is where additional members get generated ids; this one keeps
         *         its name so existing rows never need re-scoping. Recorded in ADR-0004.
         */
        const val DEFAULT_PROFILE_ID = "local"
    }
}

/**
 * Who a plan belongs to — the profile row onboarding creates.
 *
 * Why:    the profile is written here rather than by `SettingsStore` because these are two
 *         different stores answering two different questions: DataStore holds the user's
 *         *settings*, and the `profile` table is the row every financial record is scoped to.
 *         Issue 2.1 wrote the first and, having no financial data to scope, never wrote the second.
 * Result: the argument to [QuickSetupRepository.applySeeds].
 * Changelog: 2026-07-27 — Created for issue 2.3.
 *
 * Input:  [displayName] — local only, may be blank if the user skipped it; [timeZoneId] — IANA
 *         zone, the one TIM-001's calendar logic resolves in; [currencyCode] — ISO-4217;
 *         [id] — defaults to the single local profile.
 * Output: an immutable value.
 */
data class ProfileSeed(
    val displayName: String,
    val timeZoneId: String,
    val currencyCode: String,
    val id: String = QuickSetupRepository.DEFAULT_PROFILE_ID,
)

/**
 * The Room-backed [QuickSetupRepository].
 * Why:    ARC-003 — one public interface, an internal implementation, assembled by the DI graph.
 * Result: the implementation injected into the onboarding and dashboard ViewModels.
 * Changelog: 2026-07-27 — Created for issue 2.3.
 *
 * Input:  [database] — needed whole rather than as three DAOs, because `withTransaction` is a
 *         method on it and the atomicity guarantee is the point of this class; [clock] — stamps
 *         row timestamps, never the wall clock (TIM-001); [dispatchers] — database I/O off the
 *         caller's thread; [activeProfileId] — which profile the no-argument read resolves to,
 *         supplied by `DemoModeRepository`, so this class knows only "which profile is current" and
 *         never that demo mode exists (issue 2.4).
 * Output: a working repository.
 */
@OptIn(ExperimentalCoroutinesApi::class)
internal class RoomQuickSetupRepository(
    private val database: CfoDatabase,
    private val clock: Clock,
    private val dispatchers: DispatcherProvider,
    private val activeProfileId: Flow<String>,
) : QuickSetupRepository {
    override suspend fun applySeeds(
        plan: QuickSetupPlan,
        profile: ProfileSeed,
    ): Result<Unit, AppError> =
        runCatchingToResult {
            // An empty plan is not an error and not a write. Returning early here rather than
            // letting three empty inserts run means the skipped step leaves no profile row behind
            // either — "nothing is fabricated if skipped", enforced at the only place that writes.
            if (plan.isEmpty) return@runCatchingToResult
            val now = clock.nowUtcMillis()
            // withTransaction supplies its own dispatcher, so this is already off the caller's
            // thread; the reads below are inside the same transaction as the writes on purpose.
            database.withTransaction {
                val existing = database.profileDao().findById(profile.id)
                database.profileDao().upsert(
                    ProfileEntity(
                        id = profile.id,
                        displayName = profile.displayName,
                        timeZoneId = profile.timeZoneId,
                        currencyCode = profile.currencyCode,
                        // Preserved, because REPLACE is a delete-and-insert: without this a
                        // re-run would restate when the user joined as today.
                        createdAtUtcMillis = existing?.createdAtUtcMillis ?: now,
                        updatedAtUtcMillis = now,
                    ),
                )
                database.budgetDao().upsertAll(
                    plan.envelopes.map { it.toEntity(profile.id, plan.periodStartIsoDate, now) },
                )
                database.recurringRuleDao().upsertAll(
                    plan.recurring.map { it.toEntity(profile.id, now) },
                )
            }
        }

    override fun observeLatestEnvelopes(): Flow<List<BudgetEnvelope>> =
        // flatMapLatest, not map: entering or leaving the demo must *switch* which query is being
        // observed, cancelling the previous one. A `map` would leave the old profile's Flow running
        // and the screen showing the budget it was already showing.
        activeProfileId.flatMapLatest { profileId -> observeLatestEnvelopes(profileId) }

    override fun observeLatestEnvelopes(profileId: String): Flow<List<BudgetEnvelope>> =
        database.budgetDao().observeLatestPeriod(profileId)
            // Sorted here rather than in SQL: ORDER BY nature is alphabetical — invest, need,
            // want — which is not an order that means anything to a reader. Display order is
            // needs, wants, savings, and that lives in the enum, so the domain decides it rather
            // than SQLite's collation.
            .map { rows -> rows.mapNotNull { it.toEnvelope() }.sortedBy { it.nature.ordinal } }
            .flowOn(dispatchers.io)
}

/** Written on every row this repository creates, so a later screen can tell it apart from a user's own. */
internal const val SOURCE_QUICK_SETUP = "quick_setup"

/**
 * Written on every row the demo dataset creates (issue 2.4; FR-ONB-004).
 *
 * Why: the demo profile id already isolates these rows, but `source` is the column P-02 reserves for
 *      "where did this come from?", and a fabricated figure is exactly the kind of provenance a
 *      drill-down must be able to state. Two independent markers is the right number for data the
 *      app made up.
 */
internal const val SOURCE_DEMO = "demo"

/**
 * Converts an envelope to its row.
 * Why:    the id is **derived, never generated** — `<profile>:budget:<nature>:<period>` — which is
 *         what makes `applySeeds` idempotent under REPLACE and keeps the write deterministic (P-08).
 *         A random id would append a fresh set of envelopes on every run.
 * Result: a [BudgetEntity] for one nature in one month.
 * Input:  the receiver; [profileId]; [periodStartIsoDate]; [nowUtcMillis] — from the injected Clock;
 *         [source] — provenance, defaulting to quick setup and overridden only by the demo dataset
 *         (issue 2.4), which needs the row to say it was fabricated.
 * Output: [BudgetEntity].
 * Changelog: 2026-07-27 — Created for issue 2.3.
 *            2026-07-28 — Issue 2.4: [source] became a parameter so the demo can mark its own rows.
 *
 * `created_at` is restated on a re-run, because REPLACE cannot preserve it without a read per row.
 * That is the accepted cost of the simpler write: the value a caller relies on is `updated_at`, and
 * no envelope outlives the seeds it was derived from.
 */
internal fun BudgetEnvelope.toEntity(
    profileId: String,
    periodStartIsoDate: String,
    nowUtcMillis: Long,
    source: String = SOURCE_QUICK_SETUP,
): BudgetEntity =
    BudgetEntity(
        id = budgetId(profileId, nature, periodStartIsoDate),
        profileId = profileId,
        // No category: this is a nature-level envelope (FR-BUD-001's "category-group"), because no
        // categories exist until issue 4.1.
        categoryId = null,
        nature = nature.storedValue,
        periodStartIsoDate = periodStartIsoDate,
        amountMinor = amount.minor,
        source = source,
        ruleId = citation.ruleId,
        ruleVersion = citation.ruleVersion,
        createdAtUtcMillis = nowUtcMillis,
        updatedAtUtcMillis = nowUtcMillis,
    )

/**
 * Converts a recurring seed to its row.
 * Why:    same derived-id reasoning as [toEntity] above; there is one rule per kind, so the kind is
 *         the id. `isConfirmed` is false because FR-TXN-006 requires the user to confirm a rule
 *         before it creates anything — these figures were an estimate typed during onboarding.
 * Result: a [RecurringRuleEntity].
 * Input:  the receiver; [profileId]; [nowUtcMillis]; [source] — provenance, defaulting to quick
 *         setup and overridden only by the demo dataset (issue 2.4).
 * Output: [RecurringRuleEntity].
 * Changelog: 2026-07-27 — Created for issue 2.3.
 *            2026-07-28 — Issue 2.4: [source] became a parameter so the demo can mark its own rows.
 */
internal fun RecurringSeed.toEntity(
    profileId: String,
    nowUtcMillis: Long,
    source: String = SOURCE_QUICK_SETUP,
): RecurringRuleEntity =
    RecurringRuleEntity(
        id = recurringId(profileId, kind.storedValue),
        profileId = profileId,
        // Neither exists yet: issue 2.5 attaches an account, 4.1/4.3 a category.
        accountId = null,
        categoryId = null,
        // Null, not a label: the UI resolves seedKind to a string resource (§21.6).
        name = null,
        seedKind = kind.storedValue,
        amountMinor = amount.minor,
        cadence = cadence.storedValue,
        nextDueIsoDate = nextDueIsoDate,
        source = source,
        isConfirmed = false,
        createdAtUtcMillis = nowUtcMillis,
        updatedAtUtcMillis = nowUtcMillis,
    )

/**
 * Converts a stored budget row back to an envelope.
 * Why:    a row written by a newer build may carry a nature this one has never heard of, and a
 *         per-category budget (issue 4.4) has no nature at all. Both map to `null` and are dropped
 *         rather than throwing — an old build reading a newer database should show fewer envelopes,
 *         not crash the dashboard.
 * Result: a [BudgetEnvelope], or `null` when the row is not a nature-level envelope this build
 *         understands.
 * Input:  the receiver. Output: `BudgetEnvelope?`.
 * Changelog: 2026-07-27 — Created for issue 2.3.
 */
internal fun BudgetEntity.toEnvelope(): BudgetEnvelope? {
    val parsed = BudgetNature.entries.firstOrNull { it.storedValue == nature } ?: return null
    return BudgetEnvelope(
        nature = parsed,
        amount = Money(amountMinor),
        // A row the user typed carries no rule; UNATTRIBUTED says so rather than inventing one.
        citation = RuleCitation(ruleId ?: UNATTRIBUTED_RULE_ID, ruleVersion ?: UNATTRIBUTED_RULE_VERSION),
    )
}

/**
 * Result: the derived id for one envelope. Input: [profileId], [nature], [periodStartIsoDate].
 * Output: a stable [String] — the same inputs always name the same row.
 */
internal fun budgetId(
    profileId: String,
    nature: BudgetNature,
    periodStartIsoDate: String,
): String = "$profileId:budget:${nature.storedValue}:$periodStartIsoDate"

/**
 * Result: the derived id for one recurring rule. Input: [profileId], [kind] — a stored kind code.
 * Output: a stable [String].
 */
internal fun recurringId(
    profileId: String,
    kind: String,
): String = "$profileId:recurring:$kind"

/** Stands in for a rule on a budget nobody derived — a user-entered amount (issue 4.4). */
internal const val UNATTRIBUTED_RULE_ID = "USER-ENTERED"

/** The version paired with [UNATTRIBUTED_RULE_ID]; there is no rulebook row to version. */
internal const val UNATTRIBUTED_RULE_VERSION = "0"
