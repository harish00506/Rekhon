package com.aicfo.feature.transactions

import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import app.cash.paparazzi.DeviceConfig
import app.cash.paparazzi.Paparazzi
import com.aicfo.core.designsystem.theme.CfoTheme
import com.aicfo.core.model.Category
import com.aicfo.core.model.CategoryNature
import org.junit.Rule
import org.junit.Test

/**
 * Screenshot tests for the add-transaction screen — light, dark and 200% font (issue 12.3; §21.5).
 *
 * Why:  FR-TXN-002 budgets this screen at **three taps**, which makes it the one screen in the app
 *       whose layout *is* a requirement: every control that moves below the fold costs a tap, and a
 *       tap budget is not something a unit test can see. It is also one of `CLAUDE.md` §4's three
 *       critical flows, and until this issue the dashboard was the only screen with any screenshot
 *       coverage at all.
 *
 *       The 200% case is the one that bites here. The amount field, the direction chips, the account
 *       and category pickers and the save button all have to stay reachable for a user who needs
 *       large text — and there is no emulator in this project, so a render is the only way anyone
 *       ever sees whether they do.
 * What: the empty screen a user meets, and one with an amount and a category chosen.
 * Result: a visual diff fails the build when the screen's layout or theming changes unintentionally.
 * Changelog: 2026-10-02 — Created for issue 12.3.
 *
 * Record with `./gradlew :feature:transactions:recordPaparazziDebug`; verify with
 * `verifyPaparazziDebug`. Baselines are committed — a screenshot test with no committed baseline
 * checks nothing. See `docs/testing/screenshot-tests.md`.
 */
class AddTransactionScreenshotTest {
    @get:Rule
    val paparazzi: Paparazzi =
        Paparazzi(
            deviceConfig = DeviceConfig.PIXEL_5,
            // Pinned: the renderer's platform version decides the pixels, so leaving it floating
            // would make every baseline change under an unrelated SDK update.
            theme = "android:Theme.Material.Light.NoActionBar",
        )

    /**
     * Input:  the screen as the FAB opens it — nothing typed.
     * Output: the three-tap starting point. This is what a user actually meets, so it is the render
     *         that decides whether the budget is met.
     */
    @Test
    fun empty_light() {
        paparazzi.snapshot { Screen(emptyState(), darkTheme = false) }
    }

    /** Input: the same, dark. Output: proves the screen is genuinely themed, not inverted. */
    @Test
    fun empty_dark() {
        paparazzi.snapshot { Screen(emptyState(), darkTheme = true) }
    }

    /**
     * Input:  the empty screen at a 2x font scale.
     * Output: the accessibility case, on the screen where it costs the most — a control pushed below
     *         the fold at 200% font is an extra tap for exactly the user least able to spare it.
     */
    @Test
    fun empty_largeFont() {
        paparazzi.unsafeUpdateConfig(deviceConfig = DeviceConfig.PIXEL_5.copy(fontScale = LARGE_FONT_SCALE))
        paparazzi.snapshot { Screen(emptyState(), darkTheme = false) }
    }

    /**
     * Input:  an amount typed and a category chosen — the state one tap from saving.
     * Output: the figures rendered with Indian digit grouping (MNY-001's formatter), and the save
     *         action enabled.
     */
    @Test
    fun filled_light() {
        paparazzi.snapshot { Screen(filledState(), darkTheme = false) }
    }

    /** Input: the filled state at 200%. Output: the densest render this screen produces. */
    @Test
    fun filled_largeFont() {
        paparazzi.unsafeUpdateConfig(deviceConfig = DeviceConfig.PIXEL_5.copy(fontScale = LARGE_FONT_SCALE))
        paparazzi.snapshot { Screen(filledState(), darkTheme = false) }
    }

    /** Result: the screen as the FAB opens it, with one account to choose. Output: the state. */
    private fun emptyState() =
        AddTransactionUiState(
            accounts = listOf(account()),
            selectedAccountId = "account:1",
            categories = CATEGORIES,
        )

    /** Result: an amount typed and a category chosen — one tap from saved. Output: the state. */
    private fun filledState() = emptyState().copy(amountText = "1250", selectedCategoryId = "category:fuel")

    /**
     * The screen under one theme.
     * Why:    no wrapping Column and no padding — `AddTransactionContent` already applies its own,
     *         including the `imePadding` that its comment calls load-bearing. Wrapping it would
     *         render a screen the app never draws.
     * Result: the composition. Input: [uiState]; [darkTheme]. Output: none.
     * Changelog: 2026-10-02 — Created for issue 12.3.
     */
    @Composable
    private fun Screen(
        uiState: AddTransactionUiState,
        darkTheme: Boolean,
    ) {
        CfoTheme(darkTheme = darkTheme) {
            Surface {
                AddTransactionContent(uiState = uiState, onEvent = {}, onCancel = {})
            }
        }
    }

    private companion object {
        /** 200%, which is what §21.5 and the Definition of Done mean by the large-font case. */
        const val LARGE_FONT_SCALE = 2.0f

        /** A couple of real categories, so the picker renders as a user would see it. */
        val CATEGORIES =
            listOf(
                Category("category:fuel", "Fuel", CategoryNature.NEED),
                Category("category:dining", "Dining out", CategoryNature.WANT),
            )
    }
}
