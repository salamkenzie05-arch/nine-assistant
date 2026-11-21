package id.nine.assistant.llm

import org.junit.After
import org.junit.Before
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

    @Before
    fun resetThinkingMemory() = GeminiBackend.forgetThinkingSupport()

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

    private val req = ChatRequest("SYS", listOf("user" to "a", "model" to "b"), "c")

    private fun collect(b: ChatBackend, model: String): String {
        val sb = StringBuilder()
        b.stream(model, req) { sb.append(it) }
        return sb.toString()
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


    @Test
    fun geminiAsksForMinimalThinkingByDefault() {
        val (base, seen) = serve { ex, _ -> ex.sse(gchunk("ok")) }
        collect(GeminiBackend("k", search = false, baseUrl = base), "gemini-flash-latest")
        val level = MiniJson.str(MiniJson.parse(seen.single().body), "generationConfig", "thinkingConfig", "thinkingLevel")
        assertEquals("MINIMAL", level)
    }

    @Test
    fun geminiRetriesWithoutThinkingWhenModelRejectsItAndRemembers() {
        val (base, seen) = serve { ex, s ->
            if (s.body.contains("thinkingConfig")) ex.json(400, """{"error":{"code":400,"message":"Thinking level MINIMAL is not supported for this model"}}""")
            else ex.sse(gchunk("tanpa berpikir"))
        }
        assertEquals("tanpa berpikir", collect(GeminiBackend("k", search = false, baseUrl = base), "gemini-lama"))
        assertEquals(2, seen.size)
        assertTrue(seen[0].body.contains("thinkingConfig"))
        assertFalse(seen[1].body.contains("thinkingConfig"))
        // pertanyaan berikutnya ke model yang sama langsung tanpa thinkingConfig (tidak menebak ulang)
        assertEquals("tanpa berpikir", collect(GeminiBackend("k", search = false, baseUrl = base), "gemini-lama"))
        assertEquals(3, seen.size)
        assertFalse(seen[2].body.contains("thinkingConfig"))
    }

    @Test
    fun geminiDropsSearchAndThinkingOneAtATime() {
        val (base, seen) = serve { ex, s ->
            when {
                s.body.contains("google_search") -> ex.json(400, """{"error":{"code":400,"message":"Search Grounding is not supported"}}""")
                s.body.contains("thinkingConfig") -> ex.json(400, """{"error":{"code":400,"message":"Unknown name thinkingLevel"}}""")
                else -> ex.sse(gchunk("polos"))
            }
        }
        assertEquals("polos", collect(GeminiBackend("k", search = true, baseUrl = base), "gemini-polos"))
        assertEquals(3, seen.size)
    }

    @Test
    fun geminiNotFoundModelIsNotRetriedForever() {
        val (base, seen) = serve { ex, _ -> ex.json(404, """{"error":{"code":404,"message":"models/gemini-x is not found"}}""") }
        try {
            collect(GeminiBackend("k", search = false, baseUrl = base), "gemini-x")
            fail()
        } catch (e: ProviderException) {
            assertEquals(FailKind.NOT_FOUND, e.failure.kind)
            assertEquals(1, seen.size) // 404 bukan 400: tidak diulang
        }
    }

    // ------------------------------------------------------------ jaringan

    @Test
    fun connectionRefusedIsNetwork() {
        val ss = java.net.ServerSocket(0, 1, java.net.InetAddress.getByName("127.0.0.1"))
        val port = ss.localPort
        ss.close() // port tertutup
        try {
            collect(GeminiBackend("k", false, "http://127.0.0.1:$port"), "m")
            fail()
        } catch (e: ConnectException) {
            assertEquals(FailKind.NETWORK, Failures.fromException(e).kind)
        }
    }

    @Test
    fun slowServerTimesOutAsTimeout() {
        val (base, _) = serve { ex, _ ->
            Thread.sleep(1500)
            ex.sse(gchunk("terlambat"))
        }
        try {
            collect(GeminiBackend("k", false, base, connectMs = 1000, readMs = 300), "m")
            fail()
        } catch (e: SocketTimeoutException) {
            assertEquals(FailKind.TIMEOUT, Failures.fromException(e).kind)
        }
    }

    @Test
    fun abortBeforeCallCancelsImmediately() {
        val (base, seen) = serve { ex, _ -> ex.sse(gchunk("x")) }
        val b = GeminiBackend("k", false, base)
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
            ex.write(gchunk("awal") + "\n\n")
            release.await(5, java.util.concurrent.TimeUnit.SECONDS)
            ex.write(gchunk(" akhir") + "\n\n")
        }
        val engine = BrainEngine({ GeminiBackend(it.key, it.search, base) })
        val token = CancelToken()
        val got = StringBuilder()
        val out = engine.run(listOf(ProviderConfig(Providers.GEMINI, "k", Providers.geminiModels(""))), req, {
            got.append(it)
            token.cancel()
            release.countDown()
        }, token)
        assertEquals(Outcome.Cancelled, out)
        assertEquals("awal", got.toString())
    }

    // ------------------------------------------------------------ mesin + klien nyata

    @Test
    fun exhaustedFlashFallsBackToLite() {
        val (base, seen) = serve { ex, s ->
            if (s.path.contains("gemini-flash-latest")) {
                ex.json(429, """{"error":{"code":429,"message":"quota","details":[{"violations":[{"quotaId":"GenerateRequestsPerDayPerProjectPerModel-FreeTier","quotaValue":"20"}]}]}}""")
            } else {
                ex.sse(gchunk("Halo dari lite"), gchunk("."))
            }
        }
        val engine = BrainEngine({ GeminiBackend(it.key, it.search, base) })
        val cfg = ProviderConfig(Providers.GEMINI, "k", Providers.geminiModels(""))
        val out = engine.run(listOf(cfg), req, { })
        assertEquals(Outcome.Success("Halo dari lite.", "Gemini", "gemini-flash-lite-latest"), out)
        assertEquals(2, seen.size)
        // pertanyaan kedua: flash istirahat, langsung ke lite
        engine.run(listOf(cfg), req, { })
        assertEquals(3, seen.size)
    }

    @Test
    fun probeShowsWhichModelFailedFirst() {
        val (base, _) = serve { ex, s ->
            if (s.path.contains("gemini-flash-latest")) ex.json(503, """{"error":{"code":503,"message":"overloaded"}}""") else ex.sse(gchunk("ready"))
        }
        val engine = BrainEngine({ GeminiBackend(it.key, it.search, base) })
        val r = engine.probe(ProviderConfig(Providers.GEMINI, "k", Providers.geminiModels("")))
        assertTrue(r.ok)
        assertEquals("gemini-flash-lite-latest", r.model)
        assertEquals(listOf("gemini-flash-latest"), r.skipped.map { it.first })
        assertEquals(FailKind.SERVER, r.skipped[0].second.kind)
    }
}
