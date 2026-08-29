package com.aicfo.feature.categories

import androidx.compose.runtime.Immutable
import com.aicfo.core.model.Category
import com.aicfo.core.model.CategoryNature

/**
 * Everything the categories editor renders, as one value (issue 4.1; ARC-004, FR-SET-001).
 *
 * Why:  §21.2 requires one immutable state class per screen exposed as a `StateFlow`, so a screen
 *       can never render a half-updated mix and every state it can be in is nameable in a test.
 * What: the loading flag, the taxonomy grouped for display, the open editor sheet, the open delete
 *       confirmation, and any error to surface.
 * Result: the editor is a pure function of this value.
 * Changelog: 2026-08-08 — Created for issue 4.1.
 *
 * **[categories] holds `Category`, a `:core:model` type, never a Room entity** (ARC-005).
 *
 * Input:  [isLoading]; [categories] — name-ordered, empty for a profile with none; [editing] — the
 *         open add/edit sheet, `null` when closed; [confirmingDelete] — the open confirmation,
 *         `null` when closed; [errorCode] — an `AppError.code`, never a message, so the wording
 *         stays in `strings.xml` (§21.6).
 * Output: an immutable snapshot for the composable.
 */
@Immutable
data class CategoriesUiState(
    val isLoading: Boolean = true,
    val categories: List<Category> = emptyList(),
    val editing: CategoryEditorState? = null,
    val confirmingDelete: DeleteConfirmation? = null,
    val errorCode: String? = null,
) {
    /**
     * Whether to show the "no categories yet" invitation rather than a list.
     *
     * Why: **this project has widened an `isEmpty` clause four times already**, each time because a
     *      new state emptied the list without emptying the profile, and each time the omission was
     *      found by a test rather than by reading (`docs/memory.md`). The states that empty
     *      [categories] here are: still loading, a failed read, and a profile that genuinely has
     *      none. Only the third is an invitation — rendering the first would flash it before the
     *      store answered, and rendering the second would hide a database that would not open
     *      behind a cheerful "add your first category".
     *
     *      A fifth clause is needed the day this screen gains a filter or a search box.
     */
    val isEmpty: Boolean get() = !isLoading && errorCode == null && categories.isEmpty()

    /**
     * The taxonomy grouped for display: each top-level category with its children.
     *
     * Why:    §8's taxonomy is one level deep, and the screen has to draw a child under its parent
     *         rather than alphabetically among the rest. Derived here rather than in the composable
     *         so the grouping is assertable in a unit test without rendering anything.
     * Result: parents in the order [categories] arrived (name-ordered by the DAO), each with its
     *         children in that same order. **A child whose parent is missing is promoted to the top
     *         level rather than dropped** — a category the user can see is one they can fix, and a
     *         category filtered out of its own editor is one they cannot.
     */
    val grouped: List<CategoryGroup>
        get() {
            val childrenByParent = categories.filter { it.parentId != null }.groupBy { it.parentId }
            val topLevelIds = categories.filter { it.parentId == null }.map { it.id }.toSet()
            val orphans = categories.filter { it.parentId != null && it.parentId !in topLevelIds }
            return categories.filter { it.parentId == null }
                .map { parent -> CategoryGroup(parent, childrenByParent[parent.id].orEmpty()) }
                .plus(orphans.map { CategoryGroup(it, emptyList()) })
        }

    /** The categories a new or edited row may be nested under — top-level ones, minus itself. */
    fun parentOptions(excludingId: String?): List<Category> =
        categories.filter { it.parentId == null && it.id != excludingId }
}

/**
 * One top-level category and its children (issue 4.1).
 * Why:    a `Map<Category, List<Category>>` would lose the order the DAO sorted, and order is what
 *         makes a list findable.
 * Result: a section of the list. Input: [parent]; [children] — possibly empty. Output: an immutable
 *         value.
 * Changelog: 2026-08-08 — Created for issue 4.1.
 */
@Immutable
data class CategoryGroup(
    val parent: Category,
    val children: List<Category>,
)

/**
 * The open add/edit sheet (issue 4.1; ARC-004).
 *
 * Why:  add and edit are the same three fields and the same validation, so they are one sheet with
 *       a nullable [id] rather than two — the shape `AccountEditorUiState` already uses, and for the
 *       same reason: duplicating the form is how the two drift apart.
 * What: the fields as the user is typing them, plus whether a save is in flight.
 * Result: every state the sheet can be in is assertable.
 * Changelog: 2026-08-08 — Created for issue 4.1.
 *
 * Input:  [id] — `null` when adding; [name]; [nature]; [parentId] — `null` for a top-level
 *         category; [isSaving] — the write is in flight, so Save is disabled.
 * Output: an immutable value.
 */
@Immutable
data class CategoryEditorState(
    val id: String? = null,
    val name: String = "",
    val nature: CategoryNature = CategoryNature.NEED,
    val parentId: String? = null,
    val isSaving: Boolean = false,
) {
    /** Whether this is an edit of an existing category rather than a new one. */
    val isEditing: Boolean get() = id != null

    /**
     * Whether Save may proceed.
     *
     * Only blankness is checked, and only on the name — the same rule the repository enforces.
     * Uniqueness is deliberately **not** duplicated here: the repository checks it inside the
     * transaction that writes, which is the only place the answer cannot go stale, and a second copy
     * of the rule is how the screen and the store come to disagree about what is allowed.
     */
    val canSave: Boolean get() = name.isNotBlank() && !isSaving
}

/**
 * The open delete confirmation (issue 4.1; P-02).
 *
 * Why:  deleting a category does not delete the money spent in it — the transactions keep their
 *       `category_id` and start reading as "Uncategorised". That is deliberate, and surprising
 *       enough that the user is told the consequence and its size before they confirm it, rather
 *       than discovering it on the transactions list afterwards.
 * What: which category, its name, and how many rows carry it.
 * Result: the dialog can state a number the user can check rather than a vague warning.
 * Changelog: 2026-08-08 — Created for issue 4.1.
 *
 * Input:  [id]; [name] — for the message; [usageCount] — live transactions plus live split lines,
 *         `null` while it is still being counted so the dialog can say so rather than claim zero.
 * Output: an immutable value.
 */
@Immutable
data class DeleteConfirmation(
    val id: String,
    val name: String,
    val usageCount: Int? = null,
)

/**
 * Everything the user can do in the categories editor (ARC-004).
 *
 * Why:    events flow **up** through a sealed interface, so the composable holds no logic and the
 *         compiler lists every interaction the ViewModel must handle.
 * Result: the screen's complete input surface.
 * Changelog: 2026-08-08 — Created for issue 4.1.
 */
sealed interface CategoriesEvent {
    /** The user tapped Add. */
    data object AddClicked : CategoriesEvent

    /** The user tapped a category to edit it. */
    data class EditClicked(val id: String) : CategoriesEvent

    /** The user typed in the name field. */
    data class NameChanged(val value: String) : CategoriesEvent

    /** The user picked one of §8.3's five natures. */
    data class NatureChanged(val value: CategoryNature) : CategoriesEvent

    /** The user chose a parent, or cleared it back to top-level. */
    data class ParentChanged(val value: String?) : CategoriesEvent

    /** The user tapped Save in the sheet. */
    data object Save : CategoriesEvent

    /** The user dismissed the sheet without saving. */
    data object CancelEdit : CategoriesEvent

    /** The user tapped Delete on a category — opens the confirmation, does not delete. */
    data class DeleteClicked(val id: String) : CategoriesEvent

    /** The user confirmed the delete. */
    data object ConfirmDelete : CategoriesEvent

    /** The user backed out of the confirmation. */
    data object CancelDelete : CategoriesEvent

    /** The user dismissed the error banner. */
    data object DismissError : CategoriesEvent
}
