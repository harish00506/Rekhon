package com.aicfo.core.designsystem.chart

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color

/**
 * One slice of a [CfoProportionBar].
 * Why:    a value plus its colour, so the caller decides the palette from theme tokens rather than
 *         the chart inventing one.
 * Result: an immutable input, so recomposition is skippable.
 * Input:  [weight] — any non-negative magnitude; the bar normalises. Amounts are `Long` paise
 *         (MNY-001) — this never takes a `Double`. [color] — from the theme.
 * Output: a data holder.
 * Changelog: 2026-07-25 — Created for issue 1.8.
 */
@Immutable
data class CfoProportionSegment(
    val weight: Long,
    val color: Color,
)
