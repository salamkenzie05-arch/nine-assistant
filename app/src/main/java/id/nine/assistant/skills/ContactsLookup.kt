package id.nine.assistant.skills

import android.content.Context
import android.provider.ContactsContract

/** Pencarian kontak lokal dengan pencocokan longgar (cocok untuk hasil pengenalan suara). */
class ContactsLookup(context: Context) {
    private val resolver = context.applicationContext.contentResolver

    data class Entry(val name: String, val number: String, val isMobile: Boolean)

    sealed class Result {
        data class Found(val name: String, val number: String) : Result()
        data class Ambiguous(val names: List<String>) : Result()
        object NotFound : Result()
    }

    /** Butuh izin READ_CONTACTS. */
    fun find(query: String): Result {
        val q = norm(query)
        if (q.isEmpty()) return Result.NotFound
        val qTokens = tokens(query)

        val entries = loadAll()
        var best = 0
        val scored = ArrayList<Pair<Int, Entry>>()
        for (e in entries) {
            val s = score(q, qTokens, e.name)
            if (s >= MIN_SCORE) {
                scored.add(Pair(s, e))
                if (s > best) best = s
            }
        }
        if (scored.isEmpty()) return Result.NotFound
        val top = scored.filter { it.first == best }.map { it.second }
        val names = top.map { it.name }.distinct()
        if (names.size > 1) return Result.Ambiguous(names.take(4))
        val chosen = top.firstOrNull { it.isMobile } ?: top.first()
        return Result.Found(chosen.name, chosen.number)
    }

    private fun loadAll(): List<Entry> {
        val out = ArrayList<Entry>()
        val proj = arrayOf(
            ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
            ContactsContract.CommonDataKinds.Phone.NUMBER,
            ContactsContract.CommonDataKinds.Phone.TYPE
        )
        try {
            resolver.query(ContactsContract.CommonDataKinds.Phone.CONTENT_URI, proj, null, null, null)?.use { c ->
                while (c.moveToNext()) {
                    val name = c.getString(0) ?: continue
                    val number = c.getString(1) ?: continue
                    val type = c.getInt(2)
                    out.add(Entry(name, number, type == ContactsContract.CommonDataKinds.Phone.TYPE_MOBILE))
                }
            }
        } catch (e: SecurityException) {
            // izin belum diberikan
        }
        return out
    }

    private fun score(q: String, qTokens: List<String>, name: String): Int {
        val n = norm(name)
        if (n.isEmpty()) return 0
        val nTokens = tokens(name)
        return when {
            n == q -> 100
            n.startsWith(q) -> 90
            qTokens.isNotEmpty() && qTokens.all { qt -> nTokens.any { it.startsWith(qt) } } -> 80
            n.contains(q) -> 70
            // "pak budi" untuk kontak "Budi": semua kata di nama ada di ucapan
            nTokens.isNotEmpty() && nTokens.all { nt -> qTokens.contains(nt) } -> 60
            else -> 0
        }
    }

    private fun norm(s: String): String = s.lowercase().filter { it.isLetterOrDigit() }

    private fun tokens(s: String): List<String> =
        s.lowercase().split(Regex("[^\\p{L}\\p{N}]+")).filter { it.isNotEmpty() }

    private companion object {
        const val MIN_SCORE = 60
    }
}
