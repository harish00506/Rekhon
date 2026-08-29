package com.aicfo.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the one thing [PriceKey] exists to guarantee (issue 6.5; §16 EXT-003).
 *
 * Why:  this type is a privacy control wearing the clothes of a string wrapper. EXT-003 says a
 *       market-data request carries "only instrument identifiers", and the character set is what
 *       makes that true by construction rather than by anyone remembering it. So the tests that
 *       matter are the *rejections*: every one of them is a thing that could otherwise have been
 *       typed by a user about themselves and then transmitted.
 * What: the accepted shape, and every way the constructor refuses.
 * Result: a request payload that provably cannot carry a nickname, a note or an amount.
 * Changelog: 2026-08-29 — Created for issue 6.5.
 *
 * `:core:model` is held to **100%** line coverage, so every `require` branch here is exercised.
 */
class PriceKeyTest {
    @Test
    fun `the namespaced keys the convention uses are accepted`() {
        listOf("gold:inr.gram.24k", "crypto:btc.inr", "mf:inf109k01z48", "a", "x-1_2.3:4")
            .forEach { key -> assertEquals("'$key' is a well-formed key", key, PriceKey(key).value) }
    }

    @Test
    fun `a key renders as the identifier itself, so joining one cannot leak a wrapper`() {
        assertEquals("gold:inr.gram.24k", "${PriceKey("gold:inr.gram.24k")}")
    }

    // --- the refusals, which are the point ------------------------------------------------------

    @Test
    fun `a key with a space is refused, because a sentence is not an identifier`() {
        assertThrows(IllegalArgumentException::class.java) { PriceKey("my gold") }
    }

    @Test
    fun `uppercase is refused, so two keys cannot differ only by case`() {
        // The DISTINCT price_key query would treat these as two instruments and ask the proxy for
        // both, which is one request too many and one of them always empty.
        assertThrows(IllegalArgumentException::class.java) { PriceKey("GOLD:INR") }
    }

    @Test
    fun `an empty key is refused`() {
        assertThrows(IllegalArgumentException::class.java) { PriceKey("") }
    }

    @Test
    fun `a key past the length ceiling is refused`() {
        val tooLong = "a".repeat(PriceKey.MAX_LENGTH + 1)

        assertThrows(IllegalArgumentException::class.java) { PriceKey(tooLong) }
        assertEquals(
            "the ceiling itself is allowed",
            PriceKey.MAX_LENGTH,
            PriceKey("a".repeat(PriceKey.MAX_LENGTH)).value.length,
        )
    }

    @Test
    fun `punctuation outside the four separators is refused`() {
        // Each of these is something a user might plausibly type into a free-text field, and each
        // would then have been sent to a server (EXT-003).
        listOf("gold@home", "₹5,000", "note#1", "a/b", "why not?", "gold\nkey")
            .forEach { key -> assertTrue("'$key' must be refused", runCatching { PriceKey(key) }.isFailure) }
    }

    @Test
    fun `the refusal explains why, naming the requirement`() {
        val thrown = assertThrows(IllegalArgumentException::class.java) { PriceKey("my gold") }

        assertTrue(thrown.message!!.contains("EXT-003"))
    }
}
