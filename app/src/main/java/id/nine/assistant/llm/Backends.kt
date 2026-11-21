package id.nine.assistant.llm

import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI

/** Satu penyedia AI online. Dipanggil dari thread latar (memblokir). */
interface ChatBackend {
    /** Mengalirkan jawaban lewat [onChunk]. Melempar [ProviderException] bila gagal, [CancelledException] bila di-[abort]. */
    fun stream(model: String, req: ChatRequest, onChunk: (String) -> Unit)

    /** Memutus koneksi yang sedang berjalan. */
    fun abort()
}

/** Pembantu HTTP untuk panggilan ke penyedia. */
internal class HttpCall(private val connectMs: Int, private val readMs: Int) {
    @Volatile
    private var conn: HttpURLConnection? = null

    @Volatile
    var aborted = false
        private set

    fun abort() {
        aborted = true
        try {
            conn?.disconnect()
        } catch (e: Exception) {
            // abaikan
        }
    }

    /**
     * Menjalankan permintaan [method] ke [url]; [body] null berarti tanpa isi. Bila status 2xx, [onOk] membaca aliran
     * respons; selain itu dilempar [ProviderException] hasil klasifikasi.
     */
    fun <T> run(method: String, url: String, headers: Map<String, String>, body: String?, onOk: (HttpURLConnection) -> T): T {
        val c = URI(url).toURL().openConnection() as HttpURLConnection
        conn = c
        try {
            if (aborted) throw CancelledException()
            c.requestMethod = method
            c.connectTimeout = connectMs
            c.readTimeout = readMs
            for ((k, v) in headers) c.setRequestProperty(k, v)
            if (body != null) {
                c.doOutput = true
                c.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            }
            val code = c.responseCode
            if (code !in 200..299) {
                throw ProviderException(Failures.fromHttp(code, readError(c), c.getHeaderField("Retry-After")))
            }
            return onOk(c)
        } catch (e: IOException) {
            if (aborted) throw CancelledException()
            throw e
        } finally {
            conn = null
            try {
                c.disconnect()
            } catch (e: Exception) {
                // abaikan
            }
        }
    }

    private fun readError(c: HttpURLConnection): String = try {
        c.errorStream?.bufferedReader(Charsets.UTF_8)?.use { r ->
            val sb = StringBuilder()
            val buf = CharArray(1024)
            while (sb.length < 8000) {
                val n = r.read(buf)
                if (n < 0) break
                sb.append(buf, 0, n)
            }
            sb.toString()
        } ?: ""
    } catch (e: Exception) {
        ""
    }
}

/** Membaca aliran SSE baris demi baris; jawaban non-SSE (JSON biasa) dikumpulkan di [rawOut] untuk diurai belakangan. */
internal fun readSse(c: HttpURLConnection, rawOut: StringBuilder, onLine: (String) -> Boolean) {
    c.inputStream.bufferedReader(Charsets.UTF_8).use { reader ->
        while (true) {
            val line = reader.readLine() ?: break
            if (line.startsWith("data:")) {
                if (!onLine(line)) break
            } else if (!line.startsWith(":") && !line.startsWith("event:") && !line.startsWith("id:") && !line.startsWith("retry:")) {
                if (rawOut.length < 65_536) rawOut.append(line).append('\n')
            }
        }
    }
}

/** Gemini lewat Google AI Studio. */
class GeminiBackend(
    private val key: String,
    private val search: Boolean,
    private val baseUrl: String = Providers.GEMINI.baseUrl,
    connectMs: Int = 8_000,
    readMs: Int = 25_000
) : ChatBackend {

    private val http = HttpCall(connectMs, readMs)

    override fun abort() = http.abort()

    override fun stream(model: String, req: ChatRequest, onChunk: (String) -> Unit) {
        val id = model.trim().removePrefix("models/")
        var emitted = false
        var withSearch = search
        var withThinking = id !in noThinking
        var droppedThinking = false
        while (true) {
            try {
                streamOnce(id, req, withSearch, withThinking) {
                    emitted = true
                    onChunk(it)
                }
                if (droppedThinking) noThinking.add(id)
                return
            } catch (e: ProviderException) {
                // Pencarian Google dan "thinkingLevel" tidak didukung semua kunci/model: ulangi tanpanya (paling banyak sekali per fitur).
                val f = e.failure
                if (emitted || f.code != 400) throw e
                if (withSearch && SEARCH_WORDS.containsMatchIn(f.message)) {
                    withSearch = false
                } else if (withThinking && f.kind != FailKind.AUTH) {
                    withThinking = false
                    droppedThinking = true
                } else {
                    throw e
                }
            }
        }
    }

    private fun streamOnce(id: String, req: ChatRequest, withSearch: Boolean, withThinking: Boolean, onChunk: (String) -> Unit) {
        val url = "${baseUrl.trimEnd('/')}/models/$id:streamGenerateContent?alt=sse"
        val headers = mapOf("x-goog-api-key" to key, "Content-Type" to "application/json; charset=utf-8", "Accept-Encoding" to "identity")
        http.run("POST", url, headers, GeminiWire.body(req, withSearch, withThinking)) { c ->
            val raw = StringBuilder()
            var any = false
            var blocked: String? = null
            readSse(c, raw) { line ->
                when (val ev = GeminiWire.parseLine(line)) {
                    is SseEvent.Text -> {
                        any = true
                        onChunk(ev.text)
                    }
                    is SseEvent.Error -> throw ProviderException(Failures.fromStream(ev.message, ev.code))
                    is SseEvent.Blocked -> if (!any) blocked = ev.reason
                    else -> {}
                }
                true
            }
            if (!any) {
                val b = blocked
                if (b != null) throw ProviderException(Failure(FailKind.BLOCKED, null, b))
                // Bila penyedia membalas JSON biasa
                if (raw.isNotBlank()) {
                    val v = MiniJson.parseOrNull(raw.toString().trim().removePrefix("[").removeSuffix("]").trim())
                    MiniJson.get(v, "error")?.let {
                        val e = GeminiWire.errorEvent(it)
                        throw ProviderException(Failures.fromStream(e.message, e.code))
                    }
                }
            }
        }
    }

    companion object {
        private val SEARCH_WORDS = Regex("search|ground|tool", RegexOption.IGNORE_CASE)

        /** Model yang menolak "thinkingLevel" (diingat supaya tidak diulang tiap pertanyaan). */
        private val noThinking: MutableSet<String> = java.util.concurrent.ConcurrentHashMap.newKeySet()

        internal fun forgetThinkingSupport() = noThinking.clear()
    }
}
