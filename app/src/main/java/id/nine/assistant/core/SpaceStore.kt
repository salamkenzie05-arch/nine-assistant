package id.nine.assistant.core

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * Ruang obrolan khusus (seperti "projects"): tiap ruang punya nama, instruksi opsional, dan riwayat obrolannya sendiri.
 * Disimpan di penyimpanan pribadi aplikasi (tidak ikut cadangan, tidak dikirim ke mana pun kecuali sebagai konteks
 * ke AI saat Anda mengobrol di ruang itu). Data rusak dibaca sebagai kosong, tidak pernah membuat aplikasi gagal.
 */
class SpaceStore(context: Context) {
    data class Space(val id: String, val name: String, val note: String, val updated: Long)
    data class Msg(val fromUser: Boolean, val text: String)

    private val sp = context.applicationContext.getSharedPreferences("nine_spaces", Context.MODE_PRIVATE)

    /** Id ruang yang sedang dibuka; kosong = obrolan utama. */
    var activeId: String
        get() = sp.getString("active", "") ?: ""
        set(value) = sp.edit().putString("active", value).apply()

    fun list(): List<Space> = try {
        val arr = JSONArray(sp.getString("spaces", "[]"))
        (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            val id = o.optString("id")
            if (id.isBlank()) null else Space(id, o.optString("name"), o.optString("note"), o.optLong("updated"))
        }.sortedByDescending { it.updated }
    } catch (e: Exception) {
        emptyList()
    }

    fun get(id: String): Space? = list().firstOrNull { it.id == id }

    private fun write(items: List<Space>) {
        val arr = JSONArray()
        for (s in items) {
            arr.put(JSONObject().put("id", s.id).put("name", s.name).put("note", s.note).put("updated", s.updated))
        }
        sp.edit().putString("spaces", arr.toString()).apply()
    }

    /** Ruang baru, atau null bila nama kosong atau sudah mencapai batas [SpaceRules.MAX_SPACES]. */
    fun create(name: String, note: String): Space? {
        val n = SpaceRules.cleanName(name)
        val items = list()
        if (n.isEmpty() || items.size >= SpaceRules.MAX_SPACES) return null
        val s = Space(java.util.UUID.randomUUID().toString(), n, SpaceRules.cleanNote(note), System.currentTimeMillis())
        write(items + s)
        return s
    }

    fun update(id: String, name: String, note: String): Boolean {
        val n = SpaceRules.cleanName(name)
        if (n.isEmpty()) return false
        val items = list()
        if (items.none { it.id == id }) return false
        write(items.map { if (it.id == id) it.copy(name = n, note = SpaceRules.cleanNote(note)) else it })
        return true
    }

    fun delete(id: String) {
        write(list().filter { it.id != id })
        sp.edit().remove("msgs_$id").apply()
        if (activeId == id) activeId = ""
    }

    fun load(id: String): List<Msg> = try {
        val arr = JSONArray(sp.getString("msgs_$id", "[]"))
        (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            val t = o.optString("t")
            if (t.isBlank()) null else Msg(o.optBoolean("u"), t)
        }
    } catch (e: Exception) {
        emptyList()
    }

    fun save(id: String, messages: List<Msg>) {
        if (get(id) == null) return // ruang sudah dihapus: jangan menulis ulang datanya
        val kept = SpaceRules.trim(messages.map { it.fromUser to it.text })
        val arr = JSONArray()
        for ((u, t) in kept) arr.put(JSONObject().put("u", u).put("t", t))
        sp.edit().putString("msgs_$id", arr.toString()).apply()
    }

    /** Tandai ruang baru saja dipakai (urutan daftar). */
    fun touch(id: String) {
        val items = list()
        if (items.none { it.id == id }) return
        write(items.map { if (it.id == id) it.copy(updated = System.currentTimeMillis()) else it })
    }
}
