package com.aicfo.core.common

import kotlin.random.Random

/**
 * A reproducible sequence of random cases for a property test (issue 12.1; §21.5, P-08).
 *
 * Why:  §21.5 asks every engine for property tests, and P-08 says randomness comes only from an
 *       injected, seedable source. A property test written without that looks rigorous and is not:
 *       it fails on Tuesday, passes on Wednesday, and leaves nobody able to reproduce either. The
 *       failure message is the whole deliverable here — a reader must be able to re-run the exact
 *       case from it.
 * What: `count` cases, each with its own [Random] derived from `seed`, and a failure that names the
 *       seed and the case index.
 * Result: `SeededCases(seed = 20261002, count = 500).forEach { random -> … }` — and a failure that
 *       says which case, under which seed, so it can be re-run on demand.
 * Changelog: 2026-10-02 — Created for issue 12.1.
 *
 * Input:  [seed] — the one number that reproduces the whole run; pick a fixed literal in the test,
 *         never a clock. [count] — how many cases; must be at least one, because a property test
 *         over zero cases asserts nothing.
 * Output: an iterable-like driver.
 *
 * **Each case gets a fresh [Random] seeded from the sequence**, rather than all cases sharing one.
 * That means case 7 generates the same values whether or not cases 1–6 consumed any — so shrinking a
 * failure by hand (re-running with `count = 8`) reproduces it, which a shared source would break.
 */
class SeededCases(
    private val seed: Long,
    private val count: Int,
) {
    init {
        require(count >= 1) { "a property test needs at least one case; count was $count" }
    }

    /**
     * Runs [case] for every generated source, stopping at the first failure.
     * Why:    stopping first rather than collecting: a run that continued would report whichever
     *         case failed last, which is rarely the simplest and never the one the message's seed
     *         points at.
     * Result: returns normally when every case passed; otherwise throws an [AssertionError] naming
     *         the seed and index, with the original failure as its cause.
     * Input:  [case] — the property, given a seeded [Random]. Output: none.
     * Changelog: 2026-10-02 — Created for issue 12.1.
     */
    fun forEach(case: (Random) -> Unit) {
        val sequence = Random(seed)
        repeat(count) { index ->
            val caseSeed = sequence.nextLong()
            try {
                case(Random(caseSeed))
            } catch (failure: Throwable) {
                throw AssertionError(
                    "property failed at case $index of $count (seed = $seed, case seed = $caseSeed). " +
                        "Re-run with SeededCases(seed = $seed, count = ${index + 1}) to reproduce it.",
                    failure,
                )
            }
        }
    }

    /**
     * Collects a value from every case.
     * Why:    the tests of this class need to compare two runs' outputs, and an engine's property
     *         test sometimes wants the distribution rather than a per-case assertion.
     * Result: one value per case, in order. Input: [of] — maps a seeded source to a value.
     * Output: a list of [T].
     * Changelog: 2026-10-02 — Created for issue 12.1.
     */
    fun <T> map(of: (Random) -> T): List<T> {
        val sequence = Random(seed)
        return List(count) { of(Random(sequence.nextLong())) }
    }
}

/**
 * Asserts a subject gives the same answer twice at the same seed (issue 12.1; P-08).
 *
 * Why:  P-08 promises fixed input → fixed output, and the way that promise breaks is never dramatic:
 *       a `hashCode` in an iteration order, a `System.currentTimeMillis()` deep in a helper, a global
 *       `Random`. A "determinism test" that runs the engine once cannot see any of it. This runs the
 *       subject **twice**, each time with a fresh source at the same seed, and compares.
 * What: two runs, one comparison, and a failure that says which is which.
 * Result: a subject reading a clock or a shared random fails here and nowhere else.
 * Changelog: 2026-10-02 — Created for issue 12.1.
 *
 * Input:  [seed] — the seed both runs get; [subject] — the work under test, given a seeded [Random].
 * Output: none; throws [AssertionError] when the two runs disagree.
 *
 * Compares with `==`, so the subject should return a value class, data class or collection. A subject
 * returning something without a meaningful `equals` will fail — correctly, since such a result could
 * not be compared across runs by anyone.
 */
fun <T> assertDeterministic(
    seed: Long,
    subject: (Random) -> T,
) {
    val first = subject(Random(seed))
    val second = subject(Random(seed))
    if (first != second) {
        throw AssertionError(
            "not deterministic at seed $seed (P-08): the same input gave two answers.\n" +
                "  first run:  $first\n" +
                "  second run: $second\n" +
                "Look for a clock read, a global Random, or an iteration order that depends on a " +
                "hash code.",
        )
    }
}
