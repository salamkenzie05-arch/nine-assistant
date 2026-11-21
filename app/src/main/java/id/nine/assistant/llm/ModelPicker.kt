package id.nine.assistant.llm

/**
 * Memilih model obrolan terbaik dari daftar model yang ditawarkan penyedia saat ini.
 * Nama model gratis sering berganti, jadi Nine tidak mengunci satu nama: ia membaca daftar lalu memilih
 * berdasarkan urutan preferensi, dan membuang model yang bukan untuk obrolan teks.
 */
object ModelPicker {

    private val EXCLUDE = Regex(
        "whisper|tts|guard|embed|moderat|orpheus|playai|image|audio|transcri|rerank|vision|video|speech|" +
            "-vl\\b|lyria|imagen|veo|ocr|compound|robotics|\\blive\\b|native-audio",
        RegexOption.IGNORE_CASE
    )

    private fun rx(vararg p: String): List<Regex> = p.map { Regex(it, RegexOption.IGNORE_CASE) }

    private val RANK: Map<String, List<Regex>> = mapOf(
        Providers.GROQ.id to rx(
            "gpt-oss-120b", "kimi-k2", "llama-4-maverick", "qwen", "llama-3\\.3-70b", "gpt-oss-20b", "llama-4-scout", "llama-3\\.1-8b"
        ),
        Providers.CEREBRAS.id to rx(
            "gpt-oss-120b", "qwen-?3-235b", "glm", "gemma", "qwen", "llama-?3\\.3|llama-?4", "llama"
        ),
        Providers.OPENROUTER.id to rx(
            "gpt-oss-120b", "llama-3\\.3-70b", "qwen.*(235b|480b|80b|72b)", "kimi", "deepseek", "glm",
            "nemotron.*(ultra|super|120b|253b|49b)", "gemma-?[34].*(27b|31b)", "mistral", "gpt-oss-20b", "llama", "qwen"
        )
    )

    /** Model cadangan bila daftar model tak bisa diambil. */
    fun defaults(providerId: String): List<String> = when (providerId) {
        Providers.GROQ.id -> listOf("openai/gpt-oss-120b", "openai/gpt-oss-20b")
        Providers.CEREBRAS.id -> listOf("gpt-oss-120b", "llama3.1-8b")
        Providers.OPENROUTER.id -> listOf("openrouter/free")
        else -> emptyList()
    }

    fun isChatModel(id: String): Boolean = id.isNotBlank() && !EXCLUDE.containsMatchIn(id)

    /** Hingga [limit] model terbaik dari [ids] (urutan daftar menentukan bila peringkatnya sama). */
    fun pick(providerId: String, ids: List<String>, limit: Int): List<String> {
        val usable = ids.filter { isChatModel(it) }.distinct()
        val out = ArrayList<String>()
        for (r in RANK[providerId].orEmpty()) {
            for (id in usable) {
                if (id !in out && r.containsMatchIn(id)) out.add(id)
            }
        }
        for (id in usable) if (id !in out) out.add(id)
        // OpenRouter: router gratis otomatis dijadikan cadangan terakhir bila ada.
        if (providerId == Providers.OPENROUTER.id) {
            val router = usable.firstOrNull { it == "openrouter/free" }
            if (router != null) {
                out.remove(router)
                val top = out.take(limit - 1).toMutableList()
                top.add(router)
                return top
            }
        }
        return out.take(limit)
    }
}
