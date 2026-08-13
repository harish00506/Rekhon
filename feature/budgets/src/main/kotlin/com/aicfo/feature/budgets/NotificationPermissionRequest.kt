package com.aicfo.feature.budgets

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect

/**
 * Asks for POST_NOTIFICATIONS, once, right after the user sets their first budget (issue 4.5).
 *
 * Why:  **the timing is the whole design.** Android shows this prompt at most twice in an app's
 *       life, so where it is asked decides whether the feature works at all. Asking at launch would
 *       ask a user with no budgets to allow notifications about budgets — a request with no visible
 *       reason, which people deny, permanently. Asking here means the user has just told the app a
 *       number they care about, so "we'll tell you when you get close to it" needs no explaining.
 *
 *       **Nothing branches on the answer**, and a denial is not an error. The band still renders in
 *       the banner and on the row, and the notifier re-checks the permission at send time — so this
 *       is an offer the feature is complete without. Below API 33 there is no runtime permission and
 *       the launcher simply returns granted; the flag is cleared either way.
 * Result: the prompt is shown once per successful write, and the flag is cleared when it is answered.
 * Input:  [requested] — the state's one-shot flag; [onSettled] — clears it. Output: no composition.
 * Changelog: 2026-08-13 — Created for issue 4.5 (FR-BUD-004).
 */
@Composable
internal fun NotificationPermissionRequest(
    requested: Boolean,
    onSettled: () -> Unit,
) {
    val launcher =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { onSettled() }
    LaunchedEffect(requested) {
        if (!requested) return@LaunchedEffect
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            // Nothing to ask for: the permission is install-time before API 33. Clearing the flag
            // here rather than leaving it set is what stops the effect re-running on every state
            // change for the rest of the session.
            onSettled()
        }
    }
}
