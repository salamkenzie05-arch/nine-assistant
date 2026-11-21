package id.nine.assistant.llm

/** Satu baris hasil membaca aliran SSE dari penyedia AI. */
sealed class SseEvent {
    data class Text(val text: String) : SseEvent()
    data class Error(val message: String, val code: Int?) : SseEvent()
    data class Blocked(val reason: String) : SseEvent()
    object Skip : SseEvent()
}

/** Format API Gemini (Google AI Studio). */
object GeminiWire {

    /**
     * [thinking]: minta berpikir seminimal mungkin ("thinkingLevel": MINIMAL). Model Gemini 3 ke atas berpikir panjang secara
     * bawaan, sehingga huruf pertama baru muncul puluhan detik kemudian; untuk percakapan, jawaban cepat lebih penting.
     */
    fun body(req: ChatRequest, search: Boolean, thinking: Boolean = false): String {
        val m = LinkedHashMap<String, Any?>()
        if (req.system.isNotBlank()) {
            m["system_instruction"] = mapOf("parts" to listOf(mapOf("text" to req.system)))
        }
        m["contents"] = req.turns().map { (r, t) ->
            mapOf("role" to (if (r == "user") "user" else "model"), "parts" to listOf(mapOf("text" to t)))
        }
        if (search) m["tools"] = listOf(mapOf("google_search" to emptyMap<String, Any?>()))
        if (thinking) m["generationConfig"] = mapOf("thinkingConfig" to mapOf("thinkingLevel" to "MINIMAL"))
        return MiniJson.write(m)
    }

    /** Galat dari badan JSON atau di tengah aliran: {"code":429,"message":"…"} atau teks biasa. */
    internal fun errorEvent(err: Any?): SseEvent.Error = when (err) {
        is Map<*, *> -> SseEvent.Error(
            (err["message"] as? String)?.takeIf { it.isNotBlank() } ?: "error",
            MiniJson.num(err, "code")?.toInt()
        )
        is String -> SseEvent.Error(err, null)
        else -> SseEvent.Error("error", null)
    }

    private val BLOCKING = setOf("SAFETY", "RECITATION", "PROHIBITED_CONTENT", "BLOCKLIST", "SPII", "OTHER")

    fun parseLine(line: String): SseEvent {
        if (!line.startsWith("data:")) return SseEvent.Skip
        val payload = line.substring(5).trim()
        if (payload.isEmpty() || payload == "[DONE]") return SseEvent.Skip
        val obj = MiniJson.parseOrNull(payload) as? Map<*, *> ?: return SseEvent.Skip
        obj["error"]?.let { return errorEvent(it) }
        MiniJson.str(obj, "promptFeedback", "blockReason")?.let { return SseEvent.Blocked(it) }
        val parts = MiniJson.arr(obj, "candidates", 0, "content", "parts")
        val sb = StringBuilder()
        if (parts != null) {
            for (p in parts) {
                if (MiniJson.get(p, "thought") == true) continue
                MiniJson.str(p, "text")?.let { sb.append(it) }
            }
        }
        if (sb.isNotEmpty()) return SseEvent.Text(sb.toString())
        val fin = MiniJson.str(obj, "candidates", 0, "finishReason")
        if (fin != null && fin in BLOCKING) return SseEvent.Blocked(fin)
        return SseEvent.Skip
    }
}
