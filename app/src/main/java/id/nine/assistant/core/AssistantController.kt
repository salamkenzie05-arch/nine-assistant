package id.nine.assistant.core

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import id.nine.assistant.NineApp
import id.nine.assistant.llm.LlmBrain
import id.nine.assistant.llm.TextClean
import id.nine.assistant.nlu.Command
import id.nine.assistant.nlu.CommandParser
import id.nine.assistant.nlu.Pending
import id.nine.assistant.nlu.SlotFiller
import id.nine.assistant.nlu.SlotOutcome
import id.nine.assistant.skills.NoteStore
import id.nine.assistant.skills.SkillExecutor
import id.nine.assistant.speech.SpeechInput
import id.nine.assistant.speech.SpeechOutput
import kotlinx.coroutines.Job

/**
 * Alur Nine: mendengar → memahami (aturan) → menjalankan perintah, atau bertanya ke Gemma → menjawab.
 * Semua dipanggil dari main thread. Riwayat ditulis ke [NineApp.log]; UI cukup mengimplementasikan [Ui].
 */
class AssistantController(context: Context, private val ui: Ui) {

    enum class State { IDLE, LISTENING, THINKING, SPEAKING }

    interface Ui {
        fun onState(state: State)
        fun onPartial(text: String)
        fun onLevel(level: Float)
        fun onRequestPermissions(permissions: Array<String>)
        /** Luncurkan [intent]; bila gagal coba [fallback]. Kembalikan true bila ada yang berhasil. */
        fun onLaunch(intent: Intent, fallback: Intent?): Boolean
        fun onLanguageChanged(lang: AppLang)
        fun onNotice(text: String)
    }

    private data class Retry(val command: Command, val confirmed: Boolean)

    private val app = context.applicationContext
    private val nine = app as NineApp
    val prefs: Prefs = nine.prefs
    private val log = nine.log
    private val brain = nine.brain
    private val parser = CommandParser()
    private val filler = SlotFiller(parser)
    private val executor = SkillExecutor(app, NoteStore(app)) { prefs.persona }
    private val output = SpeechOutput(app, prefs) { ui.onNotice(it) }

    private val input = SpeechInput(app, object : SpeechInput.Callback {
        override fun onListening() {
            setState(State.LISTENING)
        }

        override fun onPartial(text: String) {
            ui.onPartial(text)
        }

        override fun onLevel(level: Float) {
            ui.onLevel(level)
        }

        override fun onResult(alternatives: List<String>) {
            if (!recognizing) return // hasil susulan setelah dibatalkan
            recognizing = false
            ui.onPartial("")
            ui.onLevel(0f)
            handleUtterance(pickBest(alternatives))
        }

        override fun onError(error: SpeechInput.Error) {
            if (!recognizing) return
            recognizing = false
            ui.onPartial("")
            ui.onLevel(0f)
            handleSpeechError(error)
        }
    })

    var state: State = State.IDLE
        private set

    var lang: AppLang
        get() = prefs.lang
        set(value) {
            prefs.lang = value
            brain.reset()
            ui.onLanguageChanged(value)
        }

    private var recognizing = false
    private var pending: Pending? = null
    private var retry: Retry? = null
    private var listenAfterPermission = false
    private val denied = HashSet<String>()
    private var brainJob: Job? = null
    /** Naik setiap kali jawaban Gemma dibatalkan/diganti, agar callback lama diabaikan. */
    private var askGen = 0

    // ------------------------------------------------------------------ mendengar

    fun startListening() {
        stopOutput()
        if (!hasMic()) {
            listenAfterPermission = true
            ui.onRequestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO))
            return
        }
        if (!input.isAvailable()) {
            ui.onNotice(
                lang.t(
                    "Tidak ada layanan pengenal suara. Aktifkan aplikasi Google dan unduh paket bahasa offline.",
                    "No speech recognition service. Enable the Google app and download offline language packs."
                )
            )
            return
        }
        recognizing = true
        setState(State.THINKING) // menunggu "siap bicara"
        input.start(lang)
    }

    /** Berhenti merekam dan proses yang sudah terdengar. */
    fun stopListening() {
        input.stop()
    }

    /** Batalkan apa pun yang sedang berjalan. */
    fun cancel() {
        recognizing = false
        input.cancel()
        stopOutput()
        ui.onPartial("")
        ui.onLevel(0f)
        setState(State.IDLE)
    }

    private fun stopOutput() {
        output.stop()
        askGen++
        if (brainJob != null) {
            brain.cancel()
            brainJob = null
        }
    }

    /** UI tidak lagi tampil: berhenti merekam, biarkan jawaban selesai diucapkan. */
    fun onBackground() {
        if (recognizing) {
            recognizing = false
            input.cancel()
            ui.onPartial("")
            setState(State.IDLE)
        }
    }

    fun submitText(text: String) {
        val t = text.trim()
        if (t.isEmpty()) return
        recognizing = false
        input.cancel()
        stopOutput()
        handleUtterance(t)
    }

    fun destroy() {
        input.destroy()
        output.shutdown()
        // Jawaban Gemma yang sedang berjalan dihentikan; model tetap dimuat di NineApp.
        if (brainJob != null) brain.cancel()
    }

    // ------------------------------------------------------------------ izin

    fun onPermissionsResult(result: Map<String, Boolean>) {
        for ((perm, granted) in result) {
            if (granted) denied.remove(perm) else denied.add(perm)
        }
        if (listenAfterPermission && result.containsKey(Manifest.permission.RECORD_AUDIO)) {
            listenAfterPermission = false
            if (result[Manifest.permission.RECORD_AUDIO] == true) {
                startListening()
            } else {
                ui.onNotice(
                    lang.t(
                        "Izin mikrofon diperlukan untuk perintah suara. Anda tetap bisa mengetik.",
                        "Microphone permission is needed for voice. You can still type."
                    )
                )
            }
            return
        }
        val r = retry
        if (r != null) {
            retry = null
            execute(r.command, r.confirmed)
        }
    }

    private fun hasMic() =
        ContextCompat.checkSelfPermission(app, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    // ------------------------------------------------------------------ memahami & menjalankan

    private fun pickBest(alternatives: List<String>): String {
        if (pending != null || alternatives.size == 1) return alternatives.first()
        return alternatives.firstOrNull { parser.parse(it) !is Command.Unknown } ?: alternatives.first()
    }

    private fun handleUtterance(text: String) {
        setState(State.THINKING)
        log.add(true, text)

        val p = pending
        if (p != null) {
            pending = null
            when (val outcome = filler.fill(p, text)) {
                is SlotOutcome.Resolved -> {
                    execute(outcome.command, confirmed = p is Pending.Confirm)
                    return
                }
                SlotOutcome.Cancelled -> {
                    reply(lang.t("Baik${prefs.persona.sfx(lang)}, dibatalkan.", "Very well${prefs.persona.sfx(lang)}, cancelled."), listenAfter = false)
                    return
                }
                SlotOutcome.NotAnAnswer -> Unit // perlakukan sebagai perintah baru
            }
        }
        execute(parser.parse(text), confirmed = false)
    }

    private fun execute(cmd: Command, confirmed: Boolean) {
        val res = executor.execute(cmd, lang, confirmed, denied)

        if (res.needPermissions.isNotEmpty()) {
            retry = Retry(cmd, confirmed)
            log.add(false, res.reply)
            setState(State.IDLE)
            ui.onRequestPermissions(res.needPermissions.toTypedArray())
            return
        }

        res.newLang?.let { lang = it }
        if (res.resetChat) {
            brain.reset()
        }

        if (res.stopSpeaking) {
            stopOutput()
            setState(State.IDLE)
            return
        }

        val chat = res.chat
        if (chat != null && brain.isUsable) {
            askBrain(chat)
            return
        }

        var text = res.reply
        var nextPending = res.pending
        val intent = res.intent
        if (intent != null) {
            val ok = ui.onLaunch(intent, res.fallback)
            if (!ok) {
                text = lang.t(
                    "Maaf${prefs.persona.sfx(lang)}, tidak ada aplikasi di perangkat ini yang bisa melakukannya.",
                    "I'm afraid no app on this device can do that${prefs.persona.sfx(lang)}."
                )
                nextPending = null
            }
        }
        pending = nextPending
        reply(text, listenAfter = nextPending != null)
    }

    // ------------------------------------------------------------------ Gemma

    private fun askBrain(question: String) {
        val l = lang
        setState(State.THINKING)
        if (brain.state == LlmBrain.State.IDLE || brain.state == LlmBrain.State.LOADING) {
            ui.onPartial(l.t("Membangunkan otak AI…", "Waking up the AI brain…"))
        }
        val index = log.add(false, "")
        val raw = StringBuilder()
        val splitter = TextClean.SentenceSplitter()
        val speak = prefs.speakReplies
        if (speak) output.beginStream(l) { setState(State.SPEAKING) }
        val gen = ++askGen

        brainJob = brain.ask(question, l, object : LlmBrain.ReplyCallback {
            override fun onChunk(text: String) {
                if (gen != askGen) return
                ui.onPartial("")
                raw.append(text)
                log.update(index, TextClean.clean(raw.toString()))
                if (speak) {
                    for (sentence in splitter.push(text)) output.enqueue(TextClean.clean(sentence))
                }
            }

            override fun onDone(fullText: String) {
                if (gen != askGen) return
                brainJob = null
                val cleaned = TextClean.clean(fullText)
                if (cleaned.isEmpty()) {
                    log.update(index, l.t("…", "…"))
                    output.stop()
                    setState(State.IDLE)
                    return
                }
                log.update(index, cleaned)
                val followUp = cleaned.trimEnd().endsWith("?")
                if (speak) {
                    splitter.flush()?.let { output.enqueue(TextClean.clean(it)) }
                    output.endStream {
                        setState(State.IDLE)
                        if (followUp) startListening()
                    }
                } else {
                    setState(State.IDLE)
                }
            }

            override fun onError(message: String) {
                if (gen != askGen) return
                brainJob = null
                ui.onPartial("")
                output.stop()
                val text = if (message == "no_model") {
                    prefs.persona.didNotUnderstand(l, question)
                } else {
                    l.t(
                        "Maaf${prefs.persona.sfx(l)}, otak AI saya sedang bermasalah ($message). Coba lagi sebentar lagi.",
                        "My apologies${prefs.persona.sfx(l)}, my AI core ran into trouble ($message). Please try again shortly."
                    )
                }
                log.update(index, text)
                if (speak) {
                    output.speak(text, l, onStart = { setState(State.SPEAKING) }) { setState(State.IDLE) }
                } else {
                    setState(State.IDLE)
                }
            }
        })
    }

    // ------------------------------------------------------------------ menjawab

    private fun reply(text: String, listenAfter: Boolean) {
        if (text.isBlank()) {
            setState(State.IDLE)
            return
        }
        log.add(false, text)
        val after: () -> Unit = {
            setState(State.IDLE)
            if (listenAfter) startListening()
        }
        if (prefs.speakReplies) {
            output.speak(text, lang, onStart = { setState(State.SPEAKING) }) { after() }
        } else {
            after()
        }
    }

    private fun handleSpeechError(error: SpeechInput.Error) {
        setState(State.IDLE)
        val l = lang
        val sfx = prefs.persona.sfx(l)
        when (error) {
            SpeechInput.Error.NO_SPEECH -> {
                if (pending != null) {
                    pending = null
                    ui.onNotice(l.t("Tidak terdengar jawaban, dibatalkan.", "I didn't hear an answer, so I cancelled it."))
                } else {
                    ui.onNotice(l.t("Saya tidak mendengar apa pun$sfx.", "I didn't catch that$sfx."))
                }
            }
            SpeechInput.Error.LANGUAGE_UNAVAILABLE -> {
                val started = input.requestLanguagePackDownload()
                ui.onNotice(
                    if (started) {
                        l.t(
                            "Paket bahasa suara offline sedang diunduh sistem (butuh internet sekali). Coba lagi setelah selesai.",
                            "The system is downloading the offline speech pack (needs internet once). Try again when it finishes."
                        )
                    } else {
                        l.t(
                            "Paket bahasa suara offline belum terpasang. Unduh di aplikasi Google > Pengaturan > Suara > Pengenalan ucapan offline.",
                            "The offline speech pack isn't installed. Download it in the Google app > Settings > Voice > Offline speech recognition."
                        )
                    }
                )
            }
            SpeechInput.Error.LANGUAGE_NOT_SUPPORTED -> ui.onNotice(
                l.t("Bahasa ini tidak didukung pengenal suara di perangkat ini.", "This language isn't supported by the recognizer on this device.")
            )
            SpeechInput.Error.NEEDS_NETWORK -> ui.onNotice(
                l.t(
                    "Pengenal suara meminta internet. Unduh paket bahasa offline agar bisa bekerja tanpa internet.",
                    "The recognizer wants the internet. Download the offline language pack to work without it."
                )
            )
            SpeechInput.Error.PERMISSION -> {
                listenAfterPermission = true
                ui.onRequestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO))
            }
            SpeechInput.Error.BUSY -> ui.onNotice(l.t("Pengenal suara sedang sibuk. Coba lagi.", "The recognizer is busy. Try again."))
            SpeechInput.Error.NO_RECOGNIZER -> ui.onNotice(
                l.t("Tidak ada layanan pengenal suara di perangkat ini.", "No speech recognition service on this device.")
            )
            SpeechInput.Error.OTHER -> ui.onNotice(l.t("Terjadi masalah pada pengenal suara. Coba lagi.", "Something went wrong with the recognizer. Try again."))
        }
    }

    private fun setState(s: State) {
        if (state == s) return
        state = s
        ui.onState(s)
    }
}
