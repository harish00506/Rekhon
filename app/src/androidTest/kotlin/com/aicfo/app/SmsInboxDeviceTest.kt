package com.aicfo.app

import androidx.test.platform.app.InstrumentationRegistry
import com.aicfo.core.common.DefaultDispatcherProvider
import com.aicfo.core.common.Ok
import com.aicfo.data.sms.SmsInboxReaderFactory
import com.aicfo.domain.engines.sms.SmsEngineFactory
import com.aicfo.domain.engines.sms.SmsInput
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The one check against Android's own SMS provider (issue 3.9; §18, §23, P-01).
 *
 * Why:  every other test in this issue stops at a boundary. The parser's suite feeds it values, the
 *       repository's suite fakes the reader, and `ContentResolverSmsInboxReaderTest` uses a fake
 *       provider **of ours** — which is the problem this test exists for. A fake we wrote will
 *       happily answer a query naming a column that does not exist, a sort order the real provider
 *       rejects, or a `LIMIT` clause it does not honour. The real one throws.
 *
 *       That is not hypothetical. The equivalent test for issue 3.8 is what revealed ML Kit returns
 *       a receipt's *cells* rather than its rows, silently reading an item price as the total —
 *       something no amount of reasoning had found. This is the same check for the same class of
 *       assumption.
 * What: grants `READ_SMS`, runs the real reader against `Telephony.Sms.Inbox`, and asserts the
 *       query is accepted and its contract holds; then runs the real parser over whatever came back.
 * Result: the seam between `:data:sms` and the platform is checked against reality.
 * Changelog: 2026-08-07 — Created for issue 3.9.
 *
 * ## What this cannot check on a bare emulator, and why
 *
 * **It does not deliver messages.** Since KitKat only the *default SMS app* may write to the
 * telephony provider, so the test cannot seed rows itself; and `adb emu sms send` — which does go
 * through the modem — needs a default SMS app that is actually initialised to persist anything. A
 * stock AVD has Google Messages installed but never set up, so alerts are delivered and then
 * dropped. Holding the SMS role (`cmd role add-role-holder`) is **not** sufficient: verified on
 * 2026-08-07 against `CfoTest` (API 34), where the role was held, three alerts were sent, and
 * `content://sms/inbox` stayed empty.
 *
 * So the assertions below are **about the query, not about the rows**: that the projection names
 * columns the provider has, that `_ID > ?` and `_ID ASC LIMIT n` are accepted and honoured, and
 * that whatever comes back satisfies the reader's ordering contract. On a device that *does* have
 * messages the same test additionally exercises the parse, which is why it runs the parser over
 * what it finds rather than asserting the inbox is empty.
 *
 * **What is still unproven:** that a real bank alert *as the provider stores it* parses correctly —
 * the platform could normalise whitespace, or split a long message across rows. Closing that needs
 * a device with real messages, and it is the one item the 3.9 tracker keeps open.
 */
class SmsInboxDeviceTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    /**
     * Input:  none. Output: `READ_SMS` granted to the app under test.
     *
     * Granted through `uiAutomation` rather than `GrantPermissionRule`, which would mean adding
     * `androidx.test:rules` for one call — and that artifact does not publish the version the rest
     * of the test stack is pinned to.
     *
     * The permission is denied by default on a fresh install; that is the correct shipping
     * behaviour and is verified separately against the built APK's manifest.
     */
    @Before
    fun setUp() {
        InstrumentationRegistry.getInstrumentation().uiAutomation.grantRuntimePermission(
            context.packageName,
            android.Manifest.permission.READ_SMS,
        )
    }

    @Test
    fun theRealProviderAcceptsTheQueryTheReaderIssues() =
        runBlocking {
            val reader = SmsInboxReaderFactory.create(context, DefaultDispatcherProvider())
            assertTrue("the test APK must hold READ_SMS for this to mean anything", reader.canRead())

            val read = reader.readSince(afterId = 0L, limit = 500)

            // The assertion that earns this test its place. A projection naming a column the
            // provider does not have, or a sort order it rejects, throws from deep inside the
            // ContentResolver — which `runCatchingToResult` classifies into exactly this `Err`.
            // Our own fake provider would have answered every one of them happily.
            assertTrue("the platform provider rejected the reader's query: $read", read is Ok)
        }

    @Test
    fun theReaderHonoursItsOrderingAndBatchContractAgainstTheRealProvider() =
        runBlocking {
            val reader = SmsInboxReaderFactory.create(context, DefaultDispatcherProvider())

            val messages = (reader.readSince(afterId = 0L, limit = 2) as Ok).value

            // Oldest first is what lets a caller advance its cursor to the last message it actually
            // processed; descending would leave a gap no later scan revisits. And the LIMIT must be
            // honoured, or a first scan on a phone with a decade of messages loads all of them.
            assertEquals(
                "messages must arrive in ascending id order",
                messages.map { it.id }.sorted(),
                messages.map { it.id },
            )
            assertTrue("the LIMIT inside the sort order must be honoured", messages.size <= 2)
            assertTrue("no message may arrive without a sender", messages.all { it.sender.isNotEmpty() })
        }

    @Test
    fun theCursorExcludesWhatHasAlreadyBeenRead() =
        runBlocking {
            val reader = SmsInboxReaderFactory.create(context, DefaultDispatcherProvider())
            val all = (reader.readSince(afterId = 0L, limit = 500) as Ok).value
            val highest = all.maxOfOrNull { it.id } ?: return@runBlocking

            val afterEverything = (reader.readSince(afterId = highest, limit = 500) as Ok).value

            // `_ID > ?` against the real provider — what stops the app re-reading the user's
            // messages on every scan (P-01).
            assertTrue("nothing may come back after the highest id already read", afterEverything.isEmpty())
        }

    @Test
    fun theParserSurvivesWhateverTheProviderActuallyStores() =
        runBlocking {
            val reader = SmsInboxReaderFactory.create(context, DefaultDispatcherProvider())
            val messages = (reader.readSince(afterId = 0L, limit = 500) as Ok).value
            val engine = SmsEngineFactory.create()

            // On a bare AVD this runs over nothing; on a device with messages it is the only place
            // the parser meets text the platform stored rather than text a fixture invented. Either
            // way it must not throw, and it must never produce a zero-amount draft.
            messages.forEach { message ->
                val outcome =
                    engine.parse(SmsInput(message = message, receivedOnIsoDate = TODAY, nowUtcMillis = 0L))
                assertTrue("the parser errored on a real message", outcome is Ok)
                val draft = (outcome as Ok).value
                assertFalse("a draft must never carry a zero amount", draft != null && draft.amount.minor == 0L)
            }
        }

    private companion object {
        /** Fixed, so a parse over real messages is reproducible across runs (P-08, TIM-001). */
        const val TODAY = "2026-08-07"
    }
}
