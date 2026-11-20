package id.nine.assistant.alarm

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import id.nine.assistant.AlarmsActivity
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * Menjadwalkan alarm Nine lewat AlarmManager.setAlarmClock: tepat waktu, tetap jalan saat HP tidur (Doze),
 * dan muncul sebagai ikon alarm di status bar / layar kunci seperti alarm aplikasi Jam.
 */
object AlarmScheduler {
    const val ACTION_FIRE = "id.nine.assistant.alarm.FIRE"
    const val EXTRA_ID = "alarm_id"
    const val EXTRA_SNOOZE = "snooze"
    private const val SNOOZE_BASE = 50_000

    private fun am(ctx: Context) = ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager

    /** Android 12+ memerlukan izin alarm tepat waktu (otomatis diberikan untuk aplikasi alarm di Android 13+). */
    fun canExact(ctx: Context): Boolean = Build.VERSION.SDK_INT < 31 || am(ctx).canScheduleExactAlarms()

    private fun firePi(ctx: Context, id: Int, snooze: Boolean): PendingIntent {
        val i = Intent(ctx, AlarmReceiver::class.java)
            .setAction(ACTION_FIRE)
            .putExtra(EXTRA_ID, id)
            .putExtra(EXTRA_SNOOZE, snooze)
        return PendingIntent.getBroadcast(
            ctx, if (snooze) SNOOZE_BASE + id else id, i,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
    }

    private fun showPi(ctx: Context): PendingIntent =
        PendingIntent.getActivity(
            ctx, 3001, Intent(ctx, AlarmsActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

    private fun setAt(ctx: Context, millis: Long, op: PendingIntent) {
        val am = am(ctx)
        try {
            if (canExact(ctx)) {
                am.setAlarmClock(AlarmManager.AlarmClockInfo(millis, showPi(ctx)), op)
            } else {
                am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, millis, op)
            }
        } catch (e: SecurityException) {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, millis, op)
        }
    }

    fun schedule(ctx: Context, a: NineAlarm, now: LocalDateTime = LocalDateTime.now()) {
        val app = ctx.applicationContext
        if (!a.enabled) {
            am(app).cancel(firePi(app, a.id, false))
            return
        }
        val at = a.nextTrigger(now).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        setAt(app, at, firePi(app, a.id, false))
    }

    fun cancel(ctx: Context, id: Int) {
        val app = ctx.applicationContext
        am(app).cancel(firePi(app, id, false))
        am(app).cancel(firePi(app, id, true))
    }

    fun snooze(ctx: Context, id: Int, minutes: Int) {
        val app = ctx.applicationContext
        setAt(app, System.currentTimeMillis() + minutes * 60_000L, firePi(app, id, true))
    }

    fun rescheduleAll(ctx: Context) {
        val store = AlarmStore(ctx)
        val now = LocalDateTime.now()
        for (a in store.all()) schedule(ctx, a, now)
    }
}
