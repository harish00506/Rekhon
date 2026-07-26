package com.aicfo.app.lock

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import com.aicfo.app.R
import com.aicfo.core.designsystem.component.CfoButton
import com.aicfo.core.designsystem.component.CfoCard
import com.aicfo.core.designsystem.component.CfoPinField
import com.aicfo.core.designsystem.component.CfoSecondaryButton
import com.aicfo.core.designsystem.theme.CfoDimens
import kotlinx.coroutines.delay
import kotlin.math.ceil

/**
 * The lock screen (issue 2.2; SEC-002, §23.1, ARC-004).
 *
 * Why:  the one screen in the app whose job is to show **nothing**. Everything here is either the
 *       means to unlock or an explanation of why unlocking is currently refused — there is no
 *       balance, no name, no figure of any kind, because the threat §23.1 names is someone else
 *       holding the phone.
 * What: a pure function of [AppLockUiState] that sends [AppLockEvent]s up.
 * Result: the gate a user actually taps through.
 * Changelog: 2026-07-26 — Created for issue 2.2.
 *
 * Scrollable, because at a 200% font the title, body, field, two buttons and an error message do
 * not fit a small screen — and a lock screen whose Unlock button is below the fold is a lock the
 * user cannot open.
 *
 * Input:  [uiState]; [onEvent] — events up (ARC-004); [onBiometricRequested] — asks the host
 *         activity to show `BiometricPrompt`, which only a `FragmentActivity` can do; [modifier].
 * Output: the rendered lock screen.
 */
@Composable
fun LockScreen(
    uiState: AppLockUiState,
    onEvent: (AppLockEvent) -> Unit,
    onBiometricRequested: () -> Unit,
    modifier: Modifier = Modifier,
) {
    LockoutCountdown(uiState.lockoutRemainingMillis, onEvent)

    Column(
        modifier =
            modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(CfoDimens.spaceMd),
        verticalArrangement = Arrangement.spacedBy(CfoDimens.spaceMd, Alignment.CenterVertically),
    ) {
        Text(text = stringResource(R.string.lock_title), style = MaterialTheme.typography.headlineSmall)
        Text(text = stringResource(R.string.lock_body))

        uiState.errorCode?.let { code -> ErrorBanner(code = code, onEvent = onEvent) }

        CfoPinField(
            value = uiState.pinEntry,
            onValueChange = { onEvent(AppLockEvent.PinChanged(it)) },
            label = stringResource(R.string.lock_pin_label),
            // Disabled during a lockout: the field going inert is what makes the timeout legible,
            // rather than the user typing into something that silently refuses.
            enabled = !uiState.isLockedOut && !uiState.isVerifying,
        )

        LockoutOrAttempts(uiState)

        CfoButton(
            text = stringResource(R.string.lock_unlock),
            onClick = { onEvent(AppLockEvent.PinSubmitted) },
            enabled = uiState.canSubmitPin,
            modifier = Modifier.fillMaxWidth(),
        )

        if (uiState.canUseBiometric) {
            CfoSecondaryButton(
                text = stringResource(R.string.lock_use_biometric),
                onClick = onBiometricRequested,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

/**
 * Re-enables entry the moment a lockout expires.
 * Why:    the countdown has to end by itself. Without this the user sits on "try again in 0
 *         seconds" until they background the app and come back, which reads as a frozen app rather
 *         than an expired timeout.
 * Result: sends [AppLockEvent.LockoutElapsed] once the remaining time has passed.
 * Input:  [remainingMillis] — `null` when not locked out; [onEvent]. Output: none (an effect).
 * Changelog: 2026-07-26 — Created for issue 2.2.
 */
@Composable
private fun LockoutCountdown(
    remainingMillis: Long?,
    onEvent: (AppLockEvent) -> Unit,
) {
    LaunchedEffect(remainingMillis) {
        if (remainingMillis != null) {
            delay(remainingMillis)
            onEvent(AppLockEvent.LockoutElapsed)
        }
    }
}

/**
 * Says either how long the lockout has left or how many tries remain.
 * Why:    P-02's spirit on a security screen — the user is told what the rule is and where they
 *         stand in it, rather than being surprised by a lockout. Both use ICU plurals so "1
 *         attempt" never reads as "1 attempts" (§21.6).
 * Result: one line of guidance, or nothing while the count is still full.
 * Input:  [uiState]. Output: the composition.
 * Changelog: 2026-07-26 — Created for issue 2.2.
 */
@Composable
private fun LockoutOrAttempts(uiState: AppLockUiState) {
    val remaining = uiState.lockoutRemainingMillis
    when {
        remaining != null -> {
            // Rounded up: "0 seconds" while still locked would be a lie the user can see.
            val seconds = ceil(remaining / MILLIS_PER_SECOND).toInt()
            Text(
                text = pluralStringResource(R.plurals.lock_locked_out, seconds, seconds),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
            )
        }

        // Only once they have actually got one wrong — warning an untouched screen that it has
        // five tries left just makes the app feel hostile on launch.
        uiState.attemptsRemaining < FULL_ATTEMPTS -> {
            Text(
                text =
                    pluralStringResource(
                        R.plurals.lock_attempts_remaining,
                        uiState.attemptsRemaining,
                        uiState.attemptsRemaining,
                    ),
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

/**
 * Shows why the last attempt was refused.
 * Why:    the wording lives in `strings.xml` and is chosen by code, never passed in as text — the
 *         ViewModel deals only in codes so no message can carry a detail that helps an attacker.
 * Result: a dismissible banner.
 * Input:  [code] — an [AppLockUiState] error code; [onEvent]. Output: the composition.
 * Changelog: 2026-07-26 — Created for issue 2.2.
 */
@Composable
private fun ErrorBanner(
    code: String,
    onEvent: (AppLockEvent) -> Unit,
) {
    CfoCard {
        Text(
            text =
                stringResource(
                    when (code) {
                        AppLockUiState.BIOMETRIC_FAILED -> R.string.lock_error_biometric
                        // Everything else — a wrong PIN, an unreadable credential, a storage
                        // fault — reads as "that PIN is not right". Distinguishing them on screen
                        // would tell whoever is holding the phone which factor to work on.
                        else -> R.string.lock_error_wrong_pin
                    },
                ),
            color = MaterialTheme.colorScheme.error,
        )
        CfoSecondaryButton(
            text = stringResource(R.string.lock_error_dismiss),
            onClick = { onEvent(AppLockEvent.DismissError) },
        )
    }
}

/** The lockout countdown is in millis; the message is in seconds. */
private const val MILLIS_PER_SECOND = 1_000.0

/** No warning is shown while the user still has every attempt (see [LockoutOrAttempts]). */
private const val FULL_ATTEMPTS = 5
