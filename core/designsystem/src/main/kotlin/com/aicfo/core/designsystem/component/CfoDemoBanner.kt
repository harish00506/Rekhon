package com.aicfo.core.designsystem.component

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import com.aicfo.core.designsystem.theme.CfoDimens
import com.aicfo.core.designsystem.theme.CfoTheme

/**
 * The persistent "this is not your money" label shown throughout demo mode (issue 2.4; FR-ONB-004).
 *
 * Why:  the acceptance criterion says the sample data must be **clearly marked**, and the only way
 *       to keep that promise on every screen is to label the app rather than the screens. So this is
 *       composed once, above the navigation graph, instead of being added to each destination — a
 *       per-screen banner is one forgotten screen away from showing a user fabricated figures with
 *       nothing saying so, which is the single worst thing this feature could do. It carries the
 *       exit action for the same reason: the way out has to be visible from wherever the user is.
 * What: a full-width warning-coloured strip with a message and one action.
 * Result: no screen in demo mode can be mistaken for the real app.
 * Changelog: 2026-07-28 — Created for issue 2.4.
 *
 * **Warning, not error, colouring.** Nothing is wrong — the user asked for this — but it must not
 * read as ordinary chrome either. The warning role is the one that says "be aware of context"
 * without implying a fault, and it carries its own `onWarning` pair so contrast holds in both
 * themes (checked by `ColorContrastTest`).
 *
 * **Announced to a screen reader as a live region.** A visual band means nothing to a user who
 * cannot see it, and "am I looking at real money?" is precisely the question an accessibility user
 * must not have to guess at.
 *
 * Input:  [message] — the label, supplied by the caller from its own `strings.xml` (§21.6 keeps copy
 *         out of the design system); [actionText] — the exit action's label; [onExit] — what leaving
 *         the demo does; [modifier].
 * Output: the rendered banner.
 */
@Composable
fun CfoDemoBanner(
    message: String,
    actionText: String,
    onExit: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier =
            modifier
                .fillMaxWidth()
                .background(CfoTheme.extendedColors.warning)
                .padding(horizontal = CfoDimens.spaceMd, vertical = CfoDimens.spaceXs)
                // Polite, not assertive: it should be read when the user reaches it, not interrupt
                // whatever they are already listening to.
                .semantics { liveRegion = LiveRegionMode.Polite },
        horizontalArrangement = Arrangement.spacedBy(CfoDimens.spaceSm, Alignment.Start),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = message,
            style = MaterialTheme.typography.labelLarge,
            color = CfoTheme.extendedColors.onWarning,
            // Takes the leftover width so the action keeps its own space at a 200% font setting,
            // where the message would otherwise push the way out off the screen.
            modifier = Modifier.weight(1f),
        )
        TextButton(
            onClick = onExit,
            modifier = Modifier.defaultMinSize(minHeight = CfoDimens.minTouchTarget),
        ) {
            Text(
                text = actionText,
                style = MaterialTheme.typography.labelLarge,
                color = CfoTheme.extendedColors.onWarning,
            )
        }
    }
}
