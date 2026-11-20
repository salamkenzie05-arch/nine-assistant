package id.nine.assistant.widget

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context

/** Widget orb Nine putih 2x2: selalu putih, tidak ikut tema. */
class OrbWhiteWidgetProvider : AppWidgetProvider() {
    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        for (id in appWidgetIds) appWidgetManager.updateAppWidget(id, WidgetUpdater.orbViews(context, white = true))
    }
}
