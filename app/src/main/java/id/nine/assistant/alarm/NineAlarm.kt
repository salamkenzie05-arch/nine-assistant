package id.nine.assistant.alarm

import id.nine.assistant.nlu.AlarmRef
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime

/** Alarm milik Nine (bukan alarm aplikasi Jam). Kotlin murni agar logikanya bisa diuji. */
data class NineAlarm(
    val id: Int,
    val hour: Int,
    val minute: Int,
    val label: String?,
    val enabled: Boolean,
    /** Kosong = sekali saja. */
    val days: Set<DayOfWeek>,
    /** Untuk alarm sekali dengan tanggal tertentu ("alarm lusa jam 6"); null = jam berikutnya. */
    val date: LocalDate?,
    val createdAt: Long
) {
    val repeating: Boolean get() = days.isNotEmpty()

    /** Kapan alarm ini berbunyi berikutnya (dihitung walau sedang mati). */
    fun nextTrigger(now: LocalDateTime): LocalDateTime {
        if (days.isNotEmpty()) {
            var d = now.toLocalDate()
            repeat(8) {
                val t = d.atTime(hour, minute)
                if (d.dayOfWeek in days && t.isAfter(now)) return t
                d = d.plusDays(1)
            }
        }
        if (date != null) {
            val t = date.atTime(hour, minute)
            if (t.isAfter(now)) return t
        }
        val today = now.toLocalDate().atTime(hour, minute)
        return if (today.isAfter(now)) today else today.plusDays(1)
    }

    val clock: String get() = String.format("%02d.%02d", hour, minute)
}

object AlarmMatch {
    enum class Op { ON, OFF, DELETE }

    fun sorted(alarms: List<NineAlarm>): List<NineAlarm> = alarms.sortedWith(compareBy({ it.hour }, { it.minute }, { it.id }))

    private fun words(s: String): List<String> =
        s.lowercase().replace(Regex("[^\\p{L}\\p{N}\\s]"), " ").split(Regex("\\s+")).filter { it.length >= 2 }

    /**
     * Alarm yang dimaksud [ref]. Bila [ref] tidak menyebut alarm tertentu, hasilnya semua alarm yang masuk akal untuk [op]
     * (yang menyala untuk OFF, yang mati untuk ON, semua untuk DELETE); pemanggil memutuskan perlu bertanya atau tidak.
     */
    fun find(ref: AlarmRef, alarms: List<NineAlarm>, op: Op, now: LocalDateTime, lastId: Int): List<NineAlarm> {
        val list = sorted(alarms)
        ref.id?.let { id -> return list.filter { it.id == id } }
        ref.index?.let { n ->
            val i = if (n == -1) list.size - 1 else n - 1
            return listOfNotNull(list.getOrNull(i))
        }
        var pool: List<NineAlarm> = list
        var selected = false
        if (ref.last) {
            val a = list.firstOrNull { it.id == lastId } ?: list.maxByOrNull { it.createdAt }
            return listOfNotNull(a)
        }
        if (ref.next) {
            val a = list.filter { it.enabled }.minByOrNull { it.nextTrigger(now) }
            return listOfNotNull(a)
        }
        if (ref.hour != null) {
            val h = ref.hour
            val m = ref.minute ?: 0
            pool = pool.filter { a ->
                a.minute == m && (if (ref.ambiguous) a.hour % 12 == h % 12 else a.hour == h)
            }
            selected = true
        }
        if (ref.label != null) {
            val ws = words(ref.label)
            if (ws.isNotEmpty()) {
                pool = pool.filter { a ->
                    val l = a.label?.lowercase() ?: ""
                    ws.all { l.contains(it) }
                }
                selected = true
            }
        }
        if (selected && !ref.all) return pool
        return when (op) {
            Op.OFF -> pool.filter { it.enabled }
            Op.ON -> pool.filter { !it.enabled }
            Op.DELETE -> pool
        }
    }
}
