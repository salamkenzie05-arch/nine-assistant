package id.nine.assistant.nlu

import id.nine.assistant.core.AppLang
import java.time.LocalDateTime

enum class SettingsTarget {
    GENERAL, WIFI, BLUETOOTH, AIRPLANE, LOCATION, MOBILE_DATA, DISPLAY, SOUND, BATTERY_SAVER, DND
}

enum class VolumeAction { UP, DOWN, MUTE, UNMUTE, SET, MAX }

/** Pertanyaan lanjutan yang sedang menunggu jawaban pengguna. */
sealed class Pending {
    /** Menunggu "ya/tidak" sebelum menjalankan [command]. */
    data class Confirm(val command: Command) : Pending()
    data class AlarmTime(val label: String?) : Pending()
    data class TimerDuration(val label: String?) : Pending()
    data class ReminderTime(val text: String) : Pending()
    data object ReminderText : Pending()
    data object NoteText : Pending()
    data class SmsBody(val target: String) : Pending()
    data object CallTarget : Pending()
    data object AppName : Pending()
}

/** Hasil pemahaman bahasa alami: apa yang diminta pengguna. */
sealed class Command {
    data class SetAlarm(val time: LocalDateTime, val label: String?) : Command()
    data class SetTimer(val seconds: Int, val label: String?) : Command()
    data class SetReminder(val text: String, val at: LocalDateTime) : Command()
    data object ShowAlarms : Command()

    data class OpenApp(val query: String) : Command()
    data class OpenSettings(val target: SettingsTarget, val turnOn: Boolean?) : Command()
    data class Flashlight(val on: Boolean) : Command()
    data class Volume(val action: VolumeAction, val percent: Int? = null) : Command()

    data object Battery : Command()
    data object CurrentTime : Command()
    data object CurrentDate : Command()
    data object DeviceInfo : Command()

    data class AddNote(val text: String) : Command()
    data object ReadNotes : Command()
    data object ClearNotes : Command()

    data class Call(val target: String) : Command()
    data class SendSms(val target: String, val body: String?) : Command()

    /** Dibuat oleh executor setelah kontak ditemukan; dikirim hanya setelah konfirmasi. */
    data class SendSmsResolved(val number: String, val displayName: String, val body: String) : Command()

    data class SwitchLanguage(val lang: AppLang) : Command()
    data object Help : Command()
    data object Greeting : Command()
    data object Thanks : Command()
    data object WhoAreYou : Command()
    data object HowAreYou : Command()
    data object StopSpeaking : Command()
    /** Mulai percakapan baru dengan otak AI (lupakan konteks). */
    data object ResetChat : Command()

    /** Perintah belum lengkap; tanyakan informasi yang kurang. */
    data class NeedInfo(val pending: Pending) : Command()

    data class Unknown(val text: String) : Command()
}
