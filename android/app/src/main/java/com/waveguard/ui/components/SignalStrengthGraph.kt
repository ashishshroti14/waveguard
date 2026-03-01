package com.waveguard.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import com.waveguard.ui.theme.CyanActive
import com.waveguard.ui.theme.SurfaceDark

/**
 * Canvas-based real-time line graph for RSSI signal history.
 *
 * Draws:
 *  - Background grid lines (horizontal at 25% intervals)
 *  - Filled area under the signal curve
 *  - Signal line in [lineColor] (default: [CyanActive])
 *
 * @param rssiHistory List of up to 60 RSSI float values (e.g. -90f to -30f dBm)
 */
@Composable
fun SignalStrengthGraph(
    rssiHistory: List<Float>,
    modifier: Modifier = Modifier,
    lineColor: Color = CyanActive,
    gridColor: Color = SurfaceDark
) {
    Canvas(modifier = modifier) {
        if (rssiHistory.isEmpty()) {
            drawEmptyGrid(gridColor)
            return@Canvas
        }

        val width = size.width
        val height = size.height

        // Normalise RSSI values: typical Wi-Fi range is -90 dBm (weak) to -30 dBm (strong)
        val minRssi = -95f
        val maxRssi = -20f
        val range = maxRssi - minRssi

        drawGrid(gridColor, 4)

        val points = rssiHistory.mapIndexed { index, rssi ->
            val x = if (rssiHistory.size == 1) width / 2f
            else width * index / (rssiHistory.size - 1).toFloat()
            val normalised = ((rssi - minRssi) / range).coerceIn(0f, 1f)
            val y = height * (1f - normalised)
            Offset(x, y)
        }

        // Draw filled area beneath the line
        val fillPath = Path().apply {
            moveTo(points.first().x, height)
            points.forEach { lineTo(it.x, it.y) }
            lineTo(points.last().x, height)
            close()
        }
        drawPath(fillPath, color = lineColor.copy(alpha = 0.15f))

        // Draw signal line
        val linePath = Path().apply {
            points.forEachIndexed { i, point ->
                if (i == 0) moveTo(point.x, point.y) else lineTo(point.x, point.y)
            }
        }
        drawPath(
            path = linePath,
            color = lineColor,
            style = Stroke(
                width = 2.5.dp.toPx(),
                cap = StrokeCap.Round,
                join = StrokeJoin.Round
            )
        )

        // Draw latest value dot
        if (points.isNotEmpty()) {
            drawCircle(
                color = lineColor,
                radius = 4.dp.toPx(),
                center = points.last()
            )
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
