package com.aicfo.core.designsystem.chart

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import com.aicfo.core.designsystem.theme.CfoDimens

/*
 * The chart primitives (SRS §24; ACC-*).
 *
 * Why:  two shapes cover what the dashboard actually needs — "where did the money go" (a
 *       proportion split) and "which way is it trending" (a line over time). Both are a few lines
 *       of Canvas, so a charting dependency would be more code to configure than to write, and
 *       every chart library brings its own colours and text that would sit outside the theme.
 *       Anything more elaborate waits for issue 5.1, where a real screen states a real requirement.
 * What: CfoProportionBar and CfoSparkline.
 * Result: token-driven charts that render identically in light, dark and at 200% font.
 * Changelog: 2026-07-25 — Created for issue 1.8.
 *
 * **Both require a `contentDescription` (ACC-*).** A chart is pure pixels: to a screen reader it
 * does not exist unless something says what it shows. It is a required parameter rather than an
 * optional one so it cannot be forgotten — a caller that passes a summary like "food 40%, rent
 * 35%, transport 25%" gives a non-sighted user the same information a sighted one gets.
 */

/**
 * A single horizontal bar split into proportional segments — "where the money went".
 *
 * Why:    the clearest way to show a spending split without the label-crowding a pie chart brings
 *         at small sizes, and it stays readable at 200% font because it has no text of its own.
 * What:   normalises the segment weights and draws them left to right.
 * Result: a themed bar with a text alternative.
 * Changelog: 2026-07-25 — Created for issue 1.8.
 *
 * Zero-weight segments are skipped rather than drawn as hairlines, and an all-zero input renders
 * the empty track — a bar of nothing, which is the honest picture of a month with no spending.
 *
 * Input:  [segments] — the split; [contentDescription] — required, what the bar shows in words;
 *         [modifier]; [height].
 * Output: the rendered bar.
 */
@Composable
fun CfoProportionBar(
    segments: List<CfoProportionSegment>,
    contentDescription: String,
    modifier: Modifier = Modifier,
    height: Dp = CfoDimens.chartBarHeight,
) {
    val total = segments.sumOf { it.weight.coerceAtLeast(0L) }
    val track = MaterialTheme.colorScheme.surfaceVariant
    Canvas(
        modifier =
            modifier
                .fillMaxWidth()
                .height(height)
                .clip(RoundedCornerShape(height / 2))
                .semantics { this.contentDescription = contentDescription },
    ) {
        drawRect(color = track, size = size)
        if (total <= 0L) return@Canvas
        var startX = 0f
        segments.forEach { segment ->
            val weight = segment.weight.coerceAtLeast(0L)
            if (weight == 0L) return@forEach
            // Long arithmetic first, then one conversion — the fraction is a drawing ratio, never
            // a monetary value (MNY-001 applies to the amounts, not to pixels).
            val width = size.width * (weight.toFloat() / total.toFloat())
            drawRect(color = segment.color, topLeft = Offset(startX, 0f), size = Size(width, size.height))
            startX += width
        }
    }
}

/**
 * A compact line of a value over time — "which way is it trending".
 *
 * Why:    a balance or net-worth trend is the one chart worth showing without axes: the shape
 *         carries the message, and the exact figures are already on screen as text next to it.
 *         Dropping the axes keeps it legible in a list row and at large font sizes.
 * What:   scales the points to the available box and strokes a path through them.
 * Result: a themed sparkline with a text alternative.
 * Changelog: 2026-07-25 — Created for issue 1.8.
 *
 * A flat series (every value identical) draws through the vertical centre rather than dividing by
 * a zero range — the case that would otherwise be a crash on a month with no change.
 *
 * Input:  [values] — the series, `Long` minor units in order; [contentDescription] — required;
 *         [modifier]; [height]; [color] — defaults to the theme's primary.
 * Output: the rendered sparkline.
 */
@Composable
fun CfoSparkline(
    values: List<Long>,
    contentDescription: String,
    modifier: Modifier = Modifier,
    height: Dp = CfoDimens.chartSparklineHeight,
    color: Color = MaterialTheme.colorScheme.primary,
) {
    val stroke = CfoDimens.chartStroke
    Canvas(
        modifier =
            modifier
                .fillMaxWidth()
                .height(height)
                .semantics { this.contentDescription = contentDescription },
    ) {
        if (values.size < MIN_POINTS) return@Canvas
        val minValue = values.min()
        val maxValue = values.max()
        val range = (maxValue - minValue).toFloat()
        val stepX = size.width / (values.size - 1).toFloat()
        val strokePx = stroke.toPx()
        val usableHeight = size.height - strokePx

        val path = Path()
        values.forEachIndexed { index, value ->
            // A flat series has no range to scale against; centre it instead of dividing by zero.
            val fraction = if (range == 0f) HALF else (value - minValue).toFloat() / range
            val x = index * stepX
            val y = strokePx / 2f + usableHeight * (1f - fraction)
            if (index == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        drawPath(path = path, color = color, style = Stroke(width = strokePx, cap = StrokeCap.Round))
    }
}

/**
 * A small colour key for a [CfoProportionBar].
 * Why:    P-02 again — the bar's colours mean nothing on their own, so anything using it needs a
 *         way to name the segments. Providing the swatch here stops each screen drawing its own.
 * Result: a row of colour swatches the caller labels.
 * Input:  [color] — the segment colour; [modifier]. Output: the rendered swatch.
 * Changelog: 2026-07-25 — Created for issue 1.8.
 */
@Composable
fun CfoLegendSwatch(
    color: Color,
    modifier: Modifier = Modifier,
) {
    Canvas(
        modifier =
            modifier
                .size(CfoDimens.spaceSm)
                .clip(RoundedCornerShape(CfoDimens.spaceXs)),
    ) {
        drawRect(color = color, size = size)
    }
}

/**
 * Lays swatches and their labels out in a row.
 * Result: a legend. Input: [modifier], [content]. Output: the rendered legend.
 * Changelog: 2026-07-25 — Created for issue 1.8.
 */
@Composable
fun CfoLegendRow(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(CfoDimens.spaceMd),
    ) { content() }
}

/** Two points is the fewest that make a line. */
private const val MIN_POINTS = 2

/** Where a flat series is drawn: the vertical middle. */
private const val HALF = 0.5f
