package com.aicfo.feature.categories

import com.aicfo.core.common.AppError
import com.aicfo.core.common.Err
import com.aicfo.core.common.Ok
import com.aicfo.core.common.Result
import com.aicfo.core.model.Category
import com.aicfo.core.model.CategoryNature
import com.aicfo.data.repository.CategoryRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * An in-memory [CategoryRepository] for this module's tests (issue 4.1).
 *
 * Why:  the ViewModel's job is state, not storage, and `CategoryRepositoryTest` already proves the
 *       SQL against a real engine. What the tests here need is control over the *outcome* — a save
 *       that is refused, a count that fails, a list that changes underneath the screen — which a
 *       real database makes awkward to arrange and a fake makes one line.
 * What: a `MutableStateFlow` of categories, plus per-method overrides for the failure paths.
 * Result: every branch in `CategoriesViewModel` is reachable from a test.
 * Changelog: 2026-08-08 — Created for issue 4.1.
 *
 * **It does not re-implement the repository's validation**, and that is deliberate: duplicating the
 * uniqueness and nesting rules here would mean the ViewModel tests pass against a second, drifting
 * copy of them. A test that wants a refusal states the refusal it wants ([nextError]).
 *
 * Input:  [initial] — the categories the screen starts with. Output: a fake repository.
 */
internal class FakeCategoryRepository(
    initial: List<Category> = emptyList(),
) : CategoryRepository {
    private val categories = MutableStateFlow(initial)

    /** What the next write returns, or `null` for success. Cleared after it is used once. */
    var nextError: AppError? = null

    /** What [countUsage] returns. `null` makes it fail, which the dialog must render honestly. */
    var usageCount: Int? = 0

    /** The ids passed to [delete], in order — so a test can assert what was actually deleted. */
    val deleted: MutableList<String> = mutableListOf()

    override fun observeCategories(): Flow<List<Category>> = categories

    override suspend fun ensureSeeded(): Result<Int, AppError> = Ok(0)

    override suspend fun create(
        name: String,
        nature: CategoryNature,
        parentId: String?,
    ): Result<Category, AppError> {
        takeError()?.let { return Err(it) }
        val created =
            Category(id = "category:${categories.value.size + 1}", name = name, nature = nature, parentId = parentId)
        categories.value = categories.value + created
        return Ok(created)
    }

    override suspend fun update(
        id: String,
        name: String,
        nature: CategoryNature,
        parentId: String?,
    ): Result<Category, AppError> {
        val arranged = takeError()
        val existing = categories.value.firstOrNull { it.id == id }
        if (arranged != null || existing == null) return Err(arranged ?: AppError.NotFound)
        val updated = existing.copy(name = name, nature = nature, parentId = parentId)
        categories.value = categories.value.map { if (it.id == id) updated else it }
        return Ok(updated)
    }

    override suspend fun delete(id: String): Result<Unit, AppError> {
        takeError()?.let { return Err(it) }
        deleted += id
        categories.value = categories.value.filterNot { it.id == id }
        return Ok(Unit)
    }

    override suspend fun countUsage(id: String): Result<Int, AppError> =
        usageCount?.let { Ok(it) } ?: Err(AppError.Storage("count failed"))

    /**
     * Consumes [nextError] so one arranged failure affects one call.
     * Why:    a sticky error would make "the second save succeeds after a rejected first" impossible
     *         to write, which is exactly the sequence the sheet's keep-the-typing behaviour is about.
     * Result: the error to return, or `null`. Input: none. Output: `AppError?`.
     * Changelog: 2026-08-08 — Created for issue 4.1.
     */
    private fun takeError(): AppError? = nextError.also { nextError = null }
}
