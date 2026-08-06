package com.aicfo.feature.transactions

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.aicfo.core.designsystem.theme.CfoTheme
import com.aicfo.core.model.Money
import com.aicfo.core.model.Transaction
import com.aicfo.core.model.TransactionSource
import com.aicfo.core.model.TransactionType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Compose tests for the receipt review screen — FR-OCR-001, FR-OCR-004, FR-OCR-006 (issue 3.8).
 *
 * Why:  three of this screen's requirements are about what the user can *see and reach*, which a
 *       ViewModel test cannot check: **both capture paths must be offered** (FR-OCR-001), **Save
 *       must be unreachable without an amount and a date** (FR-OCR-004), and **a flagged field must
 *       announce itself to a screen reader**, not only turn a colour — the accessibility line in
 *       §21.6 and the Definition of Done's scan.
 * What: the capture step's two buttons, the disabled Save, the flag's content description, and the
 *       merge branch.
 * Result: FR-OCR-004's gate is asserted where the user meets it.
 * Changelog: 2026-08-06 — Created for issue 3.8.
 *
 * On the JVM via Robolectric, following `AddTransactionFlowTest`: the flow is checked on every
 * `test` run rather than only when an emulator happens to be up.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w400dp-h1200dp")
class ReceiptReviewFlowTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    /** Result: a string from this module's resources. Input: [id]. Output: the resolved text. */
    private fun text(id: Int): String = compose.activity.getString(id)

    // --- capture (FR-OCR-001) ---------------------------------------------------------------------

    @Test
    fun `both capture paths are offered, because the requirement says both`() {
        var photos = 0
        var picks = 0
        setContent(ReceiptReviewUiState(), onTakePhoto = { photos++ }, onChoosePhoto = { picks++ })

        compose.onNodeWithText(text(R.string.receipt_take_photo)).performClick()
        compose.onNodeWithText(text(R.string.receipt_choose_photo)).performClick()

        assertEquals(1, photos)
        assertEquals(1, picks)
    }

    @Test
    fun `the on-device promise is shown before the photo is taken, not after`() {
        setContent(ReceiptReviewUiState())

        // FR-OCR-002 matters to the user at the moment they decide to point a camera at their
        // shopping — afterwards the app already has it.
        compose.onNodeWithText(text(R.string.receipt_on_device)).assertIsDisplayed()
    }

    // --- the save gate (FR-OCR-004) ---------------------------------------------------------------

    @Test
    fun `Save is disabled until there is an amount and a date`() {
        setContent(reviewState(amount = "", date = "2026-08-04"))

        compose.onNodeWithText(text(R.string.receipt_save)).assertIsNotEnabled()
    }

    @Test
    fun `Save is enabled once both are present`() {
        setContent(reviewState(amount = "365.80", date = "2026-08-04"))

        compose.onNodeWithText(text(R.string.receipt_save)).assertIsEnabled()
    }

    // --- the confidence flag (FR-OCR-004) ---------------------------------------------------------

    @Test
    fun `a flagged field says so in words, not only in colour`() {
        setContent(reviewState(amount = "365.80", date = "2026-08-04").copy(amountFlagged = true))

        // The supporting text a sighted user sees...
        compose.onAllNodesWithText(text(R.string.receipt_low_confidence)).onFirst().assertIsDisplayed()
        // ...and what a screen reader announces, which colour alone would not provide.
        compose
            .onNodeWithContentDescription(
                "${text(R.string.receipt_amount)}. ${text(R.string.receipt_low_confidence)}",
            )
            .assertIsDisplayed()
    }

    // --- the merge offer (FR-OCR-006) -------------------------------------------------------------

    @Test
    fun `a possible duplicate replaces Save with a choice`() {
        val events = mutableListOf<ReceiptReviewEvent>()
        setContent(
            reviewState(amount = "365.80", date = "2026-08-04").copy(duplicates = listOf(existing())),
            onEvent = { events += it },
        )

        compose.onNodeWithText(text(R.string.receipt_duplicate_merge)).performClick()

        assertTrue(
            "FR-OCR-006: the offer must attach to the existing row rather than save a second one",
            events.filterIsInstance<ReceiptReviewEvent.MergeInto>().single().transactionId == "txn:existing",
        )
    }

    @Test
    fun `save anyway is offered too, because the guard is a heuristic`() {
        val events = mutableListOf<ReceiptReviewEvent>()
        setContent(
            reviewState(amount = "365.80", date = "2026-08-04").copy(duplicates = listOf(existing())),
            onEvent = { events += it },
        )

        compose.onNodeWithText(text(R.string.receipt_duplicate_save_anyway)).performClick()

        assertTrue(events.contains(ReceiptReviewEvent.SaveAnyway))
    }

    // --- fixtures ---------------------------------------------------------------------------------

    /** Result: the screen rendered over [state]. Input: the state and the three callbacks. */
    private fun setContent(
        state: ReceiptReviewUiState,
        onEvent: (ReceiptReviewEvent) -> Unit = {},
        onTakePhoto: () -> Unit = {},
        onChoosePhoto: () -> Unit = {},
    ) {
        compose.setContent {
            CfoTheme {
                ReceiptReviewContent(
                    uiState = state,
                    onEvent = onEvent,
                    onTakePhoto = onTakePhoto,
                    onChoosePhoto = onChoosePhoto,
                    onCancel = {},
                )
            }
        }
    }

    /** Result: a state on the review step. Input: [amount]; [date]. Output: the state. */
    private fun reviewState(
        amount: String,
        date: String,
    ) = ReceiptReviewUiState(
        stage = ReceiptStage.REVIEW,
        accounts = listOf(account()),
        selectedAccountId = "account:1",
        amountText = amount,
        dateText = date,
    )

    /** Result: the row FR-OCR-006 would offer as a merge. Output: [Transaction]. */
    private fun existing(): Transaction =
        Transaction(
            id = "txn:existing",
            accountId = "account:1",
            amount = Money(-36_580L),
            occurredAtUtcMillis = 0L,
            bookedOn = "2026-08-04",
            categoryId = null,
            merchant = "Big Bazaar",
            note = null,
            source = TransactionSource.MANUAL,
            type = TransactionType.EXPENSE,
        )
}
