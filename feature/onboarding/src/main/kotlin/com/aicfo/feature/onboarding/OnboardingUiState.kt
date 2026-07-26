package com.aicfo.feature.onboarding

import androidx.compose.runtime.Immutable

/**
 * The four steps of first-run onboarding (FR-ONB-001, FR-ONB-002, FR-ONB-003).
 *
 * Why:  an ordered enum rather than an `Int` means "which step am I on?" cannot hold a value that
 *       is not a step, and `entries` gives the progress indicator its total for free — so adding a
 *       step never leaves a screen saying "Step 4 of 3".
 * What: the closed, ordered set of steps.
 * Result: the flow's position, expressed as a type.
 * Changelog: 2026-07-25 — Created for issue 2.1.
 *
 * The SRS's own step 3 (security) and step 4 (first account) belong to issues 2.2 and 2.5, which
 * this issue does not depend on and which therefore cannot be built yet. The ordering here is the
 * one the issue and tracker specify; the deviation is recorded in
 * `docs/adr/0002-onboarding-step-order.md`, which also says where 2.2 and 2.5 insert their steps.
 */
enum class OnboardingStep {
    /** Welcome and the privacy pledge (FR-ONB-001). */
    WELCOME,

    /** The SMS-parsing opt-in — separate, skippable, explained (FR-ONB-003, P-01). */
    CONSENT,

    /** Display name, currency and profile time zone (FR-ONB-001, TIM-001). */
    PROFILE,

    /** The optional income / rent / savings seeds (FR-ONB-002). */
    QUICK_SETUP,
    ;

    /** Whether this is the last step, i.e. its primary action finishes rather than advances. */
    val isLast: Boolean get() = ordinal == entries.lastIndex
}

/**
 * Everything the onboarding flow renders, as one value (ARC-004).
 *
 * Why:  §21.2 requires one immutable state class per screen exposed as a `StateFlow`. Here it also
 *       carries the answers themselves, because the whole flow is one screen with four faces — a
 *       state per step would let the user's currency choice go missing when they step back.
 * What: the current step, every captured answer, and the outcome of finishing.
 * Result: every state the flow can be in is nameable in a test.
 * Changelog: 2026-07-25 — Created for issue 2.1.
 *
 * Amounts stay as **text** here rather than `Money`, because that is what the user is mid-way
 * through typing: `"1."` is a legitimate thing to have on screen and not an amount yet. They are
 * converted once, at Finish, by `MoneyFormatter.parse` (MNY-001) — the screen never does money math.
 *
 * Input:  [step]; [smsConsentGranted] — default **off**, because absence is never consent (P-01);
 *         [displayName], [currencyCode], [timeZoneId]; [deviceZoneId] — what the device reports,
 *         carried in state so the composable stays a pure function of it rather than reading the
 *         platform itself; [monthlyIncomeText], [rentOrEmiText], [typicalSavingsText]; [isSaving];
 *         [isComplete]; [errorCode] — an `AppError.code`, never a message, so the wording stays in
 *         `strings.xml` (§21.6).
 * Output: an immutable snapshot for the composable.
 */
@Immutable
data class OnboardingUiState(
    val step: OnboardingStep = OnboardingStep.WELCOME,
    val smsConsentGranted: Boolean = false,
    val displayName: String = "",
    val currencyCode: String = DEFAULT_CURRENCY_CODE,
    val timeZoneId: String = "",
    val deviceZoneId: String = "",
    val monthlyIncomeText: String = "",
    val rentOrEmiText: String = "",
    val typicalSavingsText: String = "",
    val isSaving: Boolean = false,
    val isComplete: Boolean = false,
    val errorCode: String? = null,
) {
    /** Human-facing step number, 1-based — `entries` supplies the total (see [OnboardingStep]). */
    val stepNumber: Int get() = step.ordinal + 1

    /** How many steps there are, so the indicator cannot disagree with the enum. */
    val stepCount: Int get() = OnboardingStep.entries.size

    /** Whether the Back action applies. The first step has nowhere to go back to. */
    val canGoBack: Boolean get() = step.ordinal > 0 && !isSaving

    /** The time zones to offer, the device's own first (see [OnboardingOptions]). */
    val timeZoneOptions: List<String> get() = OnboardingOptions.zoneIds(deviceZoneId)
}

/** ISO-4217 for the Indian rupee. v1 is India-only (P-06); `MoneyFormatter` renders ₹. */
const val DEFAULT_CURRENCY_CODE: String = "INR"

/**
 * Everything the user can do during onboarding (ARC-004).
 *
 * Why:    events flow **up** through a sealed interface, so the composables stay functions of state
 *         and the compiler lists every interaction the ViewModel must handle. A lambda per action
 *         would let a new field be added with nothing wired to it and no build error.
 * Result: the flow's complete input surface.
 * Changelog: 2026-07-25 — Created for issue 2.1.
 */
sealed interface OnboardingEvent {
    /** Advance to the next step, or finish if this is the last one. */
    data object Next : OnboardingEvent

    /** Go back to the previous step. */
    data object Back : OnboardingEvent

    /** The user turned the SMS-parsing opt-in on or off (FR-ONB-003). */
    data class SmsConsentChanged(
        val granted: Boolean,
    ) : OnboardingEvent

    /** The user edited their display name. */
    data class DisplayNameChanged(
        val name: String,
    ) : OnboardingEvent

    /** The user chose a currency (ISO-4217). */
    data class CurrencyChanged(
        val currencyCode: String,
    ) : OnboardingEvent

    /** The user chose a profile time zone (IANA id) — TIM-001. */
    data class TimeZoneChanged(
        val zoneId: String,
    ) : OnboardingEvent

    /** The user edited their monthly income (FR-ONB-002). */
    data class MonthlyIncomeChanged(
        val text: String,
    ) : OnboardingEvent

    /** The user edited their rent or EMI (FR-ONB-002). */
    data class RentOrEmiChanged(
        val text: String,
    ) : OnboardingEvent

    /** The user edited their typical savings (FR-ONB-002). */
    data class TypicalSavingsChanged(
        val text: String,
    ) : OnboardingEvent

    /** The user skipped the optional quick-setup step and finished without seeds. */
    data object SkipQuickSetup : OnboardingEvent

    /** The user dismissed the error banner. */
    data object DismissError : OnboardingEvent
}
