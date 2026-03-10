package com.waveguard.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import com.waveguard.ui.theme.CyanActive
import com.waveguard.ui.theme.SurfaceDark
import kotlin.math.ceil
import kotlin.math.floor

data class SignalSeries(
    val name: String,
    val values: List<Float>,
    val color: Color
)

/**
 * Canvas-based real-time line graph for RSSI signal history.
 *
 * **Auto-scales** the Y-axis to fit the data with a ±5 dB margin, so even small
 * RSSI variations are clearly visible.  Falls back to the fixed −95…−20 range
 * when there are fewer than 3 data points.
 *
 * Draws: background grid, filled area, signal line, latest-point indicator with glow,
 * and a dashed mean-reference line.
 */
@Composable
fun SignalStrengthGraph(
    rssiHistory: List<Float>,
    modifier: Modifier = Modifier,
    lineColor: Color = CyanActive,
    gridColor: Color = SurfaceDark,
    extraSeries: List<SignalSeries> = emptyList()
) {
    Canvas(modifier = modifier) {
        val allSeries = buildList {
            add(SignalSeries(name = "Combined", values = rssiHistory, color = lineColor))
            addAll(extraSeries.filter { it.values.isNotEmpty() })
        }

        val allValues = allSeries.flatMap { it.values }
        if (allValues.isEmpty()) {
            drawEmptyGrid(gridColor)
            return@Canvas
        }

        val width = size.width
        val height = size.height

        // ---- Auto-scale Y axis ----
        val dataMin: Float
        val dataMax: Float
        if (allValues.size >= 3) {
            val margin = 5f
            dataMin = floor((allValues.min() - margin) / 5f) * 5f
            dataMax = ceil((allValues.max() + margin) / 5f) * 5f
        } else {
            dataMin = -95f
            dataMax = -20f
        }
        val range = (dataMax - dataMin).coerceAtLeast(1f)

        // ---- Grid ----
        drawGrid(gridColor, 4)

        // ---- Map data to screen points ----
        val primaryPoints = toGraphPoints(
            values = rssiHistory,
            width = width,
            height = height,
            min = dataMin,
            range = range
        )

        // ---- Filled area ----
        if (primaryPoints.isNotEmpty()) {
            val fillPath = Path().apply {
                moveTo(primaryPoints.first().x, height)
                primaryPoints.forEach { lineTo(it.x, it.y) }
                lineTo(primaryPoints.last().x, height)
                close()
            }
            drawPath(fillPath, color = lineColor.copy(alpha = 0.15f))
        }

        // ---- Signal lines ----
        allSeries.forEachIndexed { index, series ->
            val points = toGraphPoints(
                values = series.values,
                width = width,
                height = height,
                min = dataMin,
                range = range
            )
            if (points.isEmpty()) return@forEachIndexed

            val linePath = Path().apply {
                points.forEachIndexed { i, point ->
                    if (i == 0) moveTo(point.x, point.y) else lineTo(point.x, point.y)
                }
            }

            drawPath(
                path = linePath,
                color = series.color.copy(alpha = if (index == 0) 1f else 0.9f),
                style = Stroke(
                    width = if (index == 0) 2.5.dp.toPx() else 2.dp.toPx(),
                    cap = StrokeCap.Round,
                    join = StrokeJoin.Round
                )
            )
        }

        // ---- Baseline reference (mean of all data) as dashed line ----
        if (rssiHistory.size >= 5) {
            val meanRssi = rssiHistory.average().toFloat()
            val meanY = height * (1f - ((meanRssi - dataMin) / range).coerceIn(0f, 1f))
            drawLine(
                color = lineColor.copy(alpha = 0.35f),
                start = Offset(0f, meanY),
                end = Offset(width, meanY),
                strokeWidth = 1.dp.toPx(),
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 6f))
            )
        }

        // ---- Latest value dot with glow (combined series) ----
        if (primaryPoints.isNotEmpty()) {
            drawCircle(
                color = lineColor.copy(alpha = 0.3f),
                radius = 7.dp.toPx(),
                center = primaryPoints.last()
            )
            drawCircle(
                color = lineColor,
                radius = 4.dp.toPx(),
                center = primaryPoints.last()
            )
        }

        // Draw small markers for latest node series values.
        extraSeries.forEach { series ->
            val points = toGraphPoints(
                values = series.values,
                width = width,
                height = height,
                min = dataMin,
                range = range
            )
            if (points.isNotEmpty()) {
                drawCircle(
                    color = series.color,
                    radius = 3.dp.toPx(),
                    center = points.last()
                )
            }
        }
    }
}

private fun DrawScope.drawGrid(gridColor: Color, divisions: Int) {
    val step = size.height / divisions
    repeat(divisions + 1) { i ->
        val y = step * i
        drawLine(
            color = gridColor,
            start = Offset(0f, y),
            end = Offset(size.width, y),
            strokeWidth = 0.5.dp.toPx()
        )
    }
}

private fun DrawScope.drawEmptyGrid(gridColor: Color) {
    drawGrid(gridColor, 4)
}

private fun toGraphPoints(
    values: List<Float>,
    width: Float,
    height: Float,
    min: Float,
    range: Float
): List<Offset> {
    if (values.isEmpty()) return emptyList()
    return values.mapIndexed { index, rssi ->
        val x = if (values.size == 1) width / 2f
        else width * index / (values.size - 1).toFloat()
        val normalised = ((rssi - min) / range).coerceIn(0f, 1f)
        val y = height * (1f - normalised)
        Offset(x, y)
    }
}
