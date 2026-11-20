package id.nine.assistant.widget

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context

/** Widget orb Nine versi gelap: ketuk untuk langsung bicara. */
class OrbDarkWidgetProvider : AppWidgetProvider() {
    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        for (id in appWidgetIds) appWidgetManager.updateAppWidget(id, WidgetUpdater.orbViews(context, dark = true))
    }
}
