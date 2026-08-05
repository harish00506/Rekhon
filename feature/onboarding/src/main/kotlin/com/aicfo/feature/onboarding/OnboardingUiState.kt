package com.aicfo.feature.onboarding

import androidx.compose.runtime.Immutable
import com.aicfo.core.model.AccountType
import com.aicfo.domain.engines.quicksetup.QuickSetupPlan

/**
 * The steps of first-run onboarding (FR-ONB-001, FR-ONB-002, FR-ONB-003).
 *
 * Why:  an ordered enum rather than an `Int` means "which step am I on?" cannot hold a value that
 *       is not a step, and `entries` gives the progress indicator its total for free — so adding a
 *       step never leaves a screen saying "Step 4 of 3".
 * What: the closed, ordered set of steps.
 * Result: the flow's position, expressed as a type.
 * Changelog: 2026-07-25 — Created for issue 2.1.
 *            2026-07-26 — Issue 2.2 inserted [SECURITY].
 *            2026-07-28 — Issue 2.5 appended [ACCOUNT]. **FR-ONB-001 is now satisfied** — see
 *            `docs/adr/0002-onboarding-step-order.md`, which predicted both insertions and has been
 *            waiting on this one since.
 *
 * ADR-0002 records why issue 2.1 could not build the SRS's four steps literally: two of them
 * belonged to issues it did not depend on. Both have now landed where that record said they would.
 */
enum class OnboardingStep {
    /** Welcome and the privacy pledge (FR-ONB-001). */
    WELCOME,

    /** The SMS-parsing opt-in — separate, skippable, explained (FR-ONB-003, P-01). */
    CONSENT,

    /** Display name, currency and profile time zone (FR-ONB-001, TIM-001). */
    PROFILE,

    /**
     * The app lock: biometric/PIN (FR-ONB-001 step 3, SEC-002, issue 2.2).
     *
     * Placed after [PROFILE] exactly as ADR-0002 said it would be — the profile exists by then,
     * which is what a PIN is protecting. Skippable: SEC-002 describes the lock, it does not require
     * turning it on during first run.
     */
    SECURITY,

    /** The optional income / rent / savings seeds (FR-ONB-002). */
    QUICK_SETUP,

    /**
     * The first account and its opening balance (FR-ONB-001 step 4, SEC-002's sibling; issue 2.5).
     *
     * Placed **last**, exactly as ADR-0002 said it would be — it needed a repository and a schema
     * version that did not exist when issue 2.1 built this flow. Skippable, like quick setup:
     * leaving the name blank writes no account rather than inventing an empty one (P-03).
     */
    ACCOUNT,
    ;

    /** Whether this is the last step, i.e. its primary action finishes rather than advances. */
    val isLast: Boolean get() = ordinal == entries.lastIndex

    /**
     * Whether the step offers a Skip action (issue 2.5).
     *
     * Why: the two data steps are optional and the other four are not — the app cannot work without
     *      a time zone, and the welcome and consent steps ask for nothing to skip. Until issue 2.5
     *      this was expressed as `isLast`, which was true only by coincidence: quick setup happened
     *      to be last. Appending [ACCOUNT] would have moved Skip off quick setup entirely.
     */
    val isSkippable: Boolean get() = this == QUICK_SETUP || this == ACCOUNT
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
 * **The typed PIN lives here and nowhere else (issue 2.2).** Unlike every other answer in this
 * state, [pinText] and [pinConfirmText] are deliberately **not** written to `SavedStateHandle`:
 * that bundle is persisted by the platform, and a PIN sitting in it would be a plaintext credential
 * on disk — defeating the whole point of verifying it against a Keystore-bound MAC. Losing the
 * half-typed PIN to process death is the correct trade.
 *
 * Input:  [step]; [smsConsentGranted] — default **off**, because absence is never consent (P-01);
 *         [displayName], [currencyCode], [timeZoneId]; [deviceZoneId] — what the device reports,
 *         carried in state so the composable stays a pure function of it rather than reading the
 *         platform itself; [appLockEnabled], [pinText], [pinConfirmText] — the SEC-002 step;
 *         [monthlyIncomeText], [rentOrEmiText], [typicalSavingsText]; [accountName],
 *         [accountType], [accountOpeningBalanceText] — FR-ONB-001's fourth step, all optional; a
 *         blank [accountName] means the user skipped it and no account is written (issue 2.5);
 *         [quickSetupPlan] — what the
 *         engine derived from those three, recomputed on every keystroke and `null` until at least
 *         one of them parses (issue 2.3); [quickSetupSkipped] — the user pressed Skip on the
 *         quick-setup step, so its amounts are not written even if something was typed before
 *         (issue 2.5, which moved that step off the end of the flow); [isSaving];
 *         [isComplete]; [isDemoStarted] — the sample dataset is loaded and the screen should leave
 *         for the dashboard (issue 2.4). **Separate from [isComplete] on purpose:** onboarding was
 *         not completed, no profile exists, and conflating the two would mark a demo user onboarded;
 *         [errorCode] — an `AppError.code`, never a message, so the wording stays in
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
    val appLockEnabled: Boolean = false,
    val pinText: String = "",
    val pinConfirmText: String = "",
    val monthlyIncomeText: String = "",
    val rentOrEmiText: String = "",
    val typicalSavingsText: String = "",
    val quickSetupPlan: QuickSetupPlan? = null,
    val quickSetupSkipped: Boolean = false,
    val accountName: String = "",
    val accountType: AccountType = AccountType.BANK,
    val accountOpeningBalanceText: String = "",
    val isSaving: Boolean = false,
    val isComplete: Boolean = false,
    val isDemoStarted: Boolean = false,
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

    /**
     * Whether the typed PIN is one `TinkPinVerifier` would accept (issue 2.2, SEC-002).
     *
     * Only the length is checked here: `CfoPinField` has already filtered the input to ASCII digits,
     * and duplicating the digit rule in a second place is how the two drift apart.
     */
    val isPinWellFormed: Boolean get() = pinText.length in MIN_PIN_LENGTH..MAX_PIN_LENGTH

    /** Whether the confirmation matches — checked only once something has been typed into it. */
    val pinsMatch: Boolean get() = pinText == pinConfirmText

    /**
     * Whether the primary action may proceed from the current step.
     *
     * Why: every step but [OnboardingStep.SECURITY] is always advanceable, and that one is only
     *      blocked when the user has asked for a lock they have not yet given a usable PIN. Letting
     *      them through anyway would enable a lock with no credential — an app that cannot be opened
     *      at all, on the very first launch.
     */
    val canAdvance: Boolean
        get() =
            when {
                isSaving -> false
                step != OnboardingStep.SECURITY -> true
                !appLockEnabled -> true
                else -> isPinWellFormed && pinsMatch
            }
}

/** `TinkPinVerifier`'s floor. Repeated as a UI constant so the field can disable Next before a write. */
const val MIN_PIN_LENGTH: Int = 4

/** `TinkPinVerifier`'s ceiling, matching `CfoPinField`'s own cap. */
const val MAX_PIN_LENGTH: Int = 6

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
    /**
     * An edit to one of the user's answers, as opposed to a navigation or error action.
     *
     * Why: the two kinds behave completely differently — an answer is always just "copy the state
     *      with this field replaced", while Next, Back, Skip and Dismiss each do something. Naming
     *      that split in the type lets the ViewModel handle each kind in its own **exhaustive**
     *      `when`, instead of one long one that a new event could quietly fall out of.
     */
    sealed interface Answer : OnboardingEvent

    /** Advance to the next step, or finish if this is the last one. */
    data object Next : OnboardingEvent

    /** Go back to the previous step. */
    data object Back : OnboardingEvent

    /** The user turned the SMS-parsing opt-in on or off (FR-ONB-003). */
    data class SmsConsentChanged(
        val granted: Boolean,
    ) : Answer

    /** The user edited their display name. */
    data class DisplayNameChanged(
        val name: String,
    ) : Answer

    /** The user chose a currency (ISO-4217). */
    data class CurrencyChanged(
        val currencyCode: String,
    ) : Answer

    /** The user chose a profile time zone (IANA id) — TIM-001. */
    data class TimeZoneChanged(
        val zoneId: String,
    ) : Answer

    /** The user edited their monthly income (FR-ONB-002). */
    data class MonthlyIncomeChanged(
        val text: String,
    ) : Answer

    /** The user edited their rent or EMI (FR-ONB-002). */
    data class RentOrEmiChanged(
        val text: String,
    ) : Answer

    /** The user edited their typical savings (FR-ONB-002). */
    data class TypicalSavingsChanged(
        val text: String,
    ) : Answer

    /** The user named their first account (issue 2.5, FR-ONB-001 step 4). Blank means skipped. */
    data class AccountNameChanged(
        val name: String,
    ) : Answer

    /** The user picked their first account's type (issue 2.5, FR-ACC-001). */
    data class AccountTypeChanged(
        val type: AccountType,
    ) : Answer

    /** The user edited their first account's opening balance (issue 2.5). */
    data class AccountOpeningBalanceChanged(
        val text: String,
    ) : Answer

    /** The user turned the app lock on or off (issue 2.2, SEC-002, FR-ONB-001 step 3). */
    data class AppLockToggled(
        val enabled: Boolean,
    ) : Answer

    /** The user typed a PIN. Already filtered to digits by `CfoPinField`. */
    data class PinChanged(
        val pin: String,
    ) : Answer

    /** The user typed the confirmation PIN. */
    data class PinConfirmChanged(
        val pin: String,
    ) : Answer

    /** The user skipped the optional quick-setup step and finished without seeds. */
    data object SkipQuickSetup : OnboardingEvent

    /**
     * The user chose to explore the app on sample data instead (issue 2.4, FR-ONB-004).
     *
     * Not an [Answer]: it does not edit a field, it leaves the flow entirely — and it leaves it
     * without writing a profile, which is the whole point of the requirement.
     */
    data object StartDemo : OnboardingEvent

    /** The user dismissed the error banner. */
    data object DismissError : OnboardingEvent
}
