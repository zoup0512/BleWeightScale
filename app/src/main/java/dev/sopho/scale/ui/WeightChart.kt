package dev.sopho.scale.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import dev.sopho.scale.data.WeightRecord

@Composable
fun WeightChart(records: List<WeightRecord>, modifier: Modifier = Modifier) {
    if (records.isEmpty()) {
        Box(modifier.fillMaxWidth().height(144.dp), contentAlignment = Alignment.Center) {
            Text("暂无趋势数据", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        return
    }
    val ordered = records.sortedBy { it.measuredAt }
    val line = MaterialTheme.colorScheme.primary
    val grid = MaterialTheme.colorScheme.outlineVariant
    Canvas(modifier.fillMaxWidth().height(156.dp).padding(horizontal = 8.dp, vertical = 16.dp)) {
        val values = ordered.map { it.weightKg }
        val minimum = values.minOrNull() ?: 0.0
        val maximum = values.maxOrNull() ?: 0.0
        val span = (maximum - minimum).coerceAtLeast(1.0)
        val lower = minimum - (span * 0.25)
        val upper = maximum + (span * 0.25)
        val left = 10.dp.toPx()
        val right = size.width - left
        val top = 8.dp.toPx()
        val bottom = size.height - top
        repeat(3) { step ->
            val y = top + (bottom - top) * step / 2f
            drawLine(grid, Offset(left, y), Offset(right, y), strokeWidth = 1.dp.toPx())
        }
        val start = ordered.first().measuredAt
        val duration = (ordered.last().measuredAt - start).coerceAtLeast(1L)
        val points = ordered.map { record ->
            val x = if (ordered.size == 1) (left + right) / 2f else
                left + ((record.measuredAt - start).toFloat() / duration) * (right - left)
            val y = bottom - ((record.weightKg - lower) / (upper - lower)).toFloat() * (bottom - top)
            Offset(x, y)
        }
        points.zipWithNext { a, b -> drawLine(line, a, b, strokeWidth = 3.dp.toPx(), cap = StrokeCap.Round) }
        points.forEach { point ->
            drawCircle(line, radius = 5.dp.toPx(), center = point)
            drawCircle(color = androidx.compose.ui.graphics.Color.White, radius = 2.dp.toPx(), center = point)
        }
    }
}
