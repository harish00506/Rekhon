package com.aicfo.core.designsystem.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/*
 * The type tokens (SRS §24; docs/Design.md).
 *
 * Why:  Roboto ships on every Android device, so no font is bundled — the native platform beats a
 *       dependency, and a bundled font is weight in every APK for a difference nobody asked for.
 *       The one addition Material's scale does not cover is [CfoTypography.amount].
 * What: the standard M3 type scale plus the app-specific amount style.
 * Result: text sizes scale with the system font setting, and money columns line up.
 * Changelog: 2026-07-25 — Created for issue 1.8 from the docs/Design.md proposal.
 */

/**
 * The M3 scale, at the sizes `docs/Design.md` specifies.
 * Why:    written out rather than taking Material's defaults so the design doc and the code cannot
 *         drift apart silently.
 * Result: the typography passed to `MaterialTheme`.
 */
internal val CfoTypography =
    Typography(
        displayLarge = TextStyle(fontFamily = FontFamily.Default, fontSize = 57.sp, fontWeight = FontWeight.Normal),
        displayMedium = TextStyle(fontFamily = FontFamily.Default, fontSize = 45.sp, fontWeight = FontWeight.Normal),
        displaySmall = TextStyle(fontFamily = FontFamily.Default, fontSize = 36.sp, fontWeight = FontWeight.Normal),
        headlineLarge = TextStyle(fontFamily = FontFamily.Default, fontSize = 32.sp, fontWeight = FontWeight.Normal),
        headlineMedium = TextStyle(fontFamily = FontFamily.Default, fontSize = 28.sp, fontWeight = FontWeight.Normal),
        headlineSmall = TextStyle(fontFamily = FontFamily.Default, fontSize = 24.sp, fontWeight = FontWeight.Normal),
        titleLarge = TextStyle(fontFamily = FontFamily.Default, fontSize = 22.sp, fontWeight = FontWeight.Medium),
        titleMedium = TextStyle(fontFamily = FontFamily.Default, fontSize = 16.sp, fontWeight = FontWeight.Medium),
        titleSmall = TextStyle(fontFamily = FontFamily.Default, fontSize = 14.sp, fontWeight = FontWeight.Medium),
        bodyLarge = TextStyle(fontFamily = FontFamily.Default, fontSize = 16.sp, fontWeight = FontWeight.Normal),
        bodyMedium = TextStyle(fontFamily = FontFamily.Default, fontSize = 14.sp, fontWeight = FontWeight.Normal),
        bodySmall = TextStyle(fontFamily = FontFamily.Default, fontSize = 12.sp, fontWeight = FontWeight.Normal),
        labelLarge = TextStyle(fontFamily = FontFamily.Default, fontSize = 14.sp, fontWeight = FontWeight.Medium),
        labelMedium = TextStyle(fontFamily = FontFamily.Default, fontSize = 12.sp, fontWeight = FontWeight.Medium),
        labelSmall = TextStyle(fontFamily = FontFamily.Default, fontSize = 11.sp, fontWeight = FontWeight.Medium),
    )

/**
 * The style every monetary figure uses.
 *
 * Why:    proportional digits make a column of amounts ragged — ₹1,11,111 is visibly narrower than
 *         ₹8,88,888 — so balances and running totals do not line up and are far harder to scan.
 *         `tnum` switches Roboto to tabular (fixed-width) figures, which is the one typographic
 *         detail a finance app cannot skip.
 * What:   a title-sized style with tabular figures and medium weight.
 * Result: `CfoAmountText` renders alignable money.
 * Input:  none (a constant). Output: the [TextStyle] for amounts.
 * Changelog: 2026-07-25 — Created for issue 1.8.
 */
val CfoAmountTextStyle: TextStyle =
    TextStyle(
        fontFamily = FontFamily.Default,
        fontSize = 16.sp,
        fontWeight = FontWeight.Medium,
        fontFeatureSettings = "tnum",
    )
