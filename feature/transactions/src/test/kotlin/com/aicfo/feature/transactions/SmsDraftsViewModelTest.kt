package com.aicfo.feature.transactions

import app.cash.turbine.test
import com.aicfo.core.model.Account
import com.aicfo.core.model.AccountType
import com.aicfo.core.model.Money
import com.aicfo.data.repository.SmsDraft
import com.aicfo.domain.engines.sms.SmsDirection
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.LocalDate

/**
 * Tests for [SmsDraftsViewModel] — the four faces and the two taps (issue 3.9; §18, P-01, P-07).
 *
 * Why:  the first three tests are the privacy ordering, and they are the reason this suite exists.
 *       **The consent is checked before the permission**, so a user who has not opted in is never
 *       shown a button that asks Android for `READ_SMS` — asking for a dangerous, Play-restricted
 *       permission for something they never agreed to is the exact inversion P-01 exists to prevent
 *       (ADR-0013). That rule lives in `SmsDraftsUiState.stage` precisely so it can be asserted here
 *       without rendering anything.
 *
 *       After that: the sign, which the ViewModel derives rather than reads. A debit alert can only
 *       produce a negative row, so a misread spend can never be recorded as income.
 * What: the stage machine, the scan, both decisions, and the failure paths.
 * Result: the decisions half of issue 3.9 is proven without a device or a database.
 * Changelog: 2026-08-07 — Created for issue 3.9.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SmsDraftsViewModelTest {
    private val sms = FakeSmsRepository()
    private val accounts = FakeAccountRepository()

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        accounts.setAccounts(ACCOUNT)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    // --- the privacy ordering (P-01, ADR-0013) --------------------------------------------------

    @Test
    fun `a user who has not opted in is never offered the permission`() =
        runTest {
            sms.setAccess(consent = false, permission = false)

            viewModel().uiState.test {
                assertEquals(SmsDraftsStage.CONSENT_OFF, awaitItem().stage)
            }
        }

    @Test
    fun `a user who opted in but has not granted READ_SMS is asked for it`() =
        runTest {
            sms.setAccess(consent = true, permission = false)

            viewModel().uiState.test {
                assertEquals(SmsDraftsStage.PERMISSION_NEEDED, awaitItem().stage)
            }
        }

    @Test
    fun `the consent outranks the permission even when Android has granted it`() =
        runTest {
            // The case an ordering written the other way round would get wrong: Android may still
            // hold a grant from before the user turned the feature off, and the app must not use it.
            sms.setAccess(consent = false, permission = true)
            sms.setDrafts(draft())

            viewModel().uiState.test {
                assertEquals(SmsDraftsStage.CONSENT_OFF, awaitItem().stage)
            }
        }

    @Test
    fun `everything granted with nothing waiting is the empty state`() =
        runTest {
            sms.setAccess(consent = true, permission = true)

            viewModel().uiState.test {
                assertEquals(SmsDraftsStage.EMPTY, awaitItem().stage)
            }
        }

    @Test
    fun `drafts waiting is the review state`() =
        runTest {
            sms.setAccess(consent = true, permission = true)
            sms.setDrafts(draft())

            viewModel().uiState.test {
                assertEquals(SmsDraftsStage.REVIEW, awaitItem().stage)
            }
        }

    @Test
    fun `revoking the consent while the screen is open empties it`() =
        runTest {
            sms.setAccess(consent = true, permission = true)
            sms.setDrafts(draft())
            val viewModel = viewModel()

            viewModel.uiState.test {
                assertEquals(SmsDraftsStage.REVIEW, awaitItem().stage)

                sms.setAccess(consent = false, permission = true)
                sms.setDrafts()

                assertEquals(SmsDraftsStage.CONSENT_OFF, expectMostRecentItem().stage)
            }
        }

    // --- the permission dialog --------------------------------------------------------------------

    @Test
    fun `granting the permission scans straight away`() =
        runTest {
            sms.setAccess(consent = true, permission = false)
            val viewModel = viewModel()

            viewModel.onEvent(SmsDraftsEvent.PermissionResult(granted = true))

            // The user tapped "allow" in order to see their alerts. Making them tap again would be
            // asking twice for one decision.
            assertEquals(1, sms.scanCount)
        }

    @Test
    fun `refusing the permission scans nothing and stays on the ask`() =
        runTest {
            sms.setAccess(consent = true, permission = false)
            val viewModel = viewModel()

            viewModel.onEvent(SmsDraftsEvent.PermissionResult(granted = false))

            assertEquals(0, sms.scanCount)
            assertEquals(SmsDraftsStage.PERMISSION_NEEDED, viewModel.uiState.value.stage)
        }

    // --- scanning -----------------------------------------------------------------------------------

    @Test
    fun `a scan reports what it found and clears the spinner`() =
        runTest {
            sms.setAccess(consent = true, permission = true)
            sms.scanResult = com.aicfo.core.common.Ok(3)
            val viewModel = viewModel()

            viewModel.onEvent(SmsDraftsEvent.Scan)

            assertEquals(3, viewModel.uiState.value.lastScanFound)
            assertTrue("a spinner that survives is a screen the user has to leave", !viewModel.uiState.value.isScanning)
        }

    @Test
    fun `a failed scan surfaces a code and clears the spinner`() =
        runTest {
            sms.setAccess(consent = true, permission = true)
            sms.scanResult = com.aicfo.core.common.Err(com.aicfo.core.common.AppError.Storage("x"))
            val viewModel = viewModel()

            viewModel.onEvent(SmsDraftsEvent.Scan)

            assertEquals("storage", viewModel.uiState.value.errorCode)
            assertTrue(!viewModel.uiState.value.isScanning)
        }

    // --- decisions (P-07) --------------------------------------------------------------------------

    @Test
    fun `accepting a debit draft records a negative amount`() =
        runTest {
            sms.setAccess(consent = true, permission = true)
            sms.setDrafts(draft(direction = SmsDirection.DEBIT, amount = Money(125_000L)))
            val viewModel = viewModel()

            viewModel.onEvent(SmsDraftsEvent.Accept("d1"))

            // The sign is derived from the alert, never typed — so a misread spend cannot be
            // recorded as income.
            val (draftId, transaction) = sms.accepted.single()
            assertEquals("d1", draftId)
            assertEquals(Money(-125_000L), transaction.amount)
            assertEquals(ACCOUNT.id, transaction.accountId)
            assertEquals(LocalDate.of(2026, 8, 7), transaction.bookedOn)
        }

    @Test
    fun `accepting a credit draft records a positive amount`() =
        runTest {
            sms.setAccess(consent = true, permission = true)
            sms.setDrafts(draft(direction = SmsDirection.CREDIT, amount = Money(8_500_000L)))
            val viewModel = viewModel()

            viewModel.onEvent(SmsDraftsEvent.Accept("d1"))

            assertEquals(Money(8_500_000L), sms.accepted.single().second.amount)
        }

    @Test
    fun `the payee travels onto the transaction`() =
        runTest {
            sms.setAccess(consent = true, permission = true)
            sms.setDrafts(draft(counterparty = "SWIGGY"))
            val viewModel = viewModel()

            viewModel.onEvent(SmsDraftsEvent.Accept("d1"))

            assertEquals("SWIGGY", sms.accepted.single().second.merchant)
        }

    @Test
    fun `a corrected amount is what gets saved`() =
        runTest {
            sms.setAccess(consent = true, permission = true)
            sms.setDrafts(draft(amount = Money(125_000L)))
            val viewModel = viewModel()

            viewModel.onEvent(SmsDraftsEvent.AmountEdited("d1", "1300"))
            viewModel.onEvent(SmsDraftsEvent.Accept("d1"))

            // P-07: the parser proposes and the user decides — and deciding includes correcting.
            assertEquals(Money(-130_000L), sms.accepted.single().second.amount)
        }

    @Test
    fun `correcting the amount cannot flip the direction`() =
        runTest {
            sms.setAccess(consent = true, permission = true)
            sms.setDrafts(draft(direction = SmsDirection.DEBIT))
            val viewModel = viewModel()

            // A minus sign typed into the field must not turn a debit into a credit: the direction
            // came from the alert's wording and no field on the screen can change it.
            viewModel.onEvent(SmsDraftsEvent.AmountEdited("d1", "-1300"))
            viewModel.onEvent(SmsDraftsEvent.Accept("d1"))

            // The magnitude is refused outright rather than being silently made positive, so the
            // user sees the field is wrong instead of the app guessing what they meant.
            assertEquals(emptyList<Any>(), sms.accepted)
        }

    @Test
    fun `a corrected payee is what gets saved`() =
        runTest {
            sms.setAccess(consent = true, permission = true)
            sms.setDrafts(draft(counterparty = null))
            val viewModel = viewModel()

            viewModel.onEvent(SmsDraftsEvent.MerchantEdited("d1", "  CHAI POINT  "))
            viewModel.onEvent(SmsDraftsEvent.Accept("d1"))

            assertEquals("CHAI POINT", sms.accepted.single().second.merchant)
        }

    @Test
    fun `editing the payee does not wipe the amount the parser read`() =
        runTest {
            sms.setAccess(consent = true, permission = true)
            sms.setDrafts(draft(amount = Money(125_000L)))
            val viewModel = viewModel()

            viewModel.onEvent(SmsDraftsEvent.MerchantEdited("d1", "ACME"))
            viewModel.onEvent(SmsDraftsEvent.Accept("d1"))

            // The edit is seeded from what was read, not from an empty value.
            assertEquals(Money(-125_000L), sms.accepted.single().second.amount)
        }

    @Test
    fun `an unreadable amount blocks that draft and no other`() =
        runTest {
            sms.setAccess(consent = true, permission = true)
            sms.setDrafts(draft(), draft(id = "d2"))
            val viewModel = viewModel()

            viewModel.onEvent(SmsDraftsEvent.AmountEdited("d1", "twelve hundred"))

            val state = viewModel.uiState.value
            assertFalse(state.canAcceptDraft(state.drafts.first { it.id == "d1" }))
            assertTrue(state.canAcceptDraft(state.drafts.first { it.id == "d2" }))
        }

    @Test
    fun `a zero amount is refused`() =
        runTest {
            sms.setAccess(consent = true, permission = true)
            sms.setDrafts(draft())
            val viewModel = viewModel()

            viewModel.onEvent(SmsDraftsEvent.AmountEdited("d1", "0"))
            viewModel.onEvent(SmsDraftsEvent.Accept("d1"))

            assertEquals(emptyList<Any>(), sms.accepted)
        }

    @Test
    fun `nothing is accepted without an account`() =
        runTest {
            accounts.setAccounts()
            sms.setAccess(consent = true, permission = true)
            sms.setDrafts(draft())
            val viewModel = viewModel()

            viewModel.onEvent(SmsDraftsEvent.Accept("d1"))

            // A bank alert names four masked digits, not an account this app knows. Without a chosen
            // account there is nowhere for the money to have moved from.
            assertEquals(emptyList<Any>(), sms.accepted)
            assertTrue(!viewModel.uiState.value.canAccept)
        }

    @Test
    fun `a failed accept surfaces a code`() =
        runTest {
            sms.setAccess(consent = true, permission = true)
            sms.setDrafts(draft())
            sms.acceptFails = true
            val viewModel = viewModel()

            viewModel.onEvent(SmsDraftsEvent.Accept("d1"))

            assertEquals("storage", viewModel.uiState.value.errorCode)
        }

    @Test
    fun `dismissing a draft says so and writes nothing`() =
        runTest {
            sms.setAccess(consent = true, permission = true)
            sms.setDrafts(draft())
            val viewModel = viewModel()

            viewModel.onEvent(SmsDraftsEvent.Dismiss("d1"))

            assertEquals(listOf("d1"), sms.dismissed)
            assertEquals(emptyList<Any>(), sms.accepted)
        }

    @Test
    fun `an error can be dismissed`() =
        runTest {
            sms.setAccess(consent = true, permission = true)
            sms.scanResult = com.aicfo.core.common.Err(com.aicfo.core.common.AppError.Storage("x"))
            val viewModel = viewModel()
            viewModel.onEvent(SmsDraftsEvent.Scan)

            viewModel.onEvent(SmsDraftsEvent.DismissError)

            assertNull(viewModel.uiState.value.errorCode)
        }

    // --- helpers --------------------------------------------------------------------------------

    /** Result: a ViewModel over the fakes. Input: none. Output: [SmsDraftsViewModel]. */
    private fun viewModel(): SmsDraftsViewModel = SmsDraftsViewModel(sms, accounts)

    /** Result: one pending draft. Input: the fields under test. Output: [SmsDraft]. */
    private fun draft(
        id: String = "d1",
        direction: SmsDirection = SmsDirection.DEBIT,
        amount: Money = Money(125_000L),
        counterparty: String? = "SWIGGY",
        isLowConfidence: Boolean = false,
    ): SmsDraft =
        SmsDraft(
            id = id,
            sender = "VM-HDFCBK",
            amount = amount,
            direction = direction,
            bookedOn = LocalDate.of(2026, 8, 7),
            counterparty = counterparty,
            accountTail = "4521",
            confidenceBps = if (isLowConfidence) 5_500 else 9_000,
            isLowConfidence = isLowConfidence,
        )

    private companion object {
        val ACCOUNT =
            Account(
                id = "a1",
                profileId = "p1",
                name = "HDFC Savings",
                type = AccountType.BANK,
                institution = null,
                openingBalance = Money(1_000_000L),
                balance = Money(1_000_000L),
                currencyCode = "INR",
                isArchived = false,
            )
    }
}
