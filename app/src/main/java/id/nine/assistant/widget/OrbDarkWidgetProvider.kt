package id.nine.assistant.widget

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context

/**
 * Widget lama "orb gelap". Kini sama dengan widget orb biasa (warnanya mengikuti tema Nine di aplikasi);
 * kelasnya dipertahankan agar widget yang sudah terpasang tetap berfungsi. Disembunyikan dari pemilih widget di Android 12+.
 */
class OrbDarkWidgetProvider : AppWidgetProvider() {
    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        for (id in appWidgetIds) appWidgetManager.updateAppWidget(id, WidgetUpdater.orbViews(context))
    }
}
