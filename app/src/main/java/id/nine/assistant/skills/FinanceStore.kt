package id.nine.assistant.skills

import android.content.Context
import id.nine.assistant.nlu.FinType
import id.nine.assistant.nlu.Txn
import org.json.JSONArray
import org.json.JSONObject

/**
 * Catatan pemasukan & pengeluaran, disimpan lokal di penyimpanan pribadi aplikasi (tidak dikirim ke mana pun).
 * Sengaja tidak dienkripsi dengan Keystore: bila kunci Keystore hilang (mis. setelah ganti kunci layar),
 * catatan tidak akan bisa dibuka lagi. Penyimpanan pribadi aplikasi sudah terisolasi oleh Android dan tidak ikut backup.
 */
class FinanceStore(context: Context) {

    private val sp = context.applicationContext.getSharedPreferences("nine_finance", Context.MODE_PRIVATE)

    @Synchronized
    fun all(): List<Txn> = load()

    @Synchronized
    fun add(type: FinType, amount: Long, cat: String, note: String, time: Long = System.currentTimeMillis()): Txn {
        val items = load()
        val id = (items.maxOfOrNull { it.id } ?: 0L) + 1
        val t = Txn(id, time, type, amount, cat, note.trim().take(MAX_NOTE))
        items.add(t)
        // Batas wajar supaya penyimpanan tidak membengkak; yang paling lama dibuang lebih dulu.
        val kept = if (items.size > MAX_ITEMS) items.sortedBy { it.time }.takeLast(MAX_ITEMS) else items
        save(kept)
        return t
    }

    /** Hapus catatan terakhir yang dibuat (id terbesar). Mengembalikan catatan yang dihapus, atau null bila kosong. */
    @Synchronized
    fun undoLast(): Txn? {
        val items = load()
        val last = items.maxByOrNull { it.id } ?: return null
        save(items.filter { it.id != last.id })
        return last
    }

    @Synchronized
    fun remove(id: Long): Boolean {
        val items = load()
        val out = items.filter { it.id != id }
        if (out.size == items.size) return false
        save(out)
        return true
    }

    @Synchronized
    fun clear() {
        sp.edit().remove(KEY).apply()
    }

    private fun load(): MutableList<Txn> {
        val raw = sp.getString(KEY, null) ?: return ArrayList()
        return try {
            val arr = JSONArray(raw)
            val out = ArrayList<Txn>(arr.length())
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val type = if (o.optString("y") == "I") FinType.INCOME else FinType.EXPENSE
                val amount = o.getLong("a")
                if (amount <= 0) continue
                out.add(Txn(o.getLong("id"), o.getLong("t"), type, amount, o.optString("c", "other"), o.optString("n", "")))
            }
            out
        } catch (e: Exception) {
            // Data rusak: simpan salinannya sekali (bukan ditimpa diam-diam) lalu mulai dari kosong.
            if (!sp.contains(KEY_BAD)) sp.edit().putString(KEY_BAD, raw).apply()
            ArrayList()
        }
    }

    private fun save(items: List<Txn>) {
        val arr = JSONArray()
        for (t in items) {
            arr.put(
                JSONObject()
                    .put("id", t.id)
                    .put("t", t.time)
                    .put("y", if (t.type == FinType.INCOME) "I" else "E")
                    .put("a", t.amount)
                    .put("c", t.cat)
                    .put("n", t.note)
            )
        }
        sp.edit().putString(KEY, arr.toString()).apply()
    }

    companion object {
        private const val KEY = "items"
        private const val KEY_BAD = "items_unreadable_backup"
        private const val MAX_ITEMS = 20_000
        private const val MAX_NOTE = 80
    }
}
