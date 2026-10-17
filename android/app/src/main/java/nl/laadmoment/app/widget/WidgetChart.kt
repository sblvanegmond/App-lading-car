package nl.laadmoment.app.widget

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import nl.laadmoment.app.ui.ChartColors
import nl.laadmoment.core.HourInsight
import nl.laadmoment.core.SunLevel
import kotlin.math.max
import kotlin.math.min

/**
 * The widget's hour strip as a bitmap: a remote view cannot run Compose.
 * Per hour, from top to bottom: a sun dot, a price bar in the colour of its
 * price level (bright green for the best hours), and the hour.
 */
object WidgetChart {
    private const val HOURS = 24

    fun draw(insights: List<HourInsight>, width: Int, height: Int, density: Float): Bitmap {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val hours = insights.take(HOURS)
        if (hours.isEmpty()) return bitmap

        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFFB8C0CC.toInt()
            textSize = 10 * density
            textAlign = Paint.Align.CENTER
        }

        val sunBand = 10 * density
        val labelBand = 13 * density
        val barTop = sunBand
        val barBottom = height - labelBand
        val barArea = max(4f, barBottom - barTop)
        val step = width.toFloat() / HOURS
        val barWidth = step * 0.68f
        val radius = min(barWidth / 2, 3 * density)

        val prices = hours.mapNotNull { it.slot.allInPrice }
        val top = prices.maxOrNull() ?: 0.0
        val bottom = min(0.0, prices.minOrNull() ?: 0.0)
        val span = (top - bottom).takeIf { it > 1e-6 } ?: 1.0

        hours.forEachIndexed { i, hour ->
            val cx = step * i + step / 2
            val left = cx - barWidth / 2

            // Charging hours get a soft green band behind them; neighbours touch,
            // so a window reads as one block.
            if (hour.isBest) {
                paint.color = ChartColors.BEST_BAND
                canvas.drawRect(step * i, 0f, step * (i + 1), height.toFloat(), paint)
            }

            val sunRadius = when (hour.sunLevel) {
                SunLevel.NONE -> 0f
                SunLevel.LITTLE -> 1.6f
                SunLevel.SOME -> 2.6f
                SunLevel.LOTS -> 3.6f
            } * density
            if (sunRadius > 0) {
                paint.color = ChartColors.SUN
                canvas.drawCircle(cx, sunBand / 2, sunRadius, paint)
            }

            val price = hour.slot.allInPrice
            val fraction = if (price == null) 0.18 else 0.18 + 0.82 * ((price - bottom) / span)
            val barHeight = (barArea * fraction.toFloat()).coerceIn(2 * density, barArea)
            paint.color = when {
                hour.isBest -> ChartColors.BEST
                else -> ChartColors.forLevel(hour.priceLevel)
            }
            canvas.drawRoundRect(RectF(left, barBottom - barHeight, left + barWidth, barBottom), radius, radius, paint)

            val clock = hour.slot.start.atZone(java.time.ZoneId.systemDefault()).hour
            if (clock % 3 == 0 && !hour.isNow) {
                canvas.drawText(clock.toString().padStart(2, '0'), cx, height - 2 * density, text)
            } else if (hour.isNow) {
                text.color = 0xFFFFFFFF.toInt()
                canvas.drawText("nu", cx, height - 2 * density, text)
                text.color = 0xFFB8C0CC.toInt()
            }
        }
        return bitmap
    }
}
