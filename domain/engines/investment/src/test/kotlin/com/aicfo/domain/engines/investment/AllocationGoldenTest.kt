package com.aicfo.domain.engines.investment

import com.aicfo.core.common.Ok
import com.aicfo.core.model.AssetClass
import com.aicfo.core.model.Money
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import org.junit.Test

/**
 * Freezes the allocation split against expectations computed somewhere else (issue 6.4; §11.2,
 * P-08).
 *
 * Why:  every number in `golden/allocation.txt` came from an independent Python implementation
 *       written from the specification — largest-remainder apportionment over integer basis points
 *       — rather than from the Kotlin it guards. Agreement between the two is evidence the shares
 *       are **right**, not merely unchanged. This is the discipline `InvestmentGoldenTest` set for
 *       XIRR, and it matters more here than it looks: an apportionment bug does not crash, it just
 *       quietly shows a pie that adds to 99.97%.
 * What: nine portfolios — the boundary pair that sits exactly on and one basis point past both
 *       ceilings, all three flag kinds at once, an unpriced exclusion, an account counted whole,
 *       the thirds case where a basis point has to be handed out, and the three empties.
 * Result: a change to the ordering, the rounding or any threshold shows up here as a diff rather
 *         than as a percentage nobody notices moving.
 * Changelog: 2026-08-28 — Created for issue 6.4.
 */
class AllocationGoldenTest {
    private val engine = InvestmentEngineFactory.create()

    /** Input: every record in the fixture. Output: asserts each splits and flags exactly as recorded. */
    @Test
    fun `every golden portfolio still splits the way an independent implementation split it`() {
        val records = records()
        assertWithMessage("the fixture must not have been emptied").that(records).isNotEmpty()

        for (record in records) {
            val label = record.required("label")
            val result = engine.allocation(AllocationInput(record.positions(), nowUtcMillis = 1L))

            assertWithMessage("%s — must return Ok", label).that(result).isInstanceOf(Ok::class.java)
            val allocation = (result as Ok).value

            assertWithMessage("%s — total", label)
                .that(allocation.total.minor).isEqualTo(record.long("expect_total"))
            val slices =
                allocation.slices.joinToString(",") {
                    "${it.assetClass.storedValue}:${it.value.minor}:${it.shareBps}"
                }
            assertWithMessage("%s — slices", label)
                .that(slices).isEqualTo(record.listOrEmpty("expect_slices"))
            assertWithMessage("%s — flags", label)
                .that(allocation.flags.joinToString(",") { it.wire() })
                .isEqualTo(record.listOrEmpty("expect_flags"))
            assertWithMessage("%s — valued positions", label)
                .that(allocation.valuedCount).isEqualTo(record.int("expect_valued"))
            assertWithMessage("%s — unvalued positions", label)
                .that(allocation.unvaluedCount).isEqualTo(record.int("expect_unvalued"))
            assertWithMessage("%s — unavailable reason", label)
                .that(allocation.unavailable?.name ?: "none").isEqualTo(record.required("expect_unavailable"))
        }
    }

    /**
     * Input:  every record's slices.
     * Output: asserts the shares account for the whole portfolio wherever there is one.
     *
     * The invariant is also a `require` on [PortfolioAllocation], so this cannot fail without that
     * throwing first — which is the point. It is asserted here as well so the *fixture* is checked
     * too: a hand-edited record whose shares no longer sum to 10 000 is a wrong expectation, and a
     * wrong expectation that nothing checks is how a golden file starts lying.
     */
    @Test
    fun `every golden split accounts for the whole portfolio`() {
        for (record in records()) {
            val slices = record.listOrEmpty("expect_slices")
            if (slices.isEmpty()) continue
            val summed = slices.split(",").sumOf { it.substringAfterLast(':').toInt() }

            assertWithMessage("%s — recorded shares sum to %s bps, not 10000", record.required("label"), summed)
                .that(summed).isEqualTo(10_000)
        }
    }

    /**
     * Input:  the fixture's labels.
     * Output: asserts it still covers the paths it claims to.
     *
     * Bumped deliberately when a record is added, so a deletion cannot pass unnoticed — a golden
     * gate that silently shrinks to one trivial case is green and worthless.
     */
    @Test
    fun `the fixture still covers the paths it says it does`() {
        val labels = records().map { it.required("label") }

        assertThat(labels).hasSize(EXPECTED_RECORDS)
        listOf(
            "exactly on both boundaries",
            "one basis point past",
            "all three flag kinds",
            "unpriced holding is excluded",
            "counted whole",
            "distributed, not dropped",
            "nothing priced",
            "wholly exited",
            "no positions at all",
        ).forEach { path ->
            assertWithMessage("the fixture lost its '%s' record", path)
                .that(labels.any { path in it }).isTrue()
        }
    }

    /** Result: the flag rendered as the fixture writes it. Input: the receiver. Output: [String]. */
    private fun ConcentrationFlag.wire(): String {
        val subject = if (kind == ConcentrationKind.SINGLE_HOLDING) name else assetClass!!.storedValue
        return "$kind:$subject:$measuredBps:$thresholdBps"
    }

    /**
     * One portfolio out of the fixture: its expectations and the positions that produce them.
     * Result: a record. Input: the `# key=value` headers and the payload rows. Output: an instance.
     */
    private class Record(
        private val values: Map<String, String>,
        private val rows: List<String>,
    ) {
        fun required(key: String): String =
            values[key] ?: error("golden record is missing '$key': ${values["label"] ?: values}")

        fun int(key: String): Int = required(key).toInt()

        fun long(key: String): Long = required(key).toLong()

        /** `none` is the fixture's empty list, kept distinct from a missing key. */
        fun listOrEmpty(key: String): String = required(key).takeIf { it != "none" } ?: ""

        /**
         * Result: the positions this record describes.
         * `none` in the holding id means the position is an account counted whole; `none` in the
         * value means it was never priced, which is absent and not zero (P-03).
         */
        fun positions(): List<PortfolioPosition> =
            rows.map { row ->
                // Indexed rather than destructured: five fields is past detekt's limit for a
                // destructuring declaration, and the field names are documented in the fixture's
                // own header anyway.
                val fields = row.split('|')
                PortfolioPosition(
                    holdingId = fields[HOLDING_ID].takeIf { it != "none" },
                    accountId = fields[ACCOUNT_ID],
                    name = fields[NAME],
                    assetClass =
                        checkNotNull(AssetClass.fromStored(fields[ASSET_CLASS])) {
                            "golden record names an asset class that does not exist: ${fields[ASSET_CLASS]}"
                        },
                    value = fields[VALUE].takeIf { it != "none" }?.let { Money(it.toLong()) },
                )
            }
    }

    /**
     * Reads the fixture into records.
     * Why:    parsed by hand because `:domain:*` has no serialisation dependency (ARC-002).
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
        const val FIXTURE = "/golden/allocation.txt"

        /** Payload-row columns: `holding_id|account_id|name|asset_class|value_minor`. */
        const val HOLDING_ID = 0
        const val ACCOUNT_ID = 1
        const val NAME = 2
        const val ASSET_CLASS = 3
        const val VALUE = 4

        /** Bump deliberately when a record is added, so a deletion cannot pass unnoticed. */
        const val EXPECTED_RECORDS = 9
    }
}
