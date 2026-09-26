package com.aicfo.domain.engines.vehicle

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Holds [VehicleKnowledge] to `ai/knowledge/vehicle-maintenance-kb.json` (§6, ADR-0017).
 *
 * Why:  the engine is pure Kotlin and cannot read the file, so it reads a mirror — and a mirror is
 *       only safe while something proves it is still a copy. The failure this prevents is the
 *       quiet one: an interval edited in the JSON because a manufacturer changed it, a mirror left
 *       alone, and an app that goes on predicting to the old schedule while the file that documents
 *       it says otherwise.
 * What: the KB's version, every class's interval and cost range, both renewal cadences, and every
 *       prediction and alert parameter, parsed out of the file and compared with the mirror.
 * Result: the two cannot silently disagree.
 * Changelog: 2026-09-26 — Created for issue 10.4.
 *
 * The JSON is read with small regular expressions rather than a parser, deliberately: adding a JSON
 * dependency to a pure-Kotlin engine's test source set to check five numbers would be the more
 * expensive mistake, and the same choice is already made in the rulebook drift tests.
 */
class VehicleKbDriftTest {
    private val kb: String by lazy { kbFile().readText() }
    private val knowledge = VehicleKnowledge.BUNDLED

    @Test
    fun `the knowledge base is where this test thinks it is`() {
        assertTrue("the vehicle KB looks empty or truncated", kb.length > 1_000)
        assertTrue("no vehicle classes in the KB", "\"vehicle_classes\"" in kb)
    }

    @Test
    fun `the mirror names the revision it copied from`() {
        assertEquals(
            "VehicleKnowledge.BUNDLED.version must restate the KB's _meta.version",
            stringAt("version"),
            knowledge.version,
        )
    }

    @Test
    fun `every class's interval and cost range match the file`() {
        KB_CLASSES.forEach { (kbClass, mirrored) ->
            val block = classBlock(kbClass)
            val spec = knowledge.serviceFor(mirrored)

            assertEquals("$kbClass interval_km", numberIn(block, "interval_km"), spec.intervalKm)
            assertEquals("$kbClass interval_months", numberIn(block, "interval_months"), spec.intervalMonths.toLong())
            assertEquals("$kbClass cost low", costRangeIn(block).first, spec.costLow.minor)
            assertEquals("$kbClass cost high", costRangeIn(block).second, spec.costHigh.minor)
        }
    }

    @Test
    fun `every class in the knowledge base is mirrored, and no more`() {
        // A class added to the file and forgotten here would silently never be selectable.
        val inFile = Regex("\"class\"\\s*:\\s*\"([^\"]+)\"").findAll(kb).map { it.groupValues[1] }.toList()

        assertEquals(KB_CLASSES.keys.toList().sorted(), inFile.sorted())
        assertEquals(inFile.size, knowledge.classes.size)
    }

    @Test
    fun `both renewal cadences match the file`() {
        assertEquals(12L, cadenceOf("insurance"))
        assertEquals(6L, cadenceOf("PUC"))
        assertEquals(cadenceOf("insurance"), knowledge.renewals.getValue(RenewalItem.INSURANCE).cadenceMonths.toLong())
        assertEquals(cadenceOf("PUC"), knowledge.renewals.getValue(RenewalItem.PUC).cadenceMonths.toLong())
    }

    @Test
    fun `the prediction parameters match the file`() {
        val block = blockNamed("prediction")
        val spec = knowledge.prediction

        assertEquals(numberIn(block, "slope_window_months"), spec.slopeWindowMonths.toLong())
        assertEquals(numberIn(block, "min_readings_for_slope"), spec.minReadingsForSlope.toLong())
        assertEquals(numberIn(block, "min_services_for_personal_index"), spec.minServicesForPersonalIndex.toLong())
        assertEquals(numberIn(block, "personal_index_floor_bps"), spec.personalIndexFloorBps.toLong())
        assertEquals(numberIn(block, "personal_index_ceiling_bps"), spec.personalIndexCeilingBps.toLong())
    }

    @Test
    fun `the alert thresholds match the file`() {
        val block = blockNamed("alerts")
        val spec = knowledge.alerts

        assertEquals(numberIn(block, "service_due_days"), spec.serviceDueDays)
        assertEquals(numberIn(block, "service_due_km"), spec.serviceDueKm)
        assertEquals(
            listOf(30L, 7L),
            Regex("\"renewal_reminder_days\"\\s*:\\s*\\[([^]]*)]")
                .find(block)!!
                .groupValues[1]
                .split(",")
                .map { it.trim().toLong() },
        )
        assertEquals(listOf(30L, 7L), spec.renewalReminderDays)
    }

    @Test
    fun `the deferred alert is still only in the file, not in the engine`() {
        // The KB describes a mileage-drop alert. Nothing records a fuel volume yet, so AI-VEH
        // cannot raise it — and an enum entry that can never be produced would be worse than the
        // honest absence ADR-0052 records. If a fuel log ever lands, this test is the reminder.
        assertTrue("the KB no longer describes the mileage-drop alert", "mileage_drop_bps" in kb)
        assertTrue(
            "a mileage-drop alert kind now exists — 10.4's deferral needs revisiting",
            VehicleAlertKind.entries.none { it.name.contains("MILEAGE") },
        )
    }

    // --- parsing ----------------------------------------------------------------------------------

    /** Result: the `_meta.version` string. Input: [key]. Output: [String]. */
    private fun stringAt(key: String): String =
        Regex("\"$key\"\\s*:\\s*\"([^\"]+)\"").find(kb)?.groupValues?.get(1)
            ?: throw AssertionError("no \"$key\" in the vehicle KB")

    /** Result: the text of a top-level block, so a key is read from the right one. */
    private fun blockNamed(name: String): String =
        Regex("\"$name\"\\s*:\\s*\\{(.+?)\\n  }", RegexOption.DOT_MATCHES_ALL).find(kb)?.groupValues?.get(1)
            ?: throw AssertionError("no \"$name\" block in the vehicle KB")

    /** Result: one vehicle class's JSON object. Input: [kbClass]. Output: [String]. */
    private fun classBlock(kbClass: String): String =
        Regex("\"class\"\\s*:\\s*\"$kbClass\".+?\"consumables\"", RegexOption.DOT_MATCHES_ALL)
            .find(kb)?.value
            ?: throw AssertionError("no class \"$kbClass\" in the vehicle KB")

    /** Result: a number under [key] inside [block]. Input: [block]; [key]. Output: [Long]. */
    private fun numberIn(
        block: String,
        key: String,
    ): Long =
        Regex("\"$key\"\\s*:\\s*(-?\\d+)").find(block)?.groupValues?.get(1)?.toLong()
            ?: throw AssertionError("no \"$key\" in the block")

    /** Result: a class's `cost_range_minor` pair. Input: [block]. Output: (low, high). */
    private fun costRangeIn(block: String): Pair<Long, Long> {
        val pair =
            Regex("\"cost_range_minor\"\\s*:\\s*\\[\\s*(\\d+)\\s*,\\s*(\\d+)\\s*]").find(block)
                ?: throw AssertionError("no cost_range_minor in the block")
        return pair.groupValues[1].toLong() to pair.groupValues[2].toLong()
    }

    /** Result: a renewal's cadence in months, from the file. Input: [item]. Output: [Long]. */
    private fun cadenceOf(item: String): Long =
        numberIn(
            Regex("\\{[^{}]*\"item\"\\s*:\\s*\"$item\"[^{}]*}").find(kb)?.value
                ?: throw AssertionError("no renewal \"$item\" in the vehicle KB"),
            "cadence_months",
        )

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
        const val KB_PATH = "ai/knowledge/vehicle-maintenance-kb.json"

        /** The file's class strings, and what each is mirrored as. */
        val KB_CLASSES =
            mapOf(
                "2W" to VehicleClass.TWO_WHEELER,
                "hatch" to VehicleClass.HATCHBACK,
                "sedan" to VehicleClass.SEDAN,
                "SUV" to VehicleClass.SUV,
                "EV" to VehicleClass.ELECTRIC,
            )
    }
}
