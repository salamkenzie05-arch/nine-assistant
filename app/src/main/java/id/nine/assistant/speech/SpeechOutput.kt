package id.nine.assistant.speech

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Handler
import android.os.Looper
import id.nine.assistant.core.AppLang
import id.nine.assistant.core.Prefs
import java.util.concurrent.Executors

/**
 * Suara Nine: bunyi droid (bip, kicau, siulan) yang disintesis di perangkat oleh [DroidVoice]. Tidak ada suara
 * manusia dan tidak butuh mesin Text-to-Speech maupun internet. Isi jawaban tetap tampil sebagai teks.
 *
 * Mendukung ucapan bertahap: [beginStream] → [enqueue] (per kalimat) → [endStream].
 * Dipakai dari main thread; sintesis dan pemutaran berjalan di satu thread latar.
 */
class SpeechOutput(@Suppress("UNUSED_PARAMETER") context: Context, @Suppress("UNUSED_PARAMETER") prefs: Prefs, private val onNotice: (String) -> Unit) {

    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor { r -> Thread(r, "nine-droid") }
    private var counter = 0

    /** Naik setiap [stop]; pekerjaan lama yang melihat angka berbeda berhenti tanpa bunyi. */
    @Volatile
    private var generation = 0

    // Status aliran ucapan saat ini (main thread)
    private var streamOpen = false
    private var queued = 0
    private var lastQueuedId: String? = null
    private var finishedIds = HashSet<String>()
    private var streamDone: (() -> Unit)? = null
    private var onStartSpeaking: (() -> Unit)? = null

    /** Tidak ada TTS yang perlu disiapkan; dipertahankan agar pemanggil lama tetap jalan. */
    fun setOnReady(f: () -> Unit) {
        f()
    }

    private fun finished(utteranceId: String) {
        finishedIds.add(utteranceId)
        checkStreamDone()
    }

    private fun checkStreamDone() {
        if (streamOpen) return
        val last = lastQueuedId
        if (last == null || finishedIds.contains(last)) {
            val done = streamDone
            streamDone = null
            lastQueuedId = null
            finishedIds.clear()
            done?.invoke()
        }
    }

    // ------------------------------------------------------------------ API

    /** Bunyikan satu teks utuh. */
    fun speak(text: String, lang: AppLang, onStart: (() -> Unit)? = null, onDone: () -> Unit) {
        beginStream(lang, onStart)
        enqueue(text)
        endStream(onDone)
    }

    /** Mulai aliran bunyi baru (menghentikan yang lama). [lang] dipertahankan agar API tidak berubah. */
    @Suppress("UNUSED_PARAMETER")
    fun beginStream(lang: AppLang, onStart: (() -> Unit)? = null) {
        stop()
        streamOpen = true
        queued = 0
        onStartSpeaking = onStart
    }

    fun enqueue(sentence: String) {
        val text = sentence.trim()
        if (text.isEmpty() || !streamOpen) return
        // Droid cukup "berkicau" untuk beberapa kalimat pertama; sisanya dibaca di layar.
        if (queued >= MAX_SENTENCES) return
        queued++
        val id = "nine-" + (++counter)
        lastQueuedId = id
        val gen = generation
        worker.execute {
            if (gen != generation) return@execute
            val pcm = try {
                DroidVoice.render(text)
            } catch (e: Exception) {
                ShortArray(0)
            }
            if (gen != generation) return@execute
            main.post {
                if (gen == generation) {
                    val f = onStartSpeaking
                    onStartSpeaking = null
                    f?.invoke()
                }
            }
            play(pcm, gen)
            main.post { if (gen == generation) finished(id) }
        }
    }

    /** Tidak ada kalimat lagi; [onDone] dipanggil setelah semua selesai berbunyi. */
    fun endStream(onDone: () -> Unit) {
        streamOpen = false
        streamDone = onDone
        checkStreamDone()
    }

    /** Hentikan bunyi tanpa memanggil callback selesai. */
    fun stop() {
        generation++
        streamOpen = false
        streamDone = null
        onStartSpeaking = null
        lastQueuedId = null
        finishedIds.clear()
    }

    fun shutdown() {
        stop()
        worker.shutdownNow()
    }

    // ------------------------------------------------------------------ pemutaran

    /** Putar [pcm] sampai selesai atau sampai [gen] tidak berlaku lagi. Dipanggil di thread latar. */
    private fun play(pcm: ShortArray, gen: Int) {
        if (pcm.isEmpty()) return
        var track: AudioTrack? = null
        try {
            val attrs = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ASSISTANT)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build()
            val format = AudioFormat.Builder()
                .setSampleRate(DroidVoice.SAMPLE_RATE)
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                .build()
            val t = AudioTrack.Builder()
                .setAudioAttributes(attrs)
                .setAudioFormat(format)
                .setBufferSizeInBytes(pcm.size * 2)
                .setTransferMode(AudioTrack.MODE_STATIC)
                .build()
            track = t
            t.write(pcm, 0, pcm.size)
            t.play()
            while (gen == generation && t.playState == AudioTrack.PLAYSTATE_PLAYING && t.playbackHeadPosition < pcm.size - 1) {
                Thread.sleep(10)
            }
        } catch (e: Exception) {
            main.post { if (gen == generation) onNotice("Suara tidak bisa diputar / Sound could not be played.") }
        } finally {
            try {
                track?.stop()
            } catch (e: Exception) {
                // sudah berhenti
            }
            try {
                track?.release()
            } catch (e: Exception) {
                // abaikan
            }
        }
    }

    private companion object {
        const val MAX_SENTENCES = 3
    }
}
