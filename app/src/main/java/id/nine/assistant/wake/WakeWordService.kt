package id.nine.assistant.wake

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.annotation.SuppressLint
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
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
import java.util.concurrent.Executors
import kotlin.math.max

/**
 * Mendengarkan frasa pemanggil (mis. "hey nine") terus-menerus, sepenuhnya offline dengan Vosk.
 * Berjalan sebagai foreground service bertipe mikrofon; dijeda saat layar Nine terbuka agar mikrofon
 * bisa dipakai pengenal suara utama.
 *
 * Hemat daya: audio dibaca langsung dari mikrofon dan hanya diteruskan ke Vosk saat ada suara ([VoiceGate]),
 * jadi selama hening pengenal tidak bekerja sama sekali. Bila HP terlalu panas (thermal SEVERE ke atas),
 * pendengar dijeda sendiri sampai suhu turun.
 */
class WakeWordService : Service() {

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

        private const val SAMPLE_RATE = 16000

        /** Ubah daftar frasa ("hey nine, okay nine") menjadi frasa huruf kecil yang bersih. */
        fun parsePhrases(raw: String): List<String> = raw.split(',', ';', '\n')
            .map { it.lowercase().replace(Regex("[^a-z' ]"), " ").replace(Regex("\\s+"), " ").trim() }
            .filter { it.isNotEmpty() }
            .distinct()
    }

    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()
    private var model: Model? = null
    @Volatile
    private var recognizer: Recognizer? = null

    @Volatile
    private var listener: Listener? = null

    @Volatile
    private var phrases: List<String> = emptyList()
    private var paused = false

    /** HP terlalu panas: pendengar dijeda sampai suhu turun. */
    private var hot = false
    private var thermalListener: Any? = null
    private var lastTrigger = 0L

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        if (Build.VERSION.SDK_INT >= 29) {
            try {
                val l = PowerManager.OnThermalStatusChangedListener { s -> onThermal(s) }
                thermalListener = l
                getSystemService(PowerManager::class.java).addThermalStatusListener(ContextCompat.getMainExecutor(this), l)
            } catch (e: Exception) {
                // tanpa pemantau suhu, pendengar tetap jalan seperti biasa
            }
        }
    }

    /** Di thread utama. Jeda mendengarkan saat HP sangat panas, lanjutkan saat sudah turun. */
    private fun onThermal(level: Int) {
        val nowHot = level >= PowerManager.THERMAL_STATUS_SEVERE
        if (nowHot == hot) return
        hot = nowHot
        if (nowHot) {
            stopListening()
            status = getString(R.string.wake_hot)
        } else {
            if (status == getString(R.string.wake_hot)) status = ""
            if (!paused) startListening()
        }
        updateNotification()
    }

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
                stopListening()?.awaitEnd(200) // mikrofon harus sudah lepas sebelum pengenal suara utama memakainya
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
                // Pengenal lama baru boleh ditutup setelah pembacanya berhenti (kalau tidak, bisa crash di kode native).
                val old = stopListening()
                val oldRec = recognizer
                recognizer = null
                worker.execute {
                    old?.awaitEnd()
                    try {
                        oldRec?.close()
                    } catch (e: Exception) {
                        // abaikan
                    }
                    load()
                }
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
            stopListening()?.awaitEnd()
            val old = recognizer
            recognizer = null
            old?.close()
            recognizer = Recognizer(m, SAMPLE_RATE.toFloat(), grammar)
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

    private val lock = Any()
    private var retryMs = 5_000L
    private var micError = false

    private fun startListening() {
        if (!running || paused || hot) return
        val r = recognizer ?: return
        synchronized(lock) {
            if (listener != null) return
            listener = Listener(r).also { it.start() }
        }
    }

    /** Minta pembaca mikrofon berhenti (tidak menunggu). Mengembalikan pembaca itu agar pemanggil bisa menunggunya selesai. */
    private fun stopListening(): Listener? = synchronized(lock) {
        val l = listener
        listener = null
        l?.requestStop()
        l
    }

    /** Di thread utama: pembaca [l] gagal (mikrofon dipakai aplikasi lain, izin dicabut, dll.). Coba lagi dengan jeda yang makin panjang. */
    private fun onListenFailed(l: Listener, msg: String) {
        synchronized(lock) {
            if (listener !== l) return
            listener = null
        }
        micError = true
        status = getString(R.string.wake_error, msg)
        updateNotification()
        main.postDelayed({ if (!paused) startListening() }, retryMs)
        retryMs = (retryMs * 2).coerceAtMost(60_000L)
    }

    private fun onListenerStarted(l: Listener) {
        if (listener !== l) return
        retryMs = 5_000L
        if (micError) {
            micError = false
            status = ""
            updateNotification()
        }
    }

    private fun shutdown() {
        running = false
        val l = stopListening()
        worker.execute {
            l?.awaitEnd()
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
        main.removeCallbacksAndMessages(null)
        if (Build.VERSION.SDK_INT >= 29) {
            (thermalListener as? PowerManager.OnThermalStatusChangedListener)?.let {
                try {
                    getSystemService(PowerManager::class.java).removeThermalStatusListener(it)
                } catch (e: Exception) {
                    // abaikan
                }
            }
        }
        thermalListener = null
        shutdown()
        super.onDestroy()
    }

    // ------------------------------------------------------------------ pembaca mikrofon + gerbang suara

    /**
     * Thread yang memiliki AudioRecord: membaca bingkai 20 ms, memberikannya ke [VoiceGate], dan hanya saat ada suara
     * audio masuk ke pengenal Vosk. Pengenal hanya disentuh dari thread ini; hasilnya dikirim ke thread utama
     * hanya bila cocok dengan frasa pemanggil.
     */
    private inner class Listener(private val rec: Recognizer) : Thread("nine-wake") {
        @Volatile
        private var stopRequested = false

        fun requestStop() {
            stopRequested = true
        }

        fun awaitEnd(ms: Long = 3000L) {
            if (currentThread() === this) return
            try {
                join(ms)
            } catch (e: InterruptedException) {
                currentThread().interrupt()
            }
        }

        private fun fail(msg: String) {
            if (!stopRequested) main.post { onListenFailed(this@Listener, msg) }
        }

        private fun textOf(json: String?, key: String): String = try {
            JSONObject(json ?: return "").optString(key, "").trim()
        } catch (e: Exception) {
            ""
        }

        private fun emit(text: String) {
            if (text.isNotEmpty() && WakePhrase.matches(text, phrases)) main.post { if (listener === this@Listener) trigger() }
        }

        @SuppressLint("MissingPermission")
        override fun run() {
            var audio: AudioRecord? = null
            try {
                val minBuf = AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
                if (minBuf <= 0) {
                    fail("mic")
                    return
                }
                val a = AudioRecord(
                    MediaRecorder.AudioSource.VOICE_RECOGNITION, SAMPLE_RATE,
                    AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
                    max(minBuf, SAMPLE_RATE * 2) // ~1 detik cadangan agar tidak ada audio yang hilang
                )
                audio = a
                if (a.state != AudioRecord.STATE_INITIALIZED) {
                    fail("mic")
                    return
                }
                a.startRecording()
                if (a.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
                    fail("mic")
                    return
                }
                main.post { onListenerStarted(this@Listener) }

                val gate = VoiceGate()
                val frame = ShortArray(gate.frameSamples)
                val sink = object : VoiceGate.Sink {
                    var chunks = 0
                    var lastPartial = ""

                    override fun onAudio(buf: ShortArray, len: Int) {
                        if (rec.acceptWaveForm(buf, len)) {
                            chunks = 0
                            lastPartial = ""
                            emit(textOf(rec.result, "text"))
                        } else if (++chunks >= 5) { // hipotesis sementara tiap ~100 ms cukup untuk kata pemanggil
                            chunks = 0
                            val p = textOf(rec.partialResult, "partial")
                            if (p.isNotEmpty() && p != lastPartial) {
                                lastPartial = p
                                emit(p)
                            }
                        }
                    }

                    override fun onUtteranceEnd() {
                        chunks = 0
                        lastPartial = ""
                        emit(textOf(rec.finalResult, "text"))
                        rec.reset() // mulai ucapan berikutnya dari nol, apa pun keadaan dalam pengenal
                    }
                }

                while (!stopRequested) {
                    val n = a.read(frame, 0, frame.size)
                    if (n < 0) {
                        fail("mic ($n)")
                        return
                    }
                    if (n > 0) gate.feed(frame, n, sink)
                }
            } catch (e: Throwable) {
                fail(e.message ?: "mic")
            } finally {
                try {
                    audio?.stop()
                } catch (e: Exception) {
                    // abaikan
                }
                audio?.release()
            }
        }
    }

    private fun trigger() {
        val now = System.currentTimeMillis()
        if (now - lastTrigger < 3000) return
        lastTrigger = now
        // Lepaskan mikrofon agar pengenal suara utama bisa mendengar perintahnya.
        paused = true
        stopListening()?.awaitEnd(200)
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
