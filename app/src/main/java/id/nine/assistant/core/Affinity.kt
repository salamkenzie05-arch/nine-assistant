package id.nine.assistant.core

import android.content.Context
import java.time.LocalDate
import java.time.temporal.ChronoUnit

/**
 * Tingkat kedekatan dengan Nine (seperti streak): 9 level berdasarkan XP, plus streak harian.
 *
 * - +10 XP untuk interaksi pertama setiap hari, +2 XP per interaksi berikutnya (maks. 20 XP per hari dari ini)
 * - Bonus streak: +1 XP per hari streak (maks. +15) pada interaksi pertama hari itu
 * - Streak bertambah bila Anda bicara dengan Nine setiap hari; terlewat satu hari = streak kembali ke 1
 * - XP tidak pernah berkurang, jadi level tidak turun
 * - Level dibatasi oleh hari pemakaian ([AffinityRules]): level tertinggi baru terbuka setelah 90 hari pemakaian
 *
 * Setiap level punya [Level.perkKey] sebagai kaitan untuk fitur/easter egg di masa depan.
 */
class Affinity(context: Context) {

    data class Level(val number: Int, val minXp: Int, val nameId: String, val nameEn: String, val perkKey: String) {
        fun name(lang: AppLang): String = lang.t(nameId, nameEn)
    }

    data class State(
        val xp: Int,
        val level: Level,
        val next: Level?,
        val streak: Int,
        val bestStreak: Int,
        val todayCount: Int,
        val total: Int,
        val activeToday: Boolean,
        /** Jumlah hari berbeda Anda berbicara dengan Nine. */
        val days: Int = 0
    ) {
        /** Progres 0..1 menuju level berikutnya (yang lebih lambat antara XP dan hari pemakaian). */
        val progress: Float
            get() = if (next == null) 1f else minOf(
                ((xp - level.minXp).toFloat() / (next.minXp - level.minXp)).coerceIn(0f, 1f),
                AffinityRules.daysProgress(level.number, days)
            )
    }

    /** Hasil satu interaksi: naik level? streak baru? */
    data class Event(val state: State, val leveledUp: Boolean, val streakExtended: Boolean)

    companion object {
        val LEVELS = listOf(
            // Nama level sengaja memakai istilah Inggris yang sama di kedua bahasa (seperti pangkat dalam game).
            Level(1, 0, "Stranger", "Stranger", "lv1"),
            Level(2, 60, "Aware", "Aware", "lv2"),
            Level(3, 170, "Acquainted", "Acquainted", "lv3"),
            Level(4, 340, "Interested", "Interested", "lv4"),
            Level(5, 600, "Familiar", "Familiar", "lv5"),
            Level(6, 950, "Connected", "Connected", "lv6"),
            Level(7, 1200, "Trusted", "Trusted", "lv7"),
            Level(8, 1450, "Loyal", "Loyal", "lv8"),
            Level(9, 2300, "Devoted", "Devoted", "lv9")
        )

        fun levelFor(xp: Int): Level = LEVELS.last { xp >= it.minXp }
        fun nextAfter(level: Level): Level? = LEVELS.getOrNull(level.number)
    }

    private val sp = context.applicationContext.getSharedPreferences("nine_affinity", Context.MODE_PRIVATE)

    fun state(today: LocalDate = LocalDate.now()): State {
        val xp = sp.getInt("xp", 0)
        val last = sp.getString("last", null)?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
        var streak = sp.getInt("streak", 0)
        // Streak putus bila hari kemarin terlewat (ditampilkan 0 sampai Anda bicara lagi).
        if (last != null && ChronoUnit.DAYS.between(last, today) > 1) streak = 0
        val days = daysUsed(xp)
        val level = LEVELS[AffinityRules.effectiveLevel(levelFor(xp).number, days) - 1]
        return State(
            xp = xp,
            level = level,
            next = nextAfter(level),
            streak = streak,
            bestStreak = sp.getInt("best", 0),
            todayCount = if (last == today) sp.getInt("today", 0) else 0,
            total = sp.getInt("total", 0),
            activeToday = last == today,
            days = days
        )
    }

    /** Hari pemakaian; untuk data lama (tanpa hitungan) dipakai streak terbaik sebagai perkiraan. */
    private fun daysUsed(xp: Int): Int =
        if (sp.contains("days")) sp.getInt("days", 0) else if (xp > 0) maxOf(1, sp.getInt("best", 0)) else 0

    /** Poin tambahan (mis. kejutan harian). Tidak mengubah streak atau hari pemakaian; level tetap dibatasi hari pemakaian. */
    fun bonus(points: Int, today: LocalDate = LocalDate.now()): Event {
        val before = state(today)
        sp.edit().putInt("xp", before.xp + points.coerceIn(0, 50)).apply()
        val after = state(today)
        return Event(after, after.level.number > before.level.number, false)
    }

    /** Catat satu interaksi dengan Nine. */
    fun record(today: LocalDate = LocalDate.now()): Event {
        val before = state(today)
        val last = sp.getString("last", null)?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
        var xp = before.xp
        var streak = sp.getInt("streak", 0)
        var todayCount = if (last == today) sp.getInt("today", 0) else 0
        var extended = false
        var days = daysUsed(xp)
        if (last != today) {
            days++
            streak = if (last != null && ChronoUnit.DAYS.between(last, today) == 1L) streak + 1 else 1
            extended = true
            xp += 10 + minOf(streak, 15)
        } else if (todayCount < 10) {
            xp += 2
        }
        todayCount++
        val best = maxOf(sp.getInt("best", 0), streak)
        sp.edit()
            .putInt("xp", xp)
            .putInt("streak", streak)
            .putInt("best", best)
            .putInt("today", todayCount)
            .putInt("days", days)
            .putInt("total", sp.getInt("total", 0) + 1)
            .putString("last", today.toString())
            .apply()
        val after = state(today)
        return Event(after, after.level.number > before.level.number, extended)
    }
}
