package com.aicfo.domain.engines.marketsignal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Holds [MarketKnowledge] to `ai/knowledge/market-signals.json` (§6, §30, ADR-0017).
 *
 * Why:  §30's whole argument is that the weights are data, editable on evidence — which is only
 *       true while the file people edit is the file the engine reads. The failure this prevents is
 *       the quiet one: a weight lowered in the JSON because a backtest said so, a mirror left
 *       alone, and an app that goes on scoring by the old library while the file that documents it
 *       says otherwise.
 * What: the version, every signal's max points and ladder, the verdict bands, the history
 *       minimums, the hit-rate policy, the staleness limits and the tranche gates.
 * Result: the two cannot silently disagree.
 * Changelog: 2026-09-27 — Created for issue 10.7.
 */
class MarketKbDriftTest {
    private val kb: String by lazy { kbFile().readText() }
    private val knowledge = MarketKnowledge.BUNDLED

    @Test
    fun `the knowledge base is where this test thinks it is`() {
        assertTrue("the signal library looks empty or truncated", kb.length > 2_000)
        assertTrue("no scored signals in the library", "\"scored_signals\"" in kb)
    }

    @Test
    fun `the mirror names the revision it copied from`() {
        assertEquals(stringAt("version"), knowledge.version)
    }

    @Test
    fun `every scored signal is mirrored, in order, with its weight`() {
        val inFile =
            Regex("\"id\"\\s*:\\s*\"(SIG-[A-Z0-9]+)\"").findAll(scoredBlock()).map { it.groupValues[1] }.toList()

        assertEquals(knowledge.signals.map { it.id }, inFile)
        knowledge.signals.forEach { spec ->
            assertEquals("${spec.id} max_points", numberIn(signalBlock(spec.id), "max_points"), spec.maxPoints)
        }
    }

    @Test
    fun `every signal's ladder matches the file, step for step`() {
        knowledge.signals.forEach { spec ->
            val block = signalBlock(spec.id)
            val kind = Regex("\"kind\"\\s*:\\s*\"([a-z_]+)\"").find(block)?.groupValues?.get(1)
            // The ladder is pretty-printed over several lines, so the pairs are read from the text
            // between "ladder" and the note that follows it rather than from one bracket match —
            // a non-greedy bracket regex stops at the first inner `]` and finds nothing.
            val ladderText = block.substringAfter("\"ladder\"").substringBefore("\"note\"")
            val steps =
                Regex("\\[\\s*(-?\\d+)\\s*,\\s*(-?\\d+)\\s*]")
                    .findAll(ladderText)
                    .map { it.groupValues[1].toInt() to it.groupValues[2].toInt() }
                    .toList()

            assertEquals("${spec.id} kind", spec.kind.name.lowercase(), kind)
            assertEquals("${spec.id} ladder", spec.ladder, steps)
        }
    }

    @Test
    fun `the weights still add up to the cap the file states`() {
        // Not a mirror check but a library one: if the seven weights stopped summing to 100, a
        // "100 out of 100" verdict would be unreachable and the bands would quietly mean less.
        assertEquals(knowledge.bands.cap, knowledge.signals.sumOf { it.maxPoints })
    }

    @Test
    fun `the verdict bands match the file`() {
        val bands = namedBlock("verdict_bands")

        assertTrue("STRONG_BUY_DAY moved", ">= ${knowledge.bands.strongBuyFrom}" in bands)
        assertTrue("GOOD_DAY moved", "${knowledge.bands.goodFrom}-" in bands)
        assertTrue("NEUTRAL moved", "${knowledge.bands.neutralFrom}-" in bands)
    }

    @Test
    fun `the history minimums match the file`() {
        val block = namedBlock("history")

        assertEquals(numberIn(block, "minimum_days_for_any_score"), knowledge.history.minimumDaysForAnyScore)
        assertEquals(numberIn(block, "minimum_days_for_ma200"), knowledge.history.minimumDaysForMa200)
        assertEquals(numberIn(block, "minimum_days_for_52w_high"), knowledge.history.minimumDaysFor52wHigh)
    }

    @Test
    fun `the hit-rate policy matches the file`() {
        val block = namedBlock("hit_rate")

        assertEquals(numberIn(block, "horizon_days"), knowledge.hitRate.horizonDays)
        assertEquals(numberIn(block, "min_samples"), knowledge.hitRate.minSamples)
    }

    @Test
    fun `the staleness limits match the file`() {
        val block = namedBlock("staleness")

        assertEquals(numberIn(block, "stale_after_days"), knowledge.staleness.staleAfterDays)
        assertEquals(numberIn(block, "refuse_after_days"), knowledge.staleness.refuseAfterDays)
    }

    @Test
    fun `the capacity gates are the ones the file names`() {
        val policy = namedBlock("tranche_policy")

        knowledge.tranches.gates.forEach { gate ->
            assertTrue("$gate is no longer a gate in the file", gate in policy)
        }
    }

    @Test
    fun `the context-only signals are still never scored`() {
        // §30.2 keeps three signals as colour, explicitly out of the score. A mirror that started
        // scoring one would be scoring evidence the library says is contested.
        val contextOnly =
            Regex("\"id\"\\s*:\\s*\"(SIG-[A-Z0-9-]+)\"")
                .findAll(namedListBlock("context_signals_never_scored"))
                .map { it.groupValues[1] }
                .toList()

        assertTrue("the file no longer marks any signal as context-only", contextOnly.isNotEmpty())
        contextOnly.forEach { id ->
            assertTrue("$id is scored by the mirror", knowledge.signals.none { it.id == id })
        }
    }

    // --- parsing ----------------------------------------------------------------------------------

    /** Result: a top-level string value. Input: [key]. Output: [String]. */
    private fun stringAt(key: String): String =
        Regex("\"$key\"\\s*:\\s*\"([^\"]+)\"").find(kb)?.groupValues?.get(1)
            ?: throw AssertionError("no \"$key\" in the library")

    /** Result: the `scored_signals` array's text. Input: none. Output: [String]. */
    private fun scoredBlock(): String =
        Regex("\"scored_signals\"\\s*:\\s*\\[(.+?)\\n  ]", RegexOption.DOT_MATCHES_ALL)
            .find(kb)?.groupValues?.get(1)
            ?: throw AssertionError("no scored_signals block")

    /** Result: one signal's object. Input: [id]. Output: [String]. */
    private fun signalBlock(id: String): String =
        Regex("\\{\\s*\"id\"\\s*:\\s*\"$id\".+?\\n    }", RegexOption.DOT_MATCHES_ALL)
            .find(scoredBlock())?.value
            ?: throw AssertionError("no signal \"$id\" in the library")

    /** Result: a named object's text. Input: [name]. Output: [String]. */
    private fun namedBlock(name: String): String =
        Regex("\"$name\"\\s*:\\s*\\{(.+?)\\n  }", RegexOption.DOT_MATCHES_ALL).find(kb)?.groupValues?.get(1)
            ?: Regex("\"$name\"\\s*:\\s*\\{(.+?)}", RegexOption.DOT_MATCHES_ALL).find(kb)?.groupValues?.get(1)
            ?: throw AssertionError("no \"$name\" block in the library")

    /** Result: a named array's text. Input: [name]. Output: [String]. */
    private fun namedListBlock(name: String): String =
        Regex("\"$name\"\\s*:\\s*\\[(.+?)\\n  ]", RegexOption.DOT_MATCHES_ALL).find(kb)?.groupValues?.get(1)
            ?: throw AssertionError("no \"$name\" list in the library")

    /** Result: a number under [key] inside [block]. Input: [block]; [key]. Output: [Int]. */
    private fun numberIn(
        block: String,
        key: String,
    ): Int =
        Regex("\"$key\"\\s*:\\s*(-?\\d+)").find(block)?.groupValues?.get(1)?.toInt()
            ?: throw AssertionError("no \"$key\" in the block")

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
        const val KB_PATH = "ai/knowledge/market-signals.json"
    }
}
