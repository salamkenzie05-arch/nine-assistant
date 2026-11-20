package id.nine.assistant.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.view.View
import android.widget.RemoteViews
import id.nine.assistant.MainActivity
import id.nine.assistant.R
import id.nine.assistant.TodoActivity
import id.nine.assistant.core.Prefs
import id.nine.assistant.skills.TodoStore
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter

/** Menggambar ulang widget Nine di layar utama. */
object WidgetUpdater {

    fun listenIntent(ctx: Context): PendingIntent {
        val i = Intent(ctx, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            putExtra(MainActivity.EXTRA_AUTO_LISTEN, true)
            putExtra(MainActivity.EXTRA_TOKEN, Prefs(ctx).launchToken)
        }
        return PendingIntent.getActivity(ctx, 10, i, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    private fun todoIntent(ctx: Context): PendingIntent {
        val i = Intent(ctx, TodoActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return PendingIntent.getActivity(ctx, 11, i, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    @Volatile
    private var lastFace: Face? = null

    /** Perbarui widget hanya bila ekspresinya berubah (agar animasi tidak terus diulang dari awal). */
    fun refreshFace(ctx: Context) {
        if (face(ctx) != lastFace) refresh(ctx)
    }

    fun refresh(ctx: Context) {
        val app = ctx.applicationContext
        lastFace = face(app)
        val mgr = AppWidgetManager.getInstance(app) ?: return
        try {
            val orbIds = mgr.getAppWidgetIds(ComponentName(app, OrbWidgetProvider::class.java))
            for (id in orbIds) mgr.updateAppWidget(id, orbViews(app))
            val darkIds = mgr.getAppWidgetIds(ComponentName(app, OrbDarkWidgetProvider::class.java))
            for (id in darkIds) mgr.updateAppWidget(id, orbViews(app, dark = true))
            val todayIds = mgr.getAppWidgetIds(ComponentName(app, TodayWidgetProvider::class.java))
            for (id in todayIds) mgr.updateAppWidget(id, todayViews(app))
        } catch (e: Exception) {
            // launcher bisa menolak update saat belum siap; abaikan
        }
    }

    enum class Face { NEUTRAL, HAPPY, SLEEPY, CHARGING, LOWBAT }

    /** Ekspresi wajah di widget: dicas, baterai lemah, malam (mengantuk), baru diajak bicara (senang), atau biasa. */
    fun face(ctx: Context): Face {
        val b = try {
            ctx.applicationContext.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        } catch (e: Exception) {
            null
        }
        if (b != null) {
            val status = b.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
            if (status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL) return Face.CHARGING
            val lvl = b.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
            val scale = b.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
            if (lvl >= 0 && scale > 0 && lvl * 100 / scale <= 15) return Face.LOWBAT
        }
        val h = LocalTime.now().hour
        if (h >= 23 || h < 5) return Face.SLEEPY
        val last = Prefs(ctx).lastTalk
        if (System.currentTimeMillis() - last < 15 * 60_000L) return Face.HAPPY
        return Face.NEUTRAL
    }

    private fun rep(name: String, n: Int): List<String> = List(n) { name }

    /**
     * Urutan bingkai animasi (tiap bingkai 160 ms, diulang terus oleh launcher): berkedip, melirik kiri-kanan,
     * mendongak, tersenyum; versi mengantuk ("zzz"), dicas (petir berdenyut), dan baterai lemah (ikon berkedip).
     */
    private fun sequence(f: Face): List<String> = when (f) {
        Face.NEUTRAL -> rep("neutral", 14) + "blink" + rep("neutral", 8) +
            "left1" + rep("left2", 5) + "left1" + rep("neutral", 3) +
            "right1" + rep("right2", 5) + "right1" + rep("neutral", 9) +
            "blink" + "neutral" + "blink" + rep("neutral", 8) +
            rep("up", 4) + rep("neutral", 2) + rep("happy", 5) + rep("neutral", 4)
        Face.HAPPY -> rep("happy", 10) + rep("neutral", 2) + "blink" + rep("neutral", 2) + rep("happy", 8) +
            "left1" + rep("left2", 4) + "left1" + rep("happy", 6) +
            "right1" + rep("right2", 4) + "right1" + rep("happy", 6)
        Face.SLEEPY -> rep("sleepy1", 5) + rep("sleepy2", 5) + rep("sleepy3", 5) + rep("sleepyclosed", 8) +
            rep("sleepy1", 5) + rep("sleepy2", 5) + rep("sleepy3", 5) + rep("sleepyclosed", 4)
        Face.CHARGING -> rep("charge1", 4) + rep("charge2", 4) + rep("charge1", 4) + rep("charge2", 4) +
            "chargeblink" + rep("charge1", 3) + rep("charge2", 4) + rep("charge1", 4) + rep("charge2", 4)
        Face.LOWBAT -> rep("lowbat1", 5) + rep("lowbat2", 5) + rep("lowbat1", 5) + rep("lowbat2", 5) +
            rep("lowbatblink", 3) + rep("lowbat1", 5) + rep("lowbat2", 5)
    }

    /** Isi ViewFlipper [flipperId] dengan bingkai wajah. [theme]: "light", "dark", atau "auto" (ikut mode gelap sistem). */
    private fun fillFace(ctx: Context, views: RemoteViews, flipperId: Int, theme: String, face: Face) {
        val table = when (theme) {
            "dark" -> FaceFrames.DARK
            "auto" -> FaceFrames.AUTO
            else -> FaceFrames.LIGHT
        }
        views.removeAllViews(flipperId)
        for (name in sequence(face)) {
            val frame = RemoteViews(ctx.packageName, R.layout.widget_face_frame)
            frame.setImageViewResource(R.id.faceFrame, table[name] ?: table.getValue("neutral"))
            views.addView(flipperId, frame)
        }
    }

    fun orbViews(ctx: Context, dark: Boolean = false): RemoteViews =
        RemoteViews(ctx.packageName, if (dark) R.layout.widget_orb_dark else R.layout.widget_orb).apply {
            fillFace(ctx, this, R.id.widgetFlipper, if (dark) "dark" else "light", face(ctx))
            setOnClickPendingIntent(R.id.widgetRoot, listenIntent(ctx))
        }

    fun todayViews(ctx: Context): RemoteViews {
        val lang = Prefs(ctx).lang
        val v = RemoteViews(ctx.packageName, R.layout.widget_today)
        fillFace(ctx, v, R.id.widgetFaceFlipper, "auto", face(ctx))
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
