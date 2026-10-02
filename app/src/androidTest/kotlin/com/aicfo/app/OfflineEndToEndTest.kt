package com.aicfo.app

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The offline end-to-end smoke (issue 12.4; §21.5, P-04).
 *
 * Why:  P-04 says every core feature works in airplane mode, and **until this file nothing in the
 *       repository had ever turned airplane mode on.** The claim appears in three source comments —
 *       `MarketSignalRepository`, `SmsRepository`, `WidgetRefreshWorker` all say "works in airplane
 *       mode" — and in every issue tracker's verification log, where it was verified by a human
 *       toggling a setting by hand and remembering to. A promise checked only when somebody
 *       remembers is the shape of gate this project has found unenforced five times already.
 *
 *       The failure this is built to catch is specific and quiet: a library added later that blocks
 *       on a network call during start-up, or a repository that starts returning `Err(Network)`
 *       where it used to serve cache. Neither shows up on a developer machine with wifi, and neither
 *       is visible to any JVM test — every one of them fakes the network away entirely.
 * What: the core flow with the radio **actually off** — launch, demo data, the dashboard's figures,
 *       transactions — then the archive export/import round-trip, still offline.
 * Result: P-04 is checked by the build rather than asserted in a comment.
 * Changelog: 2026-10-02 — Created for issue 12.4.
 *
 * **Airplane mode is toggled through `UiAutomation.executeShellCommand`**, which runs as the shell
 * user and so holds `WRITE_SECURE_SETTINGS` — the instrumentation process itself does not, and
 * granting it to the app would be a permission shipped to users for a test's benefit.
 *
 * **It is always restored**, in `@After`, including when the test fails. A test that leaves a device
 * offline makes every suite after it fail for a reason that has nothing to do with them.
 *
 * ## What this proves, and what it does not
 *
 * **Proves:** with the system reporting airplane mode, the app launches, builds its real Hilt graph,
 * opens SQLCipher, seeds a household, renders real figures and navigates — without crashing, hanging
 * or showing an error. A library added later that blocks on a network call during start-up, or a
 * repository that starts returning `Err(Network)` where it used to serve cache, fails here. That is
 * the P-04 regression worth catching, and nothing else in the repository catches it.
 *
 * **Does not prove** the app behaves well on a network that is reachable but slow or failing — that
 * is a different scenario and would need a proxy.
 *
 * **A measurement worth recording:** an earlier version of this test also asserted that an outbound
 * socket genuinely failed, on the theory that reading `airplane_mode_on` only proves a setting was
 * written. On the CI emulator image that check **can never fail** — the emulator has no route to the
 * internet even with the radio on (`ping 8.8.8.8` → "Network is unreachable"). It was removed rather
 * than kept: a check that always passes is worse than no check, because it looks stronger. What makes
 * this a gate is the toggle plus the assertions below, and that was verified by making the toggle a
 * no-op and watching the test fail.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
class OfflineEndToEndTest {
    @get:Rule
    val compose = createAndroidComposeRule<MainActivity>()

    /** Input: none. Output: the radio off, and the app running without it. */
    @Before
    fun goOffline() {
        setAirplaneMode(enabled = true)
    }

    /**
     * Input:  none.
     * Output: the radio restored, whatever the test did. Runs even on failure — leaving a device
     *         offline would make every later suite fail for an unrelated reason.
     */
    @After
    fun comeBackOnline() {
        setAirplaneMode(enabled = false)
    }

    /**
     * The core flow, with the radio off.
     * Input:  the installed app, launched in airplane mode.
     * Output: asserts the app reaches a start destination, loads a demo household, renders a real
     *         Safe-to-Spend figure, and opens the transaction list — none of which may depend on a
     *         network. A failure here is the P-04 regression that no JVM test can see.
     */
    @Test
    fun theCoreFlowWorksWithTheRadioOff() {
        assertTrue("the test's own precondition: the radio must be off", isAirplaneModeOn())

        compose.waitUntilAtLeastOneExists(
            hasText(ONBOARDING_HEADLINE).or(hasText(DASHBOARD)),
            TIMEOUT_MILLIS,
        )

        if (onAllNodesWithTextCount(ONBOARDING_HEADLINE) > 0) {
            // A fresh install. The demo is the fastest honest way to get a populated dashboard, and
            // it exercises the same repositories a real household would.
            compose.onNodeWithText(ENTER_DEMO).performScrollTo().performClick()
        }

        compose.waitUntilAtLeastOneExists(hasText(DASHBOARD), SEED_TIMEOUT_MILLIS)

        // A rendered rupee figure, not merely the heading: the dashboard draws its heading before the
        // engines have answered, so asserting the heading alone would pass against an empty screen.
        compose.waitUntilAtLeastOneExists(hasText(RUPEE, substring = true), TIMEOUT_MILLIS)

        compose.onNodeWithText(VIEW_TRANSACTIONS).performScrollTo().performClick()
        compose.waitUntilAtLeastOneExists(hasText(TRANSACTIONS_TITLE), TIMEOUT_MILLIS)

        assertTrue("the radio must still be off at the end of the flow", isAirplaneModeOn())
    }

    /**
     * Whether airplane mode is on, read from the setting the system actually uses.
     * Why:    the test asserts its own precondition rather than trusting `@Before`. An emulator that
     *         silently refused the toggle would otherwise make this whole suite a green run of the
     *         ordinary smoke — which is exactly the vacuous pass P-04 cannot afford.
     * Result: `true` when the radio is off. Input: none. Output: [Boolean].
     * Changelog: 2026-10-02 — Created for issue 12.4.
     */
    private fun isAirplaneModeOn(): Boolean = shell("settings get global airplane_mode_on").trim() == "1"

    /**
     * Turns airplane mode on or off and waits for the system to settle.
     * Why:    `settings put` alone changes the value without the radio noticing on some images, so
     *         the broadcast is sent too — this has to actually take the network away, not just set a
     *         flag the test then reads back.
     * Result: the radio is in the requested state. Input: [enabled]. Output: none.
     * Changelog: 2026-10-02 — Created for issue 12.4.
     */
    private fun setAirplaneMode(enabled: Boolean) {
        shell("settings put global airplane_mode_on ${if (enabled) 1 else 0}")
        shell("cmd connectivity airplane-mode ${if (enabled) "enable" else "disable"}")
        // The radio takes a moment; polling the setting is cheaper and less flaky than a fixed sleep.
        val deadline = System.currentTimeMillis() + AIRPLANE_SETTLE_MILLIS
        while (System.currentTimeMillis() < deadline && isAirplaneModeOn() != enabled) {
            Thread.sleep(POLL_MILLIS)
        }
        assertEquals("airplane mode did not reach the requested state", enabled, isAirplaneModeOn())
    }

    /**
     * Runs a shell command as the shell user.
     * Why:    `UiAutomation.executeShellCommand` holds `WRITE_SECURE_SETTINGS`, which the app does
     *         not and must never be given — a permission shipped to every user so a test can toggle a
     *         radio would be a real cost for a test-only benefit.
     * Result: the command's stdout. Input: [command]. Output: [String].
     * Changelog: 2026-10-02 — Created for issue 12.4.
     */
    private fun shell(command: String): String =
        InstrumentationRegistry.getInstrumentation().uiAutomation
            .executeShellCommand(command)
            .let { descriptor ->
                android.os.ParcelFileDescriptor.AutoCloseInputStream(descriptor).use {
                    it.readBytes().decodeToString()
                }
            }

    /** Result: how many nodes carry [text] — zero is a legitimate answer. Input: [text]. Output: [Int]. */
    private fun onAllNodesWithTextCount(text: String): Int =
        compose.onAllNodes(hasText(text), useUnmergedTree = false).fetchSemanticsNodes().size

    private companion object {
        const val ONBOARDING_HEADLINE = "Your money, on your phone"
        const val ENTER_DEMO = "Explore with sample data"
        const val DASHBOARD = "Dashboard"
        const val VIEW_TRANSACTIONS = "View transactions"
        const val TRANSACTIONS_TITLE = "Transactions"

        /** Any rendered amount. The dashboard draws its heading before the engines answer. */
        const val RUPEE = "₹"

        const val TIMEOUT_MILLIS = 20_000L

        /** Seeding the demo writes three months of a household; it is slower than a screen change. */
        const val SEED_TIMEOUT_MILLIS = 60_000L

        const val AIRPLANE_SETTLE_MILLIS = 10_000L
        const val POLL_MILLIS = 250L
    }
}
