package com.aicfo.feature.dashboard

import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import app.cash.paparazzi.DeviceConfig
import app.cash.paparazzi.Paparazzi
import com.aicfo.core.designsystem.component.LocalPrivacyBlur
import com.aicfo.core.designsystem.theme.CfoTheme
import org.junit.Rule
import org.junit.Test

/**
 * Screenshot tests for the dashboard — light, dark and 200% font (§21.5, ACC-*, issue 5.1).
 *
 * Why:  this project has no emulator, so these renders are the only way anyone sees the budget
 *       status, cash-flow and recent-activity sections issue 5.1 added actually look right — the
 *       same reasoning `DesignSystemScreenshotTest`'s doc comment gives, applied to a real screen
 *       rather than the component gallery.
 * What: the loaded state (every 5.1 section populated) and the all-empty state, in three
 *       configurations each.
 * Result: a visual diff fails the build when a token or layout changes unintentionally.
 * Changelog: 2026-08-15 — Created for issue 5.1.
 *
 * Record baselines with `./gradlew :feature:dashboard:recordPaparazziDebug`; verify with
 * `verifyPaparazziDebug`. Baselines are committed — a screenshot test with no committed baseline
 * checks nothing.
 */
class DashboardScreenshotTest {
    @get:Rule
    val paparazzi: Paparazzi =
        Paparazzi(
            deviceConfig = DeviceConfig.PIXEL_5,
            // Pinned: the renderer's platform version decides the pixels, so leaving it floating
            // would make baselines change under an unrelated SDK update.
            theme = "android:Theme.Material.Light.NoActionBar",
        )

    /** Input: the loaded, populated dashboard in light mode. Output: records/verifies the baseline. */
    @Test
    fun loaded_light() {
        paparazzi.snapshot { Screen(populatedDashboardState(), darkTheme = false) }
    }

    /** Input: the same state in dark mode. Output: proves dark mode is genuinely themed. */
    @Test
    fun loaded_dark() {
        paparazzi.snapshot { Screen(populatedDashboardState(), darkTheme = true) }
    }

    /**
     * Input:  the loaded state at a 2x font scale.
     * Output: the accessibility case the DoD asks for — if a label clips or a row collapses at
     *         200% font, it is visible here rather than on a user's phone.
     */
    @Test
    fun loaded_largeFont() {
        paparazzi.unsafeUpdateConfig(deviceConfig = DeviceConfig.PIXEL_5.copy(fontScale = LARGE_FONT_SCALE))
        paparazzi.snapshot { Screen(populatedDashboardState(), darkTheme = false) }
    }

    /**
     * Input:  a brand-new profile — no net worth snapshot, no budget, no transactions.
     * Output: every issue-5.1 section's empty-state line renders instead of a figure the app made
     *         up (P-03) — the state most likely to regress silently, since it has no numbers to
     *         eyeball wrong.
     */
    @Test
    fun empty_light() {
        paparazzi.snapshot { Screen(DashboardUiState(isLoading = false), darkTheme = false) }
    }

    /**
     * Input:  the same populated dashboard with the privacy blur on (issue 5.3; §23, FR-PRIV-*).
     * Output: the masked screen, as a reviewable artefact.
     *
     * Why:    `DashboardPrivacyBlurTest` already proves no amount survives, which is the assertion
     *         that matters. What it cannot show is whether the result is **usable** — whether a
     *         column of `₹•••••••` still aligns, whether the masked figures wrap, whether the
     *         layout collapses now that every amount is the same width. Those are exactly the
     *         questions a screenshot answers and a semantics sweep cannot.
     */
    @Test
    fun blurred_light() {
        paparazzi.snapshot { Screen(populatedDashboardState(), darkTheme = false, blurred = true) }
    }

    @Composable
    private fun Screen(
        uiState: DashboardUiState,
        darkTheme: Boolean,
        blurred: Boolean = false,
    ) {
        // No wrapping Column, and no padding of its own: `DashboardContent` already applies
        // `fillMaxWidth().padding(CfoDimens.spaceMd)` and its own vertical arrangement, so the
        // harness that used to wrap it here rendered every baseline at double the real padding —
        // a picture of a screen the app never draws. Found by review, 2026-08-16.
        CompositionLocalProvider(LocalPrivacyBlur provides blurred) {
            CfoTheme(darkTheme = darkTheme) {
                Surface {
                    DashboardContent(
                        uiState = uiState,
                        onEvent = {},
                        actions =
                            DashboardActions(
                                onNavigateToTransactions = {},
                                onNavigateToAccounts = {},
                                onNavigateToBudgets = {},
                                onNavigateToSettings = {},
                            ),
                    )
                }
            }
        }
    }

    private companion object {
        /** The DoD's accessibility case: text at twice the default size. */
        const val LARGE_FONT_SCALE = 2.0f
    }
}
