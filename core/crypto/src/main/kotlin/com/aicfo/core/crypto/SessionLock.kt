package com.aicfo.core.crypto

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Whether this session has been unlocked (issue 2.2; SEC-002, SEC-001 in part).
 *
 * Why:  the lock screen is a composable, and a composable is not a security boundary — it can be
 *       skipped by a deep link, a restored back stack, or simply by a future screen being wired in
 *       above it. This is the one piece of state that says "the owner has proved who they are this
 *       session", and it is what the encrypted database's provider checks before opening the file.
 *       Without it, "the lock gates the encrypted store" would be a sentence in a document rather
 *       than something the build can fail on.
 * What: an app-scoped, observable boolean, plus the two transitions that change it.
 * Result: a single answer the DI graph, the nav gate and any future background job all read.
 * Changelog: 2026-07-26 — Created for issue 2.2.
 *
 * **Starts locked (`false`) and can only be opened by [unlock].** Fail-secure is the default rather
 * than a code path, so forgetting to lock somewhere cannot expose data — the failure mode of a bug
 * here is an app that will not open, which is recoverable, rather than one that opens for anyone,
 * which is not.
 *
 * **When the app lock is disabled** the gate is opened once at startup by the same code that reads
 * the setting. "Unlocked" here means "permitted to read data", not "the user typed a PIN".
 */
class SessionLock {
    private val _isUnlocked = MutableStateFlow(false)

    /**
     * Whether data access is currently permitted.
     * Result: `false` until something calls [unlock]; back to `false` after [lock].
     */
    val isUnlocked: StateFlow<Boolean> = _isUnlocked.asStateFlow()

    /**
     * Opens the gate for this session.
     * Why:    called after a successful biometric or PIN unlock, and at startup when the app lock
     *         is switched off. It is the only way this becomes `true`.
     * Result: [isUnlocked] emits `true`.
     * Input:  none. Output: none.
     * Changelog: 2026-07-26 — Created for issue 2.2.
     */
    fun unlock() {
        _isUnlocked.value = true
    }

    /**
     * Closes the gate.
     * Why:    the idle timeout and an explicit lock action. Idempotent, so a re-lock that races
     *         another re-lock is harmless.
     * Result: [isUnlocked] emits `false`.
     * Input:  none. Output: none.
     * Changelog: 2026-07-26 — Created for issue 2.2.
     */
    fun lock() {
        _isUnlocked.value = false
    }
}
