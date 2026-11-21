package id.nine.assistant.llm

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.net.ConnectException
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException

/** Menguji klien HTTP sungguhan terhadap server lokal (tanpa internet). */
class HttpBackendTest {

    private class Seen(val method: String, val path: String, val query: String?, val headers: Map<String, String>, val body: String)

    /** Koneksi satu permintaan di server uji: menulis respons HTTP/1.1 mentah ("Connection: close", tanpa dependensi tambahan). */
    private class Conn(private val sock: Socket) {
        private val out: OutputStream = sock.getOutputStream()

        fun head(status: Int, contentType: String, length: Int?, vararg extra: Pair<String, String>) {
            val sb = StringBuilder("HTTP/1.1 $status X\r\nContent-Type: $contentType\r\nConnection: close\r\n")
            if (length != null) sb.append("Content-Length: $length\r\n")
            for ((k, v) in extra) sb.append("$k: $v\r\n")
            sb.append("\r\n")
            out.write(sb.toString().toByteArray())
            out.flush()
        }

        fun startSse(status: Int = 200) = head(status, "text/event-stream", null)

        fun write(s: String) {
            out.write(s.toByteArray())
            out.flush()
        }

        fun sse(vararg lines: String, status: Int = 200) {
            startSse(status)
            for (l in lines) write(l + "\n\n")
        }

        fun json(status: Int, body: String, vararg extra: Pair<String, String>) {
            val b = body.toByteArray()
            head(status, "application/json", b.size, *extra)
            out.write(b)
            out.flush()
        }

        fun close() {
            try {
                sock.close()
            } catch (e: Exception) {
                // abaikan
            }
        }
    }

    private val servers = ArrayList<ServerSocket>()

    @After
    fun stop() {
        servers.forEach { try { it.close() } catch (e: Exception) { } }
    }

    private fun readLine(inp: InputStream): String? {
        val bo = ByteArrayOutputStream()
        while (true) {
            val c = inp.read()
            if (c < 0) return if (bo.size() == 0) null else bo.toString("UTF-8")
            if (c == '\n'.code) return bo.toString("UTF-8").trimEnd('\r')
            bo.write(c)
        }
    }

    private fun serve(handler: (Conn, Seen) -> Unit): Pair<String, MutableList<Seen>> {
        val seen = ArrayList<Seen>()
        val ss = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
        servers.add(ss)
        Thread {
            while (!ss.isClosed) {
                val sock = try { ss.accept() } catch (e: Exception) { break }
                Thread {
                    val conn = Conn(sock)
                    try {
                        val inp = sock.getInputStream()
                        val reqLine = readLine(inp) ?: return@Thread
                        val parts = reqLine.split(" ")
                        val hs = HashMap<String, String>()
                        while (true) {
                            val l = readLine(inp) ?: break
                            if (l.isEmpty()) break
                            val i = l.indexOf(':')
                            if (i > 0) hs[l.substring(0, i).trim().lowercase()] = l.substring(i + 1).trim()
                        }
                        val len = hs["content-length"]?.toIntOrNull() ?: 0
                        val bodyBytes = ByteArray(len)
                        var got = 0
                        while (got < len) {
                            val n = inp.read(bodyBytes, got, len - got)
                            if (n < 0) break
                            got += n
                        }
                        val target = parts.getOrElse(1) { "/" }
                        val q = target.indexOf('?')
                        val sn = Seen(parts[0], if (q >= 0) target.substring(0, q) else target, if (q >= 0) target.substring(q + 1) else null, hs, String(bodyBytes, Charsets.UTF_8))
                        synchronized(seen) { seen.add(sn) }
                        handler(conn, sn)
                    } catch (e: Exception) {
                        // klien memutus: abaikan
                    } finally {
                        conn.close()
                    }
                }.start()
            }
        }.start()
        return "http://127.0.0.1:${ss.localPort}" to seen
    }

    private fun chunk(t: String) = """data: {"choices":[{"delta":{"content":${MiniJson.write(t)}}}]}"""

    private val req = ChatRequest("SYS", listOf("user" to "a", "model" to "b"), "c")

    private fun collect(b: ChatBackend, model: String): String {
        val sb = StringBuilder()
        b.stream(model, req) { sb.append(it) }
        return sb.toString()
    }

    // ------------------------------------------------------------ OpenAI-compatible

    @Test
    fun openAiStreamsTextAndSendsAuthAndBody() {
        val (base, seen) = serve { ex, _ -> ex.sse(": PROCESSING", chunk("Ha"), chunk("lo "), chunk("dunia"), "data: [DONE]") }
        val b = OpenAiBackend(Providers.OPENROUTER, "SECRET", "$base/v1")
        assertEquals("Halo dunia", collect(b, "meta/llama:free"))
        val s = seen.single()
        assertEquals("POST", s.method)
        assertEquals("/v1/chat/completions", s.path)
        assertEquals("Bearer SECRET", s.headers["authorization"])
        assertNotNull(s.headers["x-title"])
        val v = MiniJson.parse(s.body)
        assertEquals("meta/llama:free", MiniJson.str(v, "model"))
        assertEquals(true, MiniJson.get(v, "stream"))
        assertEquals("SYS", MiniJson.str(v, "messages", 0, "content"))
        assertEquals("c", MiniJson.str(v, "messages", 3, "content"))
        assertNull(MiniJson.get(v, "reasoning_effort"))
    }

    @Test
    fun groqGptOssGetsReasoningEffortAndNoOpenRouterHeaders() {
        val (base, seen) = serve { ex, _ -> ex.sse(chunk("ok"), "data: [DONE]") }
        val b = OpenAiBackend(Providers.GROQ, "k", "$base/openai/v1")
        collect(b, "openai/gpt-oss-120b")
        assertEquals("low", MiniJson.str(MiniJson.parse(seen.single().body), "reasoning_effort"))
        assertNull(seen.single().headers["x-title"])
    }

    @Test
    fun thinkBlocksAreRemovedAcrossSseLines() {
        val (base, _) = serve { ex, _ ->
            ex.sse(chunk("<thi"), chunk("nk>aku mikir"), chunk(" dulu</th"), chunk("ink>\n\nJawaban"), chunk(" final."), "data: [DONE]")
        }
        assertEquals("Jawaban final.", collect(OpenAiBackend(Providers.CEREBRAS, "k", base), "qwen-3-235b"))
    }

    @Test
    fun rateLimitCarriesRetryAfter() {
        val (base, _) = serve { ex, _ -> ex.json(429, """{"error":{"message":"Rate limit reached on tokens per minute"}}""", "Retry-After" to "7") }
        try {
            collect(OpenAiBackend(Providers.GROQ, "k", base), "m")
            fail()
        } catch (e: ProviderException) {
            assertEquals(FailKind.RATE_LIMIT, e.failure.kind)
            assertEquals(7000L, e.failure.retryAfterMs)
            assertEquals(429, e.failure.code)
            assertEquals("Rate limit reached on tokens per minute", e.failure.message)
        }
    }

    @Test
    fun authErrorIsClassified() {
        val (base, _) = serve { ex, _ -> ex.json(401, """{"error":{"message":"Invalid API Key","type":"invalid_request_error"}}""") }
        try {
            collect(OpenAiBackend(Providers.GROQ, "k", base), "m")
            fail()
        } catch (e: ProviderException) {
            assertEquals(FailKind.AUTH, e.failure.kind)
        }
    }

    @Test
    fun retriesWithoutReasoningEffortWhenRejected() {
        val (base, seen) = serve { ex, s ->
            if (s.body.contains("reasoning_effort")) ex.json(400, """{"error":{"message":"unknown field reasoning_effort"}}""")
            else ex.sse(chunk("lolos"), "data: [DONE]")
        }
        assertEquals("lolos", collect(OpenAiBackend(Providers.CEREBRAS, "k", base), "gpt-oss-120b"))
        assertEquals(2, seen.size)
        assertFalse(seen[1].body.contains("reasoning_effort"))
    }

    @Test
    fun noRetryForOtherModelsOn400() {
        val (base, seen) = serve { ex, _ -> ex.json(400, """{"error":{"message":"bad"}}""") }
        try {
            collect(OpenAiBackend(Providers.GROQ, "k", base), "llama-3.1-8b-instant")
            fail()
        } catch (e: ProviderException) {
            assertEquals(1, seen.size)
        }
    }

    @Test
    fun inStreamErrorAfterTextKeepsTextAndThrows() {
        val (base, _) = serve { ex, _ ->
            ex.sse(chunk("Halo "), """data: {"error":{"code":429,"message":"Rate limit exceeded: free-models-per-day"}}""")
        }
        val got = StringBuilder()
        try {
            OpenAiBackend(Providers.OPENROUTER, "k", base).stream("m:free", req) { got.append(it) }
            fail()
        } catch (e: ProviderException) {
            assertEquals(FailKind.QUOTA_DAILY, e.failure.kind)
        }
        assertEquals("Halo ", got.toString())
    }

    @Test
    fun nonStreamingJsonReplyIsStillUnderstood() {
        val (base, _) = serve { ex, _ ->
            ex.json(200, """{"choices":[{"message":{"role":"assistant","content":"<think>x</think>Jawaban biasa"}}]}""")
        }
        assertEquals("Jawaban biasa", collect(OpenAiBackend(Providers.GROQ, "k", base), "m"))
    }

    @Test
    fun errorObjectWithStatus200IsAnError() {
        val (base, _) = serve { ex, _ -> ex.json(200, """{"error":{"code":429,"message":"Rate limit"}}""") }
        try {
            collect(OpenAiBackend(Providers.OPENROUTER, "k", base), "m:free")
            fail()
        } catch (e: ProviderException) {
            assertEquals(FailKind.RATE_LIMIT, e.failure.kind)
        }
    }

    @Test
    fun emptyStreamProducesNoText() {
        val (base, _) = serve { ex, _ -> ex.sse("data: [DONE]") }
        assertEquals("", collect(OpenAiBackend(Providers.GROQ, "k", base), "m"))
    }

    @Test
    fun listModelsParsesAndFiltersOpenRouter() {
        val (base, seen) = serve { ex, _ ->
            ex.json(200, """{"data":[{"id":"a:free"},{"id":"paid/b"},{"id":"openrouter/free"}]}""")
        }
        val ids = OpenAiBackend(Providers.OPENROUTER, "KEY", base).listModels()
        assertEquals(listOf("a:free", "openrouter/free"), ids)
        assertEquals("GET", seen.single().method)
        assertEquals("/models", seen.single().path)
        assertEquals("Bearer KEY", seen.single().headers["authorization"])
    }

    @Test
    fun listModelsAuthError() {
        val (base, _) = serve { ex, _ -> ex.json(401, """{"error":{"message":"Invalid API Key"}}""") }
        try {
            OpenAiBackend(Providers.GROQ, "k", base).listModels()
            fail()
        } catch (e: ProviderException) {
            assertEquals(FailKind.AUTH, e.failure.kind)
        }
    }

    // ------------------------------------------------------------ Gemini

    private fun gchunk(t: String, thought: Boolean = false) =
        """data: {"candidates":[{"content":{"role":"model","parts":[{"text":${MiniJson.write(t)}${if (thought) ""","thought":true""" else ""}}]}}]}"""

    @Test
    fun geminiStreamsAndSendsKeyHeader() {
        val (base, seen) = serve { ex, _ -> ex.sse(gchunk("pikir", thought = true), gchunk("Ha"), gchunk("lo")) }
        val b = GeminiBackend("GKEY", search = false, baseUrl = "$base/v1beta")
        assertEquals("Halo", collect(b, "models/gemini-flash-latest"))
        val s = seen.single()
        assertEquals("/v1beta/models/gemini-flash-latest:streamGenerateContent", s.path)
        assertEquals("alt=sse", s.query)
        assertEquals("GKEY", s.headers["x-goog-api-key"])
        val v = MiniJson.parse(s.body)
        assertEquals("c", MiniJson.str(v, "contents", 2, "parts", 0, "text"))
        assertNull(MiniJson.get(v, "tools"))
    }

    @Test
    fun geminiRetriesWithoutSearchToolWhenRejected() {
        val (base, seen) = serve { ex, s ->
            if (s.body.contains("google_search")) ex.json(400, """{"error":{"code":400,"message":"Search Grounding is not supported for this model"}}""")
            else ex.sse(gchunk("tanpa pencarian"))
        }
        val b = GeminiBackend("k", search = true, baseUrl = base)
        assertEquals("tanpa pencarian", collect(b, "gemini-x"))
        assertEquals(2, seen.size)
        assertTrue(seen[0].body.contains("google_search"))
        assertFalse(seen[1].body.contains("google_search"))
    }

    @Test
    fun geminiInvalidKeyIsAuth() {
        val (base, seen) = serve { ex, _ ->
            ex.json(400, """{"error":{"code":400,"message":"API key not valid. Please pass a valid API key.","status":"INVALID_ARGUMENT"}}""")
        }
        try {
            collect(GeminiBackend("bad", search = true, baseUrl = base), "gemini-x")
            fail()
        } catch (e: ProviderException) {
            assertEquals(FailKind.AUTH, e.failure.kind)
            assertEquals(1, seen.size) // bukan soal pencarian: tidak diulang
        }
    }

    @Test
    fun geminiQuotaWithRetryDelay() {
        val (base, _) = serve { ex, _ ->
            ex.json(429, """{"error":{"code":429,"message":"You exceeded your current quota","status":"RESOURCE_EXHAUSTED","details":[{"@type":"type.googleapis.com/google.rpc.RetryInfo","retryDelay":"21s"}]}}""")
        }
        try {
            collect(GeminiBackend("k", search = false, baseUrl = base), "gemini-x")
            fail()
        } catch (e: ProviderException) {
            assertEquals(FailKind.RATE_LIMIT, e.failure.kind)
            assertEquals(21000L, e.failure.retryAfterMs)
        }
    }

    @Test
    fun geminiBlockedPromptIsBlocked() {
        val (base, _) = serve { ex, _ -> ex.sse("""data: {"promptFeedback":{"blockReason":"SAFETY"}}""") }
        try {
            collect(GeminiBackend("k", search = false, baseUrl = base), "gemini-x")
            fail()
        } catch (e: ProviderException) {
            assertEquals(FailKind.BLOCKED, e.failure.kind)
        }
    }

    @Test
    fun geminiBlockedAfterTextKeepsText() {
        val (base, _) = serve { ex, _ -> ex.sse(gchunk("Sebagian"), """data: {"candidates":[{"finishReason":"SAFETY"}]}""") }
        assertEquals("Sebagian", collect(GeminiBackend("k", search = false, baseUrl = base), "gemini-x"))
    }

    // ------------------------------------------------------------ jaringan

    @Test
    fun connectionRefusedIsNetwork() {
        val ss = java.net.ServerSocket(0, 1, java.net.InetAddress.getByName("127.0.0.1"))
        val port = ss.localPort
        ss.close() // port tertutup
        try {
            collect(OpenAiBackend(Providers.GROQ, "k", "http://127.0.0.1:$port"), "m")
            fail()
        } catch (e: ConnectException) {
            assertEquals(FailKind.NETWORK, Failures.fromException(e).kind)
        }
    }

    @Test
    fun slowServerTimesOutAsTimeout() {
        val (base, _) = serve { ex, _ ->
            Thread.sleep(1500)
            ex.sse(chunk("terlambat"))
        }
        try {
            collect(OpenAiBackend(Providers.GROQ, "k", base, connectMs = 1000, readMs = 300), "m")
            fail()
        } catch (e: SocketTimeoutException) {
            assertEquals(FailKind.TIMEOUT, Failures.fromException(e).kind)
        }
    }

    @Test
    fun abortBeforeCallCancelsImmediately() {
        val (base, seen) = serve { ex, _ -> ex.sse(chunk("x")) }
        val b = OpenAiBackend(Providers.GROQ, "k", base)
        b.abort()
        try {
            collect(b, "m")
            fail()
        } catch (e: CancelledException) {
            assertTrue(seen.isEmpty())
        }
    }

    @Test
    fun engineCancelDuringRealStreamYieldsCancelledAndKeepsEarlyText() {
        val release = java.util.concurrent.CountDownLatch(1)
        val (base, _) = serve { ex, _ ->
            ex.startSse()
            ex.write(chunk("awal") + "\n\n")
            release.await(5, java.util.concurrent.TimeUnit.SECONDS)
            ex.write(chunk(" akhir") + "\n\ndata: [DONE]\n\n")
        }
        val engine = BrainEngine({ OpenAiBackend(it.info, it.key, base) }, MemoryModelStore())
        val token = CancelToken()
        val got = StringBuilder()
        val out = engine.run(listOf(ProviderConfig(Providers.CEREBRAS, "k")), req, {
            got.append(it)
            token.cancel()
            release.countDown()
        }, token)
        assertEquals(Outcome.Cancelled, out)
        assertEquals("awal", got.toString())
    }

    // ------------------------------------------------------------ mesin + klien nyata

    @Test
    fun engineFallsBackAcrossRealHttpProviders() {
        val (gBase, gSeen) = serve { ex, _ ->
            ex.json(429, """{"error":{"code":429,"message":"quota","details":[{"quotaId":"GenerateRequestsPerDayPerProjectPerModel-FreeTier"}]}}""")
        }
        val (qBase, qSeen) = serve { ex, s ->
            if (s.path.endsWith("/models")) ex.json(200, """{"data":[{"id":"whisper-large-v3"},{"id":"openai/gpt-oss-120b"}]}""")
            else ex.sse(chunk("Halo dari Groq"), "data: [DONE]")
        }
        val engine = BrainEngine({ cfg ->
            if (cfg.info.native) GeminiBackend(cfg.key, cfg.search, "$gBase/v1beta") else OpenAiBackend(cfg.info, cfg.key, "$qBase/openai/v1")
        }, MemoryModelStore())
        val sink = StringBuilder()
        val out = engine.run(
            listOf(ProviderConfig(Providers.GEMINI, "g"), ProviderConfig(Providers.GROQ, "q")),
            req, { sink.append(it) }
        )
        assertEquals(Outcome.Success("Halo dari Groq", "Groq", "openai/gpt-oss-120b"), out)
        assertEquals("Halo dari Groq", sink.toString())
        assertEquals(2, gSeen.size) // dua model Gemini dicoba
        assertEquals(listOf("/openai/v1/models", "/openai/v1/chat/completions"), qSeen.map { it.path })
        // pertanyaan kedua: Gemini sedang istirahat (kuota harian), langsung ke Groq tanpa menanya daftar model lagi
        val out2 = engine.run(
            listOf(ProviderConfig(Providers.GEMINI, "g"), ProviderConfig(Providers.GROQ, "q")),
            req, { }
        )
        assertTrue(out2 is Outcome.Success)
        assertEquals(2, gSeen.size)
        assertEquals(3, qSeen.size)
    }
}
