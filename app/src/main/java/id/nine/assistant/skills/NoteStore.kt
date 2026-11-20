package id.nine.assistant.skills

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** Catatan suara, disimpan hanya di perangkat (SharedPreferences, tanpa cloud). */
class NoteStore(context: Context) {
    data class Note(val text: String, val time: Long)

    private val sp = context.applicationContext.getSharedPreferences("asisten_notes", Context.MODE_PRIVATE)

    fun all(): List<Note> {
        val raw = sp.getString(KEY, null) ?: return emptyList()
        return try {
            val arr = JSONArray(raw)
            (0 until arr.length()).map {
                val o = arr.getJSONObject(it)
                Note(o.getString("t"), o.optLong("ts", 0L))
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun add(text: String) {
        val list = all().toMutableList()
        list.add(Note(text, System.currentTimeMillis()))
        save(list)
    }

    fun clear() {
        sp.edit().remove(KEY).apply()
    }

    private fun save(list: List<Note>) {
        val arr = JSONArray()
        for (n in list) arr.put(JSONObject().put("t", n.text).put("ts", n.time))
        sp.edit().putString(KEY, arr.toString()).apply()
    }

    private companion object {
        const val KEY = "notes"
    }
}
