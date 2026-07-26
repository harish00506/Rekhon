package com.aicfo.feature.onboarding

import android.content.Context
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.unit.Density
import androidx.lifecycle.SavedStateHandle
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.aicfo.core.common.FakeClock
import com.aicfo.core.datastore.ConsentFeature
import com.aicfo.core.designsystem.theme.CfoTheme
import com.aicfo.core.model.Money
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.time.ZoneId

/**
 * Compose UI tests driving the whole onboarding flow (issue 2.1; §21.5, ACC-*).
 *
 * Why:  the acceptance criteria ask for a UI test that drives all four steps, and this project has
 *       had a **device-shaped hole** in its testing since the start — CI has never run and the
 *       emulator on the build machine is unstable. Robolectric renders on the JVM, so this runs on
 *       every `./gradlew test` rather than only when a device happens to be attached. The
 *       instrumented twin in `androidTest` still runs the same flow against real storage; this is
 *       the copy that will not be skipped.
 * What: the happy path end to end, the FR-ONB-003 consent wording and its default, dark mode, and
 *       a 200% font setting.
 * Result: the flow is proven navigable and legible without an emulator.
 * Changelog: 2026-07-25 — Created for issue 2.1.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
class OnboardingFlowTest {
    @get:Rule
    val compose = createComposeRule()

    private val settings = FakeSettingsStore()
    private val consents = FakeConsentStore()
    private val appLock = FakeAppLockStore()
    private val pinVerifier = FakePinVerifier()
    private val clock = FakeClock(initialZone = ZoneId.of("Asia/Kolkata"))
    private var finishedCount = 0

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    /** Input: none. Output: pins `viewModelScope` so the save completes within the test. */
    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    /** Input: none. Output: releases the main dispatcher. */
    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    /**
     * Renders the real screen over the real ViewModel.
     * Why:    driving [OnboardingContent] with a hand-held state would test the layout and not the
     *         wiring — the interesting failures are an event that reaches nothing and a step that
     *         never advances.
     * Result: the flow, on screen, ready to be clicked through.
     * Input:  [darkTheme]; [fontScale] — 2.0 for the accessibility case. Output: none.
     */
    private fun ComposeContentTestRule.showFlow(
        darkTheme: Boolean = false,
        fontScale: Float = 1f,
    ) {
        val viewModel = OnboardingViewModel(settings, consents, appLock, pinVerifier, clock, SavedStateHandle())
        setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(
                LocalDensity provides Density(density.density, fontScale),
            ) {
                CfoTheme(darkTheme = darkTheme) {
                    // The real screen, given the real ViewModel — so its collect and its
                    // navigate-on-success effect are under test too, not reimplemented here.
                    OnboardingScreen(onFinished = { finishedCount++ }, viewModel = viewModel)
                }
            }
        }
    }

    private fun text(id: Int): String = context.getString(id)

    /**
     * Finds a node by its visible text and brings it into view.
     * Why:    the flow scrolls, and the profile step is taller than a phone even at the default font
     *         size — its Next button starts below the fold. Scrolling first is what a user does, and
     *         doing it here is what makes the 200%-font test meaningful rather than a test of the
     *         top of the screen.
     * Result: the node, scrolled into view. Input: [label]. Output: the interaction.
     */
    private fun node(label: String) = compose.onNodeWithText(label).performScrollTo()

    private fun clickNext() = node(text(R.string.onboarding_next)).performClick()

    /**
     * Advances to the security step.
     * Why:    derived from the enum's ordinal rather than a literal number of taps — issue 2.2
     *         inserted a step here and ADR-0002 says issue 2.5 will insert another, so a hardcoded
     *         count would break both of these tests again.
     * Result: the flow sits on [OnboardingStep.SECURITY]. Input: none. Output: none.
     */
    private fun goToSecurityStep() = repeat(OnboardingStep.SECURITY.ordinal) { clickNext() }

    /**
     * Input:  the flow, clicked through all five steps with answers along the way.
     * Output: asserts each step renders, the profile is written exactly once with the amounts
     *         converted to paise, the consent is granted, and the caller is told to navigate on.
     * Changelog: 2026-07-26 — Issue 2.2 added the SECURITY step, passed through here without
     *            enabling the lock; `enables the app lock when the user sets a PIN` covers that.
     */
    @Test
    fun `drives all five steps and saves the profile`() {
        compose.showFlow()

        compose.onNodeWithText(text(R.string.onboarding_welcome_pledge_title)).assertIsDisplayed()
        clickNext()

        node(text(R.string.onboarding_consent_toggle)).performClick()
        clickNext()

        node(text(R.string.onboarding_profile_name_label)).performTextInput("Harish")
        node("Asia/Dubai").performClick()
        clickNext()

        // SECURITY (issue 2.2): left off, so the step must not block Next.
        compose.onNodeWithText(text(R.string.onboarding_security_optional)).assertIsDisplayed()
        clickNext()

        node(text(R.string.onboarding_quick_setup_income_label)).performTextInput("85000")
        node(text(R.string.onboarding_finish)).performClick()
        // The save is asynchronous and the navigation hangs off a LaunchedEffect on its result, so
        // the composition has to be pumped before either can be asserted.
        compose.waitForIdle()

        val saved = settings.savedProfile!!
        assertEquals(1, settings.completeCallCount)
        assertEquals("Harish", saved.displayName)
        assertEquals("Asia/Dubai", saved.timeZoneId)
        assertEquals(Money(85_000_00), saved.quickSetup.monthlyIncome)
        assertEquals(listOf(ConsentFeature.SMS_PARSING), consents.granted)
        assertEquals("navigates once the save succeeded, not on the tap", 1, finishedCount)
    }

    /**
     * Input:  the consent step, left untouched.
     * Output: asserts FR-ONB-003's three required statements are on screen — what is read, that it
     *         happens on-device only, and that the step is optional — and that finishing without
     *         touching the switch grants nothing. The wording is the requirement here, so it is
     *         asserted rather than eyeballed.
     */
    @Test
    fun `the consent step explains itself and grants nothing by default`() {
        compose.showFlow()
        clickNext()

        compose.onNodeWithText(text(R.string.onboarding_consent_what_is_read)).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.onboarding_consent_on_device)).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.onboarding_consent_optional)).assertIsDisplayed()

        clickNext()
        clickNext()
        clickNext()
        node(text(R.string.onboarding_skip)).performClick()

        assertTrue("skipping the opt-in must write nothing", consents.granted.isEmpty())
        assertTrue(consents.revoked.isEmpty())
    }

    /**
     * Input:  the flow at step two, then Back.
     * Output: asserts Back returns to the welcome step and that the first step offers no Back —
     *         a dead Back button on step one is the classic wizard bug.
     */
    @Test
    fun `back returns to the previous step and is absent on the first`() {
        compose.showFlow()
        compose.onNodeWithText(text(R.string.onboarding_back)).assertDoesNotExist()

        clickNext()
        node(text(R.string.onboarding_back)).performClick()
        compose.onNodeWithText(text(R.string.onboarding_welcome_pledge_title)).assertIsDisplayed()
    }

    /**
     * Input:  the flow rendered in dark mode.
     * Output: asserts it still renders and navigates. Dark mode is a DoD line item that is normally
     *         claimed rather than checked; this is the cheapest honest check of it.
     */
    @Test
    fun `renders in dark mode`() {
        compose.showFlow(darkTheme = true)
        compose.onNodeWithText(text(R.string.onboarding_welcome_title)).assertIsDisplayed()
        clickNext()
        compose.onNodeWithText(text(R.string.onboarding_consent_title)).assertIsDisplayed()
    }

    /**
     * Input:  the flow at a 200% font scale — the accessibility case the DoD names.
     * Output: asserts the primary action is still reachable. At twice the font size the quick-setup
     *         step is taller than a phone, so this is really a test that the flow scrolls: a step
     *         whose Finish button cannot be reached is a dead end for the users who need large text
     *         most.
     */
    @Test
    fun `stays usable at a 200% font scale`() {
        compose.showFlow(fontScale = LARGE_FONT_SCALE)
        // Derived from the enum rather than a literal count of taps: issue 2.2 inserted a step here
        // and ADR-0002 says issue 2.5 will insert another, so a hardcoded three would keep breaking.
        repeat(OnboardingStep.entries.lastIndex) { clickNext() }
        node(text(R.string.onboarding_finish)).performClick()

        assertEquals(1, settings.completeCallCount)
    }

    /**
     * Input:  the security step, with the lock switched on and a PIN typed twice.
     * Output: asserts the PIN fields only appear once the lock is on, that the flow completes, and
     *         that the PIN reaches the verifier. The fields being hidden until asked for is the
     *         point: a first-run screen that opens with two PIN boxes reads as a demand rather than
     *         the offer ADR-0002 requires it to be.
     */
    @Test
    fun `enables the app lock when the user sets a PIN`() {
        compose.showFlow()
        goToSecurityStep()

        // On the security step, before the toggle: no PIN entry at all.
        compose.onNodeWithText(text(R.string.onboarding_security_pin_label)).assertDoesNotExist()

        node(text(R.string.onboarding_security_toggle)).performClick()
        node(text(R.string.onboarding_security_pin_label)).performTextInput("135790")
        node(text(R.string.onboarding_security_pin_confirm_label)).performTextInput("135790")
        clickNext()

        node(text(R.string.onboarding_skip)).performClick()
        compose.waitForIdle()

        assertEquals("135790", pinVerifier.storedPin)
        assertTrue("the lock must be on after the step", appLock.enabled)
        assertEquals(1, finishedCount)
    }

    /**
     * Input:  the security step with the lock on but two different PINs.
     * Output: asserts the flow refuses to move on and says why, and that nothing was stored. A
     *         mistyped PIN set here is unrecoverable — the next thing that asks for it is the lock
     *         screen, and the user has only their memory of what they meant to type.
     */
    @Test
    fun `refuses to leave the security step when the PINs differ`() {
        compose.showFlow()
        goToSecurityStep()

        node(text(R.string.onboarding_security_toggle)).performClick()
        node(text(R.string.onboarding_security_pin_label)).performTextInput("1234")
        node(text(R.string.onboarding_security_pin_confirm_label)).performTextInput("4321")
        clickNext()

        // node(), not onNodeWithText(): with two PIN fields on screen the step is taller than the
        // viewport, so the banner at the top is composed but scrolled out of view.
        node(text(R.string.onboarding_security_error_pin_mismatch)).assertIsDisplayed()
        node(text(R.string.onboarding_security_toggle)).assertIsDisplayed()
        assertNull("a mismatched PIN must never be stored", pinVerifier.storedPin)
    }

    private companion object {
        /** The DoD's accessibility case: text at twice the default size. */
        const val LARGE_FONT_SCALE = 2.0f
    }
}
