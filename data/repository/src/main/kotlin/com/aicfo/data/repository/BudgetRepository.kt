package com.aicfo.data.repository

import androidx.room.withTransaction
import com.aicfo.core.common.AppError
import com.aicfo.core.common.Clock
import com.aicfo.core.common.DispatcherProvider
import com.aicfo.core.common.Err
import com.aicfo.core.common.Ok
import com.aicfo.core.common.Result
import com.aicfo.core.common.getOrElse
import com.aicfo.core.common.getOrNull
import com.aicfo.core.common.runCatchingToResult
import com.aicfo.core.database.CfoDatabase
import com.aicfo.core.database.entity.BudgetAlertEntity
import com.aicfo.core.database.entity.BudgetEntity
import com.aicfo.core.database.entity.BudgetReviewEntity
import com.aicfo.core.model.Category
import com.aicfo.core.model.Money
import com.aicfo.domain.engines.budget.BudgetAlert
import com.aicfo.domain.engines.budget.BudgetAlertInput
import com.aicfo.domain.engines.budget.BudgetEngine
import com.aicfo.domain.engines.budget.BudgetReview
import com.aicfo.domain.engines.budget.BudgetReviewInput
import com.aicfo.domain.engines.budget.BudgetStatus
import com.aicfo.domain.engines.budget.BudgetStatusInput
import com.aicfo.domain.engines.budget.BudgetSuggestion
import com.aicfo.domain.engines.budget.BudgetSuggestionInput
import com.aicfo.domain.engines.budget.MonthlySpend
import com.aicfo.domain.engines.budget.ReviewedCategoryInput
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import java.time.LocalDate

/**
 * One category's budget for a month, with everything the screen shows about it (FR-BUD-003).
 *
 * Why:  a budget row on its own says nothing useful — the screen's question is always "how is this
 *       going?", which needs the category's name, the amount planned and the engine's status
 *       together. Assembling them here rather than on the screen keeps the ViewModel free of any
 *       arithmetic (P-03) and free of Room types (ARC-005).
 * What: the category, the stored budget's identity and provenance, and the computed status.
 * Result: a row the UI renders without computing anything.
 * Changelog: 2026-08-11 — Created for issue 4.4.
 *
 * Input:  [id] — the `budget` row, `null` when this category has no budget yet; [category];
 *         [status] — the engine's figures; [rolloverEnabled] — FR-BUD-001's opt-in; [source] —
 *         `manual` when the user typed the number, `suggested` when they accepted one (P-02).
 * Output: an immutable value.
 */
data class CategoryBudget(
    val id: String?,
    val category: Category,
    val status: BudgetStatus,
    val rolloverEnabled: Boolean,
    val source: String,
) {
    /** Result: true when nothing has been planned for this category — it is spend without a budget. */
    val isUnbudgeted: Boolean get() = id == null
}

/**
 * A budget the app proposes for a category, with the reasoning attached (FR-BUD-002).
 *
 * Why:  P-07 — the app recommends and the user decides, so a suggestion has to be a *different
 *       thing* from a budget in the type system, not a budget with a flag on it. Nothing writes a
 *       `budget` row until [BudgetRepository.acceptSuggestion] is called from a tap.
 * What: the category it is for and the engine's [BudgetSuggestion], which carries the amount, the
 *       unadjusted median, the seasonal event and the cited rules.
 * Result: the screen can show the number **and** why it is that number (P-02).
 * Changelog: 2026-08-11 — Created for issue 4.4.
 */
data class CategoryBudgetSuggestion(
    val category: Category,
    val suggestion: BudgetSuggestion,
)

/**
 * One category budget that has crossed an alert band this month (FR-BUD-004).
 *
 * Why:  the notifier and the banner both need the same three things — which budget, whose category,
 *       and every figure they are allowed to say. Bundling them means the guardrail can be handed
 *       exactly the values behind the message (AI-ARC-004) rather than the notifier reaching back
 *       into a repository for them.
 * What: the budget row's id, the category, and the engine's [BudgetAlert].
 * Result: a value the notification and the screen render without computing anything (P-03).
 * Changelog: 2026-08-13 — Created for issue 4.5.
 *
 * Input:  [budgetId] — the `budget` row, part of the once-per-band key; [category]; [alert].
 * Output: an immutable value.
 */
data class CategoryBudgetAlert(
    val budgetId: String,
    val category: Category,
    val alert: BudgetAlert,
)

/**
 * The per-category budget store (issue 4.4; FR-BUD-001, FR-BUD-002, FR-BUD-003).
 *
 * Why:  ARC-005 — this is the only class allowed to touch the budget, transaction and category DAOs,
 *       and the only place the engine's inputs are assembled. Everything above it sees domain types.
 * What: reads budgets with their live status, offers suggestions from history, and writes the
 *       amounts the user chooses.
 * Result: a budget screen that recomputes itself whenever a transaction lands, with no arithmetic
 *       above this layer.
 * Changelog: 2026-08-11 — Created for issue 4.4.
 *
 * **Budgets share the `budget` table with quick setup's nature-level envelopes** (ADR-0004). The two
 * are separated by `category_id`: null for an envelope, set for one of these. Neither read sees the
 * other's rows, which is why `observeLatestEnvelopes` still drives the dashboard rings unchanged.
 *
 * **Eleven operations across three features** (budgets, alerts, review) that all read and write the
 * same `budget`-family tables — the count below is the interface's, not a design choice, and
 * splitting it would scatter one repository's responsibility across several to satisfy a counter.
 *
 * **What happens when the engine fails — one rule, inherited rather than invented** (issue 4.7).
 * `BudgetEngine` already draws the line: `suggest`, `alert` and `review` each document `Ok(null)`
 * as a legitimate answer, and `status` has no such slot because there is no such thing as "no
 * status". So:
 *
 * > **Where the engine documents `Ok(null)` as a legitimate answer, a failure collapses into that
 * > same absence. Where it does not — `status` — the failure terminates the stream, carrying the
 * > engine's own `AppError`.**
 *
 * In practice that means a failed suggestion, alert or review is silently absent (no offer, no
 * band, no card) while the figures beside it survive, and a failed status surfaces through the
 * `.catch {}` every consumer of [observeBudgets] already has. The suspend readers below
 * ([pendingAlerts], [acceptSuggestion], [acceptReviewProposal]) convert that throw back into `Err`,
 * so no exception crosses a layer boundary (§21.6). Each method's own doc comment says which case
 * it is; the cost — that "no answer" and "could not answer" are indistinguishable on three of the
 * four — is real, and unrecorded until this app has a structured logger.
 */
@Suppress("TooManyFunctions")
interface BudgetRepository {
    /**
     * Every category with its budget and live status, for the current month.
     * Why:    categories with **no** budget are included, as unbudgeted rows with zero planned. A
     *         screen that listed only budgeted categories would hide the spending a user most needs
     *         to see — the category they have not thought about yet.
     * Result: one row per category, re-emitted whenever a transaction or a budget changes.
     * Input:  none — the active profile and the current month are resolved internally.
     * Output: `Flow<List<CategoryBudget>>`.
     *
     * **If the status engine fails, this stream fails** (issue 4.7) — the one read here that does.
     * The collector's `.catch {}` sees a `BudgetEngineFailure` carrying the engine's `AppError`;
     * both ViewModels already turn that into an error banner. It fails whole rather than dropping
     * the row, because these rows are folded into a headline total downstream.
     */
    fun observeBudgets(): Flow<List<CategoryBudget>>

    /**
     * Proposals for categories that have enough history and no budget yet (FR-BUD-002).
     * Why:    a category the user has already budgeted is a decision already made; re-suggesting
     *         over it would be the app arguing with them (P-07).
     * Result: one row per suggestible category, empty when history is too thin — **or when the
     *         engine failed** (issue 4.7), which is indistinguishable here and deliberately so: the
     *         engine already documents "no opinion" as a legitimate answer, and a failure lands in
     *         that lane rather than blanking the budgets the user came to read.
     * Input:  none. Output: `Flow<List<CategoryBudgetSuggestion>>`.
     */
    fun observeSuggestions(): Flow<List<CategoryBudgetSuggestion>>

    /**
     * Sets or replaces one category's budget for the current month (FR-BUD-001).
     * Result: `Ok` with the stored budget's id, or `Validation` when the amount is negative or the
     *         category does not exist.
     * Input:  [categoryId]; [amount] — paise (MNY-001); [rolloverEnabled] — FR-BUD-001's opt-in.
     * Output: `Result<String, AppError>`.
     */
    suspend fun setBudget(
        categoryId: String,
        amount: Money,
        rolloverEnabled: Boolean,
    ): Result<String, AppError>

    /**
     * Accepts a suggestion as this month's budget for one category (FR-BUD-002's one-tap accept).
     * Why:    a separate call from [setBudget] because the two record different things. An accepted
     *         suggestion stores `source = 'suggested'` **and the rule that produced it**, so a
     *         reviewer can later tell an amount the app proposed from one the user chose — which is
     *         the audit trail §29's governance clause asks for (AI-ARC-006).
     * Result: `Ok` with the stored id, or `NotFound` when nothing is suggestible for that category.
     * Input:  [categoryId]. Output: `Result<String, AppError>`.
     */
    suspend fun acceptSuggestion(categoryId: String): Result<String, AppError>

    /**
     * Removes a budget, leaving the category unbudgeted (FR-BUD-001).
     * Result: `Ok`, or `NotFound` when the id names nothing live. Soft delete (DB-002).
     * Input:  [id] — the budget row's id. Output: `Result<Unit, AppError>`.
     */
    suspend fun deleteBudget(id: String): Result<Unit, AppError>

    /**
     * Every budget currently sitting in an alert band, for the in-app banner (FR-BUD-004).
     * Why:    **not deduplicated**, unlike the notification. A band that has been crossed stays true
     *         for the rest of the month, and a banner that disappeared because the notification had
     *         already been sent would hide the very state it exists to show (P-02). It is also what
     *         a user who denied notification permission sees instead of nothing.
     * Result: one row per budget in a band, re-emitted whenever a transaction or a budget changes.
     *         **A band the engine could not decide is simply absent** (issue 4.7) — the row it would
     *         have highlighted is still in [observeBudgets] with its real figures. A failure of the
     *         *status* engine is different and does fail this stream, since these rows derive from
     *         that read.
     * Input:  none. Output: `Flow<List<CategoryBudgetAlert>>`.
     */
    fun observeAlerts(): Flow<List<CategoryBudgetAlert>>

    /**
     * Decides which alert band, if any, one already-fetched row has crossed (FR-BUD-004).
     *
     * Why:    a pure, synchronous companion to [observeAlerts], for a caller that has already read
     *         [observeBudgets] itself and would otherwise have to open a **second** subscription to
     *         `observeBudgets()`'s three-Room-query `combine()` just to learn what [observeAlerts]
     *         derives from the same rows. The dashboard is exactly this caller (issue 5.1): before
     *         this existed, its budget-status card and its "needs attention" line each triggered the
     *         whole budget read independently, doubling the query cost on the app's landing screen.
     *         No I/O and no clock read happen here — this is the same engine call [observeAlerts]
     *         already makes internally, exposed so a second read is never needed to reach it.
     * Result: the alert, or `null` when the row is below the warn band, has no budget, **or the
     *         engine could not decide** (issue 4.7) — a caller meets the degradation directly here,
     *         with no Flow between them, so there is nowhere else for it to be reported.
     * Input:  [row] — a row already read from [observeBudgets]. Output: `CategoryBudgetAlert?`.
     */
    fun alertFor(row: CategoryBudget): CategoryBudgetAlert?

    /**
     * The bands crossed that the user has **not** yet been told about (FR-BUD-004).
     * Why:    the worker's input. Separate from [observeAlerts] because the two answer different
     *         questions — "what is true?" and "what is new?" — and only the second may interrupt
     *         someone.
     * Result: `Ok` with the unnotified alerts, empty when everything current has been sent **or when
     *         the alert engine failed** (issue 4.7). That second meaning is a deliberate contract
     *         change: it used to be `Err`, which made `BudgetAlertWorker` retry — daily, forever,
     *         silently — a failure that is deterministic given the same rows. An empty list lets the
     *         worker report success and stop burning a wakeup on it. A failure of the *status*
     *         engine still arrives as `Err`, because that one throws rather than emptying.
     * Input:  none. Output: `Result<List<CategoryBudgetAlert>, AppError>`.
     */
    suspend fun pendingAlerts(): Result<List<CategoryBudgetAlert>, AppError>

    /**
     * Claims one alert for sending, recording that the user is being told (FR-BUD-004).
     * Why:    **claim before you send, not after.** The insert is what makes a duplicate impossible,
     *         so it has to happen before the notification rather than as a receipt afterwards — with
     *         the order reversed, a crash between the two would re-notify on the next run. The cost
     *         of this order is the opposite failure: a claim that is written and then fails to post
     *         is silently dropped. That is the right way round to be wrong — a missed warning is a
     *         disappointment, a repeating one teaches the user to mute the channel and costs them
     *         every later warning too.
     * Result: `Ok(true)` when this call claimed it and the caller should notify; `Ok(false)` when
     *         someone already had, and the caller must not.
     * Input:  [alert]. Output: `Result<Boolean, AppError>`.
     */
    suspend fun markNotified(alert: CategoryBudgetAlert): Result<Boolean, AppError>

    /**
     * The last closed month's budget review, for the card on the budgets screen (issue 4.6; §5.5).
     * Why:    `null` covers three different truths at once, deliberately: nothing was budgeted last
     *         month, the review has already been dismissed (`RULE-BUD-REVIEW.review_once_per_month`,
     *         [dismissReview]'s claim), or the engine could not compute one (issue 4.7). All three
     *         render as no card. Unlike [observeAlerts], **this one does deduplicate** — a
     *         review is a one-time task to act on or dismiss, not an ongoing status like a crossed
     *         band, so the same card returning every time the screen reopens would be exactly the
     *         nagging the rule exists to prevent.
     * Result: the review, or `null` when there is nothing to show. Re-emitted whenever a transaction,
     *         a budget, or the claim changes.
     * Input:  none. Output: `Flow<BudgetReview?>`.
     */
    fun observeReview(): Flow<BudgetReview?>

    /**
     * Accepts a reviewed category's proposed adjustment as **this month's** budget (FR-BUD-*, P-07).
     * Why:    re-reads [observeReview] rather than taking the amount from the screen, for the reason
     *         [acceptSuggestion] gives: the number written is provably the one the engine produced.
     *         Writes to the current month, not the reviewed one — a proposal prices the month the
     *         user is standing in (see `DefaultBudgetEngine.proposalFor`), and the reviewed month is
     *         already closed and cannot be re-budgeted.
     * Result: `Ok` with the stored id, or `NotFound` when the category has no material proposal —
     *         either it was on-plan, or the engine had too little history to price one.
     * Input:  [categoryId]. Output: `Result<String, AppError>`.
     */
    suspend fun acceptReviewProposal(categoryId: String): Result<String, AppError>

    /**
     * Dismisses the last closed month's review, so [observeReview] stops showing it.
     * Why:    the claim [observeReview] checks, mirroring [markNotified]'s insert-and-check shape —
     *         a `budget_review` row for (profile, reviewed month) is what makes "reviewed once" a
     *         property of the schema rather than of the screen remembering not to reopen it.
     * Result: `Ok(true)` when this call claimed it; `Ok(false)` when it was already claimed, or
     *         when there was nothing to review — dismissing nothing is not an error, and nothing
     *         is written.
     * Input:  none. Output: `Result<Boolean, AppError>`.
     */
    suspend fun dismissReview(): Result<Boolean, AppError>

    companion object {
        /** `manual` — the user typed this number themselves. */
        const val SOURCE_MANUAL = "manual"

        /** `suggested` — the user accepted a number the app proposed, which cites its rule. */
        const val SOURCE_SUGGESTED = "suggested"
    }
}

/**
 * The Room-backed [BudgetRepository].
 *
 * Why:    takes the whole [database] because a budget's status spans three tables — the budget, the
 *         month's transactions (and their split lines) and the taxonomy — and rollover additionally
 *         needs last month's budgets read in the same breath as last month's spend.
 * Result: a [BudgetRepository] over the encrypted database.
 * Input:  [database]; [engine] — takes the engine rather than constructing one, so the number and
 *         the code that produced it stay assembled in the DI graph (ARC-003, P-03); [clock] —
 *         TIM-001, the **only** clock in this feature: the engine gets days, never dates;
 *         [dispatchers]; [activeProfileId] — which profile's budgets, so the demo keeps its own.
 * Output: [BudgetRepository].
 * Changelog: 2026-08-11 — Created for issue 4.4.
 *            2026-08-15 — Issue 4.6: added the monthly review (`observeReview`,
 *            `acceptReviewProposal`, `dismissReview`).
 *            2026-08-16 — Issue 4.7: removed the four unchecked `as Ok` engine casts; each site now
 *            follows the rule in the interface's doc comment above.
 */
@OptIn(ExperimentalCoroutinesApi::class)
// Eleven of these are [BudgetRepository]'s own operations and the rest assemble engine inputs from
// rows, which is this class's entire job (ARC-005). The count is the interface's, not a design
// choice — the same argument `CfoDatabase` and `DemoDao` make. Splitting the private helpers into a
// collaborator would scatter one responsibility to satisfy a counter.
@Suppress("TooManyFunctions")
internal class RoomBudgetRepository(
    private val database: CfoDatabase,
    private val engine: BudgetEngine,
    private val clock: Clock,
    private val dispatchers: DispatcherProvider,
    private val activeProfileId: Flow<String>,
) : BudgetRepository {
    override fun observeBudgets(): Flow<List<CategoryBudget>> =
        // flatMapLatest, not map: entering or leaving the demo must *switch* which queries are
        // observed, cancelling the previous ones.
        activeProfileId.flatMapLatest { profileId ->
            val month = MonthWindow.current(clock.today())
            combine(
                database.categoryDao().observeForProfile(profileId),
                database.budgetDao().observeCategoryBudgets(profileId, month.startIsoDate),
                database.transactionDao()
                    .observeCategorySpend(profileId, month.startIsoDate, month.actualsEndIsoDate),
            ) { categories, budgets, spend ->
                val spentByCategory = spend.associate { it.categoryId to Money(it.spentMinor) }
                val budgetByCategory = budgets.associateBy { it.categoryId }
                val carried = carriedOverFor(profileId, month, budgetByCategory.keys.filterNotNull())
                categories.mapNotNull { it.toCategory() }.map { category ->
                    categoryBudget(
                        category = category,
                        entity = budgetByCategory[category.id],
                        spent = spentByCategory[category.id] ?: Money.ZERO,
                        carriedOver = carried[category.id] ?: Money.ZERO,
                        month = month,
                    )
                }
            }.flowOn(dispatchers.io)
        }

    override fun observeSuggestions(): Flow<List<CategoryBudgetSuggestion>> =
        activeProfileId.flatMapLatest { profileId ->
            val month = MonthWindow.current(clock.today())
            val history = historyWindow(clock.today())
            combine(
                database.categoryDao().observeForProfile(profileId),
                database.budgetDao().observeCategoryBudgets(profileId, month.startIsoDate),
                database.transactionDao()
                    .observeMonthlyCategorySpend(profileId, history.first, history.second),
            ) { categories, budgets, monthlySpend ->
                val alreadyBudgeted = budgets.mapNotNull { it.categoryId }.toSet()
                val byCategory = monthlySpend.groupBy { it.categoryId }
                val monthsObserved = byCategory.values.flatten().map { it.monthKey }.distinct().size
                categories.mapNotNull { it.toCategory() }
                    .filterNot { it.id in alreadyBudgeted }
                    .mapNotNull { category ->
                        suggestionFor(category, byCategory[category.id].orEmpty(), monthsObserved)
                    }
            }.flowOn(dispatchers.io)
        }

    override suspend fun setBudget(
        categoryId: String,
        amount: Money,
        rolloverEnabled: Boolean,
    ): Result<String, AppError> {
        if (amount < Money.ZERO) return Err(AppError.Validation(FIELD_AMOUNT))
        return write(categoryId, amount, rolloverEnabled, BudgetRepository.SOURCE_MANUAL, citation = null)
    }

    override suspend fun acceptSuggestion(categoryId: String): Result<String, AppError> =
        // Guarded for the reason [acceptReviewProposal] is, and fixed in the same pass: this reads
        // three Room queries and the suggestion engine before it writes anything, and an unguarded
        // throw from any of them crossed a layer boundary into `viewModelScope` (§21.6). The shape
        // predates issue 4.6 — 4.6 copied it, which is how one review found both.
        withContext(dispatchers.io) {
            runCatchingToResult {
                // Read the suggestion through the same Flow the screen saw, so an accepted number is
                // provably the number that was shown rather than one recomputed a moment later — the
                // two could differ across a month boundary, and the user tapped the one they read.
                val suggestion =
                    observeSuggestions().first().firstOrNull { it.category.id == categoryId }
                        ?: return@runCatchingToResult Err(AppError.NotFound)
                write(
                    categoryId = categoryId,
                    amount = suggestion.suggestion.amount,
                    rolloverEnabled = false,
                    source = BudgetRepository.SOURCE_SUGGESTED,
                    // The first citation is the rule; a seasonal suggestion appends the calendar
                    // event. Storing it is what lets a later reviewer reproduce this amount
                    // (AI-ARC-006).
                    citation = suggestion.suggestion.provenance.evidence.firstOrNull(),
                )
            }.flatten()
        }

    override fun observeAlerts(): Flow<List<CategoryBudgetAlert>> =
        // Derived from the budgets rather than read from `budget_alert`: the band is a fact about the
        // spending, and the table records only what has been *said* about it. Reading the table here
        // would make the banner appear when the notification fired rather than when the budget was
        // crossed — including never, on a device where notifications are denied.
        observeBudgets().map { rows -> rows.mapNotNull(::alertFor) }

    override suspend fun pendingAlerts(): Result<List<CategoryBudgetAlert>, AppError> =
        withContext(dispatchers.io) {
            runCatchingToResult {
                val profileId = activeProfileId.first()
                val monthStart = MonthWindow.current(clock.today()).startIsoDate
                val alreadySent =
                    database.budgetAlertDao().forMonth(profileId, monthStart)
                        .map { it.budgetId to it.band }
                        .toSet()
                observeAlerts().first().filterNot { (it.budgetId to it.alert.band.name) in alreadySent }
            }
        }

    override suspend fun markNotified(alert: CategoryBudgetAlert): Result<Boolean, AppError> =
        withContext(dispatchers.io) {
            runCatchingToResult {
                val profileId = activeProfileId.first()
                val monthStart = MonthWindow.current(clock.today()).startIsoDate
                val citation = alert.alert.provenance.evidence.first()
                val inserted =
                    database.budgetAlertDao().insertIfNew(
                        BudgetAlertEntity(
                            id = budgetAlertId(alert.budgetId, alert.alert.band.name),
                            profileId = profileId,
                            budgetId = alert.budgetId,
                            categoryId = alert.category.id,
                            monthStartIsoDate = monthStart,
                            band = alert.alert.band.name,
                            ruleId = citation.ruleId,
                            ruleVersion = citation.ruleVersion,
                            notifiedAtUtcMillis = clock.nowUtcMillis(),
                        ),
                    )
                // IGNORE returns -1 when the unique index refused the row: someone already told them.
                inserted != INSERT_IGNORED
            }
        }

    override fun observeReview(): Flow<BudgetReview?> =
        activeProfileId.flatMapLatest { profileId ->
            rawReview(profileId).combine(
                database.budgetReviewDao().observeForMonth(profileId, reviewedMonth(clock.today()).startIsoDate),
            ) { review, claimed -> if (claimed != null) null else review }
                .flowOn(dispatchers.io)
        }

    override suspend fun acceptReviewProposal(categoryId: String): Result<String, AppError> =
        // Guarded, unlike the first version of this method. `observeReview().first()` is not a cheap
        // lookup — it runs four Room queries and the review engine — and anything it throws (a disk
        // error, an engine failure surfacing through `reviewFor`'s unchecked cast) used to escape a
        // `Result`-returning API entirely, landing on `viewModelScope` where nothing catches it: a
        // crash, not the error banner `BudgetsViewModel` believes it is handling. §21.6 is explicit
        // that no exception crosses a layer boundary. Found by review, 2026-08-16; see issue 4.7 for
        // the unchecked engine casts that make the inner throw reachable at all.
        withContext(dispatchers.io) {
            runCatchingToResult {
                // Read through the same Flow the card showed, for the reason acceptSuggestion
                // re-reads observeSuggestions: the amount written is provably the one the engine
                // produced — and, since 2026-08-16, provably the one the card actually displayed.
                val proposal =
                    observeReview().first()?.categories?.firstOrNull { it.categoryId == categoryId }?.proposal
                        ?: return@runCatchingToResult Err(AppError.NotFound)
                write(
                    categoryId = categoryId,
                    amount = proposal.amount,
                    rolloverEnabled = false,
                    source = BudgetRepository.SOURCE_SUGGESTED,
                    citation = proposal.provenance.evidence.firstOrNull(),
                )
            }.flatten()
        }

    override suspend fun dismissReview(): Result<Boolean, AppError> =
        withContext(dispatchers.io) {
            runCatchingToResult {
                val profileId = activeProfileId.first()
                // The *raw* review, not observeReview(): a claim already on record must still be
                // found so this call can honestly answer false rather than mistaking "already
                // claimed" for "nothing to claim" — the two collapse to the same null otherwise.
                val review = rawReview(profileId).first() ?: return@runCatchingToResult false
                val citation = review.provenance.evidence.first()
                val inserted =
                    database.budgetReviewDao().insertIfNew(
                        BudgetReviewEntity(
                            id = budgetReviewId(profileId, review.monthStartIsoDate),
                            profileId = profileId,
                            monthStartIsoDate = review.monthStartIsoDate,
                            ruleId = citation.ruleId,
                            ruleVersion = citation.ruleVersion,
                            totalBudgetedMinor = review.totalBudgeted.minor,
                            totalActualMinor = review.totalActual.minor,
                            reviewedAtUtcMillis = clock.nowUtcMillis(),
                        ),
                    )
                inserted != INSERT_IGNORED
            }
        }

    override suspend fun deleteBudget(id: String): Result<Unit, AppError> =
        withContext(dispatchers.io) {
            runCatchingToResult {
                val changed = database.budgetDao().softDelete(id, clock.nowUtcMillis())
                if (changed == 0) Err(AppError.NotFound) else Ok(Unit)
            }.flatten()
        }

    // --- writes ---------------------------------------------------------------------------------

    /**
     * The one place a `budget` row with a `category_id` is written.
     * Why:    both public writers land here so the id derivation, the profile scoping and the
     *         created/updated stamps cannot diverge between them — the difference between a typed
     *         budget and an accepted suggestion is two columns, not two code paths.
     * Result: `Ok` with the row's id, or `Validation` when the category does not exist.
     * Input:  [categoryId]; [amount]; [rolloverEnabled]; [source]; [citation] — the rule that
     *         produced the amount, `null` when the user typed it.
     * Output: `Result<String, AppError>`.
     */
    private suspend fun write(
        categoryId: String,
        amount: Money,
        rolloverEnabled: Boolean,
        source: String,
        citation: com.aicfo.core.model.RuleCitation?,
    ): Result<String, AppError> =
        withContext(dispatchers.io) {
            runCatchingToResult {
                val profileId = activeProfileId.first()
                val now = clock.nowUtcMillis()
                val periodStart = MonthWindow.current(clock.today()).startIsoDate
                database.withTransaction {
                    // Checked inside the transaction that then writes: a category deleted on the
                    // taxonomy screen must not gain a budget from a form opened before it went.
                    val category = database.categoryDao().findById(categoryId)
                    if (category == null || category.deletedAtUtcMillis != null) {
                        return@withTransaction Err(AppError.Validation(FIELD_CATEGORY))
                    }
                    val id = categoryBudgetId(profileId, categoryId, periodStart)
                    val existing = database.budgetDao().findById(id)
                    database.budgetDao().upsert(
                        BudgetEntity(
                            id = id,
                            profileId = profileId,
                            categoryId = categoryId,
                            nature = null,
                            periodStartIsoDate = periodStart,
                            amountMinor = amount.minor,
                            rolloverEnabled = rolloverEnabled,
                            source = source,
                            ruleId = citation?.ruleId ?: UNATTRIBUTED_RULE_ID,
                            ruleVersion = citation?.ruleVersion ?: UNATTRIBUTED_RULE_VERSION,
                            // Preserved across a re-save, so "when was this budget first set?"
                            // survives editing it. REPLACE writes a whole row, so without this the
                            // creation stamp would silently become the last edit's.
                            createdAtUtcMillis = existing?.createdAtUtcMillis ?: now,
                            updatedAtUtcMillis = now,
                        ),
                    )
                    Ok(id)
                }
            }.flatten()
        }

    // --- reads ----------------------------------------------------------------------------------

    /**
     * Assembles one category's row, budgeted or not.
     * Why:    an unbudgeted category still has spending, and the screen shows it — so it gets a
     *         status against a zero budget rather than being filtered out. `isUnbudgeted` is what
     *         the UI branches on, not a null status it would have to guard everywhere.
     * Result: the row. Input: [category]; [entity] — the stored budget or `null`; [spent];
     *         [carriedOver]; [month]. Output: [CategoryBudget].
     * Changelog: 2026-08-11 — Created for issue 4.4.
     *            2026-08-16 — Issue 4.7: the unchecked `as Ok` became an explicit failure.
     *
     * **The one engine call in this class that fails the stream instead of vanishing.**
     * [BudgetEngine.status] is the only one of the four with no `Ok(null)` in its contract:
     * `suggest`, `alert` and `review` each document "nothing to say" as a legitimate answer and so
     * have a lane a failure can land in, and this one does not. All three ways of inventing a lane
     * are worse than throwing:
     *
     * - a zeroed [BudgetStatus] would be a figure the app made up (P-03), and indistinguishable
     *   from a real budget of nothing;
     * - dropping the row would delete a category from the screen whose whole job is to list every
     *   category — and worse, `DashboardUiState.budgetTotals` folds these rows into one headline,
     *   so a list quietly one category short is a **wrong total**, not a shorter list;
     * - widening the return to `Flow<Result<…>>` would be the honest shape, but no repository in
     *   this module carries an error inside a stream, and changing that here would rewrite the
     *   contract every `.catch {}` consumer is written against. Issue 4.7 records it as the option
     *   not taken.
     *
     * So it throws, and it throws away the **whole list** rather than the row, for the totals reason
     * above. [BudgetEngineFailure] rather than `error(...)` — which is what
     * `NetWorthRepository.computeFrom` does for a structurally identical call — because
     * `runCatchingToResult` **rethrows** `IllegalStateException`, so an `error()` here would escape
     * [pendingAlerts], [acceptSuggestion] and [acceptReviewProposal] into `viewModelScope` and
     * `CoroutineWorker` as a crash: precisely the §21.6 hole closed on 2026-08-16, reopened. A plain
     * `Exception` is caught there and becomes `Err`, and on the Flow paths it reaches the `.catch`
     * both ViewModels already have.
     */
    private fun categoryBudget(
        category: Category,
        entity: BudgetEntity?,
        spent: Money,
        carriedOver: Money,
        month: MonthWindow,
    ): CategoryBudget {
        val status =
            engine.status(
                BudgetStatusInput(
                    categoryId = category.id,
                    plannedAmount = Money(entity?.amountMinor ?: 0L),
                    carriedOver = carriedOver,
                    spent = spent,
                    daysInPeriod = month.daysInMonth,
                    daysElapsed = month.daysElapsed,
                    nowUtcMillis = clock.nowUtcMillis(),
                ),
            ).getOrElse { failure -> throw BudgetEngineFailure(failure) }
        return CategoryBudget(
            id = entity?.id,
            category = category,
            status = status,
            rolloverEnabled = entity?.rolloverEnabled ?: false,
            source = entity?.source ?: BudgetRepository.SOURCE_MANUAL,
        )
    }

    /**
     * Asks the engine which band, if any, one assembled row has crossed (FR-BUD-004).
     * Why:    reuses the status the row already carries rather than re-reading spend, so the banner
     *         and the figures beside it can never disagree about what was spent — they are the same
     *         two numbers. Unbudgeted rows are skipped before the engine sees them; the engine would
     *         also return null for a zero budget, and relying on that would mean the *screen's*
     *         meaning of "no budget" and the engine's happened to coincide.
     *
     *         **Public** (issue 5.1) — see the interface doc comment for why: a caller that already
     *         holds a row from [observeBudgets] can reach the same answer [observeAlerts] would give
     *         it without opening a second subscription to get there.
     * Result: the alert, or `null` when this row is below the warn band, has no budget, **or the
     *         engine failed** (issue 4.7).
     * Input:  [row]. Output: [CategoryBudgetAlert]?.
     * Changelog: 2026-08-13 — Created for issue 4.5.
     *            2026-08-16 — Issue 4.7: the unchecked `as Ok` became `getOrNull`.
     *
     * **A failed `alert` becomes `null`, like a row below the warn band.** The band is a *highlight
     * over two numbers this row already carries* — `row.status.budgeted` and `row.status.spent` are
     * this function's only inputs, and the card renders both either way. Losing the highlight leaves
     * the figures; it cannot leave the user unaware of what they have spent. Nor can it hide a
     * broken read: this row came from [observeBudgets], whose own engine call fails the stream
     * loudly (see `categoryBudget`).
     *
     * What it does hide is the difference between "no band crossed" and "could not decide", which is
     * real and, with no structured logger in this app, unrecorded. That is the stated cost of
     * following [BudgetEngine.alert]'s own contract, which already documents `Ok(null)` as a
     * legitimate answer — a failure lands in the lane the engine had already opened.
     */
    override fun alertFor(row: CategoryBudget): CategoryBudgetAlert? {
        val budgetId = row.id ?: return null
        val alert =
            engine.alert(
                BudgetAlertInput(
                    categoryId = row.category.id,
                    categoryName = row.category.name,
                    budgeted = row.status.budgeted,
                    spent = row.status.spent,
                    nowUtcMillis = clock.nowUtcMillis(),
                ),
            )
        return alert.getOrNull()?.let { CategoryBudgetAlert(budgetId, row.category, it) }
    }

    /**
     * The last closed month, seen from today (issue 4.6; §5.5).
     * Why:    a named function rather than inlining `MonthWindow.closed(...minusMonths(1))` at every
     *         call site, the way [carriedOverFor] does for rollover — [observeReview] and
     *         [dismissReview] both need the identical window, and a copy that drifted from
     *         `carriedOverFor`'s would make the review disagree with rollover about where last
     *         month ended.
     * Result: the reviewed month, fully elapsed. Input: [today]. Output: [MonthWindow].
     */
    private fun reviewedMonth(today: LocalDate): MonthWindow =
        MonthWindow.closed(LocalDate.parse(MonthWindow.current(today).startIsoDate).minusMonths(1))

    /**
     * The month-end review, before the dismissed-claim check is folded in (issue 4.6; §5.5).
     * Why:    split out of [observeReview] so [dismissReview] can read the same computation without
     *         going through the deduplication that makes an already-dismissed month look like an
     *         empty one — the two are different facts, and only [observeReview] is allowed to
     *         conflate them for the screen.
     * Result: the review, or `null` when nothing was budgeted last month. Re-emitted whenever a
     *         transaction or a budget changes.
     * Input:  [profileId]. Output: `Flow<BudgetReview?>`.
     */
    private fun rawReview(profileId: String): Flow<BudgetReview?> {
        val reviewed = reviewedMonth(clock.today())
        // `clock.today()`, NOT the reviewed month's start. `historyWindow` returns the whole months
        // *before* the month it is given, so passing the reviewed month would drop the reviewed month
        // itself from the history the proposal is priced from — reviewing July while pricing from
        // April–June, ignoring the very month the finding is about. It also broke the guarantee
        // `ENGINE.md` §5.5 and `DefaultBudgetEngine`'s own comment both state, that a reviewed
        // proposal is "provably the same number a plain suggestion would show for the same category":
        // `observeSuggestions` passes `clock.today()` here, so anything else makes the two disagree.
        // Excluding the anomalous month is not a defence either — RULE-BUD-SUGGEST is a *median*,
        // which is chosen precisely to resist a one-off month without dropping it. Found by review,
        // 2026-08-16.
        val history = historyWindow(clock.today())
        return combine(
            database.categoryDao().observeForProfile(profileId),
            database.budgetDao().observeCategoryBudgets(profileId, reviewed.startIsoDate),
            database.transactionDao()
                .observeCategorySpend(profileId, reviewed.startIsoDate, reviewed.actualsEndIsoDate),
            database.transactionDao()
                .observeMonthlyCategorySpend(profileId, history.first, history.second),
        ) { categories, budgets, actuals, monthlySpend ->
            reviewFor(categories.mapNotNull { it.toCategory() }, budgets, actuals, monthlySpend, reviewed)
        }
    }

    /**
     * Turns one closed month's rows into a [BudgetReview].
     * Why:    only categories that had a budget are reviewed — an unbudgeted category has no plan to
     *         have missed, which is [PlannedSection]'s distinction from the budgets screen applied
     *         here too. The per-category monthly-spend history is grouped the same way
     *         [suggestionFor] groups it, because it feeds the same [BudgetEngine.suggest] call by way
     *         of `DefaultBudgetEngine.proposalFor` — one history shape, read twice.
     * Result: the review, or `null` when no category had a budget (a legitimate state, not an error)
     *         **or the engine failed** (issue 4.7) — a third meaning for this `null`, on top of the
     *         two [observeReview] already conflates deliberately. A review the engine could not
     *         compute renders as no card, which is exactly what a dismissed review and an unbudgeted
     *         month already render as. [dismissReview] is unaffected: it reads [rawReview] precisely
     *         so it can tell "already claimed" from "nothing to claim", and a failed engine simply
     *         joins the second.
     * Input:  [categories]; [budgets] — last month's plans; [actuals] — last month's spend;
     *         [monthlySpend] — the same history window `observeSuggestions` reads (the closed months
     *         up to and **including** the reviewed one), so the proposal is the number a plain
     *         suggestion would show; [reviewed] — the closed month's window. Output: [BudgetReview]?.
     */
    private fun reviewFor(
        categories: List<Category>,
        budgets: List<BudgetEntity>,
        actuals: List<com.aicfo.core.database.dao.CategorySpendRow>,
        monthlySpend: List<com.aicfo.core.database.dao.MonthlyCategorySpendRow>,
        reviewed: MonthWindow,
    ): BudgetReview? {
        val budgetByCategory = budgets.associateBy { it.categoryId }
        val actualByCategory = actuals.associate { it.categoryId to Money(it.spentMinor) }
        val historyByCategory = monthlySpend.groupBy { it.categoryId }
        val monthsObserved = monthlySpend.map { it.monthKey }.distinct().size
        val reviewedCategories =
            categories.mapNotNull { category ->
                val budget = budgetByCategory[category.id] ?: return@mapNotNull null
                ReviewedCategoryInput(
                    categoryId = category.id,
                    categoryName = category.name,
                    budgeted = Money(budget.amountMinor),
                    actual = actualByCategory[category.id] ?: Money.ZERO,
                    monthlySpends =
                        historyByCategory[category.id].orEmpty()
                            .map { MonthlySpend("${it.monthKey}-01", Money(it.spentMinor)) },
                )
            }
        val review =
            engine.review(
                BudgetReviewInput(
                    monthStartIsoDate = reviewed.startIsoDate,
                    categories = reviewedCategories,
                    targetMonth = clock.today().monthValue,
                    monthsObserved = monthsObserved,
                    nowUtcMillis = clock.nowUtcMillis(),
                ),
            )
        return review.getOrNull()
    }

    /**
     * What each category has left over from last month, when its budget rolls over (FR-BUD-001).
     * Why:    rollover is defined as "unused amount", so it is last month's budget minus last
     *         month's spend — which means reading a second month. **Only positive leftovers carry**:
     *         rolling a *deficit* forward would silently shrink a budget the user set, turning one
     *         bad month into two without ever saying so.
     *
     *         Read with the suspend DAO rather than a third Flow, because it changes only when a
     *         past month's data changes — which the current month's Flows will re-trigger anyway.
     * Result: leftover per category id; absent when rollover is off or nothing was left.
     * Input:  [profileId]; [month] — the current window; [categoryIds] — the budgeted categories.
     * Output: `Map<String, Money>`.
     */
    private suspend fun carriedOverFor(
        profileId: String,
        month: MonthWindow,
        categoryIds: List<String>,
    ): Map<String, Money> {
        if (categoryIds.isEmpty()) return emptyMap()
        val previous = MonthWindow.closed(LocalDate.parse(month.startIsoDate).minusMonths(1))
        val lastMonthBudgets =
            database.budgetDao()
                .categoryBudgetsForPeriod(profileId, previous.startIsoDate)
                .filter { it.rolloverEnabled }
        if (lastMonthBudgets.isEmpty()) return emptyMap()

        val lastMonthSpend =
            database.transactionDao()
                .observeCategorySpend(profileId, previous.startIsoDate, previous.endIsoDate)
                .first()
                .associate { it.categoryId to Money(it.spentMinor) }

        return lastMonthBudgets.mapNotNull { budget ->
            val categoryId = budget.categoryId ?: return@mapNotNull null
            val leftover = Money(budget.amountMinor) - (lastMonthSpend[categoryId] ?: Money.ZERO)
            if (leftover > Money.ZERO) categoryId to leftover else null
        }.toMap()
    }

    /**
     * Turns one category's monthly totals into a suggestion, or nothing.
     * Why:    the months arrive from a `GROUP BY` that only produces rows for months with spending,
     *         so a category that was untouched in February has two rows, not three — and the engine
     *         must see that as two months of history, which is exactly what it does. Inventing zero
     *         rows for the missing months would drag the median toward zero and suggest a budget
     *         nobody could live on.
     * Result: the suggestion, or `null` when the engine declines for want of history **or fails
     *         outright** (issue 4.7).
     * Input:  [category]; [rows] — this category's months, oldest first; [monthsObserved] — how many
     *         distinct months the *profile* has, which shrinks the seasonal prior.
     * Output: [CategoryBudgetSuggestion]?.
     * Changelog: 2026-08-11 — Created for issue 4.4.
     *            2026-08-16 — Issue 4.7: the unchecked `as Ok` became `getOrNull`.
     *
     * **A failed `suggest` becomes the same `null` as a declined one, deliberately.**
     * [BudgetEngine.suggest] already documents `Ok(null)` as a legitimate answer, so this call site
     * has a lane for "no proposal" and a failure lands in it — the same shape `RecurringRepository`
     * gives its own detector and `TransactionRepository` gives the nature engine. The cost is stated
     * rather than hidden: a suggestion the engine could not compute is indistinguishable here from
     * one it chose not to make, and with no structured logger in this app nothing records which it
     * was. That is the right trade for an offer nobody asked for — losing the budgets list to it
     * would not be.
     */
    private fun suggestionFor(
        category: Category,
        rows: List<com.aicfo.core.database.dao.MonthlyCategorySpendRow>,
        monthsObserved: Int,
    ): CategoryBudgetSuggestion? {
        if (rows.isEmpty()) return null
        val suggestion =
            engine.suggest(
                BudgetSuggestionInput(
                    categoryId = category.id,
                    categoryName = category.name,
                    monthlySpends = rows.map { MonthlySpend("${it.monthKey}-01", Money(it.spentMinor)) },
                    targetMonth = clock.today().monthValue,
                    monthsObserved = monthsObserved,
                    nowUtcMillis = clock.nowUtcMillis(),
                ),
            )
        return suggestion.getOrNull()?.let { CategoryBudgetSuggestion(category, it) }
    }

    /**
     * The date range covering the months the suggestion may read.
     * Why:    the engine takes the last `lookback_months` off whatever it is given, so this only has
     *         to be *at least* that wide — it asks for the three whole months **before** this one,
     *         because a month still being spent in is not a month's spending.
     * Result: inclusive ISO bounds. Input: [today]. Output: a `from` to `to` pair.
     */
    private fun historyWindow(today: LocalDate): Pair<String, String> {
        val lastClosedMonth = today.withDayOfMonth(1).minusMonths(1)
        val firstMonth = lastClosedMonth.minusMonths(HISTORY_MONTHS - 1L)
        return firstMonth.toString() to lastClosedMonth.withDayOfMonth(lastClosedMonth.lengthOfMonth()).toString()
    }

    private companion object {
        const val FIELD_AMOUNT = "amount"
        const val FIELD_CATEGORY = "categoryId"

        /**
         * How many closed months to fetch. Deliberately wider than `RULE-BUD-SUGGEST.lookback_months`
         * is today: the engine trims to the rule's window, so a widened rule keeps working without a
         * matching edit here, and the cost of over-fetching is one extra month per category.
         */
        const val HISTORY_MONTHS = 4

        /** What `@Insert(OnConflictStrategy.IGNORE)` returns when the unique index refused the row. */
        const val INSERT_IGNORED = -1L
    }
}

/**
 * The id of a per-category budget.
 *
 * Why:  derived, not generated, for the reason `budgetId` gives for envelopes — `REPLACE` upserts
 *       are only idempotent if saving the same budget twice produces the same id (P-08). The
 *       envelope form (`profile:budget:<nature>:<period>`) has no slot for a category, so this is a
 *       sibling rather than an extension of it, and the `cat` segment makes a collision between the
 *       two shapes impossible even if a category were ever named `need`.
 * Result: a stable id. Input: [profileId], [categoryId], [periodStartIsoDate]. Output: [String].
 * Changelog: 2026-08-11 — Created for issue 4.4.
 */
internal fun categoryBudgetId(
    profileId: String,
    categoryId: String,
    periodStartIsoDate: String,
): String = "$profileId:budget:cat:$categoryId:$periodStartIsoDate"

/**
 * The id of one alert: one budget, one band.
 *
 * Why:  derived rather than generated, for the reason [categoryBudgetId] gives — and here it earns
 *       its keep twice. The budget id already carries the profile, the category and the month, so
 *       appending the band produces exactly the tuple the unique index protects. A generated id
 *       would leave two different keys claiming to guarantee the same thing, and a bug in either
 *       would show up as a second notification rather than as a failed insert.
 * Result: a stable id. Input: [budgetId]; [band] — `BudgetAlertBand.name`. Output: [String].
 * Changelog: 2026-08-13 — Created for issue 4.5.
 */
internal fun budgetAlertId(
    budgetId: String,
    band: String,
): String = "$budgetId:alert:$band"

/**
 * The id of one review claim: one profile, one reviewed month.
 *
 * Why:  derived rather than generated, for the reason [budgetAlertId] gives — and it is what the
 *       unique index protects here too, one level coarser (no band, no budget: ADR-0020).
 * Result: a stable id. Input: [profileId]; [monthStartIsoDate] — the reviewed month, TIM-002.
 * Output: [String]. Changelog: 2026-08-15 — Created for issue 4.6.
 */
internal fun budgetReviewId(
    profileId: String,
    monthStartIsoDate: String,
): String = "$profileId:review:$monthStartIsoDate"

/**
 * Collapses a `Result<Result<T, E>, E>` produced by `runCatchingToResult` around a block that
 * already returns a `Result`.
 * Why:    the transaction body has to be able to return `Err` for a validation failure while the
 *         wrapper catches genuine exceptions; without this every caller unwraps two layers.
 *
 *         **Deliberately a third copy**, not a shared utility. `CategoryRepository` and
 *         `TransactionNature` each carry their own, and `TransactionNature`'s KDoc records why:
 *         file-private in each, so the *reason* for the double wrapping travels with the use rather
 *         than being a generic in a utility file that explains none of them.
 * Result: the inner result. Input: the receiver. Output: `Result<T, AppError>`.
 * Changelog: 2026-08-11 — Created for issue 4.4.
 */
private fun <T> Result<Result<T, AppError>, AppError>.flatten(): Result<T, AppError> =
    when (this) {
        is Ok -> value
        is Err -> this
    }

/**
 * The one engine failure this module reports by throwing (issue 4.7).
 *
 * Why:  `categoryBudget` computes a [com.aicfo.domain.engines.budget.BudgetStatus] that has nowhere
 *       to be absent — see its doc comment for why every alternative to throwing is worse. A
 *       `Flow<List<T>>` has exactly one failure channel and it is the exception channel, which
 *       `.catch {}` exists to turn back into state; §21.6's rule is about *signatures* that promise
 *       a `Result` and then also throw, and `observeBudgets(): Flow<List<CategoryBudget>>` promises
 *       no such thing. This type is what makes that throw a considered one rather than a stray cast.
 *
 *       **It extends `Exception`, not `IllegalStateException`, and that is the whole point.**
 *       `runCatchingToResult` deliberately rethrows `IllegalStateException`/`IllegalArgumentException`
 *       as programmer errors, so `error(...)` here — the shape `NetWorthRepository.computeFrom` uses
 *       for a structurally identical call — would sail straight through [BudgetRepository.pendingAlerts],
 *       [BudgetRepository.acceptSuggestion] and [BudgetRepository.acceptReviewProposal] into
 *       `viewModelScope` and `CoroutineWorker`. A plain `Exception` is caught there and becomes the
 *       `Err` those signatures promise.
 *
 *       **`internal`, so it cannot become an app-wide hatch.** `:core:common`'s `Result` exists to
 *       stop exceptions crossing layers; a public "throw an `AppError` instead of returning one"
 *       type living beside it would be an invitation the next contributor could cite. Scoped here,
 *       it is one module's answer to one non-nullable engine call.
 * What: a carrier for the engine's own [AppError], thrown from a `Flow` transform.
 * Result: both ViewModels' existing `.catch { failure.toAppError().code }` report an error rather
 *       than the app dying, and the suspend readers get `Err` — with a class name that points at
 *       the budget engine instead of at a bad cast.
 * Changelog: 2026-08-16 — Created for issue 4.7.
 *
 * **[appError] is carried although nothing reads it yet.** There is no structured logger in this
 * app and `audit_log` is closed to non-security events, so today it is only visible in a stack
 * trace and in this module's tests. Keeping it typed means the day a logger exists, promoting this
 * to `:core:common` with a `Throwable.toAppError()` branch is one commit made for a reason that
 * exists — rather than a shape invented ahead of its use. It is safe to carry and safe to name:
 * `AppError` holds a code and a fixed message, never PII or an amount (P-01, §21.6).
 */
internal class BudgetEngineFailure(val appError: AppError) : Exception()
