package id.nine.assistant.llm

import android.content.Context
import android.os.Handler
import android.os.Looper
import id.nine.assistant.core.AppLang
import id.nine.assistant.core.Prefs
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.time.LocalDateTime
import java.util.TimeZone
import java.util.concurrent.Executors

/**
 * Otak online Nine lewat Claude (Anthropic Messages API) dengan pencarian web, jawaban di-stream lewat SSE.
 * Dipakai hanya bila pengguna memilih Claude di Mode internet dan mengisi API key dari Claude Console.
 */
class ClaudeClient(context: Context, private val prefs: Prefs) : OnlineBrain {

    private val app = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private val exec = Executors.newSingleThreadExecutor { r -> Thread(r, "nine-claude") }

    @Volatile
    private var generation = 0

    @Volatile
    private var connection: HttpURLConnection? = null

    override val displayName: String = "Claude"

    override fun isConfigured(): Boolean = prefs.internetMode && prefs.claudeKey.isNotBlank()

    override fun isOnline(): Boolean = OnlineBrain.networkOk(app)

    override fun cancel() {
        generation++
        try {
            connection?.disconnect()
        } catch (e: Exception) {
            // abaikan
        }
    }

    private class ApiException(val code: Int, val type: String, msg: String) : IOException(msg)

    /** Isi permintaan; [minimal] = tanpa alat pencarian & pengaturan effort (untuk akun/model yang menolaknya). */
    private fun requestBody(text: String, lang: AppLang, history: List<Pair<String, String>>, context: String, minimal: Boolean): String {
        val body = JSONObject()
        body.put("model", prefs.claudeModel)
        body.put("max_tokens", 2048)
        body.put("stream", true)
        body.put(
            "system",
            prefs.persona.onlinePrompt(lang, LocalDateTime.now(), prefs.city, lang.t("pencarian web", "web search")) + "\n\n" + context
        )
        // Pesan harus bergantian user/assistant dan diawali user: gabungkan giliran beruntun dengan role sama.
        val turns = ArrayList<Pair<String, String>>()
        for ((role, t) in history + ("user" to text)) {
            val r = if (role == "user") "user" else "assistant"
            if (t.isBlank()) continue
            if (turns.isNotEmpty() && turns.last().first == r) {
                turns[turns.size - 1] = r to (turns.last().second + "\n" + t)
            } else {
                turns.add(r to t)
            }
        }
        while (turns.isNotEmpty() && turns.first().first != "user") turns.removeAt(0)
        val msgs = JSONArray()
        for ((r, t) in turns) msgs.put(JSONObject().put("role", r).put("content", t))
        body.put("messages", msgs)
        if (!minimal) {
            // Effort rendah: jawaban cepat dan hemat untuk asisten suara.
            body.put("output_config", JSONObject().put("effort", "low"))
            val tool = JSONObject()
                .put("type", "web_search_20250305")
                .put("name", "web_search")
                .put("max_uses", 3)
            val loc = JSONObject().put("type", "approximate").put("timezone", TimeZone.getDefault().id)
            if (prefs.city.isNotBlank()) loc.put("city", prefs.city).put("country", "ID")
            tool.put("user_location", loc)
            body.put("tools", JSONArray().put(tool))
        }
        return body.toString()
    }

    override fun ask(text: String, lang: AppLang, history: List<Pair<String, String>>, context: String, cb: OnlineBrain.Callback) {
        val gen = ++generation
        exec.execute {
            val full = StringBuilder()
            var minimal = false
            while (true) {
                try {
                    stream(requestBody(text, lang, history, context, minimal), gen, cb, full)
                    if (gen != generation) return@execute
                    val result = full.toString()
                    main.post { if (gen == generation) cb.onDone(result) }
                    return@execute
                } catch (e: InterruptedException) {
                    return@execute
                } catch (e: ApiException) {
                    if (gen != generation) return@execute
                    // Model/akun menolak alat pencarian atau effort: ulangi sekali tanpa keduanya.
                    if (e.code == 400 && !minimal && full.isEmpty()) {
                        minimal = true
                        continue
                    }
                    val canFallback = full.isEmpty() && (e.code == 429 || e.code == 529 || e.code >= 500)
                    main.post {
                        if (gen != generation) return@post
                        if (canFallback) cb.onFallback("HTTP ${e.code}")
                        else if (full.isNotEmpty()) cb.onDone(full.toString())
                        else cb.onError(apiMessage(e, lang))
                    }
                    return@execute
                } catch (e: Exception) {
                    if (gen != generation) return@execute
                    val msg = e.message ?: e.javaClass.simpleName
                    main.post {
                        if (gen != generation) return@post
                        if (full.isEmpty()) cb.onFallback(msg) else cb.onDone(full.toString())
                    }
                    return@execute
                }
            }
        }
    }

    private fun apiMessage(e: ApiException, lang: AppLang): String = when (e.code) {
        401 -> lang.t(
            "API key Claude ditolak. Periksa key di Pengaturan > Otak AI.",
            "The Claude API key was rejected. Check it in Settings > AI brain."
        )
        403 -> lang.t("Akses Claude ditolak (403): ${e.message}", "Claude access denied (403): ${e.message}")
        404 -> lang.t("Model Claude \"${prefs.claudeModel}\" tidak ditemukan.", "Claude model \"${prefs.claudeModel}\" was not found.")
        402 -> lang.t("Saldo kredit API Claude habis. Isi ulang di Claude Console.", "Your Claude API credit balance is empty. Top up in the Claude Console.")
        else -> "Claude ${e.code}: ${e.message}"
    }

    private fun stream(body: String, gen: Int, cb: OnlineBrain.Callback, full: StringBuilder) {
        val c = URL("https://api.anthropic.com/v1/messages").openConnection() as HttpURLConnection
        connection = c
        try {
            c.requestMethod = "POST"
            c.connectTimeout = 10_000
            c.readTimeout = 60_000
            c.doOutput = true
            c.setRequestProperty("content-type", "application/json; charset=utf-8")
            c.setRequestProperty("x-api-key", prefs.claudeKey)
            c.setRequestProperty("anthropic-version", "2023-06-01")
            c.setRequestProperty("accept", "text/event-stream")
            c.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            val code = c.responseCode
            if (code != 200) {
                val err = try {
                    c.errorStream?.bufferedReader()?.use { it.readText() } ?: ""
                } catch (e: Exception) {
                    ""
                }
                val (type, msg) = try {
                    val o = JSONObject(err).getJSONObject("error")
                    o.optString("type", "") to o.optString("message", "")
                } catch (e: Exception) {
                    "" to err.take(200)
                }
                val status = if (type == "billing_error") 402 else code
                throw ApiException(status, type, msg)
            }
            var emittedThisBlock = false
            var sawSearch = false
            c.inputStream.bufferedReader(Charsets.UTF_8).use { reader ->
                while (true) {
                    if (gen != generation) throw InterruptedException()
                    val line = reader.readLine() ?: break
                    if (!line.startsWith("data:")) continue
                    val json = line.substring(5).trim()
                    if (json.isEmpty()) continue
                    val obj = try {
                        JSONObject(json)
                    } catch (e: Exception) {
                        continue
                    }
                    when (obj.optString("type")) {
                        "content_block_start" -> {
                            val blockType = obj.optJSONObject("content_block")?.optString("type") ?: ""
                            if (blockType == "server_tool_use") {
                                // Teks sebelum pencarian hanyalah pengantar: buang.
                                if (!sawSearch && full.isNotEmpty() && full.length < 300) {
                                    full.setLength(0)
                                    main.post { if (gen == generation) cb.onReset() }
                                }
                                sawSearch = true
                            }
                            emittedThisBlock = false
                        }
                        "content_block_delta" -> {
                            val d = obj.optJSONObject("delta") ?: continue
                            if (d.optString("type") == "text_delta") {
                                var t = d.optString("text", "")
                                if (t.isEmpty()) continue
                                // Blok teks baru (mis. setelah kutipan) kadang tidak diawali spasi.
                                if (!emittedThisBlock && full.isNotEmpty() && !full.last().isWhitespace() && !t.first().isWhitespace() &&
                                    full.last() in ".!?:"
                                ) t = " $t"
                                emittedThisBlock = true
                                full.append(t)
                                val chunk = t
                                main.post { if (gen == generation) cb.onChunk(chunk) }
                            }
                        }
                        "content_block_stop" -> emittedThisBlock = false
                        "error" -> {
                            val e = obj.optJSONObject("error")
                            val type = e?.optString("type") ?: ""
                            val code = when (type) {
                                "overloaded_error" -> 529
                                "rate_limit_error" -> 429
                                "api_error" -> 500
                                else -> 500
                            }
                            throw ApiException(code, type, e?.optString("message") ?: type)
                        }
                        "message_stop" -> return
                    }
                }
            }
        } finally {
            connection = null
            try {
                c.disconnect()
            } catch (e: Exception) {
                // abaikan
            }
        }
    }
}
