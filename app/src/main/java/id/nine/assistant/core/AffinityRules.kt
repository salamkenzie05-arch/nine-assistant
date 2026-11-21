package id.nine.assistant.core

/**
 * Aturan hari pemakaian untuk level kedekatan: level tertinggi hanya bisa dicapai setelah 90 hari pemakaian
 * (hari berbeda yang Anda bicara dengan Nine), seberapa banyak pun XP-nya. Kotlin murni.
 */
object AffinityRules {
    const val FULL_DAYS = 90

    /** Hari pemakaian minimum untuk level 1..8 (indeks 0..7). */
    val MIN_DAYS = intArrayOf(0, 3, 8, 16, 30, 48, 68, FULL_DAYS)

    /** Level tertinggi (1..8) yang diizinkan oleh [days] hari pemakaian. */
    fun levelByDays(days: Int): Int {
        var level = 1
        for (i in MIN_DAYS.indices) if (days >= MIN_DAYS[i]) level = i + 1
        return level
    }

    /** Level akhir = yang lebih rendah antara level menurut XP dan level menurut hari pemakaian. */
    fun effectiveLevel(xpLevel: Int, days: Int): Int = minOf(xpLevel, levelByDays(days)).coerceIn(1, MIN_DAYS.size)

    /** Progres 0..1 menuju level berikutnya dari sisi hari (1 bila sudah di level tertinggi). */
    fun daysProgress(level: Int, days: Int): Float {
        if (level >= MIN_DAYS.size) return 1f
        val from = MIN_DAYS[level - 1]
        val to = MIN_DAYS[level]
        return ((days - from).toFloat() / (to - from)).coerceIn(0f, 1f)
    }

    /** Sisa hari sampai level tertinggi. */
    fun daysLeft(days: Int): Int = (FULL_DAYS - days).coerceAtLeast(0)
}
