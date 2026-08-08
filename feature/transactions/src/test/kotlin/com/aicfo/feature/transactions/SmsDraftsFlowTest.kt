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
import androidx.compose.ui.test.performTextReplacement
import com.aicfo.core.designsystem.theme.CfoTheme
import com.aicfo.core.model.Account
import com.aicfo.core.model.AccountType
import com.aicfo.core.model.Money
import com.aicfo.data.repository.SmsAccess
import com.aicfo.data.repository.SmsDraft
import com.aicfo.domain.engines.sms.SmsDirection
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.LocalDate

/**
 * Compose tests for the SMS review screen (issue 3.9; §18, §23, P-01, P-07).
 *
 * Why:  three things about this screen are only checkable where the user meets them. **The permission
 *       button must be unreachable without the consent** — a ViewModel test proves the stage, this
 *       proves nothing on screen offers a way around it. **A flagged draft must announce itself to a
 *       screen reader**, not only turn a colour (§21.6's accessibility line and the Definition of
 *       Done's scan). And **Add it must be unreachable without an account**, because a bank alert
 *       names four masked digits rather than an account this app knows.
 * What: each of the four faces, the flag's content description, and both decisions.
 * Result: the privacy ordering is asserted as rendered, not only as state.
 * Changelog: 2026-08-07 — Created for issue 3.9.
 *
 * On the JVM via Robolectric, following `ReceiptReviewFlowTest`: the flow is checked on every `test`
 * run rather than only when an emulator happens to be up.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w400dp-h1200dp")
class SmsDraftsFlowTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    /** Result: a string from this module's resources. Input: [id]. Output: the resolved text. */
    private fun text(id: Int): String = compose.activity.getString(id)

    // --- the privacy ordering (P-01, ADR-0013) --------------------------------------------------

    @Test
    fun `without the consent there is no way to ask Android for anything`() {
        var asks = 0
        setContent(SmsDraftsUiState(access = SmsAccess(consentGranted = false)), onRequestPermission = { asks++ })

        compose.onNodeWithText(text(R.string.sms_consent_off_title)).assertIsDisplayed()
        // The button that launches the system dialog is not merely disabled — it is not composed.
        compose.onNodeWithText(text(R.string.sms_permission_grant)).assertDoesNotExist()
        assertEquals(0, asks)
    }

    @Test
    fun `with the consent and no permission the ask is offered, with an explanation first`() {
        var asks = 0
        setContent(
            SmsDraftsUiState(access = SmsAccess(consentGranted = true, permissionGranted = false)),
            onRequestPermission = { asks++ },
        )

        // The explanation is on screen *before* the tap: Android's own dialog says only "Allow app
        // to send and view SMS messages?", which is broader than what this app does.
        compose.onNodeWithText(text(R.string.sms_permission_body)).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.sms_permission_grant)).performClick()

        assertEquals(1, asks)
    }

    @Test
    fun `the on-device promise is on the screen whatever the state`() {
        setContent(SmsDraftsUiState())

        // FR-ONB-003's promise, repeated where the data is. A user deciding whether to grant a
        // permission should not have to remember what an onboarding step said.
        compose.onNodeWithText(text(R.string.sms_on_device)).assertIsDisplayed()
    }

    // --- the empty state --------------------------------------------------------------------------

    @Test
    fun `before any scan the screen says it has not looked`() {
        setContent(granted().copy(lastScanFound = null))

        compose.onNodeWithText(text(R.string.sms_empty_never_scanned)).assertIsDisplayed()
    }

    @Test
    fun `after a scan that found nothing the screen says so`() {
        // Separate tests because "we have not looked" and "we looked and found nothing" feel
        // identical on screen, and the point is that they read differently.
        setContent(granted().copy(lastScanFound = 0))

        compose.onNodeWithText(text(R.string.sms_empty_after_scan)).assertIsDisplayed()
    }

    @Test
    fun `scanning shows a spinner a screen reader can find`() {
        setContent(granted().copy(isScanning = true))

        compose.onNodeWithContentDescription("sms-scanning").assertIsDisplayed()
    }

    // --- the review list (P-02, P-07) ---------------------------------------------------------------

    @Test
    fun `a draft shows what was read and who it came from`() {
        setContent(granted(draft()))

        // The sender and the payee together are how a person recognises their own transaction.
        // `onAllNodesWithText(...).onFirst()` because the payee appears twice by design: once as the
        // reading and once pre-filled into the field that can correct it.
        compose.onAllNodesWithText("SWIGGY").onFirst().assertIsDisplayed()
        compose.onNodeWithText(text(R.string.sms_direction_debit)).assertIsDisplayed()
    }

    @Test
    fun `the amount and the payee are correctable, and the direction is not`() {
        val events = mutableListOf<SmsDraftsEvent>()
        setContent(granted(draft()), onEvent = { events += it })

        compose.onNodeWithText(text(R.string.sms_amount)).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.sms_merchant)).assertIsDisplayed()
        // P-07 has a limit: editing changes how much moved, never which way. The direction is
        // rendered as text with no control beside it.
        compose.onNodeWithText(text(R.string.sms_direction_debit)).assertIsDisplayed()

        compose.onNodeWithText("₹1,250.00").performTextReplacement("1300")

        assertEquals(
            listOf(SmsDraftsEvent.AmountEdited("d1", "1300")),
            events.filterIsInstance<SmsDraftsEvent.AmountEdited>(),
        )
    }

    @Test
    fun `Add it is unreachable while the amount is not a figure`() {
        setContent(
            granted(draft()).copy(edits = mapOf("d1" to SmsDraftEdit(amountText = "twelve", merchantText = ""))),
        )

        compose.onNodeWithText(text(R.string.sms_accept)).assertIsNotEnabled()
    }

    @Test
    fun `a low-confidence draft announces itself to a screen reader`() {
        setContent(granted(draft(isLowConfidence = true)))

        // A word and a content description, not only a colour — §21.6's accessibility line, and the
        // same choice the receipt review screen makes.
        compose.onNodeWithText(text(R.string.sms_low_confidence)).assertIsDisplayed()
        compose.onNodeWithContentDescription("sms-draft-low-confidence").assertIsDisplayed()
    }

    @Test
    fun `Add it is unreachable without an account`() {
        setContent(granted(draft()).copy(accounts = emptyList(), selectedAccountId = null))

        compose.onNodeWithText(text(R.string.sms_accept)).assertIsNotEnabled()
    }

    @Test
    fun `both decisions are one tap each`() {
        val events = mutableListOf<SmsDraftsEvent>()
        setContent(granted(draft()), onEvent = { events += it })

        compose.onNodeWithText(text(R.string.sms_accept)).assertIsEnabled().performClick()
        compose.onNodeWithText(text(R.string.sms_dismiss)).performClick()

        assertEquals(
            listOf(SmsDraftsEvent.Accept("d1"), SmsDraftsEvent.Dismiss("d1")),
            events.filterNot { it is SmsDraftsEvent.AccountSelected },
        )
    }

    // --- helpers ------------------------------------------------------------------------------------

    /** Result: renders the stateless body. Input: [uiState] and the two callbacks. Output: none. */
    private fun setContent(
        uiState: SmsDraftsUiState,
        onEvent: (SmsDraftsEvent) -> Unit = {},
        onRequestPermission: () -> Unit = {},
    ) {
        compose.setContent {
            CfoTheme {
                SmsDraftsContent(
                    uiState = uiState,
                    onEvent = onEvent,
                    onDone = {},
                    onRequestPermission = onRequestPermission,
                )
            }
        }
    }

    /** Result: a fully granted state holding [drafts]. Input: [drafts]. Output: [SmsDraftsUiState]. */
    private fun granted(vararg drafts: SmsDraft): SmsDraftsUiState =
        SmsDraftsUiState(
            access = SmsAccess(consentGranted = true, permissionGranted = true),
            drafts = drafts.toList(),
            accounts = listOf(ACCOUNT),
            selectedAccountId = ACCOUNT.id,
        )

    /** Result: one pending draft. Input: the fields under test. Output: [SmsDraft]. */
    private fun draft(
        id: String = "d1",
        isLowConfidence: Boolean = false,
    ): SmsDraft =
        SmsDraft(
            id = id,
            sender = "VM-HDFCBK",
            amount = Money(125_000L),
            direction = SmsDirection.DEBIT,
            bookedOn = LocalDate.of(2026, 8, 7),
            counterparty = "SWIGGY",
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
