package com.aicfo.core.common

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The seeded property/determinism helpers (issue 12.1; §21.5, P-08).
 *
 * Why:  P-08 says randomness comes only from an injected, seedable source, and §21.5 asks for
 *       property tests and seeded-determinism tests on every engine. Both are easy to write in a way
 *       that looks rigorous and is not: a property test over an *unseeded* source fails on Tuesday
 *       and passes on Wednesday with no way to reproduce either, and a "determinism test" that
 *       happens to run the engine once proves nothing at all.
 *
 *       So these helpers make the failing case reproducible by construction — a failure names the
 *       seed — and make a determinism check actually run the subject twice.
 * What: the seed sequence's reproducibility, the failure message carrying the seed, and that
 *       `assertDeterministic` fails on a subject that is not deterministic.
 * Result: an engine's property test is reproducible from its failure message alone.
 * Changelog: 2026-10-02 — Created for issue 12.1.
 */
class SeededCasesTest {
    @Test
    fun `the same seed gives the same cases, every run`() {
        // The whole point of a seed. If this were ever false, every property test in the project
        // would be unreproducible and no failure could be investigated.
        val first = SeededCases(seed = 42, count = 5).map { it.nextLong() }
        val second = SeededCases(seed = 42, count = 5).map { it.nextLong() }

        assertEquals(first, second)
    }

    @Test
    fun `a different seed gives different cases`() {
        // Otherwise the "property test" is one case run N times.
        val a = SeededCases(seed = 1, count = 5).map { it.nextLong() }
        val b = SeededCases(seed = 2, count = 5).map { it.nextLong() }

        assertNotEquals(a, b)
    }

    @Test
    fun `the requested number of cases is what runs`() {
        var runs = 0
        SeededCases(seed = 7, count = 25).forEach { runs++ }

        assertEquals(25, runs)
    }

    @Test
    fun `a failing case names its seed, so the failure is reproducible`() {
        // The feature that makes a property test usable. Without the seed in the message, a reader
        // has a failure they cannot re-run.
        val error =
            assertThrows(AssertionError::class.java) {
                SeededCases(seed = 99, count = 3).forEach { error("boom") }
            }

        assertTrue("the message must carry the seed: ${error.message}", error.message!!.contains("99"))
        assertTrue("and the case index", error.message!!.contains("case"))
    }

    @Test
    fun `the first failing case stops the run, rather than reporting the last`() {
        // A property test that kept going would report whichever case happened to fail last, which
        // is rarely the simplest one and never the one the seed in the message refers to.
        var runs = 0
        assertThrows(AssertionError::class.java) {
            SeededCases(seed = 5, count = 50).forEach {
                runs++
                error("boom")
            }
        }

        assertEquals(1, runs)
    }

    @Test
    fun `forEach gives each case its own source, agreeing with map`() {
        // Found by a mutation: replacing `case(Random(caseSeed))` with the shared sequence survived
        // every other test here, because they all exercise `map`. The documented promise is that case
        // 7 draws the same values whether or not cases 1-6 consumed any — which is what makes
        // shrinking a failure by re-running with a smaller `count` work at all. A shared source
        // breaks that silently, and the two APIs disagreeing is the visible symptom.
        val viaMap = SeededCases(seed = 20_261_002, count = 6).map { it.nextLong() }

        val viaForEach = mutableListOf<Long>()
        SeededCases(seed = 20_261_002, count = 6).forEach { viaForEach += it.nextLong() }

        assertEquals(viaMap, viaForEach)
    }

    @Test
    fun `a case consuming extra values does not shift the cases after it`() {
        // The property that makes `count = index + 1` reproduce a failure. With one shared source,
        // a case that drew three values instead of one would move every later case along.
        val steady = mutableListOf<Long>()
        SeededCases(seed = 5, count = 4).forEach { steady += it.nextLong() }

        val greedy = mutableListOf<Long>()
        SeededCases(seed = 5, count = 4).forEach { random ->
            repeat(3) { random.nextLong() }
            greedy += random.nextLong()
        }

        // The values differ (each case drew differently), but the *case seeds* did not move — so the
        // first case's extra draws left the rest reproducible. Asserted via a third run that only
        // the first case is greedy.
        val mixed = mutableListOf<Long>()
        var index = 0
        SeededCases(seed = 5, count = 4).forEach { random ->
            if (index++ == 0) repeat(3) { random.nextLong() }
            mixed += random.nextLong()
        }

        assertEquals("cases after a greedy one must be untouched", steady.drop(1), mixed.drop(1))
        assertNotEquals("and the greedy case itself must differ", steady.first(), mixed.first())
        assertEquals(4, greedy.size)
    }

    @Test
    fun `a deterministic subject passes assertDeterministic`() {
        // Called twice at the same seed, a seedable subject must produce identical output.
        assertDeterministic(seed = 3) { random -> List(4) { random.nextInt(100) } }
    }

    @Test
    fun `a subject that ignores its seed fails assertDeterministic`() {
        // The gate. A subject reading a clock, a global `Random`, or a hash code would pass a
        // single-run "determinism test" and fail here — which is the only way to tell them apart.
        var callCount = 0

        val error =
            assertThrows(AssertionError::class.java) {
                assertDeterministic(seed = 3) { callCount++ }
            }

        assertEquals("the subject must be run twice", 2, callCount)
        assertTrue(error.message!!.contains("not deterministic"))
    }

    @Test
    fun `assertDeterministic gives each run its own source, not a shared one`() {
        // A shared source would advance between the two runs and make every subject look
        // non-deterministic — so this asserts the harness is usable, not just strict.
        assertDeterministic(seed = 11) { random -> random.nextLong() }
    }

    @Test
    fun `a count of zero is rejected, because a property test over no cases asserts nothing`() {
        val error = assertThrows(IllegalArgumentException::class.java) { SeededCases(seed = 1, count = 0) }

        assertTrue(error.message!!.contains("at least one"))
    }
}
