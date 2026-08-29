package com.aicfo.domain.engines.receipt

import com.aicfo.core.common.Err
import com.aicfo.core.common.Ok
import com.aicfo.core.model.Money
import com.aicfo.core.model.RecognizedBlock
import com.aicfo.core.model.RecognizedText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Proves SRS §18.1's heuristics behave (issue 3.8; FR-OCR-003, FR-OCR-004, MNY-001, P-08).
 *
 * Why:  the parser reads text nobody controls and hands the result to a screen the user will trust,
 *       so the cases that matter are the ones where it must **refuse** rather than the ones where it
 *       succeeds: a percentage is not an amount, a bill number is not a date, and a receipt from
 *       next month is a misread. Each of those is a case below, because each is a way the app could
 *       silently record a purchase that never happened.
 * Result: every branch of the parser is pinned to a receipt a reader can picture.
 * Changelog: 2026-08-06 — Created for issue 3.8.
 *
 * **`todayIsoDate` is fixed at 2026-08-06 in every case** (TIM-001, P-08): the parser reads it to
 * resolve two-digit years and to reject future dates, so a test that let it drift would start
 * failing on its own one day.
 */
class ReceiptEngineTest {
    private val engine = ReceiptEngineFactory.create()

    // --- the whole pipeline -----------------------------------------------------------------

    @Test
    fun `a printed receipt yields all four fields`() {
        val fields =
            extract(
                block("BIG BAZAAR\nKORAMANGALA, BENGALURU", top = 200),
                block(
                    """
                    Bill No 20260406
                    Date: 04/08/2026
                    Milk 1L          62.00
                    Atta 5kg        248.00
                    CGST 9%          27.90
                    SGST 9%          27.90
                    GRAND TOTAL     365.80
                    """.trimIndent(),
                    top = 4_000,
                ),
            )

        assertEquals(Money(36_580), fields.total?.value)
        assertEquals("2026-08-04", fields.date?.value)
        assertEquals("BIG BAZAAR", fields.merchant?.value)
        assertEquals(Money(5_580), fields.tax?.value)
    }

    @Test
    fun `an unreadable photo yields nothing rather than a guess`() {
        val fields = extract()

        assertNull(fields.total)
        assertNull(fields.date)
        assertNull(fields.merchant)
        assertNull(fields.tax)
        assertEquals(0, fields.provenance.confidenceBps)
    }

    @Test
    fun `the same text twice gives the same answer`() {
        val blocks = arrayOf(block("CAFE COFFEE DAY"), block("TOTAL 240.00\nDate 01/08/2026", top = 5_000))

        assertEquals(extract(*blocks), extract(*blocks))
    }

    @Test
    fun `every extraction cites the rulebook rule`() {
        val fields = extract(block("TOTAL 100.00"))

        assertEquals(listOf(ReceiptRules.FIELD_EXTRACT), fields.provenance.evidence)
        assertEquals("receipt-parser", fields.provenance.engineId)
    }

    @Test
    fun `a malformed today is the caller's error, not the receipt's`() {
        val outcome =
            engine.extract(
                ReceiptInput(RecognizedText(emptyList()), todayIsoDate = "not-a-date", nowUtcMillis = NOW),
            )

        assertTrue(outcome is Err)
    }

    // --- the total (§18.1) ------------------------------------------------------------------

    @Test
    fun `a keyword line beats a larger amount elsewhere`() {
        val fields = extract(block("Deposit paid 9,999.00\nTOTAL PAYABLE 450.00"))

        assertEquals(Money(45_000), fields.total?.value)
    }

    @Test
    fun `the largest amount on the keyword lines wins`() {
        val fields = extract(block("SUB TOTAL 400.00\nTOTAL 450.00"))

        assertEquals(Money(45_000), fields.total?.value)
    }

    @Test
    fun `a whole-rupee total with no paise is still read`() {
        val fields = extract(block("Items 3\nTOTAL 1240"))

        assertEquals(Money(124_000), fields.total?.value)
    }

    @Test
    fun `a currency marker makes a number an amount`() {
        val fields = extract(block("Amount payable Rs 1,240"))

        assertEquals(Money(124_000), fields.total?.value)
    }

    @Test
    fun `with no keyword the largest amount is offered but flagged`() {
        val fields = extract(block("Milk 62.00\nAtta 248.00"))

        assertEquals(Money(24_800), fields.total?.value)
        assertTrue(
            "a keyword-less guess must fall below the flag floor (FR-OCR-004)",
            fields.total!!.confidenceBps < ReceiptRules().lowConfidenceBps,
        )
    }

    @Test
    fun `one clean keyword line is more certain than a crowded one`() {
        val clean = extract(block("TOTAL 450.00")).total!!.confidenceBps
        val crowded = extract(block("SUB TOTAL 400.00\nTOTAL 450.00")).total!!.confidenceBps

        assertTrue("a lone figure beside a keyword should read as surer", clean > crowded)
    }

    @Test
    fun `a percentage is not an amount`() {
        val fields = extract(block("CGST 18%\nTOTAL 90.00"))

        assertEquals(Money(9_000), fields.total?.value)
    }

    @Test
    fun `a bill number is not an amount`() {
        val fields = extract(block("Bill No 20260406\nMilk 62.00"))

        assertEquals(Money(6_200), fields.total?.value)
    }

    @Test
    fun `a word ending in rs does not create an amount`() {
        val fields = extract(block("Counters 5"))

        assertNull(fields.total)
    }

    @Test
    fun `a zero total is not offered`() {
        val fields = extract(block("TOTAL 0.00"))

        assertNull(fields.total)
    }

    @Test
    fun `a printed row split across two blocks is read as one line`() {
        // What the emulator run found: ML Kit returns a receipt's *cells*, not its rows, so
        // `GRAND TOTAL     365.80` came back as two blocks at the same height. Every line-based
        // heuristic then saw a keyword with no amount and fell through to an item price.
        val fields =
            extract(
                block("Atta 5kg", top = 3_875),
                block("248.00", top = 3_916),
                block("GRAND TOTAL", top = 5_916),
                block("365.80", top = 5_916),
            )

        assertEquals(Money(36_580), fields.total?.value)
        assertTrue(
            "pairing the label with its own amount is a confident read, not a fallback",
            fields.total!!.confidenceBps >= ReceiptRules().lowConfidenceBps,
        )
    }

    @Test
    fun `a clipped trailing zero is still an amount`() {
        // ML Kit routinely returns `365.8` for a printed `365.80`. One decimal digit is *tens* of
        // paise, and MoneyFormatter pads it exactly — refusing it would lose the total outright.
        val fields = extract(block("TOTAL 365.8"))

        assertEquals(Money(36_580), fields.total?.value)
    }

    @Test
    fun `a dotted date is not an amount, even now that one decimal digit is allowed`() {
        // The false positive the relaxation newly exposes: `04.08.2026` must not read as ₹4.08.
        val fields = extract(block("Dt 04.08.2026\nMilk 62.00"))

        assertEquals(Money(6_200), fields.total?.value)
    }

    @Test
    fun `a multi-line block keeps its own lines rather than being joined by height`() {
        // Every line of one block shares the block's topFraction, so joining by height would put the
        // label of one row beside the amount of another.
        val fields = extract(block("Deposit paid 9,999.00\nTOTAL PAYABLE 450.00", top = 4_000))

        assertEquals(Money(45_000), fields.total?.value)
    }

    @Test
    fun `a split levy across two columns is still added`() {
        val fields =
            extract(
                block("CGST 9%", top = 4_700),
                block("27.90", top = 4_716),
                block("SGST 9%", top = 5_100),
                block("27.90", top = 5_116),
            )

        assertEquals(Money(5_580), fields.tax?.value)
    }

    @Test
    fun `moving the row band apart again separates the columns`() {
        val outcome =
            engine.extract(
                ReceiptInput(
                    RecognizedText(
                        listOf(
                            RecognizedBlock("GRAND TOTAL", topFraction = 5_916),
                            RecognizedBlock("365.80", topFraction = 6_100),
                        ),
                    ),
                    todayIsoDate = TODAY,
                    nowUtcMillis = NOW,
                    rules = ReceiptRules(sameRowBps = 0),
                ),
            )

        // Still found — as the low-confidence fallback, which is the honest degradation.
        val total = (outcome as Ok).value.total!!
        assertEquals(Money(36_580), total.value)
        assertTrue("with no row band the pairing is lost", total.confidenceBps < ReceiptRules().lowConfidenceBps)
    }

    // --- the date (§18.1) -------------------------------------------------------------------

    @Test
    fun `a slashed date reads day-first, the Indian way`() {
        assertEquals("2026-04-03", extract(block("Date 03/04/2026")).date?.value)
    }

    @Test
    fun `dashes and dots are dates too`() {
        assertEquals("2026-04-03", extract(block("03-04-2026")).date?.value)
        assertEquals("2026-04-03", extract(block("03.04.2026")).date?.value)
    }

    @Test
    fun `a two-digit year takes its century from today`() {
        assertEquals("2026-04-03", extract(block("03/04/26")).date?.value)
    }

    @Test
    fun `a two-digit year that would land in the future goes back a century`() {
        assertEquals("1999-12-31", extract(block("31/12/99")).date?.value)
    }

    @Test
    fun `a receipt cannot be from the future`() {
        assertNull(extract(block("Date 09/09/2026")).date)
    }

    @Test
    fun `an impossible date is skipped, not thrown`() {
        assertEquals("2026-01-05", extract(block("31/02/2026\n05/01/2026")).date?.value)
    }

    @Test
    fun `an unambiguous day reads as surer than an ambiguous one`() {
        val unambiguous = extract(block("21/04/2026")).date!!.confidenceBps
        val ambiguous = extract(block("03/04/2026")).date!!.confidenceBps

        assertTrue("a leading 21 can only be a day", unambiguous > ambiguous)
    }

    @Test
    fun `a four-digit year reads as surer than a two-digit one`() {
        val full = extract(block("03/04/2026")).date!!.confidenceBps
        val short = extract(block("03/04/26")).date!!.confidenceBps

        assertTrue("no century had to be guessed", full > short)
    }

    @Test
    fun `a long serial number is not a date`() {
        assertNull(extract(block("1234/5678/90")).date)
    }

    // --- the merchant (§18.1) ---------------------------------------------------------------

    @Test
    fun `the merchant is the topmost block`() {
        val fields = extract(block("MORE SUPERMARKET", top = 100), block("TOTAL 90.00", top = 8_000))

        assertEquals("MORE SUPERMARKET", fields.merchant?.value)
    }

    @Test
    fun `a till time printed above the name is skipped`() {
        val fields = extract(block("04/08/2026\nRELIANCE FRESH", top = 100))

        assertEquals("RELIANCE FRESH", fields.merchant?.value)
    }

    @Test
    fun `nothing in the top region means no merchant`() {
        val fields = extract(block("TOTAL 90.00", top = 9_000))

        assertNull(fields.merchant)
    }

    @Test
    fun `a name printed lower reads as less certain`() {
        val top = extract(block("SHOP", top = 0)).merchant!!.confidenceBps
        val lower = extract(block("SHOP", top = 2_900)).merchant!!.confidenceBps

        assertTrue("a name nearer the address block is a weaker claim", top > lower)
    }

    // --- the tax (§18.1) --------------------------------------------------------------------

    @Test
    fun `a split levy is added, because CGST and SGST are halves`() {
        val fields = extract(block("CGST 9% 27.90\nSGST 9% 27.90\nTOTAL 365.80"))

        assertEquals(Money(5_580), fields.tax?.value)
    }

    @Test
    fun `a single GST line is taken as printed`() {
        val fields = extract(block("GST 18% 55.80\nTOTAL 365.80"))

        assertEquals(Money(5_580), fields.tax?.value)
    }

    @Test
    fun `a receipt printing both a split and a summary does not double-count`() {
        val fields = extract(block("CGST 27.90\nSGST 27.90\nTOTAL GST 55.80\nTOTAL 365.80"))

        assertEquals(Money(5_580), fields.tax?.value)
    }

    @Test
    fun `no tax line means no tax field`() {
        assertNull(extract(block("TOTAL 365.80")).tax)
    }

    // --- the rulebook seam ------------------------------------------------------------------

    @Test
    fun `moving the top-region band moves the merchant`() {
        val narrow = ReceiptRules(merchantTopRegionBps = 100)
        val fields =
            engine.extract(
                ReceiptInput(
                    RecognizedText(listOf(RecognizedBlock("SHOP", topFraction = 2_000))),
                    todayIsoDate = TODAY,
                    nowUtcMillis = NOW,
                    rules = narrow,
                ),
            )

        assertNull((fields as Ok).value.merchant)
    }

    @Test
    fun `moving the keyword set moves the total`() {
        val fields =
            engine.extract(
                ReceiptInput(
                    RecognizedText(listOf(RecognizedBlock("NET DUE 450.00\nDeposit 9,999.00"))),
                    todayIsoDate = TODAY,
                    nowUtcMillis = NOW,
                    rules = ReceiptRules(totalKeywords = listOf("net due")),
                ),
            )

        assertEquals(Money(45_000), (fields as Ok).value.total?.value)
    }

    // --- helpers ----------------------------------------------------------------------------

    /**
     * Runs the parser over some blocks at a fixed date.
     * Result: the extracted fields, failing the test if the parse errored.
     * Input:  [blocks] — the recognised blocks. Output: [ReceiptFields].
     */
    private fun extract(vararg blocks: RecognizedBlock): ReceiptFields {
        val outcome =
            engine.extract(ReceiptInput(RecognizedText(blocks.toList()), todayIsoDate = TODAY, nowUtcMillis = NOW))
        assertTrue("the parser should not fail on a well-formed input", outcome is Ok)
        return (outcome as Ok).value
    }

    /** Result: one block. Input: [text]; [top] — its position, in basis points. Output: block. */
    private fun block(
        text: String,
        top: Int = 0,
    ): RecognizedBlock = RecognizedBlock(text, topFraction = top)

    private companion object {
        /** Fixed so two-digit years and the future-date refusal never drift (P-08). */
        const val TODAY = "2026-08-06"
        const val NOW = 1_786_000_000_000L
    }
}
