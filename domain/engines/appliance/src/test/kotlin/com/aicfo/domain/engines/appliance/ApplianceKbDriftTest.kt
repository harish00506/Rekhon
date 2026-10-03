package com.aicfo.domain.engines.appliance

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Holds [ApplianceKnowledge] to `ai/knowledge/appliance-maintenance-kb.json` (§6, ADR-0070).
 *
 * Why:  the engine is pure Kotlin and cannot read the file, so it reads a mirror — and a mirror is
 *       only safe while something proves it is still a copy. The failure this prevents is the quiet
 *       one: a tariff edited in the JSON because electricity got dearer, the mirror left alone, and
 *       an app that goes on quoting last year's running cost while the file that documents it says
 *       otherwise. The same arrangement AI-VEH uses, for the same reason.
 * What: the KB's version, every class's cadence, seasonal anchor, cost range, warranty and power
 *       figures, every consumable, and all the prediction and alert parameters — parsed out of the
 *       file and compared with the mirror.
 * Result: the two cannot silently disagree.
 * Changelog: 2026-10-03 — Created for issue 13.2.
 *
 * The JSON is read with small regular expressions rather than a parser, deliberately: adding a JSON
 * dependency to a pure-Kotlin engine's test source set to check a few dozen numbers would be the
 * more expensive mistake, and the same choice is already made in AI-VEH's and the rulebook's drift
 * tests.
 */
class ApplianceKbDriftTest {
    private val kb: String by lazy { kbFile().readText() }
    private val knowledge = ApplianceKnowledge.BUNDLED

    @Test
    fun `the knowledge base is where this test thinks it is`() {
        assertTrue("the appliance KB looks empty or truncated", kb.length > 1_000)
        assertTrue("no appliance classes in the KB", "\"appliance_classes\"" in kb)
    }

    @Test
    fun `the mirror names the revision it copied from`() {
        assertEquals(
            "ApplianceKnowledge.BUNDLED.version must restate the KB's _meta.version",
            stringAt("version"),
            knowledge.version,
        )
    }

    @Test
    fun `every class in the knowledge base is mirrored, and no more`() {
        // A class added to the file and forgotten here would silently never be selectable; one
        // removed from the file but left here would be a prediction with no published basis.
        val inFile = Regex("\"class\"\\s*:\\s*\"([^\"]+)\"").findAll(kb).map { it.groupValues[1] }.toList()

        assertEquals(ApplianceClass.entries.map { it.name }.sorted(), inFile.sorted())
        assertEquals(inFile.size, knowledge.classes.size)
    }

    @Test
    fun `every class's service cadence, anchor and cost range match the file`() {
        ApplianceClass.entries.forEach { applianceClass ->
            val block = classBlock(applianceClass)
            val spec = knowledge.specFor(applianceClass)

            assertEquals(
                "$applianceClass interval_months",
                numberIn(block, "interval_months"),
                spec.intervalMonths.toLong(),
            )
            assertEquals("$applianceClass seasonal_month", seasonalMonthIn(block), spec.seasonalMonth?.toLong())
            assertEquals("$applianceClass cost low", costRangeIn(block).first, spec.costLow.minor)
            assertEquals("$applianceClass cost high", costRangeIn(block).second, spec.costHigh.minor)
        }
    }

    @Test
    fun `every class's warranty and power figures match the file`() {
        ApplianceClass.entries.forEach { applianceClass ->
            val block = classBlock(applianceClass)
            val spec = knowledge.specFor(applianceClass)

            assertEquals(
                "$applianceClass warranty_months",
                numberIn(block, "warranty_months"),
                spec.warrantyMonths.toLong(),
            )
            assertEquals("$applianceClass rated_watts", numberIn(block, "rated_watts"), spec.ratedWatts.toLong())
            assertEquals(
                "$applianceClass typical_minutes_per_day",
                numberIn(block, "typical_minutes_per_day"),
                spec.minutesPerDay.toLong(),
            )
        }
    }

    @Test
    fun `every consumable matches the file, with its own cost range`() {
        // The vehicle KB's consumables were deliberately *not* mirrored, because they carry an
        // interval and no price and a dated outflow with no rupee cannot enter a forecast (ADR-0052).
        // This KB gives every consumable a range for exactly that reason, so here they are mirrored
        // in full — and this test is what stops a priceless one being added later.
        ApplianceClass.entries.forEach { applianceClass ->
            val block = classBlock(applianceClass)
            val mirrored = knowledge.specFor(applianceClass).consumables
            val inFile = Regex("\"item\"\\s*:\\s*\"([^\"]+)\"").findAll(block).map { it.groupValues[1] }.toList()

            assertEquals("$applianceClass consumable keys", inFile, mirrored.map { it.item })
            mirrored.forEach { consumable ->
                val row = consumableBlock(block, consumable.item)
                assertEquals(
                    "${consumable.item} interval_months",
                    numberIn(row, "interval_months"),
                    consumable.intervalMonths.toLong(),
                )
                assertEquals("${consumable.item} cost low", costRangeIn(row).first, consumable.costLow.minor)
                assertEquals("${consumable.item} cost high", costRangeIn(row).second, consumable.costHigh.minor)
            }
        }
    }

    @Test
    fun `the prediction parameters match the file`() {
        val block = blockNamed("prediction")
        val spec = knowledge.prediction

        assertEquals("days_per_month", numberIn(block, "days_per_month"), spec.daysPerMonth.toLong())
        assertEquals(
            "default_tariff_paise_per_kwh",
            numberIn(block, "default_tariff_paise_per_kwh"),
            spec.defaultTariffPaisePerKwh.toLong(),
        )
        assertEquals(
            "seasonal_due_day_of_month",
            numberIn(block, "seasonal_due_day_of_month"),
            spec.seasonalDueDayOfMonth.toLong(),
        )
    }

    @Test
    fun `the alert thresholds match the file`() {
        val block = blockNamed("alerts")
        val spec = knowledge.alerts

        assertEquals("service_due_days", numberIn(block, "service_due_days"), spec.serviceDueDays.toLong())
        assertEquals("consumable_due_days", numberIn(block, "consumable_due_days"), spec.consumableDueDays.toLong())
        assertEquals(
            "warranty_reminder_days",
            Regex("\"warranty_reminder_days\"\\s*:\\s*\\[([^]]*)]").find(block)!!
                .groupValues[1].split(",").map { it.trim().toInt() },
            spec.warrantyReminderDays,
        )
    }

    @Test
    fun `the service lead time agrees with the vehicle engine's`() {
        // Two maintenance engines that disagree about what "due soon" means would put two different
        // leads on one dashboard. The KB comment says they match on purpose; this is what holds it.
        assertEquals(
            "AI-APP's service lead must stay equal to AI-VEH's 30 days",
            30,
            knowledge.alerts.serviceDueDays,
        )
    }

    // --- parsing ----------------------------------------------------------------------------------

    /** Result: the `_meta.version` string. Input: [key]. Output: [String]. */
    private fun stringAt(key: String): String =
        Regex("\"$key\"\\s*:\\s*\"([^\"]+)\"").find(kb)?.groupValues?.get(1)
            ?: throw AssertionError("no \"$key\" in the appliance KB")

    /** Result: the text of a top-level block, so a key is read from the right one. */
    private fun blockNamed(name: String): String =
        Regex("\"$name\"\\s*:\\s*\\{(.+?)\\n  }", RegexOption.DOT_MATCHES_ALL).find(kb)?.groupValues?.get(1)
            ?: throw AssertionError("no \"$name\" block in the appliance KB")

    /** Result: one class's JSON object, bounded by the next class so keys cannot bleed across. */
    private fun classBlock(applianceClass: ApplianceClass): String =
        Regex("\\{\\s*\"class\"\\s*:\\s*\"${applianceClass.name}\".+?\\n    }", RegexOption.DOT_MATCHES_ALL)
            .find(kb)?.value
            ?: throw AssertionError("no class \"${applianceClass.name}\" in the appliance KB")

    /** Result: one consumable's JSON object inside a class block. */
    private fun consumableBlock(
        block: String,
        item: String,
    ): String =
        Regex("\\{[^{}]*\"item\"\\s*:\\s*\"$item\"[^{}]*}").find(block)?.value
            ?: throw AssertionError("no consumable \"$item\" in the block")

    /** Result: a number under [key] inside [block]. Input: [block]; [key]. Output: [Long]. */
    private fun numberIn(
        block: String,
        key: String,
    ): Long =
        Regex("\"$key\"\\s*:\\s*(-?\\d+)").find(block)?.groupValues?.get(1)?.toLong()
            ?: throw AssertionError("no \"$key\" in the block")

    /** Result: `seasonal_month`, or `null` where the file says `null`. Input: [block]. */
    private fun seasonalMonthIn(block: String): Long? =
        Regex("\"seasonal_month\"\\s*:\\s*(null|\\d+)").find(block)?.groupValues?.get(1)
            ?.takeIf { it != "null" }?.toLong()

    /** Result: a `cost_range_minor` pair. Input: [block]. Output: (low, high). */
    private fun costRangeIn(block: String): Pair<Long, Long> {
        val pair =
            Regex("\"cost_range_minor\"\\s*:\\s*\\[\\s*(\\d+)\\s*,\\s*(\\d+)\\s*]").find(block)
                ?: throw AssertionError("no cost_range_minor in the block")
        return pair.groupValues[1].toLong() to pair.groupValues[2].toLong()
    }

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
        const val KB_PATH = "ai/knowledge/appliance-maintenance-kb.json"
    }
}
