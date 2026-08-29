package com.aicfo.feature.accounts

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import com.aicfo.core.designsystem.component.CfoAmountText
import com.aicfo.core.designsystem.component.CfoCard
import com.aicfo.core.designsystem.component.CfoListRow
import com.aicfo.core.designsystem.component.CfoSecondaryButton
import com.aicfo.core.designsystem.component.maskedAmount
import com.aicfo.core.designsystem.theme.CfoDimens
import com.aicfo.core.model.Account
import com.aicfo.core.model.AccountType
import com.aicfo.core.model.DateFormatter
import com.aicfo.core.model.Money
import com.aicfo.domain.engines.card.CardStatus
import com.aicfo.domain.engines.loan.AmortisationRow

/**
 * One account in the list.
 * Why:    the balance shown is the **derived** one (DB-001), and the opening balance is relegated to
 *         the supporting line — those are two different facts and a row that showed only one would
 *         make the other unverifiable (P-02).
 * Result: a tappable row with its archive and delete actions.
 * Input:  [account]; [onEdit]; [onEvent]. Output: the composition.
 * Changelog: 2026-07-28 — Created for issue 2.5.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun AccountRow(
    account: Account,
    figures: AccountFigures,
    actions: AccountsActions,
    onEvent: (AccountsEvent) -> Unit,
) {
    CfoCard {
        Column(verticalArrangement = Arrangement.spacedBy(CfoDimens.spaceSm)) {
            CfoListRow(
                title = account.rowTitle(),
                supporting = account.rowSupporting(),
                trailing = { CfoAmountText(amount = account.balance) },
            )
            // Issue 6.1: only a credit card has a limit to be a share of.
            if (account.type == AccountType.CREDIT_CARD) {
                CardUtilisation(figures.card)
            }
            // Issue 6.2: only a loan has an instalment to split.
            if (account.type == AccountType.LOAN) {
                NextInstalment(figures.instalment)
            }
            // Issue 6.3: only these three hold instruments with a value and a return.
            if (account.type in INVESTABLE_TYPES) {
                InvestmentSummary(figures.holdings)
            }
            AccountRowActions(account = account, actions = actions, onEvent = onEvent)
        }
    }
}

/**
 * The actions on one account row.
 * Why:    split out of [AccountRow] when issue 6.3's Holdings button made that function too long
 *         (§21.6). The seam is a real one: everything above it describes the account, everything
 *         here is something the user can do to it.
 * Result: the button row. Input: [account]; [actions]; [onEvent]. Output: none.
 * Changelog: 2026-08-24 — Created for issue 6.3.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AccountRowActions(
    account: Account,
    actions: AccountsActions,
    onEvent: (AccountsEvent) -> Unit,
) {
    // FlowRow, not Row: three buttons do not fit one line once the archive action reads
    // "Reopen account" rather than "Close account", and a plain Row pushes Delete off the
    // right edge with nothing to scroll. Found on a device, not in a test — at 200% font
    // even the shorter labels wrap. Issue 2.7's Reconcile makes it four, so the wrapping
    // this already did is now load-bearing rather than a precaution.
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(CfoDimens.spaceSm),
        verticalArrangement = Arrangement.spacedBy(CfoDimens.spaceXs),
    ) {
        CfoSecondaryButton(
            text = stringResource(R.string.account_editor_title_edit),
            onClick = { actions.onEditAccount(account.id) },
        )
        // Issue 6.3: offered only where there is something to hold, so the row does not
        // grow a fifth button on every savings account.
        if (account.type in INVESTABLE_TYPES) {
            CfoSecondaryButton(
                text = stringResource(R.string.accounts_investments_open),
                onClick = { actions.onOpenHoldings(account.id) },
            )
        }
        CfoSecondaryButton(
            // Offered on a closed account too: recording its final statement balance is
            // exactly when a user reaches for this (FR-ACC-006 with FR-ACC-007).
            text = stringResource(R.string.accounts_reconcile),
            onClick = { onEvent(AccountsEvent.OpenReconcile(account.id)) },
        )
        CfoSecondaryButton(
            text =
                stringResource(
                    if (account.isArchived) R.string.accounts_unarchive else R.string.accounts_archive,
                ),
            onClick = { onEvent(AccountsEvent.SetArchived(account.id, !account.isArchived)) },
        )
        CfoSecondaryButton(
            text = stringResource(R.string.accounts_delete),
            onClick = { onEvent(AccountsEvent.Delete(account.id)) },
        )
    }
}

/** Result: the row's title — the name, marked when closed (FR-ACC-007). Output: a [String]. */
@Composable
private fun Account.rowTitle(): String =
    if (isArchived) {
        "$name · ${stringResource(R.string.accounts_archived_label)}"
    } else {
        name
    }

/**
 * Result: the row's second line — the type, the institution when there is one, and what the account
 *         opened with. Output: a [String].
 */
@Composable
private fun Account.rowSupporting(): String {
    val typeLabel = stringResource(AccountLabels.typeLabel(type))
    val opened = maskedAmount(openingBalance)
    return if (institution.isNullOrBlank()) {
        "$typeLabel · ${stringResource(R.string.accounts_row_supporting_no_institution, opened)}"
    } else {
        "$typeLabel · ${stringResource(R.string.accounts_row_supporting, institution!!, opened)}"
    }
}

/**
 * How much of a card's limit is in use, on its row (issue 6.1; FR-ACC-002, P-02).
 *
 * Why:    the one figure a card owner checks without opening anything, and the app holds **two** of
 *         them. This shows the **live** one — everything owed right now, which is what the user
 *         feels — and says so in words, because the alert acts on the *statement* figure and the two
 *         disagree by design (ADR-0025). An unlabelled percentage would be one of two true numbers
 *         with no way to tell which.
 *
 *         Amounts go through `maskedAmount`, so the privacy blur reaches this row like every other
 *         (issue 5.3). The bar itself is not masked: a proportion with no figures beside it says
 *         nothing about how much money is involved.
 * Result: a labelled bar, or a prompt when the card has no terms yet — never a 0% bar, which would
 *         tell a user with a balance that they are using none of their limit (P-03).
 * Input:  [card] — the computed status, or `null` when the terms have not been entered.
 * Output: the composition.
 * Changelog: 2026-08-17 — Created for issue 6.1.
 */
@Composable
private fun CardUtilisation(card: CardStatus?) {
    val ratioBps = card?.live?.ratioBps
    if (card == null || ratioBps == null) {
        Text(
            text = stringResource(R.string.accounts_card_no_terms),
            style = MaterialTheme.typography.bodySmall,
        )
        return
    }

    val used = maskedAmount(card.live.used ?: Money.ZERO)
    val limit = maskedAmount(card.creditLimit)
    val percent = ratioBps / BPS_PER_PERCENT
    // Resolved before the semantics block, which is not composable.
    val spoken = stringResource(R.string.accounts_card_utilisation_description, percent, used, limit)
    Column(
        verticalArrangement = Arrangement.spacedBy(CfoDimens.spaceXs),
        // One node for a screen reader, not three: a bar, a percentage and a caption announced
        // separately are three fragments the user has to reassemble.
        modifier =
            Modifier.semantics(mergeDescendants = true) {
                contentDescription = spoken
            },
    ) {
        LinearProgressIndicator(
            // Coerced for the *bar* only. An over-limit card is a real state and the figure below
            // still says 110%; a progress bar simply has nowhere past its end to draw.
            progress = { (ratioBps.toFloat() / BPS_FULL).coerceIn(0f, 1f) },
            modifier = Modifier.fillMaxWidth(),
        )
        Text(
            text =
                stringResource(R.string.accounts_card_utilisation_percent, percent) + " · " +
                    stringResource(R.string.accounts_card_utilisation, used, limit),
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

/**
 * The next EMI and where it goes, on a loan's row (issue 6.2; FR-ACC-003, P-02, P-03).
 *
 * Why:    a loan's balance is the one balance that tells a borrower almost nothing. "You owe
 *         ₹28,40,000" is true and useless; what they cannot see anywhere else is that this month's
 *         ₹26,034.70 is ₹21,250.00 of interest and only ₹4,784.70 of principal. That split is the
 *         whole point of the engine, and this row is where it first reaches a user.
 *
 *         **Both halves are shown, never one.** Interest alone reads as a complaint and principal
 *         alone reads as progress; the two together are the fact, and they sum to the instalment
 *         above them so the reader can check the line without trusting it (P-02).
 *
 *         Amounts go through `maskedAmount`, so the privacy blur reaches this row like every other
 *         (issue 5.3).
 * Result: two lines — the instalment with its date, then the split — or a prompt when the loan has
 *         no terms yet or is repaid. **Never a ₹0 EMI**, which would tell a borrower with twenty
 *         years left that they owe nothing this month (P-03).
 * Input:  [instalment] — the next row due, or `null`. Output: the composition.
 * Changelog: 2026-08-20 — Created for issue 6.2.
 */
@Composable
private fun NextInstalment(instalment: AmortisationRow?) {
    if (instalment == null) {
        Text(
            text = stringResource(R.string.accounts_loan_no_terms),
            style = MaterialTheme.typography.bodySmall,
        )
        return
    }

    val amount = maskedAmount(instalment.amount)
    val principal = maskedAmount(instalment.principal)
    val interest = maskedAmount(instalment.interest)
    val due = DateFormatter.day(instalment.dueIsoDate)
    // Resolved before the semantics block, which is not composable.
    val spoken = stringResource(R.string.accounts_loan_next_emi_description, amount, due, principal, interest)
    Column(
        verticalArrangement = Arrangement.spacedBy(CfoDimens.spaceXs),
        // One node for a screen reader, not two: an amount and its split announced separately are
        // two fragments the listener has to reassemble into one sentence.
        modifier = Modifier.semantics(mergeDescendants = true) { contentDescription = spoken },
    ) {
        Text(
            text = stringResource(R.string.accounts_loan_next_emi, amount, due),
            style = MaterialTheme.typography.bodyMedium,
        )
        Text(
            text = stringResource(R.string.accounts_loan_split, principal, interest),
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

/** 100 bps = 1%, so a ratio in basis points becomes the whole percent a sentence uses (MNY-002). */
private const val BPS_PER_PERCENT = 100

/** 10 000 bps = 100% — the full width of the utilisation bar (MNY-002). */
private const val BPS_FULL = 10_000f
