package com.aicfo.feature.settings

import androidx.compose.runtime.Immutable
import com.aicfo.core.crypto.BackupCipher
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
 *   2026-09-18 — Issue 8.1 added [backup].
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
 * @property backup the encrypted-backup form and where it is (issue 8.1).
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
    val backup: BackupUiState = BackupUiState(),
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

    /**
     * Whether the backup consent is on (P-01).
     *
     * Why: the backup section's button and its hint both read it, and the ledger is the only truth.
     */
    val backupConsented: Boolean get() = consents[ConsentFeature.CLOUD_BACKUP] == true

    /**
     * What still stands between the user and a backup, or `null` when nothing does.
     *
     * Why: a disabled button that does not say why is a dead end. One reason at a time, in the order
     *      the user meets them: consent first (P-01), then the passphrase, then the match, then the
     *      acknowledgement SEC-005 requires — so the hint always names the next thing to do.
     */
    val backupBlocker: BackupBlocker?
        get() =
            when {
                !backupConsented -> BackupBlocker.CONSENT
                backup.passphraseText.length < BackupCipher.MIN_PASSPHRASE_LENGTH -> BackupBlocker.TOO_SHORT
                backup.passphraseText != backup.confirmationText -> BackupBlocker.MISMATCH
                !backup.acknowledged -> BackupBlocker.NOT_ACKNOWLEDGED
                else -> null
            }

    /**
     * Whether "Create encrypted backup" may be tapped.
     *
     * Why: nothing blocks it and no backup is already being sealed — Argon2id takes a noticeable
     *      moment, and a second tap must not start a second one.
     */
    val canCreateBackup: Boolean get() = backupBlocker == null && backup.status != BackupStatus.Sealing

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

    /**
     * The encrypted backup's events (issue 8.1; SEC-005), grouped so the ViewModel routes them with
     * one branch and the backup flow reads as one unit.
     */
    sealed interface Backup : SettingsEvent

    /** The backup passphrase field changed. */
    data class BackupPassphraseChanged(val value: String) : Backup

    /** The "type it again" field changed. */
    data class BackupConfirmationChanged(val value: String) : Backup

    /** The user ticked or unticked "I understand a lost passphrase cannot be recovered". */
    data class BackupAcknowledged(val checked: Boolean) : Backup

    /** Seal the archive under the typed passphrase. */
    data object CreateBackup : Backup

    /** The system file picker finished: [written] is whether the bytes reached the chosen file. */
    data class BackupWritten(val written: Boolean) : Backup

    /** Dismiss the backup's result line, or back out of the picker. */
    data object BackupDismissed : Backup
}

/**
 * The encrypted-backup form (issue 8.1; SEC-005, P-01).
 *
 * Why:  its own value inside [SettingsUiState] rather than five more fields on it — the backup is a
 *       self-contained flow with its own lifecycle, and grouping it keeps "reset the form after a
 *       backup" one assignment rather than five.
 *
 *       **The passphrase is held as text only while it is being typed.** A text field needs a
 *       `String`; the moment the user taps create, the ViewModel copies it into a `CharArray` for
 *       the repository (which zero-fills it) and clears both fields here. It is never persisted.
 * What: the two passphrase fields, the acknowledgement, and where the backup is.
 * Result: every reachable backup state, constructible in a test.
 * Changelog: 2026-09-18 — Created for issue 8.1.
 *
 * @property passphraseText the passphrase as typed.
 * @property confirmationText the passphrase typed a second time.
 * @property acknowledged whether the user ticked that a lost passphrase means a lost backup.
 * @property status where the backup is.
 */
@Immutable
data class BackupUiState(
    val passphraseText: String = "",
    val confirmationText: String = "",
    val acknowledged: Boolean = false,
    val status: BackupStatus = BackupStatus.Idle,
)

/**
 * Where a backup is (issue 8.1).
 * Why:    a sealed type rather than flags, so "sealing" and "failed" cannot both be true.
 * Result: what the backup section renders under its button.
 * Changelog: 2026-09-18 — Created for issue 8.1.
 */
sealed interface BackupStatus {
    /** Nothing in flight. */
    data object Idle : BackupStatus

    /** Argon2id and AES-GCM are running. */
    data object Sealing : BackupStatus

    /**
     * Sealed, waiting for the user to pick where it goes.
     *
     * Why: a plain class, not a `data class` — a `ByteArray` has identity equality, and a data class
     *      over one would claim structural equality it does not have. Each sealed backup is a new
     *      instance, which is exactly what the screen's effect keys on.
     */
    class ReadyToWrite(val bytes: ByteArray) : BackupStatus

    /** The file was written. */
    data object Written : BackupStatus

    /** Something refused; [code] is an `AppError` code or field the screen maps to copy. */
    data class Failed(val code: String) : BackupStatus
}

/**
 * The one thing still stopping a backup (issue 8.1).
 * Why:    named reasons rather than one generic "cannot back up yet", so the hint tells the user what
 *         to do next.
 * Result: what the backup section's hint says.
 * Changelog: 2026-09-18 — Created for issue 8.1.
 */
enum class BackupBlocker {
    /** The backup consent is off (P-01). */
    CONSENT,

    /** The passphrase is under `BackupCipher.MIN_PASSPHRASE_LENGTH` characters. */
    TOO_SHORT,

    /** The two passphrase fields differ. */
    MISMATCH,

    /** SEC-005's irrecoverability has not been acknowledged. */
    NOT_ACKNOWLEDGED,
}
