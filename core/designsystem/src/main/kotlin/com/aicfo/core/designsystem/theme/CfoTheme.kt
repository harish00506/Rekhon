package com.aicfo.core.designsystem.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable

/**
 * The app's Material 3 theme.
 *
 * Why:  one wrapper so no screen assembles its own colours or typography — §21.6's "every colour
 *       and dimension from theme tokens" is only true if there is a single theme to take them
 *       from. It also makes dark mode a property of the app rather than a feature each screen has
 *       to remember to support.
 * What: picks the light or dark scheme, installs the M3 typography, and provides the finance
 *       colour roles Material has no slot for.
 * Result: `CfoTheme { … }` wraps any screen, preview or screenshot test identically.
 * Changelog: 2026-07-25 — Created for issue 1.8.
 *
 * **No dynamic colour.** Material You would recolour the app from the user's wallpaper, which
 * would override the deliberate contrast of the `positive`/`negative` roles — the two colours in a
 * finance app that must stay legible and distinguishable. Revisit only with an accessibility
 * check that covers the generated palettes.
 *
 * Input:  [darkTheme] — defaults to the system setting; overridden by screenshot tests to render
 *         both. [content] — the UI to theme.
 * Output: the themed composition.
 */
@Composable
fun CfoTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val colorScheme = if (darkTheme) CfoDarkColorScheme else CfoLightColorScheme
    val extendedColors = if (darkTheme) CfoDarkExtendedColors else CfoLightExtendedColors

    CompositionLocalProvider(LocalCfoExtendedColors provides extendedColors) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = CfoTypography,
            content = content,
        )
    }
}

/**
 * Accessors for the parts of the theme Material does not carry.
 * Why:    mirrors `MaterialTheme.colorScheme` so a reader does not have to learn a second idiom.
 * Result: `CfoTheme.extendedColors.negative` inside any composable.
 * Changelog: 2026-07-25 — Created for issue 1.8.
 */
object CfoTheme {
    /** Input: none. Output: the finance colour roles for the current theme. */
    val extendedColors: CfoExtendedColors
        @Composable
        @ReadOnlyComposable
        get() = LocalCfoExtendedColors.current

    /** Input: none. Output: the spacing and sizing tokens. */
    val dimens: CfoDimens
        get() = CfoDimens
}
