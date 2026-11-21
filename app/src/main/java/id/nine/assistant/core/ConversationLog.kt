package id.nine.assistant.core

/** Riwayat percakapan di memori (tidak disimpan ke disk). */
class ConversationLog {
    /**
     * [private] = isi pribadi: tetap tampil di layar, tetapi TIDAK disimpan ke ruang obrolan dan TIDAK dikirim
     * ke AI sebagai riwayat.
     */
    data class Entry(val fromUser: Boolean, var text: String, var private: Boolean = false)

    interface Listener {
        fun onAdded(index: Int)
        fun onChanged(index: Int)
        fun onCleared()
        fun onRemoved(index: Int) {}
    }

    val entries = ArrayList<Entry>()
    private val listeners = LinkedHashSet<Listener>()

    fun addListener(l: Listener) {
        listeners.add(l)
    }

    fun removeListener(l: Listener) {
        listeners.remove(l)
    }

    fun add(fromUser: Boolean, text: String, private: Boolean = false): Int {
        entries.add(Entry(fromUser, text, private))
        val i = entries.size - 1
        for (l in listeners.toList()) l.onAdded(i)
        return i
    }

    /** Tandai entri sebagai pribadi. */
    fun markPrivate(index: Int) {
        if (index in entries.indices) entries[index].private = true
    }

    fun update(index: Int, text: String) {
        if (index !in entries.indices) return
        entries[index].text = text
        for (l in listeners.toList()) l.onChanged(index)
    }

    fun remove(index: Int) {
        if (index !in entries.indices) return
        entries.removeAt(index)
        for (l in listeners.toList()) l.onRemoved(index)
    }

    fun clear() {
        entries.clear()
        for (l in listeners.toList()) l.onCleared()
    }
}
