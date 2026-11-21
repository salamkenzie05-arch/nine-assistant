package id.nine.assistant.llm

/** Satu baris hasil membaca aliran SSE dari penyedia AI. */
sealed class SseEvent {
    data class Text(val text: String) : SseEvent()
    data class Error(val message: String, val code: Int?) : SseEvent()
    data class Blocked(val reason: String) : SseEvent()
    object Done : SseEvent()
    object Skip : SseEvent()
}

/** Penyaring blok pikiran model penalaran (<think>…</think>) dari teks yang datang bertahap. */
class ThinkFilter {
    private var inThink = false
    private var trimLead = true // spasi/baris kosong di awal jawaban (sisa setelah blok pikiran) dibuang
    private val buf = StringBuilder()

    fun feed(chunk: String): String {
        buf.append(chunk)
        val out = StringBuilder()
        while (true) {
            if (!inThink) {
                val i = buf.indexOf(OPEN)
                if (i >= 0) {
                    emit(out, buf.substring(0, i))
                    buf.delete(0, i + OPEN.length)
                    inThink = true
                    continue
                }
                val n = buf.length - partialSuffix(OPEN)
                emit(out, buf.substring(0, n))
                buf.delete(0, n)
                break
            } else {
                val i = buf.indexOf(CLOSE)
                if (i >= 0) {
                    buf.delete(0, i + CLOSE.length)
                    inThink = false
                    continue
                }
                buf.delete(0, buf.length - partialSuffix(CLOSE))
                break
            }
        }
        return out.toString()
    }

    /** Akhir aliran: sisa teks yang tertahan (kecuali masih di dalam blok pikiran). */
    fun finish(): String {
        val out = StringBuilder()
        if (!inThink) emit(out, buf.toString())
        buf.setLength(0)
        return out.toString()
    }

    private fun emit(out: StringBuilder, s: String) {
        if (s.isEmpty()) return
        if (trimLead) {
            val t = s.trimStart()
            if (t.isNotEmpty()) {
                trimLead = false
                out.append(t)
            }
        } else {
            out.append(s)
        }
    }

    private fun partialSuffix(tag: String): Int {
        val max = minOf(tag.length - 1, buf.length)
        for (k in max downTo 1) {
            if (buf.endsWith(tag.substring(0, k))) return k
        }
        return 0
    }

    private companion object {
        const val OPEN = "<think>"
        const val CLOSE = "</think>"
    }
}

/** Format OpenAI-compatible (Groq, Cerebras, OpenRouter). */
object OpenAiWire {

    fun body(model: String, req: ChatRequest, reasoningEffort: String?): String {
        val msgs = ArrayList<Map<String, Any?>>()
        if (req.system.isNotBlank()) msgs.add(mapOf("role" to "system", "content" to req.system))
        for ((r, t) in req.turns()) msgs.add(mapOf("role" to (if (r == "user") "user" else "assistant"), "content" to t))
        val m = LinkedHashMap<String, Any?>()
        m["model"] = model
        m["messages"] = msgs
        m["stream"] = true
        if (reasoningEffort != null) m["reasoning_effort"] = reasoningEffort
        return MiniJson.write(m)
    }

    /** Model gpt-oss menerima reasoning_effort; "low" menjaga jawaban tetap cepat. */
    fun reasoningEffortFor(providerId: String, model: String): String? =
        if ((providerId == Providers.GROQ.id || providerId == Providers.CEREBRAS.id) && model.contains("gpt-oss", ignoreCase = true)) "low" else null

    fun parseLine(line: String): SseEvent {
        if (!line.startsWith("data:")) return SseEvent.Skip
        val payload = line.substring(5).trim()
        if (payload.isEmpty()) return SseEvent.Skip
        if (payload == "[DONE]") return SseEvent.Done
        val obj = MiniJson.parseOrNull(payload) as? Map<*, *> ?: return SseEvent.Skip
        val err = obj["error"]
        if (err != null) return errorEvent(err)
        val text = MiniJson.str(obj, "choices", 0, "delta", "content")
            ?: MiniJson.str(obj, "choices", 0, "text")
        return if (!text.isNullOrEmpty()) SseEvent.Text(text) else SseEvent.Skip
    }

    internal fun errorEvent(err: Any?): SseEvent.Error = when (err) {
        is Map<*, *> -> SseEvent.Error(
            (err["message"] as? String)?.takeIf { it.isNotBlank() } ?: "error",
            MiniJson.num(err, "code")?.toInt()
        )
        is String -> SseEvent.Error(err, null)
        else -> SseEvent.Error("error", null)
    }

    /** Daftar id model dari GET /models. Untuk OpenRouter hanya yang gratis. */
    fun parseModels(json: String, providerId: String): List<String> {
        val data = MiniJson.arr(MiniJson.parseOrNull(json), "data") ?: return emptyList()
        val out = ArrayList<String>()
        for (m in data) {
            val id = MiniJson.str(m, "id")?.trim() ?: continue
            if (id.isEmpty()) continue
            if (MiniJson.get(m, "active") == false) continue
            val outs = MiniJson.arr(m, "architecture", "output_modalities")
            if (outs != null && !outs.contains("text")) continue
            if (providerId == Providers.OPENROUTER.id && !(id.endsWith(":free") || id == "openrouter/free")) continue
            if (id !in out) out.add(id)
        }
        return out
    }
}

/** Format API Gemini (Google AI Studio). */
object GeminiWire {

    fun body(req: ChatRequest, search: Boolean): String {
        val m = LinkedHashMap<String, Any?>()
        if (req.system.isNotBlank()) {
            m["system_instruction"] = mapOf("parts" to listOf(mapOf("text" to req.system)))
        }
        m["contents"] = req.turns().map { (r, t) ->
            mapOf("role" to (if (r == "user") "user" else "model"), "parts" to listOf(mapOf("text" to t)))
        }
        if (search) m["tools"] = listOf(mapOf("google_search" to emptyMap<String, Any?>()))
        return MiniJson.write(m)
    }

    private val BLOCKING = setOf("SAFETY", "RECITATION", "PROHIBITED_CONTENT", "BLOCKLIST", "SPII", "OTHER")

    fun parseLine(line: String): SseEvent {
        if (!line.startsWith("data:")) return SseEvent.Skip
        val payload = line.substring(5).trim()
        if (payload.isEmpty() || payload == "[DONE]") return SseEvent.Skip
        val obj = MiniJson.parseOrNull(payload) as? Map<*, *> ?: return SseEvent.Skip
        obj["error"]?.let { return OpenAiWire.errorEvent(it) }
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
