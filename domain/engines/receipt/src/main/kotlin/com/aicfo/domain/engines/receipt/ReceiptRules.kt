package com.aicfo.domain.engines.receipt

import com.aicfo.core.model.RuleCitation

/**
 * The thresholds this parser applies, copied from `ai/rules/rules-kb.json` (ADR-0005).
 *
 * Why:  CLAUDE.md §6 says a financial threshold is a **data row in `ai/`, never a hardcoded
 *       number** — and these are hardcoded numbers. The same deliberate, recorded deferral ADR-0005
 *       made for `QuickSetupRules` and `RecurringRules`: nothing in the app loads `ai/` yet, so
 *       honouring §6 literally would mean this issue first building an asset pipeline and a rulebook
 *       parser. `RulebookDriftTest` closes the gap that matters — edit a threshold in the rulebook
 *       and the build goes red until this file agrees.
 * What: one field per `RULE-RECEIPT-PARSE` parameter, each carrying the rule id it came from.
 * Result: every number in the parser is attributable to a rulebook row a reviewer can open.
 * Changelog: 2026-08-06 — Created for issue 3.8 from rules-kb.json v1.7.0.
 *
 * **Injected rather than read, so a test can move a threshold** and assert the parser moves with it
 * — which is also the seam the real loader will use when it lands, with no change to the engine.
 *
 * Input:  [totalKeywords] — §18.1's "keywords {total, grand, amount, payable}", lower-cased, the
 *         words a candidate amount is scored by its nearness to; [taxKeywords] — §18.1's GST
 *         heuristic, lower-cased; [lowConfidenceBps] — the floor below which FR-OCR-004 says a field
 *         is "visually flagged", in basis points (MNY-002, so no `Float` reaches the engine);
 *         [merchantTopRegionBps] — how far down the image §18.1's "top-region text" extends, in
 *         basis points of image height; [sameRowBps] — how close two recognised blocks must sit
 *         vertically to be treated as the same printed row, which is what pairs a label in one
 *         column with its amount in another (see `DefaultReceiptEngine.logicalLines`);
 *         [duplicateAmountTolerancePct] and [duplicateDateToleranceDays] — FR-OCR-006's "±1% and
 *         ±1 day", read by the repository rather than by this engine, but held here so both halves
 *         of the rule cite one row.
 * Output: an immutable value.
 */
data class ReceiptRules(
    /** RULE-RECEIPT-PARSE `total_keywords`. */
    val totalKeywords: List<String> = listOf("total", "grand", "amount", "payable"),
    /** RULE-RECEIPT-PARSE `tax_keywords`. */
    val taxKeywords: List<String> = listOf("gst", "cgst", "sgst"),
    /** RULE-RECEIPT-PARSE `low_confidence_bps`. */
    val lowConfidenceBps: Int = 6_000,
    /** RULE-RECEIPT-PARSE `merchant_top_region_bps`. */
    val merchantTopRegionBps: Int = 3_000,
    /** RULE-RECEIPT-PARSE `same_row_bps` — how close two blocks sit to be the same printed row. */
    val sameRowBps: Int = 200,
    /** RULE-RECEIPT-PARSE `duplicate_amount_tolerance_pct` — FR-OCR-006's "±1%". */
    val duplicateAmountTolerancePct: Int = 1,
    /** RULE-RECEIPT-PARSE `duplicate_date_tolerance_days` — FR-OCR-006's "±1 day". */
    val duplicateDateToleranceDays: Long = 1L,
) {
    init {
        // A parser with no keywords would score every amount identically and return whichever the
        // tie-break happened to reach — the rulebook editing itself into a coin toss.
        require(totalKeywords.isNotEmpty()) { "RULE-RECEIPT-PARSE needs at least one total keyword" }
        require(lowConfidenceBps in 0..BPS_FULL) {
            "lowConfidenceBps is basis points in 0..$BPS_FULL, was $lowConfidenceBps"
        }
        require(merchantTopRegionBps in 0..BPS_FULL) {
            "merchantTopRegionBps is basis points in 0..$BPS_FULL, was $merchantTopRegionBps"
        }
        // A row band wide enough to swallow the whole page would join every block on the receipt
        // into one line, and the total would become "the largest number anywhere" with a keyword
        // accidentally attached to it.
        require(sameRowBps in 0..BPS_FULL) {
            "sameRowBps is basis points in 0..$BPS_FULL, was $sameRowBps"
        }
        // A tolerance of 100% would make every transaction in the ledger a duplicate of every other,
        // and FR-OCR-006 would start offering to merge a coffee into a rent payment.
        require(duplicateAmountTolerancePct in 0 until PCT_TOTAL) {
            "duplicateAmountTolerancePct is a whole percent under $PCT_TOTAL, was $duplicateAmountTolerancePct"
        }
        require(duplicateDateToleranceDays >= 0L) {
            "duplicateDateToleranceDays cannot be negative, was $duplicateDateToleranceDays"
        }
    }

    companion object {
        /** The rule every extraction cites as its evidence (P-02, AI-ARC-006). */
        val FIELD_EXTRACT = RuleCitation("RULE-RECEIPT-PARSE", "1.0")
    }
}

/** 10 000 bps = 100% (MNY-002). */
internal const val BPS_FULL = 10_000

/** The whole of an amount, as the percent a tolerance is bounded by. */
internal const val PCT_TOTAL = 100
