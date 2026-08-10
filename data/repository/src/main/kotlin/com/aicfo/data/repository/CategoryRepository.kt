package com.aicfo.data.repository

import androidx.room.withTransaction
import com.aicfo.core.common.AppError
import com.aicfo.core.common.Clock
import com.aicfo.core.common.DispatcherProvider
import com.aicfo.core.common.Err
import com.aicfo.core.common.IdGenerator
import com.aicfo.core.common.Ok
import com.aicfo.core.common.Result
import com.aicfo.core.common.runCatchingToResult
import com.aicfo.core.database.CfoDatabase
import com.aicfo.core.database.entity.CategoryEntity
import com.aicfo.core.model.Category
import com.aicfo.core.model.CategoryNature
import com.aicfo.core.model.CategorySeed
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

/**
 * The category taxonomy: seed it, read it, and let the user change it (issue 4.1; FR-SET-001).
 *
 * Why:  `category`, `CategoryDao` and `transactions.category_id` have existed since issue 1.6, and
 *       until this class **the only thing that ever wrote a category row was `DemoDataset`** —
 *       `DemoModeRepositoryTest` asserts a real profile has exactly zero. So the add-transaction
 *       screen offered an empty chip row, every transaction on a real profile read "Uncategorised",
 *       and FR-SET-001's editor had nothing to edit. This class is what makes the taxonomy real, and
 *       [ensureSeeded] is what makes it non-empty on the first launch rather than the first edit.
 * What: one observation query, one idempotent seed, and five writes.
 * Result: the taxonomy is the user's to manage, and it exists before they manage it.
 * Changelog: 2026-08-08 — Created for issue 4.1 (FR-SET-001, AI-CLSN-001).
 *
 * **The only class allowed to touch [com.aicfo.core.database.dao.CategoryDao] for the editor
 * (ARC-005).** The categories ViewModel sees [Category] — a `:core:model` type — and never a Room
 * entity. `TransactionRepository.observeCategories` reads the same table for the add screen's chips
 * and shares this module's one `CategoryEntity.toCategory` mapper, so the two views of a category
 * cannot disagree about what a category is.
 *
 * **Deletes are soft (DB-002), and a soft delete does not un-spend the money.** A transaction keeps
 * its `category_id` when the category goes; because the observation query excludes deleted rows, it
 * starts reading as "Uncategorised". That is deliberate — rewriting history to erase a category
 * would silently change what past months looked like — and it is surprising, so [countUsage] exists
 * to let the editor state the consequence before the user confirms it (P-02).
 *
 * **Nothing here touches money or time arithmetic.** Categories carry no amounts (MNY-001 does not
 * apply) and no dates; the only clock read is the `updated_at` stamp, taken from the injected
 * [Clock] like every other write in this module (TIM-001).
 */
interface CategoryRepository {
    /**
     * Observes the active profile's live categories.
     * Why:    the caller does not choose the profile, for the same reason
     *         [AccountRepository.observeAccounts] does not — demo mode puts its sample data under a
     *         second profile id, and answering "which profile?" here means the editor follows the
     *         demo without importing it.
     * Result: emits on every change, sorted by name; an empty list where there is no taxonomy, which
     *         the screen must render as an empty state.
     * Input:  none. Output: `Flow<List<Category>>`.
     */
    fun observeCategories(): Flow<List<Category>>

    /**
     * Writes the default taxonomy, once per profile.
     *
     * Why:    a profile with no categories cannot use FR-TXN-002's "amount → category suggestion →
     *         save", and the three obvious places to seed from all fail on some path:
     *         `OnboardingWriter` only touches DataStore, `QuickSetupRepository.applySeeds` returns
     *         early for a user who skipped quick setup, and seeding from the editor would leave the
     *         add screen empty for anyone who never opens it. One idempotent call at cold start
     *         covers all three, plus the profiles that were onboarded before this issue existed.
     * Result: `Ok(0)` when the profile already has categories — including when every one of them has
     *         been deleted, because **a soft-deleted row is proof the profile was seeded** and
     *         restoring it would overrule a decision the user made on purpose (P-07). Otherwise
     *         `Ok(15)` and the [CategorySeed] rows are written. `Err(Storage)` writes nothing.
     * Input:  none. Output: `Result<Int, AppError>` — how many rows were written.
     */
    suspend fun ensureSeeded(): Result<Int, AppError>

    /**
     * Creates a category.
     * Result: `Ok` with the stored category; `Err(Validation)` naming the field at fault — `name`
     *         when it is blank or already taken on this profile, `parentId` when the parent does not
     *         exist or is itself a child (§8's taxonomy is one level deep).
     * Input:  [name] — the user's own words, trimmed before it is stored; [nature]; [parentId] —
     *         `null` for a top-level category. Output: `Result<Category, AppError>`.
     */
    suspend fun create(
        name: String,
        nature: CategoryNature,
        parentId: String? = null,
    ): Result<Category, AppError>

    /**
     * Renames a category, changes its nature, or re-parents it — whichever the caller passes.
     * Why:    one write rather than three, because the editor's sheet saves all three fields at once
     *         and three calls would let a rename land while a nature change failed.
     * Result: `Ok` with the updated category; `Err(NotFound)` when the id names nothing live;
     *         `Err(Validation)` on the same rules [create] applies, plus `parentId` when a category
     *         is given a parent while it has children of its own, or is made its own parent.
     * Input:  [id]; [name]; [nature]; [parentId]. Output: `Result<Category, AppError>`.
     */
    suspend fun update(
        id: String,
        name: String,
        nature: CategoryNature,
        parentId: String? = null,
    ): Result<Category, AppError>

    /**
     * Soft-deletes a category (DB-002).
     * Result: `Ok(Unit)`; `Err(NotFound)` when the id names nothing live; `Err(Validation)` on
     *         `children` when it still has live children, which would otherwise be orphaned behind a
     *         `parent_id` that resolves to a deleted row.
     * Input:  [id]. Output: `Result<Unit, AppError>`.
     */
    suspend fun delete(id: String): Result<Unit, AppError>

    /**
     * Counts what a category is attached to, for the delete confirmation (P-02).
     * Result: live transactions plus live split lines carrying this category; `0` for an unused one.
     * Input:  [id]. Output: `Result<Int, AppError>`.
     */
    suspend fun countUsage(id: String): Result<Int, AppError>

    companion object {
        /** The prefix every category id carries, demo rows included (`DemoDataset.categoryId`). */
        const val ID_PREFIX = "category"
    }
}

/**
 * The Room-backed [CategoryRepository].
 *
 * Why:    takes the whole [database] rather than the DAO alone, because uniqueness and nesting are
 *         checked against rows the same statement then writes — that read and that write have to sit
 *         in one `withTransaction`, which is a method on the database. Two saves racing into two
 *         categories called "Fuel" is otherwise a real outcome, and there is no unique index on
 *         `(profile_id, name)` to catch it: names are the user's own words and case-folding them in
 *         SQL would need a collation the schema does not declare.
 * Result:  a working repository.
 * Changelog: 2026-08-08 — Created for issue 4.1.
 *
 * **Two id schemes, and the split is deliberate.** A seeded row's id is *derived* from its
 * [CategorySeed] key, which is what makes [ensureSeeded] land on the same fifteen primary keys every
 * time (P-08). A user-created row's id comes from the injected [IdGenerator], like every other
 * repository in this module. The first draft derived that one too — from the name and the create
 * stamp — and `a name freed by a delete can be used again` failed against it: deleting "Fuel" and
 * recreating it inside the same millisecond produced the *same* primary key, so `REPLACE` quietly
 * resurrected the soft-deleted row instead of creating a new one, carrying the old nature with it.
 * **A timestamp is not a uniqueness source.**
 *
 * Input:  [database] — the open, encrypted database; [clock] — TIM-001, for the row stamps;
 *         [ids] — the injected id source (P-08); [dispatchers] — database I/O off the caller's
 *         thread; [activeProfileId] — which profile is read and written, so the demo gets its own
 *         taxonomy.
 * Output: a [CategoryRepository].
 */
@OptIn(ExperimentalCoroutinesApi::class)
internal class RoomCategoryRepository(
    private val database: CfoDatabase,
    private val clock: Clock,
    private val ids: IdGenerator,
    private val dispatchers: DispatcherProvider,
    private val activeProfileId: Flow<String>,
) : CategoryRepository {
    override fun observeCategories(): Flow<List<Category>> =
        // flatMapLatest, not map: entering or leaving the demo must *switch* which query is observed,
        // cancelling the previous one — the reason `QuickSetupRepository` states at length.
        activeProfileId.flatMapLatest { profileId ->
            database.categoryDao().observeForProfile(profileId)
                // mapNotNull: a row whose `nature` this build does not recognise is dropped rather
                // than guessed at — see `CategoryEntity.toCategory`.
                .map { rows -> rows.mapNotNull { it.toCategory() } }
                .flowOn(dispatchers.io)
        }

    override suspend fun ensureSeeded(): Result<Int, AppError> =
        withContext(dispatchers.io) {
            runCatchingToResult {
                val profileId = activeProfileIdNow()
                val now = clock.nowUtcMillis()
                database.withTransaction {
                    // Counted inside the transaction, so two cold starts racing cannot both see zero
                    // and both write. Counts deleted rows too — see CategoryDao.countForProfile.
                    if (database.categoryDao().countForProfile(profileId) > 0) return@withTransaction 0
                    val rows =
                        CategorySeed.rows.map { seed ->
                            CategoryEntity(
                                // Derived from the seed's stable key, never from its name: the user
                                // may rename "Dining" to anything, and the row must stay the row.
                                id = seededId(profileId, seed.key),
                                profileId = profileId,
                                name = seed.name,
                                nature = seed.nature.storedValue,
                                // True only says "this came from the knowledge base, not from you".
                                // It grants the row no protection — see Category.isSystem.
                                isSystem = true,
                                createdAtUtcMillis = now,
                                updatedAtUtcMillis = now,
                            )
                        }
                    database.categoryDao().upsertAll(rows)
                    rows.size
                }
            }
        }

    override suspend fun create(
        name: String,
        nature: CategoryNature,
        parentId: String?,
    ): Result<Category, AppError> {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return Err(AppError.Validation(FIELD_NAME))
        return withContext(dispatchers.io) {
            runCatchingToResult {
                val profileId = activeProfileIdNow()
                val now = clock.nowUtcMillis()
                database.withTransaction {
                    val live = database.categoryDao().liveForProfile(profileId)
                    live.rejectDuplicateName(trimmed, excludingId = null)?.let { return@withTransaction Err(it) }
                    live.rejectBadParent(parentId, childId = null)?.let { return@withTransaction Err(it) }
                    val entity =
                        CategoryEntity(
                            // Generated, not derived — see the class note. A derived id collided
                            // with the row a delete had just soft-deleted.
                            id = ids.newId(CategoryRepository.ID_PREFIX),
                            profileId = profileId,
                            name = trimmed,
                            parentId = parentId,
                            nature = nature.storedValue,
                            isSystem = false,
                            createdAtUtcMillis = now,
                            updatedAtUtcMillis = now,
                        )
                    database.categoryDao().upsert(entity)
                    // Built from the values in hand rather than re-parsed out of the entity: this
                    // path knows the nature is valid because it was handed one, so a `null` here
                    // could only ever be a bug, and `!!` would be the place it crashed.
                    Ok(entity.asCategory(nature))
                }
            }.flatten()
        }
    }

    override suspend fun update(
        id: String,
        name: String,
        nature: CategoryNature,
        parentId: String?,
    ): Result<Category, AppError> {
        val trimmed = name.trim()
        // Both cheap guards in one `when`, so this function keeps to detekt's two-return limit —
        // and so the two things checked before any I/O read as the one list they are.
        val rejectedField =
            when {
                trimmed.isEmpty() -> FIELD_NAME
                parentId == id -> FIELD_PARENT
                else -> null
            }
        if (rejectedField != null) return Err(AppError.Validation(rejectedField))
        return withContext(dispatchers.io) {
            runCatchingToResult {
                val profileId = activeProfileIdNow()
                database.withTransaction {
                    val existing = database.categoryDao().findById(id)
                    if (existing == null || existing.deletedAtUtcMillis != null) {
                        return@withTransaction Err(AppError.NotFound)
                    }
                    val live = database.categoryDao().liveForProfile(profileId)
                    live.rejectDuplicateName(trimmed, excludingId = id)?.let { return@withTransaction Err(it) }
                    live.rejectBadParent(parentId, childId = id)?.let { return@withTransaction Err(it) }
                    // Giving a parent to a category that has children would make a three-level
                    // taxonomy out of a two-level one — §8's category/subcategory pair, no deeper.
                    if (parentId != null && database.categoryDao().countLiveChildren(id) > 0) {
                        return@withTransaction Err(AppError.Validation(FIELD_PARENT))
                    }
                    val updated =
                        existing.copy(
                            name = trimmed,
                            parentId = parentId,
                            nature = nature.storedValue,
                            updatedAtUtcMillis = clock.nowUtcMillis(),
                        )
                    database.categoryDao().update(updated)
                    Ok(updated.asCategory(nature))
                }
            }.flatten()
        }
    }

    override suspend fun delete(id: String): Result<Unit, AppError> =
        withContext(dispatchers.io) {
            runCatchingToResult {
                database.withTransaction {
                    val existing = database.categoryDao().findById(id)
                    if (existing == null || existing.deletedAtUtcMillis != null) {
                        return@withTransaction Err(AppError.NotFound)
                    }
                    if (database.categoryDao().countLiveChildren(id) > 0) {
                        return@withTransaction Err(AppError.Validation(FIELD_CHILDREN))
                    }
                    val now = clock.nowUtcMillis()
                    database.categoryDao().update(
                        existing.copy(deletedAtUtcMillis = now, updatedAtUtcMillis = now),
                    )
                    Ok(Unit)
                }
            }.flatten()
        }

    override suspend fun countUsage(id: String): Result<Int, AppError> =
        withContext(dispatchers.io) {
            runCatchingToResult { database.transactionDao().countForCategory(id) }
        }

    /**
     * Rejects a name another live category on this profile already uses.
     * Why:    two categories called "Fuel" are indistinguishable on every chip, in every budget and
     *         in every insight that names one. Compared case-insensitively because `fuel` and `Fuel`
     *         are the same category to the person typing them.
     * Result: the error to return, or `null` when the name is free. Input: the receiver — the live
     *         rows; [name] — already trimmed; [excludingId] — the row being edited, which may of
     *         course keep its own name. Output: `AppError?`.
     * Changelog: 2026-08-08 — Created for issue 4.1.
     */
    private fun List<CategoryEntity>.rejectDuplicateName(
        name: String,
        excludingId: String?,
    ): AppError? =
        AppError.Validation(FIELD_NAME).takeIf {
            any { it.id != excludingId && it.name.equals(name, ignoreCase = true) }
        }

    /**
     * Rejects a parent that does not exist, or one that is itself a child.
     * Why:    FR-TXN-001 names "category, subcategory" — two levels. A grandchild would render under
     *         a parent the editor does not draw and would be reachable only by editing it again.
     * Result: the error to return, or `null` for a valid parent (including `null`, meaning
     *         top-level). Input: the receiver — the live rows; [parentId]; [childId] — the row being
     *         edited, so a category cannot be made its own parent. Output: `AppError?`.
     * Changelog: 2026-08-08 — Created for issue 4.1.
     */
    private fun List<CategoryEntity>.rejectBadParent(
        parentId: String?,
        childId: String?,
    ): AppError? {
        if (parentId == null) return null
        if (parentId == childId) return AppError.Validation(FIELD_PARENT)
        val parent = firstOrNull { it.id == parentId } ?: return AppError.Validation(FIELD_PARENT)
        return AppError.Validation(FIELD_PARENT).takeIf { parent.parentId != null }
    }

    /**
     * Reads the active profile id once.
     * Why:    the same one-shot read [RoomAccountRepository] uses — a write needs a value, not a
     *         subscription, and `activeProfileId` always has a current one.
     * Result: the id. Input: none. Output: [String].
     * Changelog: 2026-08-08 — Created for issue 4.1.
     */
    private suspend fun activeProfileIdNow(): String = activeProfileId.first()

    private companion object {
        const val FIELD_NAME = "name"
        const val FIELD_PARENT = "parentId"
        const val FIELD_CHILDREN = "children"
    }
}

/**
 * Converts a row this class just wrote into the domain model, with the nature it was written with.
 * Why:    `CategoryEntity.toCategory` is nullable because it re-parses a *stored* nature string, and
 *         a write path has no stored string to distrust — it was handed a [CategoryNature]. Using
 *         the shared mapper here would mean a `!!` on every write, which is the wrong shape for
 *         "this cannot fail" (§21.6: crashes are for programmer errors, not for a mapper).
 * Result: a [Category]. Input: the receiver — a row just written; [nature] — what it was written
 *         with. Output: [Category].
 * Changelog: 2026-08-08 — Created for issue 4.1.
 */
private fun CategoryEntity.asCategory(nature: CategoryNature): Category =
    Category(id = id, name = name, nature = nature, parentId = parentId, isSystem = isSystem)

/**
 * The id a seeded category gets.
 * Why:    derived rather than generated, so re-running the seed on the same profile would land on
 *         the same fifteen primary keys instead of accumulating a second set — the determinism
 *         `QuickSetupRepository`'s derived budget ids rely on for the same reason (P-08).
 * Result: a stable id. Input: [profileId]; [key] — a [CategorySeed] row's key. Output: [String].
 * Changelog: 2026-08-08 — Created for issue 4.1.
 */
internal fun seededId(
    profileId: String,
    key: String,
): String = "$profileId:${CategoryRepository.ID_PREFIX}:$key"

/**
 * Unwraps a `Result` that a `runCatchingToResult` block produced around another `Result`.
 * Why:    the validation branches inside `withTransaction` need to abort the transaction *and* carry
 *         their own error, so the block's value is itself a `Result`. Without this the caller would
 *         get `Result<Result<T, AppError>, AppError>` and every call site would flatten it by hand.
 * Result: the inner result when the outer one succeeded, the storage error otherwise.
 * Input:  the receiver. Output: `Result<T, AppError>`.
 * Changelog: 2026-08-08 — Created for issue 4.1.
 */
private fun <T> Result<Result<T, AppError>, AppError>.flatten(): Result<T, AppError> =
    when (this) {
        is Ok -> value
        is Err -> this
    }
