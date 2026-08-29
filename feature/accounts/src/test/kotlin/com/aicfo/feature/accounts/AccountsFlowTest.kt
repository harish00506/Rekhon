package com.aicfo.feature.accounts

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.unit.dp
import com.aicfo.core.designsystem.theme.CfoTheme
import com.aicfo.core.model.AccountType
import com.aicfo.core.model.Money
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Compose tests for the accounts screens (issue 2.5; §21.5's "critical flows").
 *
 * Why:  the ViewModel tests prove what the state does; these prove the screen actually renders it
 *       and routes the taps back. Two things only a rendered test catches: **an account's figures
 *       reaching the screen at all** — a row that showed the opening balance where the current one
 *       belongs would pass every state test — and **the type picker offering all eleven types**,
 *       which is FR-ACC-001's MUST expressed as pixels rather than as an enum.
 * What: the list, the empty state, the archived toggle, and the editor form.
 * Result: the screens are exercised on every `test` run, not only when a device is attached.
 * Changelog: 2026-07-28 — Created for issue 2.5.
 *
 * On the JVM via Robolectric, following `:feature:onboarding`'s `OnboardingFlowTest`: the flow is
 * checked on every run rather than only when an emulator happens to be up.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w400dp-h1200dp")
class AccountsFlowTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    /** Result: a string from this module's resources. Input: [id]. Output: the resolved text. */
    private fun text(id: Int): String = compose.activity.getString(id)

    // --- the list --------------------------------------------------------------------------------

    @Test
    fun `an account renders with its name, type and current balance`() {
        compose.setContent {
            CfoTheme {
                AccountsContent(
                    uiState =
                        AccountsUiState(
                            isLoading = false,
                            accounts =
                                listOf(
                                    account {
                                        copy(
                                            name = "HDFC Savings",
                                            openingBalance = Money(1_00_000_00L),
                                            balance = Money(75_000_00L),
                                        )
                                    },
                                ),
                        ),
                    onEvent = {},
                    actions = AccountsActions({}, {}, {}, {}),
                )
            }
        }

        compose.onNodeWithText("HDFC Savings").assertIsDisplayed()
        // The derived balance, not the opening one — the row's whole job (DB-001).
        compose.onNodeWithText("₹75,000.00", substring = true).assertIsDisplayed()
    }

    @Test
    fun `a user with no accounts sees an invitation, not a blank screen`() {
        compose.setContent {
            CfoTheme {
                AccountsContent(
                    uiState = AccountsUiState(isLoading = false),
                    onEvent = {},
                    actions = AccountsActions({}, {}, {}, {}),
                )
            }
        }

        compose.onNodeWithText(text(R.string.accounts_empty_title)).assertIsDisplayed()
    }

    @Test
    fun `a closed account is labelled as closed`() {
        // FR-ACC-007: it is still there and still counts in history, so it must be distinguishable
        // from an active one rather than merely absent.
        compose.setContent {
            CfoTheme {
                AccountsContent(
                    uiState =
                        AccountsUiState(
                            isLoading = false,
                            showArchived = true,
                            accounts = listOf(account { copy(name = "Old Card", isArchived = true) }),
                        ),
                    onEvent = {},
                    actions = AccountsActions({}, {}, {}, {}),
                )
            }
        }

        compose.onNodeWithText(text(R.string.accounts_archived_label), substring = true).assertIsDisplayed()
    }

    @Test
    fun `tapping the archived toggle sends the event up`() {
        val events = mutableListOf<AccountsEvent>()
        compose.setContent {
            CfoTheme {
                AccountsContent(
                    uiState = AccountsUiState(isLoading = false),
                    onEvent = { events += it },
                    actions = AccountsActions({}, {}, {}, {}),
                )
            }
        }

        compose.onNodeWithText(text(R.string.accounts_show_archived)).performClick()

        assertEquals(listOf(AccountsEvent.ToggleArchived(show = true)), events)
    }

    @Test
    fun `tapping delete sends the account's id up`() {
        val events = mutableListOf<AccountsEvent>()
        compose.setContent {
            CfoTheme {
                AccountsContent(
                    uiState = AccountsUiState(isLoading = false, accounts = listOf(account { copy(id = "account:7") })),
                    onEvent = { events += it },
                    actions = AccountsActions({}, {}, {}, {}),
                )
            }
        }

        compose.onNodeWithText(text(R.string.accounts_delete)).performScrollTo().performClick()

        assertEquals(listOf(AccountsEvent.Delete("account:7")), events)
    }

    @Test
    @Config(qualifiers = "w320dp-h1200dp")
    fun `every action stays reachable on an archived row`() {
        // **This test did not find the bug and cannot.** On a real device a plain `Row` squeezed
        // the Delete button to 10px on the active row and to nothing on the archived one, where the
        // action reads the longer "Reopen account". Robolectric measures text with a stub that
        // gives every glyph the same narrow width, so all three fit here regardless of layout —
        // the assertion below stays green against the very code that produced the defect, which was
        // confirmed by reverting the fix and re-running. It is kept as a cheap smoke check that the
        // three actions exist and are not zero-width; **the device is the gate**, and the tracker
        // records it that way rather than claiming this one bites.
        compose.setContent {
            CfoTheme {
                AccountsContent(
                    uiState =
                        AccountsUiState(
                            isLoading = false,
                            showArchived = true,
                            accounts = listOf(account { copy(name = "Old Card", isArchived = true) }),
                        ),
                    onEvent = {},
                    actions = AccountsActions({}, {}, {}, {}),
                )
            }
        }

        // Width rather than `assertIsDisplayed`, because the failure mode is a squeeze, not an
        // absence: the semantics tree calls a 10px-wide button displayed.
        listOf(
            R.string.account_editor_title_edit,
            R.string.accounts_unarchive,
            R.string.accounts_delete,
        ).forEach { label ->
            val bounds = compose.onNodeWithText(text(label)).getUnclippedBoundsInRoot()
            val width = bounds.right - bounds.left
            assertTrue("${text(label)} is only $width wide — it has been squeezed", width > MIN_ACTION_WIDTH)
        }
    }

    @Test
    fun `an error is shown rather than swallowed into an empty list`() {
        compose.setContent {
            CfoTheme {
                AccountsContent(
                    uiState = AccountsUiState(isLoading = false, errorCode = "storage"),
                    onEvent = {},
                    actions = AccountsActions({}, {}, {}, {}),
                )
            }
        }

        compose.onNodeWithText(text(R.string.accounts_error_storage)).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.accounts_empty_title)).assertDoesNotExistSafely()
    }

    // --- the editor ------------------------------------------------------------------------------

    @Test
    fun `the editor offers every one of FR-ACC-001's eleven types`() {
        // The requirement as pixels: an enum with eleven constants and a picker showing four is a
        // requirement met on paper only.
        compose.setContent {
            CfoTheme {
                AccountEditorContent(uiState = AccountEditorUiState(), onEvent = {}, onCancel = {})
            }
        }

        AccountType.entries.forEach { type ->
            compose.onNodeWithText(text(AccountLabels.typeLabel(type)))
                .performScrollTo()
                .assertIsDisplayed()
        }
    }

    @Test
    fun `typing a name sends it up`() {
        val events = mutableListOf<AccountEditorEvent>()
        compose.setContent {
            CfoTheme {
                AccountEditorContent(
                    uiState = AccountEditorUiState(),
                    onEvent = { events += it },
                    onCancel = {},
                )
            }
        }

        compose.onNodeWithText(text(R.string.account_editor_name)).performScrollTo().performTextInput("HDFC")

        assertTrue(events.any { it is AccountEditorEvent.NameChanged && it.value == "HDFC" })
    }

    @Test
    fun `the editor says the current balance is worked out rather than typed`() {
        // P-02: the user must be able to see *why* they cannot edit the balance here, not merely
        // find that they cannot.
        compose.setContent {
            CfoTheme {
                AccountEditorContent(uiState = AccountEditorUiState(), onEvent = {}, onCancel = {})
            }
        }

        compose.onNodeWithText(text(R.string.account_editor_opening_balance_help))
            .performScrollTo()
            .assertIsDisplayed()
    }

    @Test
    fun `an editor opened on an existing account says it is editing`() {
        compose.setContent {
            CfoTheme {
                AccountEditorContent(
                    uiState = AccountEditorUiState(id = "account:1", name = "HDFC"),
                    onEvent = {},
                    onCancel = {},
                )
            }
        }

        compose.onNodeWithText(text(R.string.account_editor_title_edit)).assertIsDisplayed()
    }

    // --- the reconciliation panel (issue 2.7; FR-ACC-006) ----------------------------------------

    @Test
    fun `tapping reconcile opens the panel for that account`() {
        val events = mutableListOf<AccountsEvent>()
        compose.setContent {
            CfoTheme {
                AccountsContent(
                    uiState =
                        AccountsUiState(
                            isLoading = false,
                            accounts = listOf(account { copy(id = "account:7", name = "HDFC Savings") }),
                        ),
                    onEvent = { events += it },
                    actions = AccountsActions({}, {}, {}, {}),
                )
            }
        }

        compose.onNodeWithText(text(R.string.accounts_reconcile)).performScrollTo().performClick()

        assertEquals(listOf(AccountsEvent.OpenReconcile("account:7")), events)
    }

    @Test
    fun `the panel shows the app balance, the adjustment and the rule that produced it`() {
        // P-02 as pixels. All three have to be on screen *before* the user commits, because the
        // adjustment becomes a permanent row in their history.
        compose.setContent {
            CfoTheme {
                AccountsContent(
                    uiState =
                        AccountsUiState(
                            isLoading = false,
                            accounts = listOf(account()),
                            reconciling =
                                ReconcileState(
                                    account = account { copy(balance = Money(92_500_00L)) },
                                    statementText = "93000",
                                ),
                        ),
                    onEvent = {},
                    actions = AccountsActions({}, {}, {}, {}),
                )
            }
        }

        compose.onNodeWithText("₹92,500.00", substring = true).assertIsDisplayed()
        compose.onNodeWithText("+₹500.00", substring = true).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.accounts_reconcile_rule)).assertIsDisplayed()
    }

    @Test
    fun `an empty statement leaves the confirm button disabled and promises nothing`() {
        // **Found by driving the app on a device, not by a test.** The panel used to announce
        // "this adds one adjustment transaction" on an untouched form, while Confirm was disabled
        // and nothing could be added. Every existing test had already typed an amount, so the state
        // a user actually sees first was the one state nothing asserted.
        compose.setContent {
            CfoTheme {
                AccountsContent(
                    uiState =
                        AccountsUiState(
                            isLoading = false,
                            accounts = listOf(account()),
                            reconciling = ReconcileState(account = account()),
                        ),
                    onEvent = {},
                    actions = AccountsActions({}, {}, {}, {}),
                )
            }
        }

        compose.onNodeWithText(text(R.string.accounts_reconcile_confirm)).assertIsNotEnabled()
        compose.onNodeWithText(text(R.string.accounts_reconcile_explain)).assertDoesNotExistSafely()
        compose.onNodeWithText(text(R.string.accounts_reconcile_in_step)).assertDoesNotExistSafely()
        // The rule itself stays: it explains the field the user is about to fill in.
        compose.onNodeWithText(text(R.string.accounts_reconcile_rule)).assertIsDisplayed()
    }

    @Test
    fun `a statement that already matches says so rather than showing a dead button`() {
        compose.setContent {
            CfoTheme {
                AccountsContent(
                    uiState =
                        AccountsUiState(
                            isLoading = false,
                            accounts = listOf(account()),
                            reconciling =
                                ReconcileState(
                                    account = account { copy(balance = Money(92_500_00L)) },
                                    statementText = "92500",
                                ),
                        ),
                    onEvent = {},
                    actions = AccountsActions({}, {}, {}, {}),
                )
            }
        }

        compose.onNodeWithText(text(R.string.accounts_reconcile_in_step)).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.accounts_reconcile_confirm)).assertIsEnabled()
    }

    @Test
    fun `typing in the panel sends the text up`() {
        val events = mutableListOf<AccountsEvent>()
        compose.setContent {
            CfoTheme {
                AccountsContent(
                    uiState =
                        AccountsUiState(
                            isLoading = false,
                            accounts = listOf(account()),
                            reconciling = ReconcileState(account = account()),
                        ),
                    onEvent = { events += it },
                    actions = AccountsActions({}, {}, {}, {}),
                )
            }
        }

        compose.onNodeWithText(text(R.string.accounts_reconcile_statement)).performTextInput("93000")

        assertEquals(listOf(AccountsEvent.StatementChanged("93000")), events)
    }
}

/**
 * The narrowest a readable action label can be. Well under any real button, well over a squeeze.
 */
private val MIN_ACTION_WIDTH = 48.dp

/**
 * Asserts a node is absent, tolerating a matcher that finds nothing.
 * Why:    `assertDoesNotExist()` is the direct form, kept behind a helper here so the intent reads
 *         as an assertion about the screen rather than as an exception being avoided.
 * Result: fails if the node is present. Input: the receiver. Output: none.
 * Changelog: 2026-07-28 — Created for issue 2.5.
 */
private fun androidx.compose.ui.test.SemanticsNodeInteraction.assertDoesNotExistSafely() = assertDoesNotExist()
