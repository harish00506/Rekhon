package com.aicfo.feature.accounts

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aicfo.core.common.AppError
import com.aicfo.core.common.Err
import com.aicfo.core.common.Ok
import com.aicfo.core.common.Result
import com.aicfo.core.common.toAppError
import com.aicfo.core.model.AssetClass
import com.aicfo.core.model.Money
import com.aicfo.core.model.MoneyFormatter
import com.aicfo.core.model.PriceKey
import com.aicfo.core.model.Quantity
import com.aicfo.data.repository.HoldingDraft
import com.aicfo.data.repository.InvestmentRepository
import com.aicfo.data.repository.LotDraft
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.math.BigDecimal
import java.math.RoundingMode
import javax.inject.Inject

/**
 * Holds the holdings screen's state (issue 6.3; §11, ARC-003, ARC-004).
 *
 * Why:  the screen changes from underneath itself for two reasons — the user edits a holding or a
 *       lot, and the engine re-prices everything when either lands — so the list is a `Flow`
 *       collected on `viewModelScope` (ARC-006, so it dies with the screen) rather than a one-off
 *       read that would be stale after the first save.
 *
 *       **Parsing lives here, not in the repository and not in the composable.** The editor holds
 *       text because a half-typed amount is not a number yet; turning that text into `Money` and
 *       `Quantity` is the one step that can fail on input the user can fix, so it happens where a
 *       failure can be turned into a field error rather than a crash or a silent zero.
 * What: exposes [uiState] and handles [HoldingsEvent]s.
 * Result: a screen whose every state is reachable and assertable without a device.
 * Changelog: 2026-08-24 — Created for issue 6.3.
 *
 * **The ViewModel sees domain models and engine results, never a Room entity** (ARC-005).
 *
 * Input:  [repository] — the holdings store; [savedState] — carries the route's `accountId`.
 * Output: an observable screen state.
 */
@HiltViewModel
class HoldingsViewModel
    @Inject
    constructor(
        private val repository: InvestmentRepository,
        savedState: SavedStateHandle,
    ) : ViewModel() {
        private val accountId: String = savedState.get<String>(ACCOUNT_ID_KEY).orEmpty()

        private val _uiState = MutableStateFlow(HoldingsUiState(accountId = accountId))

        /**
         * The screen's state.
         * Result: emits the current [HoldingsUiState] and every update. Read-only to callers —
         *         `asStateFlow()` prevents a composable from writing to it (ARC-004).
         */
        val uiState: StateFlow<HoldingsUiState> = _uiState.asStateFlow()

        init {
            observeHoldings()
        }

        /**
         * Handles one event from the screen.
         * Why:    an exhaustive `when`, so a new [HoldingsEvent] cannot be added without somewhere
         *         to handle it.
         * Result: the state advances, or a write is launched.
         * Input:  [event]. Output: none.
         * Changelog: 2026-08-24 — Created for issue 6.3.
         */
        fun onEvent(event: HoldingsEvent) {
            when (event) {
                HoldingsEvent.AddHolding -> openEditor(null)
                is HoldingsEvent.EditHolding -> openEditor(event.holdingId)
                HoldingsEvent.CancelEditor -> _uiState.update { it.copy(editor = null) }
                HoldingsEvent.SaveEditor -> save()
                is HoldingsEvent.DeleteHolding -> delete(event.holdingId)
                HoldingsEvent.DismissError -> _uiState.update { it.copy(errorCode = null) }
                else -> onEditorEvent(event)
            }
        }

        /**
         * Handles the events that only change what is in the open editor.
         * Why:    split from [onEvent] so neither `when` is past detekt's complexity ceiling, and on
         *         a real seam rather than an arbitrary one: everything here is a keystroke that
         *         cannot fail, everything there opens, closes, writes or deletes.
         * Result: the editor advances; the list is untouched.
         * Input:  [event] — one of the editing events. Output: none.
         * Changelog: 2026-08-24 — Created for issue 6.3.
         */
        private fun onEditorEvent(event: HoldingsEvent) {
            when (event) {
                is HoldingsEvent.NameChanged -> editEditor { it.copy(name = event.value) }
                is HoldingsEvent.AssetClassChanged -> editEditor { it.copy(assetClass = event.value) }
                is HoldingsEvent.UnitPriceChanged -> editEditor { it.copy(unitPrice = event.value) }
                is HoldingsEvent.PricedOnChanged -> editEditor { it.copy(pricedOn = event.value) }
                is HoldingsEvent.PriceKeyChanged -> editEditor { it.copy(priceKey = event.value) }
                HoldingsEvent.AddLot -> editEditor { it.copy(lots = it.lots + LotEditorState()) }
                is HoldingsEvent.LotChanged ->
                    editEditor { state ->
                        state.copy(
                            lots = state.lots.mapIndexed { i, lot -> if (i == event.index) event.lot else lot },
                        )
                    }
                is HoldingsEvent.RemoveLot ->
                    editEditor { state ->
                        state.copy(lots = state.lots.filterIndexed { i, _ -> i != event.index })
                    }
                else -> Unit
            }
        }

        /**
         * Keeps the priced list in step with the store.
         * Why:    a read failure clears the list and raises the banner rather than leaving stale
         *         figures on screen — an out-of-date value is worse than none, because nothing on
         *         the row says it is old.
         * Result: [uiState] carries the account's holdings, already priced by the engine.
         * Input:  none. Output: none (collects on `viewModelScope`).
         * Changelog: 2026-08-24 — Created for issue 6.3.
         */
        private fun observeHoldings() {
            repository.observeForAccount(accountId)
                .onEach { priced ->
                    _uiState.update { it.copy(holdings = priced, isLoading = false, errorCode = null) }
                }
                .catch { cause ->
                    _uiState.update {
                        it.copy(holdings = emptyList(), isLoading = false, errorCode = cause.toAppError().code)
                    }
                }
                .launchIn(viewModelScope)
        }

        /**
         * Opens the editor, empty for a new holding or filled from an existing one.
         * Why:    the asset class defaults from the account's own type via [AssetClass.defaultFor],
         *         so adding an equity fund to a broker account needs no thought — and the field is
         *         still there, because that default is a guess and the column exists precisely so
         *         the user can overrule it (ADR-0027).
         * Result: `editor` is populated.
         * Input:  [holdingId] — `null` for a new holding. Output: none.
         * Changelog: 2026-08-24 — Created for issue 6.3.
         */
        private fun openEditor(holdingId: String?) {
            if (holdingId == null) {
                _uiState.update { it.copy(editor = HoldingEditorState()) }
                return
            }
            viewModelScope.launch {
                val holding = (repository.find(holdingId) as? Ok)?.value
                val lots = (repository.lotsOf(holdingId) as? Ok)?.value.orEmpty()
                if (holding == null) {
                    _uiState.update { it.copy(errorCode = AppError.NotFound.code) }
                    return@launch
                }
                _uiState.update {
                    it.copy(
                        editor =
                            HoldingEditorState(
                                holdingId = holding.id,
                                name = holding.name,
                                assetClass = holding.assetClass,
                                unitPrice = holding.unitPrice?.let(MoneyFormatter::format).orEmpty(),
                                pricedOn = holding.pricedOnIsoDate.orEmpty(),
                                priceKey = holding.priceKey?.value.orEmpty(),
                                lots =
                                    lots.map { lot ->
                                        LotEditorState(
                                            lotId = lot.id,
                                            kind = lot.kind,
                                            day = lot.transactedOnIsoDate,
                                            units = lot.quantity.plain(),
                                            amount = MoneyFormatter.format(lot.amount),
                                        )
                                    },
                            ),
                    )
                }
            }
        }

        /**
         * Writes the editor's contents: the holding first, then every lot beneath it.
         * Why:    the holding first because a lot needs its id, and a lot written against a holding
         *         that failed to save would be an orphan no screen could reach.
         *
         *         Parsing failures become a `fieldError` on the editor rather than an error banner:
         *         the user is looking at the field they mistyped, and a banner would send them
         *         somewhere else to read about it.
         * Result: the editor closes on success; on failure it stays open, naming the field.
         * Input:  none. Output: none.
         * Changelog: 2026-08-24 — Created for issue 6.3.
         */
        private fun save() {
            val editor = _uiState.value.editor ?: return
            val draft = editor.toDraft(accountId)
            if (draft == null) {
                setFieldError(FIELD_HOLDING)
                return
            }
            val lots = editor.lots.map { it.toDraftOrNull() }
            if (lots.any { it == null }) {
                setFieldError(FIELD_LOT)
                return
            }
            viewModelScope.launch {
                when (val written = repository.saveHolding(draft, editor.holdingId)) {
                    is Err -> _uiState.update { it.copy(errorCode = written.error.code) }
                    is Ok -> {
                        val failure = writeLots(written.value, editor, lots.filterNotNull())
                        _uiState.update {
                            if (failure == null) it.copy(editor = null) else it.copy(errorCode = failure.code)
                        }
                    }
                }
            }
        }

        /**
         * Writes every lot in the editor against a saved holding.
         * Result: the first failure, or `null` when all of them landed.
         * Input:  [holdingId] — the id the holding was written under; [editor]; [drafts].
         * Output: [AppError]?.
         * Changelog: 2026-08-24 — Created for issue 6.3.
         */
        private suspend fun writeLots(
            holdingId: String,
            editor: HoldingEditorState,
            drafts: List<LotDraft>,
        ): AppError? {
            for ((index, draft) in drafts.withIndex()) {
                val written = repository.saveLot(draft.copy(holdingId = holdingId), editor.lots[index].lotId)
                if (written is Err) return written.error
            }
            return null
        }

        /**
         * Soft-deletes a holding and everything under it.
         * Result: the row leaves the list on the next emission; nothing is erased (DB-003).
         * Input:  [holdingId]. Output: none.
         * Changelog: 2026-08-24 — Created for issue 6.3.
         */
        private fun delete(holdingId: String) {
            viewModelScope.launch {
                val outcome: Result<Unit, AppError> = repository.deleteHolding(holdingId)
                if (outcome is Err) _uiState.update { it.copy(errorCode = outcome.error.code) }
            }
        }

        /**
         * Applies [change] to the open editor, clearing any field error.
         *
         * Why: every caller is the user typing, and typing in a refused form is them fixing it — so
         *      the error goes as soon as they touch anything. Setting an error therefore cannot go
         *      through here, which is what [setFieldError] is for: routing it through this would
         *      clear the error in the same expression that set it.
         */
        private fun editEditor(change: (HoldingEditorState) -> HoldingEditorState) {
            _uiState.update { state ->
                state.editor?.let { state.copy(editor = change(it).copy(fieldError = null)) } ?: state
            }
        }

        /** Marks the open editor as refused, naming [field]. Leaves everything the user typed. */
        private fun setFieldError(field: String) {
            _uiState.update { state ->
                state.editor?.let { state.copy(editor = it.copy(fieldError = field)) } ?: state
            }
        }

        companion object {
            /** The route argument this ViewModel reads, matching `CfoRoute.Holdings`. */
            const val ACCOUNT_ID_KEY = "accountId"

            /** The editor's own field-error codes, kept out of [AppError] because they never leave. */
            const val FIELD_HOLDING = "holding"
            const val FIELD_LOT = "lot"
        }
    }

/**
 * Turns the editor's text into a [HoldingDraft].
 * Why:    the one place text becomes money. A blank price is legitimate — it means "not valued
 *         yet" — but a price the user typed and got wrong is not, so blank and unparseable are
 *         deliberately different outcomes here (P-03).
 * Result: the draft, or `null` when the name is empty, the price will not parse, or the price and
 *         its date disagree about whether they exist.
 * Input:  the receiver; [accountId] — the account the holding sits in. Output: [HoldingDraft]?.
 * Changelog: 2026-08-24 — Created for issue 6.3.
 */
internal fun HoldingEditorState.toDraft(accountId: String): HoldingDraft? {
    val hasPrice = unitPrice.isNotBlank()
    val hasDay = pricedOn.isNotBlank()
    val parsed = if (hasPrice) MoneyFormatter.parse(unitPrice) else null
    val priceIsUsable = !hasPrice || (parsed != null && parsed > Money.ZERO)
    // A blank key means "I price this by hand" and is the default. A key that is present but
    // malformed is refused rather than dropped: silently saving `null` would leave a holding that
    // looks linked to a market and never updates, which is the one outcome worse than not offering
    // the field at all.
    val key = priceKey.trim().takeIf { it.isNotEmpty() }
    val parsedKey = key?.let { runCatching { PriceKey(it) }.getOrNull() }
    val keyIsUsable = key == null || parsedKey != null
    // Named rather than inlined into the guard: "a price and its date exist together and the price
    // parses" is one idea, and detekt is right that four clauses in a row stop reading as one.
    val priceIsWellFormed = hasPrice == hasDay && priceIsUsable
    return if (name.isBlank() || !priceIsWellFormed || !keyIsUsable) {
        null
    } else {
        HoldingDraft(
            accountId = accountId,
            name = name.trim(),
            assetClass = assetClass,
            unitPrice = parsed,
            pricedOnIsoDate = pricedOn.takeIf { hasDay },
            priceKey = parsedKey,
        )
    }
}

/**
 * Turns one lot row's text into a [LotDraft].
 * Result: the draft, or `null` when the day, the units or the amount will not parse. A blank unit
 *         count is [Quantity.ZERO], which is what an income lot is.
 * Input:  the receiver. Output: [LotDraft]?.
 * Changelog: 2026-08-24 — Created for issue 6.3.
 */
internal fun LotEditorState.toDraftOrNull(): LotDraft? {
    val cash = MoneyFormatter.parse(amount) ?: return null
    if (cash <= Money.ZERO) return null
    val quantity = if (units.isBlank()) Quantity.ZERO else units.toQuantityOrNull() ?: return null
    if (quantity < Quantity.ZERO) return null
    if (day.isBlank()) return null
    return LotDraft(
        // Replaced by the caller once the holding has an id; a draft cannot know it before then.
        holdingId = "",
        kind = kind,
        transactedOnIsoDate = day.trim(),
        quantity = quantity,
        amount = cash,
    )
}

/**
 * Parses a typed unit count into nano-units.
 * Why:    `BigDecimal`, not `toDouble()`, for the reason money is never floating point: 0.001 units
 *         of a fund has no exact binary form, and a portfolio built from rounded unit counts drifts
 *         against the statement it was typed from.
 * Result: the quantity, or `null` when the text is not a non-negative decimal.
 * Input:  the receiver — as typed. Output: [Quantity]?.
 * Changelog: 2026-08-24 — Created for issue 6.3.
 */
private fun String.toQuantityOrNull(): Quantity? =
    try {
        Quantity(
            BigDecimal(trim())
                .multiply(BigDecimal.valueOf(Quantity.SCALE))
                // setScale, not toBigInteger(): `BigInteger.longValueExact` is API 31 and this app
                // ships to API 26, so the first spelling compiled and would have thrown on a real
                // phone. HALF_EVEN rounds anything finer than a nano-unit - ten or more decimal
                // places, which no instrument quotes - rather than truncating it.
                .setScale(0, RoundingMode.HALF_EVEN)
                .longValueExact(),
        )
    } catch (_: NumberFormatException) {
        null
    } catch (_: ArithmeticException) {
        null
    }

/** Result: the units as a plain decimal string the editor can round-trip. Output: [String]. */
private fun Quantity.plain(): String =
    BigDecimal.valueOf(nano)
        .divide(BigDecimal.valueOf(Quantity.SCALE))
        .stripTrailingZeros()
        .toPlainString()
