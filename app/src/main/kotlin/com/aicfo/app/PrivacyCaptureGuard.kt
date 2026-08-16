package com.aicfo.app

import android.view.WindowManager
import androidx.activity.compose.LocalActivity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect

/**
 * Stops the window being captured while the privacy blur is on (issue 5.3; §23, FR-PRIV-*).
 *
 * Why:  masking the text is only half of what the requirement asks for. §5.3's criterion is that
 *       amounts are hidden "on screen (and in the widget/screenshots)", and a mask does nothing
 *       about the second: a screenshot, a screen recording, or a shared video call all capture the
 *       *rendered* surface, and if the user turned the blur on to hide their balance during a call
 *       then the call is exactly the thing carrying it away. `FLAG_SECURE` is the only API that
 *       stops all three, and it blanks the recents thumbnail as a side effect.
 *
 *       **Driven by the same flag as the mask**, so the two cannot disagree. An earlier shape had
 *       the Activity set the flag in its own collector, which would have been a second read of the
 *       same setting and a second thing to keep in step.
 * What: sets `FLAG_SECURE` while [secure] is true, and clears it otherwise.
 * Result: with the blur on, a screenshot is blank and a screen-share shows nothing.
 * Changelog: 2026-08-16 — Created for issue 5.3.
 *
 * **Scope, against issue 11.2.** That issue owns the *policy* — whether the flag should be on
 * permanently, which screens are exempt, and what the recents thumbnail should show. This is the
 * narrow version 5.3 needs and no more: the flag follows the user's own toggle. When 11.2 lands it
 * replaces this decision rather than fighting it, because there is one place that sets the flag.
 *
 * **`DisposableEffect`, and the `onDispose` matters.** Without it a blurred session would leave the
 * flag set on a window that outlives this composable, so every later screen would be uncapturable
 * with nothing on screen explaining why — the kind of state that looks like a broken phone rather
 * than a feature. Keyed on [secure], so it re-runs whenever the toggle flips.
 *
 * **A no-op outside an Activity.** `LocalActivity` is null in a Paparazzi or Robolectric render,
 * and a test host has no window to secure. Failing loudly there would break every screenshot test to
 * protect a surface that does not exist.
 *
 * `LocalActivity`, not `LocalContext as? Activity` — the cast is what `ContextCastToActivity` fails
 * the build on, and it is right to: the context is not guaranteed to be the Activity once the
 * composition is hosted somewhere else.
 *
 * Input:  [secure] — whether the window must refuse capture.
 * Output: none (a side effect on the Activity's window).
 */
@Composable
internal fun PrivacyCaptureGuard(secure: Boolean) {
    val activity = LocalActivity.current ?: return

    DisposableEffect(secure) {
        if (secure) {
            activity.window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        } else {
            activity.window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
        }
        onDispose { activity.window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE) }
    }
}
