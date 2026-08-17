package com.aicfo.widget

import androidx.datastore.preferences.core.mutablePreferencesOf
import com.aicfo.core.model.Money
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for [toWidgetSnapshot] — reading the widget cache (issue 5.5; MNY-001, P-03).
 *
 * Why:  this conversion is the seam between two modules that never call each other. `:app`'s
 *       `WidgetRefreshWorker` writes keys; `:widget`'s composable reads a snapshot; nothing at
 *       compile time connects the two beyond [WidgetKeys]. The failure this guards against is
 *       quiet: a missing key silently becoming `0` would put a ₹0.00 on the home screen of every
 *       user who has not been onboarded yet, and no exception would be thrown.
 * What: the empty store, a full store, absence per field, and the sign.
 * Result: absence stays absence all the way from an unwritten preference to a pending label.
 * Changelog: 2026-08-17 — Created for issue 5.5.
 */
class WidgetSnapshotTest {
    /**
     * Input: the state of a widget just dropped on the home screen, before any refresh.
     * Output: asserts both figures are `null` and the blur is off — never `Money.ZERO`.
     */
    @Test
    fun `empty preferences mean no figure, not zero`() {
        val snapshot = mutablePreferencesOf().toWidgetSnapshot()

        assertNull(snapshot.safeToSpend)
        assertNull(snapshot.netWorth)
        assertEquals(false, snapshot.blurred)
    }

    /** Input: both figures and the flag written. Output: asserts each lands in its own field. */
    @Test
    fun `reads back what the worker wrote`() {
        val preferences =
            mutablePreferencesOf(
                WidgetKeys.SafeToSpendMinor to 12_345_67L,
                WidgetKeys.NetWorthMinor to 98_76_543_21L,
                WidgetKeys.Blurred to true,
            )

        val snapshot = preferences.toWidgetSnapshot()

        assertEquals(Money(12_345_67L), snapshot.safeToSpend)
        assertEquals(Money(98_76_543_21L), snapshot.netWorth)
        assertTrue(snapshot.blurred)
    }

    /**
     * Input: only net worth written — a profile with a snapshot but no income basis, which is what
     * `SafeToSpendRepository` answers with `null`.
     * Output: asserts the two figures are independent; one being absent does not blank the other.
     */
    @Test
    fun `one figure can be absent while the other is not`() {
        val snapshot = mutablePreferencesOf(WidgetKeys.NetWorthMinor to 5_00_000_00L).toWidgetSnapshot()

        assertNull(snapshot.safeToSpend)
        assertEquals(Money(5_00_000_00L), snapshot.netWorth)
    }

    /**
     * Input: a negative Safe-to-Spend — the overspent month, and the reading a user most needs.
     * Output: asserts the sign survives the round trip rather than being clamped or dropped.
     */
    @Test
    fun `a negative Safe-to-Spend survives`() {
        val snapshot = mutablePreferencesOf(WidgetKeys.SafeToSpendMinor to -2_500_00L).toWidgetSnapshot()

        assertEquals(Money(-2_500_00L), snapshot.safeToSpend)
    }
}
