package id.nine.assistant.core

/** Jatah harian (mis. muncratan hati maks. 3 kali sehari). Kotlin murni agar mudah diuji. */
object DailyQuota {
    /** Sisa jatah hari ini bila tersimpan [storedCount] pemakaian pada hari [storedDay]. */
    fun remaining(today: String, storedDay: String, storedCount: Int, max: Int): Int {
        val used = if (storedDay == today) storedCount else 0
        return (max - used).coerceAtLeast(0)
    }

    /** Pakai satu jatah: hasilnya (hari, jumlah baru) untuk disimpan, atau null bila jatah hari ini habis. */
    fun tryUse(today: String, storedDay: String, storedCount: Int, max: Int): Pair<String, Int>? {
        val used = if (storedDay == today) storedCount else 0
        if (used >= max) return null
        return today to (used + 1)
    }
}
