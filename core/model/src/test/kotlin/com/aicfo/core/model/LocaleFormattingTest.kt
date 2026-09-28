package com.aicfo.core.model

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

/**
 * What must not change when the app speaks another language (issue 10.8; §3.5, MNY-001, TIM-002).
 *
 * Why:  localisation is where number and date formatting quietly break, and money is the worst
 *       place for it to happen. Three things are asserted, and each is a real failure mode:
 *
 *       **Indian grouping survives the locale.** `1,00,000` is 2,2,3 in every Indian language, and
 *       [MoneyFormatter] hardcodes that rule rather than asking the platform — this test is what
 *       makes that claim true rather than merely intended.
 *
 *       **The digits stay Latin.** A locale can carry a numbering system: ask the JDK for a Hindi
 *       number and some configurations answer in Devanagari digits. `१,२३,४५६` is not wrong to
 *       read, but this app's own parser rejects it (`MoneyFormatter.parse` accepts ASCII only, and
 *       says why), so a formatter that produced it would write amounts the app could not read back.
 *
 *       **Dates do follow the locale** — that is the half of §3.5 that *should* change, and
 *       without a test the two halves are easy to get backwards.
 * What: money and dates rendered under Hindi, Kannada and Tamil.
 * Result: switching language changes the words and not the arithmetic.
 * Changelog: 2026-09-28 — Created for issue 10.8.
 */
class LocaleFormattingTest {
    private val original: Locale = Locale.getDefault()

    /** Input: none. Output: the JVM's default locale is restored, whatever a test set it to. */
    @After
    fun tearDown() {
        Locale.setDefault(original)
    }

    @Test
    fun `an amount is grouped the Indian way in every language the app ships`() {
        SHIPPED.forEach { tag ->
            Locale.setDefault(Locale.forLanguageTag(tag))

            assertEquals("in $tag", "₹1,23,456.78", MoneyFormatter.format(Money(1_23_456_78L)))
            assertEquals("in $tag", "₹1,00,000.00", MoneyFormatter.format(Money(1_00_000_00L)))
            assertEquals("in $tag", "-₹5,000.50", MoneyFormatter.format(Money(-5_000_50L)))
        }
    }

    @Test
    fun `an amount is written in digits this app can read back`() {
        SHIPPED.forEach { tag ->
            Locale.setDefault(Locale.forLanguageTag(tag))
            val formatted = MoneyFormatter.format(Money(1_23_456_78L))

            assertTrue("in $tag: $formatted", formatted.filter { it.isDigit() }.all { it in '0'..'9' })
            assertEquals("in $tag round-trip", Money(1_23_456_78L), MoneyFormatter.parse(formatted))
        }
    }

    @Test
    fun `the masked amount is the same shape in every language`() {
        // The blur leaks nothing about magnitude (§23); a locale must not widen or narrow it.
        SHIPPED.forEach { tag ->
            Locale.setDefault(Locale.forLanguageTag(tag))

            assertEquals("in $tag", MoneyFormatter.mask(Money(450_00L)), MoneyFormatter.mask(Money(4_50_000_00L)))
        }
    }

    @Test
    fun `a date does follow the language, because that half is meant to change`() {
        Locale.setDefault(Locale.ENGLISH)
        val english = DateFormatter.day("2026-08-15")
        Locale.setDefault(Locale.forLanguageTag("hi"))
        val hindi = DateFormatter.day("2026-08-15")

        assertNotEquals("a date that ignores the language is not localised", english, hindi)
        assertTrue("the day number must stay readable: $hindi", hindi.contains("15"))
    }

    private companion object {
        val SHIPPED = listOf("hi", "kn", "ta")
    }
}
