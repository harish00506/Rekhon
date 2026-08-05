package com.aicfo.app.lock

import androidx.activity.compose.LocalActivity
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aicfo.app.R

/**
 * Wraps the whole app in the lock (issue 2.2; SEC-002, §23.1).
 *
 * Why:  **a wrapper and not a navigation destination, deliberately.** A `CfoRoute.Lock` would be a
 *       screen the back stack can pop, a deep link can skip and a restored task can land behind —
 *       three ways to be past the lock without ever having unlocked. Composing the app's content
 *       only when the session is open means there is no arrangement of navigation that shows data
 *       to a locked app, and it covers onboarding too, which a start-destination approach would not.
 * What: renders [LockScreen] or [content], owns `BiometricPrompt`, and drives the auto-lock timer
 *       from the host's lifecycle.
 * Result: the single place the app decides whether to draw anything at all.
 * Changelog: 2026-07-26 — Created for issue 2.2.
 *
 * **`BiometricPrompt` lives here because it needs a `FragmentActivity`.** That is also why
 * `AppLockViewModel` never touches it: the platform decides, and the ViewModel only records the
 * outcome — which keeps every fail-secure decision on the JVM where it can be tested.
 *
 * **While the status is `CHECKING` this composes nothing.** A frame of dashboard before the lock
 * appears would leak exactly what the lock exists to protect.
 *
 * Input:  [content] — the app, composed only once the session is open.
 * Output: the rendered gate.
 */
@Composable
fun AppLockGate(content: @Composable () -> Unit) {
    val viewModel: AppLockViewModel = hiltViewModel()
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    // LocalActivity, not a cast of LocalContext: the context a composable sees may be a
    // ContextWrapper rather than the activity, so the cast silently yields null in exactly the
    // cases that are hardest to reproduce. Android Lint's ContextCastToActivity flags it.
    val activity = LocalActivity.current as? FragmentActivity

    BiometricAvailability(viewModel)
    AutoLockTimer(viewModel)

    when {
        uiState.showsContent -> content()

        uiState.status == LockStatus.LOCKED ->
            LockScreen(
                uiState = uiState,
                onEvent = viewModel::onEvent,
                onBiometricRequested = { activity?.let { showBiometricPrompt(it, viewModel) } },
            )

        // CHECKING: draw nothing at all. See the class note.
        else -> Unit
    }
}

/**
 * Tells the ViewModel whether a class-3 sensor is usable right now.
 * Why:    the answer changes while the app is installed — a user enrols a fingerprint, or removes
 *         their last one — so it is re-asked on every composition of the gate rather than cached.
 *         `BIOMETRIC_STRONG` and nothing weaker: SEC-002 says class 3, and class 2 can be satisfied
 *         by face unlock that a photograph defeats.
 * Result: the biometric button appears only when it would actually work.
 * Input:  [viewModel]. Output: none (an effect).
 * Changelog: 2026-07-26 — Created for issue 2.2.
 */
@Composable
private fun BiometricAvailability(viewModel: AppLockViewModel) {
    val context = LocalContext.current
    LaunchedEffect(context) {
        val canAuthenticate =
            BiometricManager.from(context)
                .canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_STRONG)
        viewModel.setBiometricAvailable(canAuthenticate == BiometricManager.BIOMETRIC_SUCCESS)
    }
}

/**
 * Feeds the host's lifecycle into the auto-lock timer (§23.1).
 * Why:    the threat is a phone left unlocked on a desk, so what matters is how long the app spent
 *         in the background. `ON_STOP`/`ON_START` rather than pause/resume: a transient dialog or
 *         the permission sheet pauses the activity without the app ever leaving the screen, and
 *         re-locking behind those would be maddening.
 * Result: the ViewModel is told when the app left and when it came back.
 * Input:  [viewModel]. Output: none (an effect).
 * Changelog: 2026-07-26 — Created for issue 2.2.
 */
@Composable
private fun AutoLockTimer(viewModel: AppLockViewModel) {
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer =
            LifecycleEventObserver { _, event ->
                when (event) {
                    Lifecycle.Event.ON_STOP -> viewModel.onEvent(AppLockEvent.Backgrounded)
                    Lifecycle.Event.ON_START -> viewModel.onEvent(AppLockEvent.Foregrounded)
                    else -> Unit
                }
            }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
}

/**
 * Shows the class-3 biometric prompt and reports the outcome (SEC-002).
 *
 * Why:    every callback other than `onAuthenticationSucceeded` reports a **failure**, including
 *         the user simply cancelling. That is the fail-secure requirement in its most concrete
 *         form, and the easiest thing to get wrong — treating "not an error" as "authenticated" is
 *         a one-line mistake that opens the app to anyone who dismisses the sheet.
 * Result: sends [AppLockEvent.BiometricSucceeded] or [AppLockEvent.BiometricFailed].
 * Input:  [activity] — the host, which `BiometricPrompt` requires; [viewModel] — receives the
 *         outcome. Output: none (shows a system prompt).
 * Changelog: 2026-07-26 — Created for issue 2.2.
 *
 * `onAuthenticationFailed` — a finger that did not match — is **not** reported as an attempt.
 * The platform lets the user try again within the same prompt and applies its own hardware
 * back-off; charging an app-level attempt as well would let a smudged sensor march a legitimate
 * user into a 30-second lockout. A dismissed or errored prompt does count.
 */
private fun showBiometricPrompt(
    activity: FragmentActivity,
    viewModel: AppLockViewModel,
) {
    val prompt =
        BiometricPrompt(
            activity,
            ContextCompat.getMainExecutor(activity),
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    viewModel.onEvent(AppLockEvent.BiometricSucceeded)
                }

                override fun onAuthenticationError(
                    errorCode: Int,
                    errString: CharSequence,
                ) {
                    // errString is discarded, not shown: it is platform text that can name the
                    // sensor and the reason, and P-01 keeps that off both the screen and the log.
                    viewModel.onEvent(AppLockEvent.BiometricFailed)
                }
            },
        )
    prompt.authenticate(
        BiometricPrompt.PromptInfo.Builder()
            .setTitle(activity.getString(R.string.lock_biometric_prompt_title))
            .setSubtitle(activity.getString(R.string.lock_biometric_prompt_subtitle))
            // Class 3 only (SEC-002). Not setAllowedAuthenticators(...or DEVICE_CREDENTIAL): the
            // device PIN is not this app's PIN, and accepting it would mean anyone who can unlock
            // the phone can open the user's finances — which is the threat in §23.1's first row.
            .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_STRONG)
            .setNegativeButtonText(activity.getString(R.string.lock_biometric_prompt_negative))
            .build(),
    )
}
