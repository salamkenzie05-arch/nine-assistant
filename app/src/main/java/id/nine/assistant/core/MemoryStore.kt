package id.nine.assistant.core

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * Ingatan jangka panjang Nine: hal-hal yang pernah disampaikan pengguna (nama, kesukaan, keluarga, kebiasaan, ...).
 * Disimpan hanya di perangkat (SharedPreferences, tanpa cloud) dan disisipkan ke instruksi sistem tiap percakapan
 * baru, sehingga Nine "belajar" mengenal pengguna tanpa melatih ulang model.
 *
 * Isi ingatan selalu kata-kata pengguna sendiri; pengguna bisa melihat, menghapus sebagian, atau menghapus semuanya.
 */
class MemoryStore(context: Context) {
    data class Fact(val text: String, val time: Long)

    private val sp = context.applicationContext.getSharedPreferences("nine_memory", Context.MODE_PRIVATE)

    @Synchronized
    fun all(): List<Fact> {
        val raw = sp.getString(KEY, null) ?: return emptyList()
        return try {
            val arr = JSONArray(raw)
            (0 until arr.length()).map {
                val o = arr.getJSONObject(it)
                Fact(o.getString("t"), o.optLong("ts", 0L))
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun count(): Int = all().size

    /** Simpan satu hal. False bila kosong, terlalu pendek, atau sudah diingat. */
    @Synchronized
    fun add(text: String): Boolean {
        val t = clean(text)
        if (t.length < 3) return false
        val list = all().toMutableList()
        val key = norm(t)
        if (list.any { norm(it.text) == key }) return false
        list.add(Fact(t, System.currentTimeMillis()))
        while (list.size > MAX_FACTS) list.removeAt(0) // yang tertua dilupakan lebih dulu
        save(list)
        return true
    }

    /** Hapus ingatan pada posisi [indices] (urutan seperti [all]). */
    @Synchronized
    fun removeAt(indices: Collection<Int>) {
        if (indices.isEmpty()) return
        val drop = indices.toSet()
        save(all().filterIndexed { i, _ -> i !in drop })
    }

    /** Lupakan semua ingatan yang memuat seluruh kata penting dari [query]. Mengembalikan jumlah yang dihapus. */
    @Synchronized
    fun forget(query: String): Int {
        val words = query.lowercase().split(Regex("[^\\p{L}\\p{N}]+"))
            .filter { it.length >= 3 && it !in STOP }
        if (words.isEmpty()) return 0
        val list = all()
        val keep = list.filterNot { f -> val low = f.text.lowercase(); words.all { low.contains(it) } }
        if (keep.size == list.size) return 0
        save(keep)
        return list.size - keep.size
    }

    @Synchronized
    fun clear() {
        sp.edit().remove(KEY).apply()
    }

    /**
     * Blok teks untuk instruksi sistem AI; kosong bila belum ada ingatan. Dibatasi agar konteks model kecil tidak
     * penuh: ingatan terbaru lebih diutamakan.
     */
    fun promptBlock(lang: AppLang): String {
        val facts = all().takeLast(MAX_PROMPT_FACTS)
        if (facts.isEmpty()) return ""
        val lines = ArrayList<String>()
        var used = 0
        for (f in facts.asReversed()) {
            val line = "- " + f.text
            if (used + line.length > MAX_PROMPT_CHARS) break
            lines.add(0, line)
            used += line.length
        }
        val head = lang.t(
            "Yang kamu ketahui tentang pengguna (diingat dari ucapannya sendiri). Pakai bila relevan, jangan dibacakan semuanya, jangan menambah-nambah:",
            "What you know about the user (remembered from their own words). Use it when relevant, don't recite it all, don't embellish:"
        )
        return head + "\n" + lines.joinToString("\n")
    }

    private fun save(list: List<Fact>) {
        val arr = JSONArray()
        for (f in list) arr.put(JSONObject().put("t", f.text).put("ts", f.time))
        sp.edit().putString(KEY, arr.toString()).apply()
    }

    companion object {
        private const val KEY = "facts"
        const val MAX_FACTS = 150
        private const val MAX_PROMPT_FACTS = 40
        private const val MAX_PROMPT_CHARS = 1600
        private val STOP = setOf("tentang", "soal", "bahwa", "kalau", "itu", "ini", "the", "about", "that", "yang", "aku", "saya", "gue", "kamu", "my", "me")

        /** Satu baris bersih: spasi dirapikan, tanda akhir kalimat dibuang, huruf pertama kapital, maks 200 huruf. */
        fun clean(s: String): String {
            val t = s.replace(Regex("\\s+"), " ").trim().trim('"', '“', '”').trimEnd('.', '!', ',', ';', ' ').take(200).trim()
            return if (t.isEmpty()) t else t.substring(0, 1).uppercase() + t.substring(1)
        }

        private fun norm(s: String): String = s.lowercase().replace(Regex("[^\\p{L}\\p{N}]+"), " ").trim()
    }
}
