package com.aicfo.feature.onboarding

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aicfo.core.designsystem.component.CfoButton
import com.aicfo.core.designsystem.component.CfoCard
import com.aicfo.core.designsystem.component.CfoSecondaryButton
import com.aicfo.core.designsystem.theme.CfoDimens
import com.aicfo.core.designsystem.theme.CfoTheme

/**
 * First-run onboarding (issue 2.1; FR-ONB-001/002/003, ARC-004).
 *
 * Why:  the first thing a new user sees, and the only chance to capture the profile time zone
 *       before any date in the app is computed (TIM-001). It follows the dashboard's shape exactly:
 *       a stateful entry point that collects the ViewModel, and a stateless body that renders a
 *       state — so every step can be previewed and tested without Hilt or navigation. Navigation
 *       arrives as a lambda, so this module never learns another feature exists (ARC-001).
 * What: a four-step flow, scrollable so it survives a 200% font setting.
 * Result: a profile, a consent decision, and the app's start destination for every launch after.
 * Changelog: 2026-07-25 — Created for issue 2.1.
 *
 * Input:  [onFinished] — where to go once the profile is saved; [modifier]; [viewModel] — supplied
 *         by Hilt, overridable in tests.
 * Output: the rendered flow.
 */
@Composable
fun OnboardingScreen(
    onFinished: () -> Unit,
    onDemoStarted: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: OnboardingViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    // Keyed on the flag, not fired from a click handler: the save is asynchronous and may fail, and
    // navigating on the tap rather than on the result would leave a user on a dashboard whose
    // profile was never written.
    LaunchedEffect(uiState.isComplete) {
        if (uiState.isComplete) onFinished()
    }
    // A second effect rather than a branch inside the first: the two outcomes are different
    // destinations in the graph's eyes (issue 2.4 — a demo user has no profile and must be able to
    // come back to this flow), and one keyed on two flags would re-fire on the wrong one.
    LaunchedEffect(uiState.isDemoStarted) {
        if (uiState.isDemoStarted) onDemoStarted()
    }
    OnboardingContent(uiState = uiState, onEvent = viewModel::onEvent, modifier = modifier)
}

/**
 * The flow's body, with no dependencies of its own.
 * Why:    stateless, so a preview or a test can render any step — including saving and error —
 *         without constructing a ViewModel.
 * Result: the rendered step plus its actions.
 * Input:  [uiState]; [onEvent] — events up (ARC-004); [modifier]. Output: the composition.
 * Changelog: 2026-07-25 — Created for issue 2.1.
 */
@Composable
fun OnboardingContent(
    uiState: OnboardingUiState,
    onEvent: (OnboardingEvent) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        // Scrollable on purpose: at a 200% font setting the quick-setup step is taller than a
        // phone, and a step whose Next button cannot be reached is a dead end.
        modifier =
            modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(CfoDimens.spaceMd),
        verticalArrangement = Arrangement.spacedBy(CfoDimens.spaceMd),
    ) {
        Text(
            text = stringResource(R.string.onboarding_step_of, uiState.stepNumber, uiState.stepCount),
            style = MaterialTheme.typography.labelLarge,
        )
        uiState.errorCode?.let { code -> ErrorBanner(code = code, onEvent = onEvent) }
        when (uiState.step) {
            OnboardingStep.WELCOME -> WelcomeStep(uiState = uiState, onEvent = onEvent)
            OnboardingStep.CONSENT -> ConsentStep(uiState = uiState, onEvent = onEvent)
            OnboardingStep.PROFILE -> ProfileStep(uiState = uiState, onEvent = onEvent)
            OnboardingStep.SECURITY -> SecurityStep(uiState = uiState, onEvent = onEvent)
            OnboardingStep.QUICK_SETUP -> QuickSetupStep(uiState = uiState, onEvent = onEvent)
        }
        StepActions(uiState = uiState, onEvent = onEvent)
    }
}

/**
 * The something-went-wrong banner.
 * Why:    a failed write must be visible and dismissible rather than silently swallowed — the flow
 *         deliberately does not advance on failure, so without this the Finish button would look
 *         broken. Issue 2.2 added two more reasons it can appear, and they need their own wording:
 *         "that PIN is too short" and "generic save failure" are not the same instruction.
 * Result: the banner. Input: [code] — an error code from the ViewModel; [onEvent].
 * Output: the composition.
 * Changelog: 2026-07-25 — Created for issue 2.1.
 *            2026-07-26 — Issue 2.2: takes a code so the PIN problems read differently.
 */
@Composable
private fun ErrorBanner(
    code: String,
    onEvent: (OnboardingEvent) -> Unit,
) {
    CfoCard {
        Text(
            text =
                stringResource(
                    when (code) {
                        ERROR_PIN_TOO_SHORT -> R.string.onboarding_security_error_pin_short
                        ERROR_PIN_MISMATCH -> R.string.onboarding_security_error_pin_mismatch
                        else -> R.string.onboarding_error
                    },
                ),
            color = CfoTheme.extendedColors.negative,
        )
        CfoSecondaryButton(
            text = stringResource(R.string.onboarding_error_dismiss),
            onClick = { onEvent(OnboardingEvent.DismissError) },
        )
    }
}

/**
 * Back, Next/Finish, and Skip on the optional step.
 * Why:    one place decides what the primary action means, so "Next" cannot appear on the last step
 *         or "Finish" on the first. Everything is disabled while a save is in flight, because a
 *         second Finish tap would attempt a second write.
 * Result: the action row. Input: [uiState], [onEvent]. Output: the composition.
 * Changelog: 2026-07-25 — Created for issue 2.1.
 */
@Composable
private fun StepActions(
    uiState: OnboardingUiState,
    onEvent: (OnboardingEvent) -> Unit,
) {
    if (uiState.isSaving) {
        Text(text = stringResource(R.string.onboarding_saving))
    }
    Row(horizontalArrangement = Arrangement.spacedBy(CfoDimens.spaceSm)) {
        if (uiState.canGoBack) {
            CfoSecondaryButton(
                text = stringResource(R.string.onboarding_back),
                onClick = { onEvent(OnboardingEvent.Back) },
            )
        }
        CfoButton(
            text = stringResource(if (uiState.step.isLast) R.string.onboarding_finish else R.string.onboarding_next),
            onClick = { onEvent(OnboardingEvent.Next) },
            enabled = !uiState.isSaving,
        )
    }
    if (uiState.step.isLast) {
        CfoSecondaryButton(
            text = stringResource(R.string.onboarding_skip),
            onClick = { onEvent(OnboardingEvent.SkipQuickSetup) },
            enabled = !uiState.isSaving,
        )
    }
}

/** Input: the welcome step. Output: a design-time preview of the flow's first face. */
@Preview(showBackground = true)
@Composable
private fun OnboardingWelcomePreview() {
    CfoTheme {
        OnboardingContent(uiState = OnboardingUiState(deviceZoneId = "Asia/Kolkata"), onEvent = {})
    }
}
