package com.aicfo.domain.engines.sms

import com.aicfo.core.common.Ok
import com.aicfo.core.model.Money
import com.aicfo.core.model.SmsMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The accuracy gate for the SMS parser (issue 3.9; §21.5).
 *
 * Why:  §21.5 says AI evaluation runs against frozen labelled datasets with regression thresholds
 *       that block merges. **Unlike the receipt parser, the SRS puts no number on this one** — §18.1
 *       gives OCR a 95% target and says nothing equivalent about SMS — so the two thresholds here
 *       are ours, and ENGINE.md says so. They are deliberately lopsided:
 *
 *       - the transactional half must be read at **≥ 95%** on amount and direction;
 *       - the non-transactional half must be refused at **100%**, with no budget at all.
 *
 *       That second gate is the one worth having. A missed alert costs the user a manual entry they
 *       were already making before this feature existed. A false positive puts money in their ledger
 *       that never moved, in an app whose entire claim is that its numbers come from arithmetic
 *       (P-03). One OTP or one declined-payment notice slipping through fails the build.
 * What: runs every fixture in `eval/sms.txt` and scores both halves separately.
 * Result: a keyword edit that starts admitting adverts, or stops reading UPI payments, fails CI.
 * Changelog: 2026-08-07 — Created for issue 3.9.
 *
 * **A gate nobody has watched go red is not a gate.** Before this was trusted, fixtures were
 * deliberately mis-labelled and this test was confirmed to fail — the same check `RulebookDriftTest`
 * and `ReceiptEvalTest` get, and the lesson `docs/report/2026-07-25-governance-standards-audit.md`
 * recorded.
 */
class SmsEvalTest {
    private val engine = SmsEngineFactory.create()
    private val fixtures: List<SmsFixture> by lazy { loadFixtures() }

    /**
     * Input:  the fixture file.
     * Output: asserts it was found and holds both halves. Without this, every assertion below would
     *         score 0 out of 0 and pass vacuously if the resource ever went missing — which is
     *         precisely how a coverage gate in this repo once passed at 0%.
     */
    @Test
    fun `the evaluation set is loaded and holds both halves`() {
        assertTrue("fewer fixtures than the set is documented to hold", fixtures.size >= MIN_FIXTURES)
        assertTrue("no transactional fixtures", transactional().isNotEmpty())
        assertTrue("no fixtures to refuse — the gate that matters would pass vacuously", refusals().isNotEmpty())
        assertTrue("a fixture has no body", fixtures.all { it.body.isNotBlank() })
    }

    /**
     * The gate that matters: **not one** of the messages that merely mention money becomes a draft.
     * Why:    stated as a count rather than a percentage on purpose. A percentage invites a budget,
     *         and there is no acceptable number of invented transactions.
     * Input:  every `expect=none` fixture. Output: fails naming each message that got through, so
     *         the failure is actionable rather than a score.
     */
    @Test
    fun `no non-transactional message becomes a draft`() {
        val admitted = refusals().filter { parse(it) != null }

        assertEquals(
            "these messages were read as transactions the user never made: ${admitted.map { it.body.take(60) }}",
            0,
            admitted.size,
        )
    }

    /**
     * Input:  every `expect=debit|credit` fixture.
     * Output: fails below 95%, naming the messages whose amount was misread.
     */
    @Test
    fun `amount accuracy is at least 95 percent on real alerts`() {
        val misses = transactional().filter { parse(it)?.amount != Money(it.amountMinor) }
        val accuracy = percent(transactional().size - misses.size, transactional().size)

        assertTrue(
            "amount accuracy $accuracy% is below the $MIN_AMOUNT_ACCURACY% floor; " +
                "missed: ${misses.map { it.body.take(60) }}",
            accuracy >= MIN_AMOUNT_ACCURACY,
        )
    }

    /**
     * Input:  every `expect=debit|credit` fixture.
     * Output: fails below 95%. Scored separately from the amount because a reversed sign is a
     *         different bug from a misread figure — it turns a salary into a purchase, and it would
     *         be invisible in a combined score that the amounts alone could carry.
     */
    @Test
    fun `direction accuracy is at least 95 percent on real alerts`() {
        val misses = transactional().filter { parse(it)?.direction != it.expected }
        val accuracy = percent(transactional().size - misses.size, transactional().size)

        assertTrue(
            "direction accuracy $accuracy% is below the $MIN_DIRECTION_ACCURACY% floor; " +
                "missed: ${misses.map { it.body.take(60) }}",
            accuracy >= MIN_DIRECTION_ACCURACY,
        )
    }

    /**
     * The counterweight.
     * Why:    a parser could pass every gate above and still be useless by flagging every draft it
     *         produced — the user would have to check all of them, and the flag would stop meaning
     *         anything. This asserts that on the alerts it reads correctly, it usually says so.
     * Input:  every correctly-read transactional fixture. Output: fails when most are flagged.
     */
    @Test
    fun `a correctly read alert is usually not flagged for review`() {
        val correct = transactional().mapNotNull { parse(it) }.filter { it.amount != Money(0L) }
        val confident = correct.count { it.provenance.confidenceBps!! >= SmsRules().lowConfidenceBps }

        assertTrue(
            "only ${percent(confident, correct.size)}% of reads were confident — the review screen " +
                "would flag almost everything and the flag would stop meaning anything",
            percent(confident, correct.size) >= MIN_CONFIDENT_SHARE,
        )
    }

    // --- running and scoring ------------------------------------------------------------------

    /** Result: the fixtures labelled as real movements. Input: none. Output: `List<SmsFixture>`. */
    private fun transactional(): List<SmsFixture> = fixtures.filter { it.expected != null }

    /** Result: the fixtures that must be refused. Input: none. Output: `List<SmsFixture>`. */
    private fun refusals(): List<SmsFixture> = fixtures.filter { it.expected == null }

    /**
     * Runs the parser over one fixture.
     * Why:    every fixture shares one received instant and one received day, so the whole set is
     *         reproducible to the byte (P-08) — the parser reads no clock (TIM-001), and this is
     *         what proves the set would score the same in a year.
     * Result: the draft, or `null` when the parser refused the message.
     * Input:  [fixture]. Output: `SmsDraftFields?`; fails the test on an `Err`, which no
     *         well-formed fixture can produce.
     */
    private fun parse(fixture: SmsFixture): SmsDraftFields? {
        val outcome =
            engine.parse(
                SmsInput(
                    message = SmsMessage(1L, fixture.sender, fixture.body, RECEIVED_AT),
                    receivedOnIsoDate = RECEIVED_ON,
                    nowUtcMillis = NOW,
                ),
            )
        assertTrue("the parser errored on a well-formed fixture: ${fixture.body.take(60)}", outcome is Ok)
        return (outcome as Ok).value
    }

    /** Result: [hits] as a whole percent of [total], and 100 for an empty set. Output: [Int]. */
    private fun percent(
        hits: Int,
        total: Int,
    ): Int = if (total == 0) PCT_TOTAL else hits * PCT_TOTAL / total

    /**
     * Reads the frozen set off the classpath.
     * Why:    a resource rather than a path on disk, so the fixtures travel with the test jar and
     *         cannot be found only when the working directory happens to be right.
     * Result: every fixture. Input: none. Output: `List<SmsFixture>`; throws if the resource is
     *         missing, which is louder than scoring zero messages perfectly.
     */
    private fun loadFixtures(): List<SmsFixture> {
        val raw =
            checkNotNull(javaClass.getResourceAsStream(FIXTURE_PATH)) {
                "$FIXTURE_PATH is not on the test classpath"
            }.bufferedReader().readText()
        return raw.split(SEPARATOR)
            .map { it.lines().map(String::trim) }
            .mapNotNull { it.toFixtureOrNull() }
    }

    private companion object {
        const val FIXTURE_PATH = "/eval/sms.txt"
        const val SEPARATOR = "==="

        /** Fixed so the whole set is reproducible (P-08, TIM-001). */
        const val RECEIVED_ON = "2026-08-07"
        const val RECEIVED_AT = 1_786_082_400_000L
        const val NOW = 1_786_082_401_000L

        /** Ours, not the SRS's — see the class doc and ENGINE.md. */
        const val MIN_AMOUNT_ACCURACY = 95

        /** Ours, not the SRS's. Scored apart from the amount: a reversed sign is its own bug. */
        const val MIN_DIRECTION_ACCURACY = 95

        /** The counterweight described on the test that uses it. */
        const val MIN_CONFIDENT_SHARE = 80

        /** The set is documented as fifty messages; fewer means one was lost in an edit. */
        const val MIN_FIXTURES = 50
    }
}

/** A fixture header: `# key=value`, lower-case key, no spaces around the `=`. See [toFixtureOrNull]. */
private val HEADER = Regex("""#\s*([a-z_]+)=(.*)""")

/**
 * One labelled message from the frozen set (issue 3.9).
 *
 * Why:    a value rather than a map, so a fixture missing its label is a parse failure at load time
 *         instead of a silently skipped message that made the score look better.
 * Result: the element type of the evaluation set.
 * Changelog: 2026-08-07 — Created for issue 3.9.
 *
 * Input:  [sender] — the originating address as labelled; [expected] — the direction the message
 *         describes, or `null` for a message that is not a transaction at all; [amountMinor] — the
 *         labelled amount in paise (MNY-001), `0` when [expected] is `null`; [body] — the message.
 * Output: an immutable value.
 */

private data class SmsFixture(
    val sender: String,
    val expected: SmsDirection?,
    val amountMinor: Long,
    val body: String,
)

/**
 * Parses one `# key=value` header block plus its message into a fixture.
 * Why:    six lines instead of a hand-rolled JSON parser — see the fixture file's header for why the
 *         set is a text file at all. Comment lines starting `#` that are not `key=value` headers are
 *         dropped, which is what lets the file carry its own documentation.
 * Result: the fixture, or `null` for the file's header block and the blank tail after the last
 *         separator. A record carrying an `expect=` header but no body is an error rather than a
 *         skip — a truncated fixture must not quietly shrink the set the gates are measured over.
 * Input:  the receiver — one record's trimmed lines. Output: `SmsFixture?`.
 * Changelog: 2026-08-07 — Created for issue 3.9.
 */
private fun List<String>.toFixtureOrNull(): SmsFixture? {
    // Matched with a regex rather than "starts with # and contains =", which is what the first
    // draft did and which read the fixture file's own documentation as fixtures: the header block
    // explains the format in prose containing `expect = debit | credit | none` and `>= 95%`, and a
    // loose split turned both into records. A header line is `# key=value`, no spaces, lower-case
    // key — anything else in the file is a comment.
    val headers =
        mapNotNull { HEADER.matchEntire(it) }
            .associate { it.groupValues[1] to it.groupValues[2].trim() }
    val expect = headers["expect"] ?: return null
    val body = filterNot { it.startsWith("#") }.filter { it.isNotBlank() }.joinToString(" ")
    check(body.isNotBlank()) { "a fixture labelled expect=$expect has no message body" }
    return SmsFixture(
        sender = checkNotNull(headers["sender"]) { "a fixture has no sender" },
        expected =
            when (expect) {
                "debit" -> SmsDirection.DEBIT
                "credit" -> SmsDirection.CREDIT
                "none" -> null
                else -> error("unknown expect=$expect; use debit, credit or none")
            },
        amountMinor = headers["amount"].orEmpty().ifBlank { "0" }.toLong(),
        body = body,
    )
}
