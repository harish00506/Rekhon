package com.aicfo.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Behaviour tests for [EngineProvenance] and [RuleCitation] (AI-ARC-003, AI-ARC-006, P-02).
 *
 * Why:  provenance is the mechanism two binding rules rest on. AI-ARC-006 says a result stays
 *       reproducible because the engine *version* that produced it was stored beside it, and P-02
 *       says a recommendation shows the rule that fired. Both fail silently if a result can be
 *       built with a blank id or an empty version — it would still compile, still render, and only
 *       be discovered when someone tried to reproduce a six-month-old insight. So the constructor
 *       refuses those, and these tests are what prove the refusal is real.
 * What: the identity/copy semantics engines rely on, the read-only evidence list, and every
 *       rejected construction.
 * Result: a provenance value cannot exist without saying who computed it, at what version, and
 *         when.
 * Changelog: 2026-07-27 — Created for issue 2.3 (the project's first engine result).
 */
class EngineProvenanceTest {
    // --- identity -------------------------------------------------------------------------

    /** Input: two provenances built from the same fields. Output: asserts they are equal. */
    @Test
    fun `provenance is a value - same fields means equal`() {
        assertEquals(provenance(), provenance())
        assertEquals(provenance().hashCode(), provenance().hashCode())
    }

    /**
     * Input:  two provenances differing only in engine version.
     * Output: asserts they are not equal. AI-ARC-006 exists so a result computed by v1.1 is
     *         distinguishable from the same numbers computed by v1.0; an `equals` that ignored the
     *         version would make that distinction unassertable in every downstream test.
     */
    @Test
    fun `a different engine version is a different provenance`() {
        assertNotEquals(provenance(), provenance().copy(engineVersion = "1.1"))
    }

    /** Input: a citation pair. Output: asserts rule id and version both take part in equality. */
    @Test
    fun `a citation is identified by both its rule id and version`() {
        assertEquals(RuleCitation("RULE-50-30-20", "1.0"), RuleCitation("RULE-50-30-20", "1.0"))
        assertNotEquals(RuleCitation("RULE-50-30-20", "1.0"), RuleCitation("RULE-50-30-20", "1.1"))
        assertNotEquals(RuleCitation("RULE-50-30-20", "1.0"), RuleCitation("RULE-SAVE-RATE", "1.0"))
    }

    // --- evidence -------------------------------------------------------------------------

    /** Input: a provenance with no rules. Output: asserts evidence defaults to empty, never null. */
    @Test
    fun `evidence defaults to empty rather than null`() {
        assertTrue(EngineProvenance("e", "1.0", computedAtUtcMillis = 1L).evidence.isEmpty())
    }

    /**
     * Input:  two provenances citing the same rules in different orders.
     * Output: asserts order is preserved and significant. The reasoning card renders evidence in
     *         the order the engine listed it — the rule that decided the headline figure first —
     *         so a comparison that treated evidence as a set would let a reordering regression
     *         through unnoticed.
     */
    @Test
    fun `evidence keeps the order the engine listed it in`() {
        val budgetFirst = listOf(RuleCitation("RULE-50-30-20", "1.0"), RuleCitation("RULE-DEBT-LOAD", "1.0"))
        val subject = EngineProvenance("e", "1.0", computedAtUtcMillis = 1L, evidence = budgetFirst)

        assertEquals(budgetFirst, subject.evidence)
        assertNotEquals(subject, subject.copy(evidence = budgetFirst.reversed()))
    }

    // --- what cannot be constructed -------------------------------------------------------

    /**
     * Input:  a blank engine id.
     * Output: asserts construction fails. An anonymous result cannot be traced back to the code
     *         that produced it, which is the whole point of AI-ARC-003.
     */
    @Test
    fun `an engine id is required`() {
        assertThrows(IllegalArgumentException::class.java) {
            EngineProvenance(engineId = " ", engineVersion = "1.0", computedAtUtcMillis = 1L)
        }
    }

    /** Input: a blank engine version. Output: asserts construction fails (AI-ARC-006). */
    @Test
    fun `an engine version is required`() {
        assertThrows(IllegalArgumentException::class.java) {
            EngineProvenance(engineId = "e", engineVersion = "", computedAtUtcMillis = 1L)
        }
    }

    /**
     * Input:  a negative instant.
     * Output: asserts construction fails. Epoch millis before 1970 cannot be a computation time in
     *         this app, and the value almost always means an uninitialised field reached here.
     */
    @Test
    fun `the computed-at instant cannot be negative`() {
        assertThrows(IllegalArgumentException::class.java) {
            EngineProvenance(engineId = "e", engineVersion = "1.0", computedAtUtcMillis = -1L)
        }
    }

    /** Input: a blank rule id or version. Output: asserts a citation cannot be anonymous either. */
    @Test
    fun `a citation cannot be blank`() {
        assertThrows(IllegalArgumentException::class.java) { RuleCitation(" ", "1.0") }
        assertThrows(IllegalArgumentException::class.java) { RuleCitation("RULE-50-30-20", " ") }
    }

    /**
     * Input:  a confidence outside 0..10 000 bps.
     * Output: asserts it is rejected. Confidence is a rate, so MNY-002 makes it integer basis
     *         points; a value of 120 000 would render as "1200% sure".
     */
    @Test
    fun `confidence must be a rate in basis points`() {
        assertThrows(IllegalArgumentException::class.java) {
            EngineProvenance("e", "1.0", computedAtUtcMillis = 1L, confidenceBps = 10_001)
        }
        assertThrows(IllegalArgumentException::class.java) {
            EngineProvenance("e", "1.0", computedAtUtcMillis = 1L, confidenceBps = -1)
        }
        // The bounds themselves are legal: 0% and 100% confidence are both meaningful answers.
        assertEquals(0, EngineProvenance("e", "1.0", 1L, confidenceBps = 0).confidenceBps)
        assertEquals(10_000, EngineProvenance("e", "1.0", 1L, confidenceBps = 10_000).confidenceBps)
    }

    private fun provenance() =
        EngineProvenance(
            engineId = "quick-setup",
            engineVersion = "1.0",
            computedAtUtcMillis = 1_753_000_000_000L,
            evidence = listOf(RuleCitation("RULE-50-30-20", "1.0")),
        )
}
