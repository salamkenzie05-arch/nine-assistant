package id.nine.assistant.wake

/** Pencocokan hasil pengenalan suara dengan frasa pemanggil (Kotlin murni, bisa diuji). */
object WakePhrase {
    /** True bila [text] adalah salah satu [phrases], sendiri atau di awal/akhir kalimat. */
    fun matches(text: String, phrases: List<String>): Boolean {
        val t = text.trim()
        if (t.isEmpty() || t == "[unk]") return false
        return phrases.any { t == it || t.endsWith(" $it") || t.startsWith("$it ") }
    }
}
