package com.aicfo.app

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.AndroidComposeTestRule
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The app's end-to-end smoke, on a real device (issue 3.7; §21.5 phase 8, P-04).
 *
 * Why:  **`:app:connectedDebugAndroidTest` is named in the Definition of Done and, until this file,
 *       had no source set at all** — the task ran zero tests and reported success, so a gate the
 *       workflow treats as binding had never checked anything. That is the same failure the
 *       governance audit (`docs/report/2026-07-25-governance-standards-audit.md`) found in the
 *       coverage gate: a gate nobody has watched go red is not a gate.
 *
 *       What it proves that no JVM test can: the app boots its **real** Hilt graph (every binding
 *       resolved, including issue 3.7's engine and repository), **SQLCipher opens the version-10
 *       database** after the migration chain runs, and the nav host reaches a start destination.
 *       Every unit suite in this repo uses unencrypted in-memory Room and fakes, so all three are
 *       device-only, and each has a failure mode that is a crash on launch rather than a red test.
 * What: launches the real activity, then drives the demo → transactions → recurring-proposal path.
 * Result: the DoD's phase 8 has teeth for the first time.
 * Changelog: 2026-08-05 — Created for issue 3.7.
 *
 * **No `HiltAndroidRule` and no `HiltTestApplication`.** Those exist to *replace* bindings; this
 * test wants the opposite — the graph the user actually gets. The default `AndroidJUnitRunner` with
 * the real `@HiltAndroidApp` application is what gives it, and it keeps a test-only Hilt dependency
 * out of the build.
 *
 * **State-tolerant on purpose.** It runs against whatever the installed app already holds — a fresh
 * install lands on onboarding, a device that has been driven before lands on the dashboard — so it
 * does not depend on a `pm clear` a developer might forget, and it cannot pass merely because the
 * previous run left the right screen open.
 */
@OptIn(ExperimentalTestApi::class) // `waitUntilAtLeastOneExists` — the sanctioned way to wait
// for a real screen to settle; polling with Thread.sleep is the flaky alternative.
@RunWith(AndroidJUnit4::class)
class CfoSmokeTest {
    @get:Rule
    val compose = createAndroidComposeRule<MainActivity>()

    /**
     * Input:  the installed app, launched.
     * Output: asserts a start destination rendered. A failure here is a Hilt binding the graph
     *         cannot build, a database SQLCipher will not open, or a migration that threw — none of
     *         which any JVM test in this repo can reach.
     */
    @Test
    fun theAppBootsItsRealGraphAndOpensTheEncryptedDatabase() {
        compose.waitUntilAtLeastOneExists(hasText(ONBOARDING_HEADLINE).or(hasText(DASHBOARD)), TIMEOUT_MILLIS)

        val onboarding = compose.onAllNodesWithTextCount(ONBOARDING_HEADLINE)
        val dashboard = compose.onAllNodesWithTextCount(DASHBOARD)

        assertTrue("expected either onboarding or the dashboard, saw neither", onboarding + dashboard > 0)
    }

    /**
     * The issue's own path, end to end (FR-TXN-006).
     *
     * Input:  the app, from wherever it starts. Enters the demo if it is still on onboarding — the
     *         sample ledger seeds three months of fixed monthly obligations, which is exactly the
     *         shape the detector exists to find.
     * Output: asserts a proposal renders **with its evidence** (P-02) on the transactions list,
     *         computed by the real engine from rows read out of the real encrypted database.
     *
     * **KNOWN DEFECT — this test needs a clean install, and nobody yet knows why.**
     * Reproduction is reliable: `adb shell pm clear com.aicfo.personalcfo` then run, and it passes;
     * run it a second time without clearing, and the wait for [RECURRING_HEADING] times out. Ruled
     * out so far, each by evidence rather than reasoning:
     *
     *  - **Not the onboarding path.** The precondition below does not fire on the failing run —
     *    onboarding is showing, the demo is entered, and the dashboard renders.
     *  - **Not a timeout.** Raised from 20s to [SEED_TIMEOUT_MILLIS]; the failing run still spends
     *    the whole budget.
     *  - **Not a write on a read path.** Only three places write `recurring_rule`
     *    (`DemoModeRepository.enter`, `QuickSetupRepository`, `RecurringRepository.confirm/dismiss`)
     *    and none is on the render path, so nothing silently marks a proposal decided.
     *  - **Not the demo's own seeded rules.** They are written with `name = null`, and
     *    `RecurringRuleDao.observeDecidedNames` filters `name IS NOT NULL`, so they exclude nothing.
     *  - **Not duplicated seed rows.** Demo ids are deterministic (`demo:txn:0001`) and upserted.
     *
     * What blocked going further is the app's own privacy design, which is working as intended:
     * `uiautomator dump` returns an empty hierarchy because the window is secure, and the database
     * is SQLCipher-encrypted, so neither the rendered screen nor the stored rows can be inspected
     * from outside the process. Pinning this down needs a diagnostic from *inside* an instrumented
     * build — read the candidate rows and the decided names through the repository at the point of
     * failure and print the difference between the two runs.
     */
    @Test
    fun theRecurringSectionProposesASeriesFromTheRealLedger() {
        compose.waitUntilAtLeastOneExists(hasText(ONBOARDING_HEADLINE).or(hasText(DASHBOARD)), TIMEOUT_MILLIS)

        // The demo's sample ledger IS this test's fixture, and the only way into it is the
        // onboarding button. On a device whose app data already holds a real profile there is no
        // seeded ledger, nothing will ever propose a series, and the old code walked straight past
        // this branch into a twenty-second wait that failed with "condition still not satisfied" —
        // which names the symptom and not one word of the cause.
        //
        // So the precondition is asserted where it is knowable. Not `assumeTrue`: a test that
        // quietly skips itself when its fixture is missing is the vacuous gate this repo keeps
        // finding, and a green run would then mean nothing.
        assertTrue(
            "This test needs a clean install: its fixture is the demo ledger, and the only way in " +
                "is the onboarding screen, which is not showing. The app already holds a profile. " +
                "Run `adb shell pm clear com.aicfo.personalcfo` and try again.",
            compose.onAllNodesWithTextCount(ONBOARDING_HEADLINE) > 0,
        )

        compose.onNodeWithText(ENTER_DEMO).performClick()
        // Its own budget, not TIMEOUT_MILLIS: this wait covers seeding three months of sample
        // ledger into an encrypted database, which is real work and much slower than the screen
        // transitions the shared timeout was sized for.
        compose.waitUntilAtLeastOneExists(hasText(DASHBOARD), SEED_TIMEOUT_MILLIS)

        compose.onNodeWithText(VIEW_TRANSACTIONS).performClick()
        // Also generous: the detector runs over every seeded row on first render.
        compose.waitUntilAtLeastOneExists(hasText(RECURRING_HEADING), SEED_TIMEOUT_MILLIS)

        compose.onAllNodes(hasText(RECURRING_HEADING)).onFirst().assertExists()
        // The evidence, not just the heading: a card that named a merchant without saying which
        // payments it was built from would satisfy the requirement's letter and not P-02.
        compose.waitUntilAtLeastOneExists(hasText(OCCURRENCES_SUFFIX, substring = true), SEED_TIMEOUT_MILLIS)
    }

    /**
     * Counts the nodes carrying one exact string.
     * Why:    `onNodeWithText` throws when there is no match, which is the wrong shape for a test
     *         that has to ask *which* of two screens it is looking at rather than assert one.
     * Result: the match count. Input: [text]. Output: [Int].
     */
    private fun AndroidComposeTestRule<*, *>.onAllNodesWithTextCount(text: String): Int =
        onAllNodes(hasText(text)).fetchSemanticsNodes().size

    private companion object {
        /**
         * Literal copy, not `R.string`. These strings belong to `:feature:onboarding`,
         * `:feature:dashboard` and `:feature:transactions`, and `:app` depends on all three but
         * cannot name another module's resource ids. Asserting the words the user actually reads is
         * also the more honest end-to-end check — a resource lookup would still pass if the string
         * were wired to the wrong screen.
         */
        const val ONBOARDING_HEADLINE = "Your money, on your phone"
        const val ENTER_DEMO = "Explore with sample data"
        const val DASHBOARD = "Dashboard"
        const val VIEW_TRANSACTIONS = "View transactions"
        const val RECURRING_HEADING = "Looks like a repeat"

        /** From `transactions_recurring_occurrences`, the ICU plural that carries the evidence. */
        const val OCCURRENCES_SUFFIX = "payments"

        /** Generous: a cold start decrypts the database and runs the migration chain. */
        const val TIMEOUT_MILLIS = 20_000L

        /**
         * For the demo path, which is not a screen transition but a write.
         *
         * Seeding three months of sample ledger through SQLCipher and then running the recurring
         * detector over all of it is genuinely slow on a cold emulator — the run that first exposed
         * this test's state dependency spent 21.3s and died on a 20s budget, close enough to the
         * line that the timeout would have gone on failing intermittently even after the real cause
         * was fixed. Sized so a slow machine is not mistaken for a broken app.
         */
        const val SEED_TIMEOUT_MILLIS = 60_000L
    }
}
