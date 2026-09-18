package com.aicfo.feature.settings

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.aicfo.core.datastore.ConsentFeature
import com.aicfo.core.designsystem.theme.CfoTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Compose tests for the settings screen's encrypted backup (issue 8.1; SEC-005, P-01).
 *
 * Why:  the ViewModel test proves the state; only a rendered test proves what the user **reads**.
 *       Three things here are invisible in the state: SEC-005's irrecoverability sentence actually on
 *       screen, a disabled button that says why beside it, and the acknowledgement being a real
 *       checkbox a tap reaches.
 * What: the consent hint, the enabled button and its event, the acknowledgement's event, and the
 *       written and failed lines.
 * Result: the backup section exercised on every `unitTests` run, without a device.
 * Changelog: 2026-09-18 — Created for issue 8.1.
 *
 * On the JVM via Robolectric, following `:feature:budgets`'s `BudgetsFlowTest`. Excluded from the
 * release variant by this module's build script, for the reason recorded there.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w400dp-h2400dp")
class SettingsScreenTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val events = mutableListOf<SettingsEvent>()

    @Test
    fun `the irrecoverability sentence is on screen`() {
        setContent(SettingsUiState(isLoading = false))

        compose.onNodeWithText(text(R.string.settings_backup_irrecoverable)).performScrollTo().assertIsDisplayed()
    }

    @Test
    fun `without the consent the button is disabled and says to turn the consent on`() {
        setContent(SettingsUiState(isLoading = false, backup = validForm()))

        compose.onNodeWithText(text(R.string.settings_backup_create)).performScrollTo().assertIsNotEnabled()
        compose.onNodeWithText(text(R.string.settings_backup_needs_consent)).performScrollTo().assertIsDisplayed()
    }

    @Test
    fun `with everything in place the button is enabled and asks for a backup`() {
        setContent(SettingsUiState(isLoading = false, consents = consented(), backup = validForm()))

        compose.onNodeWithText(text(R.string.settings_backup_create)).performScrollTo().assertIsEnabled().performClick()

        assertEquals(SettingsEvent.CreateBackup, events.last())
    }

    @Test
    fun `a short passphrase is named as the blocker`() {
        setContent(
            SettingsUiState(
                isLoading = false,
                consents = consented(),
                backup = BackupUiState(passphraseText = "short"),
            ),
        )

        compose.onNodeWithText(text(R.string.settings_backup_too_short)).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(text(R.string.settings_backup_create)).assertIsNotEnabled()
    }

    @Test
    fun `tapping the acknowledgement sentence ticks it`() {
        setContent(SettingsUiState(isLoading = false, consents = consented()))

        compose.onNodeWithText(text(R.string.settings_backup_irrecoverable)).performScrollTo().performClick()

        assertTrue(SettingsEvent.BackupAcknowledged(checked = true) in events)
    }

    @Test
    fun `a written backup says so and reminds the user the app keeps no passphrase`() {
        setContent(
            SettingsUiState(
                isLoading = false,
                consents = consented(),
                backup = BackupUiState(status = BackupStatus.Written),
            ),
        )

        compose.onNodeWithText(text(R.string.settings_backup_written)).performScrollTo().assertIsDisplayed()
    }

    @Test
    fun `a failed write says nothing was saved`() {
        setContent(
            SettingsUiState(
                isLoading = false,
                consents = consented(),
                backup = BackupUiState(status = BackupStatus.Failed("backup.writeFailed")),
            ),
        )

        compose.onNodeWithText(text(R.string.settings_backup_write_failed)).performScrollTo().assertIsDisplayed()
    }

    @Test
    fun `the backup consent carries its off-device label`() {
        setContent(SettingsUiState(isLoading = false, consents = consented()))

        compose.onNodeWithText(text(R.string.consent_cloud_backup)).performScrollTo().assertIsDisplayed()
    }

    // --- helpers ----------------------------------------------------------------------------------

    private fun setContent(state: SettingsUiState) {
        compose.setContent {
            CfoTheme { SettingsContent(uiState = state, onEvent = { events += it }, onDone = {}) }
        }
    }

    private fun text(id: Int): String = compose.activity.getString(id)

    private fun consented() = ConsentFeature.entries.associateWith { it == ConsentFeature.CLOUD_BACKUP }

    private fun validForm() =
        BackupUiState(passphraseText = PASSPHRASE, confirmationText = PASSPHRASE, acknowledged = true)

    private companion object {
        const val PASSPHRASE = "correct horse battery staple"
    }
}
