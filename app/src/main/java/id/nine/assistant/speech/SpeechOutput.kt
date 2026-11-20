package id.nine.assistant.speech

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import id.nine.assistant.core.AppLang
import id.nine.assistant.core.Prefs

/**
 * Suara Nine memakai Text-to-Speech bawaan Android (offline). Mengutamakan mesin Google bila terpasang
 * (punya suara Indonesia dan Inggris-UK), memilih suara pria yang tidak butuh jaringan, dengan nada sedikit
 * lebih rendah agar terdengar tenang seperti kepala pelayan.
 *
 * Mendukung ucapan bertahap: [beginStream] → [enqueue] (per kalimat) → [endStream].
 * Dipakai dari main thread.
 */
class SpeechOutput(context: Context, private val prefs: Prefs, private val onNotice: (String) -> Unit) {

    private val main = Handler(Looper.getMainLooper())
    private val app = context.applicationContext
    private var tts: TextToSpeech? = null
    private var ready = false
    private var failed = false
    private var counter = 0
    private val warned = HashSet<AppLang>()
    private val pendingActions = ArrayList<() -> Unit>()
    private var onReadyListener: (() -> Unit)? = null

    // Status aliran ucapan saat ini
    private var streamLang: AppLang? = null
    private var streamOpen = false
    private var lastQueuedId: String? = null
    private var finishedIds = HashSet<String>()
    private var streamDone: (() -> Unit)? = null
    private var onStartSpeaking: (() -> Unit)? = null

    private val progress = object : UtteranceProgressListener() {
        override fun onStart(utteranceId: String?) {
            main.post {
                val f = onStartSpeaking
                onStartSpeaking = null
                f?.invoke()
            }
        }

        override fun onDone(utteranceId: String?) = finished(utteranceId)

        @Deprecated("Deprecated in Java")
        override fun onError(utteranceId: String?) = finished(utteranceId)

        override fun onError(utteranceId: String?, errorCode: Int) = finished(utteranceId)
    }

    init {
        val listener = TextToSpeech.OnInitListener { status ->
            main.post {
                if (status == TextToSpeech.SUCCESS) {
                    ready = true
                    tts?.setOnUtteranceProgressListener(progress)
                    val actions = ArrayList(pendingActions)
                    pendingActions.clear()
                    actions.forEach { it() }
                    onReadyListener?.invoke()
                } else {
                    failed = true
                    onNotice("Text-to-speech tidak tersedia / Text-to-speech is unavailable.")
                    val done = streamDone
                    streamDone = null
                    pendingActions.clear()
                    done?.invoke()
                }
            }
        }
        val google = "com.google.android.tts"
        val hasGoogle = try {
            app.packageManager.getPackageInfo(google, 0)
            true
        } catch (e: Exception) {
            false
        }
        tts = if (hasGoogle) TextToSpeech(app, listener, google) else TextToSpeech(app, listener)
    }

    fun setOnReady(f: () -> Unit) {
        onReadyListener = f
        if (ready) f()
    }

    private fun whenReady(f: () -> Unit) {
        if (failed) return
        if (ready) f() else pendingActions.add(f)
    }

    private fun finished(utteranceId: String?) {
        main.post {
            if (utteranceId == null) return@post
            finishedIds.add(utteranceId)
            checkStreamDone()
        }
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

    /** Ucapkan satu teks utuh. */
    fun speak(text: String, lang: AppLang, onStart: (() -> Unit)? = null, onDone: () -> Unit) {
        beginStream(lang, onStart)
        enqueue(text)
        endStream(onDone)
    }

    /** Mulai aliran ucapan baru (menghentikan yang lama). */
    fun beginStream(lang: AppLang, onStart: (() -> Unit)? = null) {
        stop()
        streamLang = lang
        streamOpen = true
        onStartSpeaking = onStart
        whenReady { tts?.let { applyLanguage(it, lang) } }
    }

    fun enqueue(sentence: String) {
        val text = sentence.trim()
        if (text.isEmpty() || !streamOpen) return
        val id = "nine-" + (++counter)
        lastQueuedId = id
        whenReady {
            val t = tts ?: return@whenReady
            val clipped = text.take(TextToSpeech.getMaxSpeechInputLength() - 1)
            val res = t.speak(clipped, TextToSpeech.QUEUE_ADD, null, id)
            if (res != TextToSpeech.SUCCESS) finished(id)
        }
    }

    /** Tidak ada kalimat lagi; [onDone] dipanggil setelah semua selesai diucapkan. */
    fun endStream(onDone: () -> Unit) {
        streamOpen = false
        if (failed) {
            onDone()
            return
        }
        streamDone = onDone
        checkStreamDone()
    }

    /** Hentikan ucapan tanpa memanggil callback selesai. */
    fun stop() {
        streamOpen = false
        streamDone = null
        onStartSpeaking = null
        lastQueuedId = null
        finishedIds.clear()
        pendingActions.clear()
        try {
            tts?.stop()
        } catch (e: Exception) {
            // abaikan
        }
    }

    fun shutdown() {
        stop()
        try {
            tts?.shutdown()
        } catch (e: Exception) {
            // abaikan
        }
        tts = null
    }

    // ------------------------------------------------------------------ pemilihan suara

    private fun canon(language: String): String = if (language == "in") "id" else language

    private fun usable(v: Voice): Boolean = try {
        !v.isNetworkConnectionRequired && !(v.features?.contains(TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED) ?: false)
    } catch (e: Exception) {
        false
    }

    /** Semua suara offline untuk bahasa ini, yang paling cocok untuk Nine lebih dulu. */
    fun voicesFor(lang: AppLang): List<Voice> {
        val t = tts ?: return emptyList()
        if (!ready) return emptyList()
        val want = canon(lang.ttsLocale.language)
        val all = try {
            t.voices?.toList().orEmpty()
        } catch (e: Exception) {
            emptyList()
        }
        return all.filter { canon(it.locale.language) == want && usable(it) }
            .sortedWith(compareByDescending<Voice> { score(it, lang) }.thenBy { it.name })
    }

    private val maleHints = listOf("smtm", "gbb", "gbd", "rjs", "-x-idd", "-x-ide", "male")

    private fun score(v: Voice, lang: AppLang): Int {
        val n = v.name.lowercase()
        var s = v.quality / 100
        if (maleHints.any { n.contains(it) } && !n.contains("female")) s += 50
        val country = v.locale.country.uppercase()
        if (lang == AppLang.EN && country == "GB") s += 100
        if (lang == AppLang.ID && country == "ID") s += 100
        if (n.contains("local")) s += 5
        return s
    }

    /** Ucapkan contoh dengan suara tertentu (untuk layar Pengaturan). */
    fun preview(lang: AppLang, voiceName: String?, text: String) {
        whenReady {
            val t = tts ?: return@whenReady
            stop()
            applyLanguage(t, lang, voiceName)
            t.speak(text, TextToSpeech.QUEUE_FLUSH, null, "nine-preview")
        }
    }

    private fun applyLanguage(t: TextToSpeech, lang: AppLang, forceVoice: String? = null) {
        val res = try {
            t.setLanguage(lang.ttsLocale)
        } catch (e: Exception) {
            TextToSpeech.LANG_NOT_SUPPORTED
        }
        if (res == TextToSpeech.LANG_MISSING_DATA || res == TextToSpeech.LANG_NOT_SUPPORTED) {
            // coba varian lain bahasa yang sama (mis. en-US bila en-GB tidak ada)
            try {
                t.setLanguage(lang.locale)
            } catch (e: Exception) {
                // abaikan
            }
            if (warned.add(lang)) {
                onNotice(
                    lang.t(
                        "Suara Bahasa Indonesia belum terpasang. Pasang di Pengaturan > Manajemen umum > Teks ke ucapan.",
                        "British English voice data isn't installed. Install it in Settings > General management > Text-to-speech."
                    )
                )
            }
        }
        try {
            val wanted = forceVoice ?: prefs.voiceName(lang)
            val voices = voicesFor(lang)
            val chosen = voices.firstOrNull { it.name == wanted } ?: voices.firstOrNull()
            if (chosen != null) t.voice = chosen
        } catch (e: Exception) {
            // beberapa mesin TTS melempar galat pada voices; abaikan
        }
        // Nada sedikit lebih rendah dan tempo terukur: tenang dan berwibawa.
        t.setPitch(0.9f)
        t.setSpeechRate(if (lang == AppLang.ID) 1.05f else 1.0f)
    }
}
