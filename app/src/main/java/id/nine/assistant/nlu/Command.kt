package id.nine.assistant.nlu

import id.nine.assistant.core.AppLang
import java.time.DayOfWeek
import java.time.LocalDate
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
    data class EventTime(val title: String) : Pending()
    data object TodoText : Pending()
    /** Pengguna memilih salah satu [options] ("yang kedua", "nomor 1", atau sebagian judulnya). [all] = pilihan "semua". */
    data class Choose(val options: List<Choice>, val all: Command? = null) : Pending()
}

/** Satu pilihan dalam [Pending.Choose]. */
data class Choice(val label: String, val command: Command)

/**
 * Rujukan ke alarm Nine: "alarm jam 6", "alarm gym", "semua alarm", "alarm tadi", "alarm berikutnya", "alarm nomor 2".
 * Semua kosong = tidak disebut (Nine memilih sendiri atau bertanya).
 */
data class AlarmRef(
    val all: Boolean = false,
    val hour: Int? = null,
    val minute: Int? = null,
    val ambiguous: Boolean = false,
    val label: String? = null,
    val last: Boolean = false,
    val next: Boolean = false,
    val index: Int? = null,
    /** Id alarm yang sudah pasti (hasil memilih). */
    val id: Int? = null
) {
    val isEmpty: Boolean get() = !all && hour == null && label == null && !last && !next && index == null && id == null
}

/** Hasil pemahaman bahasa alami: apa yang diminta pengguna. */
sealed class Command {
    /** [repeat] kosong = sekali saja. */
    data class SetAlarm(val time: LocalDateTime, val label: String?, val repeat: Set<DayOfWeek> = emptySet()) : Command() {
        override fun toString(): String =
            "SetAlarm(time=$time, label=$label" + (if (repeat.isEmpty()) "" else ", repeat=" + RepeatDays.code(repeat)) + ")"
    }
    data class SetTimer(val seconds: Int, val label: String?) : Command()
    data class SetReminder(val text: String, val at: LocalDateTime) : Command()
    /** Bacakan daftar alarm. */
    data object ShowAlarms : Command()
    /** Buka layar daftar alarm. */
    data object OpenAlarms : Command()
    /** Nyalakan ([on] true) atau matikan alarm. Mematikan juga menghentikan alarm yang sedang berbunyi. */
    data class AlarmToggle(val ref: AlarmRef, val on: Boolean) : Command()
    data class AlarmDelete(val ref: AlarmRef) : Command()

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

    // --- Ingatan jangka panjang (hal tentang pengguna yang diingat Nine antar percakapan)
    data class Remember(val text: String) : Command()
    data class Forget(val query: String) : Command()
    data object ForgetAll : Command()
    data object ReadMemory : Command()

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

    // --- Kalender
    /** [end] null = durasi default 1 jam. [allDay] true bila hanya tanggal yang disebut. */
    data class AddEvent(val title: String, val start: LocalDateTime, val end: LocalDateTime?, val allDay: Boolean) : Command()
    data class ShowAgenda(val date: LocalDate) : Command()
    /** Ubah waktu acara yang terakhir dibuat Nine ("ubah jadi jam 11"). [timeText] belum diparse. */
    data class MoveLastEvent(val timeText: String) : Command()
    /** Geser acara terakhir sekian detik (positif = diundur, negatif = dimajukan). */
    data class ShiftLastEvent(val seconds: Int) : Command()
    data object DeleteLastEvent : Command()
    /** Hapus acara yang judulnya cocok dengan [query] (boleh kosong bila [date] disebut), pada [date]/jam tertentu bila disebut. */
    data class DeleteEvent(val query: String, val date: LocalDate?, val hour: Int?, val minute: Int?, val ambiguous: Boolean = false) : Command()
    /** Acara yang sudah dipastikan; [beginMillis] = waktu mulai kejadian (untuk acara berulang hanya kejadian ini yang dihapus). */
    data class DeleteEventInstance(
        val eventId: Long,
        val title: String,
        val begin: LocalDateTime,
        val beginMillis: Long,
        val allDay: Boolean,
        val recurring: Boolean
    ) : Command()
    /**
     * "hapus X" tanpa kata acara/kalender: coba cari acara bernama X; bila tidak ada, teruskan [text] ke AI.
     */
    data class DeleteMaybeEvent(val text: String, val event: DeleteEvent) : Command()
    /** Hapus beberapa acara sekaligus (jawaban "semua"/"keduanya" saat Nine bertanya yang mana). */
    data class DeleteEvents(val items: List<DeleteEventInstance>) : Command()

    // --- Daftar tugas harian
    data class TodoAdd(val text: String) : Command()
    data object TodoList : Command()
    /** [ref] nomor urut ("2"), "-1" untuk terakhir, atau potongan teks tugas. */
    data class TodoDone(val ref: String) : Command()
    data class TodoRemove(val ref: String) : Command()
    data object TodoClearDone : Command()
    data object TodoClearAll : Command()

    // --- Aplikasi eksternal
    /** [app] "youtube", "youtube music", "spotify", atau null (= YouTube Music). */
    data class PlayMedia(val query: String, val app: String?) : Command()
    /** [drive] true = langsung mulai navigasi; false = tampilkan lokasinya di peta. */
    data class Navigate(val destination: String, val drive: Boolean = true) : Command()
    data class WebSearch(val query: String) : Command()
    data class OpenUrl(val url: String) : Command()
    data class WhatsApp(val target: String, val body: String?) : Command()

    // --- Kamera & perekam
    data class Camera(val video: Boolean, val front: Boolean, val timerSeconds: Int?) : Command()
    data class RecordAudio(val start: Boolean) : Command()

    // --- Affinity & lokasi
    data object AffinityStatus : Command()
    data object WhereAmI : Command()

    /** Perintah belum lengkap; tanyakan informasi yang kurang. */
    data class NeedInfo(val pending: Pending) : Command()

    data class Unknown(val text: String) : Command()
}
