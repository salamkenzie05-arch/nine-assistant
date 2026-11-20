package id.nine.assistant.core

/**
 * Jendela atas [ConversationLog] untuk layar asisten: hanya pesan terakhir pengguna dan jawaban sesudahnya yang
 * tampil, dan hanya yang muncul sesudah layar dibuka. Panel jadi pendek dan tidak ada gelembung yang terpotong.
 * Bila tidak aktif, semua riwayat tampil ([start] = 0). Logika murni (tanpa Android) agar bisa diuji.
 */
class HistoryWindow(private val log: ConversationLog) {
    var enabled = false
        private set

    /** Indeks absolut entri pertama yang tampil. */
    var start = 0
        private set

    private var floor = 0

    val count: Int get() = (log.entries.size - start).coerceAtLeast(0)

    fun enable(on: Boolean) {
        enabled = on
        floor = if (on) log.entries.size else 0
        start = compute()
    }

    /** Dipanggil setelah entri ditambah. True bila awal jendela bergeser (adapter harus memuat ulang semuanya). */
    fun onAdded(): Boolean = recompute()

    /** Dipanggil setelah entri [index] dihapus. True bila awal jendela bergeser. */
    fun onRemoved(index: Int): Boolean {
        if (enabled && index < floor) floor--
        return recompute()
    }

    fun onCleared() {
        start = 0
        floor = 0
    }

    private fun recompute(): Boolean {
        val old = start
        start = compute()
        return start != old
    }

    private fun compute(): Int {
        if (!enabled) return 0
        val e = log.entries
        for (i in e.indices.reversed()) {
            if (i < floor) break
            if (e[i].fromUser) return i
        }
        return floor.coerceAtMost(e.size)
    }
}
