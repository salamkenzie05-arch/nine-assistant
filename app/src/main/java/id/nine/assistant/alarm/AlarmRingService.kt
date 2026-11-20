package id.nine.assistant.alarm

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import id.nine.assistant.AlarmRingActivity
import id.nine.assistant.R
import id.nine.assistant.core.Prefs

/**
 * Membunyikan alarm Nine: nada alarm (aliran suara alarm, tetap bunyi saat mode senyap/DND mengizinkan alarm),
 * getar, dan layar penuh di atas layar kunci. Berhenti sendiri setelah 10 menit.
 */
class AlarmRingService : Service() {

    companion object {
        const val ACTION_STOP = "id.nine.assistant.alarm.STOP"
        const val ACTION_SNOOZE = "id.nine.assistant.alarm.SNOOZE"
        private const val CHANNEL = "nine_alarm_ring"
        private const val NOTIF_ID = 29
        private const val MAX_RING_MS = 10 * 60_000L
        const val SNOOZE_MINUTES = 10

        /** Id alarm yang sedang berbunyi, -1 bila tidak ada. */
        @Volatile
        var ringingId: Int = -1
            private set

        val ringing: Boolean get() = ringingId != -1

        /** Dipanggil saat alarm mulai/berhenti berbunyi (dari main thread). */
        val listeners = LinkedHashSet<() -> Unit>()

        fun stop(ctx: Context) {
            if (!ringing) return
            ctx.startService(Intent(ctx, AlarmRingService::class.java).setAction(ACTION_STOP))
        }

        fun snooze(ctx: Context) {
            if (!ringing) return
            ctx.startService(Intent(ctx, AlarmRingService::class.java).setAction(ACTION_SNOOZE))
        }

        fun createChannel(ctx: Context) {
            if (Build.VERSION.SDK_INT < 26) return
            val nm = ctx.getSystemService(NotificationManager::class.java)
            if (nm.getNotificationChannel(CHANNEL) != null) return
            val ch = NotificationChannel(CHANNEL, ctx.getString(R.string.alarm_channel), NotificationManager.IMPORTANCE_HIGH).apply {
                setSound(null, null) // suara diputar sendiri lewat aliran alarm
                enableVibration(false)
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            }
            nm.createNotificationChannel(ch)
        }

        private fun label(ctx: Context, a: NineAlarm?): String =
            a?.label ?: ctx.getString(R.string.alarm_default_label)

        /** Bila layanan tidak bisa dimulai: notifikasi biasa sebagai cadangan. */
        fun fallbackNotify(ctx: Context, a: NineAlarm) {
            createChannel(ctx)
            val n = NotificationCompat.Builder(ctx, CHANNEL)
                .setSmallIcon(R.drawable.ic_stat_nine)
                .setContentTitle(a.clock + " · " + label(ctx, a))
                .setCategory(NotificationCompat.CATEGORY_ALARM)
                .setPriority(NotificationCompat.PRIORITY_MAX)
                .setDefaults(NotificationCompat.DEFAULT_ALL)
                .setAutoCancel(true)
                .build()
            try {
                NotificationManagerCompat.from(ctx).notify(NOTIF_ID + 1, n)
            } catch (e: SecurityException) {
                // izin notifikasi ditolak
            }
        }
    }

    private val main = Handler(Looper.getMainLooper())
    private var player: MediaPlayer? = null
    private var vibrator: Vibrator? = null
    private var wake: PowerManager.WakeLock? = null
    private var volume = 0.25f

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> finish()
            ACTION_SNOOZE -> {
                val id = ringingId
                if (id != -1) AlarmScheduler.snooze(this, id, SNOOZE_MINUTES)
                finish()
            }
            else -> {
                val id = intent?.getIntExtra(AlarmScheduler.EXTRA_ID, -1) ?: -1
                if (id == -1) {
                    if (!ringing) stopSelf()
                } else {
                    ring(id)
                }
            }
        }
        return START_NOT_STICKY
    }

    private fun pi(action: String, code: Int): PendingIntent =
        PendingIntent.getService(
            this, code, Intent(this, AlarmRingService::class.java).setAction(action),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

    private fun ring(id: Int) {
        val a = AlarmStore(this).get(id)
        createChannel(this)
        val full = PendingIntent.getActivity(
            this, 4001,
            Intent(this, AlarmRingActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_USER_ACTION)
                .putExtra(AlarmScheduler.EXTRA_ID, id),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val lang = Prefs(this).lang
        val n = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_nine)
            .setContentTitle((a?.clock ?: "") + " · " + label(this, a))
            .setContentText(lang.t("Ketuk untuk membuka alarm", "Tap to open the alarm"))
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setOngoing(true)
            .setFullScreenIntent(full, true)
            .setContentIntent(full)
            .addAction(0, getString(R.string.alarm_snooze), pi(ACTION_SNOOZE, 4002))
            .addAction(0, getString(R.string.alarm_stop), pi(ACTION_STOP, 4003))
            .build()
        val type = if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK else 0
        try {
            ServiceCompat.startForeground(this, NOTIF_ID, n, type)
        } catch (e: Exception) {
            if (a != null) fallbackNotify(this, a)
            stopSelf()
            return
        }
        stopSound()
        ringingId = id
        acquireWake()
        startSound()
        startVibration()
        main.removeCallbacksAndMessages(null)
        main.postDelayed({ finish() }, MAX_RING_MS)
        notifyListeners()
        // Di HP yang layarnya sedang menyala, notifikasi layar penuh hanya tampil sebagai banner: buka layar alarm langsung.
        try {
            startActivity(Intent(this, AlarmRingActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK).putExtra(AlarmScheduler.EXTRA_ID, id))
        } catch (e: Exception) {
            // Android dapat memblokir pembukaan dari latar belakang; banner notifikasi tetap ada.
        }
    }

    private fun acquireWake() {
        if (wake?.isHeld == true) return
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wake = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "nine:alarm").apply { acquire(MAX_RING_MS + 5_000) }
    }

    private fun alarmUri(): Uri? =
        RingtoneManager.getActualDefaultRingtoneUri(this, RingtoneManager.TYPE_ALARM)
            ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
            ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
            ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)

    private val ramp = object : Runnable {
        override fun run() {
            val p = player ?: return
            volume = (volume + 0.05f).coerceAtMost(1f)
            try {
                p.setVolume(volume, volume)
            } catch (e: Exception) {
                return
            }
            if (volume < 1f) main.postDelayed(this, 1500)
        }
    }

    private fun startSound() {
        val uri = alarmUri() ?: return
        volume = 0.25f
        try {
            player = MediaPlayer().apply {
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ALARM)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build()
                )
                setDataSource(this@AlarmRingService, uri)
                isLooping = true
                setVolume(volume, volume)
                prepare()
                start()
            }
            main.postDelayed(ramp, 1500)
        } catch (e: Exception) {
            player?.release()
            player = null
        }
    }

    private fun startVibration() {
        val v = if (Build.VERSION.SDK_INT >= 31) {
            (getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager).defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        }
        vibrator = v
        if (!v.hasVibrator()) return
        val effect = VibrationEffect.createWaveform(longArrayOf(0, 700, 500), 0)
        try {
            @Suppress("DEPRECATION")
            v.vibrate(effect, AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM).build())
        } catch (e: Exception) {
            // abaikan
        }
    }

    private fun stopSound() {
        main.removeCallbacks(ramp)
        try {
            player?.stop()
        } catch (e: Exception) {
            // abaikan
        }
        player?.release()
        player = null
        try {
            vibrator?.cancel()
        } catch (e: Exception) {
            // abaikan
        }
    }

    private fun finish() {
        stopSound()
        main.removeCallbacksAndMessages(null)
        ringingId = -1
        try {
            if (wake?.isHeld == true) wake?.release()
        } catch (e: Exception) {
            // abaikan
        }
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
        notifyListeners()
    }

    private fun notifyListeners() {
        main.post { for (l in listeners.toList()) l() }
    }

    override fun onDestroy() {
        stopSound()
        if (ringingId != -1) {
            ringingId = -1
            notifyListeners()
        }
        try {
            if (wake?.isHeld == true) wake?.release()
        } catch (e: Exception) {
            // abaikan
        }
        super.onDestroy()
    }
}
