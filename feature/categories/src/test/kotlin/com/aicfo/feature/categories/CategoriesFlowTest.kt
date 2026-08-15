package com.aicfo.feature.categories

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import com.aicfo.core.common.AppError
import com.aicfo.core.designsystem.theme.CfoTheme
import com.aicfo.core.model.Category
import com.aicfo.core.model.CategoryNature
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Compose tests for the categories editor (issue 4.1; §21.5's "critical flows", FR-SET-001).
 *
 * Why:  the ViewModel tests prove what the state does; these prove the screen actually renders it
 *       and routes the taps back. Three things only a rendered test catches: **a seeded category
 *       reaching the screen with its nature**, which a state test cannot distinguish from a blank
 *       label; **the delete dialog stating its count in words the user reads**, which is the whole
 *       point of asking first; and **every one of §8.3's five natures being offered**, which is the
 *       taxonomy's vocabulary expressed as pixels rather than as an enum.
 * What: the list, the empty state, the error banner, the add/edit sheet, and the delete dialog.
 * Result: the screen is exercised on every `test` run, not only when a device is attached.
 * Changelog: 2026-08-08 — Created for issue 4.1.
 *
 * On the JVM via Robolectric, following `:feature:accounts`'s `AccountsFlowTest`.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w400dp-h1200dp")
class CategoriesFlowTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    /** Result: a string from this module's resources. Input: [id]. Output: the resolved text. */
    private fun text(id: Int): String = compose.activity.getString(id)

    // --- the list --------------------------------------------------------------------------------

    @Test
    fun `a category renders with its nature, and a seeded one says it is a default`() {
        setContent(
            CategoriesUiState(
                isLoading = false,
                categories =
                    listOf(
                        Category("category:1", "Dining", CategoryNature.WANT, isSystem = true),
                        Category("category:2", "Chai", CategoryNature.WANT),
                    ),
            ),
        )

        compose.onNodeWithText("Dining").assertIsDisplayed()
        // The nature is text, not a colour: a distinction carried only by hue is unreadable in
        // greyscale and to a colour-blind user.
        val want = text(R.string.category_nature_want)
        compose.onNodeWithText("$want · ${text(R.string.categories_default_label)}").assertIsDisplayed()
        compose.onNodeWithText(want, substring = false).assertIsDisplayed()
    }

    @Test
    fun `a child renders under its parent`() {
        setContent(
            CategoriesUiState(
                isLoading = false,
                categories =
                    listOf(
                        Category("category:1", "Transport", CategoryNature.NEED),
                        Category("category:2", "Cabs", CategoryNature.NEED, parentId = "category:1"),
                    ),
            ),
        )

        compose.onNodeWithText("Transport").assertIsDisplayed()
        compose.onNodeWithText("Cabs").assertIsDisplayed()
    }

    @Test
    fun `a profile with no categories sees an invitation, not a blank screen`() {
        setContent(CategoriesUiState(isLoading = false))

        compose.onNodeWithText(text(R.string.categories_empty_title)).assertIsDisplayed()
    }

    @Test
    fun `a failed read shows the error, not the invitation`() {
        setContent(CategoriesUiState(isLoading = false, errorCode = AppError.Storage("x").code))

        compose.onNodeWithText(text(R.string.categories_error_storage)).assertIsDisplayed()
        assertEquals(
            "the empty-state invitation must not render over a failure",
            0,
            compose.onAllNodesWithText(text(R.string.categories_empty_title)).fetchSemanticsNodes().size,
        )
    }

    // --- the sheet -------------------------------------------------------------------------------

    @Test
    fun `adding a category is name, nature, save`() {
        val events = mutableListOf<CategoriesEvent>()
        setContent(
            CategoriesUiState(isLoading = false, editing = CategoryEditorState(name = "Chai")),
            onEvent = { events += it },
        )

        compose.onNodeWithText(text(R.string.category_nature_want)).performScrollTo().performClick()
        compose.onNodeWithText(text(R.string.category_editor_save)).performScrollTo().performClick()

        assertTrue(CategoriesEvent.NatureChanged(CategoryNature.WANT) in events)
        assertTrue(CategoriesEvent.Save in events)
    }

    @Test
    fun `every one of the five natures is offered`() {
        setContent(CategoriesUiState(isLoading = false, editing = CategoryEditorState()))

        // §8.3 names five and the whole advice layer branches on them; a picker offering four would
        // make one nature unreachable while every state test still passed.
        CategoryNature.entries.forEach { nature ->
            compose.onNodeWithText(text(CategoryLabels.natureLabel(nature))).performScrollTo().assertIsDisplayed()
        }
    }

    @Test
    fun `typing a name reaches the ViewModel`() {
        val events = mutableListOf<CategoriesEvent>()
        setContent(
            CategoriesUiState(isLoading = false, editing = CategoryEditorState()),
            onEvent = { events += it },
        )

        compose.onNodeWithText(text(R.string.category_editor_name)).performScrollTo().performTextInput("Chai")

        assertTrue(events.filterIsInstance<CategoriesEvent.NameChanged>().isNotEmpty())
    }

    @Test
    fun `save is disabled while the category has no name`() {
        setContent(CategoriesUiState(isLoading = false, editing = CategoryEditorState(name = "")))

        compose.onNodeWithText(text(R.string.category_editor_save)).assertIsNotEnabled()
    }

    @Test
    fun `save is enabled once the category has a name`() {
        // A second test rather than a second `setContent`: the Compose rule allows one composition
        // per test, and splitting them also makes which half failed obvious from the report.
        setContent(CategoriesUiState(isLoading = false, editing = CategoryEditorState(name = "Chai")))

        compose.onNodeWithText(text(R.string.category_editor_save)).assertIsEnabled()
    }

    @Test
    fun `the parent picker is hidden when there is nothing to nest under`() {
        // An empty label above an empty row reads as a broken control, so the whole section goes.
        setContent(CategoriesUiState(isLoading = false, editing = CategoryEditorState()))

        assertEquals(
            0,
            compose.onAllNodesWithText(text(R.string.category_editor_parent)).fetchSemanticsNodes().size,
        )
    }

    // --- the delete confirmation ------------------------------------------------------------------

    @Test
    fun `the delete dialog names the category and counts what uses it`() {
        setContent(
            CategoriesUiState(
                isLoading = false,
                categories = listOf(Category("category:1", "Chai", CategoryNature.WANT)),
                confirmingDelete = DeleteConfirmation(id = "category:1", name = "Chai", usageCount = 7),
            ),
        )

        compose.onNodeWithText(compose.activity.getString(R.string.categories_delete_title, "Chai"))
            .assertIsDisplayed()
        compose.onNodeWithText("7", substring = true).assertIsDisplayed()
    }

    @Test
    fun `a count that is not known says so rather than showing zero`() {
        setContent(
            CategoriesUiState(
                isLoading = false,
                categories = listOf(Category("category:1", "Chai", CategoryNature.WANT)),
                confirmingDelete = DeleteConfirmation(id = "category:1", name = "Chai", usageCount = null),
            ),
        )

        compose.onNodeWithText(text(R.string.categories_delete_counting)).assertIsDisplayed()
    }

    @Test
    fun `confirming the delete sends the event`() {
        val events = mutableListOf<CategoriesEvent>()
        setContent(
            CategoriesUiState(
                isLoading = false,
                categories = listOf(Category("category:1", "Chai", CategoryNature.WANT)),
                confirmingDelete = DeleteConfirmation(id = "category:1", name = "Chai", usageCount = 0),
            ),
            onEvent = { events += it },
        )

        compose.onNodeWithText(text(R.string.categories_delete_confirm)).performClick()

        assertTrue(CategoriesEvent.ConfirmDelete in events)
    }

    @Test
    fun `the confirmation's button is not labelled the same as the row's`() {
        // The row that opened the dialog keeps its own Delete button on screen. Two buttons reading
        // "Delete" are ambiguous to look at and worse to hear announced — this test is what noticed,
        // by failing on "found 2 nodes" when both said the same thing.
        assertEquals(
            "the confirm and the row action must not share a label",
            false,
            text(R.string.categories_delete_confirm) == text(R.string.categories_delete),
        )
    }

    /**
     * Renders the screen's body in the app theme.
     * Why:    every test here differs only in the state it renders, and repeating the theme wrapper
     *         at each one buries that difference.
     * Result: the composition is set. Input: [uiState]; [onEvent]. Output: none.
     * Changelog: 2026-08-08 — Created for issue 4.1.
     */
    private fun setContent(
        uiState: CategoriesUiState,
        onEvent: (CategoriesEvent) -> Unit = {},
    ) {
        compose.setContent {
            CfoTheme {
                CategoriesContent(uiState = uiState, onEvent = onEvent)
            }
        }
    }
}
