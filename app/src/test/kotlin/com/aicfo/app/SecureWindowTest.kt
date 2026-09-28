package com.aicfo.app

import android.os.Build
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.test.core.app.ActivityScenario
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The capture guard's policy (issue 11.2; §23, FR-PRIV-*).
 *
 * Why:  issue 5.3 tied `FLAG_SECURE` to the privacy-blur toggle, and left the policy to this
 *       issue. The policy is **always on**, and the two things that makes true are worth testing
 *       separately from the app: that the flag is set at all, and that **nothing clears it** —
 *       because the shape 5.3 shipped did clear it whenever the blur was off, which is the default.
 *       A guard that is off by default is not a guard.
 * What: the flag, the recents opt-out, and idempotence.
 * Result: a window that refuses to be captured, from the first frame to the last.
 * Changelog: 2026-09-28 — Created for issue 11.2.
 */
@RunWith(RobolectricTestRunner::class)
class SecureWindowTest {
    @Test
    fun `the window refuses capture`() {
        ActivityScenario.launch(ComponentActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                SecureWindow.applyTo(activity)

                assertTrue(
                    "FLAG_SECURE is not set",
                    activity.window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE != 0,
                )
            }
        }
    }

    @Test
    fun `applying it twice changes nothing`() {
        // It runs on every `onCreate`, including the recreation a language change causes (10.8).
        ActivityScenario.launch(ComponentActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                SecureWindow.applyTo(activity)
                val once = activity.window.attributes.flags

                SecureWindow.applyTo(activity)

                assertEquals(once, activity.window.attributes.flags)
            }
        }
    }

    @Test
    @Config(sdk = [Build.VERSION_CODES.TIRAMISU])
    fun `the app switcher gets no screenshot of this app`() {
        // `FLAG_SECURE` already blanks the thumbnail; this says so to the platform explicitly,
        // which is the API the acceptance criterion names and the one that keeps working if the
        // flag's side effects ever change.
        ActivityScenario.launch(ComponentActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                SecureWindow.applyTo(activity)

                assertTrue("the recents screenshot was not disabled", SecureWindow.recentsScreenshotDisabled)
            }
        }
    }

    @Test
    @Config(sdk = [Build.VERSION_CODES.O])
    fun `an older Android still gets the flag`() {
        // `setRecentsScreenshotEnabled` is API 33. The flag is not, and it is the part that matters.
        ActivityScenario.launch(ComponentActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                SecureWindow.applyTo(activity)

                assertTrue(
                    activity.window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE != 0,
                )
            }
        }
    }
}
