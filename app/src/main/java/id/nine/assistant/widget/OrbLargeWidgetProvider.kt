package id.nine.assistant.widget

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context

/** Widget orb Nine ukuran 4x4: ketuk untuk langsung bicara. */
class OrbLargeWidgetProvider : AppWidgetProvider() {
    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        for (id in appWidgetIds) appWidgetManager.updateAppWidget(id, WidgetUpdater.orbLargeViews(context))
    }
}
