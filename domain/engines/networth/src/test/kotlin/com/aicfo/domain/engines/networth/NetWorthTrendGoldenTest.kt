package com.aicfo.domain.engines.networth

import com.aicfo.core.common.Ok
import com.aicfo.core.model.Money
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import org.junit.Test

/**
 * Freezes the trend against expectations computed somewhere else (issue 6.6; FR-ACC-005, P-08).
 *
 * Why:  every number in `golden/networth-trend.txt` came from an independent Python implementation
 *       written from the specification rather than from the Kotlin it guards, the discipline
 *       `AllocationGoldenTest` and `InvestmentGoldenTest` set. Agreement between the two is evidence
 *       the figures are **right**, not merely unchanged.
 *
 *       It matters most for the percentage. A ratio bug here does not crash and does not look wrong
 *       in a screenshot — it reports a user whose net worth went from −₹50,000 to −₹10,000 as having
 *       fallen 80%, which is the opposite of what happened, in the position they trust most. Five of
 *       the ten records exist only to pin the cases where the answer must be **no percentage at
 *       all**.
 * What: rising, falling, flat, one reading, none, negative throughout, crossing zero, starting at
 *       zero, and truncation in both directions.
 * Result: a change to the ratio, the rounding direction or the absent/zero distinction shows up as a
 *         diff rather than as a figure nobody notices moving.
 * Changelog: 2026-08-29 — Created for issue 6.6.
 */
class NetWorthTrendGoldenTest {
    private val engine = NetWorthEngineFactory.create()

    /** Input: every record in the fixture. Output: asserts each measures exactly as recorded. */
    @Test
    fun `every golden series still measures the way an independent implementation measured it`() {
        val records = records()
        assertWithMessage("the fixture must not have been emptied").that(records).isNotEmpty()

        for (record in records) {
            val label = record.required("label")
            val outcome =
                engine.trend(
                    NetWorthTrendInput(
                        points = record.points(),
                        range = NetWorthRange.ALL,
                        nowUtcMillis = FIXED_MILLIS,
                    ),
                )

            assertWithMessage("%s — must return Ok", label).that(outcome).isInstanceOf(Ok::class.java)
            val trend = (outcome as Ok).value

            assertWithMessage("%s — points read back", label)
                .that(trend.points).hasSize(record.int("expect_points"))
            assertWithMessage("%s — first", label)
                .that(trend.first.wire()).isEqualTo(record.required("expect_first"))
            assertWithMessage("%s — last", label)
                .that(trend.last.wire()).isEqualTo(record.required("expect_last"))
            assertWithMessage("%s — change in paise", label)
                .that(trend.change?.minor?.toString() ?: "none").isEqualTo(record.required("expect_change"))
            assertWithMessage("%s — change in basis points", label)
                .that(trend.changeBps?.toString() ?: "none").isEqualTo(record.required("expect_bps"))
            assertWithMessage("%s — high", label)
                .that(trend.high.wire()).isEqualTo(record.required("expect_high"))
            assertWithMessage("%s — low", label)
                .that(trend.low.wire()).isEqualTo(record.required("expect_low"))
        }
    }

    /**
     * Input:  every record that records a change.
     * Output: asserts the fixture's own arithmetic holds — `last − first == change`.
     *
     * The *fixture* is checked here, not the engine: a hand-edited record whose change no longer
     * matches its endpoints is a wrong expectation, and a wrong expectation nothing checks is how a
     * golden file starts lying while staying green.
     */
    @Test
    fun `every golden change is the difference between the endpoints it records`() {
        for (record in records()) {
            val change = record.required("expect_change")
            if (change == NONE) continue
            val expected = record.pointValue("expect_last") - record.pointValue("expect_first")

            assertWithMessage("%s — recorded change disagrees with its own endpoints", record.required("label"))
                .that(change.toLong()).isEqualTo(expected)
        }
    }

    /**
     * Input:  the fixture's labels.
     * Output: asserts it still covers the paths it claims to.
     *
     * Bumped deliberately when a record is added, so a deletion cannot pass unnoticed — a golden
     * gate that quietly shrinks to one trivial case is green and worthless.
     */
    @Test
    fun `the fixture still covers the paths it says it does`() {
        val labels = records().map { it.required("label") }

        assertThat(labels).hasSize(EXPECTED_RECORDS)
        listOf(
            "a rising series",
            "a falling series",
            "flat",
            "one reading",
            "no readings at all",
            "negative throughout",
            "crossing zero",
            "starting at exactly zero",
            "truncates toward zero, positive",
            "truncates toward zero, negative",
        ).forEach { path ->
            assertWithMessage("the fixture lost its '%s' record", path)
                .that(labels.any { path in it }).isTrue()
        }
    }

    /**
     * Input:  every record whose series starts at or below zero.
     * Output: asserts none of them reports a percentage — the refusal, stated once over the whole
     *         fixture rather than record by record, so a new record cannot be added that quietly
     *         breaks the rule.
     */
    @Test
    fun `no golden series starting at or below zero reports a percentage`() {
        for (record in records()) {
            val first = record.required("expect_first")
            if (first == NONE || record.pointValue("expect_first") > 0L) continue

            assertWithMessage(
                "%s — starts at or below zero, so a percentage of it would mislead (P-03)",
                record.required("label"),
            ).that(record.required("expect_bps")).isEqualTo(NONE)
        }
    }

    /** Result: the point as the fixture writes it. Input: the receiver. Output: [String]. */
    private fun NetWorthPoint?.wire(): String = this?.let { "${it.asOfIsoDate}:${it.netWorth.minor}" } ?: NONE

    /**
     * One series out of the fixture: its expectations and the snapshots that produce them.
     * Result: a record. Input: the `# key=value` headers and the payload rows. Output: an instance.
     */
    private class Record(
        private val values: Map<String, String>,
        private val rows: List<String>,
    ) {
        fun required(key: String): String =
            values[key] ?: error("golden record is missing '$key': ${values["label"] ?: values}")

        fun int(key: String): Int = required(key).toInt()

        /** Result: the paise out of an `iso_date:paise` header. Input: [key]. Output: [Long]. */
        fun pointValue(key: String): Long = required(key).substringAfterLast(':').toLong()

        /** Result: the stored snapshots this record describes, oldest first. Output: the points. */
        fun points(): List<NetWorthPoint> =
            rows.map { row ->
                val fields = row.split('|')
                NetWorthPoint(
                    asOfIsoDate = fields[AS_OF_DATE],
                    netWorth = Money(fields[NET_WORTH].toLong()),
                )
            }
    }

    /**
     * Reads the fixture into records.
     * Why:    parsed by hand because `:domain:*` has no serialisation dependency (ARC-002) — the
     *         same reason and the same shape as `AllocationGoldenTest.records`.
     * Result: one [Record] per `===` block. Input: none. Output: the records.
     */
    private fun records(): List<Record> {
        val text =
            checkNotNull(javaClass.getResourceAsStream(FIXTURE)) {
                "golden fixture $FIXTURE is missing — this gate would otherwise pass vacuously"
            }.bufferedReader().readText()

        return text.split("\n===")
            .drop(1)
            .map { block ->
                val lines = block.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.toList()
                Record(
                    values =
                        lines.filter { it.startsWith("# ") && "=" in it }
                            .associate { line ->
                                val body = line.removePrefix("# ")
                                body.substringBefore('=') to body.substringAfter('=')
                            },
                    rows = lines.filter { !it.startsWith("#") && "|" in it },
                )
            }
    }

    private companion object {
        const val FIXTURE = "/golden/networth-trend.txt"

        /** The fixture's word for an absent figure, kept distinct from a zero one (P-03). */
        const val NONE = "none"

        /** Payload-row columns: `as_of_iso_date|net_worth_minor`. */
        const val AS_OF_DATE = 0
        const val NET_WORTH = 1

        /** Any fixed instant: the trend reads no clock, so this only lands in provenance. */
        const val FIXED_MILLIS = 1_785_542_400_000L

        /** Bump deliberately when a record is added, so a deletion cannot pass unnoticed. */
        const val EXPECTED_RECORDS = 10
    }
}
