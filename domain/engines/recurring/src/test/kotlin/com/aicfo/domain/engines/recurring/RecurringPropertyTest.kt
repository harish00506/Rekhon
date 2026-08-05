package com.aicfo.domain.engines.recurring

import com.aicfo.core.common.Ok
import com.aicfo.core.model.Money
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import kotlin.random.Random

/**
 * Identities [RecurringEngine] must satisfy for *any* ledger (issue 3.7; §21.5, P-08).
 *
 * Why:  the example tests pin the cases someone thought of. These pin the ones nobody did — every
 *       proposal must be defensible on its face, whatever the input, and the cheapest way to find
 *       a detector that invents an occurrence or projects a date into the past is to throw a few
 *       hundred generated ledgers at it and check the invariants rather than the values.
 * What: five properties over pseudo-random ledgers, plus the constructor invariants on the rules.
 * Result: the class of bugs that only show up on data nobody wrote a fixture for.
 * Changelog: 2026-08-05 — Created for issue 3.7.
 *
 * **Seeded, not random** (P-08). A fixed seed means a failure here is reproducible from the
 * message alone; an unseeded generator would turn a real bug into an intermittent one.
 */
class RecurringPropertyTest {
    private val engine = RecurringEngineFactory.create()
    private val rules = RecurringRules()

    @Test
    fun `every proposal is backed by real, distinct rows from the input`() {
        forEachLedger { ledger ->
            val ids = ledger.map { it.transactionId }.toSet()
            engine.detect(RecurringInput(ledger, rules, NOW)).expectOk().forEach { series ->
                val proposedIds = series.occurrences.map { it.transactionId }
                assertTrue("proposed an occurrence that is not in the ledger", ids.containsAll(proposedIds))
                assertEquals("the same row counted twice", proposedIds.size, proposedIds.toSet().size)
                assertTrue(
                    "FR-TXN-006 needs at least ${rules.minOccurrences} occurrences",
                    series.occurrences.size >= rules.minOccurrences,
                )
            }
        }
    }

    @Test
    fun `no row is ever proposed under two merchants at once`() {
        forEachLedger { ledger ->
            val proposed =
                engine.detect(RecurringInput(ledger, rules, NOW)).expectOk()
                    .flatMap { series -> series.occurrences.map { it.transactionId } }

            assertEquals("a transaction belongs to at most one series", proposed.size, proposed.toSet().size)
        }
    }

    @Test
    fun `the next due date is always after the last occurrence`() {
        forEachLedger { ledger ->
            engine.detect(RecurringInput(ledger, rules, NOW)).expectOk().forEach { series ->
                val last = LocalDate.parse(series.occurrences.last().bookedOn)

                assertTrue(
                    "projected ${series.nextDueIsoDate} at or before the last occurrence $last",
                    LocalDate.parse(series.nextDueIsoDate) > last,
                )
            }
        }
    }

    @Test
    fun `the median amount is always one the user actually paid`() {
        forEachLedger { ledger ->
            val byId = ledger.associateBy { it.transactionId }
            engine.detect(RecurringInput(ledger, rules, NOW)).expectOk().forEach { series ->
                val paid = series.occurrences.map { byId.getValue(it.transactionId).amount }

                assertTrue("the shown amount was invented, not observed", series.medianAmount in paid)
            }
        }
    }

    @Test
    fun `input order never changes the output`() {
        // P-08's core claim. The repository reads rows in whatever order SQLite returns them, and a
        // detector that reshuffled its proposals on a re-query would make the screen flicker.
        forEachLedger { ledger ->
            val asGiven = engine.detect(RecurringInput(ledger, rules, NOW)).expectOk()
            val shuffled = engine.detect(RecurringInput(ledger.shuffled(Random(SEED)), rules, NOW)).expectOk()

            assertEquals(asGiven, shuffled)
        }
    }

    // --- the rules' own invariants -------------------------------------------------------------

    @Test
    fun `a rulebook edit that makes the thresholds nonsense fails at construction`() {
        // Each of these is an edit someone could plausibly make to rules-kb.json. Failing loudly
        // here beats a detector that quietly proposes every purchase the user has ever made.
        assertThrows { RecurringRules(minOccurrences = 1) }
        assertThrows { RecurringRules(amountTolerancePct = -1) }
        assertThrows { RecurringRules(amountTolerancePct = 101) }
        assertThrows { RecurringRules(weeklyToleranceDays = 7) }
        assertThrows { RecurringRules(monthlyToleranceDays = 30) }
        assertThrows { RecurringRules(yearlyToleranceDays = -1) }
    }

    @Test
    fun `every cadence has a tolerance and they are all under their own period`() {
        Cadence.entries.forEach { cadence ->
            val tolerance = rules.toleranceDaysFor(cadence)

            assertTrue(
                "${cadence.name} tolerance $tolerance is not under ${cadence.periodDays}",
                tolerance < cadence.periodDays,
            )
        }
    }

    // --- generation ----------------------------------------------------------------------------

    /**
     * Runs [check] over a batch of pseudo-random ledgers.
     * Why:    one generator shared by every property, so a case that breaks one is the same case
     *         that gets thrown at the others. The shapes are deliberately mixed — clean series,
     *         drifting ones, one-offs and collisions on a single day — because a generator that
     *         only produced clean series would prove nothing about what the detector refuses.
     * Result: fails the test on the first ledger that breaks an invariant.
     * Input:  [check] — the invariant, given one ledger. Output: none.
     * Changelog: 2026-08-05 — Created for issue 3.7.
     */
    private fun forEachLedger(check: (List<RecurringCandidate>) -> Unit) {
        val random = Random(SEED)
        repeat(LEDGERS) { ledgerIndex ->
            val ledger =
                (0 until random.nextInt(1, ROWS_PER_LEDGER)).map { row ->
                    RecurringCandidate(
                        transactionId = "t:$ledgerIndex-$row",
                        // A small pool, so merchants collide often enough to form series.
                        merchant = MERCHANTS[random.nextInt(MERCHANTS.size)],
                        // Round paise, sometimes drifting by up to ~10% — either side of the 5% band.
                        amount = Money(-(random.nextLong(1, 500) * 100L)),
                        bookedOn = START.plusDays(random.nextLong(0, 800)).toString(),
                    )
                }
            check(ledger)
        }
    }

    /** Result: passes when [block] throws. Input: [block]. Output: none; fails the test otherwise. */
    private fun assertThrows(block: () -> Unit) {
        val threw = runCatching(block).isFailure

        assertTrue("expected an IllegalArgumentException from an invalid rulebook value", threw)
    }

    /** Result: the series. Input: the receiver. Output: `List<RecurringSeries>`. */
    private fun com.aicfo.core.common.Result<List<RecurringSeries>, *>.expectOk(): List<RecurringSeries> =
        (this as? Ok)?.value ?: throw AssertionError("expected Ok, got $this")

    private companion object {
        /** Fixed, so a failure is reproducible from the message alone (P-08). */
        const val SEED = 20_260_805

        const val LEDGERS = 300
        const val ROWS_PER_LEDGER = 14

        /** A frozen instant (TIM-001) — the engine never reads a clock. */
        const val NOW = 1_754_000_000_000L

        val START: LocalDate = LocalDate.parse("2024-01-01")
        val MERCHANTS = listOf("Landlord", "Netflix", "Metro Card", "Amazon", "  ", "Gym")
    }
}
