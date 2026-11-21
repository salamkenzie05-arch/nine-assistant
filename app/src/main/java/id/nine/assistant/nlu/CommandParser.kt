package id.nine.assistant.nlu

import id.nine.assistant.coach.CoachIntent
import id.nine.assistant.coach.CoachIntents
import id.nine.assistant.core.AppLang
import id.nine.assistant.support.SupportIntent
import java.time.LocalDateTime

/**
 * Pemahaman perintah suara (Bahasa Indonesia + English) berbasis aturan, 100% lokal.
 * Tidak memakai Android API supaya bisa diuji sebagai Kotlin murni.
 */
class CommandParser(private val clock: () -> LocalDateTime = { LocalDateTime.now() }) {

    private fun rx(p: String) = Regex(p, RegexOption.IGNORE_CASE)

    // ------------------------------------------------------------------ pembersihan

    private val rePrefix = rx(
        """^(?:(?:hai|halo|hei|hey|hi|hello|oke|ok|okay)\b[,\s]*(?:asisten|assistant|nine|nain|nayn)?[,\s]*|(?:nine|nain|nayn)\b[,\s]+|""" +
            """(?:tolong|mohon|please|coba|bisakah(?:\s+kamu)?|bisa(?:\s+kamu)?|can\s+you|could\s+you|would\s+you|will\s+you|""" +
            """i\s+want\s+you\s+to|i\s+want\s+to|i\s+would\s+like\s+to|i'd\s+like\s+to|saya\s+(?:mau|ingin|minta)|aku\s+(?:mau|ingin|minta))\b[,\s]*)"""
    )
    private val reSuffix = rx("""(?:\s+(?:dong|deh|nih|ya|please|tolong|dong\s+ya))+$""")

    private fun clean(input: String): String {
        var t = input.trim().replace(Regex("\\s+"), " ").trimEnd('.', ',', '!', '?', ';', ' ')
        val base = t
        var prev: String
        do {
            prev = t
            t = rePrefix.replaceFirst(t, "").trim()
        } while (t != prev && t.isNotEmpty())
        if (t.isEmpty()) return base
        t = reSuffix.replaceFirst(t, "").trim()
        return t
    }

    // ------------------------------------------------------------------ API publik

    fun parse(input: String): Command {
        val first = parseOne(input)
        if (first !is Command.Unknown) return first
        // Kalimat berantakan (urutan terbalik, salah ketik, kata pengisi): coba versi yang dirapikan.
        if (reQuestion.containsMatchIn(clean(input))) return first
        // "aku mau curhat ke ibuku": kata "ke" yang dibuang pemulih kalimat tidak boleh mengubahnya jadi ajakan curhat ke Nine.
        if (SupportIntent.targetsOtherPerson(input)) return first
        for (cand in Messy.candidates(input)) {
            val c = parseOne(cand)
            if (c !is Command.Unknown) return c
        }
        return first
    }

    private fun parseOne(input: String): Command {
        val raw = ExtraParser.stripNya(clean(input))
        if (raw.isEmpty()) return Command.Unknown(input.trim())
        val norm = NumberWords.normalize(raw)
        val now = clock()
        planText = input.lowercase()
        val cmd = parseCommand(raw, norm, now)
        // Pertanyaan umum ("kenapa alarm saya tidak bunyi?", "how do I set an alarm?") lebih cocok untuk otak AI,
        // kecuali pertanyaan informasi yang memang dijawab perangkat (jam, baterai, jadwal, tugas, ...).
        if (reQuestion.containsMatchIn(raw) && cmd !is Command.Unknown && !answersQuestion(cmd)) {
            return Command.Unknown(raw)
        }
        return cmd
    }

    private val reQuestion = rx("""^(?:apa(?:kah)?|kenapa|mengapa|bagaimana|gimana|siapa|kapan|dimana|di\s+mana|kemana|ke\s+mana|berapa|bolehkah|haruskah|apa\s+bedanya|why|how|what|who|when|where|which|is|are|does|do|did|should|would|explain|jelaskan|ceritakan|tell\s+me\s+about)\b""")

    private fun answersQuestion(c: Command): Boolean = when (c) {
        Command.CurrentTime, Command.CurrentDate, Command.Battery, Command.DeviceInfo, is Command.PhoneInfo, Command.Help,
        Command.WhoAreYou, Command.HowAreYou, Command.TodoList, Command.ReadNotes, Command.ReadMemory, Command.ShowAlarms,
        Command.AffinityStatus, Command.WhereAmI, is Command.CorrectEnglish, is Command.BetterWord, is Command.EnglishCoach, is Command.Support, is Command.Health -> true
        is Command.ShowAgenda, is Command.Calculate, is Command.FinanceReport, is Command.FinanceList,
        is Command.Entertain -> true
        else -> false
    }

    /** Ucapan asli (huruf kecil) sebelum dibersihkan: dipakai untuk membedakan rencana ("mau beli …") dari catatan. */
    private var planText: String = ""

    private fun parseCommand(raw: String, norm: String, now: LocalDateTime): Command {
        return parseLanguage(raw)
            ?: parseSupport(raw)
            ?: parseCoach(raw)
            ?: parseHealth(raw)
            ?: parseReset(raw)
            ?: parseControl(raw)
            ?: parseCalc(raw)
            ?: parseAffinity(raw)
            ?: parseLocation(raw)
            ?: parseRecorder(raw)
            ?: parseCamera(norm)
            ?: parseTodo(raw)
            ?: parseAlarm(norm, now)
            ?: parseTimer(norm)
            ?: parseReminder(raw, now)
            ?: parseCalendar(raw, now)
            ?: parseFlashlight(raw)
            ?: parseGoHome(raw)
            ?: parsePhoneInfo(raw)
            ?: parseSettings(raw)
            ?: parseVolume(norm)
            ?: (if (FinanceParser.isPlan(planText)) null else FinanceParser.parse(raw))
            ?: parseCloseApps(raw)
            ?: parseFun(raw)
            ?: parseSetName(raw)
            ?: ExtraParser.parse(raw)
            ?: parseDeviceInfo(raw)
            ?: parseMemory(raw)
            ?: parseNotes(raw)
            ?: parseWhatsApp(raw)
            ?: parseSms(raw)
            ?: parseCall(raw)
            ?: parseExternal(raw)
            ?: parseOpenApp(raw)
            ?: parseDeleteLoose(raw, now)
            ?: Command.Unknown(raw)
    }

    // ------------------------------------------------------------------ bahasa & kontrol

    private val reLang = rx("""(?:ganti|ubah|pindah|beralih|switch|change)\s+(?:the\s+)?(?:bahasa|language)\s*(?:ke|to|menjadi|jadi)?\s*(?:bahasa\s+)?(inggris|english|indonesia|indonesian)""")
    private val reSpeak = rx("""^(?:berbicara|bicara|ngomong|speak|talk)\s+(?:dalam\s+|in\s+|bahasa\s+)*(inggris|english|indonesia|indonesian)$""")

    private fun langOf(w: String): AppLang =
        if (w.lowercase().startsWith("ing") || w.lowercase().startsWith("eng")) AppLang.EN else AppLang.ID

    private fun parseLanguage(raw: String): Command? {
        val m = reLang.find(raw) ?: reSpeak.find(raw) ?: return null
        return Command.SwitchLanguage(langOf(m.groupValues[1]))
    }

    private val reReset = rx("""^(?:lupakan|hapus|reset|ulang(?:i)?|mulai(?:\s+ulang)?)\s+(?:semua\s+)?(?:percakapan|obrolan|chat)(?:\s+(?:kita|ini|baru))?$|^(?:percakapan|obrolan)\s+baru$|^(?:forget|reset|clear|start)\s+(?:a\s+|the\s+|our\s+|this\s+)?(?:new\s+)?(?:conversation|chat)$|^new\s+(?:conversation|chat)$""")

    private fun parseReset(raw: String): Command? = if (reReset.matches(raw)) Command.ResetChat else null

    private val reStop = rx("""^(?:stop|berhenti|diam|cukup|sudah\s+cukup|shut\s+up|be\s+quiet|quiet|hentikan)$""")
    private val reHelp = rx("""^(?:bantuan|help|help\s+me|bantu\s+saya|kamu\s+bisa\s+apa|bisa\s+apa\s+saja|apa\s+yang\s+bisa\s+kamu\s+lakukan|apa\s+saja\s+yang\s+bisa\s+kamu\s+lakukan|perintah\s+apa\s+saja|what\s+can\s+you\s+do|what\s+are\s+your\s+commands|commands)$""")
    private val reGreet = rx("""^(?:halo|hai|hei|hey|hi|hello|selamat\s+(?:pagi|siang|sore|malam)|good\s+(?:morning|afternoon|evening|night))(?:\s+\w+)?$""")
    private val reThanks = rx("""^(?:terima\s*kasih|makasih|thanks|thank\s+you|thx|trims)(?:\s+.*)?$""")
    private val reWho = rx("""(?:siapa\s+(?:kamu|kau|anda|namamu)|siapa\s+nama\s+(?:kamu|mu|anda)|nama\s+(?:kamu|mu|anda)\s+siapa|who\s+are\s+you|what(?:'s|\s+is)\s+your\s+name)""")
    private val reHow = rx("""^(?:apa\s+kabar|gimana\s+kabar(?:mu)?|how\s+are\s+you(?:\s+doing)?)$""")

    private fun parseControl(raw: String): Command? = when {
        reStop.matches(raw) -> Command.StopSpeaking
        reHelp.matches(raw) -> Command.Help
        reHow.matches(raw) -> Command.HowAreYou
        reGreet.matches(raw) -> Command.Greeting
        reThanks.matches(raw) -> Command.Thanks
        reWho.containsMatchIn(raw) -> Command.WhoAreYou
        else -> null
    }

    // ------------------------------------------------------------------ alarm

    private val reAlarmWord = rx("""\b(?:alarm|alarms|bangunkan|bangunin|wake\s+me(?:\s+up)?)\b""")
    private val reShowAlarm = rx("""^(?:lihat|tampilkan|tunjukkan|show|view|list|check|cek|bacakan|baca|read)\s+(?:semua\s+|daftar\s+|my\s+|all\s+|the\s+|me\s+)*alarms?\b(?:\s+(?:saya|ku|aku|apa\s+saja))?$|what\s+alarms|alarm\s+apa\s+saja|^(?:my\s+)?alarms?(?:\s+(?:saya|ku|aku))?$|^(?:daftar|list\s+of)\s+alarms?(?:\s+(?:saya|ku|aku))?$|^(?:do\s+i\s+have\s+any|ada)\s+alarms?\b""")
    private val reOpenAlarmApp = rx("""^(?:buka|bukakan|open|launch|go\s+to)\s+(?:aplikasi\s+|app\s+|the\s+|my\s+|menu\s+|layar\s+|daftar\s+)*alarms?(?:\s+(?:app|aplikasi|screen|list))?$|^(?:open|buka)\s+(?:the\s+)?(?:alarm\s+)?(?:clock|jam)\s+(?:app|aplikasi)?\s*alarms?$""")
    private val reForNum = rx("""\bfor\s+(?=\d{1,2}(?:[.:]\d{2})?\b(?!\s*(?:minutes?|mins?|hours?|hrs?|seconds?|secs?|menit|jam|detik)))""")
    private val reWake = rx("""\b(?:bangunkan|bangunin|wake\s+me)\b""")
    private val reCmdWords = rx("""\b(?:alarm|alarms|set|pasang|setel|atur|buat|bikin|tambah|tambahkan|an|a|for|untuk|me|up|wake|bangunkan|bangunin|aku|saya|please|tolong|the|my|dong|at|jam|pukul)\b""")

    /** Kata kerja untuk mengubah alarm yang sudah ada. */
    private val reAlarmOp = rx("""^(matikan|nonaktifkan|disable|turn\s+off|switch\s+off|shut\s+off|batalkan|cancel|stop|hentikan|diamkan|silence|dismiss|nyalakan(?:\s+lagi)?|hidupkan(?:\s+lagi)?|aktifkan(?:\s+lagi|\s+kembali)?|enable|turn\s+on|switch\s+on|re-?enable|hapus|delete|remove|buang|hilangkan|clear|erase)\s+(.*)$""")
    private val reAlarmOpTail = rx("""^(?:turn|switch)\s+(.*\balarms?\b.*?)\s+(off|on)$""")
    private enum class AlarmOp { OFF, ON, DELETE }

    private fun opOf(verb: String): AlarmOp {
        val v = verb.lowercase()
        return when {
            v.startsWith("hapus") || v.startsWith("delete") || v.startsWith("remove") || v.startsWith("buang") ||
                v.startsWith("hilangkan") || v.startsWith("clear") || v.startsWith("erase") -> AlarmOp.DELETE
            v.startsWith("nyala") || v.startsWith("hidup") || v.startsWith("aktif") || v.startsWith("enable") ||
                v.endsWith(" on") || v.startsWith("re") -> AlarmOp.ON
            else -> AlarmOp.OFF
        }
    }

    private val reRefAll = rx("""\b(?:semua|seluruh|all|every|everything)\b""")
    private val reRefLast = rx("""\b(?:tadi|terakhir|barusan|itu|that|last|latest|(?:i\s+)?just\s+(?:set|made|created|added)|baru\s+(?:saja\s+|aja\s+)?(?:di)?(?:pasang|buat|bikin|setel))\b""")
    private val reRefNext = rx("""\b(?:berikutnya|selanjutnya|terdekat|next|upcoming|besok\s+pagi|tomorrow\s+morning)\b""")
    private val reRefIndex = rx("""\b(?:nomor|no\.?|number|#)\s*(\d{1,2})\b|\b(?:yang\s+)?(pertama|kedua|ketiga|keempat|kelima|first|second|third|fourth|fifth)\b""")
    private val reRefFiller = rx("""\b(?:alarms?|semua|seluruh|all|every|everything|my|the|saya|ku|aku|yang|untuk|for|at|on|jam|pukul|di|pada|tadi|terakhir|barusan|itu|that|last|latest|berikutnya|selanjutnya|terdekat|next|upcoming|lagi|kembali|again|please|tolong|dong|nomor|no|number|one|ini|this|of|mine|set|besok|tomorrow|hari\s+ini|today|i|just|made|created|added|baru|saja|aja|dipasang|dibuat|pagi|siang|sore|malam|morning|afternoon|evening|night|tonight)\b""")

    private fun alarmRef(text: String): AlarmRef {
        val t = text.trim()
        val all = reRefAll.containsMatchIn(t)
        val last = !all && reRefLast.containsMatchIn(t)
        val next = !all && reRefNext.containsMatchIn(t)
        var index: Int? = null
        reRefIndex.find(t)?.let { m ->
            index = m.groupValues[1].toIntOrNull() ?: Ordinals.parse(m.groupValues[2])
        }
        var rest = if (index != null) reRefIndex.replace(t, " ") else t
        var hour: Int? = null
        var minute: Int? = null
        var amb = false
        val tp = if (Regex("\\d").containsMatchIn(rest)) TimeParser.parse(rest) else null
        if (tp != null && tp.spec.hour != null) {
            hour = tp.spec.hour
            minute = tp.spec.minute
            amb = tp.spec.ambiguous
            rest = tp.remainder
        }
        val label = reRefFiller.replace(rest, " ").replace(Regex("[^\\p{L}\\p{N}\\s'-]"), " ").replace(Regex("\\s+"), " ").trim()
            .takeIf { it.length >= 2 }
        return AlarmRef(all = all, hour = hour, minute = minute, ambiguous = amb, label = label, last = last && hour == null, next = next && hour == null, index = index)
    }

    /** Bentuk lisan/pasif kata kerja → bentuk baku yang dikenali [reAlarmOp] ("matiin", "dihapus", "hapusin", ...). */
    private val reVerbFix: List<Pair<Regex, String>> = listOf(
        rx("""\b(?:matiin|mematikan|dimatikan|matikanlah|matikan\s+(?:aja|saja|dong))\b""") to "matikan",
        rx("""\b(?:nonaktifin|menonaktifkan|dinonaktifkan)\b""") to "nonaktifkan",
        rx("""\b(?:hapusin|hapuskan|hapuslah|menghapus|dihapus|ngapus|ngehapus|hilangin|dihilangkan|menghilangkan|buangin|membuang|dibuang)\b""") to "hapus",
        rx("""\b(?:nyalain|menyalakan|dinyalakan|nyalakanlah|hidupin|menghidupkan|dihidupkan|aktifin|mengaktifkan|diaktifkan)\b""") to "nyalakan",
        rx("""\b(?:batalin|membatalkan|dibatalkan)\b""") to "batalkan"
    )

    private fun fixAlarmVerbs(s: String): String {
        var t = s
        for ((re, to) in reVerbFix) t = re.replace(t, to)
        return t
    }

    /** Kata kerja di mana saja dalam kalimat ("alarm jam 6 matikan", "alarm yang tadi hapus aja"). */
    private val reVerbAnywhere = rx("""\b(matikan|nonaktifkan|batalkan|hapus|hentikan|nyalakan|hidupkan|aktifkan|delete|remove|cancel|disable|enable)\b""")

    private fun parseAlarmOp(norm: String): Command? {
        var verb: String
        var rest: String
        val m = reAlarmOp.find(norm)
        if (m != null) {
            verb = m.groupValues[1]
            rest = m.groupValues[2]
        } else {
            val t = reAlarmOpTail.find(norm)
            if (t != null) {
                verb = "turn " + t.groupValues[2]
                rest = t.groupValues[1]
            } else {
                // "alarm jam 6 dimatikan", "alarm tadi hapus aja": kata kerja bukan di awal
                val any = reVerbAnywhere.find(norm) ?: return null
                verb = any.groupValues[1]
                rest = (norm.removeRange(any.range)).replace(Regex("\\s+"), " ").trim()
            }
        }
        if (!Regex("""\balarms?\b""", RegexOption.IGNORE_CASE).containsMatchIn(rest)) return null
        // "hapus alarm" setelah kata kerja lain, mis. "matikan semua alarm besok pagi"
        val ref = alarmRef(rest)
        return when (opOf(verb)) {
            AlarmOp.DELETE -> Command.AlarmDelete(ref)
            AlarmOp.ON -> Command.AlarmToggle(ref, true)
            AlarmOp.OFF -> Command.AlarmToggle(ref, false)
        }
    }

    private fun parseAlarm(norm0: String, now: LocalDateTime): Command? {
        val norm = fixAlarmVerbs(norm0)
        if (!reAlarmWord.containsMatchIn(norm)) return null
        if (reOpenAlarmApp.matches(norm)) return Command.OpenAlarms
        if (reShowAlarm.containsMatchIn(norm)) return Command.ShowAlarms
        parseAlarmOp(norm)?.let { return it }
        // Pengulangan: "setiap hari", "hari kerja", "every monday"
        val rep = RepeatDays.extract(norm)
        val text = rep?.second ?: norm
        // "set alarm for 7" -> "set alarm at 7"
        val n2 = reForNum.replace(text, "at ")
        val tp = TimeParser.parse(n2)
        val label = if (tp != null) labelFrom(tp.remainder) else labelFrom(n2)
        var spec = tp?.spec
        if (spec != null && spec.ambiguous && spec.hour != null && (reWake.containsMatchIn(norm) || rep != null)) {
            // "bangunkan aku jam 5" / "alarm setiap hari jam 6" = pagi hari (kecuali jam 1–3 dan 12)
            val h = spec.hour
            val hh = if (reWake.containsMatchIn(norm) || h in 4..11) h % 12 else if (h == 12) 12 else h + 12
            spec = spec.copy(hour = hh, ambiguous = false)
        }
        if (rep != null) {
            val s = spec
            if (s?.hour == null) return Command.NeedInfo(Pending.AlarmTime(label))
            val at = nextOn(rep.first, s.hour, s.minute, now)
            return Command.SetAlarm(at, label, rep.first)
        }
        val at = spec?.resolve(now)
        if (spec == null || !spec.isKnown || at == null) {
            return Command.NeedInfo(Pending.AlarmTime(label))
        }
        return Command.SetAlarm(at, label)
    }

    /** Kejadian pertama jam [h]:[m] pada salah satu [days] setelah [now]. */
    private fun nextOn(days: Set<java.time.DayOfWeek>, h: Int, m: Int, now: LocalDateTime): LocalDateTime {
        var d = now.toLocalDate()
        repeat(8) {
            val t = d.atTime(h % 24, m)
            if (d.dayOfWeek in days && t.isAfter(now)) return t
            d = d.plusDays(1)
        }
        return now.toLocalDate().plusDays(1).atTime(h % 24, m)
    }

    private fun labelFrom(remainder: String): String? {
        val t = reCmdWords.replace(remainder, " ").replace(Regex("\\s+"), " ").trim().trim(',', '.', ' ')
        return if (t.length >= 2) t else null
    }

    // ------------------------------------------------------------------ timer

    private val reTimerWord = rx("""\b(?:timer|hitung\s+mundur|countdown|pengatur\s+waktu)\b""")

    private fun parseTimer(norm: String): Command? {
        if (!reTimerWord.containsMatchIn(norm)) return null
        val secs = Durations.parseSeconds(norm) ?: return Command.NeedInfo(Pending.TimerDuration(null))
        return Command.SetTimer(secs, null)
    }

    // ------------------------------------------------------------------ pengingat

    private val reRemind = listOf(
        rx("""^(?:ingatkan|ingetin|ingatin)\b\s*(?:(?:saya|aku|gue|gw|ku)\b)?\s*(.*)$"""),
        rx("""^(?:buat(?:kan)?|tambah(?:kan)?|set|atur|pasang|bikin)\s+(?:sebuah\s+)?(?:pengingat|reminder)\s*(.*)$"""),
        rx("""^remind\s+me\s*(.*)$"""),
        rx("""^(?:set|create|make|add|schedule)\s+(?:a\s+|an\s+|new\s+)*reminder\s*(.*)$""")
    )

    private fun parseReminder(raw: String, now: LocalDateTime): Command? {
        var payload: String? = null
        for (r in reRemind) {
            val m = r.find(raw)
            if (m != null) {
                payload = m.groupValues[1].trim()
                break
            }
        }
        if (payload == null) return null
        if (payload.isEmpty()) return Command.NeedInfo(Pending.ReminderText)
        val p = NumberWords.normalize(payload)
        val tp = TimeParser.parse(p)
        if (tp == null || !tp.spec.isKnown) {
            return Command.NeedInfo(Pending.ReminderTime(tidyText(p)))
        }
        val text = tp.remainder
        val spec = tp.spec
        if (spec.relativeSeconds != null) {
            return Command.SetTimer(spec.relativeSeconds, text.ifBlank { null })
        }
        val at = spec.resolve(now) ?: return Command.NeedInfo(Pending.ReminderTime(text))
        return Command.SetReminder(text, at)
    }

    private fun tidyText(s: String): String =
        s.replace(Regex("^(?:untuk|agar|bahwa|that|to)\\s+", RegexOption.IGNORE_CASE), "").trim()

    // ------------------------------------------------------------------ senter

    private val reTorch = rx("""\b(?:senter|lampu\s+senter|flash\s*light|torch|lampu\s+kilat)\b""")
    private val reOff = rx("""\b(?:matikan|padamkan|mati|off|nonaktifkan|disable)\b""")

    private fun parseFlashlight(raw: String): Command? {
        if (!reTorch.containsMatchIn(raw)) return null
        return Command.Flashlight(!reOff.containsMatchIn(raw))
    }

    // ------------------------------------------------------------------ pengaturan

    private val settingTargets: List<Pair<Regex, SettingsTarget>> = listOf(
        // Hotspot dicek lebih dulu: "hotspot wifi" bukan Wi-Fi biasa.
        rx("""\b(?:hot\s*spot|tethering|modem\s+wi-?fi|mobile\s+hotspot|wi-?fi\s+(?:portabel|portable)|portable\s+hotspot)\b""") to SettingsTarget.HOTSPOT,
        rx("""\b(?:motion\s+assist(?:ance)?|bantuan\s+gerak(?:an)?|motion|gerakan|gerak)\b""") to SettingsTarget.MOTION,
        rx("""\b(?:wi-?fi|jaringan\s+wifi|internet)\b""") to SettingsTarget.WIFI,
        rx("""\bbluetooth\b""") to SettingsTarget.BLUETOOTH,
        rx("""\b(?:mode\s+pesawat|airplane\s+mode|flight\s+mode|mode\s+terbang|mode\s+penerbangan)\b""") to SettingsTarget.AIRPLANE,
        rx("""\b(?:hemat\s+baterai|penghemat\s+baterai|battery\s+saver|power\s+saving)\b""") to SettingsTarget.BATTERY_SAVER,
        rx("""\b(?:jangan\s+ganggu|do\s+not\s+disturb|dnd|mode\s+senyap)\b""") to SettingsTarget.DND,
        rx("""\b(?:data\s+seluler|data\s+selular|data\s+ponsel|paket\s+data|mobile\s+data|cellular)\b""") to SettingsTarget.MOBILE_DATA,
        rx("""\b(?:lokasi|location|gps)\b""") to SettingsTarget.LOCATION,
        rx("""\b(?:layar|tampilan|kecerahan|brightness|display|screen)\b""") to SettingsTarget.DISPLAY,
        rx("""\b(?:suara|sound|nada\s+dering|ringtone)\b""") to SettingsTarget.SOUND
    )

    private fun targetOf(s: String, networkOnly: Boolean = false): SettingsTarget? {
        for ((re, t) in settingTargets) {
            if (networkOnly && t !in networkTargets) continue
            if (re.containsMatchIn(s)) return t
        }
        return null
    }

    private val networkTargets = setOf(
        SettingsTarget.WIFI, SettingsTarget.BLUETOOTH, SettingsTarget.AIRPLANE,
        SettingsTarget.LOCATION, SettingsTarget.MOBILE_DATA, SettingsTarget.DND, SettingsTarget.BATTERY_SAVER,
        SettingsTarget.HOTSPOT
    )

    private val reToggle = rx("""^(nyalakan|nyalain|hidupkan|hidupin|aktifkan|aktifin|matikan|matiin|nonaktifkan|nonaktifin|turn\s+on|turn\s+off|switch\s+on|switch\s+off|enable|disable)\s+(?:the\s+|fitur\s+)?(.+)$""")
    private val reOpenSettings = rx("""^(?:buka|bukakan|open|show|tampilkan|go\s+to|pergi\s+ke)\s+(?:menu\s+)?(?:pengaturan|settings|setelan)(?:\s+(.*))?$""")
    private val reSettingsOnly = rx("""^(?:pengaturan|settings|setelan)(?:\s+(.*))?$""")

    private fun parseSettings(raw: String): Command? {
        val tg = reToggle.find(raw)
        if (tg != null) {
            val verb = tg.groupValues[1].lowercase()
            val target = targetOf(tg.groupValues[2], networkOnly = false) ?: return null
            if (target == SettingsTarget.SOUND) return null // ditangani parseVolume (unmute)
            val on = !(verb.startsWith("matikan") || verb.startsWith("matiin") || verb.startsWith("nonaktif") ||
                verb.endsWith("off") || verb == "disable")
            return Command.OpenSettings(target, on)
        }
        val os = reOpenSettings.find(raw) ?: reSettingsOnly.find(raw)
        if (os != null) {
            val rest = os.groupValues[1]
            val target = if (rest.isBlank()) SettingsTarget.GENERAL else (targetOf(rest) ?: SettingsTarget.GENERAL)
            return Command.OpenSettings(target, null)
        }
        return null
    }

    // ------------------------------------------------------------------ volume

    private val reVolWord = rx("""\b(?:volume|suara|sound|audio)\b""")
    private val reMute = rx("""\b(?:bisukan|senyapkan|mute|silence)\b""")
    private val reUnmute = rx("""\b(?:unmute|bunyikan\b(?!\s+(?:senar|string|dawai|nada|note|gitar|guitar))|aktifkan\s+suara|nyalakan\s+suara|turn\s+on\s+(?:the\s+)?sound)\b""")
    private val reVolMax = rx("""\bvolume\s+(?:maksimal|maksimum|max|penuh|full)\b|\b(?:maksimalkan|full)\s+volume\b""")
    private val reVolSet = rx("""\b(?:volume|suara)\s*(?:ke|jadi|menjadi|to|at)?\s*(\d{1,3})\s*(?:persen|percent|%)?""")
    private val reVolSet2 = rx("""\b(?:atur|set|setel|ubah)\s+(?:volume|suara)\b.*?(\d{1,3})""")
    private val reVolUp = rx("""\b(?:naikkan|besarkan|keraskan|tambah(?:kan)?|perbesar|turn\s+up|raise|increase)\b.*\b(?:volume|suara)\b|\b(?:volume|suara)\s+(?:naik|up|lebih\s+keras)\b|\blouder\b""")
    private val reVolDown = rx("""\b(?:turunkan|kecilkan|kurangi|perkecil|turn\s+down|lower|decrease|reduce)\b.*\b(?:volume|suara)\b|\b(?:volume|suara)\s+(?:turun|down|lebih\s+pelan)\b|\bquieter\b""")

    private fun parseVolume(norm: String): Command? {
        if (reUnmute.containsMatchIn(norm)) return Command.Volume(VolumeAction.UNMUTE)
        if (reMute.containsMatchIn(norm)) return Command.Volume(VolumeAction.MUTE)
        if (!reVolWord.containsMatchIn(norm) && !Regex("\\b(?:louder|quieter)\\b", RegexOption.IGNORE_CASE).containsMatchIn(norm)) return null
        if (reVolMax.containsMatchIn(norm)) return Command.Volume(VolumeAction.MAX)
        val set = reVolSet.find(norm) ?: reVolSet2.find(norm)
        if (set != null) {
            val p = set.groupValues[1].toIntOrNull()
            if (p != null && p in 0..100) return Command.Volume(VolumeAction.SET, p)
        }
        if (reVolUp.containsMatchIn(norm)) return Command.Volume(VolumeAction.UP)
        if (reVolDown.containsMatchIn(norm)) return Command.Volume(VolumeAction.DOWN)
        return null
    }

    // ------------------------------------------------------------------ info perangkat

    private val reBattery = rx("""\b(?:baterai|batre|battery|batere)\b""")
    private val reTime = rx("""(?:jam|pukul)\s+berapa|what(?:'s|\s+is)\s+the\s+time|what\s+time\s+is\s+it|current\s+time|tell\s+me\s+the\s+time|\btime\s+now\b""")
    private val reDate = rx("""tanggal\s+berapa|hari\s+apa|what(?:'s|\s+is)\s+(?:the\s+)?date|what\s+day\s+is\s+(?:it|today)|today'?s\s+date|what\s+is\s+today""")
    private val reDevice = rx("""\b(?:info(?:rmasi)?\s+(?:perangkat|hp|ponsel|device)|device\s+info(?:rmation)?|about\s+(?:this\s+)?phone|spesifikasi\s+(?:hp|ponsel|perangkat)|spek(?:\s+(?:hp|ponsel))?|phone\s+(?:specs?|info|status)|kondisi\s+(?:hp|ponsel|perangkat)|status\s+(?:hp|ponsel|perangkat)|cek\s+(?:hp|ponsel)|tentang\s+(?:hp|ponsel)\s+ini)\b""")

    // ------------------------------------------------------------------ layar utama & pertanyaan tentang HP

    private val reGoHome = rx("""^(?:(?:go|come|get|take\s+me|bring\s+me|head|return|back|press|kembali|pergi|balik|buka|open|show|tampilkan|tekan|menuju|ke|to|the|my)\s+)*(?:home(?:\s*screen|\s*page)?|homescreen|layar\s+(?:utama|depan|awal|home)|halaman\s+(?:utama|depan|awal|home)|beranda)$""")

    private fun parseGoHome(raw: String): Command? = if (reGoHome.matches(raw.trim())) Command.GoHome else null

    private val reInfoImperative = rx("""\b(?:nyalakan|hidupkan|aktifkan|matikan|matiin|turn|switch|enable|disable|buka|open|setel|set|atur|ubah|ganti|change|naikkan|turunkan|kecilkan|besarkan|increase|decrease|raise|lower|bersihkan|hapus|clear|delete|reset)\b""")

    private val infoRules: List<Pair<InfoTopic, Regex>> = listOf(
        InfoTopic.BATTERY_HEALTH to rx("""\b(?:suhu|temperatur|temperature|kesehatan|health|kondisi|voltase|voltage)\s+(?:baterai|batre|battery)\b|\b(?:baterai|batre|battery)\s+(?:saya\s+|ku\s+)?(?:health|temperature|temp|suhu|sehat|panas|overheat|kondisi)\b|\bhp\s+(?:saya\s+|ku\s+)?(?:panas|overheat)\b|\bis\s+(?:my\s+)?(?:phone|battery)\s+(?:hot|overheating|healthy)\b|\b(?:sedang|lagi)\s+(?:di\s*)?(?:cas|charge|charging|isi\s+daya)\b|\bis\s+(?:it|my\s+phone)\s+charging\b"""),
        InfoTopic.WIFI to rx("""^(?:status|cek|check)\s+(?:wifi|wi-fi)$|^(?:apakah|apa|is)\s+(?:my\s+|the\s+)?(?:wifi|wi-fi)(?:\s+(?:saya|ku))?\s+(?:sedang\s+)?(?:nyala|hidup|aktif|menyala|on|terhubung|tersambung|nyambung|connected|enabled)(?:\s+(?:nggak|ngga|gak|tidak|kah))?$|^(?:wifi|wi-fi)(?:\s+(?:saya|ku))?\s+(?:nyala|hidup|aktif|menyala|on|terhubung|nyambung|connected)\s+(?:nggak|ngga|gak|tidak|kah|atau\s+(?:mati|off)|or\s+off)$"""),
        InfoTopic.BLUETOOTH to rx("""^(?:status|cek|check)\s+bluetooth$|^(?:apakah|apa|is)\s+(?:my\s+|the\s+)?bluetooth(?:\s+(?:saya|ku))?\s+(?:sedang\s+)?(?:nyala|hidup|aktif|menyala|on|terhubung|tersambung|nyambung|connected|enabled)(?:\s+(?:nggak|ngga|gak|tidak|kah))?$|^bluetooth(?:\s+(?:saya|ku))?\s+(?:nyala|hidup|aktif|menyala|on|terhubung|nyambung|connected)\s+(?:nggak|ngga|gak|tidak|kah|atau\s+(?:mati|off)|or\s+off)$"""),
        InfoTopic.STORAGE to rx("""\b(?:penyimpanan|storage|memori\s+internal|ruang\s+(?:kosong|penyimpanan|tersisa)|sisa\s+(?:memori|ruang|penyimpanan)|free\s+space|disk\s+space|space\s+left)\b"""),
        InfoTopic.RAM to rx("""\b(?:ram|memori\s+ram)\b"""),
        InfoTopic.MODEL to rx("""\b(?:tipe|type|model|merek|merk|brand|seri)\s+(?:hp|ponsel|handphone|phone|perangkat|device)\b|\b(?:hp|ponsel|handphone|phone)\s+(?:ini\s+|saya\s+|ku\s+)?(?:apa|tipe\s+apa|merek\s+apa|merk\s+apa|model\s+apa|type\s+apa)\b|\bphone\s+model\b|\b(?:what|which)\s+(?:phone|device|model)\b|\bmerek\s+apa\b"""),
        InfoTopic.ANDROID to rx("""\b(?:versi|version)\s+(?:android|one\s*ui|os)\b|\bandroid\s+(?:version|versi|berapa|apa)\b|\bwhat\s+android\b|\bpatch\s+keamanan\b|\bsecurity\s+patch\b|\bone\s*ui\s+(?:berapa|version|versi)\b"""),
        InfoTopic.NETWORK to rx("""\b(?:status|jenis|tipe|type)\s+(?:internet|jaringan|koneksi|network|connection)\b|\b(?:wifi|wi-fi)\s+(?:atau|or)\s+(?:data|mobile)\b|\b(?:data|mobile\s+data)\s+(?:atau|or)\s+(?:wifi|wi-fi)\b|\b(?:apakah\s+)?(?:aku|saya|gue)\s+(?:sedang\s+)?online(?:\s+(?:nggak|ngga|gak|tidak|kah))?$|\b(?:am\s+i|are\s+we|is\s+(?:it|my\s+phone))\s+(?:online|connected)\b|\bis\s+(?:the\s+)?internet\s+(?:on|working|connected)\b|\b(?:cek|check)\s+(?:my\s+)?(?:internet|koneksi|jaringan|connection|network)\b|\b(?:internet|koneksi|jaringan)\s+(?:saya\s+|ku\s+)?(?:nyambung|tersambung|terhubung|connected)\b"""),
        InfoTopic.SCREEN to rx("""\b(?:resolusi|resolution|refresh\s*rate|ukuran\s+layar|screen\s+(?:size|resolution)|dpi)\b|\bberapa\s+hz\b"""),
        InfoTopic.UPTIME to rx("""\buptime\b|\b(?:sudah\s+)?(?:nyala|menyala|hidup)\s+berapa\s+lama\b|\bsudah\s+(?:nyala|menyala|hidup)\s+sejak\b|\b(?:terakhir|kapan)\s+(?:di\s*)?(?:restart|reboot|dinyalakan|dihidupkan)\b|\bsince\s+(?:the\s+)?last\s+(?:reboot|restart)\b|\bhow\s+long\s+(?:has|have)\s+(?:my\s+|the\s+|this\s+)?(?:phone|it|device)\s+(?:been\s+)?(?:on|running|up|awake)\b"""),
        InfoTopic.VOLUME to rx("""\b(?:volume|suara)\s+(?:sekarang|saat\s+ini|saya|ku|media|musik)?\s*(?:berapa|level)\b|\bberapa\s+(?:persen\s+)?volume(?:nya|ku)?\b|\bwhat(?:'s|\s+is)\s+(?:the\s+|my\s+)?(?:current\s+)?volume\b|\bvolume\s+(?:now|level|status)\b|\bcek\s+volume\b"""),
        InfoTopic.CPU to rx("""\b(?:prosesor|processor|cpu|chipset|soc)\s+(?:apa|berapa|what|which|specs?|hp|phone)\b|\b(?:what|which)\s+(?:processor|cpu|chipset|soc)\b|\b(?:pakai|pake|memakai|using)\s+(?:prosesor|processor|cpu|chipset)\b|\bjumlah\s+(?:core|inti)\b""")
    )

    private fun parsePhoneInfo(raw: String): Command? {
        if (raw.split(" ").size > 12 || raw.any { it.isDigit() } || reInfoImperative.containsMatchIn(raw)) return null
        for ((topic, re) in infoRules) if (re.containsMatchIn(raw)) return Command.PhoneInfo(topic)
        return null
    }

    private fun parseDeviceInfo(raw: String): Command? = when {
        reBattery.containsMatchIn(raw) -> Command.Battery
        reTime.containsMatchIn(raw) -> Command.CurrentTime
        reDate.containsMatchIn(raw) -> Command.CurrentDate
        reDevice.containsMatchIn(raw) -> Command.DeviceInfo
        else -> null
    }

    // ------------------------------------------------------------------ ingatan jangka panjang

    private val reMemRead = rx("""^(?:apa\s+(?:saja\s+)?yang\s+(?:kamu|kau|nine|anda)\s+(?:ingat|tahu|ketahui)(?:\s+(?:tentang|soal|mengenai)\s+(?:aku|saya|gue))?|(?:tampilkan|lihat|tunjukkan|baca(?:kan)?)\s+(?:semua\s+)?(?:ingatan|memori)(?:\s*(?:mu|kamu|nine))?|ingatan\s*(?:mu|kamu)|what\s+do\s+you\s+(?:remember|know)(?:\s+about\s+me)?|(?:show|list|read)\s+(?:me\s+)?(?:my\s+|your\s+|all\s+)?(?:memory|memories)|what\s+have\s+i\s+told\s+you)\s*\??$""")
    private val reMemClear = rx("""^(?:lupakan|hapus|bersihkan|reset|forget|clear|delete|erase)\s+(?:(?:semua(?:nya)?\s+)?(?:ingatan|memori)(?:\s*(?:mu|kamu))?|semuanya(?:\s+tentang\s+(?:aku|saya|gue))?|semua\s+tentang\s+(?:aku|saya|gue)|(?:all\s+|everything\s+)?(?:about\s+me|you\s+(?:know|remember))|(?:your\s+|all\s+)?(?:memory|memories)|everything)$""")
    private val reMemForget = rx("""^(?:lupakan|hapus\s+ingatan(?:\s+tentang)?|forget)\s+(?:bahwa\s+|tentang\s+|soal\s+|about\s+|that\s+)?(.+)$""")
    private val reMemRemember = rx("""^(?:tolong\s+|please\s+)?(?:ingat(?!kan)(?:lah)?|remember|keep\s+in\s+mind)(?!\s+(?:to|untuk|nanti)\b)\b\s*(?:bahwa|kalau|kalo|that|[:,])?\s*(.+)$""")

    private fun parseMemory(raw: String): Command? {
        if (reMemRead.matches(raw)) return Command.ReadMemory
        if (reMemClear.matches(raw)) return Command.ForgetAll
        reMemForget.find(raw)?.let { m ->
            val q = m.groupValues[1].trim()
            if (q.isNotEmpty()) return Command.Forget(q)
        }
        reMemRemember.find(raw)?.let { m ->
            val t = m.groupValues[1].trim()
            if (t.isNotEmpty()) return Command.Remember(t)
        }
        return null
    }

    // ------------------------------------------------------------------ catatan

    private val reClearNotes = rx("""^(?:hapus|bersihkan|delete|clear|remove)\s+(?:semua\s+|all\s+|my\s+)*(?:catatan|notes?)$""")
    private val reReadNotes = rx("""^(?:baca(?:kan)?|tampilkan|lihat|tunjukkan|apa\s+saja|read|show|list|what\s+are)\s+(?:semua\s+|daftar\s+|my\s+|all\s+|the\s+)*(?:catatan|notes?)(?:\s+(?:saya|ku))?$|^(?:catatan\s+(?:saya|ku)|my\s+notes)$""")
    private val reAddNote = rx(
        """^(?:catat(?:kan)?|tulis(?:kan)?|simpan(?:kan)?\s+catatan|buat\s+catatan(?:\s+baru)?|tambah(?:kan)?\s+catatan|catatan\s+baru|""" +
            """note(?:\s+that)?|take\s+a\s+note(?:\s+(?:that|saying|of))?|make\s+a\s+note(?:\s+(?:that|of))?|""" +
            """add\s+(?:a\s+)?note|write\s+(?:this\s+)?down)\b\s*[:,]?\s*(.*)$"""
    )

    private fun parseNotes(raw: String): Command? {
        if (reClearNotes.matches(raw)) return Command.ClearNotes
        if (reReadNotes.matches(raw)) return Command.ReadNotes
        val m = reAddNote.find(raw) ?: return null
        val text = m.groupValues[1].trim()
        return if (text.isEmpty()) Command.NeedInfo(Pending.NoteText) else Command.AddNote(text)
    }

    // ------------------------------------------------------------------ SMS

    private val reSmsId = rx(
        """^(?:kirim(?:kan)?|buat(?:kan)?)\s+(?:sebuah\s+)?(?:pesan|sms|teks|chat)\s+(?:ke|kepada|untuk)\s+(.+?)""" +
            """(?:\s+(?:isi(?:nya)?|berisi|yang\s+berisi|bilang|katakan|bahwa|dengan\s+pesan|dengan\s+isi)\s+(.+)|\s*:\s*(.+))?$"""
    )
    private val reSmsEn1 = rx(
        """^(?:send|write)\s+(?:a\s+|an\s+)?(?:text\s+message|text|message|sms)\s+to\s+(.+?)""" +
            """(?:\s+(?:saying|that\s+says|says|that|with\s+(?:the\s+)?message)\s+(.+)|\s*:\s*(.+))?$"""
    )
    private val reSmsEn2 = rx("""^(?:text|sms|message)\s+(.+?)\s+(?:saying|that\s+says|says|that)\s+(.+)$""")

    private fun parseSms(raw: String): Command? {
        for (r in listOf(reSmsId, reSmsEn1, reSmsEn2)) {
            val m = r.find(raw) ?: continue
            val target = m.groupValues[1].trim()
            val body = (m.groupValues.getOrNull(2).orEmpty().ifEmpty { m.groupValues.getOrNull(3).orEmpty() }).trim()
            if (target.isEmpty()) continue
            return Command.SendSms(target, body.ifEmpty { null })
        }
        return null
    }

    // ------------------------------------------------------------------ telepon

    private val reCall = rx("""^(?:telepon|telpon|tlp|hubungi|panggil|call|phone|ring|dial|make\s+a\s+(?:phone\s+)?call\s+to|place\s+a\s+call\s+to)\b\s*(?:ke\s+|nomor\s+|number\s+)?(.*)$""")

    private fun parseCall(raw: String): Command? {
        val m = reCall.find(raw) ?: return null
        val target = m.groupValues[1].trim()
        return if (target.isEmpty()) Command.NeedInfo(Pending.CallTarget) else Command.Call(target)
    }

    // ------------------------------------------------------------------ buka aplikasi

    private val reOpen = rx("""^(?:buka|bukain|bukakan|bukaain|jalankan|jalanin|luncurkan|launch|open(?:\s+up)?|start|run)\b\s*(?:aplikasi\s+|app\s+|apl\s+|the\s+|my\s+)*(.*?)(?:\s+(?:app|aplikasi|application))?$""")

    private fun parseOpenApp(raw: String): Command? {
        val m = reOpen.find(raw) ?: return null
        val q = m.groupValues[1].trim()
        if (q.isEmpty()) return Command.NeedInfo(Pending.AppName)
        if (Regex("^(?:catatan|notes?)$", RegexOption.IGNORE_CASE).matches(q)) return Command.ReadNotes
        val t = targetOf(q, networkOnly = true)
        if (t != null) return Command.OpenSettings(t, null)
        return Command.OpenApp(q)
    }

    // ------------------------------------------------------------------ daftar tugas

    private val TODO_RX = """(?:daftar\s+)?(?:tugas|to[\s-]?do(?:\s+list)?|todos?(?:\s+list)?|kerjaan|pekerjaan|tasks?(?:\s+list)?|checklist)"""
    private val reTodoClearAll = rx("""^(?:hapus|bersihkan|kosongkan|clear|delete|remove|reset)\s+(?:semua\s+|all\s+|my\s+|the\s+|seluruh\s+|isi\s+)*$TODO_RX(?:\s+(?:saya|ku|aku|hari\s+ini))*$""")
    private val reTodoClearDone = rx("""^(?:hapus|bersihkan|buang|clear|remove|delete)\s+(?:semua\s+|all\s+|the\s+)?(?:$TODO_RX\s+(?:yang\s+)?(?:sudah\s+)?(?:selesai|beres|done|completed|finished)|(?:completed|done|finished)\s+$TODO_RX)$""")
    private val reTodoAdd = listOf(
        rx("""^(?:tambah(?:kan)?|buat(?:kan)?|bikin(?:in)?|catat(?:kan)?|masukkan|masukin)\s+(?:sebuah\s+|satu\s+)?(?:tugas|to[\s-]?do|todo|kerjaan|pekerjaan)(?:\s+baru)?\b\s*[:,]?\s*(.*)$"""),
        rx("""^(?:tambah(?:kan)?|masukkan|masukin|catat(?:kan)?|taruh)\s+(.+?)\s+(?:ke|di|dalam)\s+(?:dalam\s+)?$TODO_RX(?:\s+(?:saya|ku|aku|hari\s+ini))*$"""),
        rx("""^(?:tugas|to[\s-]?do|todo)\s+baru\s*[:,]?\s*(.*)$"""),
        rx("""^(?:to[\s-]?do|todo)\s*[:,]\s*(.+)$"""),
        rx("""^(?:add|put)\s+(.+?)\s+(?:to|on)\s+(?:my\s+|the\s+)?(?:to[\s-]?do(?:\s+list)?|todo(?:\s+list)?|task\s+list|tasks|list)(?:\s+for\s+today)?$"""),
        rx("""^(?:add|create|new|make)\s+(?:a\s+)?(?:new\s+)?(?:task|to[\s-]?do|todo)\b\s*[:,]?\s*(.*)$""")
    )
    private val reTodoList = listOf(
        rx("""^(?:apa\s+(?:saja\s+)?|lihat\s+|tampilkan\s+|bacakan\s+|baca\s+|cek\s+|buka\s+|tunjukkan\s+)?$TODO_RX(?:\s+(?:saya|ku|aku))?(?:\s+(?:hari\s+ini|apa\s+saja|apa|untuk\s+hari\s+ini))*$"""),
        rx("""^(?:apa\s+)?(?:saja\s+)?(?:yang\s+)?(?:harus|perlu)\s+(?:saya|aku|gue)\s+(?:kerjakan|lakukan|selesaikan)(?:\s+hari\s+ini)?$"""),
        rx("""^(?:what(?:'s|\s+is|\s+are)\s+(?:on\s+)?my\s+$TODO_RX(?:\s+(?:today|for\s+today))?|(?:show|read|list|open|check)\s+(?:me\s+)?(?:my\s+)?$TODO_RX|my\s+$TODO_RX|what\s+do\s+i\s+(?:need|have)\s+to\s+do(?:\s+today)?)$""")
    )
    private val reTodoWord = rx("""\b(?:to[\s-]?do|todos?|tugas|kerjaan|pekerjaan|checklist|daftar\s+tugas)\b""")
    private val reTodoShowCue = rx("""^(?:tampilkan|tunjukkan|tunjukin|lihat(?:kan)?|munculkan|show|display|bacakan|baca|cek|buka|kasih\s+(?:lihat|tau)|apa|ada|what|read|list|my|what's)\b""")
    private val reTodoEdit = rx("""\b(?:tambah(?:kan)?|buat(?:kan)?|bikin|hapus|buang|catat|masukkan|selesai(?:kan)?|beres|centang|ceklis|tandai|coret|add|remove|delete|clear|mark|done|finish|create|new|baru|kosongkan|bersihkan|ingatkan|ingat|remind|alarm|pengingat|jam|besok|lusa|nanti|depan)\b""")
    private val reTodoDone = listOf(
        rx("""^(?:tandai|centang|ceklis|checklist|selesaikan|coret)\s+(?:tugas\s+)?(.+?)(?:\s+(?:sudah\s+|sebagai\s+|jadi\s+)?(?:selesai|beres))?$"""),
        rx("""^(?:tugas\s+)(.+?)\s+(?:sudah\s+|udah\s+)?(?:selesai|beres)$"""),
        rx("""^(?:sudah\s+|udah\s+)?(?:selesai|beres)\s+tugas\s+(.+)$"""),
        rx("""^(?:mark|check\s+off|tick\s+off|cross\s+off|complete|finish)\s+(?:task\s+)?(.+?)(?:\s+as)?(?:\s+(?:done|complete(?:d)?|finished))?$""")
    )
    private val reTodoRemove = listOf(
        rx("""^(?:hapus|buang|delete|remove)\s+(?:tugas|task|to[\s-]?do)\s+(.+?)(?:\s+(?:dari\s+(?:daftar\s+)?(?:tugas)?|from\s+(?:my\s+)?(?:list|to[\s-]?do\s+list)))?$"""),
        rx("""^(?:hapus|buang)\s+(.+?)\s+dari\s+$TODO_RX$"""),
        rx("""^(?:remove|delete)\s+(.+?)\s+from\s+(?:my\s+|the\s+)?(?:to[\s-]?do(?:\s+list)?|todo(?:\s+list)?|tasks|list)$""")
    )

    private fun todoRef(s: String): String {
        var t = NumberWords.normalize(s.trim()).lowercase()
        t = t.replace(Regex("""^(?:nomor|number|no\.?|#|yang|ke-?|the)\s*"""), "").trim()
        val ord = mapOf(
            "pertama" to "1", "kedua" to "2", "ketiga" to "3", "keempat" to "4", "kelima" to "5",
            "keenam" to "6", "ketujuh" to "7", "kedelapan" to "8", "kesembilan" to "9", "kesepuluh" to "10",
            "first" to "1", "second" to "2", "third" to "3", "fourth" to "4", "fifth" to "5",
            "sixth" to "6", "seventh" to "7", "eighth" to "8", "ninth" to "9", "tenth" to "10",
            "terakhir" to "-1", "last" to "-1", "one" to "1",
            "tadi" to "-1", "itu" to "-1", "barusan" to "-1", "that" to "-1", "it" to "-1", "that one" to "-1",
            "the last one" to "-1", "last one" to "-1"
        )
        ord[t]?.let { return it }
        Regex("""^(\d{1,2})(?:st|nd|rd|th)?$""").find(t)?.let { return it.groupValues[1] }
        return s.trim()
    }

    private fun parseTodo(raw: String): Command? {
        if (reTodoClearDone.matches(raw)) return Command.TodoClearDone
        if (reTodoClearAll.matches(raw)) return Command.TodoClearAll
        for (r in reTodoList) if (r.matches(raw)) return Command.TodoList
        // ucapan bebas: "tampilkan to do list hari ini yang sudah aku buat", "lihat tugasku dong"
        if (raw.split(" ").size <= 9 && reTodoShowCue.containsMatchIn(raw) && reTodoWord.containsMatchIn(raw) && !reTodoEdit.containsMatchIn(raw.replace(Regex("""yang\s+sudah\s+(?:aku\s+|saya\s+|kamu\s+)?(?:buat|catat)""", RegexOption.IGNORE_CASE), ""))) {
            return Command.TodoList
        }
        for (r in reTodoAdd) {
            val m = r.find(raw) ?: continue
            val text = m.groupValues[1].trim().trim(',', '.', ':')
            return if (text.isEmpty()) Command.NeedInfo(Pending.TodoText) else Command.TodoAdd(text)
        }
        for (r in reTodoRemove) {
            val m = r.find(raw) ?: continue
            return Command.TodoRemove(todoRef(m.groupValues[1]))
        }
        for (r in reTodoDone) {
            val m = r.find(raw) ?: continue
            val ref = m.groupValues[1].trim()
            if (ref.isEmpty()) continue
            return Command.TodoDone(todoRef(ref))
        }
        return null
    }

    // ------------------------------------------------------------------ kalender

    private val reEventAdd = listOf(
        rx("""^(?:tambah(?:kan)?|buat(?:kan)?|bikin(?:in)?|catat(?:kan)?|masukkan|masukin|simpan|atur|set)\s+(?:sebuah\s+|satu\s+)?(acara|jadwal|agenda|event|janji(?:\s+temu)?|rapat|meeting)\b(?:\s+baru)?\s*[:,]?\s*(.*)$"""),
        rx("""^(?:jadwalkan|agendakan)\s+()(.*)$"""),
        rx("""^(?:schedule|add|create|make|put|set\s+up|book)\s+(?:an?\s+|new\s+|my\s+)*(event|appointment|meeting|calendar\s+event)\b\s*[:,]?\s*(.*)$"""),
        rx("""^(?:schedule|book)\s+()(.+)$""")
    )
    private val reEventToCalendar = listOf(
        rx("""^(?:tambah(?:kan)?|masukkan|masukin|catat(?:kan)?|simpan|taruh|set)\s+(.+?)\s+(?:ke|di)\s+(?:dalam\s+)?kalend[ae]r(?:\s+(.*))?$"""),
        rx("""^(?:add|put|save|set|schedule|insert)\s+(.+?)\s+(?:to|on|in|into)\s+(?:my\s+|the\s+)?calend[ae]rs?(?:\s+(.*))?$""")
    )
    private val reCalendarWords = rx("""\s*\b(?:ke|di|to|on|in|into)\s+(?:dalam\s+)?(?:my\s+|the\s+)?(?:kalend[ae]r|calend[ae]rs?)\b\s*""")
    private val reAgendaWord = rx("""\b(?:what\s+do\s+i\s+have|jadwal|agenda|acara|kegiatan|kalender|calendar|schedule|events?|appointments?|meetings?|rapat)\b""")
    private val reAgendaCue = rx("""^(?:apa|ada|cek|lihat|tampilkan|bacakan|baca|tunjukkan|gimana|bagaimana|what|show|check|read|any|do\s+i\s+have|my|jadwal|agenda|acara|kegiatan)\b""")
    private val reAgendaAsk = rx("""\b(?:apa|ada|cek|lihat|tampilkan|bacakan|baca|tunjukkan|gimana|bagaimana|what|show|check|read|any|do\s+i\s+have)\b""")
    private val reAgendaFiller = rx("""\b(?:s|apa|ada|saja|aja|cek|lihat|tampilkan|bacakan|baca|tunjukkan|saya|aku|ku|gue|hari|untuk|ya|di|pada|jadwal|agenda|acara|kegiatan|kalender|calendar|schedule|events?|appointments?|meetings?|rapat|what|whats|what's|is|are|on|my|the|show|me|check|read|any|do|i|have|for|today|tomorrow|gimana|bagaimana|kosong|nggak|tidak)\b""")
    private val reDurationFor = rx("""\b(?:selama|for)\s+""")
    private val reUntil = rx("""\b(?:sampai|hingga|until|till)\s+(?:jam\s+|pukul\s+)?(\d{1,2})(?:[.:](\d{2}))?(?:\s*(am|pm))?""")
    private val reTitleLead = rx("""^(?:(?:untuk|tentang|dengan\s+judul|berjudul|judulnya|namanya|called|named|titled|about|for|acara|event|baru|new)\s+)+""")
    private val reTitleTrail = rx("""(?:\s+(?:pada|at|on|di|for|untuk|dan|and))+$""")

    private fun tidyTitle(t: String): String {
        var x = reCalendarWords.replace(t, " ").replace(Regex("\\s+"), " ").trim().trim(',', '.', ':')
        x = reTitleLead.replaceFirst(x, "")
        x = reTitleTrail.replaceFirst(x, "")
        return x.trim().trim(',', '.', ':')
    }

    /** Membuat acara dari judul + teks waktu. Null bila waktunya tidak bisa dipahami. */
    internal fun buildEvent(title: String, timeText: String, now: LocalDateTime): Command? {
        var text = NumberWords.normalize(timeText)
        // durasi: "selama 2 jam" / "for 90 minutes"
        var durationSec: Int? = null
        reDurationFor.find(text)?.let { f ->
            val rest = text.substring(f.range.last + 1)
            val d = Durations.find(rest)
            if (d != null && d.range.first == 0) {
                durationSec = d.seconds
                text = text.substring(0, f.range.first) + " " + rest.substring(d.range.last + 1)
            }
        }
        // jam selesai: "sampai jam 5"
        var untilH: Int? = null
        var untilM = 0
        reUntil.find(text)?.let { u ->
            untilH = u.groupValues[1].toInt()
            untilM = u.groupValues[2].ifEmpty { "0" }.toInt()
            val ap = u.groupValues[3].lowercase()
            if (ap == "pm" && untilH!! < 12) untilH = untilH!! + 12
            text = text.removeRange(u.range)
        }
        val tp = TimeParser.parse(text) ?: return null
        val spec = tp.spec
        val rawTitle = (title + " " + tp.remainder).trim()
        val name = tidyTitle(rawTitle)
        if (spec.relativeSeconds != null) {
            val start = now.plusSeconds(spec.relativeSeconds.toLong())
            return Command.AddEvent(name, start, durationSec?.let { start.plusSeconds(it.toLong()) }, false)
        }
        if (spec.hour == null) {
            if (!spec.hasDay) return null
            val d = spec.resolveDate(now) ?: return null
            return Command.AddEvent(name, d.atStartOfDay(), null, true)
        }
        val start = spec.resolve(now) ?: return null
        var end: LocalDateTime? = durationSec?.let { start.plusSeconds(it.toLong()) }
        val uh = untilH
        if (end == null && uh != null && uh in 0..23) {
            var h = uh
            if (h <= 12 && h + 12 > start.hour && h < start.hour) h += 12
            val e = start.toLocalDate().atTime(h % 24, untilM.coerceIn(0, 59))
            if (e.isAfter(start)) end = e
        }
        return Command.AddEvent(name, start, end, false)
    }

    private val LASTREF = """(?:(?:yang\s+)?(?:tadi|terakhir|itu|barusan|sebelumnya)|that|it|the\s+last\s+one|last\s+one|the\s+last|last)"""
    private val reEventMove = rx("""^(?:ubah|ganti|pindah(?:kan)?|geser|jadwalkan\s+ulang|reschedule|move|change)\s+(?:waktu(?:nya)?\s+)?(?:$LASTREF\s+)?(?:(?:acara|jadwal|rapat|event|meeting|agenda|janji)\s*)?(?:$LASTREF\s*)?(?:ke|jadi|menjadi|to|for)\s+(.+)$""")
    private val reEventShift = rx("""^(undur(?:kan)?|mundurkan|tunda|majukan|percepat|push\s+back|delay|postpone|move\s+up|bring\s+forward)\s+(?:(?:acara|jadwal|rapat|event|meeting|agenda)\s*)?(?:$LASTREF\s*)?(?:by\s+|selama\s+)?(.+)$""")
    private val reEventDelete = rx("""^(?:hapus|batalkan|cancel|delete|remove)\s+(?:(?:acara|jadwal|rapat|event|meeting|agenda|janji)(?:\s+$LASTREF)?|$LASTREF\s+(?:event|meeting|acara|jadwal))$""")

    private fun parseEventFollowUp(raw: String): Command? {
        if (reEventDelete.matches(raw)) return Command.DeleteLastEvent
        reEventShift.find(raw)?.let { m ->
            val secs = Durations.parseSeconds(NumberWords.normalize(m.groupValues[2])) ?: return@let
            val verb = m.groupValues[1].lowercase()
            val earlier = verb.startsWith("maju") || verb.startsWith("percepat") || verb.startsWith("move") || verb.startsWith("bring")
            return Command.ShiftLastEvent(if (earlier) -secs else secs)
        }
        reEventMove.find(raw)?.let { m ->
            val t = m.groupValues[1].trim()
            if (TimeParser.parse(NumberWords.normalize(t)) != null) return Command.MoveLastEvent(t)
        }
        return null
    }

    private val reDelVerb = rx("""^(?:hapus(?:kan)?|batalkan|cancel|delete|remove|buang|hilangkan|clear)\s+(.+)$""")
    private val reCalCue = rx("""\b(?:acara|jadwal|agenda|events?|janji(?:\s+temu)?|rapat|meetings?|appointments?|pengingat|reminders?|kalend[ae]r|calend[ae]rs?)\b""")
    private val reFromCal = rx("""\s*\b(?:dari|from|di|in|on)\s+(?:dalam\s+)?(?:my\s+|the\s+)?(?:kalend[ae]r|calend[ae]rs?)(?:\s+(?:saya|ku|aku))?\b""")
    private val reKindWords = rx("""\b(?:acara|jadwal|agenda|events?|janji\s+temu|janji|pengingat|reminders?|kalend[ae]r|calend[ae]rs?|my|the|saya|ku|aku|yang|ada|di|pada|on|at|for|untuk|tentang|about|called|bernama|berjudul|judulnya|an?)\b""")

    /** "hapus acara rapat RNPS besok", "batalkan meeting dengan Budi jam 3", "delete the dentist appointment". */
    private fun parseDeleteEvent(raw: String, now: LocalDateTime): Command? {
        val m = reDelVerb.find(raw) ?: return null
        var rest = m.groupValues[1]
        if (!reCalCue.containsMatchIn(rest)) return null
        rest = reFromCal.replace(rest, " ")
        val norm = NumberWords.normalize(rest)
        val tp = TimeParser.parse(norm)
        var date: java.time.LocalDate? = null
        var hour: Int? = null
        var minute: Int? = null
        var amb = false
        var q = norm
        if (tp != null && (tp.spec.hasDay || tp.spec.hour != null)) {
            if (tp.spec.hasDay) date = tp.spec.resolveDate(now)
            if (tp.spec.hour != null) {
                hour = tp.spec.hour
                minute = tp.spec.minute
                amb = tp.spec.ambiguous
            }
            q = tp.remainder
        }
        q = reKindWords.replace(q, " ").replace(Regex("\\s+"), " ").trim().trim(',', '.', ':', '"', '\'')
        if (q.isEmpty() && date == null && hour == null) return null
        return Command.DeleteEvent(q, date, hour, minute, amb)
    }

    /** "delete the RNPS agreement" (tanpa kata acara): mungkin judul acara; diperiksa ke kalender saat dijalankan. */
    private fun parseDeleteLoose(raw: String, now: LocalDateTime): Command? {
        val m = reDelVerb.find(raw) ?: return null
        val rest = m.groupValues[1].trim()
        if (rest.split(Regex("\\s+")).size > 8) return null
        val norm = NumberWords.normalize(rest)
        val tp = TimeParser.parse(norm)
        var date: java.time.LocalDate? = null
        var hour: Int? = null
        var minute: Int? = null
        var amb = false
        var q = norm
        if (tp != null && (tp.spec.hasDay || tp.spec.hour != null)) {
            if (tp.spec.hasDay) date = tp.spec.resolveDate(now)
            if (tp.spec.hour != null) {
                hour = tp.spec.hour
                minute = tp.spec.minute
                amb = tp.spec.ambiguous
            }
            q = tp.remainder
        }
        q = reKindWords.replace(q, " ").replace(Regex("\\s+"), " ").trim().trim(',', '.', ':', '"', '\'')
        if (EventMatch.keyTokens(q).isEmpty()) return null
        return Command.DeleteMaybeEvent(raw, Command.DeleteEvent(q, date, hour, minute, amb))
    }

    private fun parseCalendar(raw: String, now: LocalDateTime): Command? {
        parseEventFollowUp(raw)?.let { return it }
        parseDeleteEvent(raw, now)?.let { return it }
        // 1) "tambahkan rapat ke kalender besok jam 9"
        for (r in reEventToCalendar) {
            val m = r.find(raw) ?: continue
            val payload = (m.groupValues[1] + " " + m.groupValues.getOrElse(2) { "" }).trim()
            return eventOrAsk(payload, now)
        }
        // 2) "buat acara ...", "jadwalkan ...", "schedule a meeting ..."
        for (r in reEventAdd) {
            val m = r.find(raw) ?: continue
            val kind = m.groupValues[1].lowercase()
            val keepKind = kind == "rapat" || kind == "meeting" || kind.startsWith("janji") || kind == "appointment"
            val payload = (if (keepKind) "${m.groupValues[1]} " else "") + m.groupValues[2]
            return eventOrAsk(payload.trim(), now)
        }
        // 3) agenda: "jadwal saya besok apa saja", "what's on my calendar tomorrow"
        if (reAgendaWord.containsMatchIn(raw) && reAgendaCue.containsMatchIn(raw)) {
            val norm = NumberWords.normalize(raw)
            val tp = TimeParser.parse(norm)
            val leftover = reAgendaFiller.replace((tp?.remainder ?: norm).replace(Regex("['’]"), " "), " ")
                .replace(Regex("[?.,!\\s]+"), " ").trim()
            val asks = reAgendaAsk.containsMatchIn(raw)
            if (leftover.isEmpty() && (asks || tp?.spec?.hour == null)) {
                val date = tp?.spec?.resolveDate(now) ?: now.toLocalDate()
                return Command.ShowAgenda(date)
            }
            // "jadwal rapat besok jam 10" → anggap menambah acara bila ada waktu
            if (!asks && Regex("^(?:jadwal|agenda|acara)\\b", RegexOption.IGNORE_CASE).containsMatchIn(raw) && tp != null && (tp.spec.hour != null || tp.spec.hasDay)) {
                val payload = raw.replaceFirst(Regex("^(?:jadwal|agenda|acara)\\s*", RegexOption.IGNORE_CASE), "")
                return eventOrAsk(payload, now)
            }
        }
        return null
    }

    private fun eventOrAsk(payload: String, now: LocalDateTime): Command {
        val ev = buildEvent("", payload, now)
        if (ev != null) return ev
        val title = tidyTitle(NumberWords.normalize(payload))
        return Command.NeedInfo(Pending.EventTime(title))
    }

    // ------------------------------------------------------------------ WhatsApp

    private val WA = """(?:wa|whatsapp|whats\s+app|watsap|wasap|whatsap)"""
    private val reWa = listOf(
        rx("""^(?:kirim(?:kan|in)?\s+)?(?:pesan\s+|chat\s+)?$WA\s+(?:ke\s+|kepada\s+|untuk\s+|to\s+)?(.+?)(?:\s+(?:isi(?:nya)?|berisi|yang\s+berisi|bilang|katakan|bahwa|dengan\s+pesan|saying|that\s+says)\s+(.+)|\s*:\s*(.+))?$"""),
        rx("""^(?:kirim(?:kan|in)?|chat)\s+(?:pesan\s+)?(?:ke\s+|kepada\s+)?(.+?)\s+(?:lewat|via|pakai|di)\s+$WA(?:\s+(?:isi(?:nya)?|berisi|bilang|katakan|bahwa)\s+(.+)|\s*:\s*(.+))?$"""),
        rx("""^(?:send\s+(?:a\s+)?)?$WA(?:\s+message)?\s+to\s+(.+?)(?:\s+(?:saying|that\s+says|that)\s+(.+)|\s*:\s*(.+))?$"""),
        rx("""^(?:send\s+(?:a\s+)?)?(?:message|text)\s+to\s+(.+?)\s+on\s+$WA(?:\s+(?:saying|that\s+says|that)\s+(.+))?()$""")
    )

    private fun parseWhatsApp(raw: String): Command? {
        for (r in reWa) {
            val m = r.find(raw) ?: continue
            val target = m.groupValues[1].trim()
            if (target.isEmpty()) continue
            val body = (m.groupValues.getOrElse(2) { "" }.ifEmpty { m.groupValues.getOrElse(3) { "" } }).trim()
            return Command.WhatsApp(target, body.ifEmpty { null })
        }
        return null
    }

    // ------------------------------------------------------------------ aplikasi eksternal

    private val MEDIA_APP = """(youtube\s+music|yt\s+music|youtube|yt|you\s+tube|spotify|joox|apple\s+music|resso|soundcloud)"""
    private val rePlay = rx("""^(?:putar(?:kan)?|puterin|setel(?:in)?|mainkan|play|listen\s+to|dengarkan|dengerin|tonton|nonton|watch)\b\s*(?:lagu|musik|music|song|video|film|playlist|album|podcast|some)?\s*(.*?)(?:\s+(?:di|on|pakai|lewat|via|in|dari|from)\s+$MEDIA_APP)?$""")
    private val reSearchIn = rx("""^(?:cari(?:kan)?|carikan|search(?:\s+for)?|find|look\s+up)\s+(.+?)\s+(?:di|on|in|pakai|lewat)\s+(youtube\s+music|yt\s+music|youtube|yt|you\s+tube|spotify|google\s+maps|maps|map|peta|google|internet|web|browser|chrome)$""")
    private val reNav = rx("""^(?:navigasi(?:kan)?|arahkan(?:\s+(?:saya|aku))?|antar(?:kan|in)?\s+(?:saya|aku|gue)|tunjukkan\s+(?:jalan|arah|rute)|petunjuk\s+arah|rute|jalan|directions?|navigate|take\s+me|get\s+directions|how\s+do\s+i\s+get|drive)\s+(?:ke|menuju|to|for)\s+(.+)$""")
    private val reWeb = listOf(
        rx("""^(?:google(?:ing)?|googlein|telusuri)\s+(.+)$"""),
        rx("""^search\s+(?:the\s+)?(?:web|internet|google)\s+for\s+(.+)$""")
    )
    private val reUrl = rx("""^(?:buka|bukain|open|kunjungi|visit|go\s+to)\s+(?:situs|website|web|laman|halaman|site|the\s+website)?\s*((?:https?://)?[a-z0-9-]+(?:\s*(?:\.|\bdot\b|\btitik\b)\s*[a-z0-9-]+)+(?:/\S*)?)$""")

    private val reVideoIntent = rx("""^(?:tonton|nonton|watch)\b|^(?:putar(?:kan)?|puterin|play|setel(?:in)?|mainkan)\s+(?:video|film|vidio)\b""")

    private fun mediaApp(w: String?): String? {
        val a = w?.lowercase()?.replace(Regex("\\s+"), " ") ?: return null
        return when {
            a.contains("music") && (a.startsWith("yt") || a.startsWith("youtube")) -> "youtube music"
            a.startsWith("y") -> "youtube"
            else -> a
        }
    }

    private fun parseExternal(raw: String): Command? {
        parseMaps(raw)?.let { return it }
        reUrl.find(raw)?.let { m ->
            val url = m.groupValues[1].lowercase()
                .replace(Regex("\\s*\\b(?:dot|titik)\\b\\s*"), ".").replace(Regex("\\s*\\.\\s*"), ".").replace(" ", "")
            if (url.contains('.')) return Command.OpenUrl(url)
        }
        reSearchIn.find(raw)?.let { m ->
            val q = m.groupValues[1].trim()
            val where = m.groupValues[2].lowercase()
            return when {
                where.startsWith("y") && where.contains("music") -> Command.PlayMedia(q, "youtube music")
                where.startsWith("y") || where.startsWith("you") -> Command.PlayMedia(q, "youtube")
                where == "spotify" -> Command.PlayMedia(q, "spotify")
                where.contains("map") || where == "peta" -> Command.Navigate(q, drive = false)
                else -> Command.WebSearch(q)
            }
        }
        reNav.find(raw)?.let { m -> return Command.Navigate(m.groupValues[1].trim(), drive = true) }
        for (r in reWeb) r.find(raw)?.let { m -> return Command.WebSearch(m.groupValues[1].trim()) }
        rePlay.find(raw)?.let { m ->
            val q = m.groupValues[1].trim()
            var app = mediaApp(m.groupValues[2].ifEmpty { null })
            // Tanpa aplikasi disebut: lagu/musik ke YouTube Music, tetapi "tonton/putar video/film" ke YouTube.
            if (app == null && reVideoIntent.containsMatchIn(raw)) app = "youtube"
            // "putar youtube" tanpa kata kunci = buka aplikasinya
            if (q.isEmpty() && app != null) return Command.OpenApp(app)
            if (app == null && Regex("^$MEDIA_APP$", RegexOption.IGNORE_CASE).matches(q)) return Command.OpenApp(q)
            return Command.PlayMedia(q, app)
        }
        return null
    }

    // ------------------------------------------------------------------ affinity & lokasi

    private val reAffinity = rx(
        """\b(?:streak|affinity|afinitas|tingkat\s+kedekatan|level\s+kedekatan|""" +
            """(?:status|progres|progress|kemajuan|poin|skor|score|xp|level)\s+(?:kedekatan|affinity|kita|hubungan(?:\s+kita)?)|""" +
            """kedekatan\s+(?:kita|ku|saya|aku)|seberapa\s+dekat\s+(?:kita|aku|saya)|how\s+close\s+are\s+we|""" +
            """(?:our|my)\s+(?:level|affinity|streak|xp)|level\s+(?:kita|aku|saya|ku)|level\s+berapa|sudah\s+level\s+berapa|""" +
            """level\s+apa|(?:daftar|list|tangga|semua|apa\s+saja)\s+level|what\s+level\s+(?:are\s+we|am\s+i)|affinity\s+levels?)\b"""
    )

    private fun parseAffinity(raw: String): Command? = if (reAffinity.containsMatchIn(raw)) Command.AffinityStatus else null

    // ------------------------------------------------------------------ mentor bahasa Inggris

    private fun parseCoach(raw: String): Command? = when (val i = CoachIntents.detect(raw)) {
        is CoachIntent.Start -> Command.EnglishCoach(true)
        is CoachIntent.Stop -> Command.EnglishCoach(false)
        is CoachIntent.Correct -> Command.CorrectEnglish(i.sentence)
        is CoachIntent.BetterWord -> Command.BetterWord(i.word)
        else -> null
    }

    // ------------------------------------------------------------------ pelatih fitness + ahli gizi

    private fun parseHealth(raw: String): Command? = id.nine.assistant.fit.HealthIntent.parse(raw)?.let { Command.Health(it) }

    // ------------------------------------------------------------------ teman curhat

    private fun parseSupport(raw: String): Command? =
        SupportIntent.parse(raw)?.let { Command.Support(it.on, it.exercise) }

    private val reWhere = rx("""^(?:(?:aku|saya|gue)\s+(?:lagi\s+|sedang\s+)?(?:di\s+mana|dimana)|(?:di\s+mana|dimana)\s+(?:aku|saya|gue|posisi\s+(?:aku|saya))(?:\s+sekarang)?|lokasi\s+(?:aku|saya|ku)(?:\s+sekarang)?|posisi\s+(?:aku|saya)(?:\s+sekarang)?|where\s+am\s+i(?:\s+(?:now|right\s+now))?|what(?:'s|\s+is)\s+my\s+(?:location|current\s+location|address))$""")

    private fun parseLocation(raw: String): Command? = if (reWhere.matches(raw)) Command.WhereAmI else null

    // ------------------------------------------------------------------ perekam suara & kamera

    private val reRecStart = rx("""^(?:mulai\s+|tolong\s+)?(?:rekam|merekam|rekamkan|rekamin)(?:\s+(?:suara|audio|voice|memo(?:\s+suara)?|pembicaraan|percakapan|rapat|ini))?$|^(?:nyalakan|aktifkan|hidupkan|mulai|jalankan)\s+(?:perekam(?:\s+suara)?|rekaman(?:\s+suara)?|voice\s+recorder?)$|^(?:start\s+)?(?:a\s+)?(?:voice\s+)?(?:recording|record)(?:\s+(?:audio|voice|a\s+voice\s+memo|my\s+voice|a\s+memo|this|the\s+meeting))?$|^(?:start\s+)?(?:a\s+)?voice\s+(?:memo|note|recording)$""")
    private val reRecStop = rx("""^(?:stop|berhenti|hentikan|selesai|akhiri|matikan|stop\s+the)\s+(?:merekam|rekam(?:an)?(?:\s+suara)?|perekam(?:\s+suara)?|recording|record)$|^(?:stop|end|finish)\s+(?:the\s+)?(?:recording|voice\s+memo)$""")

    private fun parseRecorder(raw: String): Command? = when {
        reRecStop.matches(raw) -> Command.RecordAudio(false)
        reRecStart.matches(raw) -> Command.RecordAudio(true)
        else -> null
    }

    private val reCamVideo = rx("""^(?:mulai\s+|tolong\s+)?(?:rekam|merekam|ambil|buat|bikin|take|record|shoot|start(?:\s+recording)?|capture)\s+(?:sebuah\s+|a\s+)?(?:video|vidio)\b|^(?:buka|nyalakan|aktifkan|open|start)\s+(?:the\s+)?(?:kamera\s+video|video\s+camera|mode\s+video|video\s+mode)\b""")
    private val reCamPhoto = rx("""^(?:ambil|jepret|potret|take|snap|capture)\s+(?:sebuah\s+|a\s+|satu\s+)?(?:foto|gambar|photo|picture|pic|selfie|swafoto)\b|^(?:foto(?:in|kan)?|selfie|swafoto)(?:\s+(?:aku|saya|dong|sekarang|ya|kita))*(?:\s+(?:dalam|in)\s+\d{1,2}\s*(?:detik|seconds?))?$|^(?:buka|nyalakan|aktifkan|open|start|launch)\s+(?:the\s+)?(?:kamera|camera)(?:\s+(?:depan|belakang|foto|front|selfie|photo))?\s*$""")
    private val reFront = rx("""\b(?:selfie|swafoto|kamera\s+depan|front(?:\s+camera)?|depan)\b""")
    private val reCamTimer = rx("""\b(?:dalam|in|after|setelah|timer)\s+(\d{1,2})\s*(?:detik|seconds?|secs?|s)\b""")

    private fun parseCamera(norm: String): Command? {
        val video = reCamVideo.containsMatchIn(norm)
        val photo = !video && reCamPhoto.containsMatchIn(norm)
        if (!video && !photo) return null
        val timer = reCamTimer.find(norm)?.groupValues?.get(1)?.toIntOrNull()?.coerceIn(1, 30)
        return Command.Camera(video, reFront.containsMatchIn(norm), timer)
    }


    // ------------------------------------------------------------------ hitung, tutup aplikasi, hiburan, nama panggilan, peta

    private fun parseCalc(raw: String): Command? = if (Calc.isMath(raw)) Command.Calculate(raw) else null

    private val reCloseVerb = rx("""\b(?:tutup(?:kan|in)?|matikan|matiin|close|kill|hentikan|bersihkan|clear|stop|bunuh|akhiri|force\s*stop|exit|quit|singkirkan)\b""")
    private val reCloseAll = rx("""\b(?:semua|semuanya|seluruh|segala|all|every|everything|setiap|tiap)\b""")
    /** "tutup semua termasuk Nine" / "close everything including Nine": tanpa kata "aplikasi", asal Nine disebut jelas. */
    private val reCloseSelfNamed = rx("""\b(?:termasuk|including|include|dan|and|plus|sama)\s+(?:nine|kamu|dirimu|diri\s*mu|yourself)\b|\b(?:nine|kamu)\s+juga\b""")
    private val reCloseApps = rx("""\b(?:aplikasi|apk|app|apps|aplikasinya|program|recent|recents|latar\s+belakang|background)\b""")
    private val reCloseSelf = rx("""\b(?:termasuk|including|include|dan\s+(?:kamu|dirimu|nine|diri\s*mu)|kamu\s+juga|nine\s+juga|dirimu|diri\s*mu|diri\s+kamu|yourself|itself|dirinya|dirinya\s+sendiri)\b""")
    private val reCloseNotSelf = rx("""\b(?:kecuali|selain|except|but\s+not|without|jangan\s+(?:tutup\s+)?(?:kamu|nine|dirimu))\b""")

    private fun parseCloseApps(raw: String): Command? {
        if (raw.split(" ").size > 16) return null
        if (!reCloseVerb.containsMatchIn(raw) || !reCloseAll.containsMatchIn(raw)) return null
        if (!reCloseApps.containsMatchIn(raw) && !reCloseSelfNamed.containsMatchIn(raw)) return null
        val self = reCloseSelf.containsMatchIn(raw) && !reCloseNotSelf.containsMatchIn(raw)
        return Command.CloseApps(self)
    }

    private val reGombal = rx("""\b(?:gombal(?:in|kan|an|nya)?|ngegombal|nge-?gombal|rayu(?:an|in|kan)?|kata[-\s]?kata\s+(?:cinta|manis|romantis|gombal)|pick\s*up\s*lines?|flirt(?:ing)?|sweet\s+talk|romantic\s+lines?)\b""")
    private val rePantun = rx("""\b(?:pantun|berpantun|berpantunlah)\b""")
    private val reWisdom = rx("""\b(?:kata[-\s]?kata\s+(?:bijak|mutiara|motivasi|semangat|inspirasi|hari\s+ini)|quotes?|nasihat|petuah|wisdom|motivasi|motivate\s+me|inspire\s+me|peribahasa|pepatah|bijak(?:an)?|words\s+of\s+wisdom)\b""")
    private val reFunDefine = rx("""^(?:apa\s+(?:itu|arti|pengertian|maksud|bedanya)|what\s+(?:is|does)\s+(?:a|an|the)?|jelaskan|explain|siapa|sejarah)\b""")
    private val reFunTopic = rx("""\b(?:tentang|soal|mengenai|about|regarding|bertema|tema)\b""")

    private fun parseFun(raw: String): Command? {
        if (raw.split(" ").size > 10 || reFunDefine.containsMatchIn(raw) || reFunTopic.containsMatchIn(raw)) return null
        return when {
            reGombal.containsMatchIn(raw) -> Command.Entertain(FunKind.GOMBAL)
            rePantun.containsMatchIn(raw) -> Command.Entertain(FunKind.PANTUN)
            reWisdom.containsMatchIn(raw) -> Command.Entertain(FunKind.WISDOM)
            else -> null
        }
    }

    private val reSetName = rx("""^(?:panggil|sebut|call)\s+(?:aku|saya|gue|gw|me)\s+(?:dengan\s+|sebagai\s+|pakai\s+|as\s+)?([^\d]{2,24})$""")

    private fun parseSetName(raw: String): Command? {
        val m = reSetName.find(raw) ?: return null
        val name = m.groupValues[1].trim().trim('"', '\'', '.', ',')
        if (name.isEmpty() || name.split(" ").size > 3) return null
        if (Regex("""\b(?:nanti|besok|jam|lagi|dong|ya|sekarang|nya)\b""", RegexOption.IGNORE_CASE).containsMatchIn(name)) return null
        return Command.SetCallName(name.split(" ").joinToString(" ") { w -> w.replaceFirstChar { it.uppercase() } })
    }

    // --- Google Maps
    private val reNavStrong = rx("""\b(?:navigasi(?:kan)?|arahkan|antar(?:kan|in)?|pandu(?:kan)?|petunjuk\s+arah|rute|route|directions?|navigate|take\s+me|drive\s+me|cari(?:kan)?\s+(?:jalan|rute|arah)|tunjuk(?:kan|in)?\s+(?:jalan|arah|rute)|cara\s+(?:ke|menuju|pergi\s+ke)|how\s+(?:do\s+i\s+get|to\s+get)|bawa\s+(?:aku|saya|gue)\s+ke)\b""")
    private val reMapsWord = rx("""\b(?:google\s*maps?|gmaps?|g-?maps?|maps?|peta)\b""")
    private val reMapsApp = rx("""\b(?:google\s*maps?|gmaps?|g-?maps?|maps?)\b""")
    private val reNavPreview = rx("""\b(?:rute|route|directions?|petunjuk\s+arah|cara\s+(?:ke|menuju|pergi\s+ke)|how\s+(?:do\s+i\s+get|to\s+get)|tunjuk(?:kan|in)?\s+(?:jalan|arah|rute)|cari(?:kan)?\s+(?:jalan|rute|arah))\b""")
    private val reNavGo = rx("""\b(?:navigasi(?:kan)?|antar(?:kan|in)?|navigate|mulai|start|ayo|yuk|sekarang|langsung)\b""")
    private val modeWalk = rx("""\b(?:jalan\s+kaki|berjalan(?:\s+kaki)?|walking|on\s+foot|by\s+foot|dengan\s+berjalan)\b""")
    private val modeBike2 = rx("""\b(?:naik\s+motor|pakai\s+motor|pake\s+motor|dengan\s+motor|sepeda\s+motor|by\s+motorcycle|motorcycle|motorbike|two[-\s]?wheeler|bermotor)\b""")
    private val modeCycle = rx("""\b(?:naik\s+sepeda|pakai\s+sepeda|pake\s+sepeda|dengan\s+sepeda|bersepeda|by\s+bike|cycling|bicycle)\b""")
    private val modeTransit = rx("""\b(?:naik\s+(?:bus|kereta|angkot|transjakarta|krl|mrt|lrt|umum)|angkutan\s+umum|transportasi\s+umum|transit|public\s+transport(?:ation)?|by\s+(?:bus|train))\b""")
    private val modeCar = rx("""\b(?:naik\s+mobil|pakai\s+mobil|pake\s+mobil|dengan\s+mobil|by\s+car|driving|nyetir|menyetir)\b""")
    private val reAvoidTolls = rx("""\b(?:tanpa|hindari|menghindari|tidak\s+lewat|jangan\s+lewat|avoid|bebas)\s+(?:jalan\s+)?tol\b|\bno\s+tolls?\b|\bavoid\s+tolls?\b""")
    private val reNavTail = rx("""(?:\s+(?:sekarang|dong|ya|aja|saja|please|tolong|dulu|nih|deh|yuk|sih|ya\s+nine|nine))+$""")

    private fun parseMaps(raw: String): Command? {
        val strong = reNavStrong.containsMatchIn(raw)
        val maps = reMapsWord.containsMatchIn(raw)
        if (!strong && !maps) return null
        if (reSearchIn.containsMatchIn(raw)) return null
        var origin: String? = null
        var dest: String? = null
        Regex("""\b(?:dari|from)\s+(.+?)\s+(?:ke|menuju|to)\s+(.+)$""", RegexOption.IGNORE_CASE).find(raw)?.let { m ->
            origin = m.groupValues[1].trim()
            dest = m.groupValues[2].trim()
        }
        if (dest == null) {
            Regex("""\b(?:ke|menuju|to|tujuan(?:nya)?(?:\s+(?:ke|adalah))?|destination(?:\s+to)?)\s+(.+)$""", RegexOption.IGNORE_CASE).find(raw)?.let { m ->
                dest = m.groupValues[1].trim()
            }
        }
        if (dest == null && reMapsApp.containsMatchIn(raw)) {
            Regex("""\b(?:google\s*maps?|gmaps?|g-?maps?|maps?)\s+(?:tujuan\s+)?(.+)$""", RegexOption.IGNORE_CASE).find(raw)?.let { m ->
                dest = m.groupValues[1].trim()
            }
        }
        var d = dest ?: return null
        var mode: String? = null
        for ((re, name) in listOf(modeWalk to "walking", modeBike2 to "two_wheeler", modeCycle to "bicycling", modeTransit to "transit", modeCar to "driving")) {
            if (re.containsMatchIn(d) || re.containsMatchIn(raw)) {
                if (mode == null) mode = name
                d = re.replace(d, " ")
                origin = origin?.let { re.replace(it, " ").trim() }
            }
        }
        val tolls = reAvoidTolls.containsMatchIn(raw)
        d = reAvoidTolls.replace(d, " ")
        d = d.replace(Regex("\\s+"), " ").trim()
        d = reNavTail.replaceFirst(d, "").trim().trim(',', '.', '!', '?')
        d = d.replace(Regex("""^(?:ke|menuju|to|the)\s+""", RegexOption.IGNORE_CASE), "").trim()
        if (d.isEmpty() || d.length > 80) return null
        // "buka google maps dan ..." tanpa tujuan jelas dibiarkan untuk pembuka aplikasi
        if (Regex("""^(?:dan|lalu|terus|and|then)\b""", RegexOption.IGNORE_CASE).containsMatchIn(d)) return null
        // "buka google maps app/aplikasi" bukan tujuan: serahkan ke pembuka aplikasi
        if (Regex("""^(?:app|apps|aplikasi|aplikasinya|application|nya|dong|ya)$""", RegexOption.IGNORE_CASE).matches(d)) return null
        val preview = reNavPreview.containsMatchIn(raw) && !reNavGo.containsMatchIn(raw)
        return Command.Navigate(
            destination = d,
            drive = true,
            mode = mode,
            origin = origin?.ifEmpty { null },
            avoidTolls = tolls,
            preview = preview || origin != null
        )
    }

    // ------------------------------------------------------------------ util untuk SlotFiller

    internal fun parseWithNow(): LocalDateTime = clock()
}
