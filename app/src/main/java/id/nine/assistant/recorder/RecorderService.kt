package id.nine.assistant.recorder

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.MediaRecorder
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.IBinder
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.provider.MediaStore
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import id.nine.assistant.MainActivity
import id.nine.assistant.R
import id.nine.assistant.wake.WakeWordService
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * Perekam suara Nine. Berjalan sebagai foreground service bertipe mikrofon agar tetap merekam saat layar mati
 * atau Anda membuka aplikasi lain. Hasil disimpan ke Recordings/Nine (terlihat di aplikasi File / Perekam Suara).
 */
class RecorderService : Service() {

    companion object {
        private const val ACTION_START = "id.nine.assistant.rec.START"
        private const val ACTION_STOP = "id.nine.assistant.rec.STOP"
        private const val CHANNEL = "nine_recorder"
        private const val NOTIF_ID = 19

        @Volatile
        var recording = false
            private set

        /** Waktu mulai (elapsedRealtime) untuk menampilkan durasi. */
        @Volatile
        var startedAt = 0L
            private set

        /** Nama file rekaman terakhir yang selesai disimpan. */
        @Volatile
        var lastSaved: String? = null
            private set

        @Volatile
        var lastError: String? = null
            private set

        fun start(ctx: Context) {
            try {
                ContextCompat.startForegroundService(ctx, Intent(ctx, RecorderService::class.java).setAction(ACTION_START))
            } catch (e: Exception) {
                lastError = e.message
            }
        }

        fun stop(ctx: Context) {
            if (!recording) return
            ctx.startService(Intent(ctx, RecorderService::class.java).setAction(ACTION_STOP))
        }
    }

    private var recorder: MediaRecorder? = null
    private var pfd: ParcelFileDescriptor? = null
    private var uri: Uri? = null
    private var file: File? = null
    private var name = ""

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                finishRecording()
                ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
            ACTION_START -> if (!recording) begin()
            else -> stopSelf()
        }
        return START_NOT_STICKY
    }

    private fun begin() {
        createChannel()
        val type = if (Build.VERSION.SDK_INT >= 30) ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE else 0
        try {
            ServiceCompat.startForeground(this, NOTIF_ID, notification(), type)
        } catch (e: Exception) {
            lastError = e.message
            stopSelf()
            return
        }
        // Lepaskan mikrofon dari wake word selama merekam.
        WakeWordService.pause(this)
        name = "Nine " + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH.mm.ss")) + ".m4a"
        try {
            val r = if (Build.VERSION.SDK_INT >= 31) MediaRecorder(this) else @Suppress("DEPRECATION") MediaRecorder()
            r.setAudioSource(MediaRecorder.AudioSource.MIC)
            r.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            r.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            r.setAudioEncodingBitRate(128_000)
            r.setAudioSamplingRate(44_100)
            if (Build.VERSION.SDK_INT >= 29) {
                val values = ContentValues().apply {
                    put(MediaStore.Audio.Media.DISPLAY_NAME, name)
                    put(MediaStore.Audio.Media.MIME_TYPE, "audio/mp4")
                    put(MediaStore.Audio.Media.RELATIVE_PATH, Environment.DIRECTORY_RECORDINGS + "/Nine")
                    put(MediaStore.Audio.Media.IS_PENDING, 1)
                }
                val u = contentResolver.insert(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, values)
                    ?: throw IllegalStateException("MediaStore")
                uri = u
                val fd = contentResolver.openFileDescriptor(u, "w") ?: throw IllegalStateException("fd")
                pfd = fd
                r.setOutputFile(fd.fileDescriptor)
            } else {
                val dir = File(getExternalFilesDir(Environment.DIRECTORY_MUSIC), "Nine").apply { mkdirs() }
                val f = File(dir, name)
                file = f
                r.setOutputFile(f.absolutePath)
            }
            r.prepare()
            r.start()
            recorder = r
            recording = true
            startedAt = SystemClock.elapsedRealtime()
            lastError = null
        } catch (e: Exception) {
            lastError = e.message ?: e.javaClass.simpleName
            cleanupFailed()
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    private fun finishRecording() {
        val r = recorder ?: return
        try {
            r.stop()
        } catch (e: Exception) {
            // rekaman terlalu pendek
        }
        try {
            r.release()
        } catch (e: Exception) {
            // abaikan
        }
        recorder = null
        try {
            pfd?.close()
        } catch (e: Exception) {
            // abaikan
        }
        pfd = null
        uri?.let { u ->
            if (Build.VERSION.SDK_INT >= 29) {
                try {
                    contentResolver.update(u, ContentValues().apply { put(MediaStore.Audio.Media.IS_PENDING, 0) }, null, null)
                } catch (e: Exception) {
                    // abaikan
                }
            }
        }
        lastSaved = name
        recording = false
        uri = null
        file = null
        WakeWordService.resume(this)
    }

    private fun cleanupFailed() {
        try {
            recorder?.release()
        } catch (e: Exception) {
            // abaikan
        }
        recorder = null
        try {
            pfd?.close()
        } catch (e: Exception) {
            // abaikan
        }
        pfd = null
        uri?.let {
            try {
                contentResolver.delete(it, null, null)
            } catch (e: Exception) {
                // abaikan
            }
        }
        uri = null
        recording = false
        WakeWordService.resume(this)
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < 26) return
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, getString(R.string.rec_channel), NotificationManager.IMPORTANCE_LOW)
        )
    }

    private fun notification(): Notification {
        val stop = PendingIntent.getService(
            this, 21, Intent(this, RecorderService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE
        )
        val open = PendingIntent.getActivity(
            this, 22, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP), PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_nine)
            .setContentTitle(getString(R.string.rec_notif_title))
            .setContentText(getString(R.string.rec_notif_text))
            .setUsesChronometer(true)
            .setWhen(System.currentTimeMillis())
            .setOngoing(true)
            .setContentIntent(open)
            .addAction(0, getString(R.string.rec_stop), stop)
            .build()
    }

    override fun onDestroy() {
        if (recording) finishRecording()
        super.onDestroy()
    }
}
