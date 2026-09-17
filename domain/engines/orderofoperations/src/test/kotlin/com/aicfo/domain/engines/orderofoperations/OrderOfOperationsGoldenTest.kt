package com.aicfo.domain.engines.orderofoperations

import com.aicfo.core.common.Ok
import com.aicfo.core.model.Money
import com.aicfo.domain.engines.goals.SurplusBasis
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/**
 * The golden-file gate for AI-FOO (issue 7.5; §21.5, and the acceptance criterion "golden-file test
 * across representative states").
 *
 * Why:  [OrderOfOperationsEngineTest] pins each branch alone; this file fixes **all eight stages of a
 *       whole household at once** — status, reason, need and amount — plus the top action, the
 *       leftover and the debt order. A ranking that comes out right for the wrong reason still fails.
 *       That matters here more than usual: `₹0` is the right amount for a stage the money ran out
 *       above, a stage the gate held, a skipped stage, a deferred one and a month in the red. Only
 *       the status and reason beside the number tell them apart.
 * What: runs every record in `golden/order-of-operations.txt` through the engine and compares every
 *       field the record states.
 * Result: a change to any stage's decision fails the build naming the record that caught it.
 * Changelog: 2026-09-17 — Created for issue 7.5.
 *
 * **Every expectation is read from the file, never computed here** — issue 7.4's tautology lesson.
 * The comment lines in each record show the arithmetic for a human; this test never repeats it.
 */
class OrderOfOperationsGoldenTest {
    private val engine = OrderOfOperationsEngineFactory.create()
    private val records: List<GoldenCase> by lazy { loadRecords() }

    /**
     * Input:  the golden file.
     * Output: asserts it loaded and still exercises every status, every reason and every basis.
     *         Without this the comparison below would score an empty file perfectly — which is how a
     *         coverage gate in this repo once passed at 0%.
     */
    @Test
    fun `the golden file is loaded and exercises every status, reason and basis`() {
        assertTrue("fewer records than the file is documented to hold", records.size >= MIN_RECORDS)
        val expected = records.flatMap { it.stages }

        assertEquals(StageStatus.entries.map { it.name }.toSet(), expected.map { it.status }.toSet())
        assertEquals(StageReason.entries.map { it.name }.toSet(), expected.map { it.reason }.toSet())
        assertEquals(SurplusBasis.entries.map { it.name }.toSet(), records.map { it.basis }.toSet())
        assertTrue("no record has an unknown surplus", records.any { it.surplus == null })
        assertTrue("no record has a negative surplus", records.any { (it.surplus ?: 0L) < 0L })
        assertTrue("no record leaves money idle", records.any { it.unallocated > 0L })
        assertTrue("no record has no top action", records.any { it.top == NONE })
    }

    /** Input: every record. Output: fails naming each record whose eight stages came out wrong. */
    @Test
    fun `every record ranks all eight stages exactly as written`() {
        val wrong =
            records.flatMap { record ->
                val actual = rank(record).stages.map(::lineOf)
                record.stages.zip(actual).filter { (want, got) -> want != got }.map { (want, got) ->
                    "${record.label}: expected $want but was $got"
                }
            }

        assertTrue(wrong.joinToString("\n"), wrong.isEmpty())
    }

    /** Input: every record. Output: fails naming each record whose top action or leftover is wrong. */
    @Test
    fun `every record has its written top action and leftover`() {
        val wrong =
            records.mapNotNull { record ->
                val actual = rank(record)
                val top = actual.topAction?.stage?.name ?: NONE
                val left = actual.unallocated.minor
                if (top == record.top && left == record.unallocated) {
                    null
                } else {
                    "${record.label}: expected top=${record.top} left=${record.unallocated}, was top=$top left=$left"
                }
            }

        assertTrue(wrong.joinToString("\n"), wrong.isEmpty())
    }

    /** Input: every record that states a debt order. Output: fails naming each order that differs. */
    @Test
    fun `every stated debt order is the order shown`() {
        val wrong =
            records.flatMap { record ->
                val stages = rank(record).stages.associateBy { it.stage.name }
                record.debtOrders.mapNotNull { (stage, ids) ->
                    val actual = stages.getValue(stage).debts.map { it.accountId }
                    if (actual == ids) null else "${record.label} $stage: expected $ids but was $actual"
                }
            }

        assertTrue(wrong.joinToString("\n"), wrong.isEmpty())
        assertTrue("no record states a debt order", records.any { it.debtOrders.isNotEmpty() })
    }

    // --- plumbing -----------------------------------------------------------------------------

    /** Result: the engine's ranking for [record]. Output: the `Ok` value; fails on `Err`. */
    private fun rank(record: GoldenCase): OrderOfOperations {
        val result =
            engine.rank(
                OrderOfOperationsInput(
                    monthlySurplus = record.surplus?.let(::Money),
                    surplusBasis = SurplusBasis.valueOf(record.basis),
                    monthlyEssentials = record.essentials?.let(::Money),
                    liquidFunds = Money(record.liquid),
                    emergencyShortfall = record.shortfall?.let(::Money),
                    emergencyTopUpMonthly = Money(record.topUp),
                    emergencyRunwayMonthsBps = record.runwayBps,
                    debts = record.debts,
                    goalsRequiredMonthly = Money(record.goalsRequired),
                    goalCount = record.goalCount,
                    today = TODAY,
                ),
            )
        assertTrue("${record.label}: expected Ok, was $result", result is Ok)
        return (result as Ok).value
    }

    /** Result: a stage as the same `STAGE|STATUS|REASON|need|amount` line the file writes. */
    private fun lineOf(stage: StageOutcome): StageLine =
        StageLine(
            stage = stage.stage.name,
            status = stage.status.name,
            reason = stage.reason.name,
            need = stage.need?.minor?.toString() ?: UNKNOWN,
            amount = stage.amountMonthly.minor.toString(),
        )

    /** Result: every record in the file. Output: the parsed records; fails if the file is missing. */
    private fun loadRecords(): List<GoldenCase> {
        val text =
            requireNotNull(javaClass.getResourceAsStream(GOLDEN_PATH)) {
                "missing golden resource $GOLDEN_PATH"
            }.bufferedReader().readText()
        return text.split("===")
            .map { block -> block.lines().filter { it.trimStart().startsWith("#") }.map(::keyValue) }
            .filter { it.isNotEmpty() }
            .map(GoldenCase::from)
    }

    /** Result: one `# key=value` line split. Input: [line]. Output: the pair. */
    private fun keyValue(line: String): Pair<String, String> {
        val body = line.trimStart().removePrefix("#").trim()
        return body.substringBefore('=').trim() to body.substringAfter('=').trim()
    }

    /** One stage as the file writes it, compared as text so a mismatch prints readably. */
    private data class StageLine(
        val stage: String,
        val status: String,
        val reason: String,
        val need: String,
        val amount: String,
    ) {
        override fun toString() = "$stage|$status|$reason|$need|$amount"
    }

    /** One household from the golden file. */
    private data class GoldenCase(
        val label: String,
        val surplus: Long?,
        val basis: String,
        val essentials: Long?,
        val liquid: Long,
        val shortfall: Long?,
        val topUp: Long,
        val runwayBps: Int?,
        val debts: List<DebtPosition>,
        val goalsRequired: Long,
        val goalCount: Int,
        val top: String,
        val unallocated: Long,
        val stages: List<StageLine>,
        val debtOrders: List<Pair<String, List<String>>>,
    ) {
        companion object {
            /**
             * Result: a record. Input: [pairs] — the record's key/value lines, in order.
             * Output: [GoldenCase]; throws naming a missing expectation rather than defaulting it, so
             *   a typo cannot turn an assertion into a tautology.
             */
            fun from(pairs: List<Pair<String, String>>): GoldenCase {
                val fields = pairs.filterNot { it.first in REPEATABLE }.toMap()
                val repeated = { key: String -> pairs.filter { it.first == key }.map { it.second } }
                val stages = repeated("expect").map(::stageFrom)
                require(stages.size == FooStage.entries.size) {
                    "${fields["label"]}: a record must state all eight stages, stated ${stages.size}"
                }
                return GoldenCase(
                    label = fields.getValue("label"),
                    surplus = fields.getValue("surplus").orUnknown()?.toLong(),
                    basis = fields.getValue("basis"),
                    essentials = (fields["essentials"] ?: UNKNOWN).orUnknown()?.toLong(),
                    liquid = fields["liquid"]?.toLong() ?: 0L,
                    shortfall = (fields["ef_shortfall"] ?: UNKNOWN).orUnknown()?.toLong(),
                    topUp = fields["ef_topup"]?.toLong() ?: 0L,
                    runwayBps = (fields["ef_runway_bps"] ?: UNKNOWN).orUnknown()?.toInt(),
                    debts = repeated("debt").map(::debtFrom),
                    goalsRequired = fields["goals_required"]?.toLong() ?: 0L,
                    goalCount = fields["goal_count"]?.toInt() ?: 0,
                    top = fields.getValue("expect_top"),
                    unallocated = fields.getValue("expect_unallocated").toLong(),
                    stages = stages,
                    debtOrders =
                        repeated("expect_debts").map {
                            it.substringBefore('|') to it.substringAfter('|').split(',')
                        },
                )
            }

            /** Result: `null` for `unknown`, the receiver otherwise. */
            private fun String.orUnknown(): String? = takeIf { it != UNKNOWN }

            /** Result: a stage line from `STAGE|STATUS|REASON|need|amount`. */
            private fun stageFrom(value: String): StageLine {
                val parts = value.split('|')
                require(parts.size == STAGE_FIELDS) { "a stage line has five fields: $value" }
                return StageLine(parts[0], parts[1], parts[2], parts[3], parts[4])
            }

            /** Result: a debt from `id|KIND|outstanding|apr_bps or unknown`. */
            private fun debtFrom(value: String): DebtPosition {
                val parts = value.split('|')
                require(parts.size == DEBT_FIELDS) { "a debt line has four fields: $value" }
                return DebtPosition(
                    accountId = parts[0],
                    name = parts[0],
                    kind = DebtKind.valueOf(parts[1]),
                    outstanding = Money(parts[2].toLong()),
                    aprBps = parts[3].orUnknown()?.toInt(),
                )
            }

            private val REPEATABLE = setOf("debt", "expect", "expect_debts")
            private const val STAGE_FIELDS = 5
            private const val DEBT_FIELDS = 4
        }
    }

    private companion object {
        const val GOLDEN_PATH = "/golden/order-of-operations.txt"
        const val UNKNOWN = "unknown"
        const val NONE = "none"

        /** The file documents twelve households; fewer means one went missing. */
        const val MIN_RECORDS = 12

        val TODAY: LocalDate = LocalDate.of(2026, 9, 17)
    }
}
