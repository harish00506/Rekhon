package com.aicfo.feature.settings

/**
 * What the erase-everything screen shows (issue 11.4; §23, §34, SEC-003, P-01).
 *
 * Why:  this is the only screen in the app that destroys data with no way back, so its state has
 *       to make the gates **explicit** rather than implicit in the composition. The question a
 *       reader has to be able to answer from one place is "what, exactly, has the user done so far
 *       that entitles them to press the button?" — and the answer is a typed word and, when the
 *       device has one, a PIN. [canErase] is derived here, once, rather than re-decided by the
 *       button.
 * What: the typed confirmation, the PIN, where the flow has reached, and any failure.
 * Result: the screen is a pure function of this, and the gating is unit-testable (ARC-004).
 * Changelog: 2026-10-01 — Created for issue 11.4.
 *
 * Input:  [confirmationText] — what the user typed, compared against the localised word the screen
 *         shows them; [confirmationWord] — that word, passed in from resources so the gate is
 *         testable without a Context and so a Hindi user confirms in Hindi; [pinText] — the PIN
 *         they are proving themselves with; [isPinRequired] — false when no PIN is set, because a
 *         device with no credential has no secret to prove and demanding one would lock a user out
 *         of erasing their own data (ADR-0060); [isErasing] — the irreversible work is running;
 *         [isErased] — it finished, and nothing on this device can be opened again; [errorCode] —
 *         a failed erase, which is the one failure the user must be told about plainly.
 * Output: an immutable value.
 */
data class EraseUiState(
    val confirmationText: String = "",
    val confirmationWord: String = "",
    val pinText: String = "",
    val isPinRequired: Boolean = false,
    val isErasing: Boolean = false,
    val isErased: Boolean = false,
    val errorCode: String? = null,
) {
    /**
     * Whether the erase may begin.
     * Why:    every gate in one expression, so "we added a gate and forgot to enforce it" is a
     *         compile-time-visible change to one line rather than a diff spread over a screen. The
     *         word is compared case-insensitively and trimmed: this is a deliberate speed bump
     *         against a mis-tap, not a password, and failing a user over a trailing space would
     *         make it feel like one.
     * Result: `true` only when the word matches and, where a PIN exists, one has been typed. The
     *         PIN is *checked* by the verifier, not here — a four-digit length test is not an
     *         authentication and must not look like one.
     * Input:  none. Output: [Boolean].
     * Changelog: 2026-10-01 — Created for issue 11.4.
     */
    val canErase: Boolean
        get() =
            !isErasing &&
                !isErased &&
                confirmationWord.isNotBlank() &&
                confirmationText.trim().equals(confirmationWord, ignoreCase = true) &&
                (!isPinRequired || pinText.isNotBlank())
}

/**
 * What the user can do on the erase screen (issue 11.4; ARC-004).
 *
 * Why:    no `Toggled`, no generic `FieldChanged`. The two text fields mean entirely different
 *         things — one is a confirmation of intent, the other is authentication — and a single
 *         event carrying a field name would let a future refactor send a PIN into the confirmation
 *         and still typecheck.
 * Changelog: 2026-10-01 — Created for issue 11.4.
 */
sealed interface EraseEvent {
    /** The confirmation word, as typed so far. */
    data class ConfirmationTyped(val text: String) : EraseEvent

    /** The PIN, as typed so far. Never logged, never put in the state's `toString` by anything. */
    data class PinTyped(val pin: String) : EraseEvent

    /** Do it. Only honoured when [EraseUiState.canErase]; the view model re-checks. */
    data object Confirmed : EraseEvent

    /** Dismiss the error banner. */
    data object DismissError : EraseEvent
}
