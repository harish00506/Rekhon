package com.aicfo.data.sms

import android.content.Context
import android.content.pm.PackageManager
import android.database.Cursor
import android.provider.Telephony
import com.aicfo.core.common.AppError
import com.aicfo.core.common.DispatcherProvider
import com.aicfo.core.common.Result
import com.aicfo.core.common.runCatchingToResult
import com.aicfo.core.model.SmsMessage
import kotlinx.coroutines.withContext

/**
 * The production [SmsInboxReader] — one query, four columns (issue 3.9; §18, §23, P-01).
 *
 * Why:  everything about this class is an argument for reading **less**. It queries the inbox only
 *       (`Telephony.Sms.Inbox`, never `Sent` or the whole `Sms` table), asks for four columns rather
 *       than `null` — which would select every column the provider has, including the thread, the
 *       contact and the read state, none of which this app has any business seeing — and filters in
 *       SQL rather than in Kotlin so messages the app is not interested in are never copied into its
 *       process at all.
 *
 *       That last point is the one worth defending. Selecting everything and filtering afterwards
 *       would be simpler to write and would produce identical output; the difference is that the
 *       user's entire message history would pass through this app's memory on every scan. For a
 *       feature whose whole justification is P-01, "the result is the same" is not the standard.
 * What: `_ID > ?` on the inbox, oldest first, limited.
 * Result: the messages the parser has not seen. Nothing is stored, nothing is interpreted.
 * Changelog: 2026-08-07 — Created for issue 3.9.
 *
 * `internal` per ARC-003 — constructed only by [SmsInboxReaderFactory].
 *
 * **No message body is ever logged** (P-01, `CfoPiiInLogs`). There is not a `Log` call in this file,
 * and a failure returns [AppError.Storage], which carries an exception's class name and nothing else.
 */
internal class ContentResolverSmsInboxReader(
    private val context: Context,
    private val dispatchers: DispatcherProvider,
) : SmsInboxReader {
    override fun canRead(): Boolean =
        context.checkSelfPermission(android.Manifest.permission.READ_SMS) == PackageManager.PERMISSION_GRANTED

    override suspend fun readSince(
        afterId: Long,
        limit: Int,
    ): Result<List<SmsMessage>, AppError> =
        withContext(dispatchers.io) {
            runCatchingToResult {
                // A revoked permission surfaces here as a SecurityException from the provider, which
                // `runCatchingToResult` classifies rather than lets escape — the user can revoke
                // from Settings mid-scan and that must not be a crash. A null cursor means the
                // provider declined to answer, which is the same situation with less information.
                context.contentResolver.query(
                    Telephony.Sms.Inbox.CONTENT_URI,
                    COLUMNS,
                    "${Telephony.Sms._ID} > ?",
                    arrayOf(afterId.toString()),
                    // Sorted ascending so the caller can advance its cursor to the last id it
                    // actually processed. Descending would mean a partial batch left a gap that no
                    // later scan would ever revisit, and the missed alerts would be invisible.
                    "${Telephony.Sms._ID} ASC LIMIT $limit",
                )?.use { it.readMessages() }.orEmpty()
            }
        }

    private companion object {
        /**
         * The four columns this app reads, and no others.
         *
         * Passing `null` here is the documented way to select every column, and it is exactly what
         * this feature must not do — see the class doc.
         */
        val COLUMNS =
            arrayOf(
                Telephony.Sms._ID,
                Telephony.Sms.ADDRESS,
                Telephony.Sms.BODY,
                Telephony.Sms.DATE,
            )
    }
}

/**
 * Walks a cursor into messages (issue 3.9).
 * Why:    split from the query so the resource handling (`use`) and the row mapping are each one
 *         readable thing, and so the column indices are resolved **once** rather than per row —
 *         `getColumnIndexOrThrow` is a string comparison over the column list, and a first scan
 *         walks the user's whole inbox.
 *
 *         Throwing rather than defaulting on a missing column is deliberate: a provider without
 *         `body` is not a provider this code can read, and quietly mapping every message to an empty
 *         string would produce a scan that found nothing and reported success.
 * Result: the rows as [SmsMessage]s, in cursor order. A row with a null address or body is skipped
 *         rather than defaulted — both are nullable in the provider's schema, and a message with no
 *         text is not one the parser can judge.
 * Input:  the receiver — a cursor positioned before the first row. Output: `List<SmsMessage>`.
 * Changelog: 2026-08-07 — Created for issue 3.9.
 */
private fun Cursor.readMessages(): List<SmsMessage> {
    val idColumn = getColumnIndexOrThrow(Telephony.Sms._ID)
    val addressColumn = getColumnIndexOrThrow(Telephony.Sms.ADDRESS)
    val bodyColumn = getColumnIndexOrThrow(Telephony.Sms.BODY)
    val dateColumn = getColumnIndexOrThrow(Telephony.Sms.DATE)
    val messages = mutableListOf<SmsMessage>()
    while (moveToNext()) {
        val address = getStringOrNull(addressColumn)
        val body = getStringOrNull(bodyColumn)
        if (address != null && body != null) {
            messages +=
                SmsMessage(
                    id = getLong(idColumn),
                    sender = address,
                    body = body,
                    // coerceAtLeast rather than a require: the provider's DATE is whatever the
                    // network stamped, and a phone that has seen a bad timestamp should lose one
                    // message's date, not fail the whole scan on SmsMessage's constructor check.
                    receivedAtUtcMillis = getLong(dateColumn).coerceAtLeast(0L),
                )
        }
    }
    return messages
}

/**
 * Reads a nullable string column.
 * Why:    `Cursor.getString` on a null column returns `null` but is typed as non-null in Kotlin's
 *         view of the platform, so an unchecked call would produce a `SmsMessage` holding a `null`
 *         the type system promised could not exist — a platform-type trap §21.6 says to isolate at
 *         exactly this edge.
 * Result: the value, or `null`. Input: the receiver; [column] — the resolved index. Output: `String?`.
 * Changelog: 2026-08-07 — Created for issue 3.9.
 */
private fun Cursor.getStringOrNull(column: Int): String? = if (isNull(column)) null else getString(column)
