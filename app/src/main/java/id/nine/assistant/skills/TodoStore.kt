package id.nine.assistant.skills

import android.content.Context
import id.nine.assistant.widget.WidgetUpdater
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate

/**
 * Daftar tugas harian, disimpan lokal. Tugas yang belum selesai otomatis terbawa ke hari berikutnya;
 * tugas yang sudah selesai hanya tampil di hari ia diselesaikan.
 */
class TodoStore(context: Context) {

    data class Item(val id: Long, val text: String, val done: Boolean, val created: LocalDate, val doneOn: LocalDate?)

    private val app = context.applicationContext
    private val sp = app.getSharedPreferences("nine_todos", Context.MODE_PRIVATE)

    private fun load(): MutableList<Item> {
        val raw = sp.getString("items", null) ?: return ArrayList()
        return try {
            val arr = JSONArray(raw)
            val out = ArrayList<Item>()
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                out.add(
                    Item(
                        o.getLong("id"),
                        o.getString("t"),
                        o.optBoolean("d", false),
                        LocalDate.parse(o.getString("c")),
                        o.optString("o", "").ifEmpty { null }?.let { LocalDate.parse(it) }
                    )
                )
            }
            out
        } catch (e: Exception) {
            ArrayList()
        }
    }

    private fun save(items: List<Item>) {
        val arr = JSONArray()
        for (it in items) {
            arr.put(
                JSONObject()
                    .put("id", it.id)
                    .put("t", it.text)
                    .put("d", it.done)
                    .put("c", it.created.toString())
                    .put("o", it.doneOn?.toString() ?: "")
            )
        }
        sp.edit().putString("items", arr.toString()).apply()
        WidgetUpdater.refresh(app)
    }

    /** Daftar hari ini: semua yang belum selesai + yang selesai hari ini. */
    fun today(now: LocalDate = LocalDate.now()): List<Item> {
        val all = load()
        // bersihkan tugas selesai yang lebih dari 7 hari
        val kept = all.filter { !it.done || (it.doneOn != null && !it.doneOn.isBefore(now.minusDays(7))) }
        if (kept.size != all.size) save(kept)
        return kept.filter { !it.done || it.doneOn == now }
    }

    fun pending(now: LocalDate = LocalDate.now()): List<Item> = today(now).filter { !it.done }

    fun add(text: String, now: LocalDate = LocalDate.now()): Item {
        val items = load()
        val id = (items.maxOfOrNull { it.id } ?: 0L) + 1
        val item = Item(id, text.trim().replaceFirstChar { it.uppercase() }, false, now, null)
        items.add(item)
        save(items)
        return item
    }

    fun setDone(id: Long, done: Boolean, now: LocalDate = LocalDate.now()) {
        val items = load().map { if (it.id == id) it.copy(done = done, doneOn = if (done) now else null) else it }
        save(items)
    }

    fun remove(id: Long) {
        save(load().filter { it.id != id })
    }

    fun clearDone() {
        save(load().filter { !it.done })
    }

    fun clearAll() {
        save(emptyList())
    }

    /** Cari tugas dari nomor urut di daftar hari ini ("2", "-1" = terakhir) atau potongan teks. */
    fun find(ref: String, now: LocalDate = LocalDate.now()): Item? {
        val list = today(now)
        if (list.isEmpty()) return null
        val n = ref.trim().toIntOrNull()
        if (n != null) {
            if (n == -1) return list.last()
            return list.getOrNull(n - 1)
        }
        val q = norm(ref)
        if (q.isEmpty()) return null
        return list.firstOrNull { norm(it.text) == q }
            ?: list.firstOrNull { norm(it.text).contains(q) }
            ?: list.firstOrNull { q.contains(norm(it.text)) }
            ?: list.firstOrNull { item -> q.split(" ").filter { it.length > 2 }.let { w -> w.isNotEmpty() && w.all { norm(item.text).contains(it) } } }
    }

    private fun norm(s: String) = s.lowercase().replace(Regex("[^\\p{L}\\p{N} ]"), "").replace(Regex("\\s+"), " ").trim()
}
