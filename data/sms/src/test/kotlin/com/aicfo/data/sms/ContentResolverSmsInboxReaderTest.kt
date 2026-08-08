package com.aicfo.data.sms

import android.Manifest
import android.app.Application
import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.provider.Telephony
import androidx.test.core.app.ApplicationProvider
import com.aicfo.core.common.AppError
import com.aicfo.core.common.Err
import com.aicfo.core.common.Ok
import com.aicfo.core.common.TestDispatchers
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows

/**
 * Proves the inbox reader asks for as little as possible, and copes when it is refused (issue 3.9).
 *
 * Why:  two different kinds of assertion live here and both matter. The **behavioural** ones — rows
 *       map to messages, a partial row is skipped — are ordinary. The **restraint** ones are the
 *       point of the module: that the query names four columns rather than passing `null`, that it
 *       reads the inbox rather than the whole `sms` table, and that it filters in SQL. Those are
 *       privacy properties (P-01), and a privacy property nobody tests is a comment.
 *
 *       The permission case is not an edge case either. The user can revoke READ_SMS from Settings
 *       at any moment, including mid-scan, and the provider answers that with a `SecurityException`.
 *       Letting it escape would crash the app for doing exactly what the user asked.
 * What: a fake `sms` provider that records what it was asked for and can refuse.
 * Result: the reader's contract is pinned against a real `ContentResolver`.
 * Changelog: 2026-08-07 — Created for issue 3.9.
 *
 * Robolectric, because a `ContentResolver` needs a `Context`; the provider is ours, so no real
 * message is involved and none could be — a JVM test has no inbox.
 */
@RunWith(RobolectricTestRunner::class)
class ContentResolverSmsInboxReaderTest {
    private lateinit var provider: FakeSmsProvider
    private lateinit var reader: SmsInboxReader

    @Before
    fun setUp() {
        provider =
            Robolectric.buildContentProvider(FakeSmsProvider::class.java)
                .create(AUTHORITY)
                .get()
        // Unconfined rather than a scheduler-bound dispatcher: nothing here is about timing — the
        // reader does one query and returns — so there is no virtual clock worth owning, and an
        // unconfined dispatcher keeps the reader constructible outside `runTest`.
        reader =
            SmsInboxReaderFactory.create(
                ApplicationProvider.getApplicationContext(),
                TestDispatchers(UnconfinedTestDispatcher()),
            )
    }

    // --- restraint ----------------------------------------------------------------------------

    @Test
    fun `it asks for four columns, never for everything`() =
        runTest {
            reader.readSince(afterId = 0L, limit = 50)

            // `null` here would select every column the provider has — the thread, the contact, the
            // read state. The app has no business seeing any of it.
            assertEquals(
                listOf(Telephony.Sms._ID, Telephony.Sms.ADDRESS, Telephony.Sms.BODY, Telephony.Sms.DATE),
                provider.projection?.toList(),
            )
        }

    @Test
    fun `it reads the inbox, not the whole message store`() =
        runTest {
            reader.readSince(afterId = 0L, limit = 50)

            assertEquals(Telephony.Sms.Inbox.CONTENT_URI, provider.uri)
        }

    @Test
    fun `it filters in SQL so unseen messages never enter the process`() =
        runTest {
            reader.readSince(afterId = 41L, limit = 50)

            assertEquals("${Telephony.Sms._ID} > ?", provider.selection)
            assertEquals(listOf("41"), provider.selectionArgs?.toList())
        }

    @Test
    fun `it reads oldest first and bounds the batch`() =
        runTest {
            reader.readSince(afterId = 0L, limit = 25)

            // Ascending, so a partial batch leaves the cursor on the last message actually
            // processed rather than skipping the ones in between for ever.
            assertEquals("${Telephony.Sms._ID} ASC LIMIT 25", provider.sortOrder)
        }

    // --- mapping ------------------------------------------------------------------------------

    @Test
    fun `rows become messages`() =
        runTest {
            provider.rows = listOf(row(7L, "VM-HDFCBK", "Rs.100 debited from A/c XX1", 1_754_500_000_000L))

            val messages = (reader.readSince(0L, 50) as Ok).value

            assertEquals(1, messages.size)
            assertEquals(7L, messages.single().id)
            assertEquals("VM-HDFCBK", messages.single().sender)
            assertEquals("Rs.100 debited from A/c XX1", messages.single().body)
            assertEquals(1_754_500_000_000L, messages.single().receivedAtUtcMillis)
        }

    @Test
    fun `a row with no body is skipped rather than defaulted to empty`() =
        runTest {
            provider.rows =
                listOf(
                    row(7L, "VM-HDFCBK", null, 1_754_500_000_000L),
                    row(8L, null, "orphaned text", 1_754_500_000_000L),
                    row(9L, "AD-ICICIB", "Rs.50 credited to A/c XX2", 1_754_500_000_000L),
                )

            val messages = (reader.readSince(0L, 50) as Ok).value

            // An empty-bodied message would be judged by the parser and refused anyway — but it
            // would also advance the cursor past a row nothing ever looked at.
            assertEquals(listOf(9L), messages.map { it.id })
        }

    @Test
    fun `a nonsense timestamp costs one message its date, not the whole scan`() =
        runTest {
            provider.rows = listOf(row(7L, "VM-HDFCBK", "Rs.100 debited from A/c XX1", -1L))

            val messages = (reader.readSince(0L, 50) as Ok).value

            // SmsMessage refuses a negative instant; coercing here keeps that guard meaningful
            // without letting one bad row throw out of the reader.
            assertEquals(0L, messages.single().receivedAtUtcMillis)
        }

    @Test
    fun `an empty inbox is an ordinary answer`() =
        runTest {
            assertEquals(emptyList<Any>(), (reader.readSince(0L, 50) as Ok).value)
        }

    // --- refusal ------------------------------------------------------------------------------

    @Test
    fun `a permission revoked mid-scan is an error, not a crash`() =
        runTest {
            provider.refuse = true

            val result = reader.readSince(0L, 50)

            assertTrue("a revoked permission must not escape as an exception", result is Err)
        }

    @Test
    fun `an error carries no message content`() =
        runTest {
            provider.refuse = true

            val error = (reader.readSince(0L, 50) as Err).error

            // P-01: the error holds an exception's **class name** and nothing else — `toAppError`
            // discards `Throwable.message`, which for a provider refusal reads "Permission Denial:
            // reading SmsProvider from pid=… uid=…". If this ever starts carrying that message, a
            // provider whose refusal quoted a row would put message content into a log.
            assertEquals("SecurityException", (error as AppError.Unexpected).cause)
        }

    // --- the permission itself ----------------------------------------------------------------

    @Test
    fun `a granted permission is reported as readable`() =
        runTest {
            Shadows.shadowOf(ApplicationProvider.getApplicationContext<Application>())
                .grantPermissions(Manifest.permission.READ_SMS)

            assertTrue(reader.canRead())
        }

    @Test
    fun `a permission that was never granted is reported without touching the provider`() =
        runTest {
            // Not granted is the *default* state — Robolectric does not grant a manifest permission
            // just because it is declared, which is exactly how a real install behaves for a
            // dangerous permission. So this asserts the shipping default, not a contrived one.
            assertFalse(reader.canRead())
            // The whole point of `canRead`: the ordinary "not granted yet" state must be knowable
            // without a query, so the app never touches the inbox to discover it cannot.
            assertNull("canRead must not query the provider", provider.uri)
        }

    // --- helpers ------------------------------------------------------------------------------

    /** Result: one provider row. Input: the four column values. Output: `Array<Any?>`. */
    private fun row(
        id: Long,
        address: String?,
        body: String?,
        date: Long,
    ): Array<Any?> = arrayOf(id, address, body, date)

    private companion object {
        /** `Telephony.Sms.Inbox.CONTENT_URI` is `content://sms/inbox`. */
        const val AUTHORITY = "sms"
    }
}

/**
 * A stand-in for the platform's SMS provider (issue 3.9).
 *
 * Why:    Robolectric ships no SMS provider, and the assertions this test cares about are mostly
 *         about *what was asked for* rather than what came back — so the fake records its arguments
 *         instead of interpreting them. It deliberately does **not** implement the `_ID > ?` filter:
 *         a fake that re-implemented the query would let a reader that filtered in Kotlin pass the
 *         SQL-filtering test.
 * Result: a provider registered on the `sms` authority for the duration of one test.
 * Changelog: 2026-08-07 — Created for issue 3.9.
 */
class FakeSmsProvider : ContentProvider() {
    var uri: Uri? = null
    var projection: Array<out String>? = null
    var selection: String? = null
    var selectionArgs: Array<out String>? = null
    var sortOrder: String? = null

    /** Rows to hand back, in the column order of [ContentResolverSmsInboxReader]'s projection. */
    var rows: List<Array<Any?>> = emptyList()

    /** When true, answers as the platform does for a revoked READ_SMS. */
    var refuse: Boolean = false

    override fun onCreate(): Boolean = true

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor? {
        this.uri = uri
        this.projection = projection
        this.selection = selection
        this.selectionArgs = selectionArgs
        this.sortOrder = sortOrder
        if (refuse) throw SecurityException("Permission Denial: reading SmsProvider")
        val columns = projection ?: arrayOf(Telephony.Sms._ID)
        return MatrixCursor(columns).apply { rows.forEach { addRow(it) } }
    }

    override fun getType(uri: Uri): String? = null

    override fun insert(
        uri: Uri,
        values: ContentValues?,
    ): Uri? = null

    override fun delete(
        uri: Uri,
        selection: String?,
        selectionArgs: Array<out String>?,
    ): Int = 0

    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<out String>?,
    ): Int = 0
}
