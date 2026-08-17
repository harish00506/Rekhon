package com.aicfo.widget

import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import com.aicfo.core.model.Money
import com.aicfo.core.model.MoneyFormatter

/**
 * Everything the home-screen widget draws, as one value (issue 5.5; §5.2, §35, P-03/P-04).
 *
 * Why:  the widget renders from a **cache**, and this is the cache's shape. It exists as a named
 *       type rather than as three `Preferences` lookups scattered through the composable for the
 *       reason `DashboardUiState` exists: a surface must not be able to render a half-updated mix
 *       of an old figure and a new flag, and a pure value is the only thing a unit test can hand
 *       to the renderer without an Android widget host in the way.
 *
 *       **Both amounts are nullable, and that is load-bearing (P-03).** `null` means "no figure has
 *       been computed for this profile yet" — a brand-new install, or a profile with no income
 *       basis at all, which `SafeToSpendRepository` answers with `null` rather than with a zero. A
 *       widget that rendered that as `₹0.00` would be stating a number no engine produced, on the
 *       most glanceable surface the app has. It renders a pending label instead.
 *
 *       **There is deliberately no "last updated" timestamp.** Rendering one would need the profile
 *       time zone to be correct (TIM-001), which means reading settings on the render path, which
 *       is what this whole design avoids — and it would make the refresh non-idempotent, since
 *       every run would write a new value even when both figures were unchanged. The widget
 *       refreshes on every app launch and every six hours; a staleness label would cost more than
 *       it tells.
 * What: the two figures and whether the privacy blur is on.
 * Result: the single argument to [CfoWidgetContent].
 * Changelog: 2026-08-17 — Created for issue 5.5.
 *
 * Input:  [safeToSpend] — this month's Safe-to-Spend, `Long` paise (MNY-001), may be negative,
 *         `null` when uncomputed; [netWorth] — assets minus liabilities, same rules;
 *         [blurred] — whether to mask every amount (P-01).
 * Output: an immutable value.
 */
data class WidgetSnapshot(
    val safeToSpend: Money? = null,
    val netWorth: Money? = null,
    val blurred: Boolean = false,
)

/**
 * The Glance preference keys the widget's state is stored under (issue 5.5).
 *
 * Why:  the app writes these and the composable reads them, from two different modules — so they
 *       have to be named in one place or a typo becomes a widget that silently renders nothing.
 *       Absence carries nullability, not a sentinel: there is no paise value that could stand for
 *       "not computed", because `0` and every negative are real answers.
 * Result: the vocabulary of the widget cache.
 * Changelog: 2026-08-17 — Created for issue 5.5.
 */
object WidgetKeys {
    /** Safe-to-Spend in paise (MNY-001). Absent = not computed yet. */
    val SafeToSpendMinor = longPreferencesKey("safe_to_spend_minor")

    /** Net worth in paise (MNY-001). Absent = not computed yet. */
    val NetWorthMinor = longPreferencesKey("net_worth_minor")

    /**
     * Whether amounts are masked (P-01).
     *
     * Written on its own, by a watcher that touches no database, so turning the blur on hides the
     * widget even while the app is locked and the figures could not be recomputed. See ADR-0024.
     */
    val Blurred = booleanPreferencesKey("blurred")
}

/**
 * Reads the widget cache out of Glance's preference store (issue 5.5).
 *
 * Why:  one conversion, so the composable never touches a key directly and the "absent means
 *       uncomputed" rule is written down once rather than at every read.
 * What: maps each key to its field, wrapping the paise values in [Money] and leaving a missing key
 *       as `null`.
 * Result: the [WidgetSnapshot] to render. Empty preferences — a widget just dropped on the home
 *       screen, before any refresh has run — yield the all-absent snapshot, which renders as
 *       pending rather than as zero.
 * Input:  the receiver — Glance's current state.
 * Output: [WidgetSnapshot].
 * Changelog: 2026-08-17 — Created for issue 5.5.
 */
fun Preferences.toWidgetSnapshot(): WidgetSnapshot =
    WidgetSnapshot(
        safeToSpend = this[WidgetKeys.SafeToSpendMinor]?.let(::Money),
        netWorth = this[WidgetKeys.NetWorthMinor]?.let(::Money),
        blurred = this[WidgetKeys.Blurred] == true,
    )

/**
 * Turns one cached figure into the exact text the widget shows (issue 5.5; P-01/P-03).
 *
 * Why:  **this is the only place in the module that can put a digit on a home screen, and it is a
 *       plain function so that fact is provable.** Left inside the composable, the one assertion
 *       that matters — "when the blur is on, nothing readable as a number is emitted" — could only
 *       be checked through a widget host, against whichever strings a harness happens to expose.
 *       Here it is checked directly, over every combination, by `WidgetTextTest`.
 * What: three cases in a fixed order of precedence.
 * Result: **the blur wins over the figure, and the figure wins over zero.** Testing [blurred] first
 *       means a masked amount can never fall through to a formatted one; returning [pending] for
 *       `null` means an uncomputed figure is never rendered as an amount at all — the app does not
 *       claim ₹0.00 when what it has is no answer (P-03).
 * Input:  [amount] — paise (MNY-001) or `null` when no engine has produced one; [blurred] — the
 *         privacy flag; [pending] — the already-resolved `widget_pending` string, passed in so this
 *         stays free of Android and of resource lookup.
 * Output: the string to draw.
 * Changelog: 2026-08-17 — Created for issue 5.5.
 */
fun amountText(
    amount: Money?,
    blurred: Boolean,
    pending: String,
): String =
    when {
        amount == null -> pending
        blurred -> MoneyFormatter.mask(amount)
        else -> MoneyFormatter.format(amount)
    }
