package id.nine.assistant.core

import java.util.Locale

/** Bahasa yang didukung untuk pengenalan suara dan jawaban. */
enum class AppLang(val tag: String, val label: String, private val ttsTag: String) {
    ID("id-ID", "Indonesia", "id-ID"),
    // Pengenalan suara memakai en-US (paket offline paling umum); suara Nine memakai aksen Inggris (en-GB).
    EN("en-US", "English", "en-GB");

    val locale: Locale get() = Locale.forLanguageTag(tag)
    val ttsLocale: Locale get() = Locale.forLanguageTag(ttsTag)

    /** Pilih teks sesuai bahasa. */
    fun t(id: String, en: String): String = if (this == ID) id else en

    companion object {
        fun fromTag(tag: String?): AppLang = values().firstOrNull { it.tag == tag } ?: ID
    }
}
