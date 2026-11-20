package id.nine.assistant.alarm

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat

/** Dipanggil AlarmManager tepat pada waktunya: bunyikan alarm, lalu jadwalkan kejadian berikutnya. */
class AlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != AlarmScheduler.ACTION_FIRE) return
        val id = intent.getIntExtra(AlarmScheduler.EXTRA_ID, -1)
        val snooze = intent.getBooleanExtra(AlarmScheduler.EXTRA_SNOOZE, false)
        val store = AlarmStore(context)
        val a = store.get(id) ?: return
        if (!a.enabled && !snooze) return
        val ring = Intent(context, AlarmRingService::class.java).putExtra(AlarmScheduler.EXTRA_ID, id)
        try {
            ContextCompat.startForegroundService(context, ring)
        } catch (e: Exception) {
            // Sangat jarang (mis. sistem menolak layanan latar depan): tetap tampilkan notifikasi berbunyi.
            AlarmRingService.fallbackNotify(context, a)
        }
        if (snooze) return
        if (a.repeating) {
            AlarmScheduler.schedule(context, a)
        } else {
            // Alarm sekali: setelah berbunyi otomatis mati (masih bisa dinyalakan lagi).
            store.update(a.copy(enabled = false, date = null))
        }
    }
}

/** Setelah HP dinyalakan ulang, jam/zona waktu diubah, atau Nine diperbarui: pasang ulang semua alarm. */
class AlarmBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_TIME_CHANGED, Intent.ACTION_TIMEZONE_CHANGED,
            Intent.ACTION_MY_PACKAGE_REPLACED, "android.app.action.SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED" ->
                AlarmScheduler.rescheduleAll(context)
        }
    }
}
