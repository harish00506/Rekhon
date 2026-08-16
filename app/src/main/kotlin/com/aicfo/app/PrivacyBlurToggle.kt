package com.aicfo.app

import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp

/**
 * The one-tap privacy blur (issue 5.3; §23, FR-PRIV-*, P-01).
 *
 * Why:  **one tap, from wherever the user already is.** The requirement's own words are
 *       "shoulder-surfing and screen-sharing safety", and both are situations that arrive without
 *       warning — someone sits down beside you, or a call you are on starts sharing your screen.
 *       A toggle buried in a settings screen is three taps and a navigation away, by which time the
 *       figures have been read. So this sits in the app's chrome above the nav graph, beside the
 *       add-transaction FAB, and is reachable from the dashboard, budgets, accounts and the
 *       transaction list alike.
 *
 *       A named composable rather than an `IconButton` inline in `AppContent`, for the reason
 *       `CfoAddTransactionFab` gives: a test can render and click it without standing up the whole
 *       app, and `MainActivity.kt` is already at the size where another inline composable would be
 *       one too many.
 * What: an icon button that reports its own state to the accessibility layer and calls back.
 * Result: every amount on screen masks or unmasks, and `FLAG_SECURE` follows.
 * Changelog: 2026-08-16 — Created for issue 5.3.
 *
 * **It lives in `:app`, not `:core:designsystem`.** The component that *renders* a masked amount is
 * shared (`CfoAmountText`); the control that flips the setting is app chrome, like the demo banner's
 * exit action, and putting it in the design system would mean a gallery component that only makes
 * sense in one place.
 *
 * **A surface behind the icon, not a bare icon.** It floats over screen content — a chart, a
 * coloured amount, a list row — and an unbacked icon is invisible against half of them. Found the
 * same way `CfoAmountText`'s wrap bug was: by looking at the render.
 *
 * Input:  [blurred] — whether amounts are currently hidden; [onToggle] — called with the value to
 *         write, so the caller owns the persistence; [modifier].
 * Output: the composition.
 */
@Composable
internal fun CfoPrivacyBlurToggle(
    blurred: Boolean,
    onToggle: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    // Resolved here, in composable scope: `semantics { }` is not one, so a stringResource call
    // inside it does not compile — which is how hardcoded English ends up in an accessibility label.
    val state =
        stringResource(if (blurred) R.string.privacy_blur_state_on else R.string.privacy_blur_state_off)

    Surface(
        modifier = modifier,
        shape = CircleShape,
        color = MaterialTheme.colorScheme.surfaceVariant,
        tonalElevation = TOGGLE_ELEVATION,
    ) {
        IconButton(
            onClick = { onToggle(!blurred) },
            // stateDescription, not two different contentDescriptions: TalkBack then announces
            // "Hide amounts, on" / "Hide amounts, off" rather than making the user infer the current
            // state from the name of the action, which is the mistake that makes icon toggles
            // unusable without sight.
            modifier = Modifier.semantics { stateDescription = state },
        ) {
            Icon(
                // Two distinct glyphs rather than one that changes colour: the state has to be
                // readable in greyscale, the same rule CfoAmountText follows for its sign.
                imageVector = if (blurred) Icons.Filled.Lock else Icons.Filled.Search,
                contentDescription = stringResource(R.string.privacy_blur_toggle),
            )
        }
    }
}

/** Enough tonal lift to separate the control from whatever it floats over, and no more. */
private val TOGGLE_ELEVATION = 3.dp
