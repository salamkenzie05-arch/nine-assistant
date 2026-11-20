package id.nine.assistant.wake

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import id.nine.assistant.MainActivity
import id.nine.assistant.NineApp
import id.nine.assistant.R
import id.nine.assistant.service.AssistService
import org.json.JSONArray
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import org.vosk.android.RecognitionListener
import org.vosk.android.SpeechService
import java.util.concurrent.Executors

/**
 * Mendengarkan frasa pemanggil (mis. "hey nine") terus-menerus, sepenuhnya offline dengan Vosk.
 * Berjalan sebagai foreground service bertipe mikrofon; dijeda saat layar Nine terbuka agar mikrofon
 * bisa dipakai pengenal suara utama.
 */
class WakeWordService : Service(), RecognitionListener {

    companion object {
        private const val ACTION_START = "id.nine.assistant.wake.START"
        private const val ACTION_STOP = "id.nine.assistant.wake.STOP"
        private const val ACTION_PAUSE = "id.nine.assistant.wake.PAUSE"
        private const val ACTION_RESUME = "id.nine.assistant.wake.RESUME"
        private const val ACTION_RELOAD = "id.nine.assistant.wake.RELOAD"
        private const val CHANNEL = "nine_wake"
        private const val CHANNEL_ALERT = "nine_wake_alert"
        private const val NOTIF_ID = 9
        const val ALERT_ID = 99

        @Volatile
        var running = false
            private set

        @Volatile
        var status: String = ""
            private set

        private fun send(ctx: Context, action: String) {
            val i = Intent(ctx, WakeWordService::class.java).setAction(action)
            try {
                if (action == ACTION_START) ContextCompat.startForegroundService(ctx, i) else if (running) ctx.startService(i)
            } catch (e: Exception) {
                // Android bisa menolak memulai layanan mikrofon dari latar belakang; akan dicoba lagi saat app dibuka.
            }
        }

        fun start(ctx: Context) = send(ctx, ACTION_START)
        fun stop(ctx: Context) = send(ctx, ACTION_STOP)
        fun pause(ctx: Context) = send(ctx, ACTION_PAUSE)
        fun resume(ctx: Context) = send(ctx, ACTION_RESUME)
        fun reload(ctx: Context) = send(ctx, ACTION_RELOAD)

        /** Ubah daftar frasa ("hey nine, okay nine") menjadi frasa huruf kecil yang bersih. */
        fun parsePhrases(raw: String): List<String> = raw.split(',', ';', '\n')
            .map { it.lowercase().replace(Regex("[^a-z' ]"), " ").replace(Regex("\\s+"), " ").trim() }
            .filter { it.isNotEmpty() }
            .distinct()
    }

    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()
    private var model: Model? = null
    private var recognizer: Recognizer? = null
    private var speech: SpeechService? = null
    private var phrases: List<String> = emptyList()
    private var paused = false
    private var lastTrigger = 0L

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                shutdown()
                ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_PAUSE -> {
                paused = true
                stopListening()
                updateNotification()
                return START_STICKY
            }
            ACTION_RESUME -> {
                paused = false
                startListening()
                updateNotification()
                return START_STICKY
            }
            ACTION_RELOAD -> {
                recognizer?.close()
                recognizer = null
                stopListening()
                worker.execute { load() }
                return START_STICKY
            }
        }
        // START (atau dimulai ulang oleh sistem)
        goForeground()
        if (!running) {
            running = true
            worker.execute { load() }
        }
        return START_STICKY
    }

    private fun goForeground() {
        createChannels()
        val type = if (Build.VERSION.SDK_INT >= 30) ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE else 0
        try {
            ServiceCompat.startForeground(this, NOTIF_ID, notification(), type)
        } catch (e: Exception) {
            status = "start failed: ${e.message}"
            stopSelf()
        }
    }

    private fun createChannels() {
        if (Build.VERSION.SDK_INT < 26) return
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, getString(R.string.wake_channel), NotificationManager.IMPORTANCE_MIN).apply {
                setShowBadge(false)
            }
        )
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ALERT, getString(R.string.wake_alert_channel), NotificationManager.IMPORTANCE_HIGH)
        )
    }

    private fun openIntent(listen: Boolean): PendingIntent {
        val i = Intent(this, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            if (listen) putExtra(MainActivity.EXTRA_AUTO_LISTEN, true)
            putExtra(MainActivity.EXTRA_TOKEN, (application as NineApp).prefs.launchToken)
        }
        return PendingIntent.getActivity(this, if (listen) 2 else 1, i, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    private fun notification(): Notification {
        val stopIntent = PendingIntent.getService(
            this, 3, Intent(this, WakeWordService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE
        )
        val phraseText = phrases.ifEmpty { parsePhrases((application as NineApp).prefs.wakePhrases) }.joinToString(" / ") { "\"$it\"" }
        val text = when {
            status.isNotEmpty() -> status
            paused -> getString(R.string.wake_paused)
            else -> getString(R.string.wake_listening, phraseText)
        }
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_nine)
            .setContentTitle("Nine")
            .setContentText(text)
            .setOngoing(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setContentIntent(openIntent(false))
            .addAction(0, getString(R.string.wake_turn_off), stopIntent)
            .build()
    }

    private fun updateNotification() {
        try {
            getSystemService(NotificationManager::class.java).notify(NOTIF_ID, notification())
        } catch (e: Exception) {
            // abaikan
        }
    }

    /** Di thread worker. */
    private fun load() {
        try {
            status = getString(R.string.wake_loading)
            main.post { updateNotification() }
            val dir = VoskAssets.ensure(this)
            if (dir == null) {
                status = getString(R.string.wake_no_model)
                main.post { updateNotification() }
                return
            }
            val m = model ?: Model(dir.absolutePath).also { model = it }
            phrases = parsePhrases((application as NineApp).prefs.wakePhrases)
            val grammar = JSONArray().apply {
                phrases.forEach { put(it) }
                put("[unk]")
            }.toString()
            recognizer?.close()
            recognizer = Recognizer(m, 16000f, grammar)
            status = ""
            main.post {
                updateNotification()
                if (!paused) startListening()
            }
        } catch (e: Throwable) {
            status = getString(R.string.wake_error, e.message ?: e.javaClass.simpleName)
            main.post { updateNotification() }
        }
    }

    private fun startListening() {
        if (paused || speech != null) return
        val r = recognizer ?: return
        try {
            speech = SpeechService(r, 16000f).also { it.startListening(this) }
        } catch (e: Exception) {
            status = getString(R.string.wake_error, e.message ?: "mic")
            updateNotification()
            // mikrofon mungkin sedang dipakai aplikasi lain; coba lagi nanti
            main.postDelayed({ if (!paused) startListening() }, 5000)
        }
    }

    private fun stopListening() {
        try {
            speech?.stop()
            speech?.shutdown()
        } catch (e: Exception) {
            // abaikan
        }
        speech = null
    }

    private fun shutdown() {
        running = false
        stopListening()
        worker.execute {
            try {
                recognizer?.close()
                model?.close()
            } catch (e: Exception) {
                // abaikan
            }
            recognizer = null
            model = null
        }
    }

    override fun onDestroy() {
        shutdown()
        super.onDestroy()
    }

    // ------------------------------------------------------------------ RecognitionListener (Vosk)

    private fun check(json: String?, key: String) {
        val text = try {
            JSONObject(json ?: return).optString(key, "")
        } catch (e: Exception) {
            return
        }.trim()
        if (text.isEmpty() || text == "[unk]") return
        if (phrases.any { text == it || text.endsWith(" $it") || text.startsWith("$it ") }) trigger()
    }

    override fun onPartialResult(hypothesis: String?) = check(hypothesis, "partial")
    override fun onResult(hypothesis: String?) = check(hypothesis, "text")
    override fun onFinalResult(hypothesis: String?) = check(hypothesis, "text")

    override fun onError(exception: Exception?) {
        stopListening()
        main.postDelayed({ if (!paused) startListening() }, 2000)
    }

    override fun onTimeout() {
        stopListening()
        if (!paused) startListening()
    }

    private fun trigger() {
        val now = System.currentTimeMillis()
        if (now - lastTrigger < 3000) return
        lastTrigger = now
        // Lepaskan mikrofon agar pengenal suara utama bisa mendengar perintahnya.
        paused = true
        stopListening()
        updateNotification()

        // 1) Bila Nine adalah asisten digital default: buka lewat sesi asisten (diizinkan dari latar belakang).
        val viaAssistant = AssistService.showFromWakeWord()
        if (viaAssistant) return

        // 2) Coba buka layar Nine langsung (berhasil bila izin "tampil di atas aplikasi lain" diberikan).
        try {
            startActivity(
                Intent(this, MainActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                    putExtra(MainActivity.EXTRA_AUTO_LISTEN, true)
            putExtra(MainActivity.EXTRA_TOKEN, (application as NineApp).prefs.launchToken)
                    putExtra(MainActivity.EXTRA_FROM_WAKE, true)
                }
            )
        } catch (e: Exception) {
            // diabaikan; notifikasi di bawah sebagai cadangan
        }
        // 3) Cadangan: notifikasi yang bisa diketuk (dihapus otomatis bila layar Nine berhasil terbuka).
        try {
            val n = NotificationCompat.Builder(this, CHANNEL_ALERT)
                .setSmallIcon(R.drawable.ic_stat_nine)
                .setContentTitle("Nine")
                .setContentText(getString(R.string.wake_tap_to_talk))
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setCategory(NotificationCompat.CATEGORY_CALL)
                .setAutoCancel(true)
                .setTimeoutAfter(15_000)
                .setContentIntent(openIntent(true))
                .build()
            getSystemService(NotificationManager::class.java).notify(ALERT_ID, n)
        } catch (e: Exception) {
            // izin notifikasi mungkin tidak diberikan
        }
        // Bila layar Nine tidak muncul, kembali mendengarkan setelah 15 detik.
        main.postDelayed({
            if (paused && !MainActivity.visible) {
                paused = false
                startListening()
                updateNotification()
            }
        }, 15_000)
    }
}
