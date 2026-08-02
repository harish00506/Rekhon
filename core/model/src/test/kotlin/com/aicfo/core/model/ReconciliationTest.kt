package com.aicfo.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for [Reconciliation] (issue 2.7; FR-ACC-006, P-02).
 *
 * Why:  the type carries three related amounts, and the whole reason it exists is that they must
 *       stay consistent with each other — a screen showing a `before` from one moment and a `delta`
 *       from another would be the exact "wrong but plausible" figure ADR-0007 argues against. What
 *       is worth pinning here is the **identity between them** (`before + delta == statement`) and
 *       the meaning of a null [Reconciliation.adjustmentId], because that null is what tells a
 *       caller nothing was written.
 * What: the identity in both directions, and [Reconciliation.isAlreadyInStep].
 * Result: `:core:model`'s share of FR-ACC-006 is proven, and its 100% line-coverage gate is met.
 * Changelog: 2026-08-02 — Created for issue 2.7.
 *
 * The **arithmetic itself is not done here** — `AccountRepository.reconcile` computes the delta and
 * `AccountReconciliationTest` proves it against real SQL (P-03: one place produces the number).
 * These tests assert the shape a caller can rely on.
 */
class ReconciliationTest {
    @Test
    fun `before plus delta is the statement — an inflow`() {
        val outcome = reconciliation(before = Money(92_500_00L), statement = Money(93_000_00L))

        assertEquals(Money(500_00L), outcome.delta)
        assertEquals(outcome.statement, outcome.before + outcome.delta)
    }

    @Test
    fun `before plus delta is the statement — an outflow`() {
        // The liability direction: a card's balance is held negative, so correcting it downwards
        // means a negative adjustment on an already-negative figure.
        val outcome = reconciliation(before = Money(-18_000_00L), statement = Money(-19_250_00L))

        assertEquals(Money(-1_250_00L), outcome.delta)
        assertEquals(outcome.statement, outcome.before + outcome.delta)
    }

    @Test
    fun `an adjustment that was written is not in step`() {
        val outcome = reconciliation(before = Money(92_500_00L), statement = Money(93_000_00L))

        assertFalse(outcome.isAlreadyInStep)
        assertEquals("txn:1", outcome.adjustmentId)
    }

    @Test
    fun `a null adjustment id means nothing was written`() {
        // The signal a caller reads to know the app already agreed with the statement. A zero delta
        // alone would not say it: a zero-amount row is a thing this app deliberately never writes,
        // so the id is what distinguishes "nothing to do" from "wrote an adjustment of nothing".
        val outcome =
            reconciliation(before = Money(92_500_00L), statement = Money(92_500_00L), adjustmentId = null)

        assertTrue(outcome.isAlreadyInStep)
        assertEquals(Money.ZERO, outcome.delta)
    }

    @Test
    fun `it is a value — two reconciliations of the same facts are equal`() {
        // `data class` with `val` everywhere (CLAUDE.md §5). Equality by content is what lets a test
        // assert a whole outcome in one line rather than field by field.
        assertEquals(reconciliation(), reconciliation())
        assertEquals(reconciliation().hashCode(), reconciliation().hashCode())
        assertEquals(Money(1_000_00L), reconciliation().copy(delta = Money(1_000_00L)).delta)
        assertTrue("the day is carried for the caller to show", reconciliation().toString().contains("2026-08-02"))
    }

    /**
     * Result: a reconciliation with the delta already consistent with its two balances.
     * Why:    the delta is `statement − before` everywhere in the app, so a fixture that let a test
     *         set it independently would allow an inconsistent value no real caller can produce.
     * Input:  [before]; [statement]; [adjustmentId] — `null` when nothing was written.
     * Output: [Reconciliation].
     */
    private fun reconciliation(
        before: Money = Money(92_500_00L),
        statement: Money = Money(93_000_00L),
        adjustmentId: String? = "txn:1",
    ) = Reconciliation(
        accountId = "account:1",
        before = before,
        statement = statement,
        delta = statement - before,
        adjustmentId = adjustmentId,
        bookedOnIsoDate = "2026-08-02",
    )
}
