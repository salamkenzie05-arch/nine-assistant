package id.nine.assistant.alarm

import id.nine.assistant.nlu.AlarmRef
import id.nine.assistant.nlu.RepeatDays
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime

/** Logika alarm Nine. Waktu dibekukan: Jumat, 20 November 2026 pukul 11.14. */
class NineAlarmTest {
    private val now = LocalDateTime.of(2026, 11, 20, 11, 14)

    private fun a(id: Int, h: Int, m: Int, label: String? = null, on: Boolean = true, days: String = "", date: LocalDate? = null) =
        NineAlarm(id, h, m, label, on, RepeatDays.parseCode(days), date, id.toLong())

    private val alarms = listOf(
        a(1, 6, 0, "bangun pagi", days = "12345"),
        a(2, 18, 0, "gym"),
        a(3, 7, 30, on = false),
        a(4, 6, 30)
    )

    private fun ids(ref: AlarmRef, op: AlarmMatch.Op, lastId: Int = -1) =
        AlarmMatch.find(ref, alarms, op, now, lastId).map { it.id }

    @Test
    fun nextTrigger() {
        assertEquals(LocalDateTime.of(2026, 11, 21, 6, 0), a(9, 6, 0).nextTrigger(now))
        assertEquals(LocalDateTime.of(2026, 11, 20, 18, 0), a(9, 18, 0).nextTrigger(now))
        // hari kerja: Jumat sudah lewat jam 6, berikutnya Senin
        assertEquals(LocalDateTime.of(2026, 11, 23, 6, 0), a(9, 6, 0, days = "12345").nextTrigger(now))
        assertEquals(LocalDateTime.of(2026, 11, 21, 9, 0), a(9, 9, 0, days = "67").nextTrigger(now))
        assertEquals(LocalDateTime.of(2026, 11, 22, 6, 0), a(9, 6, 0, date = LocalDate.of(2026, 11, 22)).nextTrigger(now))
        assertEquals(setOf(DayOfWeek.MONDAY, DayOfWeek.FRIDAY), RepeatDays.parseCode("15"))
    }

    @Test
    fun matching() {
        // "matikan alarm jam 6" (ambigu) -> 06.00 dan 18.00 (Nine akan bertanya); 06.30 beda menit
        assertEquals(listOf(1, 2), ids(AlarmRef(hour = 6, minute = 0, ambiguous = true), AlarmMatch.Op.OFF))
        // "alarm jam 6 sore" -> 18.00
        assertEquals(listOf(2), ids(AlarmRef(hour = 18, minute = 0), AlarmMatch.Op.OFF))
        assertEquals(listOf(2), ids(AlarmRef(label = "gym"), AlarmMatch.Op.DELETE))
        assertEquals(listOf(1), ids(AlarmRef(label = "bangun"), AlarmMatch.Op.OFF))
        // tanpa rujukan: matikan = yang menyala, nyalakan = yang mati
        assertEquals(listOf(1, 4, 2), ids(AlarmRef(), AlarmMatch.Op.OFF))
        assertEquals(listOf(3), ids(AlarmRef(), AlarmMatch.Op.ON))
        assertEquals(listOf(1, 4, 3, 2), ids(AlarmRef(all = true), AlarmMatch.Op.DELETE))
        // urutan tampilan: 06.00, 06.30, 07.30, 18.00
        assertEquals(listOf(4), ids(AlarmRef(index = 2), AlarmMatch.Op.DELETE))
        assertEquals(listOf(2), ids(AlarmRef(index = -1), AlarmMatch.Op.DELETE))
        assertEquals(listOf(3), ids(AlarmRef(last = true), AlarmMatch.Op.DELETE, lastId = 3))
        // berikutnya: yang menyala & paling cepat berbunyi = 18.00 hari ini
        assertEquals(listOf(2), ids(AlarmRef(next = true), AlarmMatch.Op.OFF))
        assertEquals(emptyList<Int>(), ids(AlarmRef(label = "renang"), AlarmMatch.Op.OFF))
    }
}
