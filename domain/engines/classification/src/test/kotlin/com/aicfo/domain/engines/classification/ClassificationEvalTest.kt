package com.aicfo.domain.engines.classification

import com.aicfo.core.common.Ok
import com.aicfo.core.model.Category
import com.aicfo.core.model.CategorySeed
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The accuracy gate for Stage-1 auto-categorisation (issue 4.2; SRS §8, §21.5).
 *
 * Why:  §21.5 says AI evaluation runs against frozen labelled datasets with regression thresholds
 *       that block merges, and unlike the SMS parser this one has a number in the SRS: **§8 sets
 *       categorisation at ≥ 92 %**. The two gates here are deliberately lopsided, for the reason
 *       [ClassificationEngine] states:
 *
 *       - the labelled half must be categorised at **≥ 92 %**, the SRS's own figure;
 *       - the refusal half must be refused at **100 %**, with no budget at all.
 *
 *       A merchant the app declines to classify costs the user one tap on a chip row that is
 *       already on screen. A merchant it classifies *wrongly* files money under a category they
 *       never chose, into a budget they will later read as fact — and nothing about the screen
 *       invites them to check. One is an inconvenience and the other is a quiet falsehood.
 * What: runs every fixture in `eval/categorisation.txt` and scores both halves separately.
 * Result: a rule edit that starts misfiling money, or stops classifying Indian merchants, fails CI.
 * Changelog: 2026-08-10 — Created for issue 4.2.
 *
 * **The labelled half deliberately does not score 100 %.** Two fixtures — `AMAZONPAY MERCHANT` and
 * `BYJUS THINK AND LEARN` — are real descriptors this engine misses, and they are left in with the
 * reason beside them. A set curated until it scores perfectly measures the curation.
 *
 * **A gate nobody has watched go red is not a gate.** Before this was trusted, a fixture was
 * deliberately mis-labelled and this test was confirmed to fail — the same check the drift tests get,
 * and the lesson `docs/report/2026-07-25-governance-standards-audit.md` recorded.
 */
class ClassificationEvalTest {
    private val engine = ClassificationEngineFactory.create()
    private val fixtures: List<EvalFixture> by lazy { loadFixtures() }

    /**
     * Input:  the fixture file.
     * Output: asserts it was found and holds both halves. Without this, every assertion below would
     *         score 0 out of 0 and pass vacuously if the resource ever went missing — which is
     *         precisely how a coverage gate in this repo once passed at 0 %.
     */
    @Test
    fun `the evaluation set is loaded and holds both halves`() {
        assertTrue("fewer fixtures than the set is documented to hold", fixtures.size >= MIN_FIXTURES)
        assertTrue("no labelled fixtures", labelled().isNotEmpty())
        assertTrue("no fixtures to refuse — the gate that matters would pass vacuously", refusals().isNotEmpty())
        assertTrue("a fixture has no merchant", fixtures.all { it.merchant.isNotBlank() })
    }

    /**
     * Input:  every fixture's expected category name.
     * Output: asserts each names a category the seeded taxonomy actually defines. A typo in a label
     *         would otherwise show up as an engine miss and be chased in the wrong file.
     */
    @Test
    fun `every label names a category the taxonomy defines`() {
        val defined = liveCategories.map { it.name }.toSet()
        labelled().forEach { fixture ->
            assertTrue(
                "'${fixture.merchant}' is labelled '${fixture.expected}', which is not a seeded category",
                fixture.expected in defined,
            )
        }
    }

    /**
     * The gate that matters: **not one** merchant the rules do not cover is given a category.
     * Why:    stated as a count rather than a percentage on purpose. A percentage invites a budget,
     *         and there is no acceptable number of transactions filed somewhere the user never chose.
     * Input:  every `expect=none` fixture.
     * Output: fails naming each merchant that was classified, so the failure is actionable rather
     *         than a score.
     */
    @Test
    fun `no unrecognised merchant is given a category`() {
        val misfiled = refusals().mapNotNull { fixture -> classify(fixture)?.let { fixture.merchant to it } }

        assertEquals(
            "these merchants were filed under a category nobody chose: ${misfiled.map { it.first }}",
            0,
            misfiled.size,
        )
    }

    /**
     * Input:  every labelled fixture.
     * Output: fails below the SRS's 92 %, naming the merchants that missed.
     */
    @Test
    fun `categorisation accuracy is at least 92 percent`() {
        val misses = labelled().filter { classify(it) != it.expected }
        val accuracy = percent(labelled().size - misses.size, labelled().size)

        assertTrue(
            "categorisation accuracy $accuracy% is below the $MIN_ACCURACY% floor (SRS §8); " +
                "missed: ${misses.map { it.merchant }}",
            accuracy >= MIN_ACCURACY,
        )
    }

    /**
     * The counterweight to the accuracy gate.
     * Why:    accuracy above counts a *miss* and a *wrong answer* the same way, and they are not the
     *         same thing at all. This asserts the stronger property separately: of the labelled
     *         merchants Stage 1 was willing to classify, **none was classified wrongly**. An engine
     *         that started guessing would keep its accuracy score and fail here.
     * Input:  every labelled fixture the engine did classify.
     * Output: fails naming each merchant given the wrong category.
     */
    @Test
    fun `a labelled merchant is never given the wrong category`() {
        val wrong =
            labelled().mapNotNull { fixture ->
                classify(fixture)?.takeIf { it != fixture.expected }?.let { fixture.merchant to it }
            }

        assertEquals(
            "these merchants were filed under the wrong category: $wrong",
            0,
            wrong.size,
        )
    }

    // --- running and scoring ------------------------------------------------------------------

    /** Result: the fixtures with an expected category. Input: none. Output: `List<EvalFixture>`. */
    private fun labelled(): List<EvalFixture> = fixtures.filter { it.expected != null }

    /** Result: the fixtures that must be refused. Input: none. Output: `List<EvalFixture>`. */
    private fun refusals(): List<EvalFixture> = fixtures.filter { it.expected == null }

    /**
     * Runs the engine over one fixture.
     * Why:    every fixture shares one instant and the seeded taxonomy, and no fixture carries a
     *         history — the knowledge-base tier is what this set measures, and a history would let a
     *         fixture pass without the rules doing anything. Tier (a) is covered by
     *         [ClassificationEngineTest], where its precedence can be asserted directly.
     * Result: the **name** of the suggested category, or `null` when Stage 1 deferred. Compared by
     *         name rather than by id so a failure message reads as `Dining`, not as a profile id.
     * Input:  [fixture]. Output: `String?`; fails the test on an `Err`.
     */
    private fun classify(fixture: EvalFixture): String? {
        val outcome =
            engine.suggest(
                ClassificationInput(
                    merchant = fixture.merchant,
                    categories = liveCategories,
                    nowUtcMillis = NOW,
                ),
            )
        assertTrue("the engine errored on a well-formed fixture: ${fixture.merchant}", outcome is Ok)
        val suggestion = (outcome as Ok).value ?: return null
        return liveCategories.first { it.id == suggestion.categoryId }.name
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
     * Result: every fixture. Input: none. Output: `List<EvalFixture>`; throws if the resource is
     *         missing, which is louder than classifying zero merchants perfectly.
     */
    private fun loadFixtures(): List<EvalFixture> {
        val raw =
            checkNotNull(javaClass.getResourceAsStream(FIXTURE_PATH)) {
                "$FIXTURE_PATH is not on the test classpath"
            }.bufferedReader().readText()
        return raw.split(SEPARATOR)
            .map { it.lines().map(String::trim) }
            .mapNotNull { it.toFixtureOrNull() }
    }

    private companion object {
        const val FIXTURE_PATH = "/eval/categorisation.txt"
        const val SEPARATOR = "==="

        /** Fixed so the whole set is reproducible (P-08, TIM-001). */
        const val NOW = 1_786_082_400_000L

        /** SRS §8: "categorisation ≥ 92 %". Ours to meet, not ours to choose. */
        const val MIN_ACCURACY = 92

        /** The set is documented as seventy-odd merchants; fewer means one was lost in an edit. */
        const val MIN_FIXTURES = 70

        /** The whole, as the percent a score is taken out of. */
        const val PCT_TOTAL = 100

        /**
         * The seeded taxonomy as a profile actually holds it.
         * Why:    built from [CategorySeed] rather than hand-listed, so a category renamed in the
         *         knowledge base cannot leave this set scoring against a name the app no longer seeds.
         */
        val liveCategories: List<Category> =
            CategorySeed.rows.map {
                Category(id = "profile-1:category:${it.key}", name = it.name, nature = it.nature, isSystem = true)
            }
    }
}

/** A fixture header: `# key=value`, lower-case key, no spaces around the `=`. See [toFixtureOrNull]. */
private val HEADER = Regex("""#\s*([a-z_]+)=(.*)""")

/**
 * One labelled merchant from the frozen set (issue 4.2).
 *
 * Why:    a value rather than a map, so a fixture missing its label is a parse failure at load time
 *         instead of a silently skipped merchant that made the score look better.
 * Result: the element type of the evaluation set.
 * Changelog: 2026-08-10 — Created for issue 4.2.
 *
 * Input:  [merchant] — the descriptor as a bank or a user would write it; [expected] — the display
 *         name of the category it should be filed under, or `null` for a merchant Stage 1 must
 *         refuse. Output: an immutable value.
 */
private data class EvalFixture(
    val merchant: String,
    val expected: String?,
)

/**
 * Parses one `# key=value` header block into a fixture.
 * Why:    six lines instead of a hand-rolled JSON parser — see the fixture file's header for why the
 *         set is a text file at all. Matched with a regex rather than "starts with # and contains
 *         =", the mistake `:domain:engines:sms`'s first draft made: the file's own prose contains
 *         `expect = <name>` and a loose split read the documentation as fixtures.
 * Result: the fixture, or `null` for the file's header block and the blank tail after the last
 *         separator. A record carrying an `expect=` header but no `merchant=` is an error rather
 *         than a skip — a truncated fixture must not quietly shrink the set the gates are measured
 *         over.
 * Input:  the receiver — one record's trimmed lines. Output: `EvalFixture?`.
 * Changelog: 2026-08-10 — Created for issue 4.2.
 */
private fun List<String>.toFixtureOrNull(): EvalFixture? {
    val headers =
        mapNotNull { HEADER.matchEntire(it) }
            .associate { it.groupValues[1] to it.groupValues[2].trim() }
    val expect = headers["expect"] ?: return null
    val merchant = checkNotNull(headers["merchant"]) { "a fixture labelled expect=$expect has no merchant" }
    check(merchant.isNotBlank()) { "a fixture labelled expect=$expect has a blank merchant" }
    return EvalFixture(merchant = merchant, expected = expect.takeIf { it != "none" })
}
