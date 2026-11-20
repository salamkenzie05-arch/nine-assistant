package id.nine.assistant.nlu

/** Membaca durasi seperti "10 menit", "1 jam 30 menit", "setengah jam", "an hour". */
object Durations {
    data class Match(val seconds: Int, val range: IntRange)

    private const val UNIT = """(?:jam|hours?|hrs?|menit|minutes?|mins?|detik|seconds?|secs?)(?![a-z])"""
    private const val COMP = """(?:an?\s+hour\s+and\s+a\s+half|\d+\s+and\s+a\s+half\s+(?:hours?|hrs?)|sejam\s+setengah|\d+\s+setengah\s+jam|\d+(?:[.,]\d+)?\s*$UNIT|setengah\s+jam|half\s+(?:an\s+)?hour|seperempat\s+jam|(?:a\s+)?quarter\s+(?:of\s+an\s+)?hour|sejam|semenit|sedetik|an?\s+(?:hour|minute|second)(?![a-z]))"""

    private val seq = Regex("""\b$COMP(?:\s*(?:,|and|dan)?\s*$COMP)*""", RegexOption.IGNORE_CASE)
    private val comp = Regex(COMP, RegexOption.IGNORE_CASE)
    private val numUnit = Regex("""(\d+(?:[.,]\d+)?)\s*([a-z]+)""", RegexOption.IGNORE_CASE)

    private fun factor(unit: String): Int {
        val u = unit.lowercase()
        return when {
            u.startsWith("jam") || u.startsWith("hour") || u.startsWith("hr") -> 3600
            u.startsWith("menit") || u.startsWith("min") -> 60
            else -> 1
        }
    }

    private fun secondsOf(compText: String): Int {
        val t = compText.lowercase().trim().replace(Regex("\\s+"), " ")
        if (t.isEmpty()) return 0
        if (t.contains("and a half")) {
            val n = Regex("""\d+""").find(t)?.value?.toIntOrNull() ?: 1
            return n * 3600 + 1800
        }
        if (t == "sejam setengah") return 5400
        if (t[0].isDigit() && t.contains("setengah")) {
            val n = Regex("""\d+""").find(t)?.value?.toIntOrNull() ?: 0
            return n * 3600 + 1800
        }
        return when {
            t.startsWith("setengah") || t.startsWith("half") -> 1800
            t.contains("seperempat") || t.contains("quarter") -> 900
            t == "sejam" -> 3600
            t == "semenit" -> 60
            t == "sedetik" -> 1
            t.startsWith("a ") || t.startsWith("an ") -> factor(t.substringAfter(' '))
            else -> {
                val m = numUnit.find(t) ?: return 0
                val num = m.groupValues[1].replace(',', '.').toDoubleOrNull() ?: return 0
                (num * factor(m.groupValues[2])).toInt()
            }
        }
    }

    fun find(text: String): Match? {
        val m = seq.find(text) ?: return null
        var total = 0
        for (c in comp.findAll(m.value)) total += secondsOf(c.value)
        if (total <= 0) return null
        return Match(total, m.range)
    }

    fun parseSeconds(text: String): Int? = find(text)?.seconds
}
