package com.aicfo.feature.accounts

import androidx.compose.runtime.Immutable
import com.aicfo.core.model.AssetClass
import com.aicfo.core.model.LotKind
import com.aicfo.data.repository.PricedHolding

/**
 * Everything the holdings screen shows, in one immutable value (issue 6.3; §11, ARC-004).
 *
 * Why:  one state class per screen as a `StateFlow`, for the reason [AccountsUiState] gives —
 *       every reachable state is constructible in a test, and there is no second source of truth
 *       for the screen to disagree with.
 * What: the account being viewed, its priced holdings, the editor's contents when one is open, and
 *       the loading/error flags.
 * Result: a screen whose every state is assertable without a device.
 * Changelog: 2026-08-24 — Created for issue 6.3.
 *
 * **`@Immutable` and every field a `val`** so Compose can skip recomposition when nothing changed —
 * a list of holdings re-priced on every lot edit is exactly the shape that re-renders needlessly.
 *
 * @property accountId the account whose holdings these are.
 * @property holdings the priced holdings with their price age, name-ordered (issue 6.5).
 * @property editor the open editor, or `null` when the list is showing.
 * @property isLoading whether the first read has landed.
 * @property errorCode a failure to show in the banner, or `null`.
 */
@Immutable
data class HoldingsUiState(
    val accountId: String = "",
    val holdings: List<PricedHolding> = emptyList(),
    val editor: HoldingEditorState? = null,
    val isLoading: Boolean = true,
    val errorCode: String? = null,
) {
    /**
     * Whether to show the "nothing here yet" invitation rather than a list.
     *
     * Why: the same three-way distinction [AccountsUiState.isEmpty] draws. Still loading is not
     *      "you own nothing" — the invitation would flash before the store answered — and neither
     *      is a failed read, which has a banner of its own to explain itself.
     */
    val isEmpty: Boolean get() = holdings.isEmpty() && !isLoading && errorCode == null
}

/**
 * The holding editor's fields, as typed (issue 6.3).
 *
 * Why:  strings rather than `Money` and `Quantity`, because a half-typed amount is not a number
 *       yet — the same reason the account editor holds its balance as text. Parsing happens once,
 *       on save, where a failure can be reported.
 * What: the id being edited (`null` when new), the account, and every field.
 * Result: an editor state a test can construct without touching Compose.
 * Changelog: 2026-08-24 — Created for issue 6.3.
 *
 * @property holdingId the row being edited, or `null` when this is a new holding.
 * @property name what the user has typed for the name.
 * @property assetClass the chosen class; defaulted from the account's type via
 *   `AssetClass.defaultFor` so the common case needs no thought.
 * @property unitPrice the price per unit as typed, in rupees; blank means "not valued yet".
 * @property pricedOn the ISO day the price was observed; blank alongside a blank price.
 * @property lots the lot rows being edited beneath the holding.
 * @property fieldError which field the last save refused, or `null`.
 */
@Immutable
data class HoldingEditorState(
    val holdingId: String? = null,
    val name: String = "",
    val assetClass: AssetClass = AssetClass.EQUITY,
    val unitPrice: String = "",
    val pricedOn: String = "",
    val priceKey: String = "",
    val lots: List<LotEditorState> = emptyList(),
    val fieldError: String? = null,
)

/**
 * One lot row in the editor, as typed (issue 6.3).
 *
 * @property lotId the row being edited, or `null` when new.
 * @property kind bought, sold, or paid out — the only source of the cash direction.
 * @property day the ISO day the money moved.
 * @property units units moved, as typed; blank for an income lot.
 * @property amount the cash moved, as typed, in rupees.
 */
@Immutable
data class LotEditorState(
    val lotId: String? = null,
    val kind: LotKind = LotKind.BUY,
    val day: String = "",
    val units: String = "",
    val amount: String = "",
)

/**
 * What the holdings screen can be asked to do (issue 6.3; ARC-004).
 *
 * Why:  a sealed interface so the `when` in the ViewModel is exhaustive — a new event cannot be
 *       added without somewhere to handle it.
 * Result: events travel up, state comes down, and the composable holds nothing.
 * Changelog: 2026-08-24 — Created for issue 6.3.
 */
sealed interface HoldingsEvent {
    /** Open the editor on a new holding. */
    data object AddHolding : HoldingsEvent

    /** Open the editor on an existing holding. */
    data class EditHolding(val holdingId: String) : HoldingsEvent

    /** Close the editor without writing. */
    data object CancelEditor : HoldingsEvent

    /** Write what the editor holds. */
    data object SaveEditor : HoldingsEvent

    /** Soft-delete a holding and its lots. */
    data class DeleteHolding(val holdingId: String) : HoldingsEvent

    /** The name field changed. */
    data class NameChanged(val value: String) : HoldingsEvent

    /** The asset class changed. */
    data class AssetClassChanged(val value: AssetClass) : HoldingsEvent

    /** The unit price field changed. */
    data class UnitPriceChanged(val value: String) : HoldingsEvent

    /** The pricing-date field changed. */
    data class PricedOnChanged(val value: String) : HoldingsEvent

    /** The price-key field changed (issue 6.7). */
    data class PriceKeyChanged(val value: String) : HoldingsEvent

    /** Append an empty lot row to the editor. */
    data object AddLot : HoldingsEvent

    /** A lot row changed. */
    data class LotChanged(val index: Int, val lot: LotEditorState) : HoldingsEvent

    /** Remove a lot row from the editor. */
    data class RemoveLot(val index: Int) : HoldingsEvent

    /** Clear the error banner. */
    data object DismissError : HoldingsEvent
}
