package com.aicfo.domain.engines.budget

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Keeps [SeasonalityPriors] honest against `ai/knowledge/calendar-seasonality.json` (§6, ADR-0017).
 *
 * Why:  the same deferral [RulebookDriftTest] guards, for the other file this engine mirrors. The
 *       seasonality KB is the more dangerous of the two to let drift, because its errors are
 *       invisible: a multiplier copied wrong, or a window shifted by one month, still produces a
 *       plausible budget in every month except the one it was supposed to help with.
 * What: parses the nine events out of the KB and asserts the id, window, inflated categories and
 *       multiplier of each — as an **ordered list**, so a reordered or added event is noticed.
 * Result: the mirror cannot silently disagree with the knowledge base it claims to be a copy of.
 * Changelog: 2026-08-11 — Created for issue 4.4.
 *
 * **The window is re-derived here, not copied.** The mirror stores months as integers; the KB
 * stores them as `"Oct-Nov"`. This test parses the KB's own strings back into months, so the
 * translation is checked rather than assumed — which is the half of the mirror a reader is most
 * likely to get wrong, and the half four wrapping windows depend on.
 */
class SeasonalityKbDriftTest {
    private val kb: String by lazy { kbFile().readText() }

    /**
     * Input:  the repo's seasonality knowledge base.
     * Output: asserts it was found and is non-trivial, so nothing below passes vacuously.
     */
    @Test
    fun `the knowledge base is where this test thinks it is`() {
        assertTrue("seasonality KB looks empty or truncated", kb.length > 1_000)
        assertTrue("no events in the seasonality KB", "\"events\"" in kb)
    }

    /** Input: the file's `_meta.version`. Output: asserts the mirror names the revision it copied. */
    @Test
    fun `the mirror names the knowledge-base revision it copied from`() {
        val meta = kb.substringBefore("\"events\"")
        val match = Regex("\"version\"\\s*:\\s*\"([^\"]+)\"").find(meta)
        assertNotNull("no _meta.version in the seasonality KB — AI-ARC-006 requires one", match)
        assertEquals(SeasonalityPriors.KB_VERSION, match!!.groupValues[1])
    }

    /**
     * Input:  every event row in the KB.
     * Output: asserts the mirror holds the same events, in the same order, with the same ids.
     *         Ordered, because a reordered knowledge base is a change worth noticing, and because
     *         `strongestFor` resolves ties by taking the first maximum.
     */
    @Test
    fun `the mirror holds the same events in the same order`() {
        assertEquals(kbEvents().map { it.id }, SeasonalityPriors.events.map { it.id })
    }

    /**
     * Input:  each event's `prior_multiplier`.
     * Output: asserts the decimal in the KB equals the basis points in the mirror. This is the
     *         MNY-002 translation — `1.38` becomes `13_800` — and getting it wrong by a factor of
     *         ten would inflate a Diwali budget by 380%.
     */
    @Test
    fun `every multiplier matches, converted to basis points`() {
        val wrong =
            kbEvents().zip(SeasonalityPriors.events).mapNotNull { (fromKb, mirrored) ->
                if (fromKb.multiplierBps != mirrored.priorMultiplierBps) {
                    "${fromKb.id}: KB says ${fromKb.multiplierBps} bps, mirror says ${mirrored.priorMultiplierBps}"
                } else {
                    null
                }
            }
        assertEquals("$wrong", 0, wrong.size)
    }

    /**
     * Input:  each event's `window` string, parsed back into month numbers.
     * Output: asserts the mirror's start/end months are the KB's, including the four that wrap the
     *         year end. A window silently off by a month is the failure this whole test exists for.
     */
    @Test
    fun `every window matches, parsed back from the knowledge base`() {
        val wrong =
            kbEvents().zip(SeasonalityPriors.events).mapNotNull { (fromKb, mirrored) ->
                if (fromKb.startMonth != mirrored.startMonth || fromKb.endMonth != mirrored.endMonth) {
                    "${fromKb.id}: KB window ${fromKb.startMonth}..${fromKb.endMonth}, " +
                        "mirror ${mirrored.startMonth}..${mirrored.endMonth}"
                } else {
                    null
                }
            }
        assertEquals("$wrong", 0, wrong.size)
    }

    /** Input: each event's `inflates`. Output: asserts the mirror inflates the same categories. */
    @Test
    fun `every event inflates the same categories`() {
        val wrong =
            kbEvents().zip(SeasonalityPriors.events).mapNotNull { (fromKb, mirrored) ->
                if (fromKb.inflates != mirrored.inflates) {
                    "${fromKb.id}: KB inflates ${fromKb.inflates}, mirror ${mirrored.inflates}"
                } else {
                    null
                }
            }
        assertEquals("$wrong", 0, wrong.size)
    }

    /**
     * Input:  the KB's `_meta.shrinkage_rule`.
     * Output: asserts the denominator the engine's rules use is the one the KB states. The rule is
     *         prose in the KB, so this pins the one number in it that the engine acts on — without
     *         it, `shrinkage_denominator_months` could drift from the file that justifies it.
     */
    @Test
    fun `the shrinkage denominator matches the rule the knowledge base states`() {
        val match = Regex("months_observed\\s*/\\s*(\\d+)").find(kb)
        assertNotNull("no 'k = months_observed/N' shrinkage rule found in the KB", match)
        assertEquals(BudgetRules().shrinkageDenominatorMonths, match!!.groupValues[1].toInt())
    }

    // --- parsing ------------------------------------------------------------------------------

    private data class KbEvent(
        val id: String,
        val startMonth: Int,
        val endMonth: Int,
        val inflates: Set<String>,
        val multiplierBps: Int,
    )

    /**
     * Parses the KB's event rows.
     * Why:    regex rather than a JSON library — `:domain:*` has no serialisation dependency by
     *         design (ARC-002). Strict: an event whose fields cannot all be read fails the test
     *         rather than being skipped, which would shrink the comparison silently.
     * Result: the events, in file order. Input: none. Output: a list.
     */
    private fun kbEvents(): List<KbEvent> {
        val events = kb.substringAfter("\"events\"")
        val parsed =
            ROW.findAll(events).map { match ->
                val fields = match.groupValues
                val (start, end) = fields[WINDOW].split("-").map { monthNumber(it.trim()) }
                KbEvent(
                    id = fields[ID],
                    startMonth = start,
                    endMonth = end,
                    inflates = INFLATE.findAll(fields[INFLATES]).map { it.groupValues[1] }.toSet(),
                    multiplierBps = multiplierToBps(fields[MULTIPLIER]),
                )
            }.toList()
        assertEquals("the KB should hold nine events; the parse found ${parsed.size}", EXPECTED_EVENTS, parsed.size)
        return parsed
    }

    /**
     * Converts the KB's decimal multiplier into basis points without a `Double`.
     * Why:    parsing `1.38` as a `Double` and multiplying by 10 000 yields 13799.999999999998 —
     *         the exact class of drift MNY-001 exists to keep out of money code, in the test that is
     *         supposed to be checking for drift.
     * Result: integer basis points. Input: [decimal] — e.g. `"1.38"`. Output: [Int].
     */
    private fun multiplierToBps(decimal: String): Int {
        val (whole, fraction) = if ("." in decimal) decimal.split(".") else listOf(decimal, "")
        val padded = fraction.padEnd(BPS_DECIMALS, '0').take(BPS_DECIMALS)
        return whole.toInt() * BPS_FULL + padded.toInt()
    }

    /** Result: 1..12 for a three-letter month name. Input: [name]. Output: [Int]. */
    private fun monthNumber(name: String): Int {
        val index = MONTHS.indexOfFirst { it.equals(name, ignoreCase = true) }
        assertTrue("'$name' is not a month name the KB's windows should contain", index >= 0)
        return index + 1
    }

    /**
     * Finds the KB by walking up from the test's working directory — same reasoning as
     * [RulebookDriftTest.rulebookFile].
     * Result: the file. Input: none. Output: [File].
     */
    private fun kbFile(): File {
        var directory: File? = File("").absoluteFile
        while (directory != null) {
            val candidate = File(directory, KB_PATH)
            if (candidate.isFile) return candidate
            directory = directory.parentFile
        }
        error("Could not find $KB_PATH walking up from ${File("").absolutePath}")
    }

    private companion object {
        const val KB_PATH = "ai/knowledge/calendar-seasonality.json"
        const val EXPECTED_EVENTS = 9

        /** 10 000 bps = 100%, so a decimal multiplier carries four places. */
        const val BPS_DECIMALS = 4

        /** [ROW]'s capture groups, named so the parse reads as the KB's own field order. */
        const val ID = 1
        const val WINDOW = 2
        const val INFLATES = 3
        const val MULTIPLIER = 4

        val MONTHS = listOf("Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec")

        val ROW =
            Regex(
                """"id"\s*:\s*"([^"]+)"\s*,\s*"window"\s*:\s*"([^"]+)"\s*,\s*""" +
                    """"inflates"\s*:\s*\[([^]]*)]\s*,\s*"prior_multiplier"\s*:\s*([\d.]+)""",
            )
        val INFLATE = Regex(""""([^"]+)"""")
    }
}
