package id.nine.assistant.nlu

import id.nine.assistant.core.AppLang
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDateTime

/** Tes pemahaman perintah. Waktu dibekukan: Jumat, 20 November 2026 pukul 11.14. */
class CommandParserTest {
    private val now = LocalDateTime.of(2026, 11, 20, 11, 14)
    private val parser = CommandParser { now }
    private val slots = SlotFiller(parser)

    private fun check(input: String, expected: String) {
        assertEquals("input: \"$input\"", expected, parser.parse(input).toString())
    }

    private fun slot(p: Pending, input: String, expected: String) {
        val got = when (val r = slots.fill(p, input)) {
            is SlotOutcome.Resolved -> r.command.toString()
            SlotOutcome.Cancelled -> "Cancelled"
            SlotOutcome.NotAnAnswer -> "NotAnAnswer"
        }
        assertEquals("slot input: \"$input\" on $p", expected, got)
    }

    @Test
    fun alarm() {
        check("pasang alarm jam 7 pagi", "SetAlarm(time=2026-11-21T07:00, label=null)")
        check("set alarm for 6:30 am", "SetAlarm(time=2026-11-21T06:30, label=null)")
        check("bangunkan aku jam 5", "SetAlarm(time=2026-11-21T05:00, label=null)")
        check("alarm jam tujuh lewat lima belas malam", "SetAlarm(time=2026-11-20T19:15, label=null)")
        check("set an alarm for 7 am for gym", "SetAlarm(time=2026-11-21T07:00, label=gym)")
        check("alarm jam 5 untuk makan malam", "SetAlarm(time=2026-11-20T17:00, label=makan malam)")
        check("tolong pasang alarm", "NeedInfo(pending=AlarmTime(label=null))")
        check("lihat alarm saya", "ShowAlarms")
        check("wake me up at seven thirty", "SetAlarm(time=2026-11-21T07:30, label=null)")
        check("alarm besok jam 6 pagi", "SetAlarm(time=2026-11-21T06:00, label=null)")
        check("set alarm 14:45", "SetAlarm(time=2026-11-20T14:45, label=null)")
        check("set alarm for 12 pm", "SetAlarm(time=2026-11-20T12:00, label=null)")
        check("alarm jam 12 malam", "SetAlarm(time=2026-11-21T00:00, label=null)")
        check("pasang alarm setengah 8 pagi", "SetAlarm(time=2026-11-21T07:30, label=null)")
        check("pasang alarm jam 8 kurang 10 pagi", "SetAlarm(time=2026-11-21T07:50, label=null)")
        check("set alarm for 7 o'clock tonight", "SetAlarm(time=2026-11-20T19:00, label=null)")
        check("Hai asisten, pasang alarm jam 6 sore", "SetAlarm(time=2026-11-20T18:00, label=null)")
        check("buka aplikasi alarm", "ShowAlarms")
        check("alarm 5 menit lagi", "SetAlarm(time=2026-11-20T11:19, label=null)")
        check("Pasang alarm jam 07.00 pagi", "SetAlarm(time=2026-11-21T07:00, label=null)")
        check("Set an alarm for 6.30 a.m.", "SetAlarm(time=2026-11-21T06:30, label=null)")
        check("set alarm for 7", "SetAlarm(time=2026-11-20T19:00, label=null)")
        check("remind me in half an hour to check the oven", "SetTimer(seconds=1800, label=check the oven)")
        check("catat nomor plat B 1234 XYZ", "AddNote(text=nomor plat B 1234 XYZ)")
    }

    @Test
    fun timer() {
        check("set a timer for 10 minutes", "SetTimer(seconds=600, label=null)")
        check("timer 1 jam 30 menit", "SetTimer(seconds=5400, label=null)")
        check("hitung mundur lima menit", "SetTimer(seconds=300, label=null)")
        check("set a 25 minute timer", "SetTimer(seconds=1500, label=null)")
        check("atur timer setengah jam", "SetTimer(seconds=1800, label=null)")
        check("start a timer", "NeedInfo(pending=TimerDuration(label=null))")
        check("pasang timer 45 detik", "SetTimer(seconds=45, label=null)")
        check("set timer for an hour and a half", "SetTimer(seconds=5400, label=null)")
    }

    @Test
    fun reminder() {
        check("ingatkan saya minum obat jam 8 malam", "SetReminder(text=minum obat, at=2026-11-20T20:00)")
        check("remind me to call mom in 10 minutes", "SetTimer(seconds=600, label=call mom)")
        check("ingatkan saya 10 menit lagi untuk telepon ibu", "SetTimer(seconds=600, label=telepon ibu)")
        check("remind me tomorrow at 9 am to submit report", "SetReminder(text=submit report, at=2026-11-21T09:00)")
        check("ingatkan saya besok pagi rapat", "SetReminder(text=rapat, at=2026-11-21T08:00)")
        check("ingatkan saya beli susu", "NeedInfo(pending=ReminderTime(text=beli susu))")
        check("ingatkan", "NeedInfo(pending=ReminderText)")
        check("set a reminder to take medicine at 6 pm", "SetReminder(text=take medicine, at=2026-11-20T18:00)")
        check("remind me on friday at 3 pm to pay rent", "SetReminder(text=pay rent, at=2026-11-20T15:00)")
        check("ingatkan saya makan malam", "NeedInfo(pending=ReminderTime(text=makan malam))")
        check("ingatkan saya nanti malam jam 8 telepon budi", "SetReminder(text=telepon budi, at=2026-11-20T20:00)")
        check("buat pengingat bayar listrik hari senin jam 9 pagi", "SetReminder(text=bayar listrik, at=2026-11-23T09:00)")
    }

    @Test
    fun senterVolumePengaturan() {
        check("nyalakan senter", "Flashlight(on=true)")
        check("matikan senter", "Flashlight(on=false)")
        check("turn off the flashlight", "Flashlight(on=false)")
        check("turn on flashlight", "Flashlight(on=true)")
        check("buka senter", "Flashlight(on=true)")
        check("naikkan volume", "Volume(action=UP, percent=null)")
        check("turunkan suara", "Volume(action=DOWN, percent=null)")
        check("volume 50 persen", "Volume(action=SET, percent=50)")
        check("atur volume ke lima puluh", "Volume(action=SET, percent=50)")
        check("set volume to 30%", "Volume(action=SET, percent=30)")
        check("mute", "Volume(action=MUTE, percent=null)")
        check("bisukan", "Volume(action=MUTE, percent=null)")
        check("volume max", "Volume(action=MAX, percent=null)")
        check("turn the volume up", "Volume(action=UP, percent=null)")
        check("turn on the sound", "Volume(action=UNMUTE, percent=null)")
        check("nyalakan wifi", "OpenSettings(target=WIFI, turnOn=true)")
        check("turn off bluetooth", "OpenSettings(target=BLUETOOTH, turnOn=false)")
        check("buka pengaturan", "OpenSettings(target=GENERAL, turnOn=null)")
        check("open wifi settings", "OpenSettings(target=WIFI, turnOn=null)")
        check("buka pengaturan layar", "OpenSettings(target=DISPLAY, turnOn=null)")
        check("aktifkan mode pesawat", "OpenSettings(target=AIRPLANE, turnOn=true)")
        check("matikan jangan ganggu", "OpenSettings(target=DND, turnOn=false)")
        check("open settings", "OpenSettings(target=GENERAL, turnOn=null)")
    }

    @Test
    fun infoPerangkat() {
        check("berapa sisa baterai", "Battery")
        check("how much battery do I have", "Battery")
        check("jam berapa sekarang", "CurrentTime")
        check("what time is it", "CurrentTime")
        check("hari ini tanggal berapa", "CurrentDate")
        check("what day is it", "CurrentDate")
        check("info perangkat", "DeviceInfo")
    }

    @Test
    fun catatan() {
        check("catat beli susu dan telur", "AddNote(text=beli susu dan telur)")
        check("take a note that I need milk", "AddNote(text=I need milk)")
        check("baca catatan", "ReadNotes")
        check("hapus semua catatan", "ClearNotes")
        check("catat", "NeedInfo(pending=NoteText)")
        check("ingat bahwa kunci ada di meja", "AddNote(text=kunci ada di meja)")
        check("show my notes", "ReadNotes")
        check("buka catatan", "ReadNotes")
        check("catatan baru ide proyek", "AddNote(text=ide proyek)")
    }

    @Test
    fun sms() {
        check("kirim pesan ke Budi isinya aku sudah sampai", "SendSms(target=Budi, body=aku sudah sampai)")
        check("send a message to Bob saying I'm late", "SendSms(target=Bob, body=I'm late)")
        check("text Alice that I will be late", "SendSms(target=Alice, body=I will be late)")
        check("kirim sms ke Ibu", "SendSms(target=Ibu, body=null)")
        check("kirim pesan ke Budi bilang aku telat", "SendSms(target=Budi, body=aku telat)")
        check("kirim pesan ke Budi: halo apa kabar", "SendSms(target=Budi, body=halo apa kabar)")
    }

    @Test
    fun telepon() {
        check("telepon ibu", "Call(target=ibu)")
        check("call John Smith", "Call(target=John Smith)")
        check("hubungi 0812 3456 7890", "Call(target=0812 3456 7890)")
        check("call", "NeedInfo(pending=CallTarget)")
        check("tolong telepon pak Budi", "Call(target=pak Budi)")
    }

    @Test
    fun bukaAplikasi() {
        check("buka whatsapp", "OpenApp(query=whatsapp)")
        check("open the camera", "OpenApp(query=camera)")
        check("buka aplikasi kalkulator", "OpenApp(query=kalkulator)")
        check("open Google Maps app", "OpenApp(query=Google Maps)")
        check("buka", "NeedInfo(pending=AppName)")
        check("buka kalender", "OpenApp(query=kalender)")
        check("buka wifi", "OpenSettings(target=WIFI, turnOn=null)")
    }

    @Test
    fun bahasaKontrol() {
        check("ganti bahasa ke inggris", "SwitchLanguage(lang=EN)")
        check("switch language to Indonesian", "SwitchLanguage(lang=ID)")
        check("speak english", "SwitchLanguage(lang=EN)")
        check("halo", "Greeting")
        check("terima kasih", "Thanks")
        check("siapa kamu", "WhoAreYou")
        check("kamu bisa apa", "Help")
        check("what can you do", "Help")
        check("stop", "StopSpeaking")
        check("bagaimana cuaca besok", "Unknown(text=bagaimana cuaca besok)")
        check("", "Unknown(text=)")
    }

    @Test
    fun nineAndReset() {
        check("Nine, pasang alarm jam 7 pagi", "SetAlarm(time=2026-11-21T07:00, label=null)")
        check("hai nine jam berapa sekarang", "CurrentTime")
        check("halo nine", "Greeting")
        check("lupakan percakapan", "ResetChat")
        check("mulai percakapan baru", "ResetChat")
        check("forget our conversation", "ResetChat")
        check("new chat", "ResetChat")
        check("siapa presiden pertama indonesia", "Unknown(text=siapa presiden pertama indonesia)")
        check("jelaskan cara kerja lubang hitam", "Unknown(text=jelaskan cara kerja lubang hitam)")
    }

    @Test
    fun calendar() {
        check("tambah acara rapat tim besok jam 10", "AddEvent(title=rapat tim, start=2026-11-21T10:00, end=null, allDay=false)")
        check("buat jadwal dokter gigi tanggal 25 jam 9 pagi", "AddEvent(title=dokter gigi, start=2026-11-25T09:00, end=null, allDay=false)")
        check("jadwalkan meeting dengan klien hari senin jam 2 siang selama 2 jam", "AddEvent(title=meeting dengan klien, start=2026-11-23T14:00, end=2026-11-23T16:00, allDay=false)")
        check("tambahkan ulang tahun ibu ke kalender tanggal 3 desember", "AddEvent(title=ulang tahun ibu, start=2026-12-03T00:00, end=null, allDay=true)")
        check("buat acara makan malam keluarga sabtu jam 7 malam sampai jam 9", "AddEvent(title=makan malam keluarga, start=2026-11-21T19:00, end=2026-11-21T21:00, allDay=false)")
        check("schedule lunch with Sarah tomorrow at noon", "AddEvent(title=lunch with Sarah, start=2026-11-21T12:00, end=null, allDay=false)")
        check("add an event dentist appointment on December 5th at 3 pm", "AddEvent(title=dentist appointment, start=2026-12-05T15:00, end=null, allDay=false)")
        check("jadwal rapat besok jam 10", "AddEvent(title=rapat, start=2026-11-21T10:00, end=null, allDay=false)")
        check("tambah acara", "NeedInfo(pending=EventTime(title=))")
        check("jadwal saya hari ini apa saja", "ShowAgenda(date=2026-11-20)")
        check("ada acara apa besok", "ShowAgenda(date=2026-11-21)")
        check("what's on my calendar tomorrow", "ShowAgenda(date=2026-11-21)")
        check("ingatkan saya bayar listrik senin jam 9", "SetReminder(text=bayar listrik, at=2026-11-23T09:00)")
    }

    @Test
    fun todo() {
        check("tambah tugas beli susu", "TodoAdd(text=beli susu)")
        check("tambahkan cuci mobil ke daftar tugas", "TodoAdd(text=cuci mobil)")
        check("add buy milk to my to do list", "TodoAdd(text=buy milk)")
        check("tambah tugas", "NeedInfo(pending=TodoText)")
        check("apa saja tugas saya hari ini", "TodoList")
        check("apa yang harus saya kerjakan hari ini", "TodoList")
        check("what's on my to do list", "TodoList")
        check("tandai tugas 2 selesai", "TodoDone(ref=2)")
        check("tugas beli susu sudah selesai", "TodoDone(ref=beli susu)")
        check("selesaikan tugas pertama", "TodoDone(ref=1)")
        check("mark task 3 as done", "TodoDone(ref=3)")
        check("hapus tugas 1", "TodoRemove(ref=1)")
        check("hapus tugas yang sudah selesai", "TodoClearDone")
        check("hapus semua tugas", "TodoClearAll")
    }

    @Test
    fun externalApps() {
        check("bukain whatsapp", "OpenApp(query=whatsapp)")
        check("buka wa dong", "OpenApp(query=wa)")
        check("tolong buka instagram", "OpenApp(query=instagram)")
        check("putar youtube", "OpenApp(query=youtube)")
        check("putar lagu bohemian rhapsody di spotify", "PlayMedia(query=bohemian rhapsody, app=spotify)")
        check("cari resep rendang di youtube", "PlayMedia(query=resep rendang, app=youtube)")
        check("mainkan lagu tulus", "PlayMedia(query=tulus, app=null)")
        check("navigasi ke monas", "Navigate(destination=monas, drive=true)")
        check("cari bengkel terdekat di maps", "Navigate(destination=bengkel terdekat, drive=false)")
        check("google harga emas hari ini", "WebSearch(query=harga emas hari ini)")
        check("buka website detik dot com", "OpenUrl(url=detik.com)")
        check("kirim wa ke budi isinya aku otw", "WhatsApp(target=budi, body=aku otw)")
        check("kirim pesan ke ibu lewat wa bilang aku pulang telat", "WhatsApp(target=ibu, body=aku pulang telat)")
        check("whatsapp mom saying I'm on my way", "WhatsApp(target=mom, body=I'm on my way)")
        check("kirim pesan ke Budi isinya aku sudah sampai", "SendSms(target=Budi, body=aku sudah sampai)")
    }

    @Test
    fun contextFollowUps() {
        check("ubah jadi jam 11", "MoveLastEvent(timeText=jam 11)")
        check("pindahkan acara tadi ke besok jam 3 sore", "MoveLastEvent(timeText=besok jam 3 sore)")
        check("pindahkan acara terakhir ke jam 3 sore", "MoveLastEvent(timeText=jam 3 sore)")
        check("move the last event to 3 pm", "MoveLastEvent(timeText=3 pm)")
        check("change it to tomorrow at 9 am", "MoveLastEvent(timeText=tomorrow at 9 am)")
        check("undur 1 jam", "ShiftLastEvent(seconds=3600)")
        check("majukan acaranya 30 menit", "ShiftLastEvent(seconds=-1800)")
        check("push back the meeting by 30 minutes", "ShiftLastEvent(seconds=1800)")
        check("hapus acara tadi", "DeleteLastEvent")
        check("hapus acara terakhir", "DeleteLastEvent")
        check("cancel that meeting", "DeleteLastEvent")
        check("tandai yang tadi selesai", "TodoDone(ref=-1)")
        check("tandai tugas beli susu selesai", "TodoDone(ref=beli susu)")
        check("ubah volume ke 50", "Volume(action=SET, percent=50)")
        check("ganti bahasa ke inggris", "SwitchLanguage(lang=EN)")
    }

    @Test
    fun questionsGoToAi() {
        check("kenapa alarm saya tidak bunyi", "Unknown(text=kenapa alarm saya tidak bunyi)")
        check("how do I set an alarm", "Unknown(text=how do I set an alarm)")
        check("apa itu timer pomodoro", "Unknown(text=apa itu timer pomodoro)")
        check("berapa sisa baterai", "Battery")
        check("apa jadwal saya hari senin", "ShowAgenda(date=2026-11-23)")
        check("bisakah kamu pasang alarm jam 7 pagi", "SetAlarm(time=2026-11-21T07:00, label=null)")
    }

    @Test
    fun slotFilling() {
        slot(Pending.AlarmTime(null), "jam 7 pagi", "SetAlarm(time=2026-11-21T07:00, label=null)")
        slot(Pending.AlarmTime(null), "tidak jadi", "Cancelled")
        slot(Pending.AlarmTime("gym"), "besok jam enam", "SetAlarm(time=2026-11-21T18:00, label=gym)")
        slot(Pending.TimerDuration(null), "lima belas menit", "SetTimer(seconds=900, label=null)")
        slot(Pending.TimerDuration(null), "buka whatsapp", "NotAnAnswer")
        slot(Pending.ReminderTime("beli susu"), "jam 5 sore", "SetReminder(text=beli susu, at=2026-11-20T17:00)")
        slot(Pending.ReminderTime("beli susu"), "10 menit lagi", "SetTimer(seconds=600, label=beli susu)")
        slot(Pending.NoteText, "tidak lupa beli susu", "AddNote(text=tidak lupa beli susu)")
        slot(Pending.NoteText, "batal", "Cancelled")
        slot(Pending.SmsBody("Budi"), "aku terlambat", "SendSms(target=Budi, body=aku terlambat)")
        val conf = Pending.Confirm(Command.ClearNotes)
        slot(conf, "ya", "ClearNotes")
        slot(conf, "oke kirim", "ClearNotes")
        slot(conf, "yes", "ClearNotes")
        slot(conf, "tidak", "Cancelled")
        slot(conf, "cancel", "Cancelled")
        slot(conf, "buka whatsapp", "NotAnAnswer")
        slot(Pending.ReminderText, "minum obat jam 9 malam", "SetReminder(text=minum obat, at=2026-11-20T21:00)")
        slot(Pending.CallTarget, "ibu", "Call(target=ibu)")
        slot(Pending.EventTime("rapat"), "besok jam 9 pagi", "AddEvent(title=rapat, start=2026-11-21T09:00, end=null, allDay=false)")
        slot(Pending.TodoText, "beli susu", "TodoAdd(text=beli susu)")
    }

    @Test
    fun numberWords() {
        assertEquals("jam 7 15", NumberWords.normalize("jam tujuh lima belas"))
        assertEquals("25 minutes and 25 seconds", NumberWords.normalize("twenty-five minutes and twenty five seconds"))
        assertEquals("0 8 1 2", NumberWords.normalize("nol delapan satu dua"))
        assertEquals("23", NumberWords.normalize("dua puluh tiga"))
    }

}