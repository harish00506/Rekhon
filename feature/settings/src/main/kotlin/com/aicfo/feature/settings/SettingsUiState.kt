package com.aicfo.feature.settings

import androidx.compose.runtime.Immutable
import com.aicfo.core.datastore.ConsentFeature

/**
 * Everything the settings screen shows, in one immutable value (FR-SET-001; ARC-004).
 *
 * Why:  one state class per screen as a `StateFlow`, for the reason every other screen here gives —
 *       every reachable state is constructible in a test, and there is no second source of truth
 *       for the screen to disagree with.
 *
 *       **The three amounts are held as text, not `Money`.** A half-typed amount is not a number
 *       yet; parsing happens once, on save, where a failure can be reported as a field error rather
 *       than swallowed as a zero. That is the convention the account and holding editors set.
 * What: the money seeds as typed, the consent ledger, the lock's state, and the flags around them.
 * Result: a screen whose every state is assertable without a device.
 * Changelog: 2026-08-29 — Created for FR-SET-001.
 *
 * @property monthlyIncomeText the monthly income as typed, in rupees; blank means not supplied.
 * @property rentOrEmiText the rent or EMI as typed; blank means not supplied.
 * @property typicalSavingsText what the user usually saves, as typed; blank means not supplied.
 * @property consents every per-feature consent and whether it is currently granted (P-01).
 * @property appLockEnabled whether the app lock is on.
 * @property pinText the PIN being set, when the user is turning the lock on; never persisted here.
 * @property isLoading whether the first read has landed.
 * @property errorCode a failure to show in the banner, or `null`.
 * @property fieldError which field the last save rejected, or `null`.
 * @property savedAtLeastOnce whether a save has succeeded, so the screen can confirm it.
 */
@Immutable
data class SettingsUiState(
    val monthlyIncomeText: String = "",
    val rentOrEmiText: String = "",
    val typicalSavingsText: String = "",
    val consents: Map<ConsentFeature, Boolean> = emptyMap(),
    val appLockEnabled: Boolean = false,
    val pinText: String = "",
    val isLoading: Boolean = true,
    val errorCode: String? = null,
    val fieldError: String? = null,
    val savedAtLeastOnce: Boolean = false,
) {
    /**
     * Whether the money plan can be saved.
     *
     * Why: an income of nothing is not a plan — the engine has no basis to split, and writing three
     *      blank seeds would replace a good plan with an empty one. Rent and savings are genuinely
     *      optional, so only the income gates the button.
     */
    val canSaveMoney: Boolean get() = monthlyIncomeText.isNotBlank()

    /**
     * Whether the lock toggle can be turned on.
     *
     * Why: enabling writes a PIN *before* it flips the flag (SEC-002, the ordering `AppLockSetup`
     *      records), so there has to be a PIN to write. Turning the lock **off** needs nothing.
     */
    val canEnableLock: Boolean get() = pinText.length >= MIN_PIN_LENGTH

    companion object {
        /** The shortest PIN the screen will offer to set. Four digits is the platform convention. */
        const val MIN_PIN_LENGTH = 4
    }
}

/**
 * What the user can do on the settings screen (FR-SET-001; ARC-004).
 *
 * Why:  events up through one sealed interface rather than a lambda per control, so the ViewModel's
 *       surface is one function and a new control cannot quietly bypass it.
 * What: the edits, the two saves, and the consent and lock toggles.
 * Result: what `SettingsViewModel.onEvent` switches on.
 * Changelog: 2026-08-29 — Created for FR-SET-001.
 */
sealed interface SettingsEvent {
    /** The monthly income field changed. */
    data class MonthlyIncomeChanged(val value: String) : SettingsEvent

    /** The rent-or-EMI field changed. */
    data class RentOrEmiChanged(val value: String) : SettingsEvent

    /** The typical-savings field changed. */
    data class TypicalSavingsChanged(val value: String) : SettingsEvent

    /** Save the money plan: store the seeds and re-derive the envelopes from them. */
    data object SaveMoneyPlan : SettingsEvent

    /** Grant or revoke one consent (P-01 — revocable is the whole point). */
    data class ConsentToggled(val feature: ConsentFeature, val granted: Boolean) : SettingsEvent

    /** The PIN field changed, while turning the lock on. */
    data class PinChanged(val value: String) : SettingsEvent

    /** Turn the app lock on (with the typed PIN) or off. */
    data class AppLockToggled(val enabled: Boolean) : SettingsEvent

    /** Dismiss the error banner. */
    data object DismissError : SettingsEvent
}
