package id.nine.assistant.core

import android.os.Handler
import android.os.Looper

/**
 * Menukar isi [ConversationLog] bersama antara obrolan utama (hanya di memori) dan ruang obrolan (tersimpan).
 * Satu instance untuk seluruh aplikasi (dipakai layar utama dan layar asisten). Semua dipanggil dari main thread.
 */
class SpaceSession(private val store: SpaceStore, private val log: ConversationLog) {
    /** Id ruang yang isinya sedang ada di [log]; kosong = obrolan utama. */
    var currentId: String = ""
        private set

    private var stash: List<ConversationLog.Entry> = emptyList()
    private var switching = false
    private val handler = Handler(Looper.getMainLooper())
    private val saveRunnable = Runnable { flush() }

    init {
        log.addListener(object : ConversationLog.Listener {
            override fun onAdded(index: Int) = schedule()
            override fun onChanged(index: Int) = schedule()
            override fun onCleared() = schedule()
            override fun onRemoved(index: Int) = schedule()
        })
    }

    private fun schedule() {
        if (switching || currentId.isEmpty()) return
        handler.removeCallbacks(saveRunnable)
        handler.postDelayed(saveRunnable, 1500L)
    }

    fun active(): SpaceStore.Space? = if (currentId.isEmpty()) null else store.get(currentId)

    /** Tulis ruang yang sedang terbuka ke penyimpanan sekarang juga (panggil saat layar ditutup). */
    fun flush() {
        handler.removeCallbacks(saveRunnable)
        if (currentId.isEmpty() || switching) return
        // Entri pribadi (curhat/krisis) tidak pernah ikut tersimpan.
        store.save(currentId, log.entries.filter { !it.private }.map { SpaceStore.Msg(it.fromUser, it.text) })
    }

    /**
     * Samakan [log] dengan ruang yang dipilih di [SpaceStore.activeId]. True bila isi log berganti
     * (pemanggil perlu mengatur ulang konteks AI-nya).
     */
    fun sync(): Boolean {
        var want = store.activeId
        if (want.isNotEmpty() && store.get(want) == null) {
            want = ""
            store.activeId = ""
        }
        if (want == currentId) return false
        flush()
        switching = true
        try {
            if (currentId.isEmpty()) stash = log.entries.map { it.copy() }
            log.clear()
            currentId = want
            if (want.isEmpty()) {
                for (e in stash) log.add(e.fromUser, e.text, e.private)
                stash = emptyList()
            } else {
                for (m in store.load(want)) log.add(m.fromUser, m.text)
                store.touch(want)
            }
        } finally {
            switching = false
        }
        return true
    }
}
