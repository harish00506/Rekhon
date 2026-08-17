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
 * Why:  kept as a name in this file — the fourteen `CfoAmountText` call sites and this module's own
 *       tests read as blur code, not as currency formatting, and renaming them at every site would
 *       be a large diff for no behaviour. What it no longer does is *define* the mask.
 *
 *       **The definition moved to [MoneyFormatter.mask] (issue 5.5).** The home-screen widget masks
 *       the same two amounts from a Glance module that cannot see [LocalPrivacyBlur] and has no
 *       business depending on Material3. Two definitions of "how wide is the mask" would be two
 *       definitions of how much the blur leaks — and the fixed width is the entire point (ADR-0022).
 * Result: `₹•••••••` or `-₹•••••••` — identical for every magnitude, and now identical to what the
 *       widget renders, by construction rather than by inspection.
 * Input:  [amount] — read only for its sign, never its digits.
 * Output: the mask.
 * Changelog: 2026-08-16 — Created for issue 5.3.
 *            2026-08-17 — Issue 5.5: delegates to [MoneyFormatter.mask]; behaviour unchanged.
 */
internal fun maskOf(amount: Money): String = MoneyFormatter.mask(amount)
