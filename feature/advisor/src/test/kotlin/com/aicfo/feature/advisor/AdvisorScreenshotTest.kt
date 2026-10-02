package com.aicfo.feature.advisor

import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import app.cash.paparazzi.DeviceConfig
import app.cash.paparazzi.Paparazzi
import com.aicfo.core.designsystem.theme.CfoTheme
import com.aicfo.domain.engines.purchase.Verdict
import org.junit.Rule
import org.junit.Test

/**
 * Screenshot tests for the Purchase Advisor — light, dark and 200% font (issue 12.3; §21.5).
 *
 * Why:  the advisor is one of the three critical flows `CLAUDE.md` §4 names, and until this issue the
 *       dashboard was the only screen with any screenshot coverage at all. That matters more here
 *       than on most screens: this one is **dense with numbers and verdicts**, every gate shows its
 *       own figures, and the whole point of P-02 is that a user can read the working. A layout that
 *       clips a gate's reasoning at 200% font, or a verdict colour that vanishes in dark mode, is a
 *       recommendation the user cannot check — and there is no emulator here, so a render is the only
 *       way anyone sees it.
 * What: the three verdicts a user actually meets, in the three configurations §21.5 asks for.
 * Result: a visual diff fails the build when the advisor's layout or theming changes unintentionally.
 * Changelog: 2026-10-02 — Created for issue 12.3.
 *
 * Record baselines with `./gradlew :feature:advisor:recordPaparazziDebug`; verify with
 * `verifyPaparazziDebug`. Baselines are committed — **a screenshot test with no committed baseline
 * checks nothing.** The workflow is written up in `docs/testing/screenshot-tests.md`.
 */
class AdvisorScreenshotTest {
    @get:Rule
    val paparazzi: Paparazzi =
        Paparazzi(
            deviceConfig = DeviceConfig.PIXEL_5,
            // Pinned for the reason the dashboard's test records: the renderer's platform version
            // decides the pixels, so leaving it floating would make baselines change under an
            // unrelated SDK update — a diff nobody caused and nobody can review.
            theme = "android:Theme.Material.Light.NoActionBar",
        )

    /** Input: a STRETCH verdict in light mode. Output: records/verifies the baseline. */
    @Test
    fun stretch_light() {
        paparazzi.snapshot { Screen(Verdict.STRETCH, darkTheme = false) }
    }

    /**
     * Input:  the same verdict in dark mode.
     * Output: proves dark mode is genuinely themed rather than a light render on a dark surface —
     *         which is what a verdict colour silently falling back to the default would look like.
     */
    @Test
    fun stretch_dark() {
        paparazzi.snapshot { Screen(Verdict.STRETCH, darkTheme = true) }
    }

    /**
     * Input:  a STRETCH verdict at a 2x font scale.
     * Output: the accessibility case §21.5 and the DoD ask for. This screen is the hardest in the app
     *         for large text — every gate row carries a label and a figure — so if anything clips at
     *         200% it clips here first.
     */
    @Test
    fun stretch_largeFont() {
        paparazzi.unsafeUpdateConfig(deviceConfig = DeviceConfig.PIXEL_5.copy(fontScale = LARGE_FONT_SCALE))
        paparazzi.snapshot { Screen(Verdict.STRETCH, darkTheme = false) }
    }

    /**
     * Input:  the verdict that says no.
     * Output: the render that matters most for P-07 — the app recommends and the user decides, so a
     *         refusal must read as advice with its reasons shown, not as a blocked action.
     */
    @Test
    fun notNow_light() {
        paparazzi.snapshot { Screen(Verdict.NOT_NOW, darkTheme = false) }
    }

    /** Input: the affordable case. Output: the happy path, where every gate passes. */
    @Test
    fun comfortable_light() {
        paparazzi.snapshot { Screen(Verdict.COMFORTABLE, darkTheme = false) }
    }

    /**
     * The screen under one theme.
     * Why:    no wrapping Column and no padding of its own — `AdvisorContent` already applies
     *         `fillMaxWidth().padding(CfoDimens.spaceMd)` and its own arrangement. The dashboard's
     *         test records what happens otherwise: every baseline renders at double the real padding,
     *         which is a picture of a screen the app never draws.
     * Result: the composition. Input: [verdict]; [darkTheme]. Output: none.
     * Changelog: 2026-10-02 — Created for issue 12.3.
     */
    @Composable
    private fun Screen(
        verdict: Verdict,
        darkTheme: Boolean,
    ) {
        CfoTheme(darkTheme = darkTheme) {
            Surface {
                AdvisorContent(
                    uiState = AdvisorUiState(card = FakePurchaseAdvisorRepository.card(verdict)),
                    onEvent = {},
                    onDone = {},
                )
            }
        }
    }

    private companion object {
        /** 200%, which is what §21.5 and the Definition of Done mean by the large-font case. */
        const val LARGE_FONT_SCALE = 2.0f
    }
}
