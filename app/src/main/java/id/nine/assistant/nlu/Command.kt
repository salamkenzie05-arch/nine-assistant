package id.nine.assistant.nlu

import id.nine.assistant.core.AppLang
import id.nine.assistant.core.Mood
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime

enum class SettingsTarget {
    GENERAL, WIFI, BLUETOOTH, AIRPLANE, LOCATION, MOBILE_DATA, DISPLAY, SOUND, BATTERY_SAVER, DND, HOTSPOT, MOTION
}

/** Kontrol pemutar musik yang sedang aktif (YouTube Music dll.). */
enum class MediaAction { PLAY, PAUSE, NEXT, PREVIOUS }

/** Pengaturan Nine sendiri yang bisa diubah lewat suara. */
enum class NineSetting {
    THEME_LIGHT, THEME_DARK, THEME_SYSTEM, WAKE_ON, WAKE_OFF, EXIT_APP,
    MUSIC_REACT_ON, MUSIC_REACT_OFF
}

/** Hiburan yang Nine bawakan sendiri (tanpa AI): gombal, pantun, kata bijak. */
enum class FunKind { GOMBAL, PANTUN, WISDOM }

/** Pertanyaan tentang HP yang dijawab langsung dari sistem (tanpa data pribadi seperti IMEI atau nomor seri). */
enum class InfoTopic { STORAGE, RAM, MODEL, ANDROID, NETWORK, BATTERY_HEALTH, SCREEN, UPTIME, WIFI, BLUETOOTH, VOLUME, CPU }

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
    data class PhoneInfo(val topic: InfoTopic) : Command()
    /** "Ke layar utama": menekan Home. */
    data object GoHome : Command()

    data class AddNote(val text: String) : Command()
    data object ReadNotes : Command()
    data object ClearNotes : Command()

    // --- Ingatan jangka panjang (hal tentang pengguna yang diingat Nine antar percakapan)
    data class Remember(val text: String) : Command()
    data class Forget(val query: String) : Command()
    data object ForgetAll : Command()
    data object ReadMemory : Command()

    /** Putar/jeda/lanjut/berikutnya/sebelumnya pada pemutar musik yang sedang aktif. */
    data class MediaControl(val action: MediaAction) : Command()
    /** Angkat ([accept] true) atau tolak/tutup telepon yang masuk. */
    data class CallControl(val accept: Boolean) : Command()
    /** Nine menyanyi (nada droid). [song] null = pilih sendiri. */
    data class Sing(val song: String?) : Command()
    /** Tampilkan ekspresi [mood] atas permintaan pengguna. */
    data class Emote(val mood: Mood) : Command()
    /** Tuner gitar: [start] false = selesai; [string] 1 (E tinggi) … 6 (E rendah), null = otomatis; [tone] true = bunyikan nada acuannya. */
    data class Tuner(val start: Boolean, val string: Int? = null, val tone: Boolean = false) : Command()
    data class AppSetting(val setting: NineSetting) : Command()

    data class Call(val target: String) : Command()

    /**
     * Telepon polisi ke [number] (nomor menurut negara pengguna). Dibuat executor hanya sebagai isi pertanyaan konfirmasi;
     * panggilan baru terjadi bila perintah ini dijalankan dengan confirmed = true setelah pengguna menjawab "ya".
     */
    data class CallEmergency(val number: String) : Command()
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
    /**
     * [drive] true = langsung mulai navigasi; false = tampilkan lokasinya di peta.
     * [mode]: "driving", "two_wheeler" (motor), "bicycling", "walking", "transit"; null = mobil.
     * [origin] = titik awal bila disebut ("dari A ke B"); [preview] true = tampilkan rute dulu (tanpa langsung mulai).
     */
    data class Navigate(
        val destination: String,
        val drive: Boolean = true,
        val mode: String? = null,
        val origin: String? = null,
        val avoidTolls: Boolean = false,
        val preview: Boolean = false
    ) : Command() {
        override fun toString(): String =
            "Navigate(destination=$destination, drive=$drive" +
                (if (mode != null) ", mode=$mode" else "") +
                (if (origin != null) ", origin=$origin" else "") +
                (if (avoidTolls) ", avoidTolls=true" else "") +
                (if (preview) ", preview=true" else "") + ")"
    }
    data class WebSearch(val query: String) : Command()
    data class OpenUrl(val url: String) : Command()
    data class WhatsApp(val target: String, val body: String?) : Command()

    // --- Kamera & perekam
    data class Camera(val video: Boolean, val front: Boolean, val timerSeconds: Int?) : Command()
    data class RecordAudio(val start: Boolean) : Command()

    // --- Hitung
    /** [text] = ucapan perhitungan apa adanya; dihitung oleh [Calc]. */
    data class Calculate(val text: String) : Command()

    // --- Keuangan (pengeluaran & pemasukan)
    data class FinanceAdd(val type: FinType, val amount: Long, val category: String, val note: String) : Command()
    data class FinanceReport(val period: FinPeriod, val type: FinType? = null) : Command()
    data class FinanceList(val period: FinPeriod, val type: FinType? = null) : Command()
    data object FinanceUndo : Command()
    data object FinanceClear : Command()
    data object FinanceOpen : Command()

    /** Tutup semua aplikasi yang sedang berjalan; [includeSelf] true = Nine ikut menutup dirinya. */
    data class CloseApps(val includeSelf: Boolean) : Command()

    /** Gombal, pantun, atau kata bijak. */
    data class Entertain(val kind: FunKind) : Command()

    /** Ubah panggilan pengguna ("panggil aku Bos"). */
    data class SetCallName(val name: String) : Command()

    // --- Affinity & lokasi
    data object AffinityStatus : Command()
    data object WhereAmI : Command()

    // --- Mentor bahasa Inggris (aturan lokal, tanpa AI)
    /** Mulai ([on] = true) atau akhiri sesi latihan bahasa Inggris. */
    data class EnglishCoach(val on: Boolean) : Command()

    /** "correct my sentence: ..." — periksa satu kalimat tanpa memulai sesi. */
    data class CorrectEnglish(val sentence: String) : Command()

    /** "another word for good": pilihan kata yang lebih bagus. */
    data class BetterWord(val word: String) : Command()

    /** Perintah belum lengkap; tanyakan informasi yang kurang. */
    data class NeedInfo(val pending: Pending) : Command()

    data class Unknown(val text: String) : Command()
}
