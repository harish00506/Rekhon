package com.aicfo.core.designsystem.theme

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * The spacing, radius and touch-target tokens (SRS §21.6, ACC-*).
 *
 * Why:  §21.6 bans a hardcoded dimension in a composable for the same reason it bans a hardcoded
 *       colour — a `16.dp` scattered across forty files cannot be adjusted, and a `40.dp` button
 *       cannot be found. Naming them also makes one rule impossible to forget:
 *       [CfoDimens.minTouchTarget] is 48.dp because Android accessibility requires it, so every
 *       clickable component states that requirement rather than remembering it.
 * What: a 4dp-based spacing scale, corner radii, and the touch-target minimum.
 * Result: consistent rhythm, and an accessibility rule expressed as a token.
 * Changelog: 2026-07-25 — Created for issue 1.8.
 *
 * A plain object rather than a `CompositionLocal`: these do not change with theme or platform, and
 * a local would add indirection for no benefit.
 */
object CfoDimens {
    /** 4dp — the base unit everything else is a multiple of. */
    val spaceXs: Dp = 4.dp

    /** 8dp — inside a chip, between an icon and its label. */
    val spaceSm: Dp = 8.dp

    /** 16dp — the default padding inside a card or screen edge. */
    val spaceMd: Dp = 16.dp

    /** 24dp — between sections. */
    val spaceLg: Dp = 24.dp

    /** 32dp — above a screen's primary action. */
    val spaceXl: Dp = 32.dp

    /** Card and sheet corners. */
    val radiusMd: Dp = 12.dp

    /** Buttons and chips. */
    val radiusSm: Dp = 8.dp

    /**
     * The smallest a tappable thing may be.
     * Why: Android's accessibility guidance sets 48dp; anything less is unreliable for users with
     *      motor impairments, and it is the most common accessibility failure in review.
     */
    val minTouchTarget: Dp = 48.dp

    /** Height of the segmented proportion bar. */
    val chartBarHeight: Dp = 12.dp

    /** Default height of a sparkline. */
    val chartSparklineHeight: Dp = 40.dp

    /** Stroke width for chart lines. */
    val chartStroke: Dp = 2.dp
}
