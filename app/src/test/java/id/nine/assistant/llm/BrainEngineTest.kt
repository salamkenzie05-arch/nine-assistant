package id.nine.assistant.llm

import id.nine.assistant.core.AppLang
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.net.UnknownHostException

class BrainEngineTest {

    private class World {
        val calls = ArrayList<String>()
        val listCalls = ArrayList<String>()
        val streams = HashMap<String, (String, (String) -> Unit) -> Unit>()
        val lists = HashMap<String, () -> List<String>>()
        var now = 10_000_000L
        var aborted = 0
        val store = MemoryModelStore()
        var maxAttempts = 4

        val engine by lazy { BrainEngine({ cfg -> backend(cfg) }, store, { now }, CooldownBook(), maxAttempts) }

        fun backend(cfg: ProviderConfig) = object : ChatBackend {
            override fun stream(model: String, req: ChatRequest, onChunk: (String) -> Unit) {
                calls.add("${cfg.info.id}/$model")
                (streams[cfg.info.id] ?: error("tak ada skrip untuk ${cfg.info.id}"))(model, onChunk)
            }

            override fun listModels(): List<String> {
                listCalls.add(cfg.info.id)
                return lists[cfg.info.id]?.invoke() ?: emptyList()
            }

            override fun abort() {
                aborted++
            }
        }

        fun ok(text: String): (String, (String) -> Unit) -> Unit = { _, emit -> emit(text) }
        fun fail(kind: FailKind, code: Int?, msg: String = "x", retry: Long? = null): (String, (String) -> Unit) -> Unit =
            { _, _ -> throw ProviderException(Failure(kind, code, msg, retry)) }
    }

    private val req = ChatRequest("sys", emptyList(), "halo")
    private val gemini = ProviderConfig(Providers.GEMINI, "gk")
    private val groq = ProviderConfig(Providers.GROQ, "qk")
    private val cerebras = ProviderConfig(Providers.CEREBRAS, "ck")
    private val openrouter = ProviderConfig(Providers.OPENROUTER, "ok")

    private fun World.run(vararg cfgs: ProviderConfig, token: CancelToken = CancelToken(), sink: StringBuilder = StringBuilder()): Outcome =
        engine.run(cfgs.toList(), req, { sink.append(it) }, token)

    @Test
    fun firstProviderWinsAndOthersAreNeverContacted() {
        val w = World()
        w.streams["gemini"] = w.ok("Halo!")
        w.streams["groq"] = w.ok("tidak boleh")
        val sink = StringBuilder()
        val out = w.run(gemini, groq, sink = sink)
        assertEquals(Outcome.Success("Halo!", "Gemini", "gemini-flash-latest"), out)
        assertEquals("Halo!", sink.toString())
        assertEquals(listOf("gemini/gemini-flash-latest"), w.calls)
        assertTrue(w.listCalls.isEmpty())
    }

    @Test
    fun rateLimitedGeminiFallsBackToFlashLiteThenGroq() {
        val w = World()
        w.streams["gemini"] = w.fail(FailKind.RATE_LIMIT, 429)
        w.lists["groq"] = { listOf("openai/gpt-oss-120b", "openai/gpt-oss-20b") }
        w.streams["groq"] = w.ok("dari groq")
        val out = w.run(gemini, groq)
        assertEquals(Outcome.Success("dari groq", "Groq", "openai/gpt-oss-120b"), out)
        assertEquals(listOf("gemini/gemini-flash-latest", "gemini/gemini-flash-lite-latest", "groq/openai/gpt-oss-120b"), w.calls)
    }

    @Test
    fun coolingModelsAreSkippedOnNextQuestion() {
        val w = World()
        w.streams["gemini"] = w.fail(FailKind.RATE_LIMIT, 429)
        w.lists["groq"] = { listOf("openai/gpt-oss-120b") }
        w.streams["groq"] = w.ok("a")
        w.run(gemini, groq)
        w.calls.clear()
        w.now += 10_000 // masih dalam jeda 60 dtk
        w.run(gemini, groq)
        assertEquals(listOf("groq/openai/gpt-oss-120b"), w.calls)
        w.calls.clear()
        w.now += 120_000 // jeda selesai: Gemini dicoba lagi
        w.run(gemini, groq)
        assertEquals("gemini/gemini-flash-latest", w.calls.first())
    }

    @Test
    fun retryAfterHeaderShortensCooldown() {
        val w = World()
        w.streams["gemini"] = w.fail(FailKind.RATE_LIMIT, 429, retry = 8_000)
        w.streams["groq"] = w.ok("a")
        w.lists["groq"] = { listOf("openai/gpt-oss-120b") }
        w.run(gemini, groq)
        w.calls.clear()
        w.now += 9_000
        w.run(gemini, groq)
        assertEquals("gemini/gemini-flash-latest", w.calls.first())
    }

    @Test
    fun badKeyIsNotRetriedUntilKeyChanges() {
        val w = World()
        w.streams["gemini"] = w.fail(FailKind.AUTH, 403, "bad key")
        w.streams["groq"] = w.ok("ok")
        w.lists["groq"] = { listOf("openai/gpt-oss-120b") }
        w.run(gemini, groq)
        assertEquals(listOf("gemini/gemini-flash-latest", "groq/openai/gpt-oss-120b"), w.calls) // tidak mencoba model ke-2 Gemini
        w.calls.clear()
        w.run(gemini, groq)
        assertEquals(listOf("groq/openai/gpt-oss-120b"), w.calls)
        // kunci diganti: dicoba lagi
        w.calls.clear()
        w.streams["gemini"] = w.ok("sekarang bisa")
        val out = w.run(ProviderConfig(Providers.GEMINI, "kunci-baru"), groq)
        assertTrue(out is Outcome.Success)
        assertEquals(listOf("gemini/gemini-flash-latest"), w.calls)
    }

    @Test
    fun resetCooldownsClearsEverything() {
        val w = World()
        w.streams["gemini"] = w.fail(FailKind.AUTH, 401)
        w.run(gemini)
        w.streams["gemini"] = w.ok("ok")
        w.calls.clear()
        assertTrue(w.run(gemini) is Outcome.Failed)
        assertTrue(w.calls.isEmpty())
        w.engine.resetCooldowns()
        assertTrue(w.run(gemini) is Outcome.Success)
    }

    @Test
    fun partialTextIsKeptAndNotRetried() {
        val w = World()
        w.streams["gemini"] = { _, emit ->
            emit("Halo, ")
            emit("apa ")
            throw IOException("Connection reset")
        }
        w.streams["groq"] = w.ok("tidak boleh")
        val sink = StringBuilder()
        val out = w.run(gemini, groq, sink = sink)
        assertTrue(out is Outcome.Partial)
        assertEquals("Halo, apa ", (out as Outcome.Partial).text)
        assertEquals("Halo, apa ", sink.toString())
        assertEquals(listOf("gemini/gemini-flash-latest"), w.calls)
    }

    @Test
    fun emptyReplyTriesNextModel() {
        val w = World()
        var n = 0
        w.streams["gemini"] = { _, emit -> if (n++ == 0) emit("   ") else emit("isi") }
        val out = w.run(gemini)
        assertEquals(Outcome.Success("isi", "Gemini", "gemini-flash-lite-latest"), out)
    }

    @Test
    fun blockedReplyMovesOnWithoutCooldown() {
        val w = World()
        w.streams["gemini"] = w.fail(FailKind.BLOCKED, null, "SAFETY")
        w.streams["groq"] = w.ok("ok")
        w.lists["groq"] = { listOf("openai/gpt-oss-120b") }
        assertTrue(w.run(gemini, groq) is Outcome.Success)
        w.calls.clear()
        w.run(gemini, groq)
        assertEquals("gemini/gemini-flash-latest", w.calls.first())
    }

    @Test
    fun allFailedSummarizesEachProviderAndRetryHint() {
        val w = World()
        w.streams["gemini"] = w.fail(FailKind.QUOTA_DAILY, 429)
        w.streams["groq"] = w.fail(FailKind.AUTH, 401)
        w.lists["groq"] = { listOf("openai/gpt-oss-120b") }
        val out = w.run(gemini, groq) as Outcome.Failed
        assertFalse(out.offline)
        val s = out.summary(AppLang.ID)
        assertTrue(s, s.contains("Gemini: kuota harian habis"))
        assertTrue(s, s.contains("Groq: kunci ditolak"))
        assertTrue(s, s.contains("Coba lagi sekitar 30 menit"))
        assertTrue(out.summary(AppLang.EN).contains("Groq: key rejected"))
    }

    @Test
    fun skippedCoolingProvidersStillAppearInSummary() {
        val w = World()
        w.streams["gemini"] = w.fail(FailKind.AUTH, 401)
        w.run(gemini)
        val out = w.run(gemini) as Outcome.Failed
        assertEquals(FailKind.AUTH, out.perProvider["Gemini"]!!.kind)
    }

    @Test
    fun offlineStopsAfterTwoNetworkFailures() {
        val w = World()
        w.streams["gemini"] = { _, _ -> throw UnknownHostException("x") }
        w.streams["groq"] = { _, _ -> throw UnknownHostException("x") }
        w.streams["cerebras"] = w.ok("tidak boleh dicoba")
        w.lists["groq"] = { throw UnknownHostException("x") }
        w.lists["cerebras"] = { listOf("gpt-oss-120b") }
        val out = w.run(gemini, groq, cerebras) as Outcome.Failed
        assertTrue(out.offline)
        assertEquals("tidak ada koneksi internet", out.summary(AppLang.ID))
        assertFalse(w.calls.any { it.startsWith("cerebras") })
        // Gemini: 1 percobaan (jaringan = istirahat seluruh penyedia), Groq: 1 percobaan
        assertEquals(listOf("gemini/gemini-flash-latest", "groq/openai/gpt-oss-120b"), w.calls)
    }

    @Test
    fun cancelBeforeStartAndDuringStream() {
        val w = World()
        w.streams["gemini"] = w.ok("x")
        val pre = CancelToken().apply { cancel() }
        assertEquals(Outcome.Cancelled, w.run(gemini, token = pre))
        assertTrue(w.calls.isEmpty())

        val token = CancelToken()
        w.streams["gemini"] = { _, emit ->
            emit("a")
            token.cancel()
            emit("b") // engine harus memotong di sini
            throw AssertionError("tidak boleh sampai sini")
        }
        val sink = StringBuilder()
        assertEquals(Outcome.Cancelled, w.run(gemini, token = token, sink = sink))
        assertEquals("a", sink.toString())
    }

    @Test
    fun cancelAbortsTheActiveBackendOnly() {
        val w = World()
        val token = CancelToken()
        token.cancel() // tanpa backend aktif: aman
        assertEquals(0, w.aborted)
        val t2 = CancelToken()
        var during = 0
        w.streams["gemini"] = { _, emit ->
            t2.cancel()
            during = w.aborted
            emit("x")
        }
        assertEquals(Outcome.Cancelled, w.run(gemini, token = t2))
        assertEquals(1, during)
    }

    @Test
    fun tokenCancelledRightAfterAttachStillAborts() {
        val w = World()
        val token = CancelToken()
        val b = w.backend(ProviderConfig(Providers.GEMINI, "k"))
        token.cancel()
        token.attach(b)
        assertEquals(1, w.aborted)
    }

    // ------------------------------------------------------------ penemuan model

    @Test
    fun discoveryPicksBestModelsAndCachesList() {
        val w = World()
        w.lists["groq"] = { listOf("whisper-large-v3", "llama-3.1-8b-instant", "openai/gpt-oss-120b", "moonshotai/kimi-k2-instruct-0905") }
        w.streams["groq"] = w.fail(FailKind.SERVER, 503)
        w.run(groq)
        assertEquals(listOf("groq/openai/gpt-oss-120b", "groq/moonshotai/kimi-k2-instruct-0905"), w.calls)
        assertEquals(1, w.listCalls.size)
        w.engine.resetCooldowns()
        w.run(groq)
        assertEquals(1, w.listCalls.size) // daftar tersimpan
        w.engine.resetCooldowns()
        w.now += BrainEngine.MODEL_TTL_MS + 1
        w.run(groq)
        assertEquals(2, w.listCalls.size) // kedaluwarsa: ambil ulang
    }

    @Test
    fun discoveryFailureFallsBackToDefaultsOrStaleList() {
        val w = World()
        w.lists["cerebras"] = { throw IOException("boom") }
        w.streams["cerebras"] = w.fail(FailKind.SERVER, 500)
        w.run(cerebras)
        assertEquals(listOf("cerebras/gpt-oss-120b", "cerebras/llama3.1-8b"), w.calls)

        // daftar lama masih dipakai walau penyegaran gagal
        val w2 = World()
        w2.store.save("groq", StoredModels(w2.now - BrainEngine.MODEL_TTL_MS - 5, listOf("kimi-k2-x")))
        w2.lists["groq"] = { throw IOException("boom") }
        w2.streams["groq"] = w2.ok("ok")
        w2.run(groq)
        assertEquals(listOf("groq/kimi-k2-x"), w2.calls)
    }

    @Test
    fun discoveryAuthErrorMarksProviderWithoutAttempt() {
        val w = World()
        w.lists["groq"] = { throw ProviderException(Failure(FailKind.AUTH, 401, "Invalid API Key")) }
        w.streams["groq"] = w.ok("tidak boleh")
        w.streams["cerebras"] = w.ok("dari cerebras")
        w.lists["cerebras"] = { listOf("gpt-oss-120b") }
        val out = w.run(groq, cerebras)
        assertEquals(Outcome.Success("dari cerebras", "Cerebras", "gpt-oss-120b"), out)
        assertTrue(w.calls.none { it.startsWith("groq") })
    }

    @Test
    fun modelNotFoundForgetsListAndTriesNextModel() {
        val w = World()
        w.lists["groq"] = { listOf("openai/gpt-oss-120b", "openai/gpt-oss-20b") }
        w.streams["groq"] = { model, emit ->
            if (model.endsWith("120b")) throw ProviderException(Failure(FailKind.NOT_FOUND, 404, "gone")) else emit("dari 20b")
        }
        val out = w.run(groq)
        assertEquals(Outcome.Success("dari 20b", "Groq", "openai/gpt-oss-20b"), out)
        assertNull(w.store.load("groq"))
        // pertanyaan berikut: daftar diambil ulang
        w.run(groq)
        assertEquals(2, w.listCalls.size)
    }

    @Test
    fun openRouterRateLimitSkipsRestOfProvider() {
        val w = World()
        w.lists["openrouter"] = { listOf("openai/gpt-oss-120b:free", "meta-llama/llama-3.3-70b-instruct:free", "openrouter/free") }
        w.streams["openrouter"] = w.fail(FailKind.RATE_LIMIT, 429)
        w.run(openrouter)
        assertEquals(listOf("openrouter/openai/gpt-oss-120b:free"), w.calls)
    }

    @Test
    fun maxAttemptsIsHonored() {
        val w = World()
        w.maxAttempts = 3
        w.streams["gemini"] = w.fail(FailKind.SERVER, 503)
        w.streams["groq"] = w.fail(FailKind.SERVER, 503)
        w.streams["cerebras"] = w.fail(FailKind.SERVER, 503)
        w.lists["groq"] = { listOf("openai/gpt-oss-120b", "openai/gpt-oss-20b") }
        w.lists["cerebras"] = { listOf("gpt-oss-120b") }
        w.run(gemini, groq, cerebras)
        assertEquals(3, w.calls.size)
    }

    @Test
    fun geminiModelOrderUsesUserModelFirst() {
        val w = World()
        w.streams["gemini"] = w.fail(FailKind.SERVER, 503)
        w.run(ProviderConfig(Providers.GEMINI, "k", userModel = "models/gemini-2.5-pro"))
        assertEquals(listOf("gemini/gemini-2.5-pro", "gemini/gemini-flash-latest"), w.calls)
    }

    @Test
    fun noProvidersMeansEmptyFailure() {
        val w = World()
        val out = w.run() as Outcome.Failed
        assertTrue(out.perProvider.isEmpty())
        assertFalse(out.offline)
    }

    // ------------------------------------------------------------ uji koneksi

    @Test
    fun probeReportsModelAndClearsCooldown() {
        val w = World()
        w.streams["gemini"] = w.fail(FailKind.AUTH, 401)
        w.run(gemini)
        w.streams["gemini"] = w.ok("ready")
        val p = w.engine.probe(gemini)
        assertTrue(p.ok)
        assertEquals("gemini-flash-latest", p.model)
        w.calls.clear()
        assertTrue(w.run(gemini) is Outcome.Success) // jeda dihapus setelah uji berhasil
    }

    @Test
    fun probeReportsFailure() {
        val w = World()
        w.lists["groq"] = { listOf("openai/gpt-oss-120b") }
        w.streams["groq"] = w.fail(FailKind.AUTH, 401, "Invalid API Key")
        val p = w.engine.probe(groq)
        assertFalse(p.ok)
        assertEquals(FailKind.AUTH, p.failure!!.kind)
        val w2 = World()
        w2.lists["groq"] = { throw ProviderException(Failure(FailKind.AUTH, 401, "Invalid API Key")) }
        val p2 = w2.engine.probe(groq)
        assertEquals(FailKind.AUTH, p2.failure!!.kind)
    }
}
