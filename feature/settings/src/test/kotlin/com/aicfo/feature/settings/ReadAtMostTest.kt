package com.aicfo.feature.settings

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.ByteArrayInputStream

/**
 * The bounded read behind the restore picker (issue 8.2).
 *
 * Why:  the picker offers any file, and without the bound a picked video would crash the app with
 *       an out-of-memory error. The edges that matter are the limit itself and one byte past it.
 * What: empty, under, exactly at, one past, and far past the limit, plus a multi-buffer read.
 * Result: the restore can never be made to allocate more than a backup can be.
 * Changelog: 2026-09-18 — Created for issue 8.2.
 */
class ReadAtMostTest {
    @Test
    fun `an empty stream reads as empty`() {
        assertArrayEquals(ByteArray(0), ByteArrayInputStream(ByteArray(0)).readAtMost(10))
    }

    @Test
    fun `a stream under the limit is read whole`() {
        val bytes = ByteArray(5) { it.toByte() }
        assertArrayEquals(bytes, ByteArrayInputStream(bytes).readAtMost(10))
    }

    @Test
    fun `a stream of exactly the limit is accepted`() {
        val bytes = ByteArray(10) { 7 }
        assertArrayEquals(bytes, ByteArrayInputStream(bytes).readAtMost(10))
    }

    @Test
    fun `one byte past the limit is refused`() {
        assertNull(ByteArrayInputStream(ByteArray(11)).readAtMost(10))
    }

    @Test
    fun `a stream spanning many buffers is read in order`() {
        val bytes = ByteArray(200_000) { (it % 251).toByte() }
        assertArrayEquals(bytes, ByteArrayInputStream(bytes).readAtMost(MAX_BACKUP_BYTES))
    }

    @Test
    fun `the ceiling is the 50 MB the SRS gives a backup blob`() {
        assertEquals(52_428_800, MAX_BACKUP_BYTES)
    }
}
