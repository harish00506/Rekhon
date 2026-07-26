package com.aicfo.feature.onboarding

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for [OnboardingOptions] — the time-zone list (issue 2.1; TIM-001, P-06).
 *
 * Why:  written after the emulator showed `Asia/Calcutta` and `Asia/Kolkata` sitting next to each
 *       other in the list as two apparently different answers. Many Indian devices still report the
 *       legacy alias, so that duplicate would have shipped to this app's primary market on the one
 *       screen where the choice has to be unambiguous. A test that only checked `distinct()` would
 *       have passed the whole time.
 * What: the ordering guarantee, alias collapsing, and the unknown-zone case.
 * Result: the offered list is proven to have one entry per real zone, device zone first.
 * Changelog: 2026-07-25 — Created for issue 2.1 after the defect was found on a device.
 */
class OnboardingOptionsTest {
    /**
     * Input:  a device reporting the legacy Indian alias.
     * Output: asserts only one Indian zone is offered — the device's own — rather than the alias
     *         and its modern name as separate choices.
     */
    @Test
    fun `collapses a legacy alias against its modern name`() {
        val zones = OnboardingOptions.zoneIds("Asia/Calcutta")
        assertEquals("Asia/Calcutta", zones.first())
        assertFalse("Asia/Kolkata is the same zone under another name", zones.contains("Asia/Kolkata"))
        assertTrue(zones.contains("Asia/Dubai"))
    }

    /**
     * Input:  a device already reporting the modern id.
     * Output: asserts it appears once and stays first — the common list must not push a duplicate
     *         of the device zone further down.
     */
    @Test
    fun `the device zone appears exactly once and first`() {
        val zones = OnboardingOptions.zoneIds("Asia/Kolkata")
        assertEquals("Asia/Kolkata", zones.first())
        assertEquals(1, zones.count { it == "Asia/Kolkata" })
    }

    /**
     * Input:  a device zone outside the curated list.
     * Output: asserts it is offered anyway, so a user in Oslo is never stranded picking a zone that
     *         is not theirs.
     */
    @Test
    fun `an uncommon device zone is still offered`() {
        val zones = OnboardingOptions.zoneIds("Europe/Oslo")
        assertEquals("Europe/Oslo", zones.first())
        assertTrue(zones.contains("Asia/Kolkata"))
    }

    /**
     * Input:  a zone id this JVM cannot parse — a corrupt value, or one from a newer build.
     * Output: asserts it is dropped rather than thrown, leaving a usable list. The same defensive
     *         stance `ProfileZoneProvider` takes: a wrong zone is recoverable, a crash on the first
     *         screen of a new install is not.
     */
    @Test
    fun `an unparseable zone id is dropped rather than thrown`() {
        val zones = OnboardingOptions.zoneIds("Not/AZone")
        assertFalse(zones.contains("Not/AZone"))
        assertEquals("Asia/Kolkata", zones.first())
    }

    /** Input: the currency list. Output: asserts v1 offers the rupee (P-06). */
    @Test
    fun `offers the rupee`() {
        assertEquals(listOf("INR"), OnboardingOptions.currencies)
    }
}
