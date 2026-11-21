package id.nine.assistant.speech

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import id.nine.assistant.core.AppLang
import id.nine.assistant.core.Mood
import id.nine.assistant.core.Prefs
import id.nine.assistant.llm.Failure
import id.nine.assistant.llm.Failures
import java.util.concurrent.Callable
import java.util.concurrent.CancellationException
import java.util.concurrent.ExecutionException
import java.util.concurrent.Future
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ThreadFactory
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/**
 * Suara Nine, tiga pilihan di Pengaturan: **Droid** (bawaan; bunyi bip/kicau [DroidVoice], tanpa internet), **Wanita**, **Pria**.
 * Untuk Wanita/Pria urutannya:
 *  1. Gemini TTS (suara AI sangat natural, memakai kunci Gemini pengguna) bila mode internet menyala, ada kunci, ada jaringan;
 *  2. bila gagal/tidak tersedia: mesin Text-to-Speech bawaan HP ([SystemTts]);
 *  3. bila itu juga tidak ada: bunyi droid ([DroidVoice]), supaya Nine tetap "bersuara".
 * Nyanyian dan nada acuan tetap lewat [enqueueAudio].
 *
 * Ucapan bertahap: [beginStream] → [enqueue] (per kalimat) → [endStream]. Dengan Gemini, kalimat pertama langsung
 * disintesis dan sisanya digabung jadi satu permintaan (paling banyak 2 permintaan per jawaban, hemat kuota);
 * sintesis potongan berikutnya berjalan selagi potongan pertama diputar. Dipakai dari main thread.
 */
class SpeechOutput(context: Context, private val prefs: Prefs, private val onNotice: (String) -> Unit) {

    private val app = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private val worker = pool(1, "nine-voice")
    private val renderer = pool(2, "nine-render")
    private val system = SystemTts(app)
    private var counter = 0

    /** Naik setiap [stop]; pekerjaan lama yang melihat angka berbeda berhenti tanpa bunyi. */
    @Volatile
    private var generation = 0

    /** Dipanggil (main thread) setelah satu potong terdengar: suara apa yang dipakai dan kenapa. Dipakai tombol "Coba suara". */
    var onEngine: ((String) -> Unit)? = null

    // Status aliran ucapan saat ini (main thread)
    private var streamOpen = false
    private var lastQueuedId: String? = null
    private var finishedIds = HashSet<String>()
    private var streamDone: (() -> Unit)? = null
    private var onStartSpeaking: (() -> Unit)? = null
    private var lang = AppLang.ID
    private var gender = VoiceGender.FEMALE
    private var droid = true // pilihan Droid: tanpa Gemini maupun suara HP
    private var droidCount = 0
    private var style: String? = null
    private var ttsKey: String? = null // bukan null = sintesis Gemini dipakai untuk aliran ini
    private var ttsSkip: String? = null // bila ttsKey null: alasan Gemini tidak dipakai (untuk laporan "Coba suara")
    private var sentences = 0
    private var spokenChars = 0
    private val remainder = StringBuilder()
    private val pending = ArrayList<Future<*>>()
    private val pendingTts = ArrayList<GeminiTts>()

    private fun pool(n: Int, name: String) = ThreadPoolExecutor(
        n, n, 30, TimeUnit.SECONDS, LinkedBlockingQueue<Runnable>(),
        ThreadFactory { r -> Thread(r, name).apply { isDaemon = true } }
    ).apply { allowCoreThreadTimeOut(true) }

    private fun finished(id: String) {
        finishedIds.add(id)
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

    /** Ucapkan satu teks utuh; [mood] (ekspresi Nine) mewarnai nada bicara Gemini. */
    fun speak(text: String, lang: AppLang, onStart: (() -> Unit)? = null, mood: Mood? = null, onDone: () -> Unit) {
        beginStream(lang, onStart)
        setMood(mood)
        enqueue(text)
        endStream(onDone)
    }

    /** Mulai aliran ucapan baru (menghentikan yang lama). */
    fun beginStream(lang: AppLang, onStart: (() -> Unit)? = null) {
        stop()
        streamOpen = true
        this.lang = lang
        val choice = NineVoice.of(prefs.voice)
        droid = choice == NineVoice.DROID
        gender = choice.gender ?: VoiceGender.FEMALE
        droidCount = 0
        style = null
        sentences = 0
        spokenChars = 0
        remainder.setLength(0)
        onStartSpeaking = onStart
        if (droid) {
            ttsKey = null
            ttsSkip = null
        } else {
            gate()
        }
    }

    /** Ekspresi Nine untuk jawaban ini (menentukan gaya bicara suara Gemini). Panggil sebelum [enqueue] pertama. */
    fun setMood(mood: Mood?) {
        style = SpeechText.style(mood)
    }

    fun enqueue(sentence: String) {
        val text = sentence.trim()
        if (text.isEmpty() || !streamOpen) return
        if (droid) {
            // Droid cukup "berkicau" untuk beberapa kalimat pertama; sisanya dibaca di layar.
            if (droidCount >= DROID_MAX_SENTENCES) return
            droidCount++
            enqueueAudio(DroidVoice.SAMPLE_RATE) { DroidVoice.render(text) }
            return
        }
        if (ttsKey != null) {
            // Gemini: kalimat pertama segera, sisanya satu permintaan di akhir (lihat flushRemainder).
            if (sentences++ == 0) {
                submitText(text)
            } else if (remainder.length < REMAINDER_MAX) {
                if (remainder.isNotEmpty()) remainder.append(' ')
                remainder.append(text)
            }
        } else {
            // Suara HP: tiap kalimat langsung diucapkan, dengan batas total supaya jawaban panjang tidak dibacakan habis.
            if (spokenChars >= LOCAL_MAX_CHARS) return
            spokenChars += text.length
            submitText(text)
        }
    }

    /**
     * Antrekan satu potong bunyi: [render] dijalankan di thread latar (bisa lama, mis. nyanyian) lalu diputar
     * pada [rate] Hz. Harus setelah [beginStream]; dihitung sebagai satu "kalimat" aliran.
     */
    fun enqueueAudio(rate: Int, render: () -> ShortArray) {
        if (!streamOpen) return
        flushRemainder() // urutan: ucapan dulu, baru nyanyian
        val id = nextId()
        lastQueuedId = id
        val gen = generation
        val fut = renderer.submit(Callable { render() })
        pending.add(fut)
        worker.execute {
            if (gen != generation) return@execute
            val pcm = await(fut, gen) ?: ShortArray(0)
            if (gen != generation) return@execute
            notifyStart(gen)
            play(pcm, rate, gen, speech = false)
            main.post { if (gen == generation) finished(id) }
        }
    }

    /** Tidak ada kalimat lagi; [onDone] dipanggil setelah semua selesai berbunyi. */
    fun endStream(onDone: () -> Unit) {
        flushRemainder()
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
        remainder.setLength(0)
        for (f in pending) f.cancel(true)
        pending.clear()
        for (t in pendingTts) t.abort()
        pendingTts.clear()
        system.stop()
    }

    fun shutdown() {
        stop()
        worker.shutdownNow()
        renderer.shutdownNow()
        system.release()
    }

    // ------------------------------------------------------------------ antrean

    private fun nextId() = "nine-" + (++counter)

    private fun flushRemainder() {
        if (remainder.isEmpty()) return
        val rest = remainder.toString()
        remainder.setLength(0)
        submitText(rest)
    }

    /** Antrekan satu potong ucapan; Gemini mulai disintesis sekarang (di thread lain) selagi potongan sebelumnya diputar. */
    private fun submitText(raw: String) {
        val text = SpeechText.cap(raw, GeminiTts.MAX_CHARS)
        if (text.isEmpty()) return
        val id = nextId()
        lastQueuedId = id
        val gen = generation
        val l = lang
        val gd = gender
        val st = style
        val sk = ttsSkip
        var tts: GeminiTts? = null
        var fut: Future<TtsResult>? = null
        val key = ttsKey
        if (key != null) {
            val g = GeminiTts(key)
            tts = g
            pendingTts.add(g)
            val f = renderer.submit(Callable { g.synthesize(text, gd, st) })
            fut = f
            pending.add(f)
        }
        worker.execute { speakJob(gen, id, text, l, gd, tts, fut, sk) }
    }

    // ------------------------------------------------------------------ kerja di thread suara

    private fun speakJob(gen: Int, id: String, text: String, lang: AppLang, gender: VoiceGender, tts: GeminiTts?, fut: Future<TtsResult>?, skip: String?) {
        if (gen != generation) return
        val res = if (fut != null) await(fut, gen) else null
        if (gen != generation) return
        val why: Failure? = res?.failures?.lastOrNull()?.second ?: tts?.coolingFailure()
        val pcm = res?.pcm
        if (pcm != null) {
            report(gen, "Gemini · " + (res?.model ?: "TTS") + " · " + gender.geminiVoice)
            notifyStart(gen)
            play(pcm.samples, pcm.rate, gen, speech = true)
        } else {
            val viaPhone = system.speak(text, lang, gender, { gen == generation }, { notifyStart(gen) })
            if (gen != generation) return
            val reason = (why?.let { Failures.describe(it, lang) } ?: skip)?.let { " (Gemini: $it)" } ?: ""
            if (viaPhone) {
                report(gen, lang.t("Suara HP", "Phone voice") + reason)
            } else {
                report(gen, lang.t("Bunyi droid (tidak ada suara HP)", "Droid sound (no phone voice)") + reason)
                notifyStart(gen)
                play(DroidVoice.render(text), DroidVoice.SAMPLE_RATE, gen, speech = false)
            }
        }
        main.post { if (gen == generation) finished(id) }
    }

    private fun report(gen: Int, what: String) {
        main.post { if (gen == generation) onEngine?.invoke(what) }
    }

    private fun notifyStart(gen: Int) {
        main.post {
            if (gen == generation) {
                val f = onStartSpeaking
                onStartSpeaking = null
                f?.invoke()
            }
        }
    }

    /** Tunggu hasil kerja latar; null bila dibatalkan, gagal, atau terlalu lama. */
    private fun <T> await(f: Future<T>, gen: Int): T? {
        return try {
            f.get(AWAIT_MAX_SEC, TimeUnit.SECONDS)
        } catch (e: TimeoutException) {
            f.cancel(true)
            null
        } catch (e: CancellationException) {
            null
        } catch (e: ExecutionException) {
            null
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            null
        } finally {
            if (gen != generation) f.cancel(true)
        }
    }

    // ------------------------------------------------------------------ jaringan

    /** Tentukan apakah suara Gemini boleh dipakai untuk aliran ini (mode internet, kunci, jaringan, model tidak sedang istirahat). */
    private fun gate() {
        ttsKey = null
        ttsSkip = null
        if (!prefs.internetMode) {
            ttsSkip = lang.t("mode internet mati", "internet mode is off")
            return
        }
        val key = prefs.geminiKey
        if (key.isBlank()) {
            ttsSkip = lang.t("belum ada kunci", "no key yet")
            return
        }
        if (!online()) {
            ttsSkip = lang.t("tidak ada internet", "no internet")
            return
        }
        val probe = GeminiTts(key)
        if (!probe.available()) {
            ttsSkip = probe.coolingFailure()?.let { Failures.describe(it, lang) } ?: lang.t("sedang istirahat", "resting")
            return
        }
        ttsKey = key
    }

    private fun online(): Boolean = try {
        val cm = app.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val caps = cm.getNetworkCapabilities(cm.activeNetwork)
        caps != null && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    } catch (e: Exception) {
        false
    }

    // ------------------------------------------------------------------ pemutaran

    /** Putar [pcm] sampai selesai atau sampai [gen] tidak berlaku lagi. Dipanggil di thread latar. */
    private fun play(pcm: ShortArray, rate: Int, gen: Int, speech: Boolean) {
        if (pcm.isEmpty() || rate <= 0) return
        var track: AudioTrack? = null
        try {
            val attrs = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ASSISTANT)
                .setContentType(if (speech) AudioAttributes.CONTENT_TYPE_SPEECH else AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build()
            val format = AudioFormat.Builder()
                .setSampleRate(rate)
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
            val durationMs = pcm.size * 1000L / rate
            val deadline = SystemClock.elapsedRealtime() + durationMs + 3_000L
            while (gen == generation && t.playState == AudioTrack.PLAYSTATE_PLAYING) {
                val head = t.playbackHeadPosition
                if (head >= pcm.size - 1) break
                if (SystemClock.elapsedRealtime() > deadline) break // pemutar macet: jangan menahan antrean
                // Tidur lebih lama saat masih jauh dari ujung (hemat CPU), dan makin rapat menjelang selesai agar jeda antarkalimat tetap rapat.
                val remainMs = (pcm.size - head) * 1000L / rate
                Thread.sleep(remainMs.coerceIn(5L, 40L))
            }
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
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
        /** Batas teks yang menunggu di akhir aliran Gemini (dipotong lagi ke [GeminiTts.MAX_CHARS] saat dikirim). */
        const val REMAINDER_MAX = 2_000

        /** Batas total huruf yang dibacakan suara HP dalam satu jawaban. */
        const val LOCAL_MAX_CHARS = 700

        const val AWAIT_MAX_SEC = 60L

        /** Jumlah kalimat yang "dikicaukan" suara Droid per jawaban. */
        const val DROID_MAX_SENTENCES = 3
    }
}
