package id.nine.assistant.speech

import android.content.Context
import android.media.AudioAttributes
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import id.nine.assistant.core.AppLang
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * Suara cadangan: mesin Text-to-Speech bawaan HP (dipakai bila Gemini tidak bisa dipakai: offline, kunci kosong,
 * kuota habis). Mesin dibuat saat pertama dibutuhkan dan dilepas lagi setelah [IDLE_MS] tanpa dipakai, supaya
 * tidak menahan memori. Semua metode ucapan memblokir dan HARUS dipanggil dari thread latar, bukan main thread.
 */
class SystemTts(context: Context) {
    private val app = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private val lock = Any()

    private class Boot {
        val latch = CountDownLatch(1)

        @Volatile
        var status = PENDING
    }

    // Semua di bawah dijaga [lock].
    private var engine: TextToSpeech? = null
    private var starting: Boot? = null
    private var busy = 0
    private var lastUsed = 0L
    private var failedUntil = 0L
    private val picks = HashMap<String, VoicePicker.Pick>()

    private val counter = AtomicInteger()
    private val idle = Runnable { releaseIfIdle() }

    /**
     * Ucapkan [text] dan tunggu sampai selesai. [alive] dicek berkala: bila false, ucapan dihentikan.
     * [onStart] dipanggil (dari thread lain) saat suara mulai terdengar.
     * Hasil true = terdengar (atau dibatalkan oleh [alive]); false = mesin tidak bisa dipakai, pemanggil perlu cadangan.
     */
    fun speak(text: String, lang: AppLang, gender: VoiceGender, alive: () -> Boolean, onStart: () -> Unit): Boolean {
        val t = obtain() ?: return false
        try {
            return say(t, text, lang, gender, alive, onStart)
        } catch (e: Exception) {
            return false
        } finally {
            finish()
        }
    }

    /** Hentikan ucapan yang sedang berjalan (aman dari main thread; tidak membuat mesin baru). */
    fun stop() {
        val e = synchronized(lock) { if (busy > 0) engine else null }
        try {
            e?.stop()
        } catch (ex: Exception) {
            // mesin sudah mati
        }
    }

    /** Lepas mesin sekarang (saat aplikasi ditutup). */
    fun release() {
        main.removeCallbacks(idle)
        val e = synchronized(lock) {
            val x = engine
            engine = null
            starting = null
            picks.clear()
            x
        }
        shutdownQuietly(e)
    }

    // ------------------------------------------------------------------ mesin

    /** Ambil mesin siap pakai (menunggu inisialisasi paling lama [INIT_WAIT_MS]). Hasil non-null wajib diakhiri [finish]; pada kegagalan [finish] sudah dipanggil di sini. */
    private fun obtain(): TextToSpeech? {
        val h = synchronized(lock) {
            if (SystemClock.elapsedRealtime() < failedUntil) return null
            main.removeCallbacks(idle)
            val boot = starting ?: startEngine()
            busy++
            boot
        }
        val ready = try {
            h.latch.await(INIT_WAIT_MS, TimeUnit.MILLISECONDS) && h.status == TextToSpeech.SUCCESS
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            false
        }
        if (ready) {
            val e = synchronized(lock) { if (starting === h) engine else null }
            if (e != null) return e
            finish()
            return null
        }
        // Gagal: bila mesin memang menyatakan galat, buang dan jangan coba lagi sebentar; bila hanya lambat, biarkan.
        var dead: TextToSpeech? = null
        synchronized(lock) {
            if (h.latch.count == 0L && h.status != TextToSpeech.SUCCESS && starting === h) {
                dead = engine
                engine = null
                starting = null
                picks.clear()
                failedUntil = SystemClock.elapsedRealtime() + RETRY_AFTER_MS
            }
        }
        shutdownQuietly(dead)
        finish()
        return null
    }

    /** Buat mesin baru (dipanggil di dalam [lock]). */
    private fun startEngine(): Boot {
        val fresh = Boot()
        starting = fresh
        try {
            engine = TextToSpeech(app) { status ->
                fresh.status = status
                fresh.latch.countDown()
            }
        } catch (e: Exception) {
            fresh.status = TextToSpeech.ERROR
            fresh.latch.countDown()
        }
        return fresh
    }

    /** Selesai memakai mesin: turunkan [busy] dan jadwalkan pelepasan bila tak dipakai lagi. */
    private fun finish() {
        synchronized(lock) {
            busy--
            lastUsed = SystemClock.elapsedRealtime()
        }
        main.removeCallbacks(idle)
        main.postDelayed(idle, IDLE_MS)
    }

    private fun releaseIfIdle() {
        var e: TextToSpeech? = null
        synchronized(lock) {
            if (busy > 0) return // finish() menjadwalkan ulang
            val left = IDLE_MS - (SystemClock.elapsedRealtime() - lastUsed)
            if (left > 0) {
                main.postDelayed(idle, left)
                return
            }
            e = engine
            engine = null
            starting = null
            picks.clear()
        }
        shutdownQuietly(e)
    }

    private fun shutdownQuietly(e: TextToSpeech?) {
        try {
            e?.shutdown()
        } catch (ex: Exception) {
            // abaikan
        }
    }

    // ------------------------------------------------------------------ ucapan

    private fun say(t: TextToSpeech, text: String, lang: AppLang, gender: VoiceGender, alive: () -> Boolean, onStart: () -> Unit): Boolean {
        val loc = lang.ttsLocale
        if (t.setLanguage(loc) < 0) return false // data bahasa belum ada / tidak didukung
        val pick = pickVoice(t, loc.toLanguageTag(), gender)
        if (pick.name != null) {
            val v = try {
                t.voices?.firstOrNull { it.name == pick.name }
            } catch (e: Exception) {
                null
            }
            if (v != null) t.setVoice(v)
        }
        t.setPitch(pick.pitch)
        t.setSpeechRate(1.0f)
        t.setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ASSISTANT)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build()
        )

        val id = "nine-" + counter.incrementAndGet()
        val done = CountDownLatch(1)
        val started = AtomicBoolean(false)
        val failed = AtomicBoolean(false)
        t.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {
                if (utteranceId != id) return
                started.set(true)
                onStart()
            }

            override fun onDone(utteranceId: String?) {
                if (utteranceId == id) done.countDown()
            }

            @Suppress("OVERRIDE_DEPRECATION")
            override fun onError(utteranceId: String?) {
                if (utteranceId != id) return
                failed.set(true)
                done.countDown()
            }

            override fun onError(utteranceId: String?, errorCode: Int) {
                if (utteranceId != id) return
                failed.set(true)
                done.countDown()
            }

            override fun onStop(utteranceId: String?, interrupted: Boolean) {
                if (utteranceId == id) done.countDown()
            }
        })
        if (t.speak(text, TextToSpeech.QUEUE_FLUSH, null, id) != TextToSpeech.SUCCESS) return false

        val began = SystemClock.elapsedRealtime()
        while (true) {
            if (done.await(POLL_MS, TimeUnit.MILLISECONDS)) break
            if (!alive()) {
                t.stop()
                return true
            }
            val waited = SystemClock.elapsedRealtime() - began
            if (!started.get() && waited > START_WAIT_MS) {
                // Mesin diam: jangan membuat pengguna menunggu selamanya.
                t.stop()
                return false
            }
            if (waited > MAX_SPEAK_MS) {
                t.stop()
                return started.get()
            }
        }
        // Galat sebelum sempat bersuara = gagal (pemanggil memakai cadangan); galat di tengah ucapan = anggap sudah terdengar.
        return !failed.get() || started.get()
    }

    private fun pickVoice(t: TextToSpeech, tag: String, gender: VoiceGender): VoicePicker.Pick {
        val key = tag + "/" + gender.key
        synchronized(lock) { picks[key]?.let { return it } }
        val chosen = try {
            val infos = (t.voices ?: emptySet()).filter { it.features?.contains(TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED) != true }
                .map { VoiceInfo(it.name, it.locale.toLanguageTag(), it.isNetworkConnectionRequired, it.quality) }
            VoicePicker.pick(infos, tag, gender)
        } catch (e: Exception) {
            VoicePicker.Pick(null, VoicePicker.pitchFor(gender), false)
        }
        synchronized(lock) { picks[key] = chosen }
        return chosen
    }

    private companion object {
        const val PENDING = -100
        const val INIT_WAIT_MS = 5_000L
        const val RETRY_AFTER_MS = 60_000L
        const val IDLE_MS = 90_000L
        const val POLL_MS = 60L
        const val START_WAIT_MS = 10_000L
        const val MAX_SPEAK_MS = 90_000L
    }
}
