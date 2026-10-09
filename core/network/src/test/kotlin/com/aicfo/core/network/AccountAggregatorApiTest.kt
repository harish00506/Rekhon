package com.aicfo.core.network

import com.aicfo.core.common.AppError
import com.aicfo.core.common.Err
import com.aicfo.core.model.Money
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The AA ingest contract and its only shipping implementation (issue 13.6; §16, §22, ADR-0074).
 *
 * Why:  this issue ships an interface, not an integration, so what can be tested is the **shape** —
 *       and the shape is where the safety lives. Three things are designed to be impossible rather
 *       than merely discouraged: fetching without a consent handle, holding AA data without knowing
 *       when it was fetched, and a consent handle carrying a bank identifier. Each has a test,
 *       because an interface's guarantees are exactly what rots when somebody later finds them
 *       inconvenient.
 * What: the unconfigured client's refusal, the types' invariants, and the staleness requirement.
 * Result: AC2's stub is provably a stub, and provably the right shape.
 * Changelog: 2026-10-09 — Created for issue 13.6.
 */
class AccountAggregatorApiTest {
    private val handle = ConsentHandle(id = "consent:1", expiresAtUtcMillis = 1_800_000_000_000L)

    /**
     * Input:  the client every build gets.
     * Output: asserts it refuses, and that the refusal is **not retryable** — a missing backend is
     *         not transient, and a worker treating it as such would re-run for ever against a host
     *         that does not exist (the same reasoning as issue 6.5's market-data client).
     */
    @Test
    fun `the unconfigured client refuses, and says retrying will not help`() =
        runTest {
            val outcome = AccountAggregatorFactory.create().statements(handle)

            assertEquals(Err(AppError.Network(retryable = false)), outcome)
        }

    /**
     * Input:  the factory, twice.
     * Output: asserts every build gets the unconfigured client. There is no configured path yet,
     *         and this is what makes "the app has no bank connection" checkable rather than
     *         asserted.
     */
    @Test
    fun `no build has a configured AA client`() {
        assertTrue(AccountAggregatorFactory.create() === UnconfiguredAccountAggregatorApi)
    }

    /**
     * Input:  a result built without a fetch time.
     * Output: asserts it is impossible. P-04 requires cached data to carry a staleness label, and
     *         the reliable way to make a screen render one is to make the data unrepresentable
     *         without it — a nullable "maybe we know when" would be a label nobody shows.
     */
    @Test
    fun `AA data cannot exist without saying when it was fetched`() {
        val failure =
            runCatching { FetchedStatements(lines = emptyList(), fetchedAtUtcMillis = 0L, complete = true) }
                .exceptionOrNull()

        assertTrue("expected an IllegalArgumentException, got $failure", failure is IllegalArgumentException)
    }

    /**
     * Input:  an empty fetch.
     * Output: asserts "no transactions" is still a valid, timestamped answer — distinct from a
     *         failure. A bank with nothing in the window is not an error.
     */
    @Test
    fun `an empty fetch is an answer, not a failure`() {
        val empty = FetchedStatements(lines = emptyList(), fetchedAtUtcMillis = 1L, complete = true)

        assertTrue(empty.lines.isEmpty())
        assertTrue(empty.complete)
    }

    /**
     * Input:  a truncated window.
     * Output: asserts partial history is flagged, so a caller can say "there may be more" rather
     *         than presenting a slice as the whole.
     */
    @Test
    fun `a truncated window is reported as incomplete`() {
        val line = StatementLine("ref:1", Money(-12_345L), "2026-10-01", "UPI/merchant")
        val partial = FetchedStatements(lines = listOf(line), fetchedAtUtcMillis = 1L, complete = false)

        assertFalse(partial.complete)
    }

    /**
     * Input:  a consent handle with no id, and one with no expiry.
     * Output: asserts both are refused. An unidentified consent cannot be revoked or audited, and
     *         a consent with no expiry contradicts the framework, where every consent lapses.
     */
    @Test
    fun `a consent handle must be identified and must expire`() {
        assertTrue(runCatching { ConsentHandle("", 1L) }.isFailure)
        assertTrue(runCatching { ConsentHandle("consent:1", 0L) }.isFailure)
    }

    /**
     * Input:  the consent handle's declared fields.
     * Output: asserts it carries **only** an id and an expiry — no account number, no bank name, no
     *         token. §21.6 bans PII from logs, and a type that cannot hold a bank identifier cannot
     *         leak one into a log line, a `Result` or a UI state.
     */
    @Test
    fun `a consent handle cannot carry a bank identifier`() {
        val fields = ConsentHandle::class.java.declaredFields.map { it.name }.filterNot { it.startsWith("$") }

        assertEquals(listOf("id", "expiresAtUtcMillis"), fields)
    }

    /**
     * Input:  a statement line.
     * Output: asserts it carries the bank's own reference. Deduplication against rows the user
     *         already has will key on it, and a line without one could only be matched by
     *         amount-and-date — which is exactly how a duplicate import doubles somebody's rent.
     */
    @Test
    fun `a statement line carries the bank's reference, for deduplication`() {
        val line = StatementLine("HDFC-REF-88812", Money(-5_000_00L), "2026-10-01", "NEFT")

        assertEquals("HDFC-REF-88812", line.externalId)
        assertEquals(-5_000_00L, line.amount.minor)
    }
}
