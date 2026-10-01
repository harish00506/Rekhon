package com.aicfo.feature.settings

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.AndroidComposeTestRule
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.aicfo.core.designsystem.theme.CfoTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * What the erase screen puts in front of a person (issue 11.4; §23, §34, SEC-003, P-01).
 *
 * Why:  the view model's test proves the gates; only a rendered test proves what the user **reads**
 *       before they pass them — and on this screen the reading is most of the feature. Three
 *       sentences have to be there: what will be deleted, that it cannot be undone, and the one
 *       thing the erase **cannot reach**. That last one is the honest sentence: a backup the user
 *       exported was sealed with their own passphrase and this app never had its key, so "your data
 *       is gone" would be a lie about the copy most likely to still exist.
 *
 *       The other thing only a rendered test can catch is the button being enabled before the gates
 *       are satisfied — a state that says `canErase == false` over a button wired to something else
 *       is a screen that erases on the first tap.
 * What: the three sentences, the disabled button, the PIN field's presence, and the finished state.
 * Result: an irreversible action nobody reaches by accident or by misreading.
 * Changelog: 2026-10-01 — Created for issue 11.4.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w400dp-h2400dp")
class EraseScreenTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val events = mutableListOf<EraseEvent>()
    private var erasedCalls = 0

    @Test
    fun `the screen says what goes, that it cannot be undone, and what it cannot reach`() {
        setContent(state())

        compose.onNodeWithText(text(R.string.erase_what_goes)).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(text(R.string.erase_irreversible)).performScrollTo().assertIsDisplayed()
        // The honest sentence. Issue 8.1's backup is Argon2id over the user's own passphrase, so
        // destroying this device's Keystore does nothing to a file they exported.
        compose.onNodeWithText(text(R.string.erase_backups_not_reached)).performScrollTo().assertIsDisplayed()
    }

    @Test
    fun `the button is dead until the gates are satisfied`() {
        setContent(state(confirmationText = "", isPinRequired = true))

        compose.onNodeWithText(text(R.string.erase_action)).performScrollTo().assertIsNotEnabled()
    }

    @Test
    fun `the button comes alive only when the word and the PIN are both there`() {
        setContent(state(confirmationText = WORD, pinText = "1234", isPinRequired = true))

        compose.onNodeWithText(text(R.string.erase_action)).performScrollTo().assertIsEnabled()
    }

    @Test
    fun `the word alone leaves the button dead on a device with a PIN`() {
        setContent(state(confirmationText = WORD, pinText = "", isPinRequired = true))

        compose.onNodeWithText(text(R.string.erase_action)).performScrollTo().assertIsNotEnabled()
    }

    @Test
    fun `the PIN field is absent when there is no PIN to ask for`() {
        // Absent rather than disabled: an empty field a user cannot satisfy reads as a broken
        // screen, and SEC-002 makes the app lock optional.
        setContent(state(isPinRequired = false))

        compose.onAllNodesWithTextCount(text(R.string.erase_pin_label)).let { count ->
            assertEquals("no PIN is set, so nothing should ask for one", 0, count)
        }
    }

    @Test
    fun `the PIN field is there when a PIN exists`() {
        setContent(state(isPinRequired = true))

        compose.onNodeWithText(text(R.string.erase_pin_label)).performScrollTo().assertIsDisplayed()
    }

    @Test
    fun `tapping the button asks for the erase`() {
        setContent(state(confirmationText = WORD, isPinRequired = false))

        compose.onNodeWithText(text(R.string.erase_action)).performScrollTo().performClick()

        assertEquals(listOf(EraseEvent.Confirmed), events)
    }

    @Test
    fun `the finished screen offers only to close the app`() {
        // Nothing to go back to: every handle in this process points at a destroyed key.
        setContent(state(isErased = true))

        compose.onNodeWithText(text(R.string.erase_done)).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(text(R.string.erase_close_app)).performScrollTo().performClick()
        assertEquals(1, erasedCalls)
        assertEquals("the gates must be gone once the data is", 0, compose.onAllNodesWithTextCount(confirmationLabel()))
    }

    @Test
    fun `a failed erase says the data is still here`() {
        // The one message that must never be reassuring. The user was told this was irreversible;
        // if it did not happen they have to know their data is still on the phone.
        setContent(state(errorCode = "crypto"))

        compose.onNodeWithText(text(R.string.erase_error_failed)).performScrollTo().assertIsDisplayed()
    }

    @Test
    fun `a wrong PIN says so, and not that the erase failed`() {
        setContent(state(errorCode = "erase.pin", isPinRequired = true))

        compose.onNodeWithText(text(R.string.erase_error_pin)).performScrollTo().assertIsDisplayed()
        assertEquals(0, compose.onAllNodesWithTextCount(text(R.string.erase_error_failed)))
    }

    @Test
    fun `the confirmation word is a translated string, never a hardcoded literal`() {
        // 10.8's rule, and the reason the gate compares against a value passed in: a Hindi user
        // must be able to confirm in Hindi. If this resource were ever dropped the gate would
        // compare against a blank and refuse every erase — which is safe, and still a bug.
        assertTrue(text(R.string.erase_confirmation_word).isNotBlank())
    }

    // --- helpers ----------------------------------------------------------------------------------

    private fun state(
        confirmationText: String = "",
        pinText: String = "",
        isPinRequired: Boolean = false,
        isErased: Boolean = false,
        errorCode: String? = null,
    ) = EraseUiState(
        confirmationText = confirmationText,
        confirmationWord = WORD,
        pinText = pinText,
        isPinRequired = isPinRequired,
        isErased = isErased,
        errorCode = errorCode,
    )

    private fun setContent(state: EraseUiState) {
        compose.setContent {
            CfoTheme {
                EraseContent(
                    uiState = state,
                    onEvent = { events += it },
                    onDone = {},
                    onErased = { erasedCalls++ },
                )
            }
        }
    }

    private fun text(
        id: Int,
        vararg args: Any,
    ): String = compose.activity.getString(id, *args)

    /**
     * Result: how many nodes carry [value] — zero being the assertion that matters here. Input:
     *         [value]. Output: [Int].
     * Why:    `assertDoesNotExist` throws on a *matcher* with no match rather than returning, and
     *         reads as an assertion about one node; counting says plainly "nothing on this screen
     *         says that".
     */
    private fun AndroidComposeTestRule<*, *>.onAllNodesWithTextCount(value: String): Int =
        onAllNodesWithText(value).fetchSemanticsNodes().size

    /** Result: the label of the confirmation field, for asserting it is gone. */
    private fun confirmationLabel(): String = text(R.string.erase_confirmation_label)

    private companion object {
        /** The word the state under test carries; the real one comes from resources. */
        const val WORD = "ERASE"
    }
}
