package id.nine.assistant.core

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import id.nine.assistant.NineApp
import id.nine.assistant.llm.GeminiClient
import id.nine.assistant.llm.LlmBrain
import id.nine.assistant.llm.TextClean
import id.nine.assistant.nlu.Command
import id.nine.assistant.nlu.CommandParser
import id.nine.assistant.nlu.Pending
import id.nine.assistant.nlu.SlotFiller
import id.nine.assistant.nlu.SlotOutcome
import id.nine.assistant.recorder.RecorderService
import id.nine.assistant.skills.CalendarSkill
import id.nine.assistant.skills.NoteStore
import id.nine.assistant.skills.TodoStore
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
        /** Interaksi tercatat untuk affinity (badge diperbarui; naik level dirayakan). */
        fun onAffinity(event: Affinity.Event) {}
        /** HP terkunci: minta pengguna membuka kunci, lalu jalankan [then]. */
        fun onRequestUnlock(then: () -> Unit) {}
        fun onRecordingChanged() {}
        /** True bila layar sedang terkunci (Nine dipanggil di atas layar kunci). */
        fun isLocked(): Boolean = false
    }

    private data class Retry(val command: Command, val confirmed: Boolean)

    private val app = context.applicationContext
    private val nine = app as NineApp
    val prefs: Prefs = nine.prefs
    private val log = nine.log
    private val brain = nine.brain
    private val gemini = nine.gemini
    private val parser = CommandParser()
    private val filler = SlotFiller(parser)
    private val executor = SkillExecutor(app, NoteStore(app), prefs)
    private val todos = TodoStore(app)
    private val calendar = CalendarSkill(app)

    /** Ukuran log saat jawaban AI terakhir selesai; giliran sesudahnya = perintah yang belum diketahui Gemma. */
    private var aiSeenUpTo = 0
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
            gemini.reset()
            ui.onLanguageChanged(value)
        }

    private var recognizing = false
    private var pending: Pending? = null
    private var retry: Retry? = null
    private var listenAfterPermission = false
    private val denied = HashSet<String>()
    private var brainJob: Job? = null
    /** Membatalkan jawaban AI yang sedang berjalan (online atau offline). */
    private var cancelAi: (() -> Unit)? = null
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
        cancelAi?.invoke()
        cancelAi = null
        brainJob = null
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
        // Jawaban AI yang sedang berjalan dihentikan; model tetap dimuat di NineApp.
        cancelAi?.invoke()
        cancelAi = null
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
        try {
            ui.onAffinity(nine.affinity.record())
        } catch (e: Exception) {
            // affinity tidak boleh mengganggu perintah
        }

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

    /** Perintah yang aman dijalankan walau HP terkunci (tidak membuka data pribadi). */
    private fun safeWhileLocked(c: Command): Boolean = when (c) {
        is Command.SetAlarm, is Command.SetTimer, is Command.Flashlight, is Command.Volume,
        Command.CurrentTime, Command.CurrentDate, Command.Battery, Command.StopSpeaking, Command.Greeting,
        Command.Thanks, Command.WhoAreYou, Command.HowAreYou, Command.Help, is Command.SwitchLanguage,
        is Command.NeedInfo, Command.AffinityStatus -> true
        is Command.Camera -> true // kamera aman tersedia di layar kunci
        else -> false
    }

    /** Tindakan berisiko yang diusulkan AI (bisa saja terpengaruh isi web) selalu dikonfirmasi dulu. */
    private fun aiNeedsConfirm(c: Command): String? = when (c) {
        is Command.Call -> lang.t("menelepon ${c.target}", "call ${c.target}")
        is Command.WhatsApp -> lang.t("membuka WhatsApp ke ${c.target}", "open WhatsApp to ${c.target}")
        is Command.OpenUrl -> lang.t("membuka ${c.url}", "open ${c.url}")
        is Command.RecordAudio -> if (c.start) lang.t("mulai merekam suara", "start recording audio") else null
        is Command.SendSms -> lang.t("menyiapkan SMS ke ${c.target}", "prepare a text to ${c.target}")
        else -> null
    }

    private fun execute(cmd: Command, confirmed: Boolean, fromAi: Boolean = false) {
        if (ui.isLocked() && !safeWhileLocked(cmd) && cmd !is Command.Unknown) {
            reply(lang.t("Buka kunci HP dulu${prefs.persona.sfx(lang)}.", "Please unlock your phone first${prefs.persona.sfx(lang)}."), listenAfter = false)
            ui.onRequestUnlock { execute(cmd, confirmed, fromAi) }
            return
        }
        if (fromAi && !confirmed) {
            aiNeedsConfirm(cmd)?.let { what ->
                pending = Pending.Confirm(cmd)
                reply(
                    lang.t("Saya akan $what. Lanjutkan? Katakan ya atau tidak.", "I'm about to $what. Shall I go ahead? Say yes or no."),
                    listenAfter = true
                )
                return
            }
        }
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
            gemini.reset()
        }

        if (res.stopSpeaking) {
            stopOutput()
            setState(State.IDLE)
            return
        }

        res.deferred?.let { work ->
            log.add(false, res.reply)
            setState(State.THINKING)
            work { text -> reply(text, listenAfter = false) }
            return
        }

        val chat = res.chat
        if (chat != null && fromAi) {
            // Perintah dari AI ternyata tidak dikenali: jangan bertanya ke AI lagi (hindari putaran).
            reply(
                lang.t(
                    "Maaf${prefs.persona.sfx(lang)}, tindakan itu belum bisa saya lakukan.",
                    "I'm afraid I can't do that one yet${prefs.persona.sfx(lang)}."
                ),
                listenAfter = false
            )
            return
        }
        if (chat != null && (gemini.isUsable() || brain.isUsable)) {
            if (ui.isLocked()) {
                // Jawaban AI memakai konteks pribadi (tugas, acara, lokasi): minta buka kunci dulu.
                reply(lang.t("Buka kunci HP dulu${prefs.persona.sfx(lang)}.", "Please unlock your phone first${prefs.persona.sfx(lang)}."), listenAfter = false)
                ui.onRequestUnlock { askAi(chat) }
                return
            }
            askAi(chat)
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
        if (res.startRecording) {
            reply(text, listenAfter = false) {
                RecorderService.start(app)
                ui.onRecordingChanged()
            }
            return
        }
        reply(text, listenAfter = nextPending != null)
    }

    /** Hentikan rekaman suara (dipanggil saat orb diketuk ketika sedang merekam). */
    fun stopRecording() {
        if (!RecorderService.recording) return
        RecorderService.stop(app)
        ui.onRecordingChanged()
        reply(lang.t("Rekaman disimpan di folder Recordings/Nine${prefs.persona.sfx(lang)}.", "Recording saved to Recordings/Nine${prefs.persona.sfx(lang)}."), listenAfter = false)
    }

    // ------------------------------------------------------------------ Gemma

    /** Ringkasan keadaan perangkat untuk AI: tugas hari ini, acara hari ini, acara terakhir yang dibuat Nine. */
    private fun deviceContext(l: AppLang): String {
        val parts = ArrayList<String>()
        try {
            val open = todos.pending().take(8).map { it.text }
            parts += l.t("Tugas hari ini yang belum selesai: ", "Open tasks today: ") +
                (if (open.isEmpty()) "-" else open.joinToString("; "))
        } catch (e: Exception) {
            // abaikan
        }
        val calOk = ContextCompat.checkSelfPermission(app, Manifest.permission.READ_CALENDAR) == PackageManager.PERMISSION_GRANTED
        if (calOk) {
            try {
                val evs = calendar.eventsOn(java.time.LocalDate.now()).take(6).map {
                    (if (it.allDay) l.t("seharian", "all day") else String.format("%02d:%02d", it.start.hour, it.start.minute)) + " " + it.title
                }
                parts += l.t("Acara kalender hari ini: ", "Today's calendar: ") + (if (evs.isEmpty()) "-" else evs.joinToString("; "))
                val last = prefs.lastEventId.takeIf { it > 0 }?.let { calendar.get(it) }
                if (last != null) {
                    parts += l.t("Acara terakhir yang dibuat Nine: ", "Last event Nine created: ") +
                        "${last.title}, ${last.start.toLocalDate()} " + String.format("%02d:%02d", last.start.hour, last.start.minute)
                }
            } catch (e: Exception) {
                // abaikan
            }
        }
        try {
            val st = nine.affinity.state()
            parts += l.t(
                "Tingkat kedekatan dengan pengguna: level ${st.level.number}/9 (${st.level.name(l)}), streak ${st.streak} hari. Makin tinggi levelnya, makin hangat dan akrab nadamu, tetap sopan.",
                "Closeness with the user: level ${st.level.number}/9 (${st.level.name(l)}), ${st.streak}-day streak. The higher the level, the warmer and more familiar your tone, while staying polite."
            )
        } catch (e: Exception) {
            // abaikan
        }
        if (nine.location.enabled()) {
            nine.location.describe()?.let { parts += l.t("Lokasi pengguna saat ini: ", "User's current location: ") + it }
            nine.location.refresh() // untuk pertanyaan berikutnya
        }
        return parts.joinToString("\n")
    }

    /** Riwayat layar untuk Gemini: giliran sebelumnya, role bergantian, dimulai dari pengguna. */
    private fun historyForAi(excludeFrom: Int): List<Pair<String, String>> {
        val entries = log.entries.subList(0, excludeFrom.coerceIn(0, log.entries.size))
            .filter { it.text.isNotBlank() && it.text != "…" }
            .takeLast(14)
        val out = ArrayList<Pair<String, String>>()
        for (e in entries) {
            val role = if (e.fromUser) "user" else "model"
            if (out.isNotEmpty() && out.last().first == role) {
                out[out.size - 1] = role to (out.last().second + "\n" + e.text)
            } else {
                out.add(role to e.text)
            }
        }
        while (out.isNotEmpty() && out.first().first != "user") out.removeAt(0)
        return out
    }

    /** Untuk Gemma (yang menyimpan riwayatnya sendiri): sisipkan giliran perintah yang belum ia lihat + konteks. */
    private fun gemmaMessage(question: String, l: AppLang, userIndex: Int): String {
        val missed = log.entries.subList(aiSeenUpTo.coerceIn(0, userIndex), userIndex.coerceAtMost(log.entries.size))
            .filter { it.text.isNotBlank() && it.text != "…" }
            .takeLast(6)
            .joinToString("\n") { (if (it.fromUser) l.t("Pengguna: ", "User: ") else "Nine: ") + it.text.take(300) }
        val ctx = deviceContext(l)
        if (missed.isEmpty() && ctx.isEmpty()) return question
        val sb = StringBuilder()
        if (missed.isNotEmpty()) sb.append(l.t("[Percakapan sebelumnya yang ditangani modul perintah]\n", "[Earlier turns handled by the command module]\n")).append(missed).append("\n")
        if (ctx.isNotEmpty()) sb.append(l.t("[Konteks perangkat]\n", "[Device context]\n")).append(ctx).append("\n")
        sb.append(l.t("[Pesan pengguna sekarang]\n", "[Current user message]\n")).append(question)
        return sb.toString()
    }

    private fun cmdOf(t: String): String? {
        val x = t.trimStart().trimStart('`', '"', '*', ' ')
        if (!x.uppercase().startsWith("CMD:")) return null
        var c = x.substring(4).lineSequence().first().trim().trim('"', '`', '*', '.', ' ')
        // Model kecil kadang menambahkan sapaan ("…, sir"): buang agar tidak ikut jadi judul acara.
        val hons = listOf(prefs.honorificId, prefs.honorificEn, "tuan", "sir", "nyonya", "madam", "bos", "boss", "pak", "bu")
            .filter { it.isNotBlank() }.map { Regex.escape(it) }.distinct().joinToString("|")
        c = c.replace(Regex("""(?:[,\s]+(?:$hons))+[.!]?$""", RegexOption.IGNORE_CASE), "").trim().trim(',', '.', ' ')
        return c.ifEmpty { null }
    }

    /** Masih mungkin awal dari "CMD:"? (tahan dulu sebelum ditampilkan/diucapkan) */
    private fun maybeCmd(t: String): Boolean {
        val x = t.trimStart().trimStart('`', '"', '*', ' ').uppercase()
        return x.length < 4 && "CMD:".startsWith(x)
    }

    private fun askAi(question: String) {
        val l = lang
        val online = gemini.isUsable()
        setState(State.THINKING)
        when {
            online -> ui.onPartial(l.t("Mencari jawaban…", "Looking that up…"))
            brain.state == LlmBrain.State.IDLE || brain.state == LlmBrain.State.LOADING ->
                ui.onPartial(l.t("Membangunkan otak AI…", "Waking up the AI brain…"))
        }
        // Pesan pengguna sudah ada di log (entri terakhir).
        val userIndex = (log.entries.size - 1).coerceAtLeast(0)
        val history = historyForAi(userIndex)
        val context = deviceContext(l)
        val index = log.add(false, "")
        val raw = StringBuilder()
        val splitter = TextClean.SentenceSplitter()
        val speak = prefs.speakReplies
        if (speak) output.beginStream(l) { setState(State.SPEAKING) }
        val gen = ++askGen
        var holding = true // menahan keluaran sampai jelas ini bukan "CMD:"

        val sink = object : GeminiClient.Callback {
            override fun onChunk(text: String) {
                if (gen != askGen) return
                raw.append(text)
                if (holding) {
                    val r = raw.toString()
                    if (cmdOf(r) != null || maybeCmd(r)) return
                    holding = false
                    ui.onPartial("")
                    log.update(index, TextClean.clean(r))
                    if (speak) for (sentence in splitter.push(r)) output.enqueue(TextClean.clean(sentence))
                    return
                }
                log.update(index, TextClean.clean(raw.toString()))
                if (speak) {
                    for (sentence in splitter.push(text)) output.enqueue(TextClean.clean(sentence))
                }
            }

            override fun onDone(fullText: String) {
                if (gen != askGen) return
                brainJob = null
                cancelAi = null
                aiSeenUpTo = log.entries.size
                ui.onPartial("")
                val action = cmdOf(fullText)
                if (action != null) {
                    // AI memahami permintaan sebagai tindakan: jalankan lewat modul perintah yang sebenarnya.
                    output.stop()
                    log.remove(index) // perintah internal tidak ditampilkan; hanya hasilnya
                    val parsed = parser.parse(action)
                    execute(parsed, confirmed = false, fromAi = true)
                    return
                }
                val cleaned = TextClean.clean(fullText)
                if (cleaned.isEmpty()) {
                    log.update(index, "…")
                    output.stop()
                    setState(State.IDLE)
                    return
                }
                log.update(index, cleaned)
                val followUp = cleaned.trimEnd().endsWith("?")
                if (speak) {
                    if (holding) {
                        // seluruh jawaban pendek tertahan: ucapkan sekarang
                        for (sentence in splitter.push(fullText)) output.enqueue(TextClean.clean(sentence))
                    }
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
                cancelAi = null
                ui.onPartial("")
                output.stop()
                val text = if (message == "no_model") {
                    prefs.persona.didNotUnderstand(l, question)
                } else if (message.contains("Gemini")) {
                    message
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

            override fun onFallback(reason: String) {
                if (gen != askGen) return
                // Internet gagal sebelum ada jawaban: lanjutkan dengan Gemma offline.
                if (brain.isUsable) {
                    ui.onPartial(l.t("Internet bermasalah, beralih ke mode offline…", "Connection trouble, switching to offline mode…"))
                    raw.setLength(0)
                    holding = true
                    brainJob = brain.ask(gemmaMessage(question, l, userIndex), l, this)
                    cancelAi = { brain.cancel() }
                } else {
                    onError(
                        l.t(
                            "Tidak bisa terhubung ke Gemini ($reason) dan otak AI offline belum diunduh.",
                            "I couldn't reach Gemini ($reason) and the offline AI brain isn't downloaded."
                        )
                    )
                }
            }
        }

        if (online) {
            gemini.ask(question, l, history, context, sink)
            cancelAi = { gemini.cancel() }
        } else {
            brainJob = brain.ask(gemmaMessage(question, l, userIndex), l, sink)
            cancelAi = { brain.cancel() }
        }
    }

    // ------------------------------------------------------------------ menjawab

    private fun reply(text: String, listenAfter: Boolean, then: (() -> Unit)? = null) {
        if (text.isBlank()) {
            setState(State.IDLE)
            then?.invoke()
            return
        }
        log.add(false, text)
        val after: () -> Unit = {
            setState(State.IDLE)
            then?.invoke()
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
