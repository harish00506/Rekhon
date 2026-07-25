package com.aicfo.core.designsystem.theme

import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/*
 * The colour tokens (SRS §24, §21.6, ACC-*; docs/Design.md).
 *
 * Why:  §21.6 bans a hardcoded colour in any composable, which only becomes enforceable once
 *       there is somewhere else to get one. This file is that somewhere: the single place in the
 *       app a hex literal is allowed to appear. Values come from `docs/Design.md`, which chose the
 *       seed `#00696E` — a deep trust-teal, deliberately not bank-blue — and derived the M3 roles
 *       from it.
 * What: the light and dark `ColorScheme`, plus the three finance roles Material has no slot for.
 * Result: every screen reads colour from the theme, and dark mode is one branch, not a rewrite.
 * Changelog: 2026-07-25 — Created for issue 1.8 from the docs/Design.md proposal.
 *
 * **P-02: colour never carries meaning alone.** `positive`/`negative` exist to reinforce a sign
 * and a label, never to replace them — otherwise the difference between income and spending
 * vanishes for a colour-blind user, in greyscale, and under the privacy-blur mode.
 */

private val TrustTeal = Color(0xFF00696E)
private val TrustTealDark = Color(0xFF4FD8DE)
private val OnTrustTealDark = Color(0xFF00363A)

private val CanvasLight = Color(0xFFF5FBFA)
private val CanvasDark = Color(0xFF191C1C)
private val OnCanvasLight = Color(0xFF191C1C)
private val OnCanvasDark = Color(0xFFE0E3E2)
private val SurfaceVariantLight = Color(0xFFDAE5E3)
private val SurfaceVariantDark = Color(0xFF3F4948)
private val OutlineLight = Color(0xFF3F4948)
private val OutlineDark = Color(0xFFBEC9C7)

private val PositiveLight = Color(0xFF146C2E)
private val PositiveDark = Color(0xFF7DDC97)
private val OnPositiveLight = Color(0xFFFFFFFF)
private val OnPositiveDark = Color(0xFF00390F)

private val NegativeLight = Color(0xFFBA1A1A)
private val NegativeDark = Color(0xFFFFB4AB)
private val OnNegativeLight = Color(0xFFFFFFFF)
private val OnNegativeDark = Color(0xFF690005)

private val WarningLight = Color(0xFF8A5000)
private val WarningDark = Color(0xFFFFB86B)
private val OnWarningLight = Color(0xFFFFFFFF)
private val OnWarningDark = Color(0xFF4A2800)

/** The M3 roles for light mode. */
internal val CfoLightColorScheme =
    lightColorScheme(
        primary = TrustTeal,
        onPrimary = Color.White,
        background = CanvasLight,
        onBackground = OnCanvasLight,
        surface = CanvasLight,
        onSurface = OnCanvasLight,
        surfaceVariant = SurfaceVariantLight,
        onSurfaceVariant = OnCanvasLight,
        outline = OutlineLight,
        error = NegativeLight,
        onError = OnNegativeLight,
    )

/** The M3 roles for dark mode. */
internal val CfoDarkColorScheme =
    darkColorScheme(
        primary = TrustTealDark,
        onPrimary = OnTrustTealDark,
        background = CanvasDark,
        onBackground = OnCanvasDark,
        surface = CanvasDark,
        onSurface = OnCanvasDark,
        surfaceVariant = SurfaceVariantDark,
        onSurfaceVariant = OnCanvasDark,
        outline = OutlineDark,
        error = NegativeDark,
        onError = OnNegativeDark,
    )

/**
 * The finance roles Material 3 has no slot for.
 *
 * Why:    money needs three meanings M3 does not model — a gain, a loss, and a caution (a budget
 *         at 80%, a forecast cash crunch). Reaching for `error` to mean "you spent money" would be
 *         wrong: spending is normal, and colouring it like a failure trains users to ignore real
 *         errors.
 * What:   the three pairs, resolved per theme and carried in a [CompositionLocal].
 * Result: a component asks for `CfoTheme.extendedColors.negative` instead of inventing a red.
 * Changelog: 2026-07-25 — Created for issue 1.8.
 *
 * Input:  the six colours. Output: an immutable holder read through `CfoTheme.extendedColors`.
 */
@Immutable
data class CfoExtendedColors(
    val positive: Color,
    val onPositive: Color,
    val negative: Color,
    val onNegative: Color,
    val warning: Color,
    val onWarning: Color,
)

/** Finance roles for light mode. */
internal val CfoLightExtendedColors =
    CfoExtendedColors(
        positive = PositiveLight,
        onPositive = OnPositiveLight,
        negative = NegativeLight,
        onNegative = OnNegativeLight,
        warning = WarningLight,
        onWarning = OnWarningLight,
    )

/** Finance roles for dark mode. */
internal val CfoDarkExtendedColors =
    CfoExtendedColors(
        positive = PositiveDark,
        onPositive = OnPositiveDark,
        negative = NegativeDark,
        onNegative = OnNegativeDark,
        warning = WarningDark,
        onWarning = OnWarningDark,
    )

/**
 * Carries the finance roles down the tree.
 * Why:    `static` because these change only when the theme itself does; a non-static local would
 *         invalidate every reader on any recomposition.
 * Result: `CfoTheme.extendedColors` resolves without threading a parameter through every call.
 */
internal val LocalCfoExtendedColors =
    staticCompositionLocalOf { CfoLightExtendedColors }
