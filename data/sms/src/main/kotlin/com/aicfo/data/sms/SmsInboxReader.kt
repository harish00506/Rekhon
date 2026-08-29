package com.aicfo.data.sms

import android.content.Context
import com.aicfo.core.common.AppError
import com.aicfo.core.common.DispatcherProvider
import com.aicfo.core.common.Result
import com.aicfo.core.model.SmsMessage

/**
 * Reads bank alerts out of the phone's inbox (issue 3.9; SRS §18, §23, P-01).
 *
 * Why:  the one place in this app that can touch the user's messages, and it is an interface so
 *       that fact is checkable rather than asserted: everything above it depends on this contract,
 *       so a reviewer establishes what the app can do with an inbox by reading one file, and a test
 *       proves the consent gate holds by substituting a reader that fails if it is ever called.
 *
 *       **It reads and returns; it decides nothing.** No parsing, no filtering by sender, no
 *       judgement about which message is a transaction — all of that is `:domain:engines:sms`,
 *       which is pure Kotlin and provable in CI. That split is the same one `ReceiptTextRecognizer`
 *       makes against the receipt parser, and it exists so the interesting half of this feature is
 *       not trapped behind a device and a restricted permission.
 * What: one method, from a cursor position to the messages after it.
 * Result: the input to the parser. Nothing here is persisted and nothing leaves the device (P-01) —
 *       there is no network dependency in this module that could carry a message anywhere.
 * Changelog: 2026-08-07 — Created for issue 3.9.
 *
 * One public interface with an `internal` implementation behind [SmsInboxReaderFactory], the
 * ARC-003 seam every engine and recogniser in this codebase already uses.
 */
interface SmsInboxReader {
    /**
     * Whether the OS permission to read the inbox is currently granted.
     *
     * Why:    the caller needs to distinguish "the user has not granted READ_SMS" from "the query
     *         failed", because those are different screens: one asks for the permission and the
     *         other says something went wrong. Discovering it by running a query and classifying the
     *         exception would mean the ordinary, expected state — a user who simply has not granted
     *         it — arrived as an error, and it would touch the provider to find out.
     *
     *         It lives on this interface rather than in the repository because **this module owns
     *         the permission**: it is declared in this module's manifest, so the constant that names
     *         it should not be repeated in a layer that cannot see the declaration.
     *
     *         This is a snapshot, not a guarantee. The user can revoke from Settings between this
     *         call and the next query, which is why [readSince] still has to survive being refused.
     * Result: `true` when [readSince] can be expected to work.
     * Input:  none. Output: `Boolean`.
     */
    fun canRead(): Boolean

    /**
     * Reads every inbox message newer than a cursor.
     *
     * Why:    a **cursor rather than a date window**, because the identity of "already seen" has to
     *         survive a phone whose clock moved, a message that arrived out of order, and a restore
     *         onto a new device. `Telephony.Sms._ID` increases monotonically per inbox, so "greater
     *         than the highest id I have read" is both cheap in SQL and exactly the question being
     *         asked. A date window would re-read or skip messages at the boundary, and re-reading is
     *         not free here: it is the user's private data being touched again for nothing.
     *
     *         A `Result` rather than a throw, matching every other boundary (§21.6). The realistic
     *         failure is the one that matters most: **the permission was revoked between the check
     *         and the query** — the user can do that from Settings at any moment, and mid-scan is a
     *         perfectly ordinary time for it. That must leave the app on a screen explaining the
     *         feature is off, not on a crash.
     * Result: `Ok(messages)` oldest-first, so the caller can advance its cursor by taking the last
     *         id and a partial failure never moves the cursor past a message that was not processed.
     *         Empty is the ordinary answer for a phone with no new alerts. `Err` when the provider
     *         refused the query — `AppError.Unexpected("SecurityException")` for a permission
     *         revoked mid-scan, which [canRead] exists so the caller rarely has to meet.
     * Input:  [afterId] — the highest `_ID` already read; `0` reads the whole inbox, which is what
     *         the first scan after the consent is granted does; [limit] — the most messages to
     *         return in one call, so a first scan on a phone with ten years of messages cannot load
     *         them all into memory at once.
     * Output: `Result<List<SmsMessage>, AppError>`.
     */
    suspend fun readSince(
        afterId: Long,
        limit: Int,
    ): Result<List<SmsMessage>, AppError>
}

/**
 * Builds the reader for the DI graph (ARC-003).
 * Why:    the implementation is `internal`, so `:app`'s Hilt module cannot name it — the same seam
 *         [com.aicfo.core.model.SmsMessage]'s other consumers use, kept deliberately identical so
 *         the codebase has one pattern rather than several.
 * Result: an [SmsInboxReader].
 * Input:  [context] — the application context, for its `ContentResolver`; [dispatchers] — so the
 *         cursor walk lands on IO rather than on the caller's thread (ARC-006).
 * Output: the reader.
 * Changelog: 2026-08-07 — Created for issue 3.9.
 */
object SmsInboxReaderFactory {
    /** Result: the production reader. Input: [context], [dispatchers]. Output: [SmsInboxReader]. */
    fun create(
        context: Context,
        dispatchers: DispatcherProvider,
    ): SmsInboxReader = ContentResolverSmsInboxReader(context, dispatchers)
}
