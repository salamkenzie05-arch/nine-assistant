package id.nine.assistant.core

import java.time.DayOfWeek
import java.time.LocalDateTime
import java.time.format.TextStyle

/** Format waktu/durasi yang enak diucapkan oleh mesin Text-to-Speech. */
object Fmt {

    private fun hour12(h: Int): Int = if (h % 12 == 0) 12 else h % 12

    private fun partId(h: Int): String = when (h) {
        in 4..10 -> "pagi"
        in 11..14 -> "siang"
        in 15..17 -> "sore"
        else -> "malam"
    }

    fun clock(h: Int, m: Int, lang: AppLang): String =
        if (lang == AppLang.ID) {
            if (m == 0) "pukul ${hour12(h)} ${partId(h)}"
            else "pukul ${hour12(h)} lewat $m menit ${partId(h)}"
        } else {
            val ampm = if (h < 12) "AM" else "PM"
            String.format("%d:%02d %s", hour12(h), m, ampm)
        }

    private fun dayName(d: DayOfWeek, lang: AppLang): String = d.getDisplayName(TextStyle.FULL, lang.locale)

    /** "besok pukul 7 pagi", "pukul 19 ..." dst. [now] dipakai untuk menentukan hari ini/besok. */
    fun at(t: LocalDateTime, lang: AppLang, now: LocalDateTime): String {
        val days = java.time.temporal.ChronoUnit.DAYS.between(now.toLocalDate(), t.toLocalDate())
        val c = clock(t.hour, t.minute, lang)
        val prefix = when {
            days == 0L -> ""
            days == 1L -> lang.t("besok ", "tomorrow ")
            days in 2..6 -> lang.t("hari ${dayName(t.dayOfWeek, lang)} ", "on ${dayName(t.dayOfWeek, lang)} ")
            else -> lang.t("tanggal ${t.dayOfMonth}/${t.monthValue} ", "on ${t.monthValue}/${t.dayOfMonth} ")
        }
        return prefix + c
    }

    fun duration(totalSeconds: Int, lang: AppLang): String {
        val h = totalSeconds / 3600
        val m = (totalSeconds % 3600) / 60
        val s = totalSeconds % 60
        val parts = ArrayList<String>()
        if (lang == AppLang.ID) {
            if (h > 0) parts.add("$h jam")
            if (m > 0) parts.add("$m menit")
            if (s > 0 || parts.isEmpty()) parts.add("$s detik")
        } else {
            if (h > 0) parts.add(if (h == 1) "1 hour" else "$h hours")
            if (m > 0) parts.add(if (m == 1) "1 minute" else "$m minutes")
            if (s > 0 || parts.isEmpty()) parts.add(if (s == 1) "1 second" else "$s seconds")
        }
        return parts.joinToString(" ")
    }
}
