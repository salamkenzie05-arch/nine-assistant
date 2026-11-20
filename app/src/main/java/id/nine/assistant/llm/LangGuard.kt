package id.nine.assistant.llm

import id.nine.assistant.core.AppLang

/**
 * Pendeteksi jawaban yang bahasanya bercampur. Model kecil di HP kadang menyelipkan kalimat bahasa Inggris
 * di jawaban Indonesia (atau sebaliknya). Pengecekan ini menghitung kata fungsi khas tiap bahasa; bila kata
 * bahasa "asing" terlalu banyak, jawaban ditulis ulang. Kotlin murni agar mudah diuji.
 */
object LangGuard {

    private val ID_WORDS = setOf(
        "yang", "dan", "di", "ke", "dari", "untuk", "dengan", "ini", "itu", "adalah", "akan", "tidak", "ada", "saya", "aku",
        "kamu", "anda", "pada", "juga", "atau", "sudah", "bisa", "kalau", "jika", "karena", "tapi", "tetapi", "bukan",
        "belum", "sangat", "lebih", "mau", "harus", "apa", "siapa", "bagaimana", "kapan", "mengapa", "kenapa", "hari",
        "besok", "sekarang", "nanti", "tadi", "saja", "lagi", "dalam", "oleh", "sebagai", "tentang", "seperti", "agar",
        "supaya", "masih", "banyak", "semua", "setiap", "antara", "telah", "dapat", "namun", "maka", "dulu", "tolong",
        "silakan", "terima", "kasih", "maaf", "tuan", "nyonya", "baik", "siap", "segera", "tentu", "jadi", "boleh",
        "hanya", "cuma", "kami", "kita", "mereka", "dia", "ya", "dong", "sih"
    )

    private val EN_WORDS = setOf(
        "the", "and", "of", "to", "in", "is", "are", "was", "were", "for", "with", "that", "this", "it", "you", "your", "my",
        "me", "we", "not", "have", "has", "be", "can", "will", "would", "could", "should", "on", "at", "by", "from", "as",
        "but", "or", "if", "so", "what", "which", "who", "how", "when", "where", "why", "there", "here", "just", "now",
        "please", "sorry", "thank", "thanks", "shall", "sir", "may", "might", "been", "being", "do", "does", "did", "an",
        "their", "our", "them", "they", "he", "she", "his", "her", "its", "than", "then", "also", "very", "more", "most",
        "some", "any", "all", "yes", "certainly", "course", "indeed", "quite", "rather", "really", "much", "many", "let",
        "need", "want", "know", "think", "about", "into", "over", "after", "before", "because", "while", "though",
        "however", "today", "tomorrow", "tonight", "morning", "evening", "good", "great"
    )

    private val wordRegex = Regex("[a-zA-Z']+")
    private val quoted = Regex("\"[^\"]*\"|“[^”]*”|'[^']{3,}'")
    private val translating = Regex(
        "terjemah|translate|translation|bahasa\\s+(?:inggris|indonesia|jepang|korea|arab|mandarin|prancis|jerman|spanyol)|" +
            "in\\s+(?:english|indonesian|japanese|korean|arabic|chinese|french|german|spanish)|artinya|\\barti\\b|\\bmeaning\\b|\\bmeans?\\b",
        RegexOption.IGNORE_CASE
    )

    /** Banyaknya kata penanda bahasa Indonesia dan Inggris di [text] (kutipan diabaikan). */
    fun counts(text: String): Pair<Int, Int> {
        var id = 0
        var en = 0
        for (m in wordRegex.findAll(quoted.replace(text, " "))) {
            val w = m.value.lowercase()
            if (w in ID_WORDS) id++
            if (w in EN_WORDS) en++
        }
        return id to en
    }

    /** Banyaknya kata penanda bahasa lain (bukan [lang]) di [text]. */
    fun foreign(text: String, lang: AppLang): Int {
        val (id, en) = counts(text)
        return if (lang == AppLang.ID) en else id
    }

    /**
     * True bila [reply] terlalu bercampur untuk bahasa [lang] dan perlu ditulis ulang.
     * Pertanyaan tentang terjemahan/arti kata dibiarkan, karena memang wajar memuat bahasa lain.
     */
    fun needsRewrite(reply: String, lang: AppLang, question: String = ""): Boolean {
        if (reply.length < 20) return false
        if (translating.containsMatchIn(question)) return false
        val (id, en) = counts(reply)
        val native = if (lang == AppLang.ID) id else en
        val foreign = if (lang == AppLang.ID) en else id
        return foreign >= 3 && foreign * 5 >= foreign + native
    }
}
