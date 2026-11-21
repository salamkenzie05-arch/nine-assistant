package id.nine.assistant.skills

import android.Manifest
import android.app.ActivityManager
import android.app.SearchManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.net.Uri
import android.os.BatteryManager
import android.os.Build
import android.os.Environment
import android.os.StatFs
import android.provider.AlarmClock
import android.provider.MediaStore
import android.provider.CalendarContract
import android.provider.Settings
import android.os.SystemClock
import android.view.KeyEvent
import android.telephony.SmsManager
import androidx.core.content.ContextCompat
import id.nine.assistant.coach.CoachFeedback
import id.nine.assistant.core.AppLang
import id.nine.assistant.core.Fmt
import id.nine.assistant.NineApp
import id.nine.assistant.core.Affinity
import id.nine.assistant.core.AffinityRules
import id.nine.assistant.core.Mood
import id.nine.assistant.core.UiAction
import id.nine.assistant.a11y.NineAccessibilityService
import id.nine.assistant.audio.GuitarTuning
import id.nine.assistant.audio.Songs
import id.nine.assistant.call.CallControl
import id.nine.assistant.nlu.FunKind
import id.nine.assistant.nlu.MediaAction
import id.nine.assistant.nlu.NineSetting
import id.nine.assistant.wake.WakeWordService
import id.nine.assistant.core.LocationHelper
import id.nine.assistant.core.Persona
import id.nine.assistant.recorder.RecorderService
import id.nine.assistant.core.MemoryStore
import id.nine.assistant.core.Prefs
import id.nine.assistant.AlarmsActivity
import id.nine.assistant.FinanceActivity
import id.nine.assistant.nlu.Calc
import id.nine.assistant.nlu.FinPeriod
import id.nine.assistant.nlu.FinType
import id.nine.assistant.nlu.FinanceCats
import id.nine.assistant.nlu.FinanceMath
import id.nine.assistant.alarm.AlarmMatch
import id.nine.assistant.alarm.AlarmRingService
import id.nine.assistant.alarm.AlarmScheduler
import id.nine.assistant.alarm.AlarmStore
import id.nine.assistant.alarm.NineAlarm
import id.nine.assistant.nlu.AlarmRef
import id.nine.assistant.nlu.Choice
import id.nine.assistant.nlu.RepeatDays
import id.nine.assistant.nlu.NumberWords
import id.nine.assistant.nlu.TimeParser
import id.nine.assistant.nlu.Command
import id.nine.assistant.nlu.Pending
import id.nine.assistant.nlu.SettingsTarget
import id.nine.assistant.nlu.VolumeAction
import java.time.DayOfWeek
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.roundToInt

/** Hasil menjalankan satu perintah. */
data class SkillResult(
    val reply: String,
    val intent: Intent? = null,
    val fallback: Intent? = null,
    val needPermissions: List<String> = emptyList(),
    val pending: Pending? = null,
    val newLang: AppLang? = null,
    val stopSpeaking: Boolean = false,
    /** Bukan perintah perangkat: teruskan ke otak AI (Gemma). */
    val chat: String? = null,
    val resetChat: Boolean = false,
    /** Mulai perekam suara setelah jawaban selesai diucapkan (agar suara Nine tidak ikut terekam). */
    val startRecording: Boolean = false,
    /** Jawaban yang butuh waktu (mis. lokasi): dipanggil oleh controller, lalu [done] dengan teks jawaban. */
    val deferred: ((done: (String) -> Unit) -> Unit)? = null,
    /** Tindakan tambahan di sisi layar/pengontrol (tema, keluar, tuner, nyanyi, ...). */
    val action: UiAction? = null,
    /** Ekspresi wajah yang diminta langsung oleh perintah (mis. "buat wajah sedih"); menimpa ekspresi otomatis. */
    val mood: Mood? = null,
    /** Dipanggil pengontrol tepat setelah [intent] berhasil dibuka (mis. mengetuk hasil pencarian lewat aksesibilitas). */
    val afterLaunch: (() -> Unit)? = null
)

/**
 * Menjalankan [Command] di perangkat. Semua dilakukan lokal lewat API Android;
 * tidak ada akses jaringan (aplikasi ini bahkan tidak meminta izin INTERNET).
 */
class SkillExecutor(
    context: Context,
    private val notes: NoteStore,
    private val prefs: Prefs,
    private val memory: MemoryStore = MemoryStore(context)
) {
    private val personaOf: () -> Persona = { prefs.persona }
    private val ctx = context.applicationContext
    private val contacts = ContactsLookup(ctx)
    private val calendar = CalendarSkill(ctx)
    private val todos = TodoStore(ctx)
    private val finance = FinanceStore(ctx)
    private val phoneInfo = PhoneInfoSkill(ctx)
    private val affinity = Affinity(ctx)
    private val alarms = AlarmStore(ctx)
    private val location: LocationHelper get() = (ctx as NineApp).location
    private var persona: Persona = personaOf()

    /** ", Tuan" / ", sir" */
    private fun s(lang: AppLang) = persona.sfx(lang)

    private fun has(perm: String) =
        ContextCompat.checkSelfPermission(ctx, perm) == PackageManager.PERMISSION_GRANTED

    fun execute(
        cmd: Command,
        lang: AppLang,
        confirmed: Boolean = false,
        denied: Set<String> = emptySet(),
        now: LocalDateTime = LocalDateTime.now()
    ): SkillResult {
        persona = personaOf()
        return run(cmd, lang, confirmed, denied, now)
    }

    private fun run(
        cmd: Command,
        lang: AppLang,
        confirmed: Boolean,
        denied: Set<String>,
        now: LocalDateTime
    ): SkillResult = when (cmd) {
        is Command.SetAlarm -> setAlarm(cmd, lang, now, denied)
        is Command.SetTimer -> setTimer(cmd, lang)
        is Command.SetReminder -> setReminder(cmd, lang, now, denied)
        Command.ShowAlarms -> showAlarms(lang, now)
        Command.OpenAlarms -> if (prefs.nineAlarms) {
            SkillResult(lang.t("Membuka daftar alarm${s(lang)}.", "Opening your alarms${s(lang)}."), intent = Intent(ctx, AlarmsActivity::class.java))
        } else {
            SkillResult(lang.t("Membuka daftar alarm${s(lang)}.", "Opening your alarms${s(lang)}."), intent = Intent(AlarmClock.ACTION_SHOW_ALARMS))
        }
        is Command.AlarmToggle -> alarmToggle(cmd, lang, now)
        is Command.AlarmDelete -> alarmDelete(cmd, lang, confirmed, now)
        is Command.OpenApp -> openApp(cmd.query, lang)
        is Command.OpenSettings -> openSettings(cmd, lang)
        is Command.Flashlight -> flashlight(cmd.on, lang)
        is Command.Volume -> volume(cmd, lang)
        Command.Battery -> battery(lang)
        Command.CurrentTime -> SkillResult(
            lang.t("Sekarang ", "It's ") + Fmt.clock(now.hour, now.minute, lang) + s(lang) + "."
        )
        Command.CurrentDate -> SkillResult(
            lang.t("Hari ini ", "Today is ") +
                now.format(DateTimeFormatter.ofPattern("EEEE, d MMMM yyyy", lang.locale)) + s(lang) + "."
        )
        Command.DeviceInfo -> SkillResult(phoneInfo.summary(lang))
        is Command.PhoneInfo -> SkillResult(phoneInfo.answer(cmd.topic, lang))
        Command.GoHome -> SkillResult(
            lang.t("Baik${s(lang)}.", "Going home${s(lang)}."),
            intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
        is Command.AddNote -> {
            notes.add(cmd.text)
            SkillResult(lang.t("Sudah saya catat${s(lang)}: ${cmd.text}", "Noted${s(lang)}: ${cmd.text}"))
        }
        Command.ReadNotes -> readNotes(lang)
        Command.ClearNotes -> clearNotes(lang, confirmed)
        is Command.Remember -> remember(cmd.text, lang)
        is Command.Forget -> forget(cmd.query, lang)
        Command.ForgetAll -> forgetAll(lang, confirmed)
        Command.ReadMemory -> readMemory(lang)
        is Command.Call -> call(cmd.target, lang, denied)
        is Command.SendSms -> prepareSms(cmd, lang, denied)
        is Command.SendSmsResolved -> sendSms(cmd, lang, denied)
        is Command.SwitchLanguage -> SkillResult(
            cmd.lang.t(
                "Baik${persona.sfx(cmd.lang)}. Mulai sekarang saya berbicara dalam Bahasa Indonesia.",
                "Very good${persona.sfx(cmd.lang)}. I'll speak English from now on."
            ),
            newLang = cmd.lang
        )
        Command.Help -> SkillResult(help(lang))
        Command.Greeting -> SkillResult(persona.greeting(lang, now))
        Command.Thanks -> SkillResult(persona.thanks(lang))
        Command.WhoAreYou -> SkillResult(persona.whoAreYou(lang))
        Command.HowAreYou -> SkillResult(persona.howAreYou(lang))
        Command.ResetChat -> SkillResult(
            lang.t("Baik${s(lang)}. Lembaran baru; percakapan sebelumnya sudah saya lupakan.", "Very well${s(lang)}. A clean slate; I've forgotten our previous conversation."),
            resetChat = true
        )
        Command.StopSpeaking -> SkillResult("", stopSpeaking = true)
        is Command.NeedInfo -> SkillResult(prompt(cmd.pending, lang), pending = cmd.pending)
        is Command.Unknown -> unknown(cmd.text, lang)

        is Command.AddEvent -> addEvent(cmd, lang, now, denied)
        is Command.ShowAgenda -> agenda(cmd, lang, now, denied)
        is Command.MoveLastEvent -> moveLastEvent(cmd, lang, now, denied)
        is Command.ShiftLastEvent -> shiftLastEvent(cmd, lang, now, denied)
        Command.DeleteLastEvent -> deleteLastEvent(lang, confirmed, denied)
        is Command.DeleteEvent -> deleteEvent(cmd, lang, confirmed, denied, now)
        is Command.DeleteEventInstance -> deleteEventInstance(cmd, lang, confirmed, denied, now)
        is Command.DeleteEvents -> deleteEvents(cmd, lang, confirmed, denied, now)
        is Command.DeleteMaybeEvent -> {
            // Hanya bila benar-benar ada acara bernama itu; selain itu biarkan AI yang menjawab.
            val calOk = has(Manifest.permission.READ_CALENDAR) && has(Manifest.permission.WRITE_CALENDAR)
            val found = if (calOk) {
                try {
                    calendar.findEvents(cmd.event.query, cmd.event.date, cmd.event.hour, cmd.event.minute, cmd.event.ambiguous, now)
                } catch (e: Exception) {
                    emptyList()
                }
            } else emptyList()
            if (found.isNotEmpty()) deleteEvent(cmd.event, lang, confirmed, denied, now) else unknown(cmd.text, lang)
        }

        is Command.TodoAdd -> {
            val item = todos.add(cmd.text)
            val left = todos.pending().size
            SkillResult(
                lang.t(
                    "Masuk daftar tugas${s(lang)}: ${item.text}. Total $left tugas belum selesai.",
                    "Added to your list${s(lang)}: ${item.text}. $left open ${if (left == 1) "task" else "tasks"}."
                )
            )
        }
        Command.TodoList -> todoList(lang)
        is Command.TodoDone -> {
            val item = todos.find(cmd.ref)
            if (item == null) {
                SkillResult(lang.t("Saya tidak menemukan tugas \"${cmd.ref}\" di daftar hari ini.", "I couldn't find \"${cmd.ref}\" on today's list."))
            } else {
                todos.setDone(item.id, true)
                val left = todos.pending().size
                val tail = if (left == 0) lang.t(" Semua tugas hari ini beres. Luar biasa.", " That's everything for today. Splendid.")
                else lang.t(" Tersisa $left.", " $left to go.")
                SkillResult(lang.t("Dicentang${s(lang)}: ${item.text}.", "Checked off${s(lang)}: ${item.text}.") + tail)
            }
        }
        is Command.TodoRemove -> {
            val item = todos.find(cmd.ref)
            if (item == null) {
                SkillResult(lang.t("Saya tidak menemukan tugas \"${cmd.ref}\".", "I couldn't find \"${cmd.ref}\" on your list."))
            } else {
                todos.remove(item.id)
                SkillResult(lang.t("Dihapus dari daftar${s(lang)}: ${item.text}.", "Removed${s(lang)}: ${item.text}."))
            }
        }
        Command.TodoClearDone -> {
            todos.clearDone()
            SkillResult(lang.t("Tugas yang sudah selesai saya bersihkan${s(lang)}.", "Completed tasks cleared${s(lang)}."))
        }
        Command.TodoClearAll -> {
            val n = todos.today().size
            if (n == 0) {
                SkillResult(lang.t("Daftar tugas sudah kosong${s(lang)}.", "Your list is already empty${s(lang)}."))
            } else if (!confirmed) {
                SkillResult(
                    lang.t("Hapus semua $n tugas? Katakan ya atau tidak.", "Delete all $n tasks? Say yes or no."),
                    pending = Pending.Confirm(Command.TodoClearAll)
                )
            } else {
                todos.clearAll()
                SkillResult(lang.t("Daftar tugas dikosongkan${s(lang)}.", "Task list cleared${s(lang)}."))
            }
        }

        is Command.PlayMedia -> playMedia(cmd, lang)
        is Command.Navigate -> navigate(cmd, lang)
        is Command.WebSearch -> SkillResult(
            lang.t("Mencari \"${cmd.query}\" di Google${s(lang)}.", "Searching Google for \"${cmd.query}\"${s(lang)}."),
            intent = Intent(Intent.ACTION_WEB_SEARCH).putExtra(SearchManager.QUERY, cmd.query),
            fallback = Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com/search?q=" + Uri.encode(cmd.query)))
        )
        is Command.OpenUrl -> {
            val url = if (cmd.url.startsWith("http")) cmd.url else "https://" + cmd.url
            SkillResult(
                lang.t("Membuka ${cmd.url}${s(lang)}.", "Opening ${cmd.url}${s(lang)}."),
                intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
            )
        }
        is Command.WhatsApp -> whatsApp(cmd, lang, denied)
        is Command.Camera -> camera(cmd, lang)
        is Command.RecordAudio -> recordAudio(cmd, lang, denied)
        Command.AffinityStatus -> affinityStatus(lang)
        is Command.CorrectEnglish -> SkillResult(CoachFeedback.correctionReply(cmd.sentence, lang))
        is Command.BetterWord -> SkillResult(CoachFeedback.betterWordReply(cmd.word, lang))
        // Sesi latihan dikendalikan AssistantController (butuh status percakapan); di sini hanya jaring pengaman.
        is Command.EnglishCoach -> SkillResult(lang.t("Latihan bahasa Inggris dijalankan dari obrolan utama.", "English practice runs from the main chat."))
        // Sesi curhat dikendalikan AssistantController (butuh status percakapan); di sini hanya jaring pengaman.
        is Command.Support -> SkillResult(lang.t("Mode curhat dijalankan dari obrolan utama.", "Venting mode runs from the main chat."))
        // Pelatih fitness + gizi dikendalikan AssistantController (data pribadi, balasan privat); di sini hanya jaring pengaman.
        is Command.Health -> SkillResult(lang.t("Pelatih fitness dijalankan dari obrolan utama.", "The fitness coach runs from the main chat."))
        Command.WhereAmI -> whereAmI(lang, denied)
        is Command.MediaControl -> mediaControl(cmd, lang)
        is Command.CallControl -> callControl(cmd, lang, denied)
        is Command.Sing -> sing(cmd, lang)
        is Command.Emote -> emote(cmd, lang)
        is Command.Tuner -> tuner(cmd, lang, denied)
        is Command.AppSetting -> appSetting(cmd, lang, denied)
        is Command.Calculate -> calculate(cmd, lang)
        is Command.FinanceAdd -> financeAdd(cmd, lang)
        is Command.FinanceReport -> financeReport(cmd, lang)
        is Command.FinanceList -> financeList(cmd, lang)
        Command.FinanceUndo -> financeUndo(lang)
        Command.FinanceClear -> financeClear(lang, confirmed)
        Command.FinanceOpen -> SkillResult(
            lang.t("Membuka catatan keuangan${s(lang)}.", "Opening your money log${s(lang)}."),
            intent = Intent(ctx, FinanceActivity::class.java)
        )
        is Command.CloseApps -> closeApps(cmd, lang)
        is Command.Entertain -> SkillResult(
            FunBank.pick(cmd.kind, lang),
            mood = if (cmd.kind == FunKind.GOMBAL) Mood.LOVE else Mood.HAPPY
        )
        is Command.SetCallName -> setCallName(cmd.name, lang)
    }

    // ------------------------------------------------------------ alarm, timer, pengingat

    private fun daysText(days: Set<DayOfWeek>, lang: AppLang): String = when {
        days.isEmpty() -> ""
        days == RepeatDays.ALL -> lang.t("setiap hari", "every day")
        days == RepeatDays.WEEKDAYS -> lang.t("setiap hari kerja", "on weekdays")
        days == RepeatDays.WEEKEND -> lang.t("setiap akhir pekan", "on weekends")
        else -> {
            val names = days.sorted().map { it.getDisplayName(java.time.format.TextStyle.FULL, lang.locale) }
            val joined = if (names.size <= 1) names.joinToString("") else names.dropLast(1).joinToString(", ") + lang.t(" dan ", " and ") + names.last()
            lang.t("setiap $joined", "every $joined")
        }
    }

    /** "06.00 · gym · setiap hari kerja · mati" */
    private fun alarmDesc(a: NineAlarm, lang: AppLang, withState: Boolean = false): String {
        val parts = arrayListOf(a.clock)
        a.label?.let { parts += it }
        if (a.repeating) parts += daysText(a.days, lang)
        if (withState && !a.enabled) parts += lang.t("mati", "off")
        return parts.joinToString(" · ")
    }

    private fun setAlarm(cmd: Command.SetAlarm, lang: AppLang, now: LocalDateTime, denied: Set<String>): SkillResult {
        val label = if (cmd.label.isNullOrBlank()) "" else " (${cmd.label})"
        val rep = if (cmd.repeat.isEmpty()) "" else ", " + daysText(cmd.repeat, lang)
        if (!prefs.nineAlarms) {
            val i = Intent(AlarmClock.ACTION_SET_ALARM).apply {
                putExtra(AlarmClock.EXTRA_HOUR, cmd.time.hour)
                putExtra(AlarmClock.EXTRA_MINUTES, cmd.time.minute)
                if (!cmd.label.isNullOrBlank()) putExtra(AlarmClock.EXTRA_MESSAGE, cmd.label)
                if (cmd.repeat.isNotEmpty()) {
                    // java.util.Calendar: Minggu = 1 ... Sabtu = 7
                    putIntegerArrayListExtra(AlarmClock.EXTRA_DAYS, ArrayList(cmd.repeat.map { it.value % 7 + 1 }))
                }
                putExtra(AlarmClock.EXTRA_SKIP_UI, true)
            }
            return SkillResult(
                lang.t("Alarm terpasang untuk ", "Alarm set for ") + Fmt.at(cmd.time, lang, now) + label + rep + s(lang) + ".",
                intent = i
            )
        }
        // Layar & notifikasi alarm butuh izin notifikasi (Android 13+): minta sekali.
        val notif = Manifest.permission.POST_NOTIFICATIONS
        if (Build.VERSION.SDK_INT >= 33 && !has(notif) && notif !in denied) {
            return SkillResult(
                lang.t("Izinkan notifikasi agar alarm bisa tampil di layar${s(lang)}.", "Please allow notifications so the alarm can show on screen${s(lang)}."),
                needPermissions = listOf(notif)
            )
        }
        // Simpan tanggal hanya bila bukan kejadian berikutnya yang wajar (mis. "alarm lusa jam 6").
        val natural = now.toLocalDate().atTime(cmd.time.hour, cmd.time.minute).let { if (it.isAfter(now)) it else it.plusDays(1) }
        val date = if (cmd.repeat.isEmpty() && cmd.time.toLocalDate() != natural.toLocalDate()) cmd.time.toLocalDate() else null
        val a = alarms.add(cmd.time.hour, cmd.time.minute, cmd.label, cmd.repeat, date)
        AlarmScheduler.schedule(ctx, a, now)
        val next = a.nextTrigger(now)
        val warn = if (AlarmScheduler.canExact(ctx)) "" else lang.t(
            " Catatan: izin \"Alarm & pengingat\" untuk Nine mati, jadi alarm bisa sedikit terlambat.",
            " Note: Nine's \"Alarms & reminders\" permission is off, so it may ring a little late."
        )
        val head = if (cmd.repeat.isEmpty()) {
            lang.t("Alarm terpasang untuk ", "Alarm set for ") + Fmt.at(next, lang, now) + label + s(lang) + "."
        } else {
            lang.t(
                "Alarm ${a.clock} terpasang ${daysText(cmd.repeat, lang)}$label${s(lang)}. Pertama berbunyi ",
                "Alarm set for ${a.clock} ${daysText(cmd.repeat, lang)}$label${s(lang)}. First one "
            ) + Fmt.at(next, lang, now) + "."
        }
        return SkillResult(head + warn)
    }

    private fun showAlarms(lang: AppLang, now: LocalDateTime): SkillResult {
        if (!prefs.nineAlarms) {
            return SkillResult(lang.t("Membuka daftar alarm${s(lang)}.", "Opening your alarms${s(lang)}."), intent = Intent(AlarmClock.ACTION_SHOW_ALARMS))
        }
        val list = AlarmMatch.sorted(alarms.all())
        if (list.isEmpty()) {
            return SkillResult(lang.t("Belum ada alarm${s(lang)}. Katakan misalnya \"pasang alarm jam 6 pagi\".", "No alarms yet${s(lang)}. Try \"set an alarm for 6 AM\"."))
        }
        val on = list.count { it.enabled }
        val lines = list.take(8).mapIndexed { i, a -> "${i + 1}. ${alarmDesc(a, lang, withState = true)}" }.joinToString("\n")
        val next = list.filter { it.enabled }.minByOrNull { it.nextTrigger(now) }
        val head = lang.t("Ada ${list.size} alarm, $on menyala${s(lang)}", "You have ${list.size} ${if (list.size == 1) "alarm" else "alarms"}, $on on${s(lang)}") +
            (next?.let { lang.t(". Berikutnya ", ". Next one ") + Fmt.at(it.nextTrigger(now), lang, now) } ?: "") + ":"
        return SkillResult("$head\n$lines")
    }

    private fun refDesc(ref: AlarmRef, lang: AppLang): String = when {
        ref.label != null -> "\"${ref.label}\""
        ref.hour != null -> lang.t("jam ", "for ") + String.format("%02d.%02d", ref.hour, ref.minute ?: 0)
        ref.index != null -> lang.t("nomor ${ref.index}", "number ${ref.index}")
        else -> ""
    }

    private fun alarmToggle(cmd: Command.AlarmToggle, lang: AppLang, now: LocalDateTime): SkillResult {
        // "Matikan alarm" saat alarm sedang berbunyi = hentikan bunyinya.
        if (!cmd.on && AlarmRingService.ringing && (cmd.ref.isEmpty || cmd.ref.id == AlarmRingService.ringingId)) {
            AlarmRingService.stop(ctx)
            val morning = now.hour in 4..10
            return SkillResult(
                lang.t("Alarm dimatikan${s(lang)}.", "Alarm off${s(lang)}.") +
                    if (morning) lang.t(" Selamat pagi.", " Good morning.") else ""
            )
        }
        if (!prefs.nineAlarms) {
            return SkillResult(
                lang.t(
                    "Alarm di aplikasi Jam tidak bisa saya ubah langsung, jadi saya buka daftarnya${s(lang)}. Pakai alarm Nine (Pengaturan > Alarm) agar bisa diatur lewat suara.",
                    "I can't change Clock-app alarms directly, so I'm opening the list${s(lang)}. Switch to Nine alarms (Settings > Alarms) to control them by voice."
                ),
                intent = Intent(AlarmClock.ACTION_SHOW_ALARMS)
            )
        }
        val list = alarms.all()
        if (list.isEmpty()) return SkillResult(lang.t("Belum ada alarm${s(lang)}.", "There are no alarms yet${s(lang)}."))
        val op = if (cmd.on) AlarmMatch.Op.ON else AlarmMatch.Op.OFF
        val hits = AlarmMatch.find(cmd.ref, list, op, now, alarms.lastId)
        if (hits.isEmpty()) {
            val generic = cmd.ref.hour == null && cmd.ref.label == null && cmd.ref.index == null && !cmd.ref.last && !cmd.ref.next
            return SkillResult(
                if (generic) {
                    if (cmd.on) lang.t("Semua alarm sudah menyala${s(lang)}.", "All alarms are already on${s(lang)}.")
                    else lang.t("Tidak ada alarm yang menyala${s(lang)}.", "No alarms are on${s(lang)}.")
                } else {
                    lang.t("Saya tidak menemukan alarm ${refDesc(cmd.ref, lang)}${s(lang)}.", "I couldn't find an alarm ${refDesc(cmd.ref, lang)}${s(lang)}.")
                }
            )
        }
        if (hits.size > 1 && !cmd.ref.all) {
            val options = hits.take(6).map { Choice(alarmDesc(it, lang, withState = true), Command.AlarmToggle(AlarmRef(id = it.id), cmd.on)) }
            val lines = options.mapIndexed { i, c -> "${i + 1}. ${c.label}" }.joinToString("\n")
            return SkillResult(
                lang.t("Ada ${hits.size} alarm${s(lang)}. Yang mana? (atau \"semua\")", "There are ${hits.size} alarms${s(lang)}. Which one? (or \"all\")") + "\n" + lines,
                pending = Pending.Choose(options, all = Command.AlarmToggle(cmd.ref.copy(all = true), cmd.on))
            )
        }
        for (a in hits) {
            val upd = if (cmd.on) {
                a.copy(enabled = true, date = a.date?.takeIf { d -> d.atTime(a.hour, a.minute).isAfter(now) })
            } else {
                a.copy(enabled = false)
            }
            alarms.update(upd)
            AlarmScheduler.schedule(ctx, upd, now)
            if (!cmd.on && AlarmRingService.ringingId == a.id) AlarmRingService.stop(ctx)
        }
        if (hits.size > 1) {
            return SkillResult(
                if (cmd.on) lang.t("${hits.size} alarm dinyalakan${s(lang)}.", "${hits.size} alarms turned on${s(lang)}.")
                else lang.t("${hits.size} alarm dimatikan${s(lang)}.", "${hits.size} alarms turned off${s(lang)}.")
            )
        }
        val a = hits[0]
        return SkillResult(
            if (cmd.on) {
                val next = (alarms.get(a.id) ?: a).nextTrigger(now)
                lang.t("Alarm ${alarmDesc(a, lang)} menyala lagi${s(lang)}, berbunyi ", "Alarm ${alarmDesc(a, lang)} is back on${s(lang)}, ringing ") +
                    Fmt.at(next, lang, now) + "."
            } else {
                lang.t("Alarm ${alarmDesc(a, lang)} dimatikan${s(lang)}.", "Alarm ${alarmDesc(a, lang)} turned off${s(lang)}.")
            }
        )
    }

    private fun alarmDelete(cmd: Command.AlarmDelete, lang: AppLang, confirmed: Boolean, now: LocalDateTime): SkillResult {
        if (!prefs.nineAlarms) {
            return SkillResult(
                lang.t(
                    "Alarm di aplikasi Jam tidak bisa saya hapus langsung, jadi saya buka daftarnya${s(lang)}.",
                    "I can't delete Clock-app alarms directly, so I'm opening the list${s(lang)}."
                ),
                intent = Intent(AlarmClock.ACTION_SHOW_ALARMS)
            )
        }
        val list = alarms.all()
        if (list.isEmpty()) return SkillResult(lang.t("Belum ada alarm${s(lang)}.", "There are no alarms yet${s(lang)}."))
        val hits = AlarmMatch.find(cmd.ref, list, AlarmMatch.Op.DELETE, now, alarms.lastId)
        if (hits.isEmpty()) {
            return SkillResult(lang.t("Saya tidak menemukan alarm ${refDesc(cmd.ref, lang)}${s(lang)}.", "I couldn't find an alarm ${refDesc(cmd.ref, lang)}${s(lang)}."))
        }
        if (hits.size > 1 && !cmd.ref.all) {
            val options = hits.take(6).map { Choice(alarmDesc(it, lang, withState = true), Command.AlarmDelete(AlarmRef(id = it.id))) }
            val lines = options.mapIndexed { i, c -> "${i + 1}. ${c.label}" }.joinToString("\n")
            return SkillResult(
                lang.t("Alarm yang mana${s(lang)}? (atau \"semua\")", "Which alarm${s(lang)}? (or \"all\")") + "\n" + lines,
                pending = Pending.Choose(options, all = Command.AlarmDelete(cmd.ref.copy(all = true)))
            )
        }
        if (hits.size > 1 && !confirmed) {
            return SkillResult(
                lang.t("Hapus ${hits.size} alarm? Katakan ya atau tidak.", "Delete ${hits.size} alarms? Say yes or no."),
                pending = Pending.Confirm(cmd)
            )
        }
        for (a in hits) {
            AlarmScheduler.cancel(ctx, a.id)
            alarms.remove(a.id)
            if (AlarmRingService.ringingId == a.id) AlarmRingService.stop(ctx)
        }
        return SkillResult(
            if (hits.size == 1) lang.t("Alarm ${alarmDesc(hits[0], lang)} dihapus${s(lang)}.", "Alarm ${alarmDesc(hits[0], lang)} deleted${s(lang)}.")
            else lang.t("${hits.size} alarm dihapus${s(lang)}.", "${hits.size} alarms deleted${s(lang)}.")
        )
    }

    private fun setTimer(cmd: Command.SetTimer, lang: AppLang): SkillResult {
        if (cmd.seconds > 24 * 3600) {
            return SkillResult(lang.t("Timer maksimal 24 jam.", "Timers can be at most 24 hours."))
        }
        val i = Intent(AlarmClock.ACTION_SET_TIMER).apply {
            putExtra(AlarmClock.EXTRA_LENGTH, cmd.seconds)
            if (!cmd.label.isNullOrBlank()) putExtra(AlarmClock.EXTRA_MESSAGE, cmd.label)
            putExtra(AlarmClock.EXTRA_SKIP_UI, true)
        }
        val d = Fmt.duration(cmd.seconds, lang)
        val reply = if (cmd.label.isNullOrBlank()) {
            lang.t("Timer $d berjalan${s(lang)}.", "Timer set for $d${s(lang)}.")
        } else {
            lang.t("Baik${s(lang)}, saya ingatkan $d lagi: ${cmd.label}.", "Very good${s(lang)}, I'll remind you in $d: ${cmd.label}.")
        }
        return SkillResult(reply, intent = i)
    }

    private fun setReminder(cmd: Command.SetReminder, lang: AppLang, now: LocalDateTime, denied: Set<String>): SkillResult {
        val text = cmd.text.ifBlank { lang.t("Pengingat", "Reminder") }
        if (Duration.between(now, cmd.at).isNegative) {
            return SkillResult(lang.t("Waktu itu sudah lewat${s(lang)}.", "That time has already passed${s(lang)}."))
        }
        // Pengingat disimpan sebagai acara kalender dengan notifikasi tepat waktu, jadi terlihat di Kalender.
        return addEvent(Command.AddEvent(text, cmd.at, null, false), lang, now, denied, reminderMinutes = 0, asReminder = true)
    }

    private fun prompt(p: Pending, lang: AppLang): String = when (p) {
        is Pending.AlarmTime -> lang.t("Jam berapa alarmnya?", "What time should I set the alarm for?")
        is Pending.TimerDuration -> lang.t("Berapa lama timernya?", "How long should the timer be?")
        is Pending.ReminderTime -> lang.t("Kapan saya harus mengingatkan Anda?", "When should I remind you?")
        Pending.ReminderText -> lang.t("Apa yang harus saya ingatkan?", "What should I remind you about?")
        Pending.NoteText -> lang.t("Apa yang ingin dicatat?", "What would you like to note down?")
        is Pending.SmsBody -> lang.t("Apa isi pesannya?", "What should the message say?")
        Pending.CallTarget -> lang.t("Siapa yang ingin ditelepon?", "Who would you like to call?")
        Pending.AppName -> lang.t("Aplikasi apa yang ingin dibuka?", "Which app should I open?")
        is Pending.EventTime -> lang.t("Kapan acaranya${s(lang)}?", "When is it${s(lang)}?")
        Pending.TodoText -> lang.t("Tugas apa yang ingin ditambahkan?", "What should I add to your list?")
        is Pending.Confirm -> lang.t("Lanjutkan? Katakan ya atau tidak.", "Proceed? Say yes or no.")
        is Pending.Choose -> lang.t("Yang mana${s(lang)}?", "Which one${s(lang)}?")
    }

    // ------------------------------------------------------------ buka aplikasi

    private val aliases: Map<String, List<String>> = mapOf(
        "kamera" to listOf("camera"),
        "kalender" to listOf("calendar"),
        "kalkulator" to listOf("calculator"),
        "jam" to listOf("clock"),
        "pesan" to listOf("messages", "message"),
        "sms" to listOf("messages", "message"),
        "telepon" to listOf("phone", "dialer"),
        "telpon" to listOf("phone", "dialer"),
        "kontak" to listOf("contacts"),
        "peta" to listOf("maps"),
        "gmaps" to listOf("maps"),
        "google maps" to listOf("maps"),
        "galeri" to listOf("gallery", "photos"),
        "foto" to listOf("photos", "gallery"),
        "musik" to listOf("youtube music", "music", "spotify"),
        "unduhan" to listOf("downloads", "my files"),
        "berkas" to listOf("my files", "files"),
        "file" to listOf("my files", "files"),
        "file saya" to listOf("my files"),
        "browser" to listOf("chrome", "internet", "browser"),
        "peramban" to listOf("chrome", "internet", "browser"),
        "internet" to listOf("chrome", "internet"),
        "email" to listOf("gmail", "email", "mail"),
        "surel" to listOf("gmail", "email"),
        "pengaturan" to listOf("settings"),
        "setelan" to listOf("settings"),
        "toko aplikasi" to listOf("play store", "galaxy store"),
        "playstore" to listOf("play store"),
        "wa" to listOf("whatsapp"),
        "whatsap" to listOf("whatsapp"),
        "watsap" to listOf("whatsapp"),
        "ig" to listOf("instagram"),
        "insta" to listOf("instagram"),
        "yt" to listOf("youtube"),
        "you tube" to listOf("youtube"),
        "yt music" to listOf("youtube music"),
        "fb" to listOf("facebook"),
        "x" to listOf("x", "twitter"),
        "twitter" to listOf("x", "twitter"),
        "tele" to listOf("telegram"),
        "tokped" to listOf("tokopedia"),
        "catatan samsung" to listOf("samsung notes"),
        "catatan" to listOf("samsung notes", "notes", "keep"),
        "perekam suara" to listOf("voice recorder"),
        "senter" to listOf("flashlight"),
        "cuaca" to listOf("weather"),
        "kesehatan" to listOf("samsung health", "health")
    )

    private fun key(s: String) = s.lowercase().filter { it.isLetterOrDigit() }

    private fun lev(a: String, b: String): Int {
        if (a == b) return 0
        if (a.isEmpty()) return b.length
        if (b.isEmpty()) return a.length
        var prev = IntArray(b.length + 1) { it }
        var cur = IntArray(b.length + 1)
        for (i in 1..a.length) {
            cur[0] = i
            for (j in 1..b.length) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                cur[j] = minOf(cur[j - 1] + 1, prev[j] + 1, prev[j - 1] + cost)
            }
            val t = prev
            prev = cur
            cur = t
        }
        return prev[b.length]
    }

    @Suppress("DEPRECATION")
    private fun launcherApps(): List<android.content.pm.ResolveInfo> {
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        return if (Build.VERSION.SDK_INT >= 33) {
            ctx.packageManager.queryIntentActivities(intent, PackageManager.ResolveInfoFlags.of(0))
        } else {
            ctx.packageManager.queryIntentActivities(intent, 0)
        }
    }

    private class AppHit(val label: String, val pkg: String, val cls: String, val score: Int)

    private fun appScore(k: String, w: String): Int = when {
        k == w -> 100
        k.startsWith(w) && w.length >= 3 -> 85
        w.length >= 4 && k.contains(w) -> 70
        k.length >= 4 && w.contains(k) -> 60
        w.length >= 5 && lev(k, w) <= (if (w.length >= 8) 2 else 1) -> 75 // salah dengar: "whatsap", "youtub"
        else -> 0
    }

    /** Cari aplikasi terpasang yang paling cocok dengan [query] (nama, alias, atau salah eja kecil). */
    private fun findApp(query: String): AppHit? = findApps(query).firstOrNull()

    private fun findApps(query: String): List<AppHit> {
        val q = query.lowercase().trim()
        val wanted = (listOf(q) + (aliases[q] ?: emptyList())).map { key(it) }.filter { it.isNotEmpty() }.distinct()
        if (wanted.isEmpty()) return emptyList()
        val pm = ctx.packageManager
        val hits = ArrayList<AppHit>()
        for (ri in launcherApps()) {
            val pkg = ri.activityInfo.packageName
            if (pkg == ctx.packageName) continue
            val label = ri.loadLabel(pm).toString()
            val k = key(label)
            if (k.isEmpty()) continue
            var best = 0
            for ((i, w) in wanted.withIndex()) {
                // alias sedikit di bawah nama asli
                val sc = appScore(k, w) - (if (i == 0) 0 else 2)
                if (sc > best) best = sc
            }
            if (best > 0) hits.add(AppHit(label, pkg, ri.activityInfo.name, best))
        }
        return hits.sortedWith(compareByDescending<AppHit> { it.score }.thenBy { it.label.length })
    }

    private fun launch(hit: AppHit, lang: AppLang): SkillResult {
        val launch = Intent(Intent.ACTION_MAIN).apply {
            addCategory(Intent.CATEGORY_LAUNCHER)
            setClassName(hit.pkg, hit.cls)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
        }
        val fallback = ctx.packageManager.getLaunchIntentForPackage(hit.pkg)?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return SkillResult(lang.t("Membuka ${hit.label}${s(lang)}.", "Opening ${hit.label}${s(lang)}."), intent = launch, fallback = fallback)
    }

    private fun openApp(query: String, lang: AppLang): SkillResult {
        if (key(query).isEmpty()) return SkillResult(prompt(Pending.AppName, lang), pending = Pending.AppName)
        val hits = findApps(query)
        val best = hits.firstOrNull()
        if (best == null || best.score < 60) {
            val near = hits.take(3).joinToString(", ") { it.label }
            return SkillResult(
                if (near.isNotEmpty()) {
                    lang.t("Aplikasi \"$query\" tidak ditemukan. Mungkin maksud Anda: $near?", "I couldn't find \"$query\". Did you mean: $near?")
                } else {
                    lang.t("Aplikasi \"$query\" tidak ditemukan di HP ini${s(lang)}.", "I couldn't find an app called \"$query\" on this phone${s(lang)}.")
                }
            )
        }
        return launch(best, lang)
    }

    // ------------------------------------------------------------ pengaturan

    private fun settingsName(t: SettingsTarget, lang: AppLang): String = when (t) {
        SettingsTarget.WIFI -> "Wi-Fi"
        SettingsTarget.BLUETOOTH -> "Bluetooth"
        SettingsTarget.AIRPLANE -> lang.t("mode pesawat", "airplane mode")
        SettingsTarget.LOCATION -> lang.t("lokasi", "location")
        SettingsTarget.MOBILE_DATA -> lang.t("data seluler", "mobile data")
        SettingsTarget.DISPLAY -> lang.t("layar", "display")
        SettingsTarget.SOUND -> lang.t("suara", "sound")
        SettingsTarget.BATTERY_SAVER -> lang.t("hemat baterai", "battery saver")
        SettingsTarget.DND -> lang.t("jangan ganggu", "do not disturb")
        SettingsTarget.HOTSPOT -> "hotspot"
        SettingsTarget.MOTION -> "motion assist"
        SettingsTarget.GENERAL -> lang.t("pengaturan", "settings")
    }

    /** Pengaturan yang bisa dinyalakan/dimatikan Nine lewat layanan aksesibilitas (ketuk tombol Panel Cepat). */
    private fun toggleable(t: SettingsTarget): Boolean = when (t) {
        SettingsTarget.WIFI, SettingsTarget.BLUETOOTH, SettingsTarget.AIRPLANE, SettingsTarget.HOTSPOT, SettingsTarget.MOTION,
        SettingsTarget.LOCATION, SettingsTarget.MOBILE_DATA, SettingsTarget.DND, SettingsTarget.BATTERY_SAVER -> true
        SettingsTarget.DISPLAY, SettingsTarget.SOUND, SettingsTarget.GENERAL -> false
    }

    private fun openSettings(cmd: Command.OpenSettings, lang: AppLang): SkillResult {
        val name = settingsName(cmd.target, lang)
        val on = cmd.turnOn
        val svc = NineAccessibilityService.instance
        if (on != null && toggleable(cmd.target) && svc != null) {
            val verb = if (on) lang.t("menyalakan", "turning on") else lang.t("mematikan", "turning off")
            return SkillResult(
                lang.t("Baik${s(lang)}, saya ${if (on) "nyalakan" else "matikan"} $name.", "Very well${s(lang)}, $verb $name."),
                deferred = { done ->
                    svc.toggle(cmd.target, on) { outcome -> done(toggleText(outcome, name, on, lang, svc.lastStage)) }
                }
            )
        }
        val (intent, fallback) = when (cmd.target) {
            SettingsTarget.WIFI -> Pair(
                if (Build.VERSION.SDK_INT >= 29) Intent(Settings.Panel.ACTION_INTERNET_CONNECTIVITY) else Intent(Settings.ACTION_WIFI_SETTINGS),
                Intent(Settings.ACTION_WIFI_SETTINGS)
            )
            SettingsTarget.BLUETOOTH -> Pair(Intent(Settings.ACTION_BLUETOOTH_SETTINGS), null)
            SettingsTarget.AIRPLANE -> Pair(Intent(Settings.ACTION_AIRPLANE_MODE_SETTINGS), Intent(Settings.ACTION_WIRELESS_SETTINGS))
            SettingsTarget.LOCATION -> Pair(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS), null)
            SettingsTarget.MOBILE_DATA -> Pair(
                if (Build.VERSION.SDK_INT >= 29) Intent(Settings.Panel.ACTION_INTERNET_CONNECTIVITY) else Intent(Settings.ACTION_WIRELESS_SETTINGS),
                Intent(Settings.ACTION_WIRELESS_SETTINGS)
            )
            SettingsTarget.DISPLAY -> Pair(Intent(Settings.ACTION_DISPLAY_SETTINGS), null)
            SettingsTarget.SOUND -> Pair(Intent(Settings.ACTION_SOUND_SETTINGS), null)
            SettingsTarget.BATTERY_SAVER -> Pair(Intent(Settings.ACTION_BATTERY_SAVER_SETTINGS), Intent(Settings.ACTION_SETTINGS))
            SettingsTarget.DND -> Pair(Intent("android.settings.ZEN_MODE_SETTINGS"), Intent(Settings.ACTION_SOUND_SETTINGS))
            SettingsTarget.HOTSPOT -> Pair(Intent(Settings.ACTION_WIRELESS_SETTINGS), Intent(Settings.ACTION_SETTINGS))
            SettingsTarget.MOTION -> Pair(Intent(Settings.ACTION_SETTINGS), null)
            SettingsTarget.GENERAL -> Pair(Intent(Settings.ACTION_SETTINGS), null)
        }
        val reply = if (on == null) {
            lang.t("Membuka pengaturan $name${s(lang)}.", "Opening $name settings${s(lang)}.")
        } else if (!NineAccessibilityService.AVAILABLE) {
            lang.t(
                "Android tidak mengizinkan aplikasi biasa ${if (on) "menyalakan" else "mematikan"} $name sendiri${s(lang)}, jadi saya buka pengaturannya. Nine edisi Full (Nine-Full.apk) bisa melakukannya otomatis.",
                "Android doesn't let ordinary apps switch $name ${if (on) "on" else "off"} by themselves${s(lang)}, so I'm opening its settings. The Full edition (Nine-Full.apk) can do it automatically."
            )
        } else {
            lang.t(
                "Untuk ${if (on) "menyalakan" else "mematikan"} $name sendiri saya butuh layanan aksesibilitas Nine (Pengaturan > Aksesibilitas > Nine). Sementara ini saya buka pengaturannya.",
                "To switch $name ${if (on) "on" else "off"} by myself I need the Nine accessibility service (Settings > Accessibility > Nine). For now I'm opening its settings."
            )
        }
        return SkillResult(reply, intent = intent, fallback = fallback)
    }

    private fun toggleText(o: NineAccessibilityService.Outcome, name: String, on: Boolean, lang: AppLang, stage: String): String {
        val state = if (on) lang.t("menyala", "on") else lang.t("mati", "off")
        return when (o) {
            NineAccessibilityService.Outcome.DONE -> lang.t("$name sekarang $state${s(lang)}.", "$name is now $state${s(lang)}.")
            NineAccessibilityService.Outcome.ALREADY -> lang.t("$name sudah $state${s(lang)}.", "$name was already $state${s(lang)}.")
            NineAccessibilityService.Outcome.CLICKED -> lang.t(
                "Tombol $name sudah saya ketuk${s(lang)}. Silakan periksa apakah sudah $state.",
                "I tapped the $name tile${s(lang)}. Please check that it's $state."
            )
            NineAccessibilityService.Outcome.NOT_FOUND -> lang.t(
                "Maaf${s(lang)}, saya tidak menemukan tombol $name di Panel Cepat maupun halaman pengaturannya. Tambahkan tombolnya ke Panel Cepat lalu coba lagi. (beta, kode: $stage)",
                "Sorry${s(lang)}, I couldn't find a $name tile in Quick Settings or its settings page. Add it to Quick Settings and try again. (beta, code: $stage)"
            )
            NineAccessibilityService.Outcome.BUSY -> lang.t(
                "Saya sedang mengerjakan pengaturan lain${s(lang)}. Coba lagi sebentar lagi.",
                "I'm in the middle of another setting${s(lang)}. Try again in a moment."
            )
            NineAccessibilityService.Outcome.FAILED -> lang.t(
                "Maaf${s(lang)}, saya gagal mengubah $name. Pastikan layar terbuka kuncinya, lalu coba lagi. (beta, kode: $stage)",
                "Sorry${s(lang)}, I couldn't change $name. Make sure the screen is unlocked, then try again. (beta, code: $stage)"
            )
        }
    }

    private fun flashlight(on: Boolean, lang: AppLang): SkillResult {
        return try {
            val cm = ctx.getSystemService(Context.CAMERA_SERVICE) as CameraManager
            val id = cm.cameraIdList.firstOrNull {
                val ch = cm.getCameraCharacteristics(it)
                ch.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true &&
                    ch.get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK
            } ?: return SkillResult(lang.t("Perangkat ini tidak punya lampu flash.", "This device has no flashlight."))
            cm.setTorchMode(id, on)
            SkillResult(if (on) lang.t("Senter menyala${s(lang)}.", "Flashlight on${s(lang)}.") else lang.t("Senter dimatikan${s(lang)}.", "Flashlight off${s(lang)}."))
        } catch (e: Exception) {
            SkillResult(lang.t("Maaf, senter tidak bisa dikendalikan sekarang.", "Sorry, I can't control the flashlight right now."))
        }
    }

    private fun volume(cmd: Command.Volume, lang: AppLang): SkillResult {
        return try {
            val am = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            val s = AudioManager.STREAM_MUSIC
            val max = am.getStreamMaxVolume(s)
            when (cmd.action) {
                VolumeAction.UP -> am.adjustStreamVolume(s, AudioManager.ADJUST_RAISE, AudioManager.FLAG_SHOW_UI)
                VolumeAction.DOWN -> am.adjustStreamVolume(s, AudioManager.ADJUST_LOWER, AudioManager.FLAG_SHOW_UI)
                VolumeAction.MUTE -> am.adjustStreamVolume(s, AudioManager.ADJUST_MUTE, AudioManager.FLAG_SHOW_UI)
                VolumeAction.UNMUTE -> am.adjustStreamVolume(s, AudioManager.ADJUST_UNMUTE, AudioManager.FLAG_SHOW_UI)
                VolumeAction.MAX -> am.setStreamVolume(s, max, AudioManager.FLAG_SHOW_UI)
                VolumeAction.SET -> {
                    val p = (cmd.percent ?: 50).coerceIn(0, 100)
                    am.setStreamVolume(s, (p * max / 100.0).roundToInt(), AudioManager.FLAG_SHOW_UI)
                }
            }
            val reply = when (cmd.action) {
                VolumeAction.UP -> lang.t("Volume dinaikkan.", "Volume up.")
                VolumeAction.DOWN -> lang.t("Volume diturunkan.", "Volume down.")
                VolumeAction.MUTE -> lang.t("Suara dibisukan.", "Muted.")
                VolumeAction.UNMUTE -> lang.t("Suara diaktifkan.", "Unmuted.")
                VolumeAction.MAX -> lang.t("Volume maksimal.", "Volume at maximum.")
                VolumeAction.SET -> lang.t("Volume diatur ke ${cmd.percent} persen.", "Volume set to ${cmd.percent} percent.")
            }
            SkillResult(reply)
        } catch (e: SecurityException) {
            SkillResult(lang.t("Maaf, volume tidak bisa diubah sekarang.", "Sorry, I can't change the volume right now."))
        }
    }

    // ------------------------------------------------------------ info perangkat

    private fun battery(lang: AppLang): SkillResult {
        val bm = ctx.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        val pct = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        val sticky = ctx.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val status = sticky?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        val charging = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL
        val base = lang.t("Baterai $pct persen", "Battery is at $pct percent")
        val tail = (if (charging) lang.t(" dan sedang diisi daya", " and charging") else "") + s(lang) + "." +
            (if (!charging && pct <= 20) lang.t(" Mungkin sudah waktunya mencari pengisi daya.", " Might I suggest a charger?") else "")
        return SkillResult(base + tail)
    }

    // ------------------------------------------------------------ catatan

    private fun readNotes(lang: AppLang): SkillResult {
        val all = notes.all()
        if (all.isEmpty()) return SkillResult(lang.t("Belum ada catatan.", "You have no notes yet."))
        val last = all.takeLast(5)
        val lines = last.mapIndexed { i, n -> "${i + 1}. ${n.text}" }.joinToString("\n")
        val head = if (all.size > last.size) {
            lang.t("Ada ${all.size} catatan. ${last.size} terakhir:", "You have ${all.size} notes. The last ${last.size}:")
        } else {
            lang.t("Ada ${all.size} catatan:", "You have ${all.size} notes:")
        }
        return SkillResult("$head\n$lines")
    }

    private fun clearNotes(lang: AppLang, confirmed: Boolean): SkillResult {
        val n = notes.all().size
        if (n == 0) return SkillResult(lang.t("Belum ada catatan untuk dihapus.", "There are no notes to delete."))
        if (!confirmed) {
            return SkillResult(
                lang.t("Hapus semua $n catatan? Katakan ya atau tidak.", "Delete all $n notes? Say yes or no."),
                pending = Pending.Confirm(Command.ClearNotes)
            )
        }
        notes.clear()
        return SkillResult(lang.t("Semua catatan sudah dihapus${s(lang)}.", "All notes deleted${s(lang)}."))
    }

    // ------------------------------------------------------------ ingatan jangka panjang

    private fun remember(text: String, lang: AppLang): SkillResult {
        val clean = MemoryStore.clean(text)
        if (clean.isEmpty()) return SkillResult(lang.t("Apa yang harus saya ingat?", "What should I remember?"))
        return if (memory.add(clean)) {
            SkillResult(lang.t("Baik${s(lang)}, saya ingat: $clean", "Very good${s(lang)}, I'll remember: $clean"))
        } else {
            SkillResult(lang.t("Itu sudah saya ingat${s(lang)}.", "I already remember that${s(lang)}."))
        }
    }

    private fun forget(query: String, lang: AppLang): SkillResult {
        val n = memory.forget(query)
        if (n == 0) {
            return SkillResult(lang.t("Saya tidak menemukan ingatan tentang \"$query\"${s(lang)}.", "I couldn't find anything about \"$query\" in my memory${s(lang)}."))
        }
        return SkillResult(
            lang.t("Sudah saya lupakan ($n hal)${s(lang)}.", "Forgotten ($n ${if (n == 1) "item" else "items"})${s(lang)}."),
            resetChat = true // percakapan AI yang berjalan masih memuat ingatan lama
        )
    }

    private fun forgetAll(lang: AppLang, confirmed: Boolean): SkillResult {
        val n = memory.count()
        if (n == 0) return SkillResult(lang.t("Saya belum mengingat apa pun tentang Anda.", "I don't remember anything about you yet."))
        if (!confirmed) {
            return SkillResult(
                lang.t("Lupakan semua $n hal yang saya ingat tentang Anda? Katakan ya atau tidak.", "Forget all $n things I remember about you? Say yes or no."),
                pending = Pending.Confirm(Command.ForgetAll)
            )
        }
        memory.clear()
        return SkillResult(lang.t("Semua ingatan sudah dihapus${s(lang)}.", "All memories deleted${s(lang)}."), resetChat = true)
    }

    private fun readMemory(lang: AppLang): SkillResult {
        val all = memory.all()
        if (all.isEmpty()) {
            return SkillResult(
                lang.t(
                    "Saya belum mengingat apa pun tentang Anda. Katakan \"ingat bahwa …\" atau ceritakan saja tentang diri Anda.",
                    "I don't remember anything about you yet. Say \"remember that …\" or simply tell me about yourself."
                )
            )
        }
        val last = all.takeLast(10)
        val lines = last.mapIndexed { i, f -> "${i + 1}. ${f.text}" }.joinToString("\n")
        val head = if (all.size > last.size) {
            lang.t("Saya mengingat ${all.size} hal. ${last.size} terakhir:", "I remember ${all.size} things. The latest ${last.size}:")
        } else {
            lang.t("Saya mengingat ${all.size} hal:", "I remember ${all.size} things:")
        }
        return SkillResult("$head\n$lines")
    }

    // ------------------------------------------------------------ telepon & SMS

    /** Mengembalikan nomor telepon bila [target] berupa nomor (digit atau angka yang diucapkan). */
    private fun asPhoneNumber(target: String): String? {
        val t = NumberWords.normalize(target)
        if (t.isEmpty()) return null
        if (!t.all { it.isDigit() || it == ' ' || it == '+' || it == '-' || it == '(' || it == ')' || it == '.' }) return null
        val digits = t.filter { it.isDigit() || it == '+' }
        return if (digits.count { it.isDigit() } >= 3) digits else null
    }

    private fun contactPermissionResult(lang: AppLang, denied: Set<String>): SkillResult? {
        if (has(Manifest.permission.READ_CONTACTS)) return null
        return if (Manifest.permission.READ_CONTACTS in denied) {
            SkillResult(
                lang.t(
                    "Tanpa izin kontak saya tidak bisa mencari nama. Sebutkan nomor teleponnya.",
                    "Without contacts permission I can't look up names. Please say the phone number."
                )
            )
        } else {
            SkillResult(
                lang.t("Saya perlu izin kontak untuk mencari nama.", "I need contacts permission to look up names."),
                needPermissions = listOf(Manifest.permission.READ_CONTACTS)
            )
        }
    }

    private fun call(target: String, lang: AppLang, denied: Set<String>): SkillResult {
        val number = asPhoneNumber(target)
        if (number != null) return dial(number, number, lang, denied)

        contactPermissionResult(lang, denied)?.let { return it }
        return when (val r = contacts.find(target)) {
            is ContactsLookup.Result.Found -> dial(r.number, r.name, lang, denied)
            is ContactsLookup.Result.Ambiguous -> SkillResult(
                lang.t(
                    "Ada beberapa kontak: ${r.names.joinToString(", ")}. Sebutkan nama lengkapnya.",
                    "There are several contacts: ${r.names.joinToString(", ")}. Please say the full name."
                )
            )
            ContactsLookup.Result.NotFound -> SkillResult(
                lang.t("Kontak \"$target\" tidak ditemukan.", "I couldn't find a contact named \"$target\".")
            )
        }
    }

    private fun dial(number: String, display: String, lang: AppLang, denied: Set<String>): SkillResult {
        val uri = Uri.parse("tel:" + Uri.encode(number))
        if (has(Manifest.permission.CALL_PHONE)) {
            return SkillResult(
                lang.t("Menghubungi $display${s(lang)}.", "Calling $display${s(lang)}."),
                intent = Intent(Intent.ACTION_CALL, uri),
                fallback = Intent(Intent.ACTION_DIAL, uri)
            )
        }
        if (Manifest.permission.CALL_PHONE !in denied) {
            return SkillResult(
                lang.t("Saya perlu izin telepon untuk menelepon langsung.", "I need phone permission to place calls directly."),
                needPermissions = listOf(Manifest.permission.CALL_PHONE)
            )
        }
        return SkillResult(
            lang.t("Membuka aplikasi telepon untuk $display.", "Opening the dialer for $display."),
            intent = Intent(Intent.ACTION_DIAL, uri)
        )
    }

    private fun prepareSms(cmd: Command.SendSms, lang: AppLang, denied: Set<String>): SkillResult {
        var body = cmd.body?.trim().orEmpty()
        var targetName = cmd.target.trim()
        var number: String? = asPhoneNumber(targetName)
        var display = targetName

        if (number == null) {
            contactPermissionResult(lang, denied)?.let { return it }
            var res = contacts.find(targetName)
            // "kirim pesan ke Budi aku terlambat": nama dan isi belum dipisah, coba potong dari belakang.
            if (res is ContactsLookup.Result.NotFound && body.isEmpty()) {
                val words = targetName.split(Regex("\\s+"))
                for (n in words.size - 1 downTo 1) {
                    val tryName = words.take(n).joinToString(" ")
                    val r = contacts.find(tryName)
                    if (r !is ContactsLookup.Result.NotFound) {
                        res = r
                        body = words.drop(n).joinToString(" ")
                        targetName = tryName
                        break
                    }
                }
            }
            when (res) {
                is ContactsLookup.Result.Found -> {
                    number = res.number
                    display = res.name
                    targetName = res.name
                }
                is ContactsLookup.Result.Ambiguous -> return SkillResult(
                    lang.t(
                        "Ada beberapa kontak: ${res.names.joinToString(", ")}. Sebutkan nama lengkapnya.",
                        "There are several contacts: ${res.names.joinToString(", ")}. Please say the full name."
                    )
                )
                ContactsLookup.Result.NotFound -> return SkillResult(
                    lang.t("Kontak \"${cmd.target}\" tidak ditemukan.", "I couldn't find a contact named \"${cmd.target}\".")
                )
            }
        }
        if (body.isEmpty()) {
            return SkillResult(prompt(Pending.SmsBody(targetName), lang), pending = Pending.SmsBody(targetName))
        }
        val resolved = Command.SendSmsResolved(number!!, display, body)
        return SkillResult(
            lang.t(
                "Kirim ke $display: \"$body\"? Katakan ya atau tidak.",
                "Send to $display: \"$body\"? Say yes or no."
            ),
            pending = Pending.Confirm(resolved)
        )
    }

    @Suppress("DEPRECATION")
    private fun sendSms(cmd: Command.SendSmsResolved, lang: AppLang, denied: Set<String>): SkillResult {
        val compose = Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:" + Uri.encode(cmd.number))).apply {
            putExtra("sms_body", cmd.body)
        }
        if (!has(Manifest.permission.SEND_SMS)) {
            return if (Manifest.permission.SEND_SMS in denied) {
                SkillResult(
                    lang.t("Tanpa izin SMS, saya buka aplikasi pesan.", "Without SMS permission, I'm opening your messaging app."),
                    intent = compose
                )
            } else {
                SkillResult(
                    lang.t("Saya perlu izin SMS untuk mengirim pesan.", "I need SMS permission to send messages."),
                    needPermissions = listOf(Manifest.permission.SEND_SMS)
                )
            }
        }
        return try {
            val sms: SmsManager = if (Build.VERSION.SDK_INT >= 31) {
                ctx.getSystemService(SmsManager::class.java)
            } else {
                SmsManager.getDefault()
            }
            val parts = sms.divideMessage(cmd.body)
            if (parts.size > 1) {
                sms.sendMultipartTextMessage(cmd.number, null, parts, null, null)
            } else {
                sms.sendTextMessage(cmd.number, null, cmd.body, null, null)
            }
            SkillResult(lang.t("Pesan terkirim ke ${cmd.displayName}${s(lang)}.", "Message sent to ${cmd.displayName}${s(lang)}."))
        } catch (e: Exception) {
            SkillResult(
                lang.t("Pesan gagal dikirim langsung, saya buka aplikasi pesan.", "I couldn't send it directly, so I'm opening your messaging app."),
                intent = compose
            )
        }
    }

    // ------------------------------------------------------------ kamera & perekam

    private fun camera(cmd: Command.Camera, lang: AppLang): SkillResult {
        val i = Intent(if (cmd.video) MediaStore.INTENT_ACTION_VIDEO_CAMERA else MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA)
        if (cmd.front) {
            i.putExtra("android.intent.extras.CAMERA_FACING", 1)
            i.putExtra("android.intent.extras.LENS_FACING_FRONT", 1)
            i.putExtra("android.intent.extra.USE_FRONT_CAMERA", true)
            i.putExtra("com.google.assistant.extra.USE_FRONT_CAMERA", true)
        }
        cmd.timerSeconds?.let {
            i.putExtra("android.intent.extra.TIMER_DURATION_SECONDS", it)
            i.putExtra("com.google.assistant.extra.TIMER_DURATION_SECONDS", it)
        }
        val fallback = findApp("camera")?.let { hit ->
            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER).setClassName(hit.pkg, hit.cls)
        }
        val reply = when {
            cmd.video -> lang.t("Kamera video siap${s(lang)}. Tekan tombol rekam bila sudah siap.", "Video camera ready${s(lang)}. Press record when you're ready.")
            cmd.front -> lang.t("Kamera depan siap${s(lang)}. Silakan tersenyum.", "Front camera ready${s(lang)}. Do smile.")
            else -> lang.t("Kamera siap${s(lang)}.", "Camera ready${s(lang)}.")
        }
        return SkillResult(reply, intent = i, fallback = fallback)
    }

    private fun recordAudio(cmd: Command.RecordAudio, lang: AppLang, denied: Set<String>): SkillResult {
        if (!cmd.start) {
            if (!RecorderService.recording) {
                return SkillResult(lang.t("Tidak ada rekaman yang sedang berjalan${s(lang)}.", "Nothing is being recorded${s(lang)}."))
            }
            RecorderService.stop(ctx)
            return SkillResult(lang.t("Rekaman dihentikan dan disimpan di folder Recordings/Nine${s(lang)}.", "Recording stopped and saved to Recordings/Nine${s(lang)}."))
        }
        if (RecorderService.recording) {
            return SkillResult(lang.t("Saya sudah merekam${s(lang)}. Ketuk orb untuk berhenti.", "I'm already recording${s(lang)}. Tap the orb to stop."))
        }
        if (!has(Manifest.permission.RECORD_AUDIO)) {
            return SkillResult(lang.t("Saya perlu izin mikrofon${s(lang)}.", "I need microphone access${s(lang)}."), needPermissions = listOf(Manifest.permission.RECORD_AUDIO))
        }
        return SkillResult(
            lang.t("Mulai merekam${s(lang)}. Ketuk orb untuk berhenti.", "Recording now${s(lang)}. Tap the orb to stop."),
            startRecording = true
        )
    }

    // ------------------------------------------------------------ affinity & lokasi

    private fun affinityStatus(lang: AppLang): SkillResult {
        val st = affinity.state()
        val streak = if (st.streak > 0) {
            lang.t("Streak kita ${st.streak} hari (terbaik ${st.bestStreak}).", "Our streak is ${st.streak} ${if (st.streak == 1) "day" else "days"} (best ${st.bestStreak}).")
        } else {
            lang.t("Streak kita sedang kosong; bicara dengan saya hari ini untuk memulainya lagi.", "Our streak is at zero; talk to me today to start it again.")
        }
        val next = st.next?.let { n ->
            val xpLeft = (n.minXp - st.xp).coerceAtLeast(0)
            val dLeft = (AffinityRules.MIN_DAYS[n.number - 1] - st.days).coerceAtLeast(0)
            val parts = ArrayList<String>()
            if (xpLeft > 0) parts += "$xpLeft XP"
            if (dLeft > 0) parts += lang.t("$dLeft hari pemakaian", "$dLeft more days of use")
            val need = parts.joinToString(lang.t(" dan ", " and "))
            val pct = (st.progress * 100).toInt().coerceIn(0, 99)
            lang.t(
                " Progres menuju level ${n.number}, ${n.name(lang)}: $pct%. Sisa: $need.",
                " Progress to level ${n.number}, ${n.name(lang)}: $pct%. Remaining: $need."
            )
        } ?: lang.t(" Kita sudah di puncak. Saya terharu.", " We've reached the top. I'm quite touched.")
        val total = (st.days * 100 / AffinityRules.FULL_DAYS).coerceIn(0, 100)
        val days = if (st.next == null) "" else lang.t(
            " Menuju level tertinggi: hari ke-${st.days} dari ${AffinityRules.FULL_DAYS} ($total%), tidak bisa dipercepat dengan XP.",
            " Toward the top level: day ${st.days} of ${AffinityRules.FULL_DAYS} ($total%); XP can't speed that up."
        )
        // Tangga level dengan level sekarang diberi tanda kurung siku, mis. "Stranger › [Aware] › Acquainted › ...".
        val ladder = Affinity.LEVELS.joinToString(" › ") { if (it.number == st.level.number) "[${it.name(lang)}]" else it.name(lang) }
        val count = Affinity.LEVELS.size
        return SkillResult(
            lang.t(
                "Kedekatan kita level ${st.level.number} dari $count: ${st.level.name(lang)}${s(lang)}. $streak$next$days\n\nTangga level: $ladder",
                "We're at level ${st.level.number} of $count: ${st.level.name(lang)}${s(lang)}. $streak$next$days\n\nLevel ladder: $ladder"
            )
        )
    }

    private fun whereAmI(lang: AppLang, denied: Set<String>): SkillResult {
        val loc = location
        if (!loc.hasPermission()) {
            prefs.useLocation = true
            if (Manifest.permission.ACCESS_FINE_LOCATION in denied && Manifest.permission.ACCESS_COARSE_LOCATION in denied) {
                return SkillResult(lang.t("Tanpa izin lokasi saya tidak bisa melihat posisi Anda${s(lang)}.", "Without location access I can't see where you are${s(lang)}."))
            }
            return SkillResult(
                lang.t("Saya perlu izin lokasi${s(lang)}.", "I need location access${s(lang)}."),
                needPermissions = listOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
            )
        }
        prefs.useLocation = true
        return SkillResult(
            lang.t("Sebentar, saya cek lokasinya.", "One moment, checking your location."),
            deferred = { done ->
                loc.refresh { p ->
                    done(
                        when {
                            p == null -> lang.t("Maaf${s(lang)}, lokasi belum bisa ditentukan. Pastikan Lokasi di HP menyala.", "Sorry${s(lang)}, I can't get a fix. Make sure Location is on.")
                            p.label != null -> lang.t("Anda berada di sekitar ${p.label}${s(lang)}.", "You're around ${p.label}${s(lang)}.")
                            else -> lang.t(
                                "Posisi Anda di koordinat ${"%.4f".format(p.lat)}, ${"%.4f".format(p.lon)}${s(lang)}.",
                                "You're at ${"%.4f".format(p.lat)}, ${"%.4f".format(p.lon)}${s(lang)}."
                            )
                        }
                    )
                }
            }
        )
    }

    // ------------------------------------------------------------ bantuan

    // ------------------------------------------------------------ kalender

    private fun calendarPermissionResult(lang: AppLang, denied: Set<String>): SkillResult? {
        val perms = listOf(Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR)
        if (perms.all { has(it) }) return null
        return if (perms.any { it in denied }) {
            SkillResult(
                lang.t(
                    "Tanpa izin kalender saya tidak bisa membaca atau menambah acara. Izinkan di Pengaturan > Aplikasi > Nine > Izin.",
                    "Without calendar permission I can't read or add events. Allow it in Settings > Apps > Nine > Permissions."
                )
            )
        } else {
            SkillResult(
                lang.t("Saya perlu izin kalender${s(lang)}.", "I need calendar access${s(lang)}."),
                needPermissions = perms
            )
        }
    }

    private fun dayPhrase(date: LocalDate, lang: AppLang, now: LocalDateTime): String {
        val days = java.time.temporal.ChronoUnit.DAYS.between(now.toLocalDate(), date)
        val full = date.format(DateTimeFormatter.ofPattern("EEEE, d MMMM", lang.locale))
        return when (days) {
            0L -> lang.t("hari ini", "today")
            1L -> lang.t("besok", "tomorrow")
            else -> lang.t("hari $full", "on $full")
        }
    }

    private fun addEvent(
        cmd: Command.AddEvent,
        lang: AppLang,
        now: LocalDateTime,
        denied: Set<String>,
        reminderMinutes: Int = 15,
        asReminder: Boolean = false
    ): SkillResult {
        // Sapaan ("… sir", "… Tuan") tidak boleh ikut jadi judul acara.
        val hons = listOf(prefs.honorificId, prefs.honorificEn, "tuan", "sir")
            .filter { it.isNotBlank() }.map { Regex.escape(it) }.distinct().joinToString("|")
        val cleanTitle = cmd.title.replace(Regex("""(?:[,\s]+(?:$hons))+[.!]?$""", RegexOption.IGNORE_CASE), "").trim()
        val title = cleanTitle.ifBlank { lang.t(if (asReminder) "Pengingat" else "Acara", if (asReminder) "Reminder" else "Event") }
            .replaceFirstChar { it.uppercase() }
        val fallback = calendar.insertIntent(title, cmd.start, cmd.end, cmd.allDay)
        calendarPermissionResult(lang, denied)?.let { res ->
            // Izin ditolak permanen: buka layar acara baru di Kalender sebagai gantinya.
            return if (res.needPermissions.isEmpty()) {
                SkillResult(
                    lang.t("Tanpa izin kalender, saya buka layar acara baru. Ketuk Simpan${s(lang)}.", "Without calendar access I'm opening a new event screen. Tap Save${s(lang)}."),
                    intent = fallback
                )
            } else res
        }
        if (!cmd.allDay && cmd.start.isBefore(now.minusMinutes(1))) {
            return SkillResult(lang.t("Waktu itu sudah lewat${s(lang)}.", "That time has already passed${s(lang)}."))
        }
        val whenText = if (cmd.allDay) dayPhrase(cmd.start.toLocalDate(), lang, now) + lang.t(" (seharian)", " (all day)")
        else Fmt.at(cmd.start, lang, now) + (cmd.end?.let { lang.t(" sampai ", " until ") + Fmt.clock(it.hour, it.minute, lang) } ?: "")
        return when (val r = calendar.add(title, cmd.start, cmd.end, cmd.allDay, prefs.calendarId, reminderMinutes)) {
            is CalendarSkill.AddResult.Added -> {
                prefs.lastEventId = r.eventId
                val where = r.calendar.label
                val reply = if (asReminder) {
                    lang.t(
                        "Baik${s(lang)}, saya ingatkan $whenText: $title. Tersimpan di kalender $where.",
                        "Very good${s(lang)}, I'll remind you $whenText: $title. Saved to your $where calendar."
                    )
                } else {
                    lang.t("\"$title\" sudah masuk kalender $where, $whenText${s(lang)}.", "\"$title\" is on your $where calendar, $whenText${s(lang)}.")
                }
                SkillResult(reply)
            }
            CalendarSkill.AddResult.NoCalendar -> SkillResult(
                lang.t(
                    "Saya tidak menemukan kalender yang bisa ditulisi, jadi saya buka layar acara baru. Ketuk Simpan${s(lang)}.",
                    "I couldn't find a writable calendar, so I'm opening a new event screen. Tap Save${s(lang)}."
                ),
                intent = fallback
            )
            is CalendarSkill.AddResult.Failed -> SkillResult(
                lang.t(
                    "Acara gagal disimpan otomatis (${r.reason}), jadi saya buka layar acara baru. Ketuk Simpan${s(lang)}.",
                    "I couldn't save it automatically (${r.reason}), so I'm opening a new event screen. Tap Save${s(lang)}."
                ),
                intent = fallback
            )
        }
    }

    private fun lastEvent(lang: AppLang, denied: Set<String>): Pair<SkillResult?, CalendarSkill.Event?> {
        calendarPermissionResult(lang, denied)?.let { return Pair(it, null) }
        val id = prefs.lastEventId
        val ev = if (id > 0) calendar.get(id) else null
        if (ev == null) {
            return Pair(
                SkillResult(lang.t("Belum ada acara yang saya buat barusan${s(lang)}.", "I haven't created an event recently${s(lang)}.")),
                null
            )
        }
        return Pair(null, ev)
    }

    private fun moveLastEvent(cmd: Command.MoveLastEvent, lang: AppLang, now: LocalDateTime, denied: Set<String>): SkillResult {
        val (err, ev) = lastEvent(lang, denied)
        if (err != null || ev == null) return err!!
        val tp = TimeParser.parse(NumberWords.normalize(cmd.timeText))
            ?: return SkillResult(lang.t("Jam berapa${s(lang)}?", "What time${s(lang)}?"))
        val spec = tp.spec
        val start: LocalDateTime = when {
            spec.relativeSeconds != null -> now.plusSeconds(spec.relativeSeconds.toLong())
            spec.hasDay && spec.hour != null -> spec.resolve(now) ?: return SkillResult(lang.t("Waktunya kurang jelas${s(lang)}.", "That time isn't clear${s(lang)}."))
            spec.hasDay -> spec.resolveDate(now)?.atTime(ev.start.hour, ev.start.minute) ?: return SkillResult(lang.t("Tanggalnya kurang jelas.", "That date isn't clear."))
            spec.hour != null -> {
                var h = spec.hour
                if (spec.ambiguous && h in 1..6) h += 12
                ev.start.toLocalDate().atTime(h % 24, spec.minute)
            }
            else -> return SkillResult(lang.t("Jam berapa${s(lang)}?", "What time${s(lang)}?"))
        }
        if (!calendar.move(ev.id, start, null)) {
            return SkillResult(lang.t("Maaf${s(lang)}, acara gagal dipindahkan.", "Sorry${s(lang)}, I couldn't move it."))
        }
        return SkillResult(lang.t("\"${ev.title}\" dipindah ke ${Fmt.at(start, lang, now)}${s(lang)}.", "\"${ev.title}\" moved to ${Fmt.at(start, lang, now)}${s(lang)}."))
    }

    private fun shiftLastEvent(cmd: Command.ShiftLastEvent, lang: AppLang, now: LocalDateTime, denied: Set<String>): SkillResult {
        val (err, ev) = lastEvent(lang, denied)
        if (err != null || ev == null) return err!!
        val start = ev.start.plusSeconds(cmd.seconds.toLong())
        if (!calendar.move(ev.id, start, null)) {
            return SkillResult(lang.t("Maaf${s(lang)}, acara gagal digeser.", "Sorry${s(lang)}, I couldn't reschedule it."))
        }
        return SkillResult(lang.t("\"${ev.title}\" sekarang ${Fmt.at(start, lang, now)}${s(lang)}.", "\"${ev.title}\" is now ${Fmt.at(start, lang, now)}${s(lang)}."))
    }

    private fun deleteLastEvent(lang: AppLang, confirmed: Boolean, denied: Set<String>): SkillResult {
        val (err, ev) = lastEvent(lang, denied)
        if (err != null || ev == null) return err!!
        if (!confirmed) {
            return SkillResult(
                lang.t("Hapus acara \"${ev.title}\"? Katakan ya atau tidak.", "Delete \"${ev.title}\"? Say yes or no."),
                pending = Pending.Confirm(Command.DeleteLastEvent)
            )
        }
        if (!calendar.delete(ev.id)) return SkillResult(lang.t("Maaf${s(lang)}, acara gagal dihapus.", "Sorry${s(lang)}, I couldn't delete it."))
        prefs.lastEventId = -1
        return SkillResult(lang.t("Acara \"${ev.title}\" sudah dihapus${s(lang)}.", "\"${ev.title}\" deleted${s(lang)}."))
    }

    private fun whenText(start: LocalDateTime, allDay: Boolean, lang: AppLang, now: LocalDateTime): String =
        if (allDay) dayPhrase(start.toLocalDate(), lang, now) else Fmt.at(start, lang, now)

    private fun deleteEvent(cmd: Command.DeleteEvent, lang: AppLang, confirmed: Boolean, denied: Set<String>, now: LocalDateTime): SkillResult {
        calendarPermissionResult(lang, denied)?.let { return it }
        val found = try {
            calendar.findEvents(cmd.query, cmd.date, cmd.hour, cmd.minute, cmd.ambiguous, now)
        } catch (e: Exception) {
            emptyList()
        }
        val what = cmd.query.ifBlank { lang.t("acara", "event") }
        val wherePhrase = cmd.date?.let { " " + dayPhrase(it, lang, now) } ?: ""
        if (found.isEmpty()) {
            return SkillResult(
                lang.t("Saya tidak menemukan \"$what\"$wherePhrase di kalender${s(lang)}.", "I couldn't find \"$what\"$wherePhrase in your calendar${s(lang)}.")
            )
        }
        val writable = found.filter { it.writable }
        if (writable.isEmpty()) {
            val f = found.first()
            return SkillResult(
                lang.t(
                    "\"${f.title}\" ada di kalender ${f.calendarName} yang hanya bisa dibaca, jadi tidak bisa saya hapus${s(lang)}.",
                    "\"${f.title}\" is in the read-only ${f.calendarName} calendar, so I can't delete it${s(lang)}."
                )
            )
        }
        // Lebih dari satu yang cocok (mis. judul kembar): selalu tanya, urutkan yang paling cocok di atas.
        val best = writable.first()
        if (writable.size > 1) {
            val options = writable.take(5).map { f ->
                Choice(
                    "${f.title} — ${whenText(f.start, f.allDay, lang, now)}",
                    Command.DeleteEventInstance(f.eventId, f.title, f.start, f.beginMillis, f.allDay, f.recurring)
                )
            }
            val lines = options.mapIndexed { i, c -> "${i + 1}. ${c.label}" }.joinToString("\n")
            val all = Command.DeleteEvents(options.map { it.command as Command.DeleteEventInstance })
            return SkillResult(
                lang.t(
                    "Ada ${options.size} acara yang cocok${s(lang)}. Yang mana yang dihapus? Sebut nomornya, atau \"semua\".",
                    "${options.size} events match${s(lang)}. Which one should I delete? Say the number, or \"all\"."
                ) + "\n" + lines,
                pending = Pending.Choose(options, all = all)
            )
        }
        return deleteEventInstance(
            Command.DeleteEventInstance(best.eventId, best.title, best.start, best.beginMillis, best.allDay, best.recurring),
            lang, confirmed, denied, now
        )
    }

    /** Hapus beberapa acara sekaligus. Dipanggil setelah pengguna menjawab "semua"/"keduanya". */
    private fun deleteEvents(cmd: Command.DeleteEvents, lang: AppLang, confirmed: Boolean, denied: Set<String>, now: LocalDateTime): SkillResult {
        calendarPermissionResult(lang, denied)?.let { return it }
        if (cmd.items.isEmpty()) return SkillResult(lang.t("Tidak ada acara yang dihapus${s(lang)}.", "Nothing to delete${s(lang)}."))
        if (!confirmed) {
            return SkillResult(
                lang.t("Hapus ${cmd.items.size} acara itu? Katakan ya atau tidak.", "Delete those ${cmd.items.size} events? Say yes or no."),
                pending = Pending.Confirm(cmd)
            )
        }
        val done = ArrayList<String>()
        val failed = ArrayList<Command.DeleteEventInstance>()
        for (e in cmd.items) {
            val ok = if (e.recurring) {
                calendar.cancelInstance(e.eventId, e.beginMillis) && !calendar.instanceExists(e.eventId, e.beginMillis)
            } else {
                calendar.delete(e.eventId) && !calendar.exists(e.eventId)
            }
            if (ok) {
                done += e.title
                if (prefs.lastEventId == e.eventId) prefs.lastEventId = -1
            } else {
                failed += e
            }
        }
        val names = done.joinToString(", ") { "\"$it\"" }
        if (failed.isEmpty()) {
            return SkillResult(lang.t("${done.size} acara dihapus${s(lang)}: $names.", "Deleted ${done.size} events${s(lang)}: $names."))
        }
        val f = failed.first()
        val head = if (done.isEmpty()) "" else lang.t("Dihapus: $names. ", "Deleted: $names. ")
        return SkillResult(
            head + lang.t(
                "\"${f.title}\" ditolak kalender, jadi saya buka acaranya. Hapus dari sana${s(lang)}.",
                "The calendar refused to delete \"${f.title}\", so I'm opening it. Delete it from there${s(lang)}."
            ),
            intent = calendar.viewIntent(f.eventId)
        )
    }

    private fun deleteEventInstance(
        cmd: Command.DeleteEventInstance,
        lang: AppLang,
        confirmed: Boolean,
        denied: Set<String>,
        now: LocalDateTime
    ): SkillResult {
        calendarPermissionResult(lang, denied)?.let { return it }
        val whenT = whenText(cmd.begin, cmd.allDay, lang, now)
        if (!confirmed) {
            val rec = if (cmd.recurring) {
                lang.t(" Ini acara berulang; hanya kejadian itu yang saya hapus.", " It's a recurring event; I'll remove only that occurrence.")
            } else ""
            return SkillResult(
                lang.t("Hapus \"${cmd.title}\" $whenT?$rec Katakan ya atau tidak.", "Delete \"${cmd.title}\" $whenT?$rec Say yes or no."),
                pending = Pending.Confirm(cmd)
            )
        }
        val ok = if (cmd.recurring) {
            calendar.cancelInstance(cmd.eventId, cmd.beginMillis) && !calendar.instanceExists(cmd.eventId, cmd.beginMillis)
        } else {
            calendar.delete(cmd.eventId) && !calendar.exists(cmd.eventId)
        }
        if (!ok) {
            return SkillResult(
                lang.t(
                    "Kalender menolak menghapus \"${cmd.title}\" secara otomatis, jadi saya buka acaranya. Hapus dari sana${s(lang)}.",
                    "The calendar wouldn't let me delete \"${cmd.title}\" automatically, so I'm opening it. Delete it from there${s(lang)}."
                ),
                intent = calendar.viewIntent(cmd.eventId)
            )
        }
        if (prefs.lastEventId == cmd.eventId) prefs.lastEventId = -1
        return SkillResult(
            if (cmd.recurring) {
                lang.t(
                    "\"${cmd.title}\" $whenT sudah dihapus; jadwal berulang lainnya tetap${s(lang)}.",
                    "\"${cmd.title}\" $whenT is deleted; the rest of the series stays${s(lang)}."
                )
            } else {
                lang.t("\"${cmd.title}\" $whenT sudah dihapus dari kalender${s(lang)}.", "\"${cmd.title}\" $whenT has been deleted from your calendar${s(lang)}.")
            }
        )
    }

    private fun agenda(cmd: Command.ShowAgenda, lang: AppLang, now: LocalDateTime, denied: Set<String>): SkillResult {
        calendarPermissionResult(lang, denied)?.let { return it }
        val day = dayPhrase(cmd.date, lang, now)
        val events = try {
            calendar.eventsOn(cmd.date)
        } catch (e: Exception) {
            emptyList()
        }
        if (events.isEmpty()) {
            return SkillResult(lang.t("Kalender Anda kosong $day${s(lang)}.", "Your calendar is clear $day${s(lang)}."))
        }
        val lines = events.take(8).joinToString("\n") { e ->
            val t = if (e.allDay) lang.t("Seharian", "All day") else Fmt.clock(e.start.hour, e.start.minute, lang).replaceFirstChar { it.uppercase() }
            "• $t — ${e.title}"
        }
        val head = lang.t(
            "${day.replaceFirstChar { it.uppercase() }} ada ${events.size} acara${s(lang)}:",
            "${day.replaceFirstChar { it.uppercase() }} you have ${events.size} ${if (events.size == 1) "event" else "events"}${s(lang)}:"
        )
        return SkillResult("$head\n$lines")
    }

    // ------------------------------------------------------------ daftar tugas

    private fun todoList(lang: AppLang): SkillResult {
        val list = todos.today()
        if (list.isEmpty()) {
            return SkillResult(
                lang.t(
                    "Daftar tugas hari ini masih kosong${s(lang)}. Katakan misalnya \"tambah tugas beli susu\".",
                    "Today's list is empty${s(lang)}. Try \"add buy milk to my to-do list\"."
                )
            )
        }
        val open = list.count { !it.done }
        val lines = list.mapIndexed { i, it -> "${i + 1}. ${if (it.done) "✓ " else ""}${it.text}" }.joinToString("\n")
        val head = if (open == 0) lang.t("Semua tugas hari ini sudah selesai${s(lang)}:", "Everything is done for today${s(lang)}:")
        else lang.t("Ada $open tugas belum selesai${s(lang)}:", "You have $open open ${if (open == 1) "task" else "tasks"}${s(lang)}:")
        return SkillResult("$head\n$lines")
    }

    // ------------------------------------------------------------ musik, telepon, nyanyi, ekspresi, tuner, pengaturan Nine

    private fun mediaControl(cmd: Command.MediaControl, lang: AppLang): SkillResult {
        return try {
            val am = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            if (cmd.action == MediaAction.PAUSE && !am.isMusicActive) {
                return SkillResult(lang.t("Tidak ada musik yang sedang diputar${s(lang)}.", "Nothing is playing right now${s(lang)}."))
            }
            val code = when (cmd.action) {
                MediaAction.PLAY -> KeyEvent.KEYCODE_MEDIA_PLAY
                MediaAction.PAUSE -> KeyEvent.KEYCODE_MEDIA_PAUSE
                MediaAction.NEXT -> KeyEvent.KEYCODE_MEDIA_NEXT
                MediaAction.PREVIOUS -> KeyEvent.KEYCODE_MEDIA_PREVIOUS
            }
            val t = SystemClock.uptimeMillis()
            am.dispatchMediaKeyEvent(KeyEvent(t, t, KeyEvent.ACTION_DOWN, code, 0))
            am.dispatchMediaKeyEvent(KeyEvent(t, SystemClock.uptimeMillis(), KeyEvent.ACTION_UP, code, 0))
            SkillResult(
                when (cmd.action) {
                    MediaAction.PLAY -> lang.t("Melanjutkan musik${s(lang)}.", "Resuming the music${s(lang)}.")
                    MediaAction.PAUSE -> lang.t("Musik dijeda${s(lang)}.", "Music paused${s(lang)}.")
                    MediaAction.NEXT -> lang.t("Lagu berikutnya${s(lang)}.", "Next track${s(lang)}.")
                    MediaAction.PREVIOUS -> lang.t("Lagu sebelumnya${s(lang)}.", "Previous track${s(lang)}.")
                }
            )
        } catch (e: Exception) {
            SkillResult(lang.t("Maaf${s(lang)}, pemutar musik tidak bisa saya kendalikan sekarang.", "Sorry${s(lang)}, I can't control the music player right now."))
        }
    }

    private fun callControl(cmd: Command.CallControl, lang: AppLang, denied: Set<String>): SkillResult {
        val need = CallControl.requiredPermissions().filter { !has(it) }
        if (need.isNotEmpty()) {
            return if (need.any { it in denied }) {
                SkillResult(
                    lang.t(
                        "Saya butuh izin Telepon untuk menjawab atau menolak panggilan. Berikan di Pengaturan > Aplikasi > Nine > Izin.",
                        "I need the Phone permission to answer or decline calls. Grant it in Settings > Apps > Nine > Permissions."
                    )
                )
            } else {
                SkillResult(
                    lang.t("Saya perlu izin Telepon untuk menjawab atau menolak panggilan${s(lang)}.", "I need the Phone permission to answer or decline calls${s(lang)}."),
                    needPermissions = need
                )
            }
        }
        val r = if (cmd.accept) CallControl.accept(ctx) else CallControl.decline(ctx)
        return SkillResult(
            when (r) {
                CallControl.Result.DONE ->
                    if (cmd.accept) lang.t("Telepon saya angkat${s(lang)}.", "Answering the call${s(lang)}.")
                    else lang.t("Telepon saya tolak${s(lang)}.", "Call declined${s(lang)}.")
                CallControl.Result.NO_CALL -> lang.t("Tidak ada telepon yang sedang masuk${s(lang)}.", "There's no incoming call${s(lang)}.")
                CallControl.Result.UNSUPPORTED -> lang.t(
                    "Versi Android ini tidak mengizinkan saya menolak telepon. Tolak lewat layar panggilan ya${s(lang)}.",
                    "This Android version doesn't let me decline calls. Please use the call screen${s(lang)}."
                )
                CallControl.Result.FAILED -> lang.t("Maaf${s(lang)}, saya gagal mengurus telepon itu.", "Sorry${s(lang)}, I couldn't handle that call.")
            }
        )
    }

    private fun sing(cmd: Command.Sing, lang: AppLang): SkillResult {
        val song = Songs.find(cmd.song)
        val title = song?.let { if (lang == AppLang.ID) it.titleId else it.titleEn }
        val text = if (song?.id == "bardroid") {
            lang.t(
                "Baik${s(lang)}. Melodi asli Cantina Band berhak cipta, jadi saya nyanyikan lagu bergaya bar droid karangan saya sendiri. Hanya nada, tanpa lirik.",
                "Very well${s(lang)}. The original Cantina Band melody is copyrighted, so here's a bar-droid style tune of my own. Notes only, no lyrics."
            )
        } else if (title != null) {
            lang.t(
                "Baik${s(lang)}, saya nyanyikan \"$title\" dengan suara saya sendiri. Hanya melodinya, tanpa lirik.",
                "Very well${s(lang)}. Here is \"$title\" in my own voice, melody only, no lyrics."
            )
        } else {
            lang.t(
                "Saya belum hafal lagu itu${s(lang)}, jadi saya nyanyikan melodi karangan sendiri. Hanya nada, tanpa lirik.",
                "I don't know that one yet${s(lang)}, so here's a tune of my own. Melody only, no lyrics."
            )
        }
        return SkillResult(text, action = UiAction.Sing(cmd.song), mood = Mood.HAPPY)
    }

    private fun moodName(m: Mood, lang: AppLang): String = when (m) {
        Mood.HAPPY -> lang.t("senang", "happy")
        Mood.LOVE -> lang.t("jatuh cinta", "loving")
        Mood.SURPRISED -> lang.t("terkejut", "surprised")
        Mood.CONFUSED -> lang.t("bingung", "confused")
        Mood.SAD -> lang.t("sedih", "sad")
        Mood.ANNOYED -> lang.t("kesal", "annoyed")
        Mood.SLEEPY -> lang.t("mengantuk", "sleepy")
        Mood.CURIOUS -> lang.t("penasaran", "curious")
        Mood.SCARED -> lang.t("takut", "scared")
        Mood.WINK -> lang.t("mengedip", "winking")
        Mood.LAUGH -> lang.t("tertawa", "laughing")
        Mood.NEUTRAL -> lang.t("biasa", "neutral")
    }

    private fun emote(cmd: Command.Emote, lang: AppLang): SkillResult =
        SkillResult(
            lang.t("Ini wajah ${moodName(cmd.mood, lang)}${s(lang)}.", "Here's my ${moodName(cmd.mood, lang)} face${s(lang)}."),
            mood = cmd.mood
        )

    private fun tuner(cmd: Command.Tuner, lang: AppLang, denied: Set<String>): SkillResult {
        if (!cmd.start) {
            return SkillResult(lang.t("Tuner gitar ditutup${s(lang)}.", "Guitar tuner closed${s(lang)}."), action = UiAction.Tuner(false, null))
        }
        if (!has(Manifest.permission.RECORD_AUDIO)) {
            return if (Manifest.permission.RECORD_AUDIO in denied) {
                SkillResult(lang.t("Tuner butuh izin mikrofon untuk mendengar senar.", "The tuner needs microphone access to hear the strings."))
            } else {
                SkillResult(
                    lang.t("Saya perlu izin mikrofon untuk mendengar senarnya${s(lang)}.", "I need microphone access to hear the strings${s(lang)}."),
                    needPermissions = listOf(Manifest.permission.RECORD_AUDIO)
                )
            }
        }
        val gs = cmd.string?.let { GuitarTuning.byNumber(it) }
        val tone = if (cmd.tone && gs != null) gs.frequency else null
        val text = when {
            gs != null && tone != null -> lang.t(
                "Ini nada ${GuitarTuning.label(gs, lang)}${s(lang)}. Setelah itu petik senarnya, saya akan beri tahu kencang atau kendur.",
                "That's the note for ${GuitarTuning.label(gs, lang)}${s(lang)}. Then pluck the string and I'll tell you whether to tighten or loosen it."
            )
            gs != null -> lang.t(
                "Tuner ${GuitarTuning.label(gs, lang)} aktif${s(lang)}. Petik senarnya, saya akan beri tahu kencang atau kendur.",
                "Tuner for ${GuitarTuning.label(gs, lang)} is on${s(lang)}. Pluck it and I'll tell you whether to tighten or loosen it."
            )
            else -> lang.t(
                "Tuner gitar aktif${s(lang)}. Petik satu senar mana saja; saya mengenalinya sendiri dan memberi tahu kencang atau kendur. Ketuk senar di layar untuk memilih manual.",
                "Guitar tuner on${s(lang)}. Pluck any string; I'll recognise it and tell you whether to tighten or loosen it. Tap a string on screen to choose it manually."
            )
        }
        return SkillResult(text, action = UiAction.Tuner(true, cmd.string, tone), mood = Mood.CURIOUS)
    }

    private fun appSetting(cmd: Command.AppSetting, lang: AppLang, denied: Set<String> = emptySet()): SkillResult {
        fun theme(t: Prefs.Theme, id: String, en: String): SkillResult {
            prefs.theme = t
            return SkillResult(
                lang.t("Baik${s(lang)}, tema $id.", "Very well${s(lang)}, $en theme."),
                action = UiAction.SetTheme(t)
            )
        }
        return when (cmd.setting) {
            NineSetting.THEME_LIGHT -> theme(Prefs.Theme.LIGHT, "terang", "light")
            NineSetting.THEME_DARK -> theme(Prefs.Theme.DARK, "gelap", "dark")
            NineSetting.THEME_SYSTEM -> theme(Prefs.Theme.SYSTEM, "mengikuti sistem", "system")
            NineSetting.EXIT_APP -> SkillResult(
                lang.t("Baik${s(lang)}, sampai jumpa.", "Very well${s(lang)}, goodbye."),
                action = UiAction.ExitApp
            )
            NineSetting.WAKE_ON -> {
                val need = ArrayList<String>()
                if (!has(Manifest.permission.RECORD_AUDIO)) need += Manifest.permission.RECORD_AUDIO
                if (Build.VERSION.SDK_INT >= 33 && !has(Manifest.permission.POST_NOTIFICATIONS) && Manifest.permission.POST_NOTIFICATIONS !in denied) {
                    need += Manifest.permission.POST_NOTIFICATIONS
                }
                if (need.isNotEmpty()) {
                    if (Manifest.permission.RECORD_AUDIO in denied) {
                        SkillResult(lang.t("Wake phrase butuh izin mikrofon.", "The wake phrase needs microphone access."))
                    } else {
                        SkillResult(lang.t("Saya perlu izin mikrofon untuk mendengarkan wake phrase${s(lang)}.", "I need microphone access to listen for the wake phrase${s(lang)}."), needPermissions = need)
                    }
                } else {
                    prefs.wakeEnabled = true
                    val phrase = WakeWordService.parsePhrases(prefs.wakePhrases).firstOrNull() ?: "hey nine"
                    SkillResult(
                        lang.t("Wake phrase \"$phrase\" saya aktifkan${s(lang)}.", "Wake phrase \"$phrase\" is on${s(lang)}."),
                        action = UiAction.WakeChanged(true)
                    )
                }
            }
            NineSetting.WAKE_OFF -> {
                prefs.wakeEnabled = false
                SkillResult(lang.t("Wake phrase saya matikan${s(lang)}.", "Wake phrase turned off${s(lang)}."), action = UiAction.WakeChanged(false))
            }
            NineSetting.MUSIC_REACT_ON -> {
                if (!has(Manifest.permission.RECORD_AUDIO)) {
                    if (Manifest.permission.RECORD_AUDIO in denied) {
                        SkillResult(lang.t("Mendengarkan musik butuh izin mikrofon.", "Listening to music needs microphone access."))
                    } else {
                        SkillResult(lang.t("Saya perlu izin mikrofon untuk mendengar musik${s(lang)}.", "I need microphone access to hear music${s(lang)}."), needPermissions = listOf(Manifest.permission.RECORD_AUDIO))
                    }
                } else {
                    prefs.musicReact = true
                    SkillResult(
                        lang.t("Saya akan ikut bergoyang saat mendengar musik${s(lang)}. Wajah saya bereaksi pada ketukan dan nadanya selama layar Nine terbuka.", "I'll groove along when I hear music${s(lang)}. My face reacts to the beat and tone while the Nine screen is open."),
                        action = UiAction.MusicReactChanged,
                        mood = Mood.HAPPY
                    )
                }
            }
            NineSetting.MUSIC_REACT_OFF -> {
                prefs.musicReact = false
                SkillResult(lang.t("Reaksi musik saya matikan${s(lang)}.", "Music reaction off${s(lang)}."), action = UiAction.MusicReactChanged)
            }
        }
    }

    // ------------------------------------------------------------ aplikasi eksternal

    private fun installed(pkg: String): Boolean = try {
        ctx.packageManager.getPackageInfo(pkg, 0)
        true
    } catch (e: Exception) {
        false
    }

    private fun playMedia(cmd: Command.PlayMedia, lang: AppLang): SkillResult {
        val q = cmd.query.trim()
        val enc = Uri.encode(q)
        return when (cmd.app) {
            "youtube" -> SkillResult(
                lang.t("Mencari \"$q\" di YouTube${s(lang)}.", "Searching YouTube for \"$q\"${s(lang)}."),
                intent = Intent(Intent.ACTION_SEARCH).setPackage("com.google.android.youtube").putExtra("query", q),
                fallback = Intent(Intent.ACTION_VIEW, Uri.parse("https://www.youtube.com/results?search_query=$enc"))
            )
            // Tanpa aplikasi disebut: musik selalu lewat YouTube Music (bukan Spotify atau pemutar lain).
            "youtube music", null -> {
                val ytm = "com.google.android.apps.youtube.music"
                if (q.isEmpty()) {
                    val open = ctx.packageManager.getLaunchIntentForPackage(ytm)
                    SkillResult(
                        lang.t("Membuka YouTube Music${s(lang)}.", "Opening YouTube Music${s(lang)}."),
                        intent = open ?: Intent(Intent.ACTION_VIEW, Uri.parse("https://music.youtube.com")),
                        fallback = Intent(Intent.ACTION_VIEW, Uri.parse("https://music.youtube.com"))
                    )
                } else {
                    val svc = NineAccessibilityService.instance
                    val search = Uri.parse("https://music.youtube.com/search?q=$enc")
                    val hasApp = installed(ytm)
                    val primary = Intent(Intent.ACTION_VIEW, search).apply { if (hasApp) setPackage(ytm) }
                    val second = if (hasApp) {
                        Intent(MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH)
                            .setPackage(ytm)
                            .putExtra(SearchManager.QUERY, q)
                            .putExtra(MediaStore.EXTRA_MEDIA_FOCUS, "vnd.android.cursor.item/*")
                    } else null
                    if (svc != null && hasApp) {
                        SkillResult(
                            lang.t(
                                "Mencari \"$q\" di YouTube Music, lalu saya putar hasil pertamanya${s(lang)} (beta).",
                                "Searching YouTube Music for \"$q\" and playing the first result${s(lang)} (beta)."
                            ),
                            intent = primary,
                            fallback = second,
                            afterLaunch = { svc.playFirstSong(q) }
                        )
                    } else {
                        SkillResult(
                            lang.t(
                                if (NineAccessibilityService.AVAILABLE) "Saya buka hasil pencarian \"$q\" di YouTube Music${s(lang)}. Ketuk lagunya, atau aktifkan layanan aksesibilitas Nine agar saya bisa memilih dan memutarnya sendiri."
                                else "Saya buka hasil pencarian \"$q\" di YouTube Music${s(lang)}. Ketuk lagunya; Nine edisi Full (Nine-Full.apk) bisa memilih dan memutarnya otomatis.",
                                if (NineAccessibilityService.AVAILABLE) "I've opened the search results for \"$q\" in YouTube Music${s(lang)}. Tap the song, or enable the Nine accessibility service so I can pick and play it myself."
                                else "I've opened the search results for \"$q\" in YouTube Music${s(lang)}. Tap the song; the Full edition (Nine-Full.apk) can pick and play it automatically."
                            ),
                            intent = primary,
                            fallback = second
                        )
                    }
                }
            }
            "spotify" -> SkillResult(
                lang.t("Memutar \"$q\" di Spotify${s(lang)}.", "Playing \"$q\" on Spotify${s(lang)}."),
                intent = Intent(MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH)
                    .setPackage("com.spotify.music")
                    .putExtra(SearchManager.QUERY, q)
                    .putExtra(MediaStore.EXTRA_MEDIA_FOCUS, "vnd.android.cursor.item/*"),
                fallback = Intent(Intent.ACTION_VIEW, Uri.parse("spotify:search:$enc"))
            )
            else -> openApp(cmd.app ?: "", lang)
        }
    }

    private fun navigate(cmd: Command.Navigate, lang: AppLang): SkillResult {
        val dest = cmd.destination.trim()
        val enc = Uri.encode(dest)
        val maps = "com.google.android.apps.maps"
        val id = lang == AppLang.ID
        val modeName = when (cmd.mode) {
            "walking" -> lang.t("jalan kaki", "on foot")
            "bicycling" -> lang.t("sepeda", "by bike")
            "two_wheeler" -> lang.t("motor", "by motorbike")
            "transit" -> lang.t("angkutan umum", "by public transport")
            else -> null
        }
        val extras = ArrayList<String>()
        if (modeName != null) extras += modeName
        if (cmd.avoidTolls) extras += lang.t("hindari tol", "avoiding tolls")
        cmd.origin?.let { extras += lang.t("dari $it", "from $it") }
        val detail = if (extras.isEmpty()) "" else " (" + extras.joinToString(", ") + ")"

        // Rute dengan titik awal, pratinjau rute, atau angkutan umum: tautan "dir" Google Maps.
        if (cmd.origin != null || cmd.preview || cmd.mode == "transit") {
            val travel = when (cmd.mode) {
                "walking" -> "walking"
                "bicycling" -> "bicycling"
                "transit" -> "transit"
                "two_wheeler" -> "two-wheeler"
                else -> "driving"
            }
            val sb = StringBuilder("https://www.google.com/maps/dir/?api=1&destination=").append(enc)
            cmd.origin?.let { sb.append("&origin=").append(Uri.encode(it)) }
            sb.append("&travelmode=").append(travel)
            if (cmd.drive && !cmd.preview && cmd.mode != "transit") sb.append("&dir_action=navigate")
            if (cmd.avoidTolls) sb.append("&avoid=tolls")
            val uri = Uri.parse(sb.toString())
            return SkillResult(
                if (cmd.preview || cmd.mode == "transit") {
                    lang.t("Menampilkan rute ke $dest di Google Maps$detail${s(lang)}.", "Showing the route to $dest in Google Maps$detail${s(lang)}.")
                } else {
                    lang.t("Menyetel Google Maps ke $dest$detail${s(lang)}.", "Setting Google Maps to $dest$detail${s(lang)}.")
                },
                intent = Intent(Intent.ACTION_VIEW, uri).setPackage(maps),
                fallback = Intent(Intent.ACTION_VIEW, uri)
            )
        }
        return if (cmd.drive) {
            val code = when (cmd.mode) {
                "walking" -> "w"
                "bicycling" -> "b"
                "two_wheeler" -> "l"
                else -> "d"
            }
            val avoid = if (cmd.avoidTolls) "&avoid=t" else ""
            SkillResult(
                if (id) "Menyetel navigasi Google Maps ke $dest$detail${s(lang)}. Hati-hati di jalan."
                else "Setting Google Maps navigation to $dest$detail${s(lang)}. Drive safely.",
                intent = Intent(Intent.ACTION_VIEW, Uri.parse("google.navigation:q=$enc&mode=$code$avoid")).setPackage(maps),
                fallback = Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0?q=$enc"))
            )
        } else {
            SkillResult(
                lang.t("Mencari $dest di peta${s(lang)}.", "Looking up $dest on the map${s(lang)}."),
                intent = Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0?q=$enc")),
                fallback = Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com/maps/search/?api=1&query=$enc"))
            )
        }
    }

    // ------------------------------------------------------------ kalkulator, keuangan, tutup app, panggilan

    private fun calculate(cmd: Command.Calculate, lang: AppLang): SkillResult {
        val r = Calc.solve(cmd.text, lang)
            ?: return SkillResult(
                lang.t(
                    "Maaf${s(lang)}, saya belum bisa menghitung itu. Coba tulis dengan angka, misalnya \"berapa 15% dari 240\" atau \"(3+4)*2^3\".",
                    "My apologies${s(lang)}, I couldn't work that out. Try it with numbers, like \"what is 15% of 240\" or \"(3+4)*2^3\"."
                )
            )
        if (r.error) return SkillResult(r.answer)
        val glue = if (r.answer.firstOrNull()?.let { it.isDigit() || it == '-' } == true) " = " else ": "
        return SkillResult(r.expression + glue + r.answer, mood = Mood.HAPPY)
    }

    private fun financeAdd(cmd: Command.FinanceAdd, lang: AppLang): SkillResult {
        if (cmd.amount <= 0L) {
            return SkillResult(lang.t("Nominalnya belum jelas${s(lang)}. Sebutkan jumlahnya, misalnya 25 ribu.", "I didn't catch the amount${s(lang)}. Say it like 25 thousand."))
        }
        val t = finance.add(cmd.type, cmd.amount, cmd.category, cmd.note)
        val (from, to) = FinanceMath.range(FinPeriod.TODAY, LocalDate.now())
        val today = FinanceMath.summarize(finance.all(), from, to)
        val income = t.type == FinType.INCOME
        val kind = if (income) lang.t("pemasukan", "income") else lang.t("pengeluaran", "expense")
        val cat = FinanceCats.name(t.cat, lang)
        val note = if (t.note.isBlank()) "" else ", ${t.note}"
        val total = if (income) today.income else today.expense
        return SkillResult(
            lang.t(
                "Dicatat: $kind ${FinanceMath.money(t.amount, lang)} ($cat$note). Total $kind hari ini ${FinanceMath.money(total, lang)}${s(lang)}.",
                "Logged: $kind ${FinanceMath.money(t.amount, lang)} ($cat$note). Today's total $kind is ${FinanceMath.money(total, lang)}${s(lang)}."
            )
        )
    }

    private fun financeReport(cmd: Command.FinanceReport, lang: AppLang): SkillResult {
        val (from, to) = FinanceMath.range(cmd.period, LocalDate.now())
        val sum = FinanceMath.summarize(finance.all(), from, to)
        val label = FinanceMath.label(cmd.period, lang)
        if (sum.count == 0) {
            return SkillResult(lang.t("Belum ada catatan keuangan $label${s(lang)}.", "There are no money entries for $label yet${s(lang)}."))
        }
        val money = { v: Long -> FinanceMath.money(v, lang) }
        val top = sum.expenseByCat.firstOrNull()?.let {
            lang.t(" Pengeluaran terbesar: ${FinanceCats.name(it.first, lang)} ${money(it.second)}.", " Biggest spending: ${FinanceCats.name(it.first, lang)} ${money(it.second)}.")
        } ?: ""
        val text = when (cmd.type) {
            FinType.EXPENSE -> lang.t(
                "Pengeluaran $label: ${money(sum.expense)} dari ${sum.count} catatan.$top",
                "Spending $label: ${money(sum.expense)} across ${sum.count} entries.$top"
            )
            FinType.INCOME -> lang.t(
                "Pemasukan $label: ${money(sum.income)}.",
                "Income $label: ${money(sum.income)}."
            )
            null -> lang.t(
                "Ringkasan $label: pemasukan ${money(sum.income)}, pengeluaran ${money(sum.expense)}, saldo ${money(sum.balance)}.$top",
                "Summary $label: income ${money(sum.income)}, spending ${money(sum.expense)}, balance ${money(sum.balance)}.$top"
            )
        }
        return SkillResult(text)
    }

    private fun financeList(cmd: Command.FinanceList, lang: AppLang): SkillResult {
        val (from, to) = FinanceMath.range(cmd.period, LocalDate.now())
        val label = FinanceMath.label(cmd.period, lang)
        val rows = finance.all()
            .filter { it.time >= from && it.time < to && (cmd.type == null || it.type == cmd.type) }
            .sortedByDescending { it.time }
        if (rows.isEmpty()) {
            return SkillResult(lang.t("Belum ada catatan $label${s(lang)}.", "No entries for $label yet${s(lang)}."))
        }
        val shown = rows.take(8)
        val lines = shown.mapIndexed { i, t ->
            val sign = if (t.type == FinType.INCOME) "+" else "-"
            val what = t.note.ifBlank { FinanceCats.name(t.cat, lang) }
            "${i + 1}. $sign${FinanceMath.money(t.amount, lang)} · $what"
        }.joinToString("\n")
        val more = if (rows.size > shown.size) lang.t("\nDan ${rows.size - shown.size} lainnya; lihat semuanya di layar Keuangan.", "\nAnd ${rows.size - shown.size} more; see them all on the Money screen.") else ""
        return SkillResult(lang.t("Catatan $label${s(lang)}:", "Entries for $label${s(lang)}:") + "\n" + lines + more)
    }

    private fun financeUndo(lang: AppLang): SkillResult {
        val t = finance.undoLast()
            ?: return SkillResult(lang.t("Belum ada catatan yang bisa dibatalkan${s(lang)}.", "There's no entry to undo yet${s(lang)}."))
        val kind = if (t.type == FinType.INCOME) lang.t("pemasukan", "income") else lang.t("pengeluaran", "expense")
        val note = if (t.note.isBlank()) "" else " (${t.note})"
        return SkillResult(
            lang.t(
                "Catatan terakhir saya hapus: $kind ${FinanceMath.money(t.amount, lang)}$note${s(lang)}.",
                "Removed the last entry: $kind ${FinanceMath.money(t.amount, lang)}$note${s(lang)}."
            )
        )
    }

    private fun financeClear(lang: AppLang, confirmed: Boolean): SkillResult {
        val n = finance.all().size
        if (n == 0) return SkillResult(lang.t("Catatan keuangan sudah kosong${s(lang)}.", "Your money log is already empty${s(lang)}."))
        if (!confirmed) {
            return SkillResult(
                lang.t("Hapus SEMUA $n catatan keuangan? Ini tidak bisa dibatalkan. Katakan ya atau tidak.", "Delete ALL $n money entries? This can't be undone. Say yes or no."),
                pending = Pending.Confirm(Command.FinanceClear)
            )
        }
        finance.clear()
        return SkillResult(lang.t("Semua catatan keuangan dihapus${s(lang)}.", "All money entries deleted${s(lang)}."))
    }

    /** Paket yang tidak boleh disentuh saat "tutup semua aplikasi": peluncur Home, telepon, dan Nine sendiri. */
    private fun protectedPackages(): Set<String> {
        val out = HashSet<String>()
        out.add(ctx.packageName)
        out.addAll(listOf("com.android.phone", "com.android.server.telecom", "com.google.android.dialer", "com.android.systemui"))
        try {
            @Suppress("DEPRECATION")
            val homes = ctx.packageManager.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), 0)
            for (ri in homes) out.add(ri.activityInfo.packageName)
        } catch (e: Exception) {
            // abaikan
        }
        return out
    }

    private fun closeApps(cmd: Command.CloseApps, lang: AppLang): SkillResult {
        // Langkah 1 (semua edisi): hentikan proses latar aplikasi lain. Android hanya mengizinkan ini untuk proses di latar.
        try {
            val am = ctx.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            val skip = protectedPackages()
            val seen = HashSet<String>()
            for (ri in launcherApps()) {
                val pkg = ri.activityInfo.packageName
                if (pkg in skip || !seen.add(pkg)) continue
                try {
                    am.killBackgroundProcesses(pkg)
                } catch (e: Exception) {
                    // satu aplikasi gagal tidak menghentikan yang lain
                }
            }
        } catch (e: Exception) {
            // abaikan
        }
        val sfx = s(lang)
        val self = cmd.includeSelf
        val exit = if (self) UiAction.ExitApp else null
        val bye = if (self) lang.t(" Sekarang giliran saya. Sampai jumpa.", " Now it's my turn. Goodbye.") else ""

        val svc = NineAccessibilityService.instance
        if (svc != null) {
            // Langkah 2 (Nine-Full + aksesibilitas): buka layar Recents lalu ketuk "Tutup semua".
            return SkillResult(
                lang.t("Menutup semua aplikasi${sfx}.", "Closing all apps${sfx}."),
                deferred = { done ->
                    svc.clearRecents { o ->
                        val text = when (o) {
                            NineAccessibilityService.Outcome.DONE ->
                                lang.t("Semua aplikasi sudah saya tutup${sfx}.$bye", "All apps are closed${sfx}.$bye")
                            NineAccessibilityService.Outcome.NOT_FOUND ->
                                lang.t(
                                    "Proses latar aplikasi lain sudah saya hentikan${sfx}, tetapi tombol \"Tutup semua\" tidak ketemu di layar Recents HP ini, jadi kartunya belum terhapus.$bye",
                                    "I stopped other apps' background processes${sfx}, but I couldn't find a \"Close all\" button on this phone's Recents screen, so the cards remain.$bye"
                                )
                            NineAccessibilityService.Outcome.BUSY ->
                                lang.t("Saya masih mengerjakan tugas layar yang lain${sfx}. Coba lagi sebentar lagi.", "I'm still busy with another screen task${sfx}. Try again in a moment.")
                            else ->
                                lang.t("Proses latar sudah saya hentikan${sfx}, tetapi menutup lewat layar Recents gagal.$bye", "I stopped background processes${sfx}, but closing through the Recents screen failed.$bye")
                        }
                        done(text)
                    }
                },
                action = exit
            )
        }
        val why = if (NineAccessibilityService.AVAILABLE) {
            lang.t(
                " Untuk menutup semuanya lewat layar Recents, nyalakan layanan aksesibilitas Nine di Pengaturan.",
                " To close everything through the Recents screen, turn on Nine's accessibility service in Settings."
            )
        } else {
            lang.t(
                " Android tidak mengizinkan aplikasi biasa menutup aplikasi yang sedang tampil; edisi Nine-Full dengan layanan aksesibilitas bisa menutup semuanya lewat Recents.",
                " Android doesn't let a normal app close apps that are on screen; the Nine-Full edition with the accessibility service can close everything through Recents."
            )
        }
        return SkillResult(
            lang.t("Proses latar aplikasi lain sudah saya hentikan${sfx}.$why$bye", "I stopped other apps' background processes${sfx}.$why$bye"),
            action = exit
        )
    }

    private fun setCallName(raw: String, lang: AppLang): SkillResult {
        val n = Prefs.cleanName(raw)
        if (n.isBlank()) {
            return SkillResult(lang.t("Mau saya panggil apa${s(lang)}?", "What would you like me to call you${s(lang)}?"))
        }
        prefs.honorificId = n
        prefs.honorificEn = n
        return SkillResult(
            lang.t("Baik, mulai sekarang saya panggil Anda $n.", "Very well, I'll call you $n from now on."),
            mood = Mood.HAPPY
        )
    }

    /** Nomor lokal Indonesia ke format internasional untuk WhatsApp (0812… → 62812…). */
    private fun waNumber(raw: String): String {
        var d = raw.filter { it.isDigit() || it == '+' }
        if (d.startsWith("+")) return d.drop(1)
        d = d.filter { it.isDigit() }
        return when {
            d.startsWith("0") -> "62" + d.drop(1)
            d.startsWith("8") -> "62$d"
            else -> d
        }
    }

    private fun whatsApp(cmd: Command.WhatsApp, lang: AppLang, denied: Set<String>): SkillResult {
        var number = asPhoneNumber(cmd.target)
        var display = cmd.target
        if (number == null) {
            contactPermissionResult(lang, denied)?.let { return it }
            when (val r = contacts.find(cmd.target)) {
                is ContactsLookup.Result.Found -> {
                    number = r.number
                    display = r.name
                }
                is ContactsLookup.Result.Ambiguous -> return SkillResult(
                    lang.t(
                        "Ada beberapa kontak: ${r.names.joinToString(", ")}. Sebutkan nama lengkapnya.",
                        "There are several contacts: ${r.names.joinToString(", ")}. Please say the full name."
                    )
                )
                ContactsLookup.Result.NotFound -> return SkillResult(
                    lang.t("Kontak \"${cmd.target}\" tidak ditemukan.", "I couldn't find a contact named \"${cmd.target}\".")
                )
            }
        }
        val num = waNumber(number ?: cmd.target)
        val text = cmd.body?.let { "?text=" + Uri.encode(it) } ?: ""
        val uri = Uri.parse("https://wa.me/$num$text")
        val pkg = listOf("com.whatsapp", "com.whatsapp.w4b").firstOrNull { installed(it) }
        val i = Intent(Intent.ACTION_VIEW, uri)
        if (pkg != null) i.setPackage(pkg)
        val reply = if (cmd.body != null) {
            lang.t("Membuka WhatsApp ke $display dengan pesan siap kirim. Tinggal tekan kirim${s(lang)}.", "Opening WhatsApp to $display with the message ready. Just tap send${s(lang)}.")
        } else {
            lang.t("Membuka chat WhatsApp dengan $display${s(lang)}.", "Opening your WhatsApp chat with $display${s(lang)}.")
        }
        return SkillResult(reply, intent = i, fallback = Intent(Intent.ACTION_VIEW, uri))
    }

    /** Ucapan yang tidak dikenali: bila itu nama aplikasi yang terpasang, buka saja; selain itu teruskan ke otak AI. */
    private fun unknown(text: String, lang: AppLang): SkillResult {
        val words = text.trim().split(Regex("\\s+"))
        if (words.size <= 3) {
            val hit = findApp(text)
            if (hit != null && hit.score >= 95) return launch(hit, lang)
        }
        return SkillResult(persona.didNotUnderstand(lang, text), chat = text)
    }

    private fun help(lang: AppLang): String = lang.t(
        "Beberapa hal yang bisa saya lakukan:\n" +
            "• Ngobrol atau bertanya apa saja (perlu otak AI di Pengaturan)\n" +
            "• \"Pasang alarm jam 6 pagi\"\n" +
            "• \"Timer 10 menit\"\n" +
            "• \"Ingatkan saya minum obat jam 8 malam\"\n" +
            "• \"Tambah acara rapat besok jam 10\" / \"Jadwal saya hari ini\"\n" +
            "• \"Tambah tugas beli susu\" / \"Apa tugas saya hari ini\"\n" +
            "• \"Putar lagu Tulus\" (YouTube Music) / \"Navigasi ke Monas\"\n" +
            "• \"Kirim WA ke Budi isinya aku otw\"\n" +
            "• \"Ambil foto\" / \"Selfie\" / \"Rekam video\" / \"Rekam suara\"\n" +
            "• \"Level kita berapa?\" / \"Di mana saya?\"\n" +
            "• \"Buka WhatsApp\" / \"Buka pengaturan Wi-Fi\"\n" +
            "• \"Nyalakan senter\" / \"Volume 50 persen\"\n" +
            "• \"Berapa sisa baterai?\" / \"Jam berapa sekarang?\"\n" +
            "• \"Sisa penyimpanan berapa?\" / \"Wi-Fi nyala nggak?\" / \"Ke layar utama\"\n" +
            "• \"Catat beli susu\" / \"Baca catatan\"\n" +
            "• \"Ingat bahwa ulang tahun ibu 3 Mei\" / \"Apa yang kamu ingat tentang aku?\" / \"Lupakan ulang tahun ibu\"\n" +
            "• \"Telepon Budi\" / \"Kirim pesan ke Ibu isinya aku pulang telat\"\n" +
            "• \"Hitung 15% dari 2,4 juta\" / \"akar 144 + 3 pangkat 4\"\n" +
            "• \"Catat pengeluaran makan siang 25 ribu\" / \"Gajian 5 juta\" / \"Laporan keuangan bulan ini\"\n" +
            "• \"Navigasi ke Monas naik motor\" (langsung memulai rute di Google Maps)\n" +
            "• \"Tutup semua aplikasi\" / \"Tutup semua termasuk Nine\"\n" +
            "• \"Gombalin dong\" / \"Pantun\" / \"Kata bijak\" / \"Nyanyi lagu bar droid\"\n" +
            "• \"Panggil saya Tuan\" / \"Status affinity\" / tombol + di kolom ketik untuk kirim gambar\n" +
            "• \"Jadi mentor bahasa Inggris saya\" (koreksi grammar & saran kata, offline) / \"Koreksi kalimat saya: i goes to school\" / \"Kata lain dari good\"\n" +
            "• \"Aku mau curhat\" (teman curhat yang mendengarkan; bukan psikolog, tidak disimpan) / \"Latihan napas\"\n" +
            "• Pelatih fitness & ahli gizi (bukan nasihat medis): \"Habis bench press 60 kg 3x8\" / \"Tadi makan nasi goreng dan telur\" / \"Menu hari ini\" / \"Progres bench press\" / \"Berapa protein yang aku butuhkan?\"\n" +
            "• \"Analisis InBody\" lalu kirim fotonya (tombol + di kolom ketik) atau ketik \"inbody berat 70 otot 32 lemak 14\"\n" +
            "• \"Ganti bahasa ke Inggris\" / \"Lupakan percakapan\"",
        "Here are some things I can do:\n" +
            "• Chat or ask me anything (needs the AI brain in Settings)\n" +
            "• \"Set an alarm for 6 AM\"\n" +
            "• \"Set a timer for 10 minutes\"\n" +
            "• \"Remind me to take my pills at 8 PM\"\n" +
            "• \"Schedule lunch with Sarah tomorrow at noon\" / \"What's on my calendar today\"\n" +
            "• \"Add buy milk to my to-do list\" / \"What's on my to-do list\"\n" +
            "• \"Play some jazz\" (YouTube Music) / \"Navigate to the airport\"\n" +
            "• \"Send a WhatsApp to John saying I'm on my way\"\n" +
            "• \"Take a photo\" / \"Take a selfie\" / \"Record a video\" / \"Start recording\"\n" +
            "• \"What's our level?\" / \"Where am I?\"\n" +
            "• \"Open WhatsApp\" / \"Open Wi-Fi settings\"\n" +
            "• \"Turn on the flashlight\" / \"Set volume to 50 percent\"\n" +
            "• \"How much battery is left?\" / \"What time is it?\"\n" +
            "• \"How much storage is left?\" / \"Is my Wi-Fi on?\" / \"Go home\"\n" +
            "• \"Take a note: buy milk\" / \"Read my notes\"\n" +
            "• \"Remember that my mom's birthday is May 3\" / \"What do you remember about me?\" / \"Forget my mom's birthday\"\n" +
            "• \"Call Bob\" / \"Send a message to Mom saying I'll be late\"\n" +
            "• \"Calculate 15% of 2.4 million\" / \"sqrt 144 + 3 to the power 4\"\n" +
            "• \"Log expense lunch 25 thousand\" / \"Got paid 5 million\" / \"Finance report this month\"\n" +
            "• \"Navigate to the airport by motorbike\" (starts the route in Google Maps)\n" +
            "• \"Close all apps\" / \"Close everything including Nine\"\n" +
            "• \"Flirt with me\" / \"Pantun\" / \"A wise quote\" / \"Sing the bar droid song\"\n" +
            "• \"Call me Sir\" / \"Affinity status\" / the + button in the typing bar sends an image\n" +
            "• \"Be my English mentor\" (grammar fixes & better words, offline) / \"Correct my sentence: i goes to school\" / \"Another word for good\"\n" +
            "• \"I need to vent\" (a listening companion; not a psychologist, not saved) / \"Breathing exercise\"\n" +
            "• Fitness coach & nutritionist (not medical advice): \"Just did bench press 60 kg 3x8\" / \"I ate fried rice and eggs\" / \"Today's meals\" / \"Bench press progress\" / \"How much protein do I need?\"\n" +
            "• \"Analyse my InBody\" then send the photo (the + button in the typing bar) or type \"inbody weight 70 muscle 32 fat 14\"\n" +
            "• \"Switch language to Indonesian\" / \"Forget our conversation\""
    )
}
