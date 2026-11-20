package id.nine.assistant.nlu

import java.time.DayOfWeek

/**
 * Hari pengulangan alarm: "setiap hari", "hari kerja", "akhir pekan", "setiap senin dan rabu", "every weekday", "on mondays".
 */
object RepeatDays {
    private fun rx(p: String) = Regex(p, RegexOption.IGNORE_CASE)

    private const val DAY =
        """(?:senin|selasa|rabu|kamis|jumat|jum'at|sabtu|minggu|ahad|mondays?|tuesdays?|wednesdays?|thursdays?|fridays?|saturdays?|sundays?)"""
    private const val SEP = """(?:\s*,\s*|\s+(?:dan|and|&|,)\s+|\s+)"""

    private val reWeekdays = rx(
        """\b(?:(?:setiap|tiap|every|on)\s+)?(?:hari\s+kerja|weekdays?|work\s*days?)\b|""" +
            """\b(?:(?:setiap|tiap|every)\s+)?(?:hari\s+)?(?:senin|monday)\s*(?:-|–|sampai|sampai\s+dengan|s\.?d\.?|hingga|to|through|thru)\s*(?:jumat|jum'at|friday)\b"""
    )
    private val reWeekend = rx("""\b(?:(?:setiap|tiap|every|on)\s+)?(?:akhir\s+pekan|akhir\s+minggu|weekends?)\b""")
    private val reDaily = rx("""\b(?:(?:setiap|tiap)\s+hari(?!\s+(?:kerja|senin|selasa|rabu|kamis|jumat|jum'at|sabtu|minggu))|every\s*day|daily|harian)\b""")
    private val reList = rx("""\b(?:setiap|tiap|every|on)\s+(?:hari\s+)?($DAY(?:$SEP(?:hari\s+)?$DAY)*)\b""")
    private val rePlural = rx("""\b((?:mondays|tuesdays|wednesdays|thursdays|fridays|saturdays|sundays)(?:$SEP(?:mondays|tuesdays|wednesdays|thursdays|fridays|saturdays|sundays))*)\b""")

    val ALL: Set<DayOfWeek> = DayOfWeek.values().toSet()
    val WEEKDAYS: Set<DayOfWeek> = setOf(DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY, DayOfWeek.THURSDAY, DayOfWeek.FRIDAY)
    val WEEKEND: Set<DayOfWeek> = setOf(DayOfWeek.SATURDAY, DayOfWeek.SUNDAY)

    private fun dayOf(w: String): DayOfWeek? {
        val x = w.lowercase().removeSuffix("s")
        return when {
            x == "senin" || x == "monday" -> DayOfWeek.MONDAY
            x == "selasa" || x == "tuesday" -> DayOfWeek.TUESDAY
            x == "rabu" || x == "wednesday" -> DayOfWeek.WEDNESDAY
            x == "kamis" || x == "thursday" -> DayOfWeek.THURSDAY
            x.startsWith("jum") || x == "friday" -> DayOfWeek.FRIDAY
            x == "sabtu" || x == "saturday" -> DayOfWeek.SATURDAY
            x == "minggu" || x == "ahad" || x == "sunday" -> DayOfWeek.SUNDAY
            else -> null
        }
    }

    /** Hari pengulangan + teks tanpa frasa itu, atau null bila tidak ada frasa pengulangan. */
    fun extract(text: String): Pair<Set<DayOfWeek>, String>? {
        fun cut(r: IntRange) = (text.substring(0, r.first) + " " + text.substring(r.last + 1)).replace(Regex("\\s+"), " ").trim()
        reWeekdays.find(text)?.let { return WEEKDAYS to cut(it.range) }
        reWeekend.find(text)?.let { return WEEKEND to cut(it.range) }
        reDaily.find(text)?.let { return ALL to cut(it.range) }
        (reList.find(text) ?: rePlural.find(text))?.let { m ->
            val days = Regex(DAY, RegexOption.IGNORE_CASE).findAll(m.groupValues[1]).mapNotNull { dayOf(it.value) }.toSet()
            if (days.isNotEmpty()) return days to cut(m.range)
        }
        return null
    }

    /** "12345" = Senin–Jumat (angka ISO), untuk penyimpanan dan tes. */
    fun code(days: Set<DayOfWeek>): String = days.map { it.value }.sorted().joinToString("")

    fun parseCode(s: String?): Set<DayOfWeek> =
        s.orEmpty().mapNotNull { c -> c.digitToIntOrNull()?.takeIf { it in 1..7 }?.let { DayOfWeek.of(it) } }.toSet()
}

/** "nomor 2", "yang kedua", "the third one", "terakhir" → 2, 2, 3, -1. */
object Ordinals {
    private val words = mapOf(
        "pertama" to 1, "kesatu" to 1, "satu" to 1, "kedua" to 2, "dua" to 2, "ketiga" to 3, "tiga" to 3,
        "keempat" to 4, "empat" to 4, "kelima" to 5, "lima" to 5, "keenam" to 6, "enam" to 6,
        "ketujuh" to 7, "tujuh" to 7, "kedelapan" to 8, "delapan" to 8, "kesembilan" to 9, "sembilan" to 9,
        "kesepuluh" to 10, "sepuluh" to 10,
        "first" to 1, "one" to 1, "second" to 2, "two" to 2, "third" to 3, "three" to 3, "fourth" to 4, "four" to 4,
        "fifth" to 5, "five" to 5, "sixth" to 6, "six" to 6, "seventh" to 7, "seven" to 7, "eighth" to 8, "eight" to 8,
        "ninth" to 9, "nine" to 9, "tenth" to 10, "ten" to 10,
        "terakhir" to -1, "last" to -1
    )
    private val filler = Regex(
        """\b(?:yang|nomor|no\.?|number|the|one|ke|pilih|pilihan|option|choice|saja|aja|itu|deh|dong|please|tolong|acara|alarm|ya)\b|#""",
        RegexOption.IGNORE_CASE
    )

    fun parse(text: String): Int? {
        val t = text.lowercase().trim().trimEnd('.', '!', '?')
        Regex("""^(?:(?:yang|nomor|no\.?|number|pilih|pilihan|option|#)\s*)*(?:ke-?\s*)?(\d{1,2})(?:st|nd|rd|th)?(?:\s+(?:saja|aja|ya|please|one))?$""").find(t)?.let {
            return it.groupValues[1].toInt()
        }
        words[t]?.let { return it }
        val rest = filler.replace(t, " ").replace(Regex("\\s+"), " ").trim()
        words[rest]?.let { return it }
        // "the last one", "yang terakhir"
        if (rest.isEmpty() && Regex("""\b(?:last|terakhir)\b""").containsMatchIn(t)) return -1
        return null
    }
}
