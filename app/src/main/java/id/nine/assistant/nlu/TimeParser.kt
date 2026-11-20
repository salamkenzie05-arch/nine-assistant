package id.nine.assistant.nlu

import java.time.DayOfWeek
import java.time.LocalDateTime
import java.time.LocalTime

/** Bagian hari. Dipakai untuk menentukan AM/PM, mis. "jam 7 malam" = 19:00. */
enum class Part(val defaultHour: Int) { MORNING(8), NOON(12), AFTERNOON(16), EVENING(18), NIGHT(20) }

/**
 * Waktu yang dipahami dari kalimat.
 * [hour] 0..23 bila jam diketahui. Bila [ambiguous] true, [hour] bernilai 1..12 dan AM/PM dipilih saat [resolve].
 */
data class TimeSpec(
    val hour: Int?,
    val minute: Int,
    val ambiguous: Boolean,
    val dayOffset: Int?,
    val weekday: DayOfWeek?,
    val relativeSeconds: Int?
) {
    val isKnown: Boolean get() = hour != null || relativeSeconds != null

    fun resolve(now: LocalDateTime): LocalDateTime? {
        if (relativeSeconds != null) return now.plusSeconds(relativeSeconds.toLong())
        val h = hour ?: return null
        val today = now.toLocalDate()
        var date = today
        var futureDay = false
        if (weekday != null) {
            val diff = ((weekday.value - today.dayOfWeek.value) + 7) % 7
            date = today.plusDays(diff.toLong())
            futureDay = diff > 0
        } else if (dayOffset != null) {
            date = today.plusDays(dayOffset.toLong())
            futureDay = dayOffset > 0
        }
        val explicitDay = weekday != null || dayOffset != null
        fun at(hh: Int): LocalDateTime = LocalDateTime.of(date, LocalTime.of(hh, minute))

        if (ambiguous) {
            if (futureDay) {
                val hh = when {
                    h == 12 -> 12
                    h in 1..6 -> h + 12
                    else -> h
                }
                return at(hh)
            }
            val cands = listOf(at(h % 12), at(h % 12 + 12))
            val upcoming = cands.firstOrNull { it.isAfter(now) }
            if (upcoming != null) return upcoming
            return if (explicitDay) cands.last() else cands.first().plusDays(1)
        }
        val dt = at(h)
        if (dt.isAfter(now) || futureDay) return dt
        if (explicitDay) return if (weekday != null) dt.plusDays(7) else dt
        return dt.plusDays(1)
    }
}

data class ParsedTime(val spec: TimeSpec, val remainder: String)

object TimeParser {
    private fun rx(p: String) = Regex(p, RegexOption.IGNORE_CASE)

    private const val PRE = """(?:jam|pukul|pkl|at|around|by)"""

    private val reRelBefore = rx("""\b(?:dalam|in|after|setelah)\s*$""")
    private val reRelAfter = rx("""^\s*(?:lagi|from\s+now|dari\s+sekarang|later)\b""")

    private val reHm = rx("""(?:\b$PRE\s*)?(\d{1,2})[.:](\d{2})(?!\d)\s*(a\.?m\.?|p\.?m\.?)?(?![a-z])""")
    private val reSpaced = rx("""\b$PRE\s*(\d{1,2})\s+(\d{2})(?!\d)(?!\s*(?:menit|minutes?|mins?|jam|hours?|detik|seconds?))""")
    private val reAmPm = rx("""\b(\d{1,2})\s*(a\.?m\.?|p\.?m\.?)(?![a-z])""")
    private val reHour = rx("""\b$PRE\s*(\d{1,2})(?!\d)(?:\s*(?:o'?clock|tepat))?(?:\s+(lewat|lebih|kurang|past)\s+(\d{1,2})(?!\d)(?:\s*(?:menit|minutes?|mins?))?)?""")
    private val reOclock = rx("""\b(\d{1,2})\s*o'?clock\b""")
    private val reSetengah = rx("""(?:\b$PRE\s*)?\bsetengah\s+(\d{1,2})(?!\d)""")
    private val reHalfQuarter = rx("""\b(half|quarter)\s+past\s+(\d{1,2})(?!\d)""")
    private val reQuarterTo = rx("""\bquarter\s+to\s+(\d{1,2})(?!\d)""")
    private val reNoonMid = rx("""\b(noon|midday|tengah\s+hari|midnight|tengah\s+malam)\b""")
    private val reBarePart = rx("""\b(\d{1,2})(?:[.:](\d{2}))?(?=\s*(?:(?:nanti|this|in\s+the|at|di|pada)\s+)?(?:pagi|siang|sore|malam|subuh|morning|afternoon|evening|night)\b)""")

    private val reDay = rx("""\b(?:hari\s+)?(hari\s+ini|today|besok|tomorrow|lusa|day\s+after\s+tomorrow|senin|selasa|rabu|kamis|jumat|jum'at|sabtu|minggu(?!\s+(?:depan|lalu|ini))|monday|tuesday|wednesday|thursday|friday|saturday|sunday)\b""")
    private val rePart = rx("""(?:\b(nanti|this|in\s+the|at|di|pada)\s+)?\b(pagi|siang|sore|malam|dini\s+hari|subuh|morning|afternoon|evening|night|tonight)\b(\s+ini)?""")

    private fun dayOffsetOf(w: String): Int? = when (w.lowercase().replace(Regex("\\s+"), " ")) {
        "hari ini", "today" -> 0
        "besok", "tomorrow" -> 1
        "lusa", "day after tomorrow" -> 2
        else -> null
    }

    private fun weekdayOf(w: String): DayOfWeek? = when (w.lowercase()) {
        "senin", "monday" -> DayOfWeek.MONDAY
        "selasa", "tuesday" -> DayOfWeek.TUESDAY
        "rabu", "wednesday" -> DayOfWeek.WEDNESDAY
        "kamis", "thursday" -> DayOfWeek.THURSDAY
        "jumat", "jum'at", "friday" -> DayOfWeek.FRIDAY
        "sabtu", "saturday" -> DayOfWeek.SATURDAY
        "minggu", "sunday" -> DayOfWeek.SUNDAY
        else -> null
    }

    private fun partOf(w: String): Part = when (w.lowercase().replace(Regex("\\s+"), " ")) {
        "pagi", "subuh", "dini hari", "morning" -> Part.MORNING
        "siang" -> Part.NOON
        "sore", "afternoon" -> Part.AFTERNOON
        "evening" -> Part.EVENING
        "malam", "night", "tonight" -> Part.NIGHT
        else -> Part.MORNING
    }

    private fun applyPart(h: Int, part: Part): Int = when (part) {
        Part.MORNING -> if (h == 12) 0 else h
        Part.NOON -> when {
            h == 12 -> 12
            h in 1..5 -> h + 12
            else -> h
        }
        Part.AFTERNOON, Part.EVENING -> if (h in 1..11) h + 12 else h
        Part.NIGHT -> when {
            h == 12 -> 0
            h in 6..11 -> h + 12
            else -> h
        }
    }

    private class Clock(val hour: Int, val minute: Int, val meridiem: Char?, val range: IntRange)

    private fun precededByDigit(text: String, start: Int): Boolean =
        text.substring(0, start).trimEnd().lastOrNull()?.isDigit() == true

    private fun findClock(text: String): Clock? {
        // 1) 7.30 / 07:30 / jam 7.30 / 7:30 pm
        for (m in reHm.findAll(text)) {
            if (m.value[0].isLetter() && precededByDigit(text, m.range.first)) continue
            val h = m.groupValues[1].toInt()
            val mi = m.groupValues[2].toInt()
            if (mi > 59) continue
            val mer = m.groupValues[3].lowercase().firstOrNull()
            if (mer != null && h !in 1..12) continue
            if (mer == null && h > 23) continue
            return Clock(h, mi, mer, m.range)
        }
        // 2) jam 7 30
        for (m in reSpaced.findAll(text)) {
            if (precededByDigit(text, m.range.first)) continue
            val h = m.groupValues[1].toInt()
            val mi = m.groupValues[2].toInt()
            if (h > 23 || mi > 59) continue
            return Clock(h, mi, null, m.range)
        }
        // 3) 7 pm
        for (m in reAmPm.findAll(text)) {
            val h = m.groupValues[1].toInt()
            if (h !in 1..12) continue
            return Clock(h, 0, m.groupValues[2].lowercase().first(), m.range)
        }
        // 4) jam 7 / at 7 / jam 7 lewat 15 / jam 8 kurang 10
        for (m in reHour.findAll(text)) {
            if (precededByDigit(text, m.range.first)) continue
            var h = m.groupValues[1].toInt()
            if (h > 23) continue
            var mi = 0
            val op = m.groupValues[2].lowercase()
            val n = m.groupValues[3]
            if (op.isNotEmpty() && n.isNotEmpty()) {
                val v = n.toInt()
                if (v > 59) continue
                if (op == "kurang") {
                    if (v == 0) continue
                    h = if (h == 0) 23 else h - 1
                    mi = 60 - v
                } else {
                    mi = v
                }
            }
            return Clock(h, mi, null, m.range)
        }
        // 4b) 7 o'clock
        for (m in reOclock.findAll(text)) {
            val h = m.groupValues[1].toInt()
            if (h > 23) continue
            return Clock(h, 0, null, m.range)
        }
        // 5) setengah 8 -> 7:30
        for (m in reSetengah.findAll(text)) {
            val h = m.groupValues[1].toInt()
            if (h !in 1..12) continue
            return Clock(if (h == 1) 12 else h - 1, 30, null, m.range)
        }
        // 6) half past 7 / quarter past 7 / quarter to 8
        for (m in reHalfQuarter.findAll(text)) {
            val h = m.groupValues[2].toInt()
            if (h !in 1..12) continue
            return Clock(h, if (m.groupValues[1].lowercase() == "half") 30 else 15, null, m.range)
        }
        for (m in reQuarterTo.findAll(text)) {
            val h = m.groupValues[1].toInt()
            if (h !in 1..12) continue
            return Clock(if (h == 1) 12 else h - 1, 45, null, m.range)
        }
        // 7) noon / midnight
        reNoonMid.find(text)?.let { m ->
            val w = m.groupValues[1].lowercase()
            val h = if (w == "noon" || w == "midday" || w.startsWith("tengah hari")) 12 else 0
            return Clock(h, 0, null, m.range)
        }
        // 8) "7 pagi" (angka langsung diikuti bagian hari)
        for (m in reBarePart.findAll(text)) {
            val h = m.groupValues[1].toInt()
            if (h !in 1..12) continue
            val mi = m.groupValues[2].ifEmpty { "0" }.toInt()
            if (mi > 59) continue
            return Clock(h, mi, null, m.range)
        }
        return null
    }

    private fun near(a: IntRange, b: IntRange): Boolean =
        (b.first - a.last in 0..3) || (a.first - b.last in 0..3)

    private class PartHit(val part: Part, val range: IntRange, val strong: Boolean)

    private fun findParts(text: String): List<PartHit> = rePart.findAll(text).map { m ->
        val prefix = m.groupValues[1].lowercase()
        val word = m.groupValues[2]
        val strong = prefix == "nanti" || prefix == "this" || m.groupValues[3].isNotEmpty() || word.lowercase() == "tonight"
        PartHit(partOf(word), m.range, strong)
    }.toList()

    /**
     * Cari informasi waktu di [text] (angka sudah dinormalisasi). Null bila tidak ada sama sekali.
     * [ParsedTime.remainder] adalah teks tanpa frasa waktu.
     */
    fun parse(text: String): ParsedTime? {
        // Relatif: "10 menit lagi", "dalam 2 jam", "in 5 minutes"
        val dur = Durations.find(text)
        if (dur != null) {
            val before = text.substring(0, dur.range.first)
            val after = text.substring(dur.range.last + 1)
            val b = reRelBefore.find(before)
            val a = reRelAfter.find(after)
            if (b != null || a != null) {
                val start = if (b != null) b.range.first else dur.range.first
                val end = if (a != null) dur.range.last + 1 + a.range.last else dur.range.last
                val spec = TimeSpec(null, 0, false, null, null, dur.seconds)
                return ParsedTime(spec, tidy(removeRanges(text, listOf(start..end))))
            }
        }

        val ranges = ArrayList<IntRange>()
        val clock = findClock(text)
        if (clock != null) ranges.add(clock.range)

        var dayOffset: Int? = null
        var weekday: DayOfWeek? = null
        var dayRange: IntRange? = null
        reDay.find(text)?.let { m ->
            val w = m.groupValues[1]
            dayOffset = dayOffsetOf(w)
            weekday = weekdayOf(w)
            dayRange = m.range
            ranges.add(m.range)
        }

        var part: Part? = null
        val anchors = ArrayList<IntRange>()
        clock?.let { anchors.add(it.range) }
        dayRange?.let { anchors.add(it) }
        for (hit in findParts(text)) {
            val ok = hit.strong || anchors.any { near(it, hit.range) }
            if (ok) {
                part = hit.part
                ranges.add(hit.range)
                break
            }
        }

        if (clock == null && dayRange == null && part == null) return null

        var hour: Int? = null
        var minute = 0
        var ambiguous = false
        if (clock != null) {
            minute = clock.minute
            val h = clock.hour
            when {
                clock.meridiem != null -> {
                    hour = (h % 12) + (if (clock.meridiem == 'p') 12 else 0)
                }
                h == 0 || h > 12 -> hour = h
                part != null -> hour = applyPart(h, part)
                else -> {
                    hour = h
                    ambiguous = true
                }
            }
        } else if (part != null) {
            hour = part.defaultHour
        }

        val spec = TimeSpec(hour, minute, ambiguous, dayOffset, weekday, null)
        return ParsedTime(spec, tidy(removeRanges(text, ranges)))
    }

    private fun removeRanges(text: String, ranges: List<IntRange>): String {
        // gabungkan dan hapus dari belakang
        val sorted = ranges.sortedByDescending { it.first }
        val sb = StringBuilder(text)
        var lastStart = Int.MAX_VALUE
        for (r in sorted) {
            if (r.first >= lastStart) continue // tumpang tindih: sudah terhapus
            val end = minOf(r.last, lastStart - 1)
            sb.delete(r.first, end + 1)
            lastStart = r.first
        }
        return sb.toString()
    }

    private val reLead = rx("""^(?:(?:pada|di|at|on|in|for|untuk|agar|bahwa|that|to|nanti|ini|the)\s+)+""")
    private val reTrail = rx("""(?:\s+(?:pada|di|at|on|in|for|nanti|dan|and|ini|jam|pukul))+$""")

    private fun tidy(s: String): String {
        var t = s.replace(Regex("\\s+"), " ").trim().trim(',', '.', ' ')
        t = reLead.replaceFirst(t, "")
        t = reTrail.replaceFirst(t, "")
        return t.trim().trim(',', '.', ' ')
    }
}
