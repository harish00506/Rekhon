package com.aicfo.app

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import com.aicfo.core.designsystem.theme.CfoTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The first tap of FR-TXN-002's budget (issue 3.1; §21.5).
 *
 * Why:  FR-TXN-002 says add-transaction is "reachable in one tap (FAB)". That half of the budget
 *       cannot be asserted in `:feature:transactions` — the FAB lives here, above the nav graph,
 *       precisely because a feature module may not see another (ARC-001). So the count is split:
 *
 *       | Where | Taps | Asserted by |
 *       |---|---|---|
 *       | FAB → the capture screen | 1 | **this file** |
 *       | amount typed, then Save | 1 | `AddTransactionFlowTest` |
 *       | a category chip, when there is one | +1 | `AddTransactionFlowTest` |
 *
 *       **2 for a common expense, 3 with a category — FR-TXN-002 allows 3.** Neither file is the
 *       whole assertion, and both say so.
 * What: that one click on the FAB fires exactly one navigation, and that the icon-only button is
 *       announced to a screen reader.
 * Result: a change that buries add-transaction behind a menu fails a test rather than a review.
 * Changelog: 2026-08-02 — Created for issue 3.1.
 *
 * Renders [CfoAddTransactionFab] rather than the whole app: `AppContent` needs Hilt, an unlocked
 * session and a database, none of which this claim depends on. Which destinations the FAB is hidden
 * on is a property of `showsAddTransactionFab`, exercised on the emulator run (tracker phase 8).
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w400dp-h1200dp")
class AddTransactionFabTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    /** Result: a string from this module's resources. Input: [id]. Output: the resolved text. */
    private fun text(id: Int): String = compose.activity.getString(id)

    @Test
    fun `one tap on the FAB opens capture — the first of FR-TXN-002's three`() {
        var taps = 0
        var opened = 0
        compose.setContent { CfoTheme { CfoAddTransactionFab(onClick = { opened++ }) } }

        compose.onNodeWithContentDescription(text(R.string.add_transaction)).performClick().also { taps++ }

        assertEquals("reaching capture must cost exactly one tap", 1, taps)
        assertEquals(1, opened)
    }

    @Test
    fun `the icon-only button announces itself`() {
        // §21.6's accessibility line: without a content description this reads as "button" and the
        // app's most-used control is unusable with TalkBack.
        compose.setContent { CfoTheme { CfoAddTransactionFab(onClick = {}) } }

        compose.onNodeWithContentDescription(text(R.string.add_transaction)).assertIsDisplayed()
    }
}
