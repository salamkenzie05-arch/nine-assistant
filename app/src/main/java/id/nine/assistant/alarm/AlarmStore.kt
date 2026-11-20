package id.nine.assistant.alarm

import android.content.Context
import id.nine.assistant.nlu.RepeatDays
import org.json.JSONArray
import org.json.JSONObject
import java.time.DayOfWeek
import java.time.LocalDate

/** Penyimpanan alarm Nine (lokal, SharedPreferences). */
class AlarmStore(context: Context) {
    private val sp = context.applicationContext.getSharedPreferences("nine_alarms", Context.MODE_PRIVATE)

    @Synchronized
    fun all(): List<NineAlarm> {
        val raw = sp.getString("list", null) ?: return emptyList()
        return try {
            val arr = JSONArray(raw)
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                NineAlarm(
                    id = o.getInt("id"),
                    hour = o.getInt("h").coerceIn(0, 23),
                    minute = o.getInt("m").coerceIn(0, 59),
                    label = o.optString("label", "").ifBlank { null },
                    enabled = o.optBoolean("on", true),
                    days = RepeatDays.parseCode(o.optString("days", "")),
                    date = o.optString("date", "").ifBlank { null }?.let { runCatching { LocalDate.parse(it) }.getOrNull() },
                    createdAt = o.optLong("created", 0L)
                )
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun get(id: Int): NineAlarm? = all().firstOrNull { it.id == id }

    /** Alarm yang terakhir dibuat/diubah (untuk "hapus alarm tadi"). */
    var lastId: Int
        get() = sp.getInt("last", -1)
        set(value) = sp.edit().putInt("last", value).apply()

    @Synchronized
    fun add(hour: Int, minute: Int, label: String?, days: Set<DayOfWeek>, date: LocalDate?): NineAlarm {
        val list = all().toMutableList()
        // Alarm dengan jam & pengulangan yang sama: nyalakan saja yang lama, jangan dobel.
        list.firstOrNull { it.hour == hour && it.minute == minute && it.days == days && (it.label ?: "") == (label ?: "") }?.let { old ->
            val upd = old.copy(enabled = true, date = date)
            save(list.map { if (it.id == old.id) upd else it })
            lastId = upd.id
            return upd
        }
        val id = sp.getInt("next_id", 1)
        val a = NineAlarm(id, hour, minute, label?.trim()?.ifBlank { null }, true, days, date, System.currentTimeMillis())
        list.add(a)
        sp.edit().putInt("next_id", id + 1).apply()
        save(list)
        lastId = id
        return a
    }

    @Synchronized
    fun update(a: NineAlarm) {
        save(all().map { if (it.id == a.id) a else it })
        lastId = a.id
    }

    @Synchronized
    fun remove(id: Int) {
        save(all().filter { it.id != id })
    }

    private fun save(list: List<NineAlarm>) {
        val arr = JSONArray()
        for (a in list) {
            arr.put(
                JSONObject()
                    .put("id", a.id).put("h", a.hour).put("m", a.minute)
                    .put("label", a.label ?: "").put("on", a.enabled)
                    .put("days", RepeatDays.code(a.days))
                    .put("date", a.date?.toString() ?: "")
                    .put("created", a.createdAt)
            )
        }
        sp.edit().putString("list", arr.toString()).apply()
    }
}
