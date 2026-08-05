package com.aicfo.domain.engines.recurring

import com.aicfo.core.common.Err
import com.aicfo.core.common.Ok
import com.aicfo.core.common.Result
import com.aicfo.core.model.Money
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for [RecurringEngine] — FR-TXN-006's "≥ 2 similar transactions" (issue 3.7).
 *
 * Why:  a detector is judged by what it **refuses**, not by what it finds. Finding the rent a user
 *       has paid on the 3rd for six months is easy; the tests that earn their place are the ones
 *       that stop two unrelated ₹250 charges, or a merchant whose amount drifts, or a gap that is
 *       nearly-but-not-quite monthly, from being proposed as a bill the user never had. The
 *       acceptance criteria name this directly — *"does not false-positive on one-offs"*.
 * What: the golden case, one test per refusal, the three cadences, and provenance.
 * Result: FR-TXN-006 is proven on a frozen fixture, and every threshold in the rulebook is pinned
 *       from both sides of its boundary.
 * Changelog: 2026-08-05 — Created for issue 3.7.
 */
class RecurringEngineTest {
    private val engine = RecurringEngineFactory.create()

    // --- the golden case (the acceptance criterion) -------------------------------------------

    /**
     * A fixed, realistic ledger: rent on the 3rd, a subscription that drifts a day, a weekly
     * commute top-up, and two one-offs that must not be proposed.
     * Frozen on purpose: these rows change only alongside a deliberate change to the formula,
     * never to make a test go green.
     */
    private val goldenLedger =
        listOf(
            candidate("t:rent-jun", "Landlord", -25_000_00L, "2026-06-03"),
            candidate("t:rent-jul", "Landlord", -25_000_00L, "2026-07-03"),
            candidate("t:rent-aug", "Landlord", -25_000_00L, "2026-08-03"),
            candidate("t:netflix-jun", "NETFLIX", -649_00L, "2026-06-05"),
            candidate("t:netflix-jul", "Netflix", -649_00L, "2026-07-04"),
            candidate("t:metro-1", "Metro Card", -500_00L, "2026-07-06"),
            candidate("t:metro-2", "Metro Card", -500_00L, "2026-07-13"),
            candidate("t:metro-3", "Metro Card", -500_00L, "2026-07-20"),
            candidate("t:gift", "Amazon", -2_499_00L, "2026-06-11"),
            candidate("t:dinner", "Dosa Plaza", -250_00L, "2026-07-21"),
        )

    @Test
    fun `golden - three series and nothing else`() {
        val series = engine.detect(input(goldenLedger)).expectOk()

        assertEquals(
            "one proposal per repeating merchant, ordered by the normalised key",
            listOf("Landlord", "Metro Card", "Netflix"),
            series.map { it.merchant },
        )
        assertEquals(
            listOf(Cadence.MONTHLY, Cadence.WEEKLY, Cadence.MONTHLY),
            series.map { it.cadence },
        )
        assertEquals(Money(-25_000_00L), series[0].medianAmount)
        assertEquals("last occurrence + one calendar month", "2026-09-03", series[0].nextDueIsoDate)
        assertEquals(
            listOf("t:rent-jun", "t:rent-jul", "t:rent-aug"),
            series[0].occurrences.map { it.transactionId },
        )
        assertEquals(
            "the dates are the evidence the card shows (P-02)",
            listOf("2026-06-03", "2026-07-03", "2026-08-03"),
            series[0].occurrences.map { it.bookedOn },
        )
    }

    @Test
    fun `golden - the merchant is shown as the user last typed it, not as the matched key`() {
        val netflix =
            engine.detect(input(goldenLedger)).expectOk()
                .single { it.cadence == Cadence.MONTHLY && it.merchant != "Landlord" }

        assertEquals("the most recent spelling, not `netflix`", "Netflix", netflix.merchant)
        assertEquals("case is a spelling, not a different payee", 2, netflix.occurrences.size)
    }

    // --- what it refuses (the acceptance criterion's "no false positives on one-offs") ---------

    @Test
    fun `a single transaction is not a series`() {
        val series = engine.detect(input(listOf(candidate("t:1", "Amazon", -999_00L, "2026-06-01")))).expectOk()

        assertTrue("one occurrence cannot establish a cadence", series.isEmpty())
    }

    @Test
    fun `two unrelated charges to different merchants are not a series`() {
        val ledger =
            listOf(
                candidate("t:1", "Dosa Plaza", -250_00L, "2026-06-01"),
                candidate("t:2", "Chai Point", -250_00L, "2026-07-01"),
            )

        assertTrue("same amount, same cadence, different payee", engine.detect(input(ledger)).expectOk().isEmpty())
    }

    @Test
    fun `two charges 40 days apart are not monthly`() {
        // 40 days is 10 outside the monthly period and the rulebook allows 4.
        val ledger =
            listOf(
                candidate("t:1", "Dosa Plaza", -250_00L, "2026-06-01"),
                candidate("t:2", "Dosa Plaza", -250_00L, "2026-07-11"),
            )

        assertTrue(engine.detect(input(ledger)).expectOk().isEmpty())
    }

    @Test
    fun `a merchant with no name is never proposed`() {
        val ledger =
            listOf(
                candidate("t:1", "   ", -250_00L, "2026-06-01"),
                candidate("t:2", "", -250_00L, "2026-07-01"),
                candidate("t:3", " ", -250_00L, "2026-08-01"),
            )

        assertTrue(
            "a series the user cannot recognise is not a proposal",
            engine.detect(input(ledger)).expectOk().isEmpty(),
        )
    }

    @Test
    fun `one irregular gap rejects the whole series - the median must not carry it`() {
        // Median gap is 30 (a clean monthly), but the last gap is 61 days. Classifying on the
        // median alone would propose this; the per-gap check is what refuses it.
        val ledger =
            listOf(
                candidate("t:1", "Gym", -1_500_00L, "2026-03-01"),
                candidate("t:2", "Gym", -1_500_00L, "2026-03-31"),
                candidate("t:3", "Gym", -1_500_00L, "2026-04-30"),
                candidate("t:4", "Gym", -1_500_00L, "2026-06-30"),
            )

        assertTrue(engine.detect(input(ledger)).expectOk().isEmpty())
    }

    // --- the rulebook thresholds, from both sides ---------------------------------------------

    @Test
    fun `a gap at the monthly tolerance edge is accepted, one day past it is not`() {
        val atEdge = twoCharges("2026-06-01", "2026-07-05") // 34 days = 30 + the 4-day tolerance
        val pastEdge = twoCharges("2026-06-01", "2026-07-06") // 35 days

        assertEquals(1, engine.detect(input(atEdge)).expectOk().size)
        assertTrue(engine.detect(input(pastEdge)).expectOk().isEmpty())
    }

    @Test
    fun `an amount at the tolerance edge is accepted, one paisa past it is not`() {
        // Three occurrences, so the median is the unambiguous middle value: 1,000.00. 5% is 50.00.
        val twoAtBase =
            listOf(
                candidate("t:1", "Water", -1_000_00L, "2026-06-01"),
                candidate("t:2", "Water", -1_000_00L, "2026-07-01"),
            )
        val atEdge = twoAtBase + candidate("t:3", "Water", -1_050_00L, "2026-08-01")
        val pastEdge = twoAtBase + candidate("t:3", "Water", -1_050_01L, "2026-08-01")

        assertEquals(1, engine.detect(input(atEdge)).expectOk().size)
        assertTrue(
            "a wandering amount is not the same bill",
            engine.detect(input(pastEdge)).expectOk().isEmpty(),
        )
    }

    @Test
    fun `on an even count the representative amount is the smaller magnitude, whatever the sign`() {
        // The tolerance band is relative to this figure, so which of the two middle values an even
        // count picks decides whether a borderline series is accepted. Ordered by **magnitude**, so
        // that decision does not depend on the direction of the money — see `representativeAmount`.
        val spending = twoAmounts(-1_000_00L, -1_020_00L)
        val income = twoAmounts(1_000_00L, 1_020_00L)

        assertEquals(Money(-1_000_00L), engine.detect(input(spending)).expectOk().single().medianAmount)
        assertEquals(Money(1_000_00L), engine.detect(input(income)).expectOk().single().medianAmount)
    }

    @Test
    fun `income and spending are judged by the same tolerance`() {
        // The defect this replaced: with a *signed* median, two outflows were measured against the
        // larger expense (a wider band) while two inflows of the same spread were measured against
        // the smaller (a narrower one) — so a mirror-image ledger got a different answer. At two
        // occurrences the median is 1,000.00 either way, and 5% of that is 50.00.
        val atEdge = { sign: Long -> twoAmounts(sign * 1_000_00L, sign * 1_050_00L) }
        val pastEdge = { sign: Long -> twoAmounts(sign * 1_000_00L, sign * 1_050_01L) }

        assertEquals(1, engine.detect(input(atEdge(-1L))).expectOk().size)
        assertEquals(1, engine.detect(input(atEdge(1L))).expectOk().size)
        assertTrue(engine.detect(input(pastEdge(-1L))).expectOk().isEmpty())
        assertTrue(
            "a mirror-image ledger must get the same answer",
            engine.detect(input(pastEdge(1L))).expectOk().isEmpty(),
        )
    }

    @Test
    fun `a zero-amount series requires every occurrence to be exactly zero`() {
        val allZero = listOf(candidate("t:1", "Free", 0L, "2026-06-01"), candidate("t:2", "Free", 0L, "2026-07-01"))
        val oneNonZero = listOf(candidate("t:1", "Free", 0L, "2026-06-01"), candidate("t:2", "Free", -1L, "2026-07-01"))

        assertEquals(
            "0% of zero is zero, so the tolerance is exactness",
            1,
            engine.detect(input(allZero)).expectOk().size,
        )
        assertTrue(engine.detect(input(oneNonZero)).expectOk().isEmpty())
    }

    @Test
    fun `a raised minimum occurrence count moves the engine with it`() {
        val threeMonths =
            listOf(
                candidate("t:1", "Gym", -1_500_00L, "2026-06-01"),
                candidate("t:2", "Gym", -1_500_00L, "2026-07-01"),
                candidate("t:3", "Gym", -1_500_00L, "2026-08-01"),
            )
        val strict = RecurringRules(minOccurrences = 4)

        assertEquals("the default rulebook proposes it", 1, engine.detect(input(threeMonths)).expectOk().size)
        assertTrue(
            "the threshold is injected, not baked in (ADR-0005)",
            engine.detect(input(threeMonths, strict)).expectOk().isEmpty(),
        )
    }

    // --- cadences ------------------------------------------------------------------------------

    @Test
    fun `a yearly series is detected and projected onto the next year`() {
        val ledger =
            listOf(
                candidate("t:1", "Car Insurance", -18_400_00L, "2024-09-12"),
                candidate("t:2", "Car Insurance", -18_400_00L, "2025-09-14"),
            )

        val series = engine.detect(input(ledger)).expectOk().single()

        assertEquals(Cadence.YEARLY, series.cadence)
        assertEquals("2026-09-14", series.nextDueIsoDate)
    }

    @Test
    fun `the next due date is calendar arithmetic, not thirty days`() {
        // 31 Jan + one month is 28 Feb, not 2 March. Cadence.periodDays is a match target only.
        val ledger =
            listOf(
                candidate("t:1", "Rent", -20_000_00L, "2025-12-31"),
                candidate("t:2", "Rent", -20_000_00L, "2026-01-31"),
            )

        assertEquals("2026-02-28", engine.detect(input(ledger)).expectOk().single().nextDueIsoDate)
    }

    // --- provenance and determinism (AI-ARC-003, P-08) ----------------------------------------

    @Test
    fun `every proposal cites the rulebook row it applied`() {
        val provenance = engine.detect(input(goldenLedger)).expectOk().first().provenance

        assertEquals("recurring-detector", provenance.engineId)
        assertEquals("1.0", provenance.engineVersion)
        assertEquals(NOW, provenance.computedAtUtcMillis)
        assertEquals(listOf(RecurringRules.SERIES_MATCH), provenance.evidence)
        assertEquals(
            "the window the user can check the proposal against",
            "2026-06-03..2026-08-03",
            provenance.inputWindow,
        )
    }

    @Test
    fun `confidence is full for a perfectly regular series and lower for a drifting one`() {
        val exact = twoCharges("2026-06-01", "2026-07-01") // gap 30, deviation 0
        val drifting = twoCharges("2026-06-01", "2026-07-05") // gap 34, deviation 4 = the tolerance

        val exactBps = engine.detect(input(exact)).expectOk().single().provenance.confidenceBps
        val driftingBps = engine.detect(input(drifting)).expectOk().single().provenance.confidenceBps

        assertEquals(10_000, exactBps)
        assertEquals("4 days of 4 allowed: 10000 − 4×10000/5", 2_000, driftingBps)
    }

    @Test
    fun `the same input twice gives an identical result`() {
        // P-08. Includes provenance, which is why `nowUtcMillis` is an input rather than a clock read.
        val first = engine.detect(input(goldenLedger.shuffled())).expectOk()
        val second = engine.detect(input(goldenLedger.reversed())).expectOk()

        assertEquals("input order must not change the output", first, second)
    }

    @Test
    fun `a malformed date is an error, not a crash`() {
        val ledger =
            listOf(
                candidate("t:1", "Rent", -20_000_00L, "01/06/2026"),
                candidate("t:2", "Rent", -20_000_00L, "01/07/2026"),
            )

        val error = engine.detect(input(ledger))

        assertTrue("no exception may cross the layer boundary (§21.6)", error is Err)
    }

    @Test
    fun `an empty ledger proposes nothing and is not an error`() {
        assertEquals(emptyList<RecurringSeries>(), engine.detect(input(emptyList())).expectOk())
    }

    // --- fixtures ------------------------------------------------------------------------------

    /** Result: one candidate row. Input: the four fields. Output: [RecurringCandidate]. */
    private fun candidate(
        id: String,
        merchant: String,
        minor: Long,
        bookedOn: String,
    ) = RecurringCandidate(id, merchant, Money(minor), bookedOn)

    /** Result: two identical charges to one merchant on the given dates. Output: the candidates. */
    private fun twoCharges(
        first: String,
        second: String,
    ) = listOf(candidate("t:1", "Water", -1_000_00L, first), candidate("t:2", "Water", -1_000_00L, second))

    /** Result: two monthly charges to one merchant at the given amounts. Output: the candidates. */
    private fun twoAmounts(
        first: Long,
        second: Long,
    ) = listOf(candidate("t:1", "Water", first, "2026-06-01"), candidate("t:2", "Water", second, "2026-07-01"))

    /** Result: an engine input at the frozen instant. Input: [candidates], [rules]. */
    private fun input(
        candidates: List<RecurringCandidate>,
        rules: RecurringRules = RecurringRules(),
    ) = RecurringInput(candidates, rules, NOW)

    /**
     * Unwraps an `Ok` or fails the test with the error.
     * Result: the series. Input: the receiver. Output: `List<RecurringSeries>`.
     */
    private fun Result<List<RecurringSeries>, *>.expectOk(): List<RecurringSeries> =
        (this as? Ok)?.value ?: throw AssertionError("expected Ok, got $this")

    private companion object {
        /** A frozen instant (TIM-001) — the engine never reads a clock, so this is all it can stamp. */
        const val NOW = 1_754_000_000_000L
    }
}
