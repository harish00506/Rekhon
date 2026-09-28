package com.aicfo.app

import android.app.Activity
import android.os.Build
import android.view.WindowManager

/**
 * The app's windows cannot be captured (issue 11.2; §23, FR-PRIV-*, and Epic 11's fail-secure rule).
 *
 * Why:  §23 asks for sensitive surfaces to be capture-guarded, and in this app **every** surface is
 *       one. There is no screen that is not either an amount, a list of what someone bought, a
 *       forecast of what they will run out of, or the PIN that protects all three. So the policy is
 *       the simple one: the flag goes on once, at `onCreate`, and nothing takes it off.
 *
 *       **The alternative was an allowlist, and it is the wrong shape.** "Which screens are
 *       sensitive?" has to be answered again for every screen anyone adds, by someone who may not
 *       be thinking about it, and the failure mode is silent — a screenshot that works. One
 *       forgotten annotation is a leak; one over-broad flag is a screenshot a user cannot take.
 *
 *       **It replaces the version issue 5.3 shipped, which was off by default.** That one tied the
 *       flag to the privacy-blur toggle, so with the blur off — the default — the window was
 *       capturable, and turning the blur on was the only thing that armed it. The blur's *masking*
 *       half is untouched and still does its job over someone's shoulder; its capture half is now
 *       redundant, which is the point: there is nothing left to disagree about.
 * What: `FLAG_SECURE`, plus the explicit recents opt-out where the platform has it.
 * Result: screenshots, screen recordings, shared screens and the app-switcher thumbnail all show
 *       nothing, from the first frame after launch.
 * Changelog: 2026-09-28 — Created for issue 11.2, replacing `PrivacyCaptureGuard` (issue 5.3).
 *
 * **Set in `onCreate`, before `setContent`.** A composition-driven flag misses the first frame, and
 * the first frame of this app is the lock screen — the one that shows a PIN pad being typed into.
 */
internal object SecureWindow {
    /**
     * Whether the last [applyTo] disabled the app-switcher screenshot.
     * Why:    `setRecentsScreenshotEnabled` has no getter, so a test has no other way to know it
     *         was called. Recorded rather than inferred, and read only by the test that asserts
     *         the API-33 branch ran.
     */
    @Volatile
    var recentsScreenshotDisabled: Boolean = false
        private set

    /**
     * Applies the policy to an activity's window.
     * Why:    one function, called from one place, so "is this screen guarded?" has exactly one
     *         answer for the whole app.
     * What:   adds `FLAG_SECURE`, and on Android 13+ tells the platform not to take a recents
     *         screenshot at all — the flag already blanks the thumbnail, and this says so
     *         explicitly, which is the API the acceptance criterion names.
     * Result: the window refuses capture for as long as it exists.
     * Input:  [activity] — the activity whose window to secure.
     * Output: none (a side effect on the window).
     */
    fun applyTo(activity: Activity) {
        activity.window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            activity.setRecentsScreenshotEnabled(false)
            recentsScreenshotDisabled = true
        }
    }
}
