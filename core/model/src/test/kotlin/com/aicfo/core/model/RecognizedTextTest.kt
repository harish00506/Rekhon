package com.aicfo.core.model

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Proves the OCR boundary type behaves (issue 3.8; FR-OCR-002).
 *
 * Why:  `:core:model` is held to 100% line coverage (CLAUDE.md §4), and more to the point
 *       [RecognizedText.lines] is what every line-scanning heuristic in the receipt parser reads —
 *       a block ML Kit returns as one multi-line string must arrive at the parser as its lines, or
 *       the GST and date rules would each be searching a paragraph.
 * Result: the flattening is pinned. Changelog: 2026-08-06 — Created for issue 3.8.
 */
class RecognizedTextTest {
    @Test
    fun `a block splits into its own lines`() {
        val text = RecognizedText(listOf(RecognizedBlock("BIG BAZAAR\nGST 18%\nTOTAL 450.00")))

        assertEquals(listOf("BIG BAZAAR", "GST 18%", "TOTAL 450.00"), text.lines)
    }

    @Test
    fun `blocks flatten in order`() {
        val text = RecognizedText(listOf(RecognizedBlock("first"), RecognizedBlock("second\nthird")))

        assertEquals(listOf("first", "second", "third"), text.lines)
    }

    @Test
    fun `an unreadable photo is empty rather than an error`() {
        assertEquals(emptyList<String>(), RecognizedText(emptyList()).lines)
    }

    @Test
    fun `a block with no geometry reads as topmost`() {
        assertEquals(0, RecognizedBlock("no bounding box").topFraction)
    }
}
