package com.aicfo.domain.engines.nature

import com.aicfo.core.common.Ok
import com.aicfo.core.model.AccountType
import com.aicfo.core.model.CategoryNature
import com.aicfo.core.model.Money
import com.aicfo.core.model.TransactionType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The golden-file gate for nature classification (issue 4.3; §21.5, and the acceptance criteria).
 *
 * Why:  §21.5 asks engines for "golden-file tests (fixed input snapshot → expected result)", and the
 *       acceptance criteria for this issue name one explicitly. What makes it worth having next to
 *       [NatureEngineTest] is not more cases — it is a **different assertion**: every record fixes
 *       the cited rule as well as the nature, so a step that fires for the wrong reason and happens
 *       to land on the right answer still fails.
 *
 *       That is not a hypothetical distinction here. Four of §8.3.1's six steps can produce NEED and
 *       three can produce WANT, so half the fixtures below would pass under a decision order with
 *       two steps swapped. Only the citation tells them apart — and a wrong order stays invisible
 *       until a merchant, an account or a category changes and the answer quietly turns.
 * What: runs every record in `golden/nature.txt` and asserts the nature, the citations in order, and
 *       whether the verdict reaches §8.3's review flow.
 * Result: a reordering of the decision order fails the build with the record that caught it.
 * Changelog: 2026-08-10 — Created for issue 4.3.
 *
 * **A gate nobody has watched go red is not a gate.** Before this was trusted, records were
 * deliberately mis-labelled and this test was confirmed to fail — the same check the drift tests get,
 * and the lesson `docs/report/2026-07-25-governance-standards-audit.md` recorded.
 */
class NatureGoldenTest {
    private val engine = NatureEngineFactory.create()
    private val records: List<GoldenRecord> by lazy { loadRecords() }

    /**
     * Input:  the golden file.
     * Output: asserts it was found and holds every branch the decision order has. Without this, the
     *         assertions below would score nothing perfectly if the resource ever went missing —
     *         which is precisely how a coverage gate in this repo once passed at 0%.
     */
    @Test
    fun `the golden file is loaded and exercises every step`() {
        assertTrue("fewer records than the file is documented to hold", records.size >= MIN_RECORDS)
        val cited = records.flatMap { it.cite }.toSet()
        assertEquals(
            "the golden file no longer exercises every CLS-NAT-* step",
            (1..STEP_COUNT).map { "CLS-NAT-00$it" }.toSet(),
            cited,
        )
        assertTrue("no record exercises the review flow", records.any { it.flagged })
        assertTrue("every record is unflagged — the flag would prove nothing", records.any { !it.flagged })
    }

    /**
     * Input:  every record.
     * Output: fails naming each record whose nature came out wrong.
     */
    @Test
    fun `every record classifies to its expected nature`() {
        val wrong =
            records.mapNotNull { record ->
                val actual = classify(record).nature
                if (actual == record.expect) null else "${record.label}: expected ${record.expect}, got $actual"
            }

        assertEquals("$wrong", 0, wrong.size)
    }

    /**
     * The assertion this file exists for.
     * Input:  every record.
     * Output: fails naming each record decided by the wrong step — **including the ones whose nature
     *         is right**, which is the whole point. Compared as an ordered list, because step 6
     *         appends itself to whatever decided the nature and the order says which is which.
     */
    @Test
    fun `every record is decided by the expected rule`() {
        val wrong =
            records.mapNotNull { record ->
                val actual = classify(record).provenance.evidence.map { it.ruleId }
                if (actual == record.cite) null else "${record.label}: expected ${record.cite}, got $actual"
            }

        assertEquals("$wrong", 0, wrong.size)
    }

    /**
     * Input:  every record.
     * Output: fails naming each record whose review-flow membership is wrong. Asserted separately
     *         because §8.3's `low_confidence_handling` is a promise about *which* transactions the
     *         app asks about, and an engine that flagged everything or nothing would keep every
     *         nature above correct while making that promise worthless.
     */
    @Test
    fun `every record reaches the review flow exactly when it should`() {
        val wrong =
            records.mapNotNull { record ->
                val actual = classify(record).isFlagged
                if (actual == record.flagged) null else "${record.label}: expected flagged=${record.flagged}"
            }

        assertEquals("$wrong", 0, wrong.size)
    }

    // --- running and loading --------------------------------------------------------------------

    /**
     * Runs the engine over one record.
     * Why:    every record shares one instant, so the whole file is reproducible to the byte (P-08) —
     *         the engine reads no clock (TIM-001), and this is what proves the file would score the
     *         same in a year.
     * Result: the verdict. Input: [record]. Output: [NatureVerdict]; fails on an `Err`.
     */
    private fun classify(record: GoldenRecord): NatureVerdict {
        val outcome =
            engine.classify(
                NatureInput(
                    accountType = record.account,
                    type = record.type,
                    amount = record.amount,
                    nowUtcMillis = NOW,
                    counterpartAccountType = record.counterpart,
                    merchantHistory = record.history,
                    categoryNature = record.category,
                    categoryMedian = record.median,
                ),
            )
        assertTrue("the engine errored on a well-formed record: ${record.label}", outcome is Ok)
        return (outcome as Ok).value
    }

    /**
     * Reads the golden file off the classpath.
     * Why:    a resource rather than a path on disk, so the fixture travels with the test jar and
     *         cannot be found only when the working directory happens to be right.
     * Result: every record. Input: none. Output: `List<GoldenRecord>`; throws if the resource is
     *         missing, which is louder than classifying nothing perfectly.
     */
    private fun loadRecords(): List<GoldenRecord> {
        val raw =
            checkNotNull(javaClass.getResourceAsStream(FIXTURE_PATH)) {
                "$FIXTURE_PATH is not on the test classpath"
            }.bufferedReader().readText()
        return raw.split(SEPARATOR)
            .map { it.lines().map(String::trim) }
            .mapNotNull { it.toRecordOrNull() }
    }

    private companion object {
        const val FIXTURE_PATH = "/golden/nature.txt"
        const val SEPARATOR = "==="

        /** Fixed so the whole file is reproducible (P-08, TIM-001). */
        const val NOW = 1_786_082_400_000L

        /** The file is documented as covering every branch; fewer means one was lost in an edit. */
        const val MIN_RECORDS = 20

        /** §8.3.1 has six steps, and the file must still exercise all of them. */
        const val STEP_COUNT = 6
    }
}

/** A record header: `# key=value`, lower-case key, no spaces around the `=`. See [toRecordOrNull]. */
private val HEADER = Regex("""#\s*([a-z_]+)=(.*)""")

/**
 * One labelled case from the golden file (issue 4.3).
 *
 * Why:    a value rather than a map, so a record missing a required field is a parse failure at load
 *         time instead of a silently skipped case that made the score look better.
 * Result: the element type of the golden file.
 * Changelog: 2026-08-10 — Created for issue 4.3.
 *
 * Input:  [account], [counterpart], [type], [amount], [category], [median], [history] — the input;
 *         [expect], [cite], [flagged] — the fixed expectations; [label] — the record as written, for
 *         a failure message that names the case rather than an index.
 * Output: an immutable value.
 */
private data class GoldenRecord(
    val account: AccountType,
    val counterpart: AccountType?,
    val type: TransactionType,
    val amount: Money,
    val category: CategoryNature?,
    val median: Money?,
    val history: List<NatureHistoryRow>,
    val expect: CategoryNature,
    val cite: List<String>,
    val flagged: Boolean,
    val label: String,
)

/**
 * Parses one `# key=value` block into a record.
 * Why:    matched with a regex rather than "starts with # and contains =", the mistake
 *         `:domain:engines:sms`'s first draft made: the file's own prose contains `expect = …` and a
 *         loose split read the documentation as fixtures.
 * Result: the record, or `null` for the file's header block and the blank tail after the last
 *         separator. A block carrying an `expect=` but missing a required field is an **error**
 *         rather than a skip — a truncated record must not quietly shrink the set the gate measures.
 * Input:  the receiver — one block's trimmed lines. Output: `GoldenRecord?`.
 * Changelog: 2026-08-10 — Created for issue 4.3.
 */
private fun List<String>.toRecordOrNull(): GoldenRecord? {
    val headers =
        mapNotNull { HEADER.matchEntire(it) }
            .associate { it.groupValues[1] to it.groupValues[2].trim() }
    val expect = headers["expect"] ?: return null
    val account = checkNotNull(headers["account"]) { "a record with expect=$expect has no account" }
    return GoldenRecord(
        account = account.asAccountType(),
        counterpart = headers["counterpart"]?.asAccountType(),
        type = checkNotNull(headers["type"]) { "a record with expect=$expect has no type" }.asTransactionType(),
        amount = Money(checkNotNull(headers["amount"]) { "a record with expect=$expect has no amount" }.toLong()),
        category = headers["category"]?.asNature(),
        median = headers["median"]?.let { Money(it.toLong()) },
        history = headers["history"].orEmpty().asHistory(),
        expect = expect.asNature(),
        cite =
            checkNotNull(
                headers["cite"],
            ) { "a record with expect=$expect cites no rule" }.split(',').map { it.trim() },
        flagged =
            checkNotNull(headers["flagged"]) { "a record with expect=$expect does not say if it is flagged" }
                .toBooleanStrict(),
        label = "$account/${headers["type"]} ${headers["amount"]} -> $expect",
    )
}

/** Result: the account type. Input: the receiver — a stored value. Output: [AccountType]; throws if unknown. */
private fun String.asAccountType(): AccountType =
    checkNotNull(AccountType.entries.firstOrNull { it.storedValue == this }) { "unknown account type '$this'" }

/** Result: the transaction type. Input: the receiver. Output: [TransactionType]; throws if unknown. */
private fun String.asTransactionType(): TransactionType =
    checkNotNull(TransactionType.entries.firstOrNull { it.storedValue == this }) { "unknown transaction type '$this'" }

/** Result: the nature. Input: the receiver — a **stored** value, not the KB spelling. Output: [CategoryNature]. */
private fun String.asNature(): CategoryNature =
    checkNotNull(CategoryNature.fromStored(this)) { "unknown nature '$this'" }

/**
 * Parses a `nature:count,nature:count` history list.
 * Result: the rows, empty for a blank field. Input: the receiver. Output: `List<NatureHistoryRow>`.
 */
private fun String.asHistory(): List<NatureHistoryRow> =
    if (isBlank()) {
        emptyList()
    } else {
        split(',').map { pair ->
            val (nature, count) = pair.trim().split(':')
            NatureHistoryRow(nature.asNature(), count.toInt())
        }
    }
