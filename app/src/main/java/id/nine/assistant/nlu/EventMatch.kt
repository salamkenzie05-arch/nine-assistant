package id.nine.assistant.nlu

/**
 * Mencocokkan ucapan ("rapat RNPS", "meeting dengan Budi") dengan judul acara di kalender.
 * Kata umum (rapat, meeting, dengan, ...) boleh tidak ada di judul; kata khusus (RNPS, Budi) harus ada,
 * dengan toleransi salah dengar satu huruf untuk kata yang cukup panjang.
 */
object EventMatch {
    private val soft = setOf(
        "rapat", "meeting", "meet", "appointment", "janji", "temu", "acara", "event", "jadwal", "agenda", "pengingat", "reminder",
        "dengan", "sama", "bersama", "with", "and", "dan", "ke", "di", "the", "a", "an", "my", "saya", "untuk", "for", "of", "at", "pada",
        "yang", "itu", "ini", "acaranya", "nya"
    )

    private fun norm(s: String): String = s.lowercase().replace(Regex("[^\\p{L}\\p{N}\\s]"), " ").replace(Regex("\\s+"), " ").trim()

    fun tokens(query: String): List<String> = norm(query).split(' ').filter { it.isNotEmpty() }

    /** Kata kunci yang wajib ada di judul. Kosong = cocok dengan acara apa saja (biasanya disaring tanggal/jam). */
    fun keyTokens(query: String): List<String> = tokens(query).filter { it !in soft }

    private fun lev(a: String, b: String): Int {
        val d = IntArray(b.length + 1) { it }
        for (i in 1..a.length) {
            var prev = d[0]
            d[0] = i
            for (j in 1..b.length) {
                val tmp = d[j]
                d[j] = minOf(d[j] + 1, d[j - 1] + 1, prev + if (a[i - 1] == b[j - 1]) 0 else 1)
                prev = tmp
            }
        }
        return d[b.length]
    }

    private fun tokenHit(token: String, title: String, words: List<String>): Boolean {
        if (title.contains(token)) return true
        if (token.length >= 5) return words.any { it.length >= 4 && lev(it, token) <= 1 }
        return false
    }

    /** 0 = tidak cocok; makin besar makin cocok. */
    fun score(query: String, title: String): Int {
        val t = norm(title)
        val words = t.split(' ')
        val keys = keyTokens(query)
        if (keys.any { !tokenHit(it, t, words) }) return 0
        val softHits = tokens(query).count { it in soft && it.length >= 4 && t.contains(it) }
        val exact = if (t == norm(query)) 5 else 0
        return 1 + keys.size * 3 + softHits + exact
    }
}
