package id.nine.assistant.llm

/** Satu penyedia AI online. Nine hanya memakai Gemini (Google AI Studio, tingkat gratis). */
data class ProviderInfo(
    val id: String,
    val label: String,
    val baseUrl: String,
    val keyUrl: String
) {
    /** Nama kunci tersimpan di Prefs (terenkripsi). */
    val prefKey: String get() = "${id}_key"
}

object Providers {
    val GEMINI = ProviderInfo("gemini", "Gemini", "https://generativelanguage.googleapis.com/v1beta", "https://aistudio.google.com/apikey")

    /** Model Gemini bawaan (alias yang selalu menunjuk Flash terbaru) dan cadangannya yang lebih ringan. */
    const val DEFAULT_GEMINI_MODEL = "gemini-flash-latest"
    const val FALLBACK_GEMINI_MODEL = "gemini-flash-lite-latest"

    /** Dua model yang dicoba berurutan: pilihan pengguna (bila ada) atau bawaan, lalu cadangan. Kuota gratis dihitung per model. */
    fun geminiModels(userModel: String): List<String> {
        val primary = userModel.trim().removePrefix("models/").ifBlank { DEFAULT_GEMINI_MODEL }
        return listOf(primary, DEFAULT_GEMINI_MODEL, FALLBACK_GEMINI_MODEL).distinct().take(2)
    }

    /** Halaman resmi Google untuk melihat batas dan pemakaian kunci. */
    const val DASHBOARD_URL = "https://aistudio.google.com/rate-limit"
}

/** Isi permintaan ke AI online. [history]: pasangan (role "user"/"model", teks), sudah urut. */
data class ChatRequest(val system: String, val history: List<Pair<String, String>>, val user: String) {

    /**
     * Giliran bersih untuk dikirim: teks kosong dibuang, giliran berurutan dengan peran sama digabung,
     * diawali giliran pengguna, dan berakhir dengan pesan pengguna sekarang.
     */
    fun turns(): List<Pair<String, String>> {
        val out = ArrayList<Pair<String, String>>()
        for ((role0, text0) in history + ("user" to user)) {
            val text = text0.trim()
            if (text.isEmpty()) continue
            val role = if (role0 == "user") "user" else "model"
            if (out.isNotEmpty() && out.last().first == role) {
                out[out.size - 1] = role to (out.last().second + "\n" + text)
            } else {
                out.add(role to text)
            }
        }
        while (out.isNotEmpty() && out.first().first != "user") out.removeAt(0)
        return out
    }
}
