package id.nine.assistant.llm

/** Satu penyedia AI online gratis. [native]: Gemini memakai format API sendiri; lainnya format OpenAI. */
data class ProviderInfo(
    val id: String,
    val label: String,
    val baseUrl: String,
    val keyUrl: String,
    val native: Boolean = false
) {
    /** Nama kunci tersimpan di Prefs (terenkripsi). */
    val prefKey: String get() = "${id}_key"
}

object Providers {
    val GEMINI = ProviderInfo("gemini", "Gemini", "https://generativelanguage.googleapis.com/v1beta", "https://aistudio.google.com/apikey", native = true)
    val GROQ = ProviderInfo("groq", "Groq", "https://api.groq.com/openai/v1", "https://console.groq.com/keys")
    val CEREBRAS = ProviderInfo("cerebras", "Cerebras", "https://api.cerebras.ai/v1", "https://cloud.cerebras.ai")
    val OPENROUTER = ProviderInfo("openrouter", "OpenRouter", "https://openrouter.ai/api/v1", "https://openrouter.ai/keys")

    /** Urutan prioritas: yang pertama dicoba lebih dulu, sisanya jadi cadangan otomatis. */
    val all: List<ProviderInfo> = listOf(GEMINI, GROQ, CEREBRAS, OPENROUTER)

    fun byId(id: String): ProviderInfo? = all.firstOrNull { it.id == id }

    /** Model Gemini bawaan (alias yang selalu menunjuk Flash terbaru) dan cadangannya yang lebih ringan. */
    const val DEFAULT_GEMINI_MODEL = "gemini-flash-latest"
    const val FALLBACK_GEMINI_MODEL = "gemini-flash-lite-latest"
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
