package com.aicfo.app

import androidx.test.platform.app.InstrumentationRegistry
import com.aicfo.core.common.Ok
import com.aicfo.core.model.Money
import com.aicfo.domain.engines.receipt.ReceiptEngineFactory
import com.aicfo.domain.engines.receipt.ReceiptInput
import com.aicfo.domain.engines.receipt.ReceiptRules
import com.aicfo.ml.ocr.ReceiptTextRecognizerFactory
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The one end-to-end OCR check: a real image through the real recogniser (issue 3.8; FR-OCR-002/003).
 *
 * Why:  every other test in this issue stops at a boundary. The parser's suite feeds it text, the
 *       repository's suite fakes the recogniser, and the eval set is frozen fixtures — all
 *       deliberately, because ML Kit's accuracy needs a device and is not ours to regression-test.
 *       But that left one thing nothing checked: **what ML Kit actually hands the parser**. Running
 *       it once revealed two things no amount of reasoning had: recognised blocks are a receipt's
 *       *cells*, not its rows, so `GRAND TOTAL    365.80` arrives as two blocks side by side; and a
 *       printed `365.80` comes back as `365.8`. Both silently produced an item price as the total.
 *       This test is what caught it, so this test stays.
 * What: decodes a bundled receipt image, recognises it on-device, parses it, and asserts the total.
 * Result: the seam between `:ml:ocr` and `:domain:engines:receipt` is checked against reality.
 * Changelog: 2026-08-06 — Created for issue 3.8.
 *
 * **Instrumentation only** — it never runs in CI, which has no device, and it is not a coverage or
 * accuracy gate. It is a single smoke assertion: the pipeline reads this receipt correctly.
 *
 * **The fixture is synthetic** (`androidTest/assets/receipt.jpg`): a rendered Indian retail bill, not
 * a photograph of anyone's shopping (P-01).
 */
class ReceiptScanDeviceTest {
    @Test
    fun theRealRecogniserAndParserReadTheTotalOffAnImage() =
        runBlocking {
            val bytes =
                InstrumentationRegistry.getInstrumentation().context.assets
                    .open(FIXTURE).use { it.readBytes() }

            val recognised = ReceiptTextRecognizerFactory.create().recognize(bytes)
            assertTrue("ML Kit should read a clean printed receipt", recognised is Ok)

            val fields =
                ReceiptEngineFactory.create().extract(
                    ReceiptInput(
                        text = (recognised as Ok).value,
                        todayIsoDate = TODAY,
                        nowUtcMillis = 0L,
                    ),
                )
            val extracted = (fields as Ok).value

            assertEquals("the printed GRAND TOTAL", Money(36_580L), extracted.total?.value)
            assertEquals("the printed date, read day-first", "2026-08-04", extracted.date?.value)
            assertEquals("the shop name from the top region", "BIG BAZAAR", extracted.merchant?.value)
            assertTrue(
                "a total paired with its own keyword must not be flagged for review",
                extracted.total!!.confidenceBps >= ReceiptRules().lowConfidenceBps,
            )
        }

    private companion object {
        const val FIXTURE = "receipt.jpg"

        /**
         * After the fixture's printed date, so it is never rejected as being in the future, and
         * fixed so this test does not start failing on its own one day (TIM-001, P-08).
         */
        const val TODAY = "2026-08-06"
    }
}
