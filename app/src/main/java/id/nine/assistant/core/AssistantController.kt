package id.nine.assistant.core

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import androidx.core.content.ContextCompat
import id.nine.assistant.NineApp
import id.nine.assistant.coach.CoachSession
import id.nine.assistant.fit.HealthEngine
import id.nine.assistant.fit.HealthIntent
import id.nine.assistant.fit.HealthRequest
import id.nine.assistant.fit.InBodyParser
import id.nine.assistant.fit.InBodyReading
import id.nine.assistant.llm.AiHistory
import id.nine.assistant.llm.LangGuard
import id.nine.assistant.llm.LlmBrain
import id.nine.assistant.llm.OnlineBrain
import id.nine.assistant.llm.TextClean
import id.nine.assistant.nlu.Command
import id.nine.assistant.nlu.CommandParser
import id.nine.assistant.nlu.Pending
import id.nine.assistant.nlu.SlotFiller
import id.nine.assistant.nlu.SlotOutcome
import id.nine.assistant.recorder.RecorderService
import id.nine.assistant.skills.CalendarSkill
import id.nine.assistant.skills.FitFileStore
import id.nine.assistant.skills.InBodyOcr
import id.nine.assistant.skills.NoteStore
import id.nine.assistant.skills.TodoStore
import id.nine.assistant.skills.SkillExecutor
import id.nine.assistant.speech.SpeechInput
import id.nine.assistant.speech.DroidSinger
import id.nine.assistant.speech.SpeechOutput
import id.nine.assistant.support.CrisisCheck
import id.nine.assistant.support.SupportEntry
import id.nine.assistant.support.SupportSafety
import id.nine.assistant.support.SupportSession
import id.nine.assistant.support.SupportText
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
        /** Ekspresi wajah Nine sesuai hasil percakapan. */
        fun onMood(mood: Mood) {}
        /** Ekspresi yang ditahan selama [ms] (atas permintaan pengguna, hinaan, nyanyian). */
        fun onMoodHold(mood: Mood, ms: Long) { onMood(mood) }
        /** Tindakan sisi layar setelah jawaban selesai diucapkan: tema, keluar, tuner, wake word, dll. */
        fun onAction(action: UiAction) {}
    }

    private data class Retry(val command: Command, val confirmed: Boolean)

    private val app = context.applicationContext
    private val nine = app as NineApp
    val prefs: Prefs = nine.prefs
    private val log = nine.log
    private val brain = nine.brain
    /** Otak online pilihan pengguna (bisa berganti di Pengaturan). */
    private val online: OnlineBrain get() = nine.online
    private val parser = CommandParser()
    private val filler = SlotFiller(parser)
    private val memory = MemoryStore(app)
    private val executor = SkillExecutor(app, NoteStore(app), prefs, memory)
    private val todos = TodoStore(app)
    private val calendar = CalendarSkill(app)
    /** Pelatih fitness + ahli gizi; datanya satu berkas pribadi di penyimpanan aplikasi. */
    private val health by lazy { HealthEngine(FitFileStore(app)) }

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
            viaVoice = true
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
            online.reset()
            ui.onLanguageChanged(value)
        }

    private var recognizing = false
    private var pending: Pending? = null
    /** Sesi mentor bahasa Inggris (offline); selama ada, ucapan diperiksa grammar-nya, bukan dijalankan sebagai perintah. */
    private var coach: CoachSession? = null
    private var coachAt = 0L
    /**
     * Sesi curhat (teman yang mendengarkan). Selama ada, ucapan ditangani sesi itu; isinya ditandai pribadi: tidak
     * disimpan ke ruang obrolan, tidak dikirim ke AI online, tidak masuk ingatan jangka panjang.
     */
    private var support: SupportSession? = null
    private var supportAt = 0L
    /** Gemma pernah dipakai di sesi curhat ini: konteksnya dibuang saat sesi berakhir. */
    private var supportUsedAi = false
    private val mainHandler = Handler(Looper.getMainLooper())
    /** Ucapan terakhir datang dari mikrofon (bukan ketikan): hanya begitu Nine mendengarkan lagi sendiri. */
    private var viaVoice = false
    /** Konfirmasi yang sedang menunggu adalah ajakan kejutan harian (penolakan dijawab ringan, bukan "dibatalkan"). */
    private var questOffer = false
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
        // Latihan bahasa Inggris: mikrofon mendengarkan dalam bahasa Inggris walau bahasa aplikasi Indonesia.
        input.start(if (coach != null) AppLang.EN else lang)
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

    /**
     * Kirim gambar ([path] = berkas di cache aplikasi) ke otak AI offline untuk dijelaskan. [caption] = pertanyaan pengguna
     * tentang gambar itu (boleh kosong). Gambar tidak pernah dikirim ke internet.
     */
    fun submitImage(path: String, caption: String, inbody: Boolean = false) {
        val l = lang
        val sfx = prefs.persona.sfx(l)
        recognizing = false
        input.cancel()
        stopOutput()
        if (inbody && prefs.inbodyOcr) {
            submitInBody(path)
            return
        }
        val q = caption.trim()
        log.add(true, if (q.isEmpty()) l.t("[Gambar]", "[Image]") else l.t("[Gambar] $q", "[Image] $q"))
        if (!brain.isUsable) {
            java.io.File(path).delete()
            ui.onMood(Mood.CONFUSED)
            reply(
                l.t(
                    "Untuk membaca gambar saya butuh otak AI offline${sfx}. Unduh dulu modelnya di Pengaturan.",
                    "To read images I need the offline AI brain${sfx}. Please download the model in Settings first."
                ),
                listenAfter = false
            )
            return
        }
        setState(State.THINKING)
        ui.onPartial(l.t("Memeriksa gambar…", "Looking at the image…"))
        val index = log.add(false, "")
        val gen = ++askGen
        val raw = StringBuilder()
        val prompt = if (q.isEmpty()) {
            l.t("Jelaskan isi gambar ini dengan singkat dan jelas.", "Describe what is in this image, briefly and clearly.")
        } else q
        val cb = object : LlmBrain.ReplyCallback {
            override fun onChunk(text: String) {
                if (gen != askGen) return
                raw.append(text)
                ui.onPartial("")
                log.update(index, TextClean.clean(raw.toString()))
            }

            override fun onDone(fullText: String) {
                java.io.File(path).delete()
                if (gen != askGen) return
                brainJob = null
                cancelAi = null
                ui.onPartial("")
                val cleaned = TextClean.clean(fullText)
                if (cleaned.isEmpty()) {
                    log.update(index, "…")
                    setState(State.IDLE)
                    return
                }
                log.update(index, cleaned)
                ui.onMood(moodForText(cleaned))
                if (prefs.speakReplies) {
                    output.speak(TextClean.speech(fullText), l, onStart = { setState(State.SPEAKING) }) { setState(State.IDLE) }
                } else {
                    setState(State.IDLE)
                }
            }

            override fun onError(message: String) {
                java.io.File(path).delete()
                if (gen != askGen) return
                brainJob = null
                cancelAi = null
                ui.onPartial("")
                ui.onMood(Mood.SAD)
                val text = if (message == "no_vision") {
                    l.t(
                        "Maaf${sfx}, model AI yang terpasang di HP ini belum bisa membaca gambar.",
                        "I'm afraid the AI model on this phone can't read images yet${sfx}."
                    )
                } else {
                    l.t(
                        "Maaf${sfx}, saya gagal membaca gambar itu ($message). Coba gambar lain atau coba lagi sebentar lagi.",
                        "My apologies${sfx}, I couldn't read that image ($message). Try another image or try again shortly."
                    )
                }
                log.update(index, text)
                setState(State.IDLE)
            }
        }
        brainJob = brain.ask(prompt, l, cb, path)
        cancelAi = { brain.cancel() }
    }

    /**
     * Foto ini akan dibaca sebagai lembar InBody (OCR di perangkat), bukan dijelaskan Gemma: pembaca foto menyala dan
     * pengguna menyebut "inbody" di keterangan, atau sebelumnya sudah minta analisis InBody dan diminta mengirim foto.
     */
    fun wantsInBodyImage(caption: String): Boolean =
        prefs.inbodyOcr && (health.isAwaitingPhoto() || HealthIntent.mentionsInBody(caption))

    /** Baca foto lembar InBody dengan ML Kit di perangkat; hasilnya SELALU ditampilkan dulu dan baru disimpan setelah dibenarkan. */
    private fun submitInBody(path: String) {
        val l = lang
        val sfx = prefs.persona.sfx(l)
        val file = java.io.File(path)
        log.add(true, l.t("[Foto InBody]", "[InBody photo]"), true)
        pending = null
        questOffer = false
        endSupport() // pindah ke fitur lain yang jelas: sesi curhat berakhir tanpa suara
        if (ui.isLocked()) {
            file.delete()
            ui.onMood(Mood.NEUTRAL)
            reply(
                l.t("Buka kunci HP dulu${sfx}, data kesehatan tidak diproses di layar kunci.", "Please unlock your phone first${sfx}; health data isn't processed on the lock screen."),
                listenAfter = false,
                priv = true
            )
            return
        }
        setState(State.THINKING)
        ui.onPartial(l.t("Membaca foto InBody…", "Reading the InBody photo…"))
        val gen = ++askGen
        InBodyOcr.read(app, path) { lines ->
            file.delete()
            if (gen != askGen) return@read // dibatalkan pengguna selagi membaca
            ui.onPartial("")
            val text = try {
                val reading = if (lines.isNullOrEmpty()) InBodyReading() else InBodyParser.parseRows(InBodyParser.rows(lines))
                health.proposeInBody(reading, l)
            } catch (e: Exception) {
                l.t(
                    "Maaf${sfx}, saya gagal membaca foto itu. Coba foto ulang lebih dekat dan terang, atau ketik angkanya: \"inbody berat 70 otot 32 lemak 14 pbf 20 visceral 7 bmr 1600\".",
                    "My apologies${sfx}, I couldn't read that photo. Retake it closer and brighter, or type the numbers: \"inbody weight 70 muscle 32 fat 14 pbf 20 visceral 7 bmr 1600\"."
                )
            }
            ui.onMood(Mood.NEUTRAL)
            reply(text, listenAfter = false, priv = true)
        }
    }

    fun submitText(text: String) {
        val t = text.trim()
        if (t.isEmpty()) return
        recognizing = false
        input.cancel()
        stopOutput()
        viaVoice = false
        handleUtterance(t)
    }

    fun destroy() {
        input.destroy()
        output.shutdown()
        // Jawaban AI yang sedang berjalan dihentikan; model tetap dimuat di NineApp.
        cancelAi?.invoke()
        cancelAi = null
    }

    // ------------------------------------------------------------------ ruang obrolan & kejutan harian

    /**
     * Isi percakapan berganti (membuka/menutup ruang obrolan): hentikan yang sedang berjalan dan mulai konteks AI
     * dari nol; riwayat ruang itu disisipkan kembali lewat log pada pertanyaan berikutnya.
     */
    fun onSpaceChanged() {
        recognizing = false
        input.cancel()
        stopOutput()
        pending = null
        questOffer = false
        retry = null
        support = null // sesi curhat tidak ikut pindah ke ruang obrolan
        supportUsedAi = false
        aiSeenUpTo = 0
        brain.reset()
        online.reset()
        ui.onPartial("")
        ui.onLevel(0f)
        setState(State.IDLE)
    }

    /**
     * Satu kejutan per hari: ajakan santai ke fitur yang belum pernah dipakai, ditaruh sebagai gelembung (tanpa suara).
     * Mengembalikan true bila ajakan ditampilkan. Tidak muncul di ruang obrolan, saat sibuk/terkunci, atau bila belum akrab.
     */
    fun offerQuestIfDue(): Boolean {
        try {
            if (!prefs.profileDone || state != State.IDLE || pending != null || support != null || ui.isLocked()) return false
            if (nine.spaceSession.currentId.isNotEmpty()) return false
            val today = java.time.LocalDate.now()
            if (prefs.questDay == today.toString()) return false
            if (nine.affinity.state(today).total < 3) return false
            val quest = SideQuests.pick(prefs.questsFound, today) ?: return false
            prefs.questDay = today.toString()
            prefs.questId = quest.id
            prefs.questDone = false
            val l = lang
            log.add(false, SideQuests.teaser(quest, l, prefs.persona.sfx(l), (System.currentTimeMillis() / 1000L).toInt()))
            ui.onMood(Mood.CURIOUS)
            quest.offer?.let {
                pending = Pending.Confirm(it)
                questOffer = true
            }
            return true
        } catch (e: Exception) {
            return false // kejutan tidak boleh mengganggu aplikasi
        }
    }

    /** Orb diusap lama (muncratan hati): salah satu kejutan. */
    fun onPat() = questFound(listOf("pat"))

    private fun questCheck(cmd: Command) = questFound(SideQuests.idsFor(cmd))

    /** Catat fitur yang baru ditemukan; bila itu kejutan hari ini dan belum selesai, kedekatan bertambah. */
    private fun questFound(ids: List<String>) {
        if (ids.isEmpty()) return
        try {
            val found = prefs.questsFound
            val fresh = ids.filter { it !in found }
            if (fresh.isNotEmpty()) prefs.questsFound = found + fresh
            if (prefs.questDay == java.time.LocalDate.now().toString() && !prefs.questDone && prefs.questId in ids) {
                prefs.questDone = true
                val ev = nine.affinity.bonus(SideQuests.REWARD)
                ui.onNotice(lang.t("Kedekatan +${SideQuests.REWARD}", "Closeness +${SideQuests.REWARD}"))
                ui.onAffinity(ev)
            }
        } catch (e: Exception) {
            // kejutan tidak boleh mengganggu perintah
        }
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
        if (pending != null || coach != null || support != null || alternatives.size == 1) return alternatives.first()
        return alternatives.firstOrNull { parser.parse(it) !is Command.Unknown } ?: alternatives.first()
    }

    private fun handleUtterance(text: String) {
        setState(State.THINKING)
        val userIndex = log.add(true, text)
        prefs.lastTalk = System.currentTimeMillis()
        try {
            ui.onAffinity(nine.affinity.record())
        } catch (e: Exception) {
            // affinity tidak boleh mengganggu perintah
        }

        // Keamanan lebih dulu, di mode apa pun: tanda krisis, lalu sesi curhat bila sedang berjalan.
        if (supportGate(text, userIndex)) return

        // Bacaan InBody dari foto menunggu "ya"/"batal"/koreksi angka: jawaban itu tidak boleh dikira perintah lain.
        if (pending == null && !ui.isLocked() && health.hasDraft()) {
            val turn = try { health.draftTurn(text, lang) } catch (e: Exception) { null }
            if (turn != null) {
                log.markPrivate(userIndex)
                ui.onMood(Mood.NEUTRAL)
                reply(turn, listenAfter = false, priv = true)
                return
            }
        }

        coach?.let { session ->
            if (pending == null && coachTurn(session, text)) return
        }

        val p = pending
        if (p != null) {
            pending = null
            val wasQuest = questOffer
            questOffer = false
            when (val outcome = filler.fill(p, text)) {
                is SlotOutcome.Resolved -> {
                    // Memilih dari daftar ("yang kedua") sudah merupakan keputusan; tidak perlu ditanya lagi.
                    execute(outcome.command, confirmed = p is Pending.Confirm || p is Pending.Choose)
                    return
                }
                SlotOutcome.Cancelled -> {
                    ui.onMood(Mood.NEUTRAL)
                    val sfx = prefs.persona.sfx(lang)
                    if (wasQuest) {
                        reply(lang.t("Baik$sfx, lain kali ya.", "Very well$sfx, another time."), listenAfter = false)
                    } else {
                        reply(lang.t("Baik$sfx, dibatalkan.", "Very well$sfx, cancelled."), listenAfter = false)
                    }
                    return
                }
                SlotOutcome.NotAnAnswer -> {
                    // Jawaban pendek yang tidak dipahami saat Nine menawarkan pilihan: tanya ulang, jangan dilempar ke AI.
                    if (p is Pending.Choose && text.trim().split(Regex("\\s+")).size <= 4 && parser.parse(text) is Command.Unknown) {
                        pending = p
                        val opts = p.options.mapIndexed { i, c -> "${i + 1}. ${c.label}" }.joinToString("\n")
                        val all = if (p.all != null) lang.t(", atau \"semua\"", ", or \"all\"") else ""
                        ui.onMood(Mood.CONFUSED)
                        reply(
                            lang.t("Maaf${prefs.persona.sfx(lang)}, yang mana? Sebut nomornya$all.", "Sorry${prefs.persona.sfx(lang)}, which one? Say the number$all.") + "\n" + opts,
                            listenAfter = true
                        )
                        return
                    }
                }
            }
        }
        val cmd = parser.parse(text)
        if (cmd is Command.Unknown) {
            // Dimarahi atau dihina: Nine sedih atau takut, tidak dilempar ke AI.
            val kind = Insults.detect(text)
            if (kind != Insults.Kind.NONE) {
                prefs.lastInsultAt = System.currentTimeMillis()
                val sfx = prefs.persona.sfx(lang)
                val line = Insults.reply(kind, lang, sfx, (System.currentTimeMillis() % 7).toInt())
                ui.onMoodHold(Insults.mood(kind), 4800)
                reply(line, listenAfter = false)
                return
            }
            // Ucapan berisi curhat: Nine masuk mode teman curhat sendiri (isinya tidak diingat, tidak disimpan).
            if (maybeOpenSupport(text, userIndex)) return
            // Ucapan biasa (bukan perintah): ingat hal tetap tentang pengguna ("aku suka …", "nama saya …").
            autoRemember(text)
        }
        execute(cmd, confirmed = false)
    }

    /**
     * Satu giliran latihan bahasa Inggris. Kembalikan false bila ucapan harus diproses seperti biasa
     * (sesi sudah lama menganggur, atau pengguna minta ganti bahasa aplikasi).
     */
    private fun coachTurn(session: CoachSession, text: String): Boolean {
        val now = System.currentTimeMillis()
        if (now - coachAt > COACH_IDLE_MS) {
            coach = null // lama tidak dipakai: kembali ke mode biasa
            return false
        }
        if (text.trim().split(Regex("\\s+")).size <= 6 && parser.parse(text) is Command.SwitchLanguage) {
            coach = null
            return false
        }
        coachAt = now
        val turn = session.handle(text, lang)
        if (turn.ended) coach = null
        ui.onMood(turn.mood)
        reply(turn.reply, listenAfter = !turn.ended && viaVoice)
        return true
    }

    /** "Jadi mentor bahasa Inggris saya" / "stop latihan". */
    private fun coachCommand(on: Boolean) {
        val l = lang
        if (on) {
            val session = CoachSession()
            coach = session
            coachAt = System.currentTimeMillis()
            pending = null
            questOffer = false
            ui.onMood(Mood.HAPPY)
            reply(session.start(l), listenAfter = viaVoice)
            return
        }
        val session = coach
        coach = null
        if (session == null) {
            reply(l.t("Latihan bahasa Inggris sedang tidak berjalan.", "English practice isn't running."), listenAfter = false)
        } else {
            ui.onMood(Mood.HAPPY)
            reply(session.stop(l), listenAfter = false)
        }
    }

    // ------------------------------------------------------------------ keamanan & teman curhat

    private fun activeSupport(): SupportSession? {
        val s = support ?: return null
        if (!s.active) {
            support = null
            return null
        }
        if (System.currentTimeMillis() - supportAt > SUPPORT_IDLE_MS) {
            endSupport()
            return null
        }
        return s
    }

    private fun endSupport() {
        support = null
        if (supportUsedAi) {
            supportUsedAi = false
            brain.reset() // konteks curhat tidak boleh terbawa ke percakapan lain
            aiSeenUpTo = 0 // percakapan biasa diisi ulang dari riwayat tak-pribadi saja
        }
    }

    private fun markLastUserPrivate() {
        val i = log.entries.indexOfLast { it.fromUser }
        if (i >= 0) log.markPrivate(i)
    }

    /**
     * Keamanan lebih dulu, di mode apa pun: tanda krisis dijawab dengan nomor bantuan, tanpa AI, dan pesannya tidak
     * pernah disimpan atau dikirim ke AI sebagai riwayat. Lalu, bila sesi curhat berjalan, ucapan ditangani sesi itu.
     * True = ucapan sudah ditangani.
     */
    private fun supportGate(text: String, userIndex: Int): Boolean {
        val l = lang
        val level = try {
            CrisisCheck.assess(text)
        } catch (e: Exception) {
            CrisisCheck.Level.NONE
        }
        var session = activeSupport()
        if (level != CrisisCheck.Level.NONE) {
            log.markPrivate(userIndex)
            val s = session ?: SupportSession()
            val turn = s.crisis(level, l)
            if (turn != null) {
                pending = null
                questOffer = false
                retry = null
                if (level != CrisisCheck.Level.OTHER) coach = null
                if (s.active) {
                    support = s
                    supportAt = System.currentTimeMillis()
                } else {
                    support = null
                }
                ui.onMood(turn.mood)
                reply(turn.reply, listenAfter = viaVoice && s.active, priv = true)
                return true
            }
            session = s.takeIf { it.active }
        }
        val s = session ?: return false
        if (ui.isLocked()) {
            // Ceritanya tidak boleh diproses atau tampil lebih jauh di layar kunci; sesi menunggu sampai HP dibuka.
            log.markPrivate(userIndex)
            reply(
                l.t(
                    "Buka kunci HP dulu untuk melanjutkan, supaya ceritamu tidak tampil di layar kunci.",
                    "Please unlock your phone to continue, so what you share doesn't show on the lock screen."
                ),
                listenAfter = false,
                priv = true
            )
            return true
        }
        // Pindah ke fitur lain yang jelas (ganti bahasa, mentor Inggris) mengakhiri sesi tanpa suara.
        if (SupportText.words(text) <= 6) {
            val c = parser.parse(text)
            if (c is Command.SwitchLanguage || (c is Command.EnglishCoach && c.on)) {
                endSupport()
                return false
            }
        }
        return supportTurn(s, text, userIndex, "")
    }

    /** Satu giliran curhat: balasan mesin aturan, atau Gemma bila tersedia (lewat pemeriksaan keamanan). */
    private fun supportTurn(s: SupportSession, text: String, userIndex: Int, prefix: String): Boolean {
        val l = lang
        val turn = s.handle(text, l)
        if (turn == null) {
            endSupport()
            return false
        }
        log.markPrivate(userIndex)
        support = s
        supportAt = System.currentTimeMillis()
        if (turn.ended) endSupport()
        val prompt = turn.aiPrompt
        if (prompt != null && !turn.ended && supportAiAllowed()) {
            askSupportAi(turn, prompt, prefix)
            return true
        }
        ui.onMood(turn.mood)
        reply(prefix + turn.reply, listenAfter = !turn.ended && viaVoice, priv = true)
        return true
    }

    /**
     * Ucapan biasa yang berisi curhat (bukan perintah): masuk mode teman curhat tanpa perlu kata "aku mau curhat".
     * Tidak di ruang obrolan (tersimpan), tidak saat HP terkunci, dan bisa dimatikan di Pengaturan.
     */
    private fun maybeOpenSupport(text: String, userIndex: Int): Boolean {
        if (!prefs.autoSupport || support != null || coach != null || pending != null) return false
        if (nine.spaceSession.currentId.isNotEmpty() || ui.isLocked()) return false
        if (!SupportEntry.shouldOpen(text)) return false
        val s = SupportSession()
        s.openNatural()
        if (brain.isUsable) brain.preload()
        return supportTurn(s, text, userIndex, SupportEntry.note(lang) + "\n\n")
    }

    /** AI lokal (Gemma) hanya dipakai bila modelnya sudah terpasang. AI online tidak pernah dipakai untuk curhat. */
    private fun supportAiAllowed(): Boolean =
        brain.isUsable && brain.state != LlmBrain.State.ERROR && brain.state != LlmBrain.State.NO_MODEL

    /**
     * Balasan curhat yang lebih alami dari Gemma. Tidak di-stream: seluruh teks diperiksa [SupportSafety.vet] dulu;
     * bila ditolak, gagal, atau melewati batas waktu, dipakai balasan mesin aturan ([turn].reply).
     */
    private fun askSupportAi(turn: SupportSession.Turn, prompt: String, prefix: String = "") {
        val l = lang
        setState(State.THINKING)
        ui.onPartial(l.t("Nine menyimak…", "Nine is listening…"))
        val gen = ++askGen
        supportUsedAi = true
        var finished = false
        var watchdog: Runnable? = null
        fun finish(fromAi: String?) {
            if (finished) return
            finished = true
            watchdog?.let { mainHandler.removeCallbacks(it) }
            if (gen != askGen) return
            brainJob = null
            cancelAi = null
            ui.onPartial("")
            val vetted = fromAi?.let { SupportSafety.vet(TextClean.clean(it), l) }
            ui.onMood(turn.mood)
            reply(prefix + (vetted ?: turn.reply), listenAfter = viaVoice, priv = true)
        }
        val timeout = Runnable {
            if (gen == askGen && !finished) {
                brain.cancel()
                finish(null)
            }
        }
        watchdog = timeout
        val cb = object : LlmBrain.ReplyCallback {
            override fun onChunk(text: String) {}
            override fun onDone(fullText: String) = finish(fullText)
            override fun onError(message: String) = finish(null)
        }
        brainJob = brain.ask(prompt, l, cb)
        cancelAi = {
            mainHandler.removeCallbacks(timeout)
            brain.cancel()
        }
        mainHandler.postDelayed(timeout, SUPPORT_AI_TIMEOUT_MS)
    }

    /** Pelatih fitness / ahli gizi: catat latihan & makanan, target, kemajuan, InBody, ilmu gizi. Semuanya pribadi. */
    private fun healthCommand(req: HealthRequest) {
        val l = lang
        markLastUserPrivate()
        health.photoEnabled = prefs.inbodyOcr
        val text = try {
            health.handle(req, l)
        } catch (e: Exception) {
            l.t(
                "Maaf${prefs.persona.sfx(l)}, ada yang salah saat mengurus catatan itu. Coba ulangi sebentar lagi.",
                "My apologies${prefs.persona.sfx(l)}, something went wrong with that entry. Please try again shortly."
            )
        }
        ui.onMood(Mood.NEUTRAL)
        reply(text, listenAfter = false, priv = true)
    }

    /** "Aku mau curhat" / "latihan napas" / "stop curhat". */
    private fun supportCommand(cmd: Command.Support) {
        val l = lang
        if (!cmd.on) {
            val s = activeSupport()
            if (s == null) {
                reply(l.t("Mode curhat sedang tidak berjalan.", "Venting mode isn't running."), listenAfter = false)
                return
            }
            val turn = s.stop(l)
            endSupport()
            markLastUserPrivate()
            ui.onMood(turn.mood)
            reply(turn.reply, listenAfter = false, priv = true)
            return
        }
        if (nine.spaceSession.currentId.isNotEmpty()) {
            // Ruang obrolan tersimpan di penyimpanan; curhat tidak boleh ikut tersimpan.
            ui.onMood(Mood.NEUTRAL)
            reply(
                l.t(
                    "Mode curhat tidak tersedia di ruang obrolan karena isinya tersimpan. Buka obrolan utama dulu ya.",
                    "Venting mode isn't available inside a chat space because spaces are saved. Please open the main chat first."
                ),
                listenAfter = false
            )
            return
        }
        coach = null
        pending = null
        questOffer = false
        val s = SupportSession()
        support = s
        supportAt = System.currentTimeMillis()
        if (brain.isUsable) brain.preload() // hangatkan model di latar belakang supaya balasan pertama tidak lama
        markLastUserPrivate()
        ui.onMood(Mood.NEUTRAL)
        reply(s.start(l, cmd.exercise), listenAfter = viaVoice, priv = true)
    }

    /** Simpan otomatis kalimat berisi fakta tentang pengguna ke ingatan jangka panjang. */
    private fun autoRemember(text: String) {
        if (!prefs.autoMemory) return
        val saved = try {
            FactExtractor.extract(text).filter { memory.add(it) }
        } catch (e: Exception) {
            emptyList()
        }
        if (saved.isNotEmpty()) {
            ui.onNotice(lang.t("Diingat: ", "Remembered: ") + saved.joinToString("; ") { MemoryStore.clean(it) })
        }
    }

    /** Perintah yang aman dijalankan walau HP terkunci (tidak membuka data pribadi). */
    private fun safeWhileLocked(c: Command): Boolean = when (c) {
        is Command.SetAlarm, is Command.SetTimer, is Command.Flashlight, is Command.Volume,
        Command.CurrentTime, Command.CurrentDate, Command.Battery, Command.StopSpeaking, Command.Greeting,
        Command.Thanks, Command.WhoAreYou, Command.HowAreYou, Command.Help, is Command.SwitchLanguage, is Command.PhoneInfo, Command.DeviceInfo,
        is Command.NeedInfo, Command.AffinityStatus, is Command.EnglishCoach, is Command.CorrectEnglish, is Command.BetterWord -> true
        is Command.Camera -> true // kamera aman tersedia di layar kunci
        is Command.AlarmToggle -> true // mematikan alarm yang berbunyi harus bisa dari layar kunci
        is Command.MediaControl, is Command.CallControl, is Command.Emote, is Command.Sing -> true
        else -> false
    }

    /** Tindakan berisiko yang diusulkan AI (bisa saja terpengaruh isi web) selalu dikonfirmasi dulu. */
    private fun aiNeedsConfirm(c: Command): String? = when (c) {
        is Command.Call -> lang.t("menelepon ${c.target}", "call ${c.target}")
        is Command.WhatsApp -> lang.t("membuka WhatsApp ke ${c.target}", "open WhatsApp to ${c.target}")
        is Command.OpenUrl -> lang.t("membuka ${c.url}", "open ${c.url}")
        is Command.RecordAudio -> if (c.start) lang.t("mulai merekam suara", "start recording audio") else null
        is Command.SendSms -> lang.t("menyiapkan SMS ke ${c.target}", "prepare a text to ${c.target}")
        is Command.DeleteEvent -> lang.t("menghapus acara ${c.query.ifBlank { "itu" }}", "delete the event ${c.query.ifBlank { "you mentioned" }}")
        is Command.DeleteLastEvent -> lang.t("menghapus acara terakhir", "delete the last event")
        is Command.AlarmDelete -> lang.t("menghapus alarm", "delete the alarm")
        is Command.DeleteEvents -> lang.t("menghapus ${c.items.size} acara", "delete ${c.items.size} events")
        is Command.Forget -> lang.t("melupakan ${c.query}", "forget ${c.query}")
        is Command.CallControl -> if (c.accept) lang.t("menjawab telepon", "answer the call") else lang.t("menolak telepon", "decline the call")
        is Command.Health -> lang.t("mengubah catatan fitness/gizi", "change your fitness/nutrition log")
        is Command.OpenSettings -> if (c.turnOn != null) lang.t("mengubah pengaturan ponsel", "change a phone setting") else null
        else -> null
    }

    private fun execute(cmd: Command, confirmed: Boolean, fromAi: Boolean = false) {
        if (ui.isLocked() && !safeWhileLocked(cmd) && cmd !is Command.Unknown) {
            if (cmd is Command.Health) markLastUserPrivate() // data kesehatan: tidak ikut riwayat walau ditolak di layar kunci
            reply(lang.t("Buka kunci HP dulu${prefs.persona.sfx(lang)}.", "Please unlock your phone first${prefs.persona.sfx(lang)}."), listenAfter = false)
            ui.onRequestUnlock { execute(cmd, confirmed, fromAi) }
            return
        }
        if (cmd is Command.EnglishCoach) {
            coachCommand(cmd.on)
            return
        }
        if (cmd is Command.Support) {
            supportCommand(cmd)
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
        if (cmd is Command.Health) {
            healthCommand(cmd.req)
            return
        }
        val res = executor.execute(cmd, lang, confirmed, denied)

        if (res.needPermissions.isNotEmpty()) {
            retry = Retry(cmd, confirmed)
            log.add(false, res.reply)
            setState(State.IDLE)
            ui.onRequestPermissions(res.needPermissions.toTypedArray())
            return
        }

        questCheck(cmd)
        res.newLang?.let { lang = it }
        if (res.resetChat) {
            brain.reset()
            online.reset()
        }

        if (res.stopSpeaking) {
            stopOutput()
            setState(State.IDLE)
            return
        }

        res.deferred?.let { work ->
            log.add(false, res.reply)
            setState(State.THINKING)
            val after = res.action
            work { text ->
                if (after != null) reply(text, listenAfter = false) { ui.onAction(after) } else reply(text, listenAfter = false)
            }
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
        if (chat != null && (online.isUsable() || brain.isUsable)) {
            if (ui.isLocked()) {
                // Jawaban AI memakai konteks pribadi (tugas, acara, lokasi): minta buka kunci dulu.
                reply(lang.t("Buka kunci HP dulu${prefs.persona.sfx(lang)}.", "Please unlock your phone first${prefs.persona.sfx(lang)}."), listenAfter = false)
                ui.onRequestUnlock { askAi(chat) }
                return
            }
            askAi(chat)
            return
        }

        if (chat != null) {
            // Tanpa otak AI: obrolan ringan ala SimSimi; pertanyaan umum dicarikan di Google; selain itu balasan santai.
            val sfx = prefs.persona.sfx(lang)
            val pick = (System.currentTimeMillis() % 5).toInt()
            ChitChat.reply(chat, lang, pick)?.let {
                ui.onMood(Mood.HAPPY)
                reply(it, listenAfter = false)
                return
            }
            if (ChitChat.worthSearching(chat) && !ui.isLocked()) {
                ui.onMood(Mood.CURIOUS)
                searchGoogle(chat, ChitChat.searchNotice(lang, sfx))
                return
            }
            ui.onMood(Mood.CONFUSED)
            reply(res.reply, listenAfter = false)
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
            } else {
                try {
                    res.afterLaunch?.invoke()
                } catch (e: Exception) {
                    // tugas lanjutan (mis. ketuk lagu pertama) tidak boleh mengganggu jawaban
                }
            }
        }
        pending = nextPending
        val forced = res.mood
        if (forced != null) ui.onMoodHold(forced, holdFor(forced)) else ui.onMood(moodFor(cmd, text, nextPending))
        if (res.startRecording) {
            reply(text, listenAfter = false) {
                RecorderService.start(app)
                ui.onRecordingChanged()
            }
            return
        }
        val act = res.action
        when {
            act is UiAction.Sing -> {
                val l = lang
                speakWithAudio(text, DroidSinger.SAMPLE_RATE, { DroidSinger.perform(act.query, l).pcm }) { }
            }
            act is UiAction.Tuner && act.toneHz != null -> {
                val hz = act.toneHz
                speakWithAudio(text, DroidSinger.SAMPLE_RATE, { DroidSinger.tone(hz) }) { ui.onAction(act) }
            }
            act != null -> reply(text, listenAfter = false) { ui.onAction(act) }
            else -> reply(text, listenAfter = nextPending != null)
        }
    }

    /** Berapa lama ekspresi permintaan pengguna ditahan. */
    private fun holdFor(m: Mood): Long = when (m) {
        Mood.HAPPY -> 12000L
        Mood.CURIOUS -> 4000L
        Mood.LOVE -> 5000L
        else -> 5200L
    }

    /** Ucapkan [text] (bila suara jawaban aktif) lalu putar audio [render] (nyanyian, nada acuan); [then] setelah selesai. */
    private fun speakWithAudio(text: String, rate: Int, render: () -> ShortArray, then: () -> Unit) {
        log.add(false, text)
        output.beginStream(lang) { setState(State.SPEAKING) }
        if (prefs.speakReplies) output.enqueue(text)
        output.enqueueAudio(rate, render)
        output.endStream {
            setState(State.IDLE)
            then()
        }
    }

    /**
     * Buka Google dengan [query] sudah terketik (hasil pencarian langsung tampil). [notice] diucapkan sebagai
     * pengantar; null = tanpa pengantar.
     */
    private fun searchGoogle(query: String, notice: String?) {
        val r = executor.execute(Command.WebSearch(query), lang)
        val intent = r.intent
        val ok = intent != null && ui.onLaunch(intent, r.fallback)
        val text = when {
            !ok -> lang.t(
                "Maaf${prefs.persona.sfx(lang)}, saya tidak bisa membuka Google di perangkat ini.",
                "I'm afraid I can't open Google on this device${prefs.persona.sfx(lang)}."
            )
            notice != null -> notice
            else -> r.reply
        }
        reply(text, listenAfter = false)
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
    private fun deviceContext(l: AppLang, withMemory: Boolean = true): String {
        val parts = ArrayList<String>()
        // Ruang obrolan khusus: namanya dan instruksi dari pengguna untuk ruang itu.
        nine.spaceSession.active()?.let { s ->
            SpaceRules.contextLine(s.name, s.note, l).takeIf { it.isNotBlank() }?.let { parts += it }
        }
        // Ingatan tentang pengguna. Gemma menerimanya lewat instruksi sistem (LlmBrain), jadi tidak diulang di tiap pesan.
        if (withMemory) memory.promptBlock(l).takeIf { it.isNotBlank() }?.let { parts += it }
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
                "Tingkat kedekatan dengan pengguna: level ${st.level.number}/${Affinity.LEVELS.size} (${st.level.name(l)}), streak ${st.streak} hari. Makin tinggi levelnya, makin hangat dan akrab nadamu, tetap sopan.",
                "Closeness with the user: level ${st.level.number}/${Affinity.LEVELS.size} (${st.level.name(l)}), ${st.streak}-day streak. The higher the level, the warmer and more familiar your tone, while staying polite."
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

    /** Riwayat layar untuk AI online: giliran sebelumnya (bukan yang pribadi), role bergantian, dimulai dari pengguna. */
    private fun historyForAi(excludeFrom: Int): List<Pair<String, String>> {
        val entries = log.entries.subList(0, excludeFrom.coerceIn(0, log.entries.size))
            .filter { !it.private && it.text.isNotBlank() && it.text != "…" && LangGuard.sameLanguage(it.text, lang) }
        return AiHistory.build(entries.map { it.fromUser to it.text })
    }

    /** Untuk Gemma (yang menyimpan riwayatnya sendiri): sisipkan giliran perintah yang belum ia lihat + konteks. */
    private fun gemmaMessage(question: String, l: AppLang, userIndex: Int): String {
        // Bila model sempat dilepas dari memori (tak dipakai), Gemma lupa percakapan: sisipkan giliran terakhir lagi.
        val from = if (brain.contextDropped) 0 else aiSeenUpTo
        val missed = log.entries.subList(from.coerceIn(0, userIndex), userIndex.coerceAtMost(log.entries.size))
            .filter { !it.private && it.text.isNotBlank() && it.text != "…" && LangGuard.sameLanguage(it.text, l) }
            .takeLast(6)
            .joinToString("\n") { (if (it.fromUser) l.t("Pengguna: ", "User: ") else "Nine: ") + it.text.take(300) }
        val ctx = deviceContext(l, withMemory = false)
        if (missed.isEmpty() && ctx.isEmpty()) return question
        val sb = StringBuilder()
        if (missed.isNotEmpty()) sb.append(l.t("[Percakapan sebelumnya]\n", "[Earlier conversation]\n")).append(missed).append("\n")
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

    /** Ekspresi wajah untuk hasil perintah. */
    private fun moodFor(cmd: Command, reply: String, next: Pending?): Mood {
        val low = reply.lowercase()
        return when {
            cmd == Command.Thanks || cmd == Command.AffinityStatus -> Mood.LOVE
            cmd == Command.Greeting || cmd == Command.HowAreYou || cmd == Command.WhoAreYou || cmd is Command.Sing -> Mood.HAPPY
            low.startsWith("maaf") || low.startsWith("sorry") || low.startsWith("my apologies") || low.startsWith("i'm afraid") ||
                low.contains("tidak menemukan") || low.contains("couldn't find") || low.contains("gagal") -> Mood.SAD
            cmd is Command.Unknown -> Mood.CONFUSED
            next != null -> Mood.CURIOUS
            cmd is Command.StopSpeaking || cmd is Command.ResetChat -> Mood.NEUTRAL
            else -> Mood.HAPPY
        }
    }

    /** Ekspresi wajah untuk jawaban AI. */
    private fun moodForText(text: String): Mood {
        val low = text.lowercase()
        return when {
            Regex("""\b(?:maaf|sayangnya|sorry|apologies|unfortunately|sadly|tidak bisa|can't|cannot)\b""").containsMatchIn(low) -> Mood.SAD
            Regex("""\b(?:selamat|hebat|bagus|luar biasa|senang|great|wonderful|excellent|splendid|congratulations|delighted|glad)\b""").containsMatchIn(low) -> Mood.HAPPY
            low.trimEnd().endsWith("?") -> Mood.CURIOUS
            else -> Mood.NEUTRAL
        }
    }

    private fun askAi(question: String) {
        val l = lang
        val ai = online
        val online = ai.isUsable()
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
        var viaGemma = !online // jawaban dari Gemma offline (bukan Gemini): periksa campuran bahasa

        // Tulis ulang jawaban yang bahasanya bercampur lewat percakapan Gemma yang sama; [done] menerima hasilnya (atau null).
        fun rewrite(original: String, done: (String?) -> Unit) {
            val prompt = l.t(
                "Jawabanmu barusan bercampur bahasa. Tulis ulang jawaban yang sama, dengan makna yang persis sama, SEPENUHNYA dalam Bahasa Indonesia tanpa kata bahasa Inggris. Balas hanya dengan teks hasil tulis ulang, tanpa pembuka.",
                "Your last reply mixed languages. Rewrite the same reply, with exactly the same meaning, ENTIRELY in English with no Indonesian words. Reply with the rewritten text only, no preamble."
            )
            val cb = object : LlmBrain.ReplyCallback {
                override fun onChunk(text: String) {}

                override fun onDone(fullText: String) {
                    if (gen != askGen) return
                    brainJob = null
                    cancelAi = null
                    val fixed = TextClean.clean(fullText)
                    val better = fixed.length >= 10 && LangGuard.foreign(fixed, l) < LangGuard.foreign(original, l)
                    done(if (better) fixed else null)
                }

                override fun onError(message: String) {
                    if (gen != askGen) return
                    brainJob = null
                    cancelAi = null
                    done(null)
                }
            }
            brainJob = brain.ask(prompt, l, cb)
            cancelAi = { brain.cancel() }
        }

        // Tampilkan kegagalan sebagai balasan Nine (diucapkan bila suara menyala).
        fun showFailure(text: String, mood: Mood) {
            brainJob = null
            cancelAi = null
            ui.onPartial("")
            output.stop()
            ui.onMood(mood)
            log.update(index, text)
            if (speak) {
                output.speak(text, l, onStart = { setState(State.SPEAKING) }) { setState(State.IDLE) }
            } else {
                setState(State.IDLE)
            }
        }

        val sink = object : OnlineBrain.Callback {
            override fun onChunk(text: String) {
                if (gen != askGen) return
                raw.append(text)
                if (holding) {
                    val r = raw.toString()
                    if (cmdOf(r) != null || maybeCmd(r)) return
                    holding = false
                    ui.onPartial("")
                    log.update(index, TextClean.clean(r))
                    if (speak) for (sentence in splitter.push(r)) output.enqueue(TextClean.speech(sentence))
                    return
                }
                log.update(index, TextClean.clean(raw.toString()))
                if (speak) {
                    for (sentence in splitter.push(text)) output.enqueue(TextClean.speech(sentence))
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
                if (speak) {
                    if (holding) {
                        // seluruh jawaban pendek tertahan: bunyikan sekarang
                        for (sentence in splitter.push(fullText)) output.enqueue(TextClean.speech(sentence))
                    }
                    splitter.flush()?.let { output.enqueue(TextClean.speech(it)) }
                }
                fun conclude(finalText: String) {
                    // AI mengaku tidak tahu / butuh pencarian: setelah selesai bicara, buka Google dengan pertanyaannya.
                    // Jawaban panjang berarti AI sudah menjawab dengan isi; hanya jawaban pendek "saya tidak tahu" yang memicu Google.
                    val unsure = finalText.length <= 320 && ChitChat.looksUnsure(finalText) && ChitChat.worthSearching(question) && !ui.isLocked()
                    ui.onMood(if (unsure) Mood.CURIOUS else moodForText(finalText))
                    val followUp = finalText.trimEnd().endsWith("?")
                    val finishUp = {
                        setState(State.IDLE)
                        if (unsure) {
                            searchGoogle(question, ChitChat.searchNotice(l, prefs.persona.sfx(l)))
                        } else if (followUp) {
                            startListening()
                        }
                    }
                    if (speak) output.endStream { finishUp() } else finishUp()
                }
                if (viaGemma && LangGuard.needsRewrite(cleaned, l, question)) {
                    ui.onPartial(l.t("Merapikan bahasa…", "Polishing the wording…"))
                    rewrite(cleaned) { fixed ->
                        ui.onPartial("")
                        if (fixed != null) log.update(index, fixed)
                        conclude(fixed ?: cleaned)
                    }
                } else {
                    conclude(cleaned)
                }
            }

            override fun onError(message: String) {
                if (gen != askGen) return
                val text = if (message == "no_model") {
                    prefs.persona.didNotUnderstand(l, question)
                } else {
                    l.t(
                        "Maaf${prefs.persona.sfx(l)}, otak AI saya sedang bermasalah ($message). Coba lagi sebentar lagi.",
                        "My apologies${prefs.persona.sfx(l)}, my AI core ran into trouble ($message). Please try again shortly."
                    )
                }
                showFailure(text, if (message == "no_model") Mood.CONFUSED else Mood.SAD)
            }

            override fun onFallback(reason: String) {
                if (gen != askGen) return
                // Internet gagal sebelum ada jawaban: lanjutkan dengan Gemma offline.
                if (brain.isUsable) {
                    ui.onPartial(l.t("Internet bermasalah, beralih ke mode offline…", "Connection trouble, switching to offline mode…"))
                    raw.setLength(0)
                    holding = true
                    viaGemma = true
                    brainJob = brain.ask(gemmaMessage(question, l, userIndex), l, this)
                    cancelAi = { brain.cancel() }
                } else {
                    showFailure(
                        l.t(
                            "Maaf${prefs.persona.sfx(l)}, AI online belum bisa menjawab: ${reason.trimEnd('.')}.",
                            "My apologies${prefs.persona.sfx(l)}, the online AI couldn't answer: ${reason.trimEnd('.')}."
                        ),
                        Mood.SAD
                    )
                }
            }
        }

        if (online) {
            ai.ask(question, l, history, context, sink)
            cancelAi = { ai.cancel() }
        } else {
            brainJob = brain.ask(gemmaMessage(question, l, userIndex), l, sink)
            cancelAi = { brain.cancel() }
        }
    }

    // ------------------------------------------------------------------ menjawab

    private fun reply(text: String, listenAfter: Boolean, priv: Boolean = false, then: (() -> Unit)? = null) {
        if (text.isBlank()) {
            setState(State.IDLE)
            then?.invoke()
            return
        }
        log.add(false, text, priv)
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

    private companion object {
        /** Sesi latihan yang didiamkan selama ini dianggap selesai (agar perintah biasa tidak terjebak). */
        const val COACH_IDLE_MS = 20 * 60_000L
        const val SUPPORT_IDLE_MS = 20 * 60_000L
        /** Batas menunggu AI lokal di sesi curhat; lewat itu dipakai balasan mesin aturan. */
        const val SUPPORT_AI_TIMEOUT_MS = 45_000L
    }
}
