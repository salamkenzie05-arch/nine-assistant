package id.nine.assistant.core

/** Riwayat percakapan di memori (tidak disimpan ke disk). */
class ConversationLog {
    data class Entry(val fromUser: Boolean, var text: String)

    interface Listener {
        fun onAdded(index: Int)
        fun onChanged(index: Int)
        fun onCleared()
    }

    val entries = ArrayList<Entry>()
    private val listeners = LinkedHashSet<Listener>()

    fun addListener(l: Listener) {
        listeners.add(l)
    }

    fun removeListener(l: Listener) {
        listeners.remove(l)
    }

    fun add(fromUser: Boolean, text: String): Int {
        entries.add(Entry(fromUser, text))
        val i = entries.size - 1
        for (l in listeners.toList()) l.onAdded(i)
        return i
    }

    fun update(index: Int, text: String) {
        if (index !in entries.indices) return
        entries[index].text = text
        for (l in listeners.toList()) l.onChanged(index)
    }

    fun clear() {
        entries.clear()
        for (l in listeners.toList()) l.onCleared()
    }
}
