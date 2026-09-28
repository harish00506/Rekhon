package com.aicfo.app

import android.view.WindowManager
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The real window refuses capture (issue 11.2; §23, FR-PRIV-*).
 *
 * Why:  `SecureWindowTest` proves the policy against a bare `ComponentActivity`. What it cannot
 *       prove is that the policy is *applied* to the activity the user actually launches, with the
 *       real Hilt graph, the app lock in front of it and the privacy blur in whatever state the
 *       installed app left it. That is one line in `onCreate` — and one line is exactly what gets
 *       lost in a merge.
 *
 *       It is also the acceptance criterion's "instrumented check": the flag is read back from the
 *       window the platform is drawing, not from a field this app set.
 * What: the launched activity's window flags.
 * Result: a screenshot of this app is blank on a device, and stays blank with the blur off.
 * Changelog: 2026-09-28 — Created for issue 11.2.
 *
 * **Independent of the blur's state**, deliberately. Issue 5.3's version armed the flag only while
 * the blur was on; asserting the flag *without touching the toggle* is what distinguishes the new
 * policy from the old one, whatever the installed app happens to have stored.
 */
@RunWith(AndroidJUnit4::class)
class CaptureGuardDeviceTest {
    @get:Rule
    val compose = createAndroidComposeRule<MainActivity>()

    /**
     * Input:  the app, launched.
     * Output: asserts `FLAG_SECURE` is set on the window the platform is drawing — so screenshots,
     *         screen recordings, shared screens and the app-switcher thumbnail all show nothing.
     */
    @Test
    fun theWindowRefusesCaptureFromTheFirstFrame() {
        val flags = compose.activity.window.attributes.flags

        assertTrue(
            "FLAG_SECURE is not set on the launched activity — screenshots of this app would work",
            flags and WindowManager.LayoutParams.FLAG_SECURE != 0,
        )
    }
}
