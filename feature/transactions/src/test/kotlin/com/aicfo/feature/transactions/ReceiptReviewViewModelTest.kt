package com.aicfo.feature.transactions

import app.cash.turbine.test
import com.aicfo.core.common.AppError
import com.aicfo.core.model.EngineProvenance
import com.aicfo.core.model.Money
import com.aicfo.core.model.Transaction
import com.aicfo.core.model.TransactionSource
import com.aicfo.core.model.TransactionType
import com.aicfo.data.repository.ReceiptScan
import com.aicfo.domain.engines.receipt.ExtractedMoney
import com.aicfo.domain.engines.receipt.ExtractedText
import com.aicfo.domain.engines.receipt.ReceiptFields
import com.aicfo.domain.engines.receipt.ReceiptRules
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Tests for [ReceiptReviewViewModel] — FR-OCR-003, FR-OCR-004 and FR-OCR-006 (issue 3.8).
 *
 * Why:  three requirements are enforced in this class rather than in a layout, and each one has a
 *       failure that would look like a working screen: **saving with no amount** would write a
 *       zero-rupee transaction, **a flag that never fires** would present a guess as a reading, and
 *       **a merge offer that saves instead** would silently record one purchase twice. None of the
 *       three is visible in a screenshot, so they are asserted here.
 * What: the pre-fill, the flags, the save gate, both duplicate branches, and what is actually handed
 *       to the repository.
 * Result: the review screen's rules are provable without a camera.
 * Changelog: 2026-08-06 — Created for issue 3.8.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ReceiptReviewViewModelTest {
    private val receipts = FakeReceiptRepository()
    private val accounts = FakeAccountRepository()

    /** Input: none. Output: `viewModelScope` runs on a test dispatcher. */
    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    /** Input: none. Output: the main dispatcher is restored. */
    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    // --- what the parser proposes (FR-OCR-003) ---------------------------------------------------

    @Test
    fun `a scan pre-fills every field it managed to read`() =
        runTest {
            receipts.nextScan = scanOf(total = Money(36_580L), date = "2026-08-04", merchant = "BIG BAZAAR")
            val viewModel = viewModel()

            viewModel.onEvent(ReceiptReviewEvent.ImagePicked(SOME_BYTES))

            val state = viewModel.uiState.value
            assertEquals(ReceiptStage.REVIEW, state.stage)
            assertEquals("365.80", state.amountText)
            assertEquals("2026-08-04", state.dateText)
            assertEquals("BIG BAZAAR", state.merchantText)
        }

    @Test
    fun `a receipt whose date smudged still hands over its total`() =
        runTest {
            // §18: "all failures fall back to manual entry pre-filled with whatever was extracted".
            receipts.nextScan = scanOf(total = Money(36_580L), date = null, merchant = null)
            val viewModel = viewModel()

            viewModel.onEvent(ReceiptReviewEvent.ImagePicked(SOME_BYTES))

            assertEquals("365.80", viewModel.uiState.value.amountText)
            assertEquals("", viewModel.uiState.value.dateText)
        }

    @Test
    fun `a photo that would not decode leaves the user on the capture step`() =
        runTest {
            receipts.failWith = AppError.Validation("image")
            val viewModel = viewModel()

            viewModel.onEvent(ReceiptReviewEvent.ImagePicked(SOME_BYTES))

            assertEquals(ReceiptStage.CAPTURE, viewModel.uiState.value.stage)
            assertEquals("validation", viewModel.uiState.value.errorCode)
        }

    // --- the confidence flags (FR-OCR-004) -------------------------------------------------------

    @Test
    fun `a field below the rulebook floor is flagged`() =
        runTest {
            val floor = ReceiptRules().lowConfidenceBps
            receipts.nextScan =
                scanOf(
                    total = Money(36_580L),
                    date = "2026-08-04",
                    merchant = "BIG BAZAAR",
                    totalConfidence = floor - 1,
                )
            val viewModel = viewModel()

            viewModel.onEvent(ReceiptReviewEvent.ImagePicked(SOME_BYTES))

            assertTrue("FR-OCR-004: a low-confidence field must be flagged", viewModel.uiState.value.amountFlagged)
            assertFalse("a confident field must not be", viewModel.uiState.value.dateFlagged)
        }

    @Test
    fun `a field the parser could not read at all is blank rather than flagged`() =
        runTest {
            // Nothing was read, so there is nothing for the user to *check* — an empty field already
            // asks, where a warning on it would be the app apologising for a reading it never made.
            receipts.nextScan = scanOf(total = null, date = null, merchant = null)
            val viewModel = viewModel()

            viewModel.onEvent(ReceiptReviewEvent.ImagePicked(SOME_BYTES))

            assertFalse(viewModel.uiState.value.amountFlagged)
            assertEquals("", viewModel.uiState.value.amountText)
        }

    // --- the save gate (FR-OCR-004) --------------------------------------------------------------

    @Test
    fun `save is refused without an amount`() =
        runTest {
            receipts.nextScan = scanOf(total = null, date = "2026-08-04", merchant = null)
            val viewModel = viewModel()
            viewModel.onEvent(ReceiptReviewEvent.ImagePicked(SOME_BYTES))

            assertFalse("FR-OCR-004", viewModel.uiState.value.canSave)
            viewModel.onEvent(ReceiptReviewEvent.Save)

            assertTrue("a refused save must write nothing", receipts.saved.isEmpty())
        }

    @Test
    fun `save is refused without a date`() =
        runTest {
            receipts.nextScan = scanOf(total = Money(36_580L), date = null, merchant = null)
            val viewModel = viewModel()
            viewModel.onEvent(ReceiptReviewEvent.ImagePicked(SOME_BYTES))

            assertFalse("FR-OCR-004", viewModel.uiState.value.canSave)
            viewModel.onEvent(ReceiptReviewEvent.Save)

            assertTrue(receipts.saved.isEmpty())
        }

    @Test
    fun `an amount that is not an exact paise figure is refused`() =
        runTest {
            val viewModel = reviewing()

            viewModel.onEvent(ReceiptReviewEvent.AmountChanged("365.807"))

            assertFalse(
                "MNY-001: a parser that guesses is worse than one that declines",
                viewModel.uiState.value.canSave,
            )
        }

    // --- what is written (FR-OCR-005, FR-TXN-009) ------------------------------------------------

    @Test
    fun `the correction the user typed is what gets saved, not what the parser read`() =
        runTest {
            val viewModel = reviewing()

            viewModel.onEvent(ReceiptReviewEvent.AmountChanged("400.00"))
            viewModel.onEvent(ReceiptReviewEvent.MerchantChanged("Big Bazaar Koramangala"))
            viewModel.onEvent(ReceiptReviewEvent.Save)

            val (draft, bytes) = receipts.saved.single()
            assertEquals("a receipt is money spent, so the sign is negative (MNY-001)", Money(-40_000L), draft.amount)
            assertEquals("Big Bazaar Koramangala", draft.merchant)
            assertEquals("2026-08-04", draft.bookedOn?.toString())
            assertTrue("FR-OCR-005: the image must be handed over with the draft", bytes != null)
        }

    @Test
    fun `a second tap on Save does not book the spend twice`() =
        runTest {
            val viewModel = reviewing()

            viewModel.onEvent(ReceiptReviewEvent.Save)
            viewModel.onEvent(ReceiptReviewEvent.Save)

            assertEquals(1, receipts.saved.size)
        }

    @Test
    fun `a failed save keeps the user on the screen with a code, never a message`() =
        runTest {
            val viewModel = reviewing()
            receipts.failWith = AppError.Storage("disk")

            viewModel.onEvent(ReceiptReviewEvent.Save)

            viewModel.uiState.test {
                val state = awaitItem()
                assertEquals("storage", state.errorCode)
                assertFalse(state.isSaved)
                cancelAndIgnoreRemainingEvents()
            }
        }

    // --- the duplicate guard (FR-OCR-006) --------------------------------------------------------

    @Test
    fun `a possible duplicate is offered rather than saved over`() =
        runTest {
            receipts.nextScan =
                scanOf(total = Money(36_580L), date = "2026-08-04", merchant = "BIG BAZAAR")
                    .copy(duplicates = listOf(existingTransaction()))
            val viewModel = viewModel()

            viewModel.onEvent(ReceiptReviewEvent.ImagePicked(SOME_BYTES))

            assertTrue("FR-OCR-006", viewModel.uiState.value.hasDuplicates)
        }

    @Test
    fun `merging attaches to the existing row and writes no transaction`() =
        runTest {
            receipts.nextScan =
                scanOf(total = Money(36_580L), date = "2026-08-04", merchant = "BIG BAZAAR")
                    .copy(duplicates = listOf(existingTransaction()))
            val viewModel = viewModel()
            viewModel.onEvent(ReceiptReviewEvent.ImagePicked(SOME_BYTES))

            viewModel.onEvent(ReceiptReviewEvent.MergeInto("txn:existing"))

            assertEquals(listOf("txn:existing"), receipts.merged)
            assertTrue("a merge must not also create a transaction", receipts.saved.isEmpty())
            assertTrue(viewModel.uiState.value.isSaved)
        }

    @Test
    fun `save anyway exists, because two identical coffees on one day are real`() =
        runTest {
            receipts.nextScan =
                scanOf(total = Money(36_580L), date = "2026-08-04", merchant = "BIG BAZAAR")
                    .copy(duplicates = listOf(existingTransaction()))
            val viewModel = viewModel()
            viewModel.onEvent(ReceiptReviewEvent.ImagePicked(SOME_BYTES))

            viewModel.onEvent(ReceiptReviewEvent.SaveAnyway)

            assertEquals(1, receipts.saved.size)
            assertTrue(receipts.merged.isEmpty())
        }

    // --- starting again --------------------------------------------------------------------------

    @Test
    fun `a different photo clears the previous receipt's figures`() =
        runTest {
            val viewModel = reviewing()

            viewModel.onEvent(ReceiptReviewEvent.Rescan)

            val state = viewModel.uiState.value
            assertEquals(ReceiptStage.CAPTURE, state.stage)
            assertEquals("last week's total under today's photo is the bug this prevents", "", state.amountText)
            assertTrue("the account choice is not part of the receipt", state.selectedAccountId != null)
        }

    // --- fixtures ---------------------------------------------------------------------------------

    /** Result: a ViewModel over the fakes, with one account already available. Output: the VM. */
    private fun viewModel(): ReceiptReviewViewModel {
        accounts.setAccounts(account())
        return ReceiptReviewViewModel(receipts, accounts)
    }

    /** Result: a ViewModel already showing a clean, saveable reading. Output: the VM. */
    private fun reviewing(): ReceiptReviewViewModel {
        receipts.nextScan = scanOf(total = Money(36_580L), date = "2026-08-04", merchant = "BIG BAZAAR")
        return viewModel().also { it.onEvent(ReceiptReviewEvent.ImagePicked(SOME_BYTES)) }
    }

    /** Result: a scan with the given reading. Input: the fields and the total's confidence. */
    private fun scanOf(
        total: Money?,
        date: String?,
        merchant: String?,
        totalConfidence: Int = CONFIDENT,
    ): ReceiptScan =
        ReceiptScan(
            fields =
                ReceiptFields(
                    total = total?.let { ExtractedMoney(it, totalConfidence) },
                    date = date?.let { ExtractedText(it, CONFIDENT) },
                    merchant = merchant?.let { ExtractedText(it, CONFIDENT) },
                    tax = null,
                    provenance =
                        EngineProvenance(
                            engineId = "receipt-parser",
                            engineVersion = "1.0",
                            computedAtUtcMillis = 0L,
                            confidenceBps = totalConfidence,
                        ),
                ),
            duplicates = emptyList(),
        )

    /** Result: the row FR-OCR-006 would offer as a merge. Output: [Transaction]. */
    private fun existingTransaction(): Transaction =
        Transaction(
            id = "txn:existing",
            accountId = "acc:1",
            amount = Money(-36_580L),
            occurredAtUtcMillis = 0L,
            bookedOn = "2026-08-04",
            categoryId = null,
            merchant = "Big Bazaar",
            note = null,
            source = TransactionSource.MANUAL,
            type = TransactionType.EXPENSE,
        )

    private companion object {
        val SOME_BYTES = ByteArray(8) { it.toByte() }

        /** Comfortably above `ReceiptRules().lowConfidenceBps`, so nothing is flagged by accident. */
        const val CONFIDENT = 9_500
    }
}
