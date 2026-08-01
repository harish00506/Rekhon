package com.aicfo.data.repository

import androidx.room.withTransaction
import com.aicfo.core.common.AppError
import com.aicfo.core.common.Clock
import com.aicfo.core.common.DispatcherProvider
import com.aicfo.core.common.Result
import com.aicfo.core.common.flatMap
import com.aicfo.core.common.getOrNull
import com.aicfo.core.common.runCatchingToResult
import com.aicfo.core.database.CfoDatabase
import com.aicfo.core.datastore.SettingsStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map

/**
 * Loads and erases the sample dataset (issue 2.4; FR-ONB-004, ARC-005, P-01).
 *
 * Why:  FR-ONB-004 asks for a demo "available without creating a profile", and two properties decide
 *       whether that is honest. It must be **isolated** — the sample data cannot touch, shadow or
 *       merge with anything real — and it must be **erasable without residue**, because data the app
 *       invented has no business outliving the user's interest in it. Both are achieved the same
 *       way: every row lives under a second profile id, so isolation is what the existing
 *       per-profile scoping already gives for free, and erasure is one `DELETE` per table.
 * What: the demo flag, the profile every read should currently resolve to, and the two transitions.
 * Result: a user can explore the app on realistic figures and leave with the database exactly as
 *       they found it.
 * Changelog: 2026-07-28 — Created for issue 2.4.
 *
 * **Nothing here is a settings write beyond the flag.** Entering the demo never sets a time zone, a
 * currency, a display name or the onboarding-completion timestamp — that is what "without creating
 * a profile" means in practice, and it is what guarantees a user who exits lands back on first-run
 * onboarding rather than on an empty dashboard belonging to a profile they never made.
 *
 * **Deliberately not consent-gated (P-01).** Consent governs data *leaving* the device or a feature
 * reading the user's own data; the demo does neither. It writes fabricated rows to local storage
 * and makes no network call on any path (P-04).
 */
interface DemoModeRepository {
    /**
     * Whether the app is currently showing the sample dataset.
     * Why:    a Flow because the banner labelling every screen is driven from it, and a snapshot
     *         read would leave the banner on screen after the user exited.
     * Result: emits `false` until [enter] succeeds, `true` until [exit] does.
     * Input:  none. Output: `Flow<Boolean>`.
     */
    val isActive: Flow<Boolean>

    /**
     * The profile every read should currently resolve to.
     * Why:    the one place the demo/real switch is expressed. A repository that asked
     *         "is demo active?" and picked an id itself would be a second copy of this rule, and the
     *         two would drift — which would show a demo user their own empty budget, or worse, show
     *         a real user the demo's.
     * Result: emits the demo profile while the demo is on, the real one otherwise.
     * Input:  none. Output: `Flow<String>`.
     */
    val activeProfileId: Flow<String>

    /**
     * Loads the sample dataset and turns the demo on.
     * Why:    the rows land in **one transaction and the flag is set after it**. A half-written demo
     *         would be a dashboard of fragments; a flag set before a failed write would be a banner
     *         over the user's own data, which is the worst outcome this feature can produce.
     * What:   generates [DemoDataset], writes all six tables, then sets the flag.
     * Result: `Ok(Unit)` with the demo visible, or `Err(Storage)` with the flag still `false`.
     *         Idempotent — ids are derived, so a second call updates the same rows.
     * Input:  none. Output: `Result<Unit, AppError>`.
     */
    suspend fun enter(): Result<Unit, AppError>

    /**
     * Turns the demo off and erases every row it wrote.
     * Why:    **the flag is cleared first, and the wipe follows** — the opposite order to [enter],
     *         for the opposite reason. Both orders lose something if the second step fails, and the
     *         choice is between orphan rows nothing reads and an app still showing fabricated money
     *         with no banner over it. The first is recoverable; the second is a lie.
     * What:   clears the flag, then hard-deletes the demo profile's rows in one transaction.
     * Result: `Ok(Unit)` with nothing left behind, or `Err(Storage)`. Safe to call when the demo was
     *         never entered — the deletes simply match nothing.
     * Input:  none. Output: `Result<Unit, AppError>`.
     */
    suspend fun exit(): Result<Unit, AppError>

    companion object {
        /**
         * The profile id every demo row carries.
         *
         * Why:    a constant, and a different one from [QuickSetupRepository.DEFAULT_PROFILE_ID], is
         *         the entire isolation mechanism (ADR-0006). Because every query in the app is
         *         already scoped by `profile_id`, no read can span the two and no wipe can reach the
         *         user's own rows. It must never be made configurable: an id that could be set to
         *         `local` would turn the demo wipe into a data-loss bug.
         */
        const val DEMO_PROFILE_ID = "demo"
    }
}

/**
 * The Room-backed [DemoModeRepository].
 * Why:    ARC-003 — one public interface, an internal implementation, assembled by the DI graph.
 * Result: the implementation injected into the onboarding and shell ViewModels.
 * Changelog: 2026-07-28 — Created for issue 2.4.
 *
 * Input:  [database] — needed whole rather than as DAOs, because both transitions rest on
 *         `withTransaction`; [settingsStore] — holds the flag; [clock] — anchors the dataset to the
 *         user's today and stamps every row, never the wall clock (TIM-001); [dispatchers] — keeps
 *         the flag's Flow off the caller's thread.
 * Output: a working repository.
 */
internal class RoomDemoModeRepository(
    private val database: CfoDatabase,
    private val settingsStore: SettingsStore,
    private val clock: Clock,
    private val dispatchers: DispatcherProvider,
) : DemoModeRepository {
    override val isActive: Flow<Boolean> =
        settingsStore.observe()
            // A settings read that failed is not evidence of a demo. Falling back to `false` keeps
            // the app showing the user's own data, which is the safe direction: the worst case is a
            // missing banner over data that is real anyway, never a banner over data that is not.
            .map { result -> result.getOrNull()?.demoModeActive == true }
            .distinctUntilChanged()
            .flowOn(dispatchers.io)

    override val activeProfileId: Flow<String> =
        isActive.map { active ->
            if (active) DemoModeRepository.DEMO_PROFILE_ID else QuickSetupRepository.DEFAULT_PROFILE_ID
        }

    override suspend fun enter(): Result<Unit, AppError> =
        runCatchingToResult {
            val rows = DemoDataset.build(clock)
            // withTransaction supplies its own dispatcher, so this is already off the caller's
            // thread. Parents before children: the profile exists before anything is scoped to it.
            database.withTransaction {
                database.profileDao().upsert(rows.profile)
                rows.accounts.forEach { database.accountDao().upsert(it) }
                database.categoryDao().upsertAll(rows.categories)
                rows.transactions.forEach { database.transactionDao().upsert(it) }
                database.budgetDao().upsertAll(rows.budgets)
                database.recurringRuleDao().upsertAll(rows.recurring)
            }
            // Last, and outside the transaction, because DataStore is a different store with its own
            // atomicity. If this fails the rows are orphaned under a profile nothing reads — which
            // the next enter() overwrites and the next exit() erases.
        }.flatMap { settingsStore.setDemoModeActive(true) }

    override suspend fun exit(): Result<Unit, AppError> =
        settingsStore.setDemoModeActive(false).flatMap {
            runCatchingToResult {
                val demo = database.demoDao()
                database.withTransaction {
                    // Children before the parent: if this fails part-way, what is left is rows under
                    // a profile that still exists, rather than orphans no query can reach to clean up.
                    demo.deleteBudgets(DemoModeRepository.DEMO_PROFILE_ID)
                    // Issue 2.6: not written by enter(), but produced while the user browses — the
                    // daily job snapshots whichever profile is active. A profile-scoped table the
                    // wipe does not reach is the residue ADR-0006 forbids.
                    demo.deleteNetWorthSnapshots(DemoModeRepository.DEMO_PROFILE_ID)
                    demo.deleteRecurringRules(DemoModeRepository.DEMO_PROFILE_ID)
                    demo.deleteTransactions(DemoModeRepository.DEMO_PROFILE_ID)
                    demo.deleteCategories(DemoModeRepository.DEMO_PROFILE_ID)
                    demo.deleteAccounts(DemoModeRepository.DEMO_PROFILE_ID)
                    demo.deleteProfile(DemoModeRepository.DEMO_PROFILE_ID)
                    // The deletes return row counts nobody acts on — "how many rows did the demo
                    // have?" is not a question with a consequence. What matters is that none are
                    // left, and DemoDao.countRowsFor is what asserts that.
                    Unit
                }
            }
        }
}
