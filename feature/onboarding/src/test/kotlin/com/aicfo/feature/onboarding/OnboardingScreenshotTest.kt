package com.aicfo.feature.onboarding

import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import app.cash.paparazzi.DeviceConfig
import app.cash.paparazzi.Paparazzi
import com.aicfo.core.designsystem.theme.CfoTheme
import org.junit.Rule
import org.junit.Test

/**
 * Screenshot tests for onboarding — light, dark and 200% font (issue 12.3; §21.5).
 *
 * Why:  onboarding is one of the three critical flows `CLAUDE.md` §4 names, and it is the one with
 *       the least room for error: it is the **first thing every user sees**, it is the only place the
 *       privacy pledge is read, and a user who cannot get past a step has no app at all. There is no
 *       emulator here, so a render is the only way anyone sees what it looks like.
 *
 *       The 200% case matters more here than anywhere: `OnboardingContent` is deliberately scrollable
 *       because at that font scale a step is taller than a phone, and a step whose Next button cannot
 *       be reached is a dead end. That comment has been in the code since issue 2.1 with nothing
 *       rendering it.
 * What: the welcome step (the pledge), the quick-setup step (the densest), in the configurations
 *       §21.5 asks for.
 * Result: a visual diff fails the build when onboarding's layout or theming changes unintentionally.
 * Changelog: 2026-10-02 — Created for issue 12.3.
 *
 * Record with `./gradlew :feature:onboarding:recordPaparazziDebug`; verify with
 * `verifyPaparazziDebug`. Baselines are committed — a screenshot test with no committed baseline
 * checks nothing. See `docs/testing/screenshot-tests.md`.
 */
class OnboardingScreenshotTest {
    @get:Rule
    val paparazzi: Paparazzi =
        Paparazzi(
            deviceConfig = DeviceConfig.PIXEL_5,
            // Pinned: the renderer's platform version decides the pixels, so leaving it floating
            // would make every baseline change under an unrelated SDK update.
            theme = "android:Theme.Material.Light.NoActionBar",
        )

    /**
     * Input:  step 1, the welcome and the privacy pledge.
     * Output: the first screen of the app, and the only place P-01's promise is stated to a user.
     */
    @Test
    fun welcome_light() {
        paparazzi.snapshot { Screen(OnboardingUiState(step = OnboardingStep.WELCOME), darkTheme = false) }
    }

    /** Input: the same step in dark mode. Output: proves the pledge is readable when themed dark. */
    @Test
    fun welcome_dark() {
        paparazzi.snapshot { Screen(OnboardingUiState(step = OnboardingStep.WELCOME), darkTheme = true) }
    }

    /**
     * Input:  the welcome step at a 2x font scale.
     * Output: the case the DoD asks for, on the screen where failing it costs the whole app — a user
     *         who needs large text and cannot reach "Continue" never gets in.
     */
    @Test
    fun welcome_largeFont() {
        paparazzi.unsafeUpdateConfig(deviceConfig = DeviceConfig.PIXEL_5.copy(fontScale = LARGE_FONT_SCALE))
        paparazzi.snapshot { Screen(OnboardingUiState(step = OnboardingStep.WELCOME), darkTheme = false) }
    }

    /**
     * Input:  the quick-setup step, the densest in the flow — three money fields and their labels.
     * Output: the step most likely to overflow, rendered.
     */
    @Test
    fun quickSetup_light() {
        paparazzi.snapshot { Screen(OnboardingUiState(step = OnboardingStep.QUICK_SETUP), darkTheme = false) }
    }

    /**
     * Input:  the quick-setup step at 200%.
     * Output: the exact case `OnboardingContent`'s own comment describes — "at a 200% font setting the
     *         quick-setup step is taller than a phone". Until this test, nothing checked it.
     */
    @Test
    fun quickSetup_largeFont() {
        paparazzi.unsafeUpdateConfig(deviceConfig = DeviceConfig.PIXEL_5.copy(fontScale = LARGE_FONT_SCALE))
        paparazzi.snapshot { Screen(OnboardingUiState(step = OnboardingStep.QUICK_SETUP), darkTheme = false) }
    }

    /**
     * The screen under one theme.
     * Why:    no wrapping Column and no padding — `OnboardingContent` already applies
     *         `fillMaxSize().verticalScroll(...).padding(CfoDimens.spaceMd)`. Wrapping it would render
     *         every baseline at double the real padding: a picture of a screen the app never draws.
     * Result: the composition. Input: [uiState]; [darkTheme]. Output: none.
     * Changelog: 2026-10-02 — Created for issue 12.3.
     */
    @Composable
    private fun Screen(
        uiState: OnboardingUiState,
        darkTheme: Boolean,
    ) {
        CfoTheme(darkTheme = darkTheme) {
            Surface {
                OnboardingContent(uiState = uiState, onEvent = {})
            }
        }
    }

    private companion object {
        /** 200%, which is what §21.5 and the Definition of Done mean by the large-font case. */
        const val LARGE_FONT_SCALE = 2.0f
    }
}
