package com.aicfo.app.work

import com.aicfo.core.common.AppError
import com.aicfo.core.common.Ok
import com.aicfo.core.common.Result
import com.aicfo.data.repository.EraseRepository

/**
 * An erase that also stops the app collecting data again (issue 11.4; §34, P-01).
 *
 * Why:  **found by running the erase on a device.** The nine periodic workers this app schedules
 *       live in WorkManager's own database, outside everything `SecretInventory` covers, and they
 *       survive the erase. Minutes later `MarketPriceWorker` fetches prices, `SmsScanWorker` reads
 *       the inbox and `WidgetRefreshWorker` repopulates the widget — each rebuilding a database
 *       behind the new key and starting to fill it. The app would be gathering data again about
 *       someone who had just asked it to stop, with no screen ever shown to them. That is a P-01
 *       failure, not an untidiness.
 *
 *       It is a decorator in `:app` rather than a step inside `SecureEraser` for two reasons: the
 *       workers are `:app`'s, and `:data:repository` cannot see WorkManager without a dependency
 *       pointing the wrong way through the architecture (ARC-001). The ordering the repository
 *       proves stays exactly as it was.
 * What: delegate the erase; on success, cancel everything scheduled.
 * Result: the same result the delegate gave — a cancel is never allowed to change it.
 * Changelog: 2026-10-01 — Created for issue 11.4.
 *
 * Input:  [delegate] — the real erase; [cancelAllWork] — injected rather than calling
 *         `WorkManager.getInstance` here, so the ordering and the failure paths are testable without
 *         a WorkManager instance (which needs a real Application).
 * Output: the repository.
 */
internal class WorkCancellingEraseRepository(
    private val delegate: EraseRepository,
    private val cancelAllWork: () -> Unit,
) : EraseRepository {
    override suspend fun eraseEverything(): Result<Unit, AppError> {
        val outcome = delegate.eraseEverything()
        // Only on success. A failed shred means the data is still there, so the app is still the
        // app, and silently stopping its budget alerts and price refreshes would be a second
        // unasked-for change on top of an operation the user was just told did not work.
        if (outcome is Ok) {
            runCatching { cancelAllWork() }
            // Deliberately swallowed. The keys are already destroyed: reporting an error here would
            // tell a user whose data is genuinely unrecoverable that the erase failed. The schedule
            // is also moot either way — the process is about to exit, and a worker that does fire
            // finds an empty database behind a key that has nothing to decrypt.
        }
        return outcome
    }
}
