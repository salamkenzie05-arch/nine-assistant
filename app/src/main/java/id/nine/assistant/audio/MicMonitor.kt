package id.nine.assistant.audio

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import androidx.core.content.ContextCompat

/**
 * Merekam mikrofon di thread latar dan memanggil [onFrame] dengan jendela [window] sampel yang bergeser
 * setiap [hop] sampel. Dipakai tuner gitar dan reaksi musik. [onFrame] berjalan di thread latar.
 */
class MicMonitor(
    private val context: Context,
    private val sampleRate: Int,
    private val window: Int,
    private val hop: Int,
    private val onFrame: (pcm: ShortArray, n: Int) -> Unit
) {
    @Volatile
    private var running = false
    private var thread: Thread? = null
    private var record: AudioRecord? = null

    val isRunning: Boolean get() = running

    @SuppressLint("MissingPermission")
    private fun create(source: Int, bufSize: Int): AudioRecord? = try {
        val r = AudioRecord(source, sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, bufSize)
        if (r.state == AudioRecord.STATE_INITIALIZED) r else {
            r.release()
            null
        }
    } catch (e: Exception) {
        null
    }

    /** Mulai merekam. Mengembalikan false bila izin mikrofon belum ada atau mikrofon sedang dipakai aplikasi lain. */
    fun start(): Boolean {
        if (running) return true
        stop() // bersihkan sisa perekaman sebelumnya (mis. yang berhenti karena mikrofon direbut)
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) return false
        val min = AudioRecord.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        if (min <= 0) return false
        val bufSize = maxOf(min, hop * 4)
        // Tanpa pengolahan (AGC/peredam derau) lebih baik untuk mengukur nada dan ketukan; cadangannya mikrofon biasa.
        val rec = create(MediaRecorder.AudioSource.UNPROCESSED, bufSize) ?: create(MediaRecorder.AudioSource.MIC, bufSize) ?: return false
        try {
            rec.startRecording()
        } catch (e: Exception) {
            rec.release()
            return false
        }
        if (rec.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
            rec.release()
            return false
        }
        record = rec
        running = true
        thread = Thread({ loop(rec) }, "nine-mic").also { it.isDaemon = true; it.start() }
        return true
    }

    private fun loop(rec: AudioRecord) {
        val buf = ShortArray(window)
        val chunk = ShortArray(hop)
        var filled = 0
        try {
            while (running) {
                val n = rec.read(chunk, 0, hop)
                if (n <= 0) {
                    if (n < 0) break
                    continue
                }
                if (filled + n <= window) {
                    System.arraycopy(chunk, 0, buf, filled, n)
                    filled += n
                } else {
                    val keep = window - n
                    if (keep > 0) System.arraycopy(buf, window - keep, buf, 0, keep)
                    System.arraycopy(chunk, 0, buf, maxOf(keep, 0), minOf(n, window))
                    filled = window
                }
                if (filled >= window) {
                    try {
                        onFrame(buf, window)
                    } catch (e: Exception) {
                        // satu bingkai gagal tidak boleh menghentikan perekaman
                    }
                }
            }
        } catch (e: Exception) {
            // mikrofon direbut aplikasi lain
        } finally {
            running = false
        }
    }

    fun stop() {
        running = false
        val t = thread
        thread = null
        val r = record
        record = null
        try {
            r?.stop()
        } catch (e: Exception) {
            // sudah berhenti
        }
        try {
            t?.join(400)
        } catch (e: InterruptedException) {
            // abaikan
        }
        try {
            r?.release()
        } catch (e: Exception) {
            // abaikan
        }
    }
}
