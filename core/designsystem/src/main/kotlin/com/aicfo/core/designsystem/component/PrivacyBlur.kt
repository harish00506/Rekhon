package com.aicfo.core.designsystem.component

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import com.aicfo.core.model.Money
import com.aicfo.core.model.MoneyFormatter

/**
 * Whether every amount on screen is currently hidden (issue 5.3; §23, FR-PRIV-*, P-01).
 *
 * Why:  a `CompositionLocal` rather than a parameter threaded through every screen, for the reason
 *       `LocalCfoExtendedColors` beside it gives: the alternative is a `Boolean` added to forty
 *       call signatures, and the one screen somebody forgets is the one that keeps showing the
 *       user's balance in a meeting. A local cannot be forgotten — it is read inside the two
 *       functions that render money, and every caller inherits it.
 *
 *       **`static`, because it changes only when the user taps the toggle.** A non-static local
 *       would invalidate every reader on any recomposition, and amounts are on the hottest screens
 *       in the app.
 *
 *       **A plain `Boolean`, not a settings object.** `:core:designsystem` depends on `:core:model`
 *       and nothing else (ARC-001); reading `SettingsStore` here would drag `:core:datastore` into
 *       every module that draws a button. `:app` reads the setting and provides the value.
 * Result: `false` by default — a screen rendered outside the app's provider (a preview, a
 *       screenshot test, a Compose preview in the IDE) shows real figures, which is the honest
 *       default for a *display* preference. Defaulting to hidden would make every unprovided
 *       surface look broken.
 * Changelog: 2026-08-16 — Created for issue 5.3.
 */
val LocalPrivacyBlur = staticCompositionLocalOf { false }

/**
 * Formats an amount, or masks it when the privacy blur is on (issue 5.3).
 *
 * Why:  amounts reach the screen by **two** paths in this app, and only one of them is a component.
 *       [CfoAmountText] renders fourteen of them; the rest are `MoneyFormatter.format(x)` handed to
 *       a `stringResource(...)` placeholder — "Received %1$s · Spent %2$s", "%1$s of %2$s spent" —
 *       because a sentence cannot contain a composable. Blurring only the component would leave the
 *       cash-flow line, the budget totals and the whole Safe-to-Spend breakdown perfectly readable,
 *       which is not what "hides all amounts on screen" means.
 *
 *       So this is the drop-in for those call sites: same shape as `MoneyFormatter.format`, one
 *       word longer, and it reads the local. **Composable rather than a plain function**, which is
 *       the whole point — a non-composable helper could not see [LocalPrivacyBlur] and would have
 *       to take the flag as an argument, putting us back to threading a `Boolean` through forty
 *       call sites.
 * What: delegates to [MoneyFormatter] when the blur is off, and returns [maskOf] when it is on.
 * Result: the string to render. Blur off, it is byte-identical to what the screen showed before —
 *       so the normal case is provably unchanged.
 * Input:  [amount] — the value, `Long` paise (MNY-001).
 * Output: the formatted amount, or its mask.
 * Changelog: 2026-08-16 — Created for issue 5.3.
 */
@Composable
@ReadOnlyComposable
fun maskedAmount(amount: Money): String =
    if (LocalPrivacyBlur.current) maskOf(amount) else MoneyFormatter.format(amount)

/**
 * The masked form of an amount (issue 5.3; §23).
 *
 * Why:  two decisions, and both are the difference between a blur that works and one that looks
 *       like it does.
 *
 *       **The run of dots is a fixed length, never the number's own.** Masking ₹450 as `₹•••` and
 *       ₹4,50,000 as `₹•,••,•••` would hide the digits and leak the order of magnitude — which is
 *       most of what someone reading over a shoulder wants, and all of what makes a salary or a
 *       balance sensitive. Every amount masks to the same width, so a column of them says nothing
 *       about any of them.
 *
 *       **The sign survives.** Direction is not the secret — nobody is embarrassed that a
 *       transaction was an outflow — and [CfoAmountText] colours by sign, so dropping it would
 *       leave colour as the only signal of direction, which its own doc comment records as the
 *       thing this component exists to prevent (P-02, and greyscale/colour-blind users). The
 *       currency symbol stays for the same reason: it keeps the masked value legible *as an
 *       amount* rather than as an error.
 * Result: `₹•••••••` or `-₹•••••••` — identical for every magnitude.
 * Input:  [amount] — read only for its sign, never its digits.
 * Output: the mask.
 * Changelog: 2026-08-16 — Created for issue 5.3.
 */
internal fun maskOf(amount: Money): String {
    val sign = if (amount.minor < 0L) "-" else ""
    return "$sign$RUPEE_SIGN$MASK_DOTS"
}

/** Matches `MoneyFormatter`'s symbol, so a masked column and a real one line up. */
private const val RUPEE_SIGN = "₹"

/**
 * The mask body. Seven dots: wide enough that a masked ₹1,23,456.78 does not read as a short number,
 * narrow enough not to wrap at 200% font, where [CfoAmountText] renders with `softWrap = false`.
 */
private const val MASK_DOTS = "•••••••"
