package nl.laadmoment.app.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.RemoteViews
import nl.laadmoment.app.MainActivity
import nl.laadmoment.app.R
import nl.laadmoment.app.laadmoment
import nl.laadmoment.app.work.Scheduler
import nl.laadmoment.core.Briefing
import nl.laadmoment.core.Fmt
import nl.laadmoment.core.Insights
import nl.laadmoment.core.Snapshot

/**
 * Home-screen widget: the best window in words, and the coming hours as a
 * strip of coloured bars with the sun on top. Drawn from the cached data;
 * the hourly refresh job keeps that cache current.
 */
class LaadWidget : AppWidgetProvider() {

    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        val snapshot = snapshotFromCache(context)
        ids.forEach { render(context, manager, it, snapshot) }
        Scheduler.refreshSoon(context)
    }

    override fun onAppWidgetOptionsChanged(context: Context, manager: AppWidgetManager, id: Int, options: Bundle) {
        render(context, manager, id, snapshotFromCache(context))
    }

    companion object {
        fun updateAll(context: Context, snapshot: Snapshot = snapshotFromCache(context)) {
            val manager = AppWidgetManager.getInstance(context) ?: return
            val ids = manager.getAppWidgetIds(ComponentName(context, LaadWidget::class.java))
            ids.forEach { render(context, manager, it, snapshot) }
        }

        private fun snapshotFromCache(context: Context): Snapshot {
            val app = context.laadmoment
            return Snapshot.of(app.settings.current, app.forecasts.cached())
        }

        private fun render(context: Context, manager: AppWidgetManager, id: Int, snapshot: Snapshot) {
            val views = RemoteViews(context.packageName, R.layout.widget_laad)
            val (title, subtitle) = texts(snapshot)
            views.setTextViewText(R.id.widget_title, title)
            views.setTextViewText(R.id.widget_subtitle, subtitle)

            val options = manager.getAppWidgetOptions(id)
            val density = context.resources.displayMetrics.density
            val widthDp = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 250).coerceAtLeast(160)
            val heightDp = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, 110).coerceAtLeast(80)
            val chartWidth = ((widthDp - 28) * density).toInt().coerceIn(200, 1400)
            val chartHeight = ((heightDp - 62) * density).toInt().coerceIn((32 * density).toInt(), (160 * density).toInt())
            views.setImageViewBitmap(R.id.widget_chart, WidgetChart.draw(snapshot.insights, chartWidth, chartHeight, density))

            val open = PendingIntent.getActivity(
                context,
                0,
                Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            views.setOnClickPendingIntent(R.id.widget_root, open)
            manager.updateAppWidget(id, views)
        }

        private fun texts(snapshot: Snapshot): Pair<String, String> {
            val plan = snapshot.plan ?: return "Laadmoment" to "Tik om prijzen en weer op te halen"
            val block = plan.blocks.firstOrNull()
                ?: return "Laadmoment" to Briefing.body(plan)
            val title = "⚡ ${Briefing.window(block, snapshot.now, snapshot.zone)}"
            val parts = Insights.reasons(block, snapshot.insights).toMutableList()
            parts += "${Fmt.cents(block.avgPricePerKwh)}/kWh"
            if (plan.blocks.size > 1) parts += "+${plan.blocks.size - 1} blok"
            return title to parts.joinToString(" · ")
        }
    }
}
