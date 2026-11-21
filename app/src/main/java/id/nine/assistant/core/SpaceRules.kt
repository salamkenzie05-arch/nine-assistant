package id.nine.assistant.core

/** Aturan murni untuk ruang obrolan: pembersihan teks dan batas ukuran. Tanpa Android agar bisa diuji. */
object SpaceRules {
    const val MAX_SPACES = 20
    const val MAX_MESSAGES = 120
    const val MAX_MESSAGE_CHARS = 2000
    const val NAME_MAX = 28
    const val NOTE_MAX = 300

    private val control = Regex("[\\p{Cntrl}&&[^\\n]]")

    /** Nama ruang: satu baris, tanpa karakter kontrol, maksimal [NAME_MAX]. */
    fun cleanName(s: String): String =
        s.replace(control, " ").replace(Regex("\\s+"), " ").trim().take(NAME_MAX).trim()

    /** Instruksi ruang: satu paragraf (baris baru jadi spasi), maksimal [NOTE_MAX]. */
    fun cleanNote(s: String): String =
        s.replace(control, " ").replace(Regex("\\s+"), " ").trim().take(NOTE_MAX).trim()

    /** Batasi pesan yang disimpan: buang yang kosong/penanda sementara, potong yang kepanjangan, simpan [MAX_MESSAGES] terakhir. */
    fun trim(messages: List<Pair<Boolean, String>>): List<Pair<Boolean, String>> =
        messages
            .filter { it.second.isNotBlank() && it.second != "…" }
            .map { it.first to it.second.take(MAX_MESSAGE_CHARS) }
            .takeLast(MAX_MESSAGES)

    /** Teks pembuka konteks untuk AI: nama ruang dan instruksi dari pengguna. Kosong bila [name] kosong. */
    fun contextLine(name: String, note: String, lang: AppLang): String {
        if (name.isBlank()) return ""
        val head = lang.t("Ruang obrolan ini bernama \"$name\".", "This chat space is called \"$name\".")
        return if (note.isBlank()) head else head + " " + lang.t("Instruksi dari pengguna untuk ruang ini: $note", "The user's instructions for this space: $note")
    }
}
