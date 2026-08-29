package com.aicfo.widget

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Column
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import com.aicfo.core.model.Money

/**
 * The whole home-screen widget, drawn from a cached snapshot (issue 5.5; §5.2, §35, P-01/P-03/P-04).
 *
 * Why:  **a pure function of its argument, with no injection, no repository and no suspend call.**
 *       That is not tidiness — it is the only way this can work. `CoreModule.provideDatabase`
 *       throws while the app is locked (SEC-002), and a home screen is mostly looked at locked, so
 *       a widget that reached for a figure at draw time would blank out or crash exactly when it is
 *       most visible. Everything that computes happens earlier, in `:app`'s `WidgetRefreshWorker`;
 *       this only renders what that left behind. It also means the widget is testable on the JVM
 *       with no AppWidgetHost — which is what `CfoWidgetContentTest` uses.
 * What: Safe-to-Spend as the headline, net worth beneath it, each with its label, and each masked
 *       when the blur is on.
 * Result: two figures a user can read at a glance, or two pending labels — never a `₹0.00` no
 *       engine produced (P-03).
 * Changelog: 2026-08-17 — Created for issue 5.5.
 *
 * Theming is `GlanceTheme`'s: it follows the system light/dark setting and, from API 31, the
 * device's dynamic colours. The widget deliberately does **not** reuse `CfoTheme` — Glance renders
 * through `RemoteViews` in the launcher's process and cannot read a Compose theme, and a
 * hand-copied palette would drift from the app's the first time a token changed.
 *
 * Input:  [snapshot] — the cached figures and the blur flag; [context] — for `getString`, since
 *         Glance has no `stringResource`; [modifier] — the tap action and sizing, supplied by
 *         [CfoWidget] so a test can compose this with none and still assert every string.
 * Output: the composed widget.
 */
@Composable
fun CfoWidgetContent(
    snapshot: WidgetSnapshot,
    context: Context,
    modifier: GlanceModifier = GlanceModifier.fillMaxSize(),
) {
    Column(
        modifier =
            modifier
                .background(GlanceTheme.colors.widgetBackground)
                .padding(WIDGET_PADDING),
        verticalAlignment = Alignment.Vertical.CenterVertically,
    ) {
        Figure(
            label = context.getString(R.string.widget_safe_to_spend_label),
            amount = snapshot.safeToSpend,
            blurred = snapshot.blurred,
            context = context,
            headline = true,
        )
        Spacer(GlanceModifier.height(FIGURE_GAP))
        Figure(
            label = context.getString(R.string.widget_net_worth_label),
            amount = snapshot.netWorth,
            blurred = snapshot.blurred,
            context = context,
            headline = false,
        )
    }
}

/**
 * One labelled figure — the widget's only repeated shape (issue 5.5).
 *
 * Why:  Safe-to-Spend and net worth differ in nothing but size and wording, and writing the
 *       absent-and-blurred logic twice is how one of them ends up leaking a digit after a later
 *       edit. There is exactly one place here that turns a [Money] into text.
 * What: the label above, and beneath it the masked amount, the formatted amount, or the pending
 *       label — in that order of precedence.
 * Result: a label and a figure. The choice of *what* the figure says is [amountText]'s, not this
 *       function's — it is the one decision in the module that can leak an amount, so it lives in a
 *       plain function its own test can exhaust.
 * Input:  [label] — the resolved string; [amount] — paise or `null`; [blurred] — P-01's flag;
 *         [context] — for the pending string; [headline] — larger and bolder when `true`.
 * Output: the composed rows.
 * Changelog: 2026-08-17 — Created for issue 5.5.
 */
@Composable
private fun Figure(
    label: String,
    amount: Money?,
    blurred: Boolean,
    context: Context,
    headline: Boolean,
) {
    Text(
        text = label,
        style =
            TextStyle(
                color = GlanceTheme.colors.onSurfaceVariant,
                fontSize = LABEL_TEXT,
            ),
    )
    Text(
        text = amountText(amount, blurred, context.getString(R.string.widget_pending)),
        style =
            TextStyle(
                color = GlanceTheme.colors.onSurface,
                fontSize = if (headline) HEADLINE_TEXT else SECONDARY_TEXT,
                fontWeight = if (headline) FontWeight.Bold else FontWeight.Medium,
            ),
    )
}

private val WIDGET_PADDING = 12.dp
private val FIGURE_GAP = 8.dp
private val HEADLINE_TEXT = 22.sp
private val SECONDARY_TEXT = 15.sp
private val LABEL_TEXT = 12.sp
