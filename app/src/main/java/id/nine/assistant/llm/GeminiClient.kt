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
import java.util.concurrent.Executors

/**
 * Otak online Nine: Gemini API (Google AI Studio) dengan Google Search, jawaban di-stream lewat SSE.
 * Dipakai hanya bila pengguna menyalakan "Mode internet" dan mengisi API key.
 */
class GeminiClient(context: Context, private val prefs: Prefs) : OnlineBrain {

    private val app = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private val exec = Executors.newSingleThreadExecutor { r -> Thread(r, "nine-gemini") }

    @Volatile
    private var generation = 0

    @Volatile
    private var connection: HttpURLConnection? = null

    override val displayName: String = "Gemini"

    override fun isConfigured(): Boolean = prefs.internetMode && prefs.geminiKey.isNotBlank()

    override fun isOnline(): Boolean = OnlineBrain.networkOk(app)

    /** Riwayat diambil dari percakapan di layar, jadi tidak ada yang perlu direset di sini. */
    override fun reset() {}

    override fun cancel() {
        generation++
        try {
            connection?.disconnect()
        } catch (e: Exception) {
            // abaikan
        }
    }

    private class ApiException(val code: Int, msg: String) : IOException(msg)

    /**
     * [history]: giliran sebelumnya (role "user"/"model"), sudah urut. [context]: info perangkat terkini
     * (tugas, acara) yang ditambahkan ke instruksi sistem.
     */
    override fun ask(text: String, lang: AppLang, history: List<Pair<String, String>>, context: String, cb: OnlineBrain.Callback) {
        val gen = ++generation
        exec.execute {
            val full = StringBuilder()
            try {
                stream(text, lang, history, context) { chunk ->
                    if (gen != generation) throw InterruptedException()
                    full.append(chunk)
                    main.post { if (gen == generation) cb.onChunk(chunk) }
                }
                if (gen != generation) return@execute
                val result = full.toString()
                main.post { if (gen == generation) cb.onDone(result) }
            } catch (e: InterruptedException) {
                // dibatalkan
            } catch (e: ApiException) {
                if (gen != generation) return@execute
                val canFallback = full.isEmpty() && (e.code == 429 || e.code >= 500)
                main.post {
                    if (gen != generation) return@post
                    if (canFallback) cb.onFallback("HTTP ${e.code}")
                    else cb.onError(apiMessage(e, lang))
                }
            } catch (e: Exception) {
                if (gen != generation) return@execute
                val msg = e.message ?: e.javaClass.simpleName
                main.post {
                    if (gen != generation) return@post
                    if (full.isEmpty()) cb.onFallback(msg) else cb.onDone(full.toString())
                }
            }
        }
    }

    private fun apiMessage(e: ApiException, lang: AppLang): String = when (e.code) {
        400, 401, 403 -> lang.t(
            "API key Gemini ditolak (${e.code}). Periksa key di Pengaturan > Mode internet.",
            "The Gemini API key was rejected (${e.code}). Check it in Settings > Internet mode."
        )
        404 -> lang.t("Model Gemini \"${prefs.geminiModel}\" tidak ditemukan.", "Gemini model \"${prefs.geminiModel}\" was not found.")
        else -> "Gemini ${e.code}: ${e.message}"
    }

    private fun requestBody(text: String, lang: AppLang, history: List<Pair<String, String>>, context: String): String {
        val persona = prefs.persona
        val body = JSONObject()
        body.put(
            "system_instruction",
            JSONObject().put("parts", JSONArray().put(JSONObject().put("text", persona.onlinePrompt(lang, LocalDateTime.now(), prefs.city, "Google Search") + "\n\n" + context)))
        )
        val contents = JSONArray()
        for ((role, t) in history + ("user" to text)) {
            contents.put(JSONObject().put("role", role).put("parts", JSONArray().put(JSONObject().put("text", t))))
        }
        body.put("contents", contents)
        body.put("tools", JSONArray().put(JSONObject().put("google_search", JSONObject())))
        body.put("generationConfig", JSONObject().put("temperature", 0.7))
        return body.toString()
    }

    private fun stream(text: String, lang: AppLang, history: List<Pair<String, String>>, context: String, onChunk: (String) -> Unit) {
        val model = prefs.geminiModel
        val url = URL("https://generativelanguage.googleapis.com/v1beta/models/$model:streamGenerateContent?alt=sse")
        val c = url.openConnection() as HttpURLConnection
        connection = c
        try {
            c.requestMethod = "POST"
            c.connectTimeout = 10_000
            c.readTimeout = 45_000
            c.doOutput = true
            c.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            c.setRequestProperty("x-goog-api-key", prefs.geminiKey)
            c.outputStream.use { it.write(requestBody(text, lang, history, context).toByteArray(Charsets.UTF_8)) }
            val code = c.responseCode
            if (code != 200) {
                val err = try {
                    c.errorStream?.bufferedReader()?.use { it.readText() } ?: ""
                } catch (e: Exception) {
                    ""
                }
                val msg = try {
                    JSONObject(err).getJSONObject("error").optString("message", "")
                } catch (e: Exception) {
                    err.take(200)
                }
                throw ApiException(code, msg)
            }
            c.inputStream.bufferedReader(Charsets.UTF_8).use { reader ->
                while (true) {
                    val line = reader.readLine() ?: break
                    if (!line.startsWith("data:")) continue
                    val json = line.substring(5).trim()
                    if (json.isEmpty() || json == "[DONE]") continue
                    val obj = try {
                        JSONObject(json)
                    } catch (e: Exception) {
                        continue
                    }
                    val cands = obj.optJSONArray("candidates") ?: continue
                    if (cands.length() == 0) continue
                    val parts = cands.getJSONObject(0).optJSONObject("content")?.optJSONArray("parts") ?: continue
                    for (i in 0 until parts.length()) {
                        val p = parts.getJSONObject(i)
                        if (p.optBoolean("thought", false)) continue
                        val t = p.optString("text", "")
                        if (t.isNotEmpty()) onChunk(t)
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
