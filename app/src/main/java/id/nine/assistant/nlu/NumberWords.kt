package id.nine.assistant.nlu

/**
 * Mengubah angka yang diucapkan menjadi digit, untuk Bahasa Indonesia dan Inggris.
 * Contoh: "jam tujuh lima belas" -> "jam 7 15", "twenty five minutes" -> "25 minutes".
 * Mendukung 0..99. Kata lain dibiarkan apa adanya.
 */
object NumberWords {
    private val idUnits = mapOf(
        "nol" to 0, "kosong" to 0, "satu" to 1, "dua" to 2, "tiga" to 3, "empat" to 4,
        "lima" to 5, "enam" to 6, "tujuh" to 7, "delapan" to 8, "sembilan" to 9
    )
    private val enUnits = mapOf(
        "zero" to 0, "one" to 1, "two" to 2, "three" to 3, "four" to 4, "five" to 5,
        "six" to 6, "seven" to 7, "eight" to 8, "nine" to 9
    )
    private val enTeens = mapOf(
        "ten" to 10, "eleven" to 11, "twelve" to 12, "thirteen" to 13, "fourteen" to 14,
        "fifteen" to 15, "sixteen" to 16, "seventeen" to 17, "eighteen" to 18, "nineteen" to 19
    )
    private val enTens = mapOf(
        "twenty" to 20, "thirty" to 30, "forty" to 40, "fifty" to 50,
        "sixty" to 60, "seventy" to 70, "eighty" to 80, "ninety" to 90
    )

    private fun key(w: String): String = w.lowercase().trim { !it.isLetterOrDigit() }

    fun normalize(input: String): String {
        val words = input.split(Regex("\\s+")).filter { it.isNotEmpty() }
        val out = ArrayList<String>(words.size)
        var i = 0
        while (i < words.size) {
            val r = read(words, i)
            if (r != null) {
                out.add(r.first.toString())
                i += r.second
            } else {
                out.add(words[i])
                i++
            }
        }
        return out.joinToString(" ")
    }

    /** Mengembalikan (nilai, jumlah kata yang dipakai) atau null. */
    private fun read(words: List<String>, i: Int): Pair<Int, Int>? {
        val k = key(words[i])
        if (k.isEmpty()) return null
        val next = if (i + 1 < words.size) key(words[i + 1]) else ""

        // Bahasa Indonesia
        if (k == "sepuluh") return Pair(10, 1)
        if (k == "sebelas") return Pair(11, 1)
        val iu = idUnits[k]
        if (iu != null) {
            if (next == "belas" && iu in 1..9) return Pair(10 + iu, 2)
            if (next == "puluh" && iu in 2..9) {
                val after = if (i + 2 < words.size) idUnits[key(words[i + 2])] else null
                if (after != null && after in 1..9) return Pair(iu * 10 + after, 3)
                return Pair(iu * 10, 2)
            }
            return Pair(iu, 1)
        }

        // English
        val et = enTens[k]
        if (et != null) {
            val after = enUnits[next]
            if (after != null && after in 1..9) return Pair(et + after, 2)
            return Pair(et, 1)
        }
        val ete = enTeens[k]
        if (ete != null) return Pair(ete, 1)
        val eu = enUnits[k]
        if (eu != null) return Pair(eu, 1)

        // "twenty-five"
        if (k.contains('-')) {
            val p = k.split('-')
            if (p.size == 2) {
                val t = enTens[p[0]]
                val u = enUnits[p[1]]
                if (t != null && u != null && u in 1..9) return Pair(t + u, 1)
            }
        }
        return null
    }
}
