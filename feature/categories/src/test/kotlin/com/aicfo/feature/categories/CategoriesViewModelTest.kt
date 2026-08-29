package com.aicfo.feature.categories

import app.cash.turbine.test
import com.aicfo.core.common.AppError
import com.aicfo.core.model.Category
import com.aicfo.core.model.CategoryNature
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Tests for [CategoriesViewModel] — the state half of issue 4.1 (FR-SET-001, ARC-004).
 *
 * Why:  four things about this screen are invisible until they are wrong. **An empty list is three
 *       different situations** and only one of them is an invitation to add a category — the clause
 *       that decides has been widened four times elsewhere in this app, always caught by a test.
 *       **A rejected save must keep the user's typing**, or they retype a name to find out what was
 *       wrong with it. **The delete count must not claim zero when it does not know.** And the three
 *       validation refusals must stay distinguishable, because `AppError.Validation` gives them all
 *       the same code and the fixes are different.
 * What: the full `UiState` sequence through load, add, edit, delete and every failure branch.
 * Result: every state the editor can be in is reachable and asserted.
 * Changelog: 2026-08-08 — Created for issue 4.1.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CategoriesViewModelTest {
    /** Input: none. Output: pins `viewModelScope` so collectors and writes run inline. */
    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    /** Input: none. Output: releases the main dispatcher. */
    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    // --- loading and emptiness --------------------------------------------------------------------

    @Test
    fun `the list loads and stops loading`() =
        runTest {
            val repository = FakeCategoryRepository(listOf(category("category:1", "Rent")))
            val viewModel = CategoriesViewModel(repository)

            viewModel.uiState.test {
                val loaded = awaitItem()
                assertFalse(loaded.isLoading)
                assertEquals(listOf("Rent"), loaded.categories.map { it.name })
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun `a profile with no categories is empty, but a loading one is not`() =
        runTest {
            assertTrue(CategoriesUiState(isLoading = false).isEmpty)
            // The invitation must not flash before the store has answered.
            assertFalse(CategoriesUiState(isLoading = true).isEmpty)
        }

    @Test
    fun `a failed read is not an empty profile`() =
        runTest {
            // The case the guard exists for: a database that will not open would otherwise render as
            // a cheerful "add your first category", hiding the failure from the one user who needs
            // to see it.
            val failed = CategoriesUiState(isLoading = false, errorCode = AppError.Storage("x").code)
            assertFalse(failed.isEmpty)
        }

    // --- grouping ---------------------------------------------------------------------------------

    @Test
    fun `children are grouped under their parent`() =
        runTest {
            val repository =
                FakeCategoryRepository(
                    listOf(
                        category("category:1", "Transport"),
                        category("category:2", "Cabs", parentId = "category:1"),
                        category("category:3", "Rent"),
                    ),
                )
            val viewModel = CategoriesViewModel(repository)

            val grouped = viewModel.uiState.value.grouped
            assertEquals(listOf("Transport", "Rent"), grouped.map { it.parent.name })
            assertEquals(listOf("Cabs"), grouped.first().children.map { it.name })
            assertTrue(grouped.last().children.isEmpty())
        }

    @Test
    fun `a child whose parent is missing is still shown`() =
        runTest {
            // A category the user can see is one they can fix. Filtering an orphan out of its own
            // editor would leave it editable from nowhere.
            val repository =
                FakeCategoryRepository(listOf(category("category:2", "Cabs", parentId = "category:gone")))
            val viewModel = CategoriesViewModel(repository)

            assertEquals(listOf("Cabs"), viewModel.uiState.value.grouped.map { it.parent.name })
        }

    @Test
    fun `a category is never offered as its own parent`() =
        runTest {
            val repository =
                FakeCategoryRepository(
                    listOf(category("category:1", "Transport"), category("category:2", "Rent")),
                )
            val viewModel = CategoriesViewModel(repository)

            val options = viewModel.uiState.value.parentOptions(excludingId = "category:1")
            assertEquals(listOf("Rent"), options.map { it.name })
        }

    // --- the editor sheet -------------------------------------------------------------------------

    @Test
    fun `adding opens an empty sheet and saving creates a category`() =
        runTest {
            val repository = FakeCategoryRepository()
            val viewModel = CategoriesViewModel(repository)

            viewModel.onEvent(CategoriesEvent.AddClicked)
            assertEquals(CategoryEditorState(), viewModel.uiState.value.editing)

            viewModel.onEvent(CategoriesEvent.NameChanged("Chai"))
            viewModel.onEvent(CategoriesEvent.NatureChanged(CategoryNature.WANT))
            viewModel.onEvent(CategoriesEvent.Save)

            assertNull("the sheet should close on success", viewModel.uiState.value.editing)
            assertEquals(listOf("Chai"), viewModel.uiState.value.categories.map { it.name })
        }

    @Test
    fun `editing opens the sheet on the row's current values`() =
        runTest {
            val repository =
                FakeCategoryRepository(
                    listOf(category("category:1", "Dining", nature = CategoryNature.WANT)),
                )
            val viewModel = CategoriesViewModel(repository)

            viewModel.onEvent(CategoriesEvent.EditClicked("category:1"))

            val editing = viewModel.uiState.value.editing
            assertEquals("Dining", editing?.name)
            assertEquals(CategoryNature.WANT, editing?.nature)
            assertTrue("the sheet should be in edit mode", editing?.isEditing == true)
        }

    @Test
    fun `editing an id that is not on screen does nothing`() =
        runTest {
            val viewModel = CategoriesViewModel(FakeCategoryRepository())

            viewModel.onEvent(CategoriesEvent.EditClicked("category:ghost"))

            assertNull(viewModel.uiState.value.editing)
        }

    @Test
    fun `a blank name cannot be saved`() =
        runTest {
            val viewModel = CategoriesViewModel(FakeCategoryRepository())

            viewModel.onEvent(CategoriesEvent.AddClicked)
            viewModel.onEvent(CategoriesEvent.NameChanged("   "))

            assertFalse(viewModel.uiState.value.editing!!.canSave)
            viewModel.onEvent(CategoriesEvent.Save)
            assertEquals(0, viewModel.uiState.value.categories.size)
        }

    @Test
    fun `a rejected save keeps the sheet open with what the user typed`() =
        runTest {
            val repository = FakeCategoryRepository()
            repository.nextError = AppError.Validation("name")
            val viewModel = CategoriesViewModel(repository)

            viewModel.onEvent(CategoriesEvent.AddClicked)
            viewModel.onEvent(CategoriesEvent.NameChanged("Fuel"))
            viewModel.onEvent(CategoriesEvent.Save)

            val state = viewModel.uiState.value
            assertEquals("Fuel", state.editing?.name)
            assertFalse("saving should have finished", state.editing!!.isSaving)
            assertEquals(CategoryLabels.VALIDATION_NAME, state.errorCode)
        }

    @Test
    fun `the three validation refusals stay distinguishable`() =
        runTest {
            // They all arrive as AppError.Validation with the same `code`; only the field tells them
            // apart, and the fixes are different — rename it, pick another parent, delete the
            // children first. One shared message would be wrong for two of the three.
            val repository = FakeCategoryRepository(listOf(category("category:1", "Transport")))
            val viewModel = CategoriesViewModel(repository)

            repository.nextError = AppError.Validation("parentId")
            viewModel.onEvent(CategoriesEvent.AddClicked)
            viewModel.onEvent(CategoriesEvent.NameChanged("Cabs"))
            viewModel.onEvent(CategoriesEvent.Save)
            assertEquals(CategoryLabels.VALIDATION_PARENT, viewModel.uiState.value.errorCode)

            repository.nextError = AppError.Validation("children")
            viewModel.onEvent(CategoriesEvent.DeleteClicked("category:1"))
            viewModel.onEvent(CategoriesEvent.ConfirmDelete)
            assertEquals(CategoryLabels.VALIDATION_CHILDREN, viewModel.uiState.value.errorCode)
        }

    @Test
    fun `cancelling the sheet discards the edit`() =
        runTest {
            val viewModel = CategoriesViewModel(FakeCategoryRepository())

            viewModel.onEvent(CategoriesEvent.AddClicked)
            viewModel.onEvent(CategoriesEvent.NameChanged("Chai"))
            viewModel.onEvent(CategoriesEvent.CancelEdit)

            assertNull(viewModel.uiState.value.editing)
            assertEquals(0, viewModel.uiState.value.categories.size)
        }

    // --- delete -----------------------------------------------------------------------------------

    @Test
    fun `deleting asks first and states how many transactions are affected`() =
        runTest {
            val repository = FakeCategoryRepository(listOf(category("category:1", "Chai")))
            repository.usageCount = 7
            val viewModel = CategoriesViewModel(repository)

            viewModel.onEvent(CategoriesEvent.DeleteClicked("category:1"))

            // The tap opens the dialog — it must not delete anything on its own.
            assertEquals(7, viewModel.uiState.value.confirmingDelete?.usageCount)
            assertTrue("nothing should be deleted yet", repository.deleted.isEmpty())

            viewModel.onEvent(CategoriesEvent.ConfirmDelete)
            assertEquals(listOf("category:1"), repository.deleted)
            assertNull(viewModel.uiState.value.confirmingDelete)
        }

    @Test
    fun `a count that cannot be read stays null rather than claiming zero`() =
        runTest {
            val repository = FakeCategoryRepository(listOf(category("category:1", "Chai")))
            repository.usageCount = null
            val viewModel = CategoriesViewModel(repository)

            viewModel.onEvent(CategoriesEvent.DeleteClicked("category:1"))

            // Telling the user nothing uses this category when the app does not know would be the
            // one wrong thing to say in a confirmation dialog (P-02).
            assertNull(viewModel.uiState.value.confirmingDelete?.usageCount)
        }

    @Test
    fun `backing out of the confirmation deletes nothing`() =
        runTest {
            val repository = FakeCategoryRepository(listOf(category("category:1", "Chai")))
            val viewModel = CategoriesViewModel(repository)

            viewModel.onEvent(CategoriesEvent.DeleteClicked("category:1"))
            viewModel.onEvent(CategoriesEvent.CancelDelete)

            assertNull(viewModel.uiState.value.confirmingDelete)
            assertTrue(repository.deleted.isEmpty())
            assertEquals(1, viewModel.uiState.value.categories.size)
        }

    @Test
    fun `deleting an id that is not on screen does nothing`() =
        runTest {
            val viewModel = CategoriesViewModel(FakeCategoryRepository())

            viewModel.onEvent(CategoriesEvent.DeleteClicked("category:ghost"))

            assertNull(viewModel.uiState.value.confirmingDelete)
        }

    // --- errors -----------------------------------------------------------------------------------

    @Test
    fun `the error banner can be dismissed`() =
        runTest {
            val repository = FakeCategoryRepository()
            repository.nextError = AppError.Storage("disk full")
            val viewModel = CategoriesViewModel(repository)

            viewModel.onEvent(CategoriesEvent.AddClicked)
            viewModel.onEvent(CategoriesEvent.NameChanged("Chai"))
            viewModel.onEvent(CategoriesEvent.Save)
            assertEquals(AppError.Storage("disk full").code, viewModel.uiState.value.errorCode)

            viewModel.onEvent(CategoriesEvent.DismissError)
            assertNull(viewModel.uiState.value.errorCode)
        }

    /**
     * Builds a category with everything but the field under test defaulted.
     * Result: a [Category]. Input: [id]; [name]; [nature]; [parentId]. Output: [Category].
     * Changelog: 2026-08-08 — Created for issue 4.1.
     */
    private fun category(
        id: String,
        name: String,
        nature: CategoryNature = CategoryNature.NEED,
        parentId: String? = null,
    ): Category = Category(id = id, name = name, nature = nature, parentId = parentId)
}
