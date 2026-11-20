package id.nine.assistant.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.view.View
import android.widget.RemoteViews
import id.nine.assistant.MainActivity
import id.nine.assistant.R
import id.nine.assistant.TodoActivity
import id.nine.assistant.core.Prefs
import id.nine.assistant.skills.TodoStore
import java.time.LocalDate
import java.time.format.DateTimeFormatter

/** Menggambar ulang widget Nine di layar utama. */
object WidgetUpdater {

    fun listenIntent(ctx: Context): PendingIntent {
        val i = Intent(ctx, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            putExtra(MainActivity.EXTRA_AUTO_LISTEN, true)
        }
        return PendingIntent.getActivity(ctx, 10, i, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    private fun todoIntent(ctx: Context): PendingIntent {
        val i = Intent(ctx, TodoActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return PendingIntent.getActivity(ctx, 11, i, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    fun refresh(ctx: Context) {
        val app = ctx.applicationContext
        val mgr = AppWidgetManager.getInstance(app) ?: return
        try {
            val orbIds = mgr.getAppWidgetIds(ComponentName(app, OrbWidgetProvider::class.java))
            for (id in orbIds) mgr.updateAppWidget(id, orbViews(app))
            val todayIds = mgr.getAppWidgetIds(ComponentName(app, TodayWidgetProvider::class.java))
            for (id in todayIds) mgr.updateAppWidget(id, todayViews(app))
        } catch (e: Exception) {
            // launcher bisa menolak update saat belum siap; abaikan
        }
    }

    fun orbViews(ctx: Context): RemoteViews = RemoteViews(ctx.packageName, R.layout.widget_orb).apply {
        setOnClickPendingIntent(R.id.widgetOrb, listenIntent(ctx))
    }

    fun todayViews(ctx: Context): RemoteViews {
        val lang = Prefs(ctx).lang
        val v = RemoteViews(ctx.packageName, R.layout.widget_today)
        v.setOnClickPendingIntent(R.id.widgetOrb, listenIntent(ctx))
        v.setOnClickPendingIntent(R.id.widgetTasks, todoIntent(ctx))
        val today = LocalDate.now()
        v.setTextViewText(R.id.widgetDate, today.format(DateTimeFormatter.ofPattern("EEEE, d MMM", lang.locale)))
        val items = TodoStore(ctx).today(today)
        val open = items.filter { !it.done }
        val rows = intArrayOf(R.id.widgetTask1, R.id.widgetTask2, R.id.widgetTask3, R.id.widgetTask4)
        for ((i, rowId) in rows.withIndex()) {
            val item = open.getOrNull(i)
            if (item == null) {
                v.setViewVisibility(rowId, View.GONE)
            } else {
                v.setViewVisibility(rowId, View.VISIBLE)
                v.setTextViewText(rowId, "○  " + item.text)
            }
        }
        val summary = when {
            items.isEmpty() -> lang.t("Belum ada tugas. Ketuk orb lalu ucapkan \"tambah tugas …\"", "No tasks yet. Tap the orb and say \"add … to my list\"")
            open.isEmpty() -> lang.t("Semua tugas hari ini selesai ✓", "All done for today ✓")
            open.size > rows.size -> lang.t("+${open.size - rows.size} tugas lagi", "+${open.size - rows.size} more")
            else -> lang.t("${open.size} tugas belum selesai", "${open.size} open ${if (open.size == 1) "task" else "tasks"}")
        }
        v.setTextViewText(R.id.widgetSummary, summary)
        return v
    }
}
