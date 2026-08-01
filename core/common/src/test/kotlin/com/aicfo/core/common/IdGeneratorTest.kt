package com.aicfo.core.common

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Covers the id seam (issue 2.5; P-08).
 *
 * Why:  two properties matter and they pull in opposite directions. Production ids must be
 *       **unique without a coordinator** — an offline-first app has no server handing out sequence
 *       numbers (P-04). Test ids must be **predictable**, or no golden assertion over generated
 *       rows can ever be written. Both are asserted here, because the whole point of the interface
 *       is that the two implementations differ in exactly this way and in no other.
 * What: uniqueness and prefixing for [UuidIdGenerator]; determinism and counting for
 *       [FakeIdGenerator].
 * Result: a repository can be handed either one and behave the same.
 * Changelog: 2026-07-28 — Created for issue 2.5.
 */
class IdGeneratorTest {
    @Test
    fun `the real generator never repeats an id`() {
        val generator = UuidIdGenerator()
        val ids = List(SAMPLE_SIZE) { generator.newId("account") }

        assertEquals(SAMPLE_SIZE, ids.toSet().size)
    }

    @Test
    fun `the real generator prefixes the id with the row kind`() {
        assertTrue(UuidIdGenerator().newId("account").startsWith("account:"))
    }

    @Test
    fun `the real generator keeps different prefixes apart`() {
        val generator = UuidIdGenerator()

        assertNotEquals(
            generator.newId("account").substringBefore(':'),
            generator.newId("transaction").substringBefore(':'),
        )
    }

    @Test
    fun `an empty prefix still yields a usable id`() {
        // Not a case any caller should hit, but it must not produce a blank id.
        assertTrue(UuidIdGenerator().newId("").length > 1)
    }

    @Test
    fun `the fake counts up from one`() {
        val generator = FakeIdGenerator()

        assertEquals("account:1", generator.newId("account"))
        assertEquals("account:2", generator.newId("account"))
    }

    @Test
    fun `two fakes with the same start agree — that is what determinism means`() {
        // The property a golden test relies on: re-running the same code gives the same ids.
        val first = FakeIdGenerator()
        val second = FakeIdGenerator()

        assertEquals(first.newId("account"), second.newId("account"))
    }

    @Test
    fun `the fake can be started elsewhere so two generators do not collide`() {
        assertEquals("account:100", FakeIdGenerator(startAt = 100).newId("account"))
    }

    @Test
    fun `the fake counter is shared across prefixes`() {
        // One counter, not one per prefix: a test asserting "nothing else minted an id" needs a
        // single number to check, and per-prefix counters would hide a stray call.
        val generator = FakeIdGenerator()
        generator.newId("account")

        assertEquals("transaction:2", generator.newId("transaction"))
    }

    @Test
    fun `the fake reports how many ids it handed out`() {
        val generator = FakeIdGenerator()

        assertEquals(0, generator.issuedCount)
        generator.newId("account")
        generator.newId("account")
        assertEquals(2, generator.issuedCount)
    }

    private companion object {
        /** Enough draws that a broken generator repeating itself would be caught. */
        const val SAMPLE_SIZE = 1_000
    }
}
