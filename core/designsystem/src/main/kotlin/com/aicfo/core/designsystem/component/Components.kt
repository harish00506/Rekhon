package com.aicfo.core.designsystem.component

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.aicfo.core.designsystem.theme.CfoDimens

/*
 * The shared components every screen builds from (SRS §24, §21.6, ACC-*).
 *
 * Why:  a design system is only worth having if using it is easier than not. These are kept to the
 *       handful of shapes the app actually repeats — a card, two button weights, a list row — so
 *       there is nothing here nobody uses. Each takes its padding, radius and minimum size from
 *       [CfoDimens], which is what makes "no hardcoded dimensions" (§21.6) a thing a reviewer can
 *       check rather than a thing they must remember.
 * What: CfoCard, CfoButton, CfoSecondaryButton, CfoListRow.
 * Result: consistent spacing and touch targets without every screen re-deciding them.
 * Changelog: 2026-07-25 — Created for issue 1.8.
 *
 * Every clickable here is at least [CfoDimens.minTouchTarget] (48dp) tall. That is the most
 * commonly failed accessibility rule in review, so it is encoded in the component rather than left
 * to each call site.
 */

/**
 * The standard surface for a block of content.
 * Why:    cards are the app's main grouping device; sharing one means corner radius and elevation
 *         cannot drift between screens.
 * Result: a themed container.
 * Input:  [modifier]; [content] — the card's contents, laid out in a column with standard padding.
 * Output: the rendered card.
 * Changelog: 2026-07-25 — Created for issue 1.8.
 */
@Composable
fun CfoCard(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(CfoDimens.radiusMd),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Column(modifier = Modifier.padding(CfoDimens.spaceMd)) { content() }
    }
}

/**
 * The primary action on a screen.
 * Why:    one button definition so weight, radius and minimum height are decided once. The height
 *         floor is the accessibility requirement, not a style choice.
 * Result: a filled button that is always tappable.
 * Input:  [text] — the label, supplied by the caller from its own `strings.xml`; [onClick];
 *         [modifier]; [enabled].
 * Output: the rendered button.
 * Changelog: 2026-07-25 — Created for issue 1.8.
 */
@Composable
fun CfoButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    Button(
        onClick = onClick,
        enabled = enabled,
        shape = RoundedCornerShape(CfoDimens.radiusSm),
        modifier = modifier.defaultMinSize(minHeight = CfoDimens.minTouchTarget),
    ) {
        Text(text = text, style = MaterialTheme.typography.labelLarge)
    }
}

/**
 * A secondary action, for the choice a user is less likely to want.
 * Why:    pairing an outlined button with the filled one gives a visible hierarchy without a
 *         second colour, which keeps the distinction legible in greyscale.
 * Result: an outlined button with the same touch-target floor.
 * Input:  [text], [onClick], [modifier], [enabled]. Output: the rendered button.
 * Changelog: 2026-07-25 — Created for issue 1.8.
 */
@Composable
fun CfoSecondaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    OutlinedButton(
        onClick = onClick,
        enabled = enabled,
        shape = RoundedCornerShape(CfoDimens.radiusSm),
        modifier = modifier.defaultMinSize(minHeight = CfoDimens.minTouchTarget),
    ) {
        Text(text = text, style = MaterialTheme.typography.labelLarge)
    }
}

/**
 * One line in a list: a label, an optional supporting line, and trailing content.
 * Why:    transaction lists, account lists and settings all share this shape. The trailing slot
 *         exists so an amount can be placed with [CfoAmountText] rather than the row inventing its
 *         own money rendering.
 * Result: a row with a consistent height floor and spacing.
 * Input:  [title]; [modifier]; [supporting] — optional second line; [trailing] — optional slot,
 *         usually an amount.
 * Output: the rendered row.
 * Changelog: 2026-07-25 — Created for issue 1.8.
 */
@Composable
fun CfoListRow(
    title: String,
    modifier: Modifier = Modifier,
    supporting: String? = null,
    trailing: (@Composable () -> Unit)? = null,
) {
    Row(
        modifier =
            modifier
                .fillMaxWidth()
                .defaultMinSize(minHeight = CfoDimens.minTouchTarget)
                .padding(horizontal = CfoDimens.spaceMd, vertical = CfoDimens.spaceSm),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        // weight(fill = false): at large font sizes the title yields width to the trailing amount
        // rather than squeezing it. An amount that does not fit is worse than a wrapped label.
        Column(
            modifier =
                Modifier
                    .weight(1f, fill = false)
                    .padding(end = CfoDimens.spaceSm),
        ) {
            Text(text = title, style = MaterialTheme.typography.bodyLarge)
            if (supporting != null) {
                Text(
                    text = supporting,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (trailing != null) {
            trailing()
        }
    }
}
