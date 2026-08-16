package com.aicfo.core.designsystem.component

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import com.aicfo.core.designsystem.R
import com.aicfo.core.designsystem.theme.CfoAmountTextStyle
import com.aicfo.core.designsystem.theme.CfoTheme
import com.aicfo.core.model.Money

/**
 * Renders a monetary amount — the one component every screen in this app will use.
 *
 * Why:  three things must be true of every amount on screen, and each is easy to get wrong once
 *       and then everywhere. It must be formatted the Indian way (₹1,23,456.78 — done by
 *       [MoneyFormatter] from issue 1.2, never re-implemented here). It must use tabular figures
 *       so a column of balances aligns. And it must not rely on colour alone to say whether money
 *       came in or went out (**P-02**) — so the sign is always rendered, and colour only
 *       reinforces it. A red number with no `−` is meaningless in greyscale, to a colour-blind
 *       user, and under the privacy-blur mode.
 * What: formats a [Money], prefixes an explicit sign for non-zero values, and colours it with the
 *       `positive`/`negative` finance roles.
 * Result: one canonical way to show money, accessible by construction.
 * Changelog: 2026-07-25 — Created for issue 1.8.
 *
 * **Copy stays out of the design system.** [contentDescription] is a parameter because only the
 * calling screen knows whether this figure is "spent", "received" or "balance", and that wording
 * belongs in the feature's `strings.xml` (§21.6). Without one, the screen reader falls back to the
 * formatted amount, which is terse but never wrong.
 *
 * Changelog: 2026-08-16 — Issue 5.3: honours [LocalPrivacyBlur].
 *
 * **The privacy blur is read here, not passed in** (issue 5.3). Fourteen call sites render an
 * amount through this component and none of them has to know the feature exists — which is the
 * point, because the one screen somebody forgot to wire would be the one still showing a balance in
 * a meeting. The caller's [contentDescription] is **discarded while blurred**: a screen reader
 * announcing "Safe to spend this month, ₹34,600" would read out loud exactly what the user just
 * hid, and the caller's wording usually contains the figure.
 *
 * Input:  [amount] — the value, `Long` paise; [modifier]; [contentDescription] — localised
 *         wording from the caller, used only when the blur is off; [showSign] — false only where a
 *         sign would be noise, such as a column that is already labelled "spending"; [textAlign].
 * Output: the rendered amount, or its mask.
 */
@Composable
fun CfoAmountText(
    amount: Money,
    modifier: Modifier = Modifier,
    contentDescription: String? = null,
    showSign: Boolean = true,
    textAlign: TextAlign? = null,
) {
    val blurred = LocalPrivacyBlur.current
    val formatted = maskedAmount(amount)
    val text =
        when {
            !showSign -> formatted
            // No leading "+" on a mask: it would be the one glyph that varies with the value, and a
            // masked column where some rows gained a character would leak which figures are inflows.
            blurred -> formatted
            amount.minor > 0L -> "+$formatted"
            // A negative amount already carries its own minus from the formatter.
            else -> formatted
        }
    val announced = if (blurred) stringResource(R.string.cfo_amount_hidden) else contentDescription ?: text
    Text(
        text = text,
        style = CfoAmountTextStyle,
        color = amountColor(amount),
        textAlign = textAlign,
        // An amount must never wrap. At 200% font the screenshot test caught "-₹2,450.0" breaking
        // with the final "0" on the next line — which reads as a completely different number.
        // A truncated amount is obviously incomplete; a wrapped one is quietly wrong.
        maxLines = 1,
        softWrap = false,
        modifier =
            modifier.semantics {
                this.contentDescription = announced
            },
    )
}

/**
 * Picks the colour for an amount.
 * Why:    kept separate so the rule is stated once — and so it is obvious that zero is neutral.
 *         Colouring a zero balance green or red implies a judgement the app has not made.
 * Result: the `positive`/`negative` role, or the default text colour for zero.
 * Input:  [amount] — the value. Output: a [Color] from the theme, never a literal.
 * Changelog: 2026-07-25 — Created for issue 1.8.
 */
@Composable
private fun amountColor(amount: Money): Color =
    when {
        amount.minor > 0L -> CfoTheme.extendedColors.positive
        amount.minor < 0L -> CfoTheme.extendedColors.negative
        else -> MaterialTheme.colorScheme.onSurface
    }
