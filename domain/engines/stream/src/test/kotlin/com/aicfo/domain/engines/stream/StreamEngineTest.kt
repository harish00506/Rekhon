package com.aicfo.domain.engines.stream

import com.aicfo.core.common.AppError
import com.aicfo.core.common.Err
import com.aicfo.core.common.Ok
import com.aicfo.core.common.Result
import com.aicfo.core.model.Money
import com.aicfo.core.model.RuleCitation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/**
 * The stream engine's contract beyond the golden file (issue 9.1; §8.2, AI-ARC-003, P-08).
 *
 * Why:  the golden file pins the formula at the shipped thresholds. What it cannot show is that the
 *       thresholds are **data** — that moving a `CLS-STR-001` value by one basis point moves exactly
 *       the class it should — nor the profile-level contract: totals, ordering, provenance, and the
 *       inputs that must be refused rather than scored.
 * What: threshold edges by injected rules; the §8.2 monthly totals; provenance and evidence;
 *       determinism under reordered input; validation.
 * Result: every branch of the engine asserted, including the ones the shipped rules never reach.
 * Changelog: 2026-09-19 — Created for issue 9.1.
 */
class StreamEngineTest {
    private val engine = StreamEngineFactory.create()

    // --- thresholds are data (CLS-STR-001) ----------------------------------------------------------

    @Test
    fun `a FIXED floor at or under the exact score admits it, and one basis point more does not`() {
        // The groceries stream scores 7 125.85 bps exactly (oracle, 50-digit decimal arithmetic).
        val under = classifyOne(groceries(), StreamRules(fixedMinScoreBps = 7_125))
        val over = classifyOne(groceries(), StreamRules(fixedMinScoreBps = 7_126))

        assertEquals(StreamClass.FIXED, under.streamClass)
        assertEquals(StreamClass.SEMI_FIXED, over.streamClass)
    }

    @Test
    fun `the class is decided on the exact score, not the rounded one shown`() {
        // 7 125.85 is displayed as 7 126, yet a floor of 7 126 must still reject it: classifying on
        // the rounded figure would promote a stream the formula says is below the line.
        val verdict = classifyOne(groceries(), StreamRules(fixedMinScoreBps = 7_126))

        assertEquals(7_126, verdict.metrics!!.scoreBps)
        assertEquals(StreamClass.SEMI_FIXED, verdict.streamClass)
    }

    @Test
    fun `a score exactly on the SEMI_FIXED floor is SEMI_FIXED`() {
        // Dining scores 4 262.496 bps exactly, so a floor of 4 262 admits it and 4 263 does not.
        assertEquals(
            StreamClass.SEMI_FIXED,
            classifyOne(dining(), StreamRules(semiFixedMinScoreBps = 4_262)).streamClass,
        )
        assertEquals(
            StreamClass.VARIABLE,
            classifyOne(dining(), StreamRules(semiFixedMinScoreBps = 4_263)).streamClass,
        )
    }

    @Test
    fun `FIXED needs its minimum months even with a perfect score`() {
        val threeMonths = monthly(day = 3, amount = 64_900L, months = 6..8)

        assertEquals(StreamClass.FIXED, classifyOne(history(threeMonths)).streamClass)
        assertEquals(
            StreamClass.SEMI_FIXED,
            classifyOne(history(threeMonths), StreamRules(fixedMinMonths = 4)).streamClass,
        )
    }

    @Test
    fun `the cold-start floor is data too`() {
        val twoMonths = monthly(day = 3, amount = 64_900L, months = 7..8)

        assertEquals(StreamBasis.SCORED, classifyOne(history(twoMonths)).basis)
        assertEquals(
            StreamBasis.COLD_START_NO_PRIOR,
            classifyOne(history(twoMonths), StreamRules(coldStartMinMonths = 3)).basis,
        )
    }

    @Test
    fun `a wider day-lock window counts payments a narrower one misses`() {
        val drifting = listOf("2026-06-01", "2026-07-05", "2026-08-01").map { occurrence(it, 10_000L) }

        assertEquals(6_667, classifyOne(history(drifting)).metrics!!.dayLockBps)
        assertEquals(10_000, classifyOne(history(drifting), StreamRules(dayLockWindowDays = 4)).metrics!!.dayLockBps)
    }

    @Test
    fun `weights that do not sum to one are refused at construction`() {
        val refused = runCatching { StreamRules(cvWeightBps = 5_000) }
        assertTrue(refused.isFailure)
    }

    // --- the profile ----------------------------------------------------------------------------------

    @Test
    fun `the monthly totals sum typical amounts by class`() {
        val profile =
            classify(
                listOf(
                    history(monthly(1, 2_500_000L), key = "rent"),
                    history(monthly(3, 64_900L, 6..8), key = "netflix"),
                    groceries(),
                    dining(),
                ),
            )

        assertEquals(Money(2_564_900L), profile.fixedLoad)
        // Rent 25 000.00 + a 649.00 subscription; groceries' median month 6 665.00; dining's 2 315.00.
        assertEquals(Money(666_500L), profile.semiFixedExpected)
        assertEquals(Money(231_500L), profile.variableBudgetable)
    }

    @Test
    fun `the three totals account for every stream exactly once`() {
        val streams = listOf(history(monthly(1, 2_500_000L), key = "rent"), groceries(), dining())
        val profile = classify(streams)

        assertEquals(
            profile.streams.sumOf { it.typicalMonthly.minor },
            profile.fixedLoad.minor + profile.semiFixedExpected.minor + profile.variableBudgetable.minor,
        )
    }

    @Test
    fun `streams come back in key order whatever order they went in (P-08)`() {
        val streams = listOf(dining(), history(monthly(1, 2_500_000L), key = "a-rent"), groceries())

        val forward = classify(streams)
        val backward = classify(streams.reversed())

        assertEquals(listOf("a-rent", "dining", "groceries"), forward.streams.map { it.streamKey })
        assertEquals(forward, backward)
    }

    @Test
    fun `occurrence order within a stream does not change the verdict`() {
        val shuffled = groceries().let { it.copy(occurrences = it.occurrences.reversed()) }

        assertEquals(classifyOne(groceries()), classifyOne(shuffled))
    }

    @Test
    fun `an empty profile totals zero and cites nothing`() {
        val profile = classify(emptyList())

        assertTrue(profile.streams.isEmpty())
        assertEquals(Money(0L), profile.fixedLoad)
        assertTrue(profile.provenance.evidence.isEmpty())
        assertFalse(profile.hasEstimates)
    }

    @Test
    fun `a stream with nothing inside the window is dropped rather than classified`() {
        val outside = history(listOf(occurrence("2026-01-15", 5_000L)), key = "old")

        assertTrue(classify(listOf(outside)).streams.isEmpty())
    }

    // --- provenance (AI-ARC-003) ------------------------------------------------------------------

    @Test
    fun `every verdict names the engine, its version, the window and the rows that fired`() {
        val verdict = classifyOne(history(monthly(1, 2_500_000L), key = "rent", priorKey = "rent"))

        assertEquals("AI-CLS.stream", verdict.provenance.engineId)
        assertEquals("1.0", verdict.provenance.engineVersion)
        assertEquals("2026-03-01..2026-08-31", verdict.provenance.inputWindow)
        assertEquals(NOW, verdict.provenance.computedAtUtcMillis)
        assertEquals(8_000, verdict.provenance.confidenceBps)
        assertEquals(listOf(StreamRules.SCORED), verdict.provenance.evidence)
    }

    @Test
    fun `a prior verdict cites the step and the category row the prior came from`() {
        val verdict = classifyOne(history(listOf(occurrence("2026-08-10", 184_000L)), priorKey = "utilities"))

        assertEquals(listOf(StreamRules.COLD_START, RuleCitation("CLS-CAT-003", "1.0")), verdict.provenance.evidence)
        assertEquals(4_000, verdict.provenance.confidenceBps)
        assertTrue(verdict.isEstimate)
    }

    @Test
    fun `an obligation and a pin carry their own confidence`() {
        assertEquals(9_500, classifyOne(dining().copy(isKnownObligation = true)).provenance.confidenceBps)
        assertEquals(10_000, classifyOne(dining().copy(pin = StreamClass.FIXED)).provenance.confidenceBps)
        assertEquals(2_000, classifyOne(history(listOf(occurrence("2026-08-10", 1L)))).provenance.confidenceBps)
    }

    @Test
    fun `the profile cites every row any verdict used, once each, in a stable order`() {
        val profile =
            classify(
                listOf(
                    history(monthly(1, 2_500_000L), key = "rent"),
                    dining().copy(isKnownObligation = true),
                    history(listOf(occurrence("2026-08-10", 184_000L)), key = "utilities", priorKey = "utilities"),
                ),
            )

        assertEquals(
            listOf(
                StreamRules.KNOWN_OBLIGATION,
                StreamRules.SCORED,
                StreamRules.COLD_START,
                RuleCitation("CLS-CAT-003", "1.0"),
            ),
            profile.provenance.evidence,
        )
        assertTrue(profile.hasEstimates)
    }

    // --- refusals ---------------------------------------------------------------------------------

    @Test
    fun `an inverted window is refused`() {
        val input = StreamInput(WINDOW_END, WINDOW_START, emptyList(), NOW)
        assertEquals(Err(AppError.Validation("stream.window")), engine.classify(input))
    }

    @Test
    fun `a negative amount is refused rather than scored`() {
        val negative = history(listOf(occurrence("2026-08-10", -5_000L)))
        assertEquals(Err(AppError.Validation("stream.amount")), engine.classify(input(listOf(negative))))
    }

    @Test
    fun `a stream key used twice is refused`() {
        assertEquals(Err(AppError.Validation("stream.key")), engine.classify(input(listOf(groceries(), groceries()))))
    }

    // --- fixtures -----------------------------------------------------------------------------------

    private fun classify(
        streams: List<StreamHistory>,
        rules: StreamRules = StreamRules(),
    ): StreamProfile = engine.classify(input(streams, rules)).expectOk()

    private fun classifyOne(
        stream: StreamHistory,
        rules: StreamRules = StreamRules(),
    ): StreamVerdict = classify(listOf(stream), rules).streams.single()

    private fun input(
        streams: List<StreamHistory>,
        rules: StreamRules = StreamRules(),
    ) = StreamInput(WINDOW_START, WINDOW_END, streams, NOW, rules)

    private fun history(
        occurrences: List<StreamOccurrence>,
        key: String = "stream",
        priorKey: String? = null,
    ) = StreamHistory(streamKey = key, priorKey = priorKey, occurrences = occurrences)

    private fun occurrence(
        date: String,
        paise: Long,
    ) = StreamOccurrence(LocalDate.parse(date), Money(paise))

    private fun monthly(
        day: Int,
        amount: Long,
        months: IntRange = 3..8,
    ) = months.map { occurrence("2026-%02d-%02d".format(it, day), amount) }

    /** The golden file's groceries record: SEMI_FIXED, score 7 125.85 bps exactly. */
    private fun groceries() =
        history(
            listOf(
                "2026-03-02" to 310_000L, "2026-03-19" to 420_000L, "2026-04-06" to 365_000L,
                "2026-04-27" to 290_000L, "2026-05-11" to 455_000L, "2026-06-01" to 330_000L,
                "2026-06-22" to 398_000L, "2026-07-14" to 342_000L, "2026-08-05" to 377_000L,
                "2026-08-26" to 301_000L,
            ).map { (d, p) -> occurrence(d, p) },
            key = "groceries",
            priorKey = "groceries",
        )

    /** The golden file's dining record: VARIABLE, score 4 262.496 bps exactly. */
    private fun dining() =
        history(
            listOf(
                "2026-03-04" to 45_000L,
                "2026-03-09" to 120_000L,
                "2026-03-28" to 38_000L,
                "2026-05-16" to 260_000L,
                "2026-06-02" to 52_000L,
                "2026-06-03" to 41_000L,
                "2026-08-21" to 310_000L,
            ).map { (d, p) -> occurrence(d, p) },
            key = "dining",
            priorKey = "dining",
        )

    private fun <T> Result<T, AppError>.expectOk(): T =
        when (this) {
            is Ok -> value
            is Err -> throw AssertionError("expected Ok, got $error")
        }

    private companion object {
        val WINDOW_START: LocalDate = LocalDate.parse("2026-03-01")
        val WINDOW_END: LocalDate = LocalDate.parse("2026-08-31")
        const val NOW = 1_789_000_000_000L
    }
}
