package id.nine.assistant.llm

import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI

/** Satu penyedia AI online. Dipanggil dari thread latar (memblokir). */
interface ChatBackend {
    /** Mengalirkan jawaban lewat [onChunk]. Melempar [ProviderException] bila gagal, [CancelledException] bila di-[abort]. */
    fun stream(model: String, req: ChatRequest, onChunk: (String) -> Unit)

    /** Daftar id model yang ditawarkan penyedia (kosong bila tak didukung). */
    fun listModels(): List<String>

    /** Memutus koneksi yang sedang berjalan. */
    fun abort()
}

/** Pembantu HTTP bersama untuk kedua jenis penyedia. */
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

/** Groq, Cerebras, OpenRouter: format OpenAI-compatible. */
class OpenAiBackend(
    private val info: ProviderInfo,
    private val key: String,
    private val baseUrl: String = info.baseUrl,
    connectMs: Int = 8_000,
    readMs: Int = 30_000
) : ChatBackend {

    private val http = HttpCall(connectMs, readMs)
    private val base get() = baseUrl.trimEnd('/')

    private fun headers(): Map<String, String> {
        val h = LinkedHashMap<String, String>()
        h["Authorization"] = "Bearer $key"
        h["Content-Type"] = "application/json; charset=utf-8"
        h["Accept"] = "text/event-stream"
        h["Accept-Encoding"] = "identity" // aliran SSE tanpa kompresi supaya potongan jawaban tiba langsung
        if (info.id == Providers.OPENROUTER.id) {
            h["HTTP-Referer"] = "https://github.com/salamkenzie05-arch/nine-assistant"
            h["X-Title"] = "Nine"
        }
        return h
    }

    override fun abort() = http.abort()

    override fun stream(model: String, req: ChatRequest, onChunk: (String) -> Unit) {
        val effort = OpenAiWire.reasoningEffortFor(info.id, model)
        var emitted = false
        try {
            streamOnce(model, req, effort) {
                emitted = true
                onChunk(it)
            }
        } catch (e: ProviderException) {
            // Sebagian model menolak reasoning_effort: ulangi sekali tanpa parameter itu (hanya bila belum ada teks keluar).
            val code = e.failure.code
            if (effort != null && !emitted && (code == 400 || code == 422)) streamOnce(model, req, null, onChunk) else throw e
        }
    }

    private fun streamOnce(model: String, req: ChatRequest, effort: String?, onChunk: (String) -> Unit) {
        http.run("POST", "$base/chat/completions", headers(), OpenAiWire.body(model, req, effort)) { c ->
            val filter = ThinkFilter()
            val raw = StringBuilder()
            var any = false
            fun emit(t: String) {
                val out = filter.feed(t)
                if (out.isNotEmpty()) {
                    any = true
                    onChunk(out)
                }
            }
            readSse(c, raw) { line ->
                when (val ev = OpenAiWire.parseLine(line)) {
                    is SseEvent.Text -> {
                        emit(ev.text)
                        true
                    }
                    is SseEvent.Error -> throw ProviderException(Failures.fromStream(ev.message, ev.code))
                    SseEvent.Done -> false
                    else -> true
                }
            }
            val tail = filter.finish()
            if (tail.isNotEmpty()) {
                any = true
                onChunk(tail)
            }
            if (!any && raw.isNotBlank()) {
                // Penyedia membalas JSON biasa (bukan SSE): ambil isinya.
                val v = MiniJson.parseOrNull(raw.toString())
                MiniJson.get(v, "error")?.let {
                    val e = OpenAiWire.errorEvent(it)
                    throw ProviderException(Failures.fromStream(e.message, e.code))
                }
                val text = MiniJson.str(v, "choices", 0, "message", "content")
                if (!text.isNullOrBlank()) {
                    val f = ThinkFilter()
                    val clean = f.feed(text) + f.finish()
                    if (clean.isNotEmpty()) onChunk(clean)
                }
            }
        }
    }

    override fun listModels(): List<String> =
        http.run("GET", "$base/models", mapOf("Authorization" to "Bearer $key", "Accept" to "application/json"), null) { c ->
            val text = c.inputStream.bufferedReader(Charsets.UTF_8).use { r ->
                val sb = StringBuilder()
                val buf = CharArray(8192)
                while (sb.length < 4_000_000) {
                    val n = r.read(buf)
                    if (n < 0) break
                    sb.append(buf, 0, n)
                }
                sb.toString()
            }
            OpenAiWire.parseModels(text, info.id)
        }
}

/** Gemini lewat Google AI Studio (API sendiri, bukan format OpenAI). */
class GeminiBackend(
    private val key: String,
    private val search: Boolean,
    private val baseUrl: String = Providers.GEMINI.baseUrl,
    connectMs: Int = 8_000,
    readMs: Int = 40_000
) : ChatBackend {

    private val http = HttpCall(connectMs, readMs)

    override fun abort() = http.abort()

    override fun listModels(): List<String> = emptyList()

    override fun stream(model: String, req: ChatRequest, onChunk: (String) -> Unit) {
        var emitted = false
        try {
            streamOnce(model, req, search) {
                emitted = true
                onChunk(it)
            }
        } catch (e: ProviderException) {
            // Pencarian Google tidak tersedia di semua kunci/model gratis: ulangi tanpa alat pencarian.
            val m = e.failure.message
            if (search && !emitted && e.failure.code == 400 && Regex("search|ground|tool", RegexOption.IGNORE_CASE).containsMatchIn(m)) {
                streamOnce(model, req, false, onChunk)
            } else {
                throw e
            }
        }
    }

    private fun streamOnce(model: String, req: ChatRequest, withSearch: Boolean, onChunk: (String) -> Unit) {
        val id = model.trim().removePrefix("models/")
        val url = "${baseUrl.trimEnd('/')}/models/$id:streamGenerateContent?alt=sse"
        val headers = mapOf("x-goog-api-key" to key, "Content-Type" to "application/json; charset=utf-8", "Accept-Encoding" to "identity")
        http.run("POST", url, headers, GeminiWire.body(req, withSearch)) { c ->
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
                        val e = OpenAiWire.errorEvent(it)
                        throw ProviderException(Failures.fromStream(e.message, e.code))
                    }
                }
            }
        }
    }
}
