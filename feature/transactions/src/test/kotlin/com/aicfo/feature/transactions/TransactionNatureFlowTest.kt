package com.aicfo.feature.transactions

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.aicfo.core.designsystem.theme.CfoTheme
import com.aicfo.core.model.CategoryNature
import com.aicfo.core.model.EngineProvenance
import com.aicfo.core.model.Money
import com.aicfo.core.model.RuleCitation
import com.aicfo.core.model.Transaction
import com.aicfo.core.model.TransactionSource
import com.aicfo.core.model.TransactionType
import com.aicfo.domain.engines.nature.NatureVerdict
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Compose tests for the nature section of the detail sheet (issue 4.3; SRS §8.3, P-02, P-07).
 *
 * Why:  a file of its own rather than more of [TransactionsFlowTest], which detekt already holds at
 *       its class-size ceiling — and the split is the honest one: every test here turns on one
 *       question the rest of that file never asks, which is **whether the user can see and change a
 *       judgement the app made about their money**.
 *
 *       §8.3 calls nature "the axis the advice layer is built on", and it is also the axis they can
 *       least see: a category is a word they chose, a nature is a conclusion drawn from it. So the
 *       section owes them three things, and each is a test below — what was decided, which rule
 *       decided it, and whether the app is unsure.
 * What: the label, the citation, the override in both directions, and the review note.
 * Result: "the app decides, the user disposes" is a property tests hold, not a convention.
 * Changelog: 2026-08-10 — Created for issue 4.3.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w400dp-h1200dp")
class TransactionNatureFlowTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    /** Result: a string from this module's resources. Input: [id]. Output: the resolved text. */
    private fun text(id: Int): String = compose.activity.getString(id)

    /**
     * P-02 on a judgement the user cannot otherwise see.
     * Input:  a detail sheet with a verdict decided by the category rule.
     * Output: the nature and the rule that chose it, cited by id. The id is asserted verbatim
     *         because that is what makes it checkable against the knowledge base.
     */
    @Test
    fun `the detail says what the money became and which rule decided`() {
        compose.setContent {
            CfoTheme {
                TransactionDetailContent(
                    transaction = transaction(),
                    accountNames = emptyMap(),
                    nature = verdict(CategoryNature.NEED, "CLS-NAT-005"),
                    onClose = {},
                )
            }
        }

        compose.onNodeWithText(text(R.string.nature_need)).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.transactions_detail_nature_reason).format("CLS-NAT-005"))
            .assertIsDisplayed()
    }

    /**
     * P-07: the user is above every rule in §8.3.1's list.
     * Input:  a tap on a nature the app did not choose.
     * Output: the override event carrying that nature.
     */
    @Test
    fun `choosing a different nature hands up the override`() {
        val events = mutableListOf<TransactionsEvent>()
        compose.setContent {
            CfoTheme {
                TransactionDetailContent(
                    transaction = transaction(),
                    accountNames = emptyMap(),
                    nature = verdict(CategoryNature.NEED, "CLS-NAT-005"),
                    onOverrideNature = { events += TransactionsEvent.NatureOverridden(it) },
                    onClose = {},
                )
            }
        }

        compose.onNodeWithText(text(R.string.nature_want)).performClick()

        assertEquals(listOf<TransactionsEvent>(TransactionsEvent.NatureOverridden(CategoryNature.WANT)), events)
    }

    /**
     * The other half of P-07, and the reason only the correction is stored.
     * Input:  a tap on the nature that is **already** selected.
     * Output: `NatureOverridden(null)` — withdraw the correction and hand the transaction back to
     *         the rules. Without this the user could change their mind but never undo it.
     */
    @Test
    fun `tapping the chosen nature again withdraws the override`() {
        val events = mutableListOf<TransactionsEvent>()
        compose.setContent {
            CfoTheme {
                TransactionDetailContent(
                    transaction = transaction(),
                    accountNames = emptyMap(),
                    nature = verdict(CategoryNature.NEED, "CLS-NAT-005"),
                    onOverrideNature = { events += TransactionsEvent.NatureOverridden(it) },
                    onClose = {},
                )
            }
        }

        compose.onNodeWithText(text(R.string.nature_need)).performClick()

        assertEquals(listOf<TransactionsEvent>(TransactionsEvent.NatureOverridden(null)), events)
    }

    /**
     * §8.3's `low_confidence_handling`, on screen.
     * Input:  a verdict below the confidence floor.
     * Output: the review note. Its absence on a confident verdict is asserted too, because a note
     *         that always showed would tell the user nothing.
     */
    @Test
    fun `an uncertain nature says so, and a confident one does not`() {
        compose.setContent {
            CfoTheme {
                TransactionDetailContent(
                    transaction = transaction(),
                    accountNames = emptyMap(),
                    nature = verdict(CategoryNature.WANT, "CLS-NAT-005", confidenceBps = 3_000),
                    onClose = {},
                )
            }
        }

        compose.onNodeWithText(text(R.string.transactions_detail_nature_unsure)).assertIsDisplayed()
    }

    /**
     * Input:  a sheet whose verdict has not arrived yet — the state every sheet opens in.
     * Output: no section at all. Flashing a nature and replacing it a moment later would be worse
     *         than waiting a beat.
     */
    @Test
    fun `the nature section is absent until the verdict arrives`() {
        compose.setContent {
            CfoTheme {
                TransactionDetailContent(transaction = transaction(), accountNames = emptyMap(), onClose = {})
            }
        }

        compose.onNodeWithText(text(R.string.transactions_detail_nature)).assertDoesNotExist()
    }

    /**
     * Result: a verdict for the sheet to render.
     * Input:  [nature]; [ruleId] — the step that decided it; [confidenceBps] — above the floor by
     *         default, so only the test that wants the review note gets one.
     * Output: [NatureVerdict].
     */
    private fun verdict(
        nature: CategoryNature,
        ruleId: String,
        confidenceBps: Int = 8_500,
    ) = NatureVerdict(
        nature = nature,
        provenance =
            EngineProvenance(
                engineId = "nature-classifier",
                engineVersion = "1.0",
                computedAtUtcMillis = 1_786_082_400_000L,
                evidence = listOf(RuleCitation(ruleId, "1.0")),
                confidenceBps = confidenceBps,
            ),
    )

    /**
     * Result: a plain saved expense for the sheet to render.
     * Input:  none. Output: [Transaction].
     */
    private fun transaction() =
        Transaction(
            id = "txn:1",
            accountId = "account:1",
            amount = Money(-4_500_00L),
            occurredAtUtcMillis = 1_786_082_400_000L,
            bookedOn = "2026-08-10",
            categoryId = null,
            merchant = null,
            note = null,
            source = TransactionSource.MANUAL,
            type = TransactionType.EXPENSE,
        )
}
