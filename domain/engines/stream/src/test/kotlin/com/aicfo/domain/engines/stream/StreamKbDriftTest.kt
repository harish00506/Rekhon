package com.aicfo.domain.engines.stream

import com.aicfo.core.model.RuleCitation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.math.BigDecimal

/**
 * Keeps [StreamRules] honest against `ai/knowledge/classification-kb.json` (issue 9.1; CLAUDE.md §6).
 *
 * Why:  the thresholds are data in `ai/`, and [StreamRules] is a typed copy of them until a runtime
 *       loader exists (ADR-0017). A copy is only acceptable while it cannot drift silently, and a
 *       stream quietly classified by last quarter's threshold would move money between "fixed" and
 *       "budgetable" on every forecast. This test is what stops that.
 * What: every weight and threshold (the file's decimals × 10 000 = the mirror's bps), the three
 *       prose parameters, the four `CLS-STR-*` rows with their versions and confidences, the fifteen
 *       `typical_stream` priors with their `CLS-CAT-*` ids, and `_meta.version`.
 * Result: an edit to either side without the other fails the build.
 * Changelog: 2026-09-19 — Created for issue 9.1.
 *
 * **Regex, not a JSON library**, as in the sibling drift tests: this module is pure Kotlin with no
 * serialisation dependency (ARC-002). Every lookup that fails to match fails loudly.
 */
class StreamKbDriftTest {
    private val kb: String by lazy { kbFile().readText() }
    private val stream: String by lazy { block("stream_classification") }
    private val rules = StreamRules()

    @Test
    fun `the knowledge base is where this test thinks it is`() {
        assertTrue("classification KB looks empty", kb.length > 1_000)
        assertTrue("stream_classification missing", "\"stream_classification\"" in kb)
    }

    @Test
    fun `the mirror claims the file version on disk`() {
        val meta = Regex(""""_meta"\s*:\s*\{.*?"version"\s*:\s*"([^"]+)"""", RegexOption.DOT_MATCHES_ALL).find(kb)
        assertNotNull(meta)
        assertEquals(StreamRules.KB_VERSION, meta!!.groupValues[1])
    }

    @Test
    fun `the weights are the file's decimals in basis points`() {
        assertEquals(bps(number(stream, "cv")), rules.cvWeightBps)
        assertEquals(bps(number(stream, "cadence")), rules.cadenceWeightBps)
        assertEquals(bps(number(stream, "dayLock")), rules.dayLockWeightBps)
    }

    @Test
    fun `the class thresholds are the file's`() {
        val fixed = objectAfter(stream, "FIXED")
        val semi = objectAfter(stream, "SEMI_FIXED")
        assertEquals(bps(number(fixed, "min_score")), rules.fixedMinScoreBps)
        assertEquals(number(fixed, "min_months").toInt(), rules.fixedMinMonths)
        assertEquals(bps(number(semi, "min_score")), rules.semiFixedMinScoreBps)
        assertEquals(
            "SEMI_FIXED's ceiling must be FIXED's floor",
            number(fixed, "min_score"),
            number(semi, "max_score"),
        )
    }

    @Test
    fun `the prose parameters are the file's`() {
        assertEquals(number(stream, "day_lock_window_days").toInt(), rules.dayLockWindowDays)
        assertEquals(number(stream, "cold_start_min_months").toInt(), rules.coldStartMinMonths)
        assertEquals(
            "the repository reads this window; keep it in step (StreamRepository.WINDOW_MONTHS)",
            6,
            number(stream, "window_months").toInt(),
        )
    }

    @Test
    fun `the four steps, their versions and their confidences are the file's, in order`() {
        val rows =
            Regex("""\{"rule_id": "(CLS-STR-\d{3})", "version": "([^"]+)", "step": (\d+), "confidence_bps": (\d+)""")
                .findAll(stream).map { it.groupValues }.toList()
        assertEquals(
            listOf(
                listOf(StreamRules.PINNED, "1", rules.pinnedConfidenceBps),
                listOf(StreamRules.KNOWN_OBLIGATION, "2", rules.obligationConfidenceBps),
                listOf(StreamRules.COLD_START, "3", rules.priorConfidenceBps),
                listOf(StreamRules.SCORED, "4", rules.scoredConfidenceBps),
            ).map { (citation, step, confidence) ->
                citation as RuleCitation
                listOf(citation.ruleId, citation.ruleVersion, step, confidence.toString())
            },
            rows.map { it.drop(1) },
        )
        assertEquals(number(stream, "no_prior_confidence_bps").toInt(), rules.noPriorConfidenceBps)
    }

    @Test
    fun `the cold-start priors are category_defaults' typical_stream, with their ids`() {
        val rows =
            Regex(
                """"rule_id": "(CLS-CAT-\d{3})", "version": "([^"]+)", "key": "([^"]+)",""" +
                    """[^}]*"typical_stream": "([A-Z_]+)"""",
            )
                .findAll(block("category_defaults", open = '[', close = ']')).map { it.groupValues }.toList()
        assertEquals(15, rows.size)
        assertEquals(rows.associate { it[3] to StreamClass.valueOf(it[4]) }, StreamRules.DEFAULT_PRIORS)
        assertEquals(rows.map { it[3] }, StreamRules.DEFAULT_PRIORS.keys.toList())
        assertEquals(rows.associate { it[3] to RuleCitation(it[1], it[2]) }, StreamRules.PRIOR_CITATIONS)
    }

    // --- parsing ------------------------------------------------------------------------------------

    private fun block(
        name: String,
        open: Char = '{',
        close: Char = '}',
    ): String {
        val start = kb.indexOf("\"$name\"").also { require(it >= 0) { "$name not found" } }
        val first = kb.indexOf(open, start)
        var depth = 0
        for (i in first until kb.length) {
            when (kb[i]) {
                open -> depth++
                close -> if (--depth == 0) return kb.substring(first, i + 1)
            }
        }
        error("$name is not closed")
    }

    private fun objectAfter(
        text: String,
        key: String,
    ): String {
        val match = Regex(""""$key"\s*:\s*\{[^}]*}""").find(text)
        assertNotNull("\"$key\" object not found", match)
        return match!!.value
    }

    private fun number(
        text: String,
        key: String,
    ): BigDecimal {
        val match = Regex(""""$key"\s*:\s*(-?\d+(?:\.\d+)?)""").find(text)
        assertNotNull("\"$key\" not found", match)
        return BigDecimal(match!!.groupValues[1])
    }

    private fun bps(fraction: BigDecimal): Int = fraction.movePointRight(4).intValueExact()

    private fun kbFile(): File =
        generateSequence(File("").absoluteFile) { it.parentFile }
            .map { File(it, "ai/knowledge/classification-kb.json") }
            .firstOrNull { it.isFile }
            ?: throw AssertionError("ai/knowledge/classification-kb.json not found above ${File("").absolutePath}")
}
