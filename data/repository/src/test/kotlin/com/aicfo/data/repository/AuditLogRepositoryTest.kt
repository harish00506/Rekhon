package com.aicfo.data.repository

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.cash.turbine.test
import com.aicfo.core.common.FakeClock
import com.aicfo.core.common.TestDispatchers
import com.aicfo.core.common.getOrNull
import com.aicfo.core.database.CfoDatabase
import com.aicfo.core.model.AuditEvent
import com.aicfo.core.model.AuditMethod
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Tests for [AuditLogRepository] — the security log the app lock writes to (issue 2.2; §21.6).
 *
 * Why:  three things about this log matter and none of them is obvious from reading the class.
 *       It must be **append-only** (a log a caller can rewrite proves nothing), it must record
 *       **failures** as faithfully as successes (a log of successes cannot show an attack), and it
 *       must never carry PII — which here means the stored row really is nothing but a code and a
 *       timestamp, checked against what actually landed in SQLite rather than against the entity
 *       declaration.
 * What: append and read back, ordering, the failure and lockout events, the clock the timestamp
 *       comes from, counting, and the unknown-constant path.
 * Result: the first `:data:repository` class is proven against a real database engine.
 * Changelog: 2026-07-26 — Created for issue 2.2.
 *
 * **Unencrypted in-memory Room, deliberately.** SQLCipher needs a device, and what is under test
 * here is the repository's SQL and mapping, not the encryption — `EncryptedDatabaseTest`
 * (androidTest, issue 1.6) is what proves the file on disk is ciphertext. Using Robolectric keeps
 * this suite running on every `./gradlew test` rather than only when a phone is plugged in.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class AuditLogRepositoryTest {
    private lateinit var database: CfoDatabase
    private lateinit var repository: AuditLogRepository
    private val clock = FakeClock()

    /** Input: none. Output: a fresh in-memory database and a repository over it. */
    @Before
    fun setUp() {
        database =
            Room.inMemoryDatabaseBuilder(
                ApplicationProvider.getApplicationContext(),
                CfoDatabase::class.java,
            )
                // For the two tests that reach past the repository and read raw SQL, so they can
                // check what actually landed in the table. The repository itself still goes through
                // the injected IO dispatcher — this relaxes the test harness, not the code.
                .allowMainThreadQueries()
                .build()
        repository =
            RepositoryFactory.auditLog(
                database = database,
                clock = clock,
                dispatchers = TestDispatchers(UnconfinedTestDispatcher()),
            )
    }

    /** Input: none. Output: closes the database between tests. */
    @After
    fun tearDown() {
        database.close()
    }

    // --- recording ------------------------------------------------------------------------

    /**
     * Input:  one successful unlock.
     * Output: asserts the event, the factor, and that the timestamp came from the injected
     *         [FakeClock] rather than the wall clock (TIM-001). A row stamped from
     *         `System.currentTimeMillis()` would look right in every test and still be wrong: the
     *         whole app resolves time through the profile zone.
     */
    @Test
    fun `records an unlock with its factor and the injected clock's instant`() =
        runTest {
            clock.setTo(FIXED_MILLIS)

            assertTrue(repository.record(AuditEvent.APP_UNLOCK_SUCCESS, AuditMethod.BIOMETRIC).isOk)

            val entries = repository.observeRecent().first()
            assertEquals(1, entries.size)
            assertEquals(AuditEvent.APP_UNLOCK_SUCCESS, entries.single().event)
            assertEquals(AuditMethod.BIOMETRIC, entries.single().method)
            assertEquals(FIXED_MILLIS, entries.single().occurredAtUtcMillis)
        }

    /**
     * Input:  an event with no factor — a timeout re-lock.
     * Output: asserts the method is `null` rather than a placeholder string. "No factor" is a real
     *         state, not an unknown one.
     */
    @Test
    fun `records an event that has no auth factor`() =
        runTest {
            assertTrue(repository.record(AuditEvent.APP_LOCKED_TIMEOUT).isOk)

            val entry = repository.observeRecent().first().single()
            assertEquals(AuditEvent.APP_LOCKED_TIMEOUT, entry.event)
            assertNull(entry.method)
        }

    /**
     * Input:  a failed unlock followed by a lockout.
     * Output: asserts both are recorded. A log that captured only successes would show nothing at
     *         all on a phone someone was actively guessing at — the one case it exists for.
     */
    @Test
    fun `records failures and lockouts, not only successes`() =
        runTest {
            repository.record(AuditEvent.APP_UNLOCK_FAILURE, AuditMethod.PIN)
            repository.record(AuditEvent.APP_LOCKOUT_STARTED)

            val events = repository.observeRecent().first().map { it.event }
            assertTrue(AuditEvent.APP_UNLOCK_FAILURE in events)
            assertTrue(AuditEvent.APP_LOCKOUT_STARTED in events)
        }

    // --- reading back ----------------------------------------------------------------------

    /**
     * Input:  three events at increasing instants.
     * Output: asserts newest first, which is the order a security screen reads in.
     */
    @Test
    fun `recent events come back newest first`() =
        runTest {
            clock.setTo(FIXED_MILLIS)
            repository.record(AuditEvent.APP_LOCK_ENABLED)
            clock.setTo(FIXED_MILLIS + 1_000L)
            repository.record(AuditEvent.APP_UNLOCK_FAILURE, AuditMethod.PIN)
            clock.setTo(FIXED_MILLIS + 2_000L)
            repository.record(AuditEvent.APP_UNLOCK_SUCCESS, AuditMethod.PIN)

            assertEquals(
                listOf(
                    AuditEvent.APP_UNLOCK_SUCCESS,
                    AuditEvent.APP_UNLOCK_FAILURE,
                    AuditEvent.APP_LOCK_ENABLED,
                ),
                repository.observeRecent().first().map { it.event },
            )
        }

    /** Input: more events than the limit. Output: asserts the bound holds — this table only grows. */
    @Test
    fun `the recent limit is respected`() =
        runTest {
            repeat(5) { index ->
                clock.setTo(FIXED_MILLIS + index)
                repository.record(AuditEvent.APP_UNLOCK_FAILURE, AuditMethod.PIN)
            }
            assertEquals(2, repository.observeRecent(limit = 2).first().size)
        }

    /**
     * Input:  a collector watching while an event is appended.
     * Output: asserts the append is emitted, which is what lets a security screen update live
     *         rather than poll.
     */
    @Test
    fun `an append is emitted to observers`() =
        runTest {
            repository.observeRecent().test {
                assertEquals(emptyList<AuditEntry>(), awaitItem())

                repository.record(AuditEvent.PIN_SET)

                assertEquals(AuditEvent.PIN_SET, awaitItem().single().event)
                cancelAndIgnoreRemainingEvents()
            }
        }

    // --- counting -------------------------------------------------------------------------

    /**
     * Input:  failures either side of a cut-off, plus an unrelated event.
     * Output: asserts only matching events after the bound are counted — the query behind "how
     *         many failed unlocks this week?".
     */
    @Test
    fun `counts only the named event since the given instant`() =
        runTest {
            clock.setTo(FIXED_MILLIS - 1L)
            repository.record(AuditEvent.APP_UNLOCK_FAILURE, AuditMethod.PIN)
            clock.setTo(FIXED_MILLIS)
            repository.record(AuditEvent.APP_UNLOCK_FAILURE, AuditMethod.PIN)
            repository.record(AuditEvent.APP_UNLOCK_SUCCESS, AuditMethod.PIN)

            assertEquals(
                1,
                repository.countSince(AuditEvent.APP_UNLOCK_FAILURE, FIXED_MILLIS).getOrNull(),
            )
        }

    /** Input: an empty log. Output: asserts zero, not an error. */
    @Test
    fun `counting an empty log returns zero`() =
        runTest {
            assertEquals(0, repository.countSince(AuditEvent.APP_UNLOCK_FAILURE, 0L).getOrNull())
        }

    // --- what actually reaches the table ----------------------------------------------------

    /**
     * Input:  a recorded event, read back with raw SQL.
     * Output: asserts the stored row is a code, a number and a code — nothing else.
     *
     * §21.6 sends security events here *because* nothing private may be logged. This checks the
     * bytes that actually landed rather than trusting the entity declaration: if a future change
     * added a column and started writing a merchant or a balance into it, the schema guard in
     * `:core:database` would catch the column and this would catch the value.
     */
    @Test
    fun `the stored row holds only codes and a timestamp`() =
        runTest {
            clock.setTo(FIXED_MILLIS)
            repository.record(AuditEvent.APP_UNLOCK_FAILURE, AuditMethod.PIN)

            database.query("SELECT * FROM audit_log", emptyArray()).use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals(
                    listOf("id", "event", "occurred_at_utc_millis", "method"),
                    cursor.columnNames.toList(),
                )
                assertEquals("APP_UNLOCK_FAILURE", cursor.getString(cursor.getColumnIndexOrThrow("event")))
                assertEquals("PIN", cursor.getString(cursor.getColumnIndexOrThrow("method")))
                assertEquals(
                    FIXED_MILLIS,
                    cursor.getLong(cursor.getColumnIndexOrThrow("occurred_at_utc_millis")),
                )
            }
        }

    /**
     * Input:  a row naming a constant this build does not know, as a newer version would write.
     * Output: asserts it maps to a `null` event rather than throwing. An old build reading a newer
     *         database must show an unknown event, not crash on the security screen.
     */
    @Test
    fun `an unrecognised stored code reads back as null rather than throwing`() =
        runTest {
            database.openHelper.writableDatabase.execSQL(
                "INSERT INTO audit_log (event, occurred_at_utc_millis, method) " +
                    "VALUES ('SOMETHING_FROM_THE_FUTURE', $FIXED_MILLIS, 'RETINA')",
            )

            val entry = repository.observeRecent().first().single()
            assertNull(entry.event)
            assertNull(entry.method)
            assertEquals(FIXED_MILLIS, entry.occurredAtUtcMillis)
        }

    private companion object {
        /** An arbitrary fixed instant — the point is that it comes from [FakeClock], not the wall. */
        const val FIXED_MILLIS = 1_800_000_000_000L
    }
}
