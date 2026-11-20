package id.nine.assistant.nlu

import id.nine.assistant.core.AppLang
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
        val raw = clean(input)
        if (raw.isEmpty()) return Command.Unknown(input.trim())
        val norm = NumberWords.normalize(raw)
        val now = clock()
        return parseLanguage(raw)
            ?: parseReset(raw)
            ?: parseControl(raw)
            ?: parseAlarm(norm, now)
            ?: parseTimer(norm)
            ?: parseReminder(raw, now)
            ?: parseFlashlight(raw)
            ?: parseSettings(raw)
            ?: parseVolume(norm)
            ?: parseDeviceInfo(raw)
            ?: parseNotes(raw)
            ?: parseSms(raw)
            ?: parseCall(raw)
            ?: parseOpenApp(raw)
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
    private val reShowAlarm = rx("""(?:lihat|tampilkan|buka|tunjukkan|show|open|view|list|check|cek|hapus|matikan|batalkan|cancel|delete|remove|turn\s+off|disable)\s+(?:semua\s+|daftar\s+|my\s+|all\s+|the\s+|aplikasi\s+|app\s+)*alarms?\b|what\s+alarms|alarm\s+apa\s+saja""")
    private val reForNum = rx("""\bfor\s+(?=\d{1,2}(?:[.:]\d{2})?\b(?!\s*(?:minutes?|mins?|hours?|hrs?|seconds?|secs?|menit|jam|detik)))""")
    private val reWake = rx("""\b(?:bangunkan|bangunin|wake\s+me)\b""")
    private val reCmdWords = rx("""\b(?:alarm|alarms|set|pasang|setel|atur|buat|bikin|tambah|tambahkan|an|a|for|untuk|me|up|wake|bangunkan|bangunin|aku|saya|please|tolong|the|my|dong|at|jam|pukul)\b""")

    private fun parseAlarm(norm: String, now: LocalDateTime): Command? {
        if (!reAlarmWord.containsMatchIn(norm)) return null
        if (reShowAlarm.containsMatchIn(norm)) return Command.ShowAlarms
        // "set alarm for 7" -> "set alarm at 7"
        val n2 = reForNum.replace(norm, "at ")
        val tp = TimeParser.parse(n2)
        val label = if (tp != null) labelFrom(tp.remainder) else labelFrom(n2)
        var spec = tp?.spec
        if (spec != null && spec.ambiguous && reWake.containsMatchIn(norm) && spec.hour != null) {
            // "bangunkan aku jam 5" = pagi hari
            spec = spec.copy(hour = spec.hour % 12, ambiguous = false)
        }
        val at = spec?.resolve(now)
        if (spec == null || !spec.isKnown || at == null) {
            return Command.NeedInfo(Pending.AlarmTime(label))
        }
        return Command.SetAlarm(at, label)
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
        SettingsTarget.LOCATION, SettingsTarget.MOBILE_DATA, SettingsTarget.DND, SettingsTarget.BATTERY_SAVER
    )

    private val reToggle = rx("""^(nyalakan|hidupkan|aktifkan|matikan|nonaktifkan|turn\s+on|turn\s+off|switch\s+on|switch\s+off|enable|disable)\s+(?:the\s+|fitur\s+)?(.+)$""")
    private val reOpenSettings = rx("""^(?:buka|bukakan|open|show|tampilkan|go\s+to|pergi\s+ke)\s+(?:menu\s+)?(?:pengaturan|settings|setelan)(?:\s+(.*))?$""")
    private val reSettingsOnly = rx("""^(?:pengaturan|settings|setelan)(?:\s+(.*))?$""")

    private fun parseSettings(raw: String): Command? {
        val tg = reToggle.find(raw)
        if (tg != null) {
            val verb = tg.groupValues[1].lowercase()
            val target = targetOf(tg.groupValues[2], networkOnly = false) ?: return null
            if (target == SettingsTarget.SOUND) return null // ditangani parseVolume (unmute)
            val on = !(verb.startsWith("matikan") || verb.startsWith("nonaktifkan") ||
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
    private val reUnmute = rx("""\b(?:unmute|bunyikan|aktifkan\s+suara|nyalakan\s+suara|turn\s+on\s+(?:the\s+)?sound)\b""")
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
    private val reDevice = rx("""\b(?:info(?:rmasi)?\s+(?:perangkat|hp|ponsel|device)|device\s+info(?:rmation)?|about\s+(?:this\s+)?phone|spesifikasi\s+(?:hp|ponsel|perangkat)|penyimpanan|storage)\b""")

    private fun parseDeviceInfo(raw: String): Command? = when {
        reBattery.containsMatchIn(raw) -> Command.Battery
        reTime.containsMatchIn(raw) -> Command.CurrentTime
        reDate.containsMatchIn(raw) -> Command.CurrentDate
        reDevice.containsMatchIn(raw) -> Command.DeviceInfo
        else -> null
    }

    // ------------------------------------------------------------------ catatan

    private val reClearNotes = rx("""^(?:hapus|bersihkan|delete|clear|remove)\s+(?:semua\s+|all\s+|my\s+)*(?:catatan|notes?)$""")
    private val reReadNotes = rx("""^(?:baca(?:kan)?|tampilkan|lihat|tunjukkan|apa\s+saja|read|show|list|what\s+are)\s+(?:semua\s+|daftar\s+|my\s+|all\s+|the\s+)*(?:catatan|notes?)(?:\s+(?:saya|ku))?$|^(?:catatan\s+(?:saya|ku)|my\s+notes)$""")
    private val reAddNote = rx(
        """^(?:catat(?:kan)?|tulis(?:kan)?|simpan(?:kan)?\s+catatan|buat\s+catatan(?:\s+baru)?|tambah(?:kan)?\s+catatan|catatan\s+baru|""" +
            """ingat(?!kan)\s+(?:bahwa\s+)?|note(?:\s+that)?|take\s+a\s+note(?:\s+(?:that|saying|of))?|make\s+a\s+note(?:\s+(?:that|of))?|""" +
            """add\s+(?:a\s+)?note|write\s+(?:this\s+)?down|remember\s+(?:that\s+)?)\b\s*[:,]?\s*(.*)$"""
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

    private val reOpen = rx("""^(?:buka|bukakan|jalankan|luncurkan|launch|open|start|run)\b\s*(?:aplikasi\s+|app\s+|apl\s+|the\s+)*(.*?)(?:\s+(?:app|aplikasi))?$""")

    private fun parseOpenApp(raw: String): Command? {
        val m = reOpen.find(raw) ?: return null
        val q = m.groupValues[1].trim()
        if (q.isEmpty()) return Command.NeedInfo(Pending.AppName)
        if (Regex("^(?:catatan|notes?)$", RegexOption.IGNORE_CASE).matches(q)) return Command.ReadNotes
        val t = targetOf(q, networkOnly = true)
        if (t != null) return Command.OpenSettings(t, null)
        return Command.OpenApp(q)
    }

    // ------------------------------------------------------------------ util untuk SlotFiller

    internal fun parseWithNow(): LocalDateTime = clock()
}
