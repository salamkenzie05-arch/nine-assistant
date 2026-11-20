package id.nine.assistant.skills

import android.Manifest
import android.app.ActivityManager
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
import android.provider.CalendarContract
import android.provider.Settings
import android.telephony.SmsManager
import androidx.core.content.ContextCompat
import id.nine.assistant.core.AppLang
import id.nine.assistant.core.Fmt
import id.nine.assistant.core.Persona
import id.nine.assistant.nlu.Command
import id.nine.assistant.nlu.NumberWords
import id.nine.assistant.nlu.Pending
import id.nine.assistant.nlu.SettingsTarget
import id.nine.assistant.nlu.VolumeAction
import java.time.Duration
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
    val resetChat: Boolean = false
)

/**
 * Menjalankan [Command] di perangkat. Semua dilakukan lokal lewat API Android;
 * tidak ada akses jaringan (aplikasi ini bahkan tidak meminta izin INTERNET).
 */
class SkillExecutor(context: Context, private val notes: NoteStore, private val personaOf: () -> Persona) {
    private val ctx = context.applicationContext
    private val contacts = ContactsLookup(ctx)
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
        is Command.SetAlarm -> setAlarm(cmd, lang, now)
        is Command.SetTimer -> setTimer(cmd, lang)
        is Command.SetReminder -> setReminder(cmd, lang, now)
        Command.ShowAlarms -> SkillResult(
            lang.t("Membuka daftar alarm${s(lang)}.", "Opening your alarms${s(lang)}."),
            intent = Intent(AlarmClock.ACTION_SHOW_ALARMS)
        )
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
        Command.DeviceInfo -> deviceInfo(lang)
        is Command.AddNote -> {
            notes.add(cmd.text)
            SkillResult(lang.t("Sudah saya catat${s(lang)}: ${cmd.text}", "Noted${s(lang)}: ${cmd.text}"))
        }
        Command.ReadNotes -> readNotes(lang)
        Command.ClearNotes -> clearNotes(lang, confirmed)
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
        is Command.Unknown -> SkillResult(persona.didNotUnderstand(lang, cmd.text), chat = cmd.text)
    }

    // ------------------------------------------------------------ alarm, timer, pengingat

    private fun setAlarm(cmd: Command.SetAlarm, lang: AppLang, now: LocalDateTime): SkillResult {
        val i = Intent(AlarmClock.ACTION_SET_ALARM).apply {
            putExtra(AlarmClock.EXTRA_HOUR, cmd.time.hour)
            putExtra(AlarmClock.EXTRA_MINUTES, cmd.time.minute)
            if (!cmd.label.isNullOrBlank()) putExtra(AlarmClock.EXTRA_MESSAGE, cmd.label)
            putExtra(AlarmClock.EXTRA_SKIP_UI, true)
        }
        val label = if (cmd.label.isNullOrBlank()) "" else " (${cmd.label})"
        return SkillResult(
            lang.t("Alarm terpasang untuk ", "Alarm set for ") + Fmt.at(cmd.time, lang, now) + label + s(lang) + ".",
            intent = i
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

    private fun setReminder(cmd: Command.SetReminder, lang: AppLang, now: LocalDateTime): SkillResult {
        val text = cmd.text.ifBlank { lang.t("Pengingat", "Reminder") }
        val until = Duration.between(now, cmd.at)
        if (until.isNegative) {
            return SkillResult(lang.t("Waktu itu sudah lewat.", "That time has already passed."))
        }
        val whenText = Fmt.at(cmd.at, lang, now)
        if (until.toHours() < 24) {
            // Dalam 24 jam: alarm berlabel di aplikasi Jam.
            val i = Intent(AlarmClock.ACTION_SET_ALARM).apply {
                putExtra(AlarmClock.EXTRA_HOUR, cmd.at.hour)
                putExtra(AlarmClock.EXTRA_MINUTES, cmd.at.minute)
                putExtra(AlarmClock.EXTRA_MESSAGE, text)
                putExtra(AlarmClock.EXTRA_SKIP_UI, true)
            }
            return SkillResult(
                lang.t("Baik${s(lang)}, saya ingatkan $whenText: $text.", "Very good${s(lang)}, I'll remind you $whenText: $text."),
                intent = i
            )
        }
        // Lebih dari 24 jam: alarm Android tidak bisa, jadi buat acara kalender.
        val millis = cmd.at.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        val i = Intent(Intent.ACTION_INSERT).apply {
            data = CalendarContract.Events.CONTENT_URI
            putExtra(CalendarContract.Events.TITLE, text)
            putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, millis)
            putExtra(CalendarContract.EXTRA_EVENT_END_TIME, millis + 30 * 60 * 1000L)
        }
        return SkillResult(
            lang.t(
                "Pengingat itu lebih dari 24 jam lagi, jadi saya buka Kalender untuk $whenText. Simpan acaranya untuk menyelesaikan.",
                "That's more than 24 hours away, so I'm opening Calendar for $whenText. Save the event to finish."
            ),
            intent = i
        )
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
        is Pending.Confirm -> lang.t("Lanjutkan? Katakan ya atau tidak.", "Proceed? Say yes or no.")
    }

    // ------------------------------------------------------------ buka aplikasi

    private val aliases: Map<String, List<String>> = mapOf(
        "kamera" to listOf("camera"),
        "kalender" to listOf("calendar"),
        "kalkulator" to listOf("calculator"),
        "jam" to listOf("clock"),
        "pesan" to listOf("messages", "message"),
        "telepon" to listOf("phone", "dialer"),
        "kontak" to listOf("contacts"),
        "peta" to listOf("maps"),
        "galeri" to listOf("gallery", "photos"),
        "foto" to listOf("photos", "gallery"),
        "musik" to listOf("music"),
        "unduhan" to listOf("downloads"),
        "berkas" to listOf("files"),
        "file" to listOf("files"),
        "browser" to listOf("chrome", "browser", "internet"),
        "peramban" to listOf("chrome", "browser", "internet"),
        "email" to listOf("gmail", "mail"),
        "surel" to listOf("gmail", "mail"),
        "pengaturan" to listOf("settings"),
        "toko aplikasi" to listOf("play store")
    )

    private fun key(s: String) = s.lowercase().filter { it.isLetterOrDigit() }

    @Suppress("DEPRECATION")
    private fun launcherApps(): List<android.content.pm.ResolveInfo> {
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        return if (Build.VERSION.SDK_INT >= 33) {
            ctx.packageManager.queryIntentActivities(intent, PackageManager.ResolveInfoFlags.of(0))
        } else {
            ctx.packageManager.queryIntentActivities(intent, 0)
        }
    }

    private fun openApp(query: String, lang: AppLang): SkillResult {
        val pm = ctx.packageManager
        val wanted = (listOf(query) + (aliases[query.lowercase().trim()] ?: emptyList())).map { key(it) }.filter { it.isNotEmpty() }
        if (wanted.isEmpty()) return SkillResult(prompt(Pending.AppName, lang), pending = Pending.AppName)

        var bestScore = 0
        var bestLabel = ""
        var bestInfo: android.content.pm.ResolveInfo? = null
        for (ri in launcherApps()) {
            if (ri.activityInfo.packageName == ctx.packageName) continue
            val label = ri.loadLabel(pm).toString()
            val k = key(label)
            if (k.isEmpty()) continue
            var s = 0
            for (w in wanted) {
                val sc = when {
                    k == w -> 100
                    k.startsWith(w) -> 80
                    w.length >= 4 && k.contains(w) -> 60
                    k.length >= 4 && w.contains(k) -> 55
                    else -> 0
                }
                if (sc > s) s = sc
            }
            // beri kemenangan pada nama yang lebih pendek bila skor sama
            if (s > bestScore || (s == bestScore && s > 0 && label.length < bestLabel.length)) {
                bestScore = s
                bestLabel = label
                bestInfo = ri
            }
        }
        val info = bestInfo
        if (info == null || bestScore < 55) {
            return SkillResult(
                lang.t("Aplikasi \"$query\" tidak ditemukan.", "I couldn't find an app called \"$query\".")
            )
        }
        val launch = Intent(Intent.ACTION_MAIN).apply {
            addCategory(Intent.CATEGORY_LAUNCHER)
            setClassName(info.activityInfo.packageName, info.activityInfo.name)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return SkillResult(lang.t("Membuka $bestLabel${s(lang)}.", "Opening $bestLabel${s(lang)}."), intent = launch)
    }

    // ------------------------------------------------------------ pengaturan

    private fun openSettings(cmd: Command.OpenSettings, lang: AppLang): SkillResult {
        val (intent, fallback, name) = when (cmd.target) {
            SettingsTarget.WIFI -> Triple(
                if (Build.VERSION.SDK_INT >= 29) Intent(Settings.Panel.ACTION_INTERNET_CONNECTIVITY) else Intent(Settings.ACTION_WIFI_SETTINGS),
                Intent(Settings.ACTION_WIFI_SETTINGS),
                "Wi-Fi"
            )
            SettingsTarget.BLUETOOTH -> Triple(Intent(Settings.ACTION_BLUETOOTH_SETTINGS), null, "Bluetooth")
            SettingsTarget.AIRPLANE -> Triple(
                Intent(Settings.ACTION_AIRPLANE_MODE_SETTINGS),
                Intent(Settings.ACTION_WIRELESS_SETTINGS),
                lang.t("mode pesawat", "airplane mode")
            )
            SettingsTarget.LOCATION -> Triple(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS), null, lang.t("lokasi", "location"))
            SettingsTarget.MOBILE_DATA -> Triple(
                if (Build.VERSION.SDK_INT >= 29) Intent(Settings.Panel.ACTION_INTERNET_CONNECTIVITY) else Intent(Settings.ACTION_WIRELESS_SETTINGS),
                Intent(Settings.ACTION_WIRELESS_SETTINGS),
                lang.t("data seluler", "mobile data")
            )
            SettingsTarget.DISPLAY -> Triple(Intent(Settings.ACTION_DISPLAY_SETTINGS), null, lang.t("layar", "display"))
            SettingsTarget.SOUND -> Triple(Intent(Settings.ACTION_SOUND_SETTINGS), null, lang.t("suara", "sound"))
            SettingsTarget.BATTERY_SAVER -> Triple(
                Intent(Settings.ACTION_BATTERY_SAVER_SETTINGS),
                Intent(Settings.ACTION_SETTINGS),
                lang.t("hemat baterai", "battery saver")
            )
            SettingsTarget.DND -> Triple(
                Intent("android.settings.ZEN_MODE_SETTINGS"),
                Intent(Settings.ACTION_SOUND_SETTINGS),
                lang.t("jangan ganggu", "do not disturb")
            )
            SettingsTarget.GENERAL -> Triple(Intent(Settings.ACTION_SETTINGS), null, lang.t("pengaturan", "settings"))
        }
        val reply = if (cmd.turnOn == null) {
            lang.t("Membuka pengaturan $name${s(lang)}.", "Opening $name settings${s(lang)}.")
        } else {
            lang.t(
                "Android tidak mengizinkan aplikasi biasa menyalakan atau mematikan $name secara langsung. Saya buka pengaturannya.",
                "Android doesn't let regular apps switch $name on or off directly. I'm opening its settings."
            )
        }
        return SkillResult(reply, intent = intent, fallback = fallback)
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

    private fun deviceInfo(lang: AppLang): SkillResult {
        val stat = StatFs(Environment.getDataDirectory().path)
        val freeGb = stat.availableBytes / 1_073_741_824.0
        val totalGb = stat.totalBytes / 1_073_741_824.0
        val mi = ActivityManager.MemoryInfo()
        (ctx.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager).getMemoryInfo(mi)
        val ramGb = mi.totalMem / 1_073_741_824.0
        val model = "${Build.MANUFACTURER} ${Build.MODEL}"
        val text = lang.t(
            "$model, Android ${Build.VERSION.RELEASE}. Penyimpanan kosong ${"%.1f".format(freeGb)} dari ${"%.0f".format(totalGb)} gigabita. RAM ${"%.1f".format(ramGb)} gigabita.",
            "$model, Android ${Build.VERSION.RELEASE}. ${"%.1f".format(freeGb)} of ${"%.0f".format(totalGb)} gigabytes of storage free. ${"%.1f".format(ramGb)} gigabytes of RAM."
        )
        return SkillResult(text)
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

    // ------------------------------------------------------------ bantuan

    private fun help(lang: AppLang): String = lang.t(
        "Beberapa hal yang bisa saya lakukan:\n" +
            "• Ngobrol atau bertanya apa saja (perlu otak AI di Pengaturan)\n" +
            "• \"Pasang alarm jam 6 pagi\"\n" +
            "• \"Timer 10 menit\"\n" +
            "• \"Ingatkan saya minum obat jam 8 malam\"\n" +
            "• \"Buka WhatsApp\" / \"Buka pengaturan Wi-Fi\"\n" +
            "• \"Nyalakan senter\" / \"Volume 50 persen\"\n" +
            "• \"Berapa sisa baterai?\" / \"Jam berapa sekarang?\"\n" +
            "• \"Catat beli susu\" / \"Baca catatan\"\n" +
            "• \"Telepon Budi\" / \"Kirim pesan ke Ibu isinya aku pulang telat\"\n" +
            "• \"Ganti bahasa ke Inggris\" / \"Lupakan percakapan\"",
        "Here are some things I can do:\n" +
            "• Chat or ask me anything (needs the AI brain in Settings)\n" +
            "• \"Set an alarm for 6 AM\"\n" +
            "• \"Set a timer for 10 minutes\"\n" +
            "• \"Remind me to take my pills at 8 PM\"\n" +
            "• \"Open WhatsApp\" / \"Open Wi-Fi settings\"\n" +
            "• \"Turn on the flashlight\" / \"Set volume to 50 percent\"\n" +
            "• \"How much battery is left?\" / \"What time is it?\"\n" +
            "• \"Take a note: buy milk\" / \"Read my notes\"\n" +
            "• \"Call Bob\" / \"Send a message to Mom saying I'll be late\"\n" +
            "• \"Switch language to Indonesian\" / \"Forget our conversation\""
    )
}
