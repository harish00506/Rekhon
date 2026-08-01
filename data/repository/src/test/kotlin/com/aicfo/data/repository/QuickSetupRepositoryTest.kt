package com.aicfo.data.repository

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.cash.turbine.test
import com.aicfo.core.common.FakeClock
import com.aicfo.core.common.Ok
import com.aicfo.core.common.TestDispatchers
import com.aicfo.core.database.CfoDatabase
import com.aicfo.core.model.Money
import com.aicfo.domain.engines.quicksetup.BudgetNature
import com.aicfo.domain.engines.quicksetup.QuickSetupEngineFactory
import com.aicfo.domain.engines.quicksetup.QuickSetupInput
import com.aicfo.domain.engines.quicksetup.QuickSetupPlan
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Tests for [QuickSetupRepository] — the first code that writes the user's financial data (2.3).
 *
 * Why:  three properties decide whether writing at the end of onboarding is safe, and none of them
 *       can be read off the class. **Idempotence**: ids are derived, so a second run must update
 *       three envelopes rather than add three more — the failure would look fine on the screen and
 *       double every budget total. **Atomicity**: a failure part-way must leave nothing, because a
 *       budget scoped to a profile that was never written is invisible to every query. And
 *       **fabricating nothing**: a skipped step must not leave a profile row behind, or the app
 *       looks set up for a user who declined to set it up.
 * What: the write and its shape in real SQL, the re-run, the empty plan, the read-back, and the
 *       soft-delete filter.
 * Result: the persistence half of FR-ONB-002 is proven against a real database engine.
 * Changelog: 2026-07-27 — Created for issue 2.3.
 *
 * **Unencrypted in-memory Room, deliberately** — the same reasoning as `AuditLogRepositoryTest`:
 * SQLCipher needs a device, and what is under test here is the SQL and the mapping, not the
 * encryption. `EncryptedDatabaseTest` (androidTest, issue 1.6) is what proves the file is
 * ciphertext.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class QuickSetupRepositoryTest {
    private lateinit var database: CfoDatabase
    private lateinit var repository: QuickSetupRepository
    private val clock = FakeClock()

    /** Input: none. Output: a fresh in-memory database and a repository over it. */
    @Before
    fun setUp() {
        database =
            Room.inMemoryDatabaseBuilder(
                ApplicationProvider.getApplicationContext(),
                CfoDatabase::class.java,
            )
                // For the tests that reach past the repository to read raw SQL and check what
                // actually landed. The repository still goes through its injected dispatcher.
                .allowMainThreadQueries()
                .build()
        repository =
            RepositoryFactory.quickSetup(
                database = database,
                clock = clock,
                dispatchers = TestDispatchers(UnconfinedTestDispatcher()),
                // Fixed at the real profile: these tests are about the write and the SQL, not about
                // demo mode. `DemoModeRepositoryTest` is what proves the switch (issue 2.4).
                activeProfileId = flowOf(QuickSetupRepository.DEFAULT_PROFILE_ID),
            )
    }

    /** Input: none. Output: closes the database between tests. */
    @After
    fun tearDown() {
        database.close()
    }

    // --- the write -----------------------------------------------------------------------------

    /**
     * Input:  a full plan for the local profile.
     * Output: asserts the profile, three envelopes and three recurring rules all landed.
     */
    @Test
    fun `applySeeds writes the profile, the envelopes and the recurring rules`() =
        runTest {
            assertTrue(repository.applySeeds(fullPlan(), profileSeed()) is Ok)

            assertEquals(1, count("profile"))
            assertEquals(3, count("budget"))
            assertEquals(3, count("recurring_rule"))
        }

    /**
     * Input:  the same write, read back through raw SQL.
     * Output: asserts the exact paise, the nature code and the rule citation on the needs envelope.
     *         Checked against the column rather than the entity, because MNY-001 is about what is
     *         *stored*: a column that had ended up `REAL` would still satisfy the Kotlin type.
     */
    @Test
    fun `a budget row stores exact paise, its nature and the rule that produced it`() =
        runTest {
            repository.applySeeds(fullPlan(), profileSeed())

            database.query(
                "SELECT amount_minor, nature, category_id, source, rule_id, rule_version " +
                    "FROM budget WHERE nature = 'need'",
                emptyArray(),
            ).use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals(42_500_00L, cursor.getLong(0))
                assertEquals("need", cursor.getString(1))
                assertTrue("no category exists at onboarding", cursor.isNull(2))
                assertEquals("quick_setup", cursor.getString(3))
                assertEquals("RULE-50-30-20", cursor.getString(4))
                assertEquals("1.0", cursor.getString(5))
            }
        }

    /**
     * Input:  the same write, reading the rent rule.
     * Output: asserts an outflow is stored negative and unconfirmed, and that the row holds a
     *         **code** rather than a label — §21.6 would make a stored "Rent or EMI" untranslatable,
     *         and FR-TXN-006 says a rule creates nothing until the user confirms it.
     */
    @Test
    fun `a recurring row stores a signed amount, a kind code and no label`() =
        runTest {
            repository.applySeeds(fullPlan(), profileSeed())

            database.query(
                "SELECT amount_minor, seed_kind, name, cadence, next_due_iso_date, is_confirmed, account_id " +
                    "FROM recurring_rule WHERE seed_kind = 'rent_emi'",
                emptyArray(),
            ).use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals(-24_000_00L, cursor.getLong(0))
                assertEquals("rent_emi", cursor.getString(1))
                assertTrue("a stored English label could never be translated", cursor.isNull(2))
                assertEquals("monthly", cursor.getString(3))
                assertEquals("2026-08-01", cursor.getString(4))
                assertEquals("unconfirmed until the user says so (FR-TXN-006)", 0, cursor.getInt(5))
                assertTrue("no account exists until issue 2.5", cursor.isNull(6))
            }
        }

    // --- idempotence ---------------------------------------------------------------------------

    /**
     * Input:  the same plan applied twice.
     * Output: asserts the row counts do not grow. This is what "deterministically" in the
     *         acceptance criteria has to mean once the values reach storage — a random id per row
     *         would pass every other test in this file and quietly double the user's budget.
     */
    @Test
    fun `applying the same plan twice updates the same rows rather than adding more`() =
        runTest {
            repository.applySeeds(fullPlan(), profileSeed())
            repository.applySeeds(fullPlan(), profileSeed())

            assertEquals(1, count("profile"))
            assertEquals(3, count("budget"))
            assertEquals(3, count("recurring_rule"))
        }

    /**
     * Input:  a plan applied, then a plan derived from larger seeds.
     * Output: asserts the envelope was **updated**, not duplicated — the second answer wins and
     *         there is exactly one needs envelope for the month afterwards.
     */
    @Test
    fun `re-running with different seeds replaces the earlier envelope`() =
        runTest {
            repository.applySeeds(fullPlan(), profileSeed())
            repository.applySeeds(planFor(income = 1_00_000_00L, rent = 24_000_00L), profileSeed())

            assertEquals(3, count("budget"))
            assertEquals(50_000_00L, envelopeMinor(BudgetNature.NEED))
        }

    /**
     * Input:  a profile written, the clock moved, then a second run.
     * Output: asserts `created_at` is preserved while `updated_at` moves. A REPLACE that restated
     *         the creation time would make "since when has this profile existed?" unanswerable —
     *         the same question `ConsentRecordProto` keeps its dates to answer.
     */
    @Test
    fun `a re-run preserves when the profile was first created`() =
        runTest {
            repository.applySeeds(fullPlan(), profileSeed())
            val createdAt = profileColumn("created_at_utc_millis")
            clock.advanceBy(java.time.Duration.ofDays(30))

            repository.applySeeds(fullPlan(), profileSeed())

            assertEquals(createdAt, profileColumn("created_at_utc_millis"))
            assertEquals(clock.nowUtcMillis(), profileColumn("updated_at_utc_millis"))
        }

    // --- fabricating nothing (P-03) --------------------------------------------------------------

    /**
     * Input:  the plan produced when the user answered nothing.
     * Output: asserts **no row at all** — not even the profile. Writing a profile here would leave
     *         the app looking set up for someone who declined to set it up, and would then be the
     *         row every later feature scopes to.
     */
    @Test
    fun `an empty plan writes nothing at all, not even a profile`() =
        runTest {
            val outcome = repository.applySeeds(emptyPlan(), profileSeed())

            assertTrue("an empty plan is not an error", outcome is Ok)
            assertEquals(0, count("profile"))
            assertEquals(0, count("budget"))
            assertEquals(0, count("recurring_rule"))
        }

    /**
     * Input:  a plan built from income alone.
     * Output: asserts only the income rule is written — the two unanswered figures produce no row,
     *         rather than rows of zero (P-03).
     */
    @Test
    fun `an unanswered figure produces no row rather than a zero one`() =
        runTest {
            repository.applySeeds(planFor(income = 85_000_00L, rent = null), profileSeed())

            assertEquals(1, count("recurring_rule"))
            assertEquals(3, count("budget"))
        }

    // --- reading back ----------------------------------------------------------------------------

    /**
     * Input:  a written plan.
     * Output: asserts the envelopes come back as domain values with their amounts and citations
     *         intact, and that no Room type escapes (ARC-005) — the return type is the engine's.
     *
     * The **order** is asserted because it is the one thing SQL gets wrong here: `ORDER BY nature`
     * is alphabetical (invest, need, want), which reads as an arbitrary shuffle. Display order is
     * needs → wants → savings, and the repository restores it from the enum.
     */
    @Test
    fun `observeLatestEnvelopes returns what was written, in display order`() =
        runTest {
            repository.applySeeds(fullPlan(), profileSeed())

            repository.observeLatestEnvelopes().test {
                val envelopes = awaitItem()
                assertEquals(
                    listOf(BudgetNature.NEED, BudgetNature.WANT, BudgetNature.INVEST),
                    envelopes.map { it.nature },
                )
                assertEquals(Money(42_500_00L), envelopes.first { it.nature == BudgetNature.NEED }.amount)
                assertEquals("RULE-50-30-20", envelopes.first().citation.ruleId)
                cancelAndIgnoreRemainingEvents()
            }
        }

    /**
     * Input:  nothing written.
     * Output: asserts an empty list. The dashboard must be able to tell "skipped" from "zero", so
     *         this may never emit three zero envelopes.
     */
    @Test
    fun `observeLatestEnvelopes is empty when the step was skipped`() =
        runTest {
            assertTrue(repository.observeLatestEnvelopes().first().isEmpty())
        }

    /**
     * Input:  two periods written, the later one after the clock moves.
     * Output: asserts only the newest period's envelopes are returned. Without the period filter
     *         the dashboard would show six envelopes and a doubled total.
     */
    @Test
    fun `observeLatestEnvelopes returns only the most recent period`() =
        runTest {
            repository.applySeeds(planFor(income = 85_000_00L, period = "2026-06-01"), profileSeed())
            repository.applySeeds(planFor(income = 1_00_000_00L, period = "2026-07-01"), profileSeed())

            val envelopes = repository.observeLatestEnvelopes().first()

            assertEquals(3, envelopes.size)
            assertEquals(Money(50_000_00L), envelopes.first { it.nature == BudgetNature.NEED }.amount)
        }

    /**
     * Input:  a soft-deleted envelope.
     * Output: asserts it disappears from the read. The rule every query in this app follows, and
     *         the one whose omission makes deleted rows reappear in a total.
     */
    @Test
    fun `a soft-deleted envelope is invisible to reads`() =
        runTest {
            repository.applySeeds(fullPlan(), profileSeed())
            database.budgetDao().softDelete(
                budgetId(QuickSetupRepository.DEFAULT_PROFILE_ID, BudgetNature.NEED, "2026-07-01"),
                clock.nowUtcMillis(),
            )

            val envelopes = repository.observeLatestEnvelopes().first()

            assertEquals(2, envelopes.size)
            assertNull(envelopes.firstOrNull { it.nature == BudgetNature.NEED })
        }

    /**
     * Input:  a plan written for one profile, read for another.
     * Output: asserts nothing crosses. No query in this app may span profiles, and household mode
     *         (issue 13.1) is where that stops being theoretical.
     */
    @Test
    fun `envelopes are scoped to their profile`() =
        runTest {
            repository.applySeeds(fullPlan(), profileSeed())

            assertTrue(repository.observeLatestEnvelopes(profileId = "someone-else").first().isEmpty())
        }

    // --- helpers -----------------------------------------------------------------------------------

    private fun profileSeed() = ProfileSeed(displayName = "Arjun", timeZoneId = "Asia/Kolkata", currencyCode = "INR")

    private fun fullPlan() = planFor(income = 85_000_00L, rent = 24_000_00L, savings = 15_000_00L)

    private fun emptyPlan() = planFor()

    /**
     * Builds a plan through the real engine.
     * Why:    hand-constructing a `QuickSetupPlan` here would let this suite pass against amounts
     *         the engine never produces, which is how a persistence test drifts from the thing it
     *         persists. The engine is already proven; this reuses it.
     * Result: the plan for these seeds. Input: the seeds and the period. Output: [QuickSetupPlan].
     */
    private fun planFor(
        income: Long? = null,
        rent: Long? = null,
        savings: Long? = null,
        period: String = "2026-07-01",
    ): QuickSetupPlan {
        val result =
            QuickSetupEngineFactory.create().plan(
                QuickSetupInput(
                    monthlyIncome = income?.let(::Money),
                    rentOrEmi = rent?.let(::Money),
                    typicalSavings = savings?.let(::Money),
                    periodStartIsoDate = period,
                    nowUtcMillis = clock.nowUtcMillis(),
                ),
            )
        return (result as Ok).value
    }

    private fun count(table: String): Int =
        database.query("SELECT COUNT(*) FROM $table", emptyArray()).use { cursor ->
            cursor.moveToFirst()
            cursor.getInt(0)
        }

    private fun profileColumn(column: String): Long =
        database.query("SELECT $column FROM profile", emptyArray()).use { cursor ->
            cursor.moveToFirst()
            cursor.getLong(0)
        }

    private fun envelopeMinor(nature: BudgetNature): Long =
        database.query(
            "SELECT amount_minor FROM budget WHERE nature = ?",
            arrayOf(nature.storedValue),
        ).use { cursor ->
            cursor.moveToFirst()
            cursor.getLong(0)
        }
}
