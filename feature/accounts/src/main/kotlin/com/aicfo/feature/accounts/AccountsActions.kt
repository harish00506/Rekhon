package com.aicfo.feature.accounts

import androidx.compose.runtime.Immutable
import com.aicfo.core.model.AccountType
import com.aicfo.data.repository.PricedHolding
import com.aicfo.domain.engines.card.CardStatus
import com.aicfo.domain.engines.loan.AmortisationRow

/**
 * Where the accounts list can send the user (issue 6.3; ARC-001).
 *
 * Why:  the list had three destinations by the time issue 6.3 added holdings, and three lambdas
 *       threaded through two composables and a row put both functions past detekt's parameter
 *       ceiling. Grouping them is the fix [DashboardActions] already made for the same reason one
 *       feature over, and the argument is the same: adding a fourth destination should be a field
 *       here rather than a fourth parameter in three signatures.
 *
 *       **Still lambdas, not a `NavController`.** ARC-001 keeps routing in `:app`'s nav graph; this
 *       type only groups the callbacks, so the module still does not know another feature exists.
 * What: one callback per destination reachable from the list.
 * Result: the row, the content and the screen each take one parameter instead of three.
 * Changelog: 2026-08-24 — Created for issue 6.3, when holdings became the third destination.
 *
 * @property onAddAccount open the editor on a new account.
 * @property onEditAccount open the editor on an existing one.
 * @property onOpenHoldings open one account's holdings (issue 6.3, §11).
 * @property onOpenAllocation open the portfolio-wide allocation (issue 6.4, FR-INV-002). No id: it
 *   is a question about every investable account at once, which is why it hangs off the list rather
 *   than off a row.
 */
@Immutable
data class AccountsActions(
    val onAddAccount: () -> Unit,
    val onEditAccount: (String) -> Unit,
    val onOpenHoldings: (String) -> Unit,
    val onOpenAllocation: () -> Unit,
)

/**
 * The engine-derived figures for one account row (issue 6.3).
 *
 * Why:  three nullable maps looked up per row, for the same reason [AccountsActions] exists: by
 *       issue 6.3 the row took seven parameters, and four of them were "what the engines said about
 *       this account". Naming that group makes the row's signature about the row again.
 *
 *       **All three are nullable and absence means "not set up yet", never zero** (P-03) — a card
 *       with no terms, a loan with none, an account holding nothing. Each renders as a prompt.
 * What: the card's status, the loan's next instalment, and the account's priced holdings.
 * Result: one parameter instead of three.
 * Changelog: 2026-08-24 — Created for issue 6.3.
 *
 * @property card the card engine's answer, or `null` when the card has no terms.
 * @property instalment the next EMI, or `null` when the loan has no terms or is repaid.
 * @property holdings the account's priced holdings, or `null` when it holds nothing.
 */
@Immutable
data class AccountFigures(
    val card: CardStatus? = null,
    val instalment: AmortisationRow? = null,
    val holdings: List<PricedHolding>? = null,
)

/**
 * The account types that can hold instruments (issue 6.3; §11).
 *
 * `PROPERTY` and `VEHICLE` are deliberately absent: they are valued, not lot-tracked, and
 * `InvestmentRepository.saveHolding` refuses a holding on either — so offering the button there
 * would be offering a dead end.
 */
internal val INVESTABLE_TYPES: Set<AccountType> =
    setOf(AccountType.INVESTMENT, AccountType.GOLD, AccountType.CRYPTO)
