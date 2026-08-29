package com.aicfo.core.model

/**
 * What an OCR engine read off a receipt (issue 3.8; FR-OCR-002, §18.1).
 *
 * Why:  the boundary between `:ml:ocr` — which is Android, needs a device and cannot be unit-tested
 *       on the JVM — and `:domain:engines:receipt`, which decides what the text *means* and must be
 *       provable without one. Putting the type here rather than in `:ml:ocr` is what lets a pure
 *       Kotlin engine consume it at all: ARC-002 forbids a `:domain:*` module from importing
 *       Android, and `:ml:ocr` is an Android library.
 * What: the recognised blocks, in the order the recogniser returned them.
 * Result: the input to the receipt parser, and the one thing a frozen eval fixture has to contain.
 * Changelog: 2026-08-06 — Created for issue 3.8.
 *
 * **Deliberately not ML Kit's `Text`.** Depending on `com.google.mlkit.vision.text.Text` would pull
 * a Google Play library into the engine, the eval fixtures and every test — and would tie the
 * parser's contract to a third party's class. Two fields are all §18.1's heuristics read.
 *
 * Input:  [blocks] — every recognised block; empty when the image held no legible text, which is a
 *         real answer rather than an error (§18: failures fall back to manual entry).
 * Output: an immutable value.
 */
data class RecognizedText(
    val blocks: List<RecognizedBlock>,
) {
    /** Every block's text, one line each, for the heuristics that scan line by line. */
    val lines: List<String> get() = blocks.flatMap { it.text.lines() }
}

/**
 * One block of text and where it sat on the page (issue 3.8; §18.1).
 *
 * Why:  §18.1's merchant heuristic is "merchant = top-region text", so position is not decoration —
 *       it is the whole of that rule. [topFraction] carries it as **integer basis points of the
 *       image height** rather than as a `Float` ratio or a pixel box: bps is the unit MNY-002
 *       already established for every other ratio in this codebase, it keeps floating point out of
 *       an engine input entirely, and it is resolution-independent, so a 4 000-pixel-tall photo and
 *       a 900-pixel one score the same block identically.
 * Result: the element type of [RecognizedText.blocks].
 * Changelog: 2026-08-06 — Created for issue 3.8.
 *
 * Input:  [text] — the block as read, which may itself contain newlines; [topFraction] — the top
 *         edge of the block's bounding box as basis points of the image height, `0` at the very top
 *         and `10000` at the very bottom. `0` when the recogniser gave no geometry, which reads as
 *         "topmost" and is the safe default: the worst case is a merchant guess the user corrects.
 * Output: an immutable value.
 */
data class RecognizedBlock(
    val text: String,
    val topFraction: Int = 0,
)
