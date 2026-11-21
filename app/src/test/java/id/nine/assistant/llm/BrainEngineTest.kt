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
        val streams = HashMap<String, (String, (String) -> Unit) -> Unit>()
        var now = 10_000_000L
        var aborted = 0
        var maxAttempts = 4

        val engine by lazy {
            BrainEngine({ cfg -> backend(cfg) }, { now }, CooldownBook(), maxAttempts)
        }

        fun backend(cfg: ProviderConfig) = object : ChatBackend {
            override fun stream(model: String, req: ChatRequest, onChunk: (String) -> Unit) {
                calls.add("${cfg.info.id}/$model")
                (streams[cfg.info.id] ?: error("tak ada skrip untuk ${cfg.info.id}"))(model, onChunk)
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

    // Nine hanya memakai Gemini, tetapi mesinnya umum: penyedia kedua di sini hanya untuk menguji perpindahan.
    private val alt = ProviderInfo("alt", "Alt", "http://alt.test", "http://alt.test/key")
    private val alt2 = ProviderInfo("alt2", "Alt2", "http://alt2.test", "http://alt2.test/key")
    private val gemini = ProviderConfig(Providers.GEMINI, "gk", Providers.geminiModels(""))
    private val altCfg = ProviderConfig(alt, "ak", listOf("a1", "a2"))
    private val alt2Cfg = ProviderConfig(alt2, "bk", listOf("b1"))

    private fun World.run(vararg cfgs: ProviderConfig, token: CancelToken = CancelToken(), sink: StringBuilder = StringBuilder()): Outcome =
        engine.run(cfgs.toList(), req, { sink.append(it) }, token)

    @Test
    fun firstProviderWinsAndOthersAreNeverContacted() {
        val w = World()
        w.streams["gemini"] = w.ok("Halo!")
        w.streams["alt"] = w.ok("tidak boleh")
        val sink = StringBuilder()
        val out = w.run(gemini, altCfg, sink = sink)
        assertEquals(Outcome.Success("Halo!", "Gemini", "gemini-flash-latest"), out)
        assertEquals("Halo!", sink.toString())
        assertEquals(listOf("gemini/gemini-flash-latest"), w.calls)
    }

    @Test
    fun rateLimitedFlashFallsBackToFlashLiteThenNextProvider() {
        val w = World()
        w.streams["gemini"] = w.fail(FailKind.RATE_LIMIT, 429)
        w.streams["alt"] = w.ok("dari alt")
        val out = w.run(gemini, altCfg)
        assertEquals(Outcome.Success("dari alt", "Alt", "a1"), out)
        assertEquals(listOf("gemini/gemini-flash-latest", "gemini/gemini-flash-lite-latest", "alt/a1"), w.calls)
    }

    @Test
    fun geminiQuotaIsPerModelSoLiteStillAnswersWhenFlashIsExhausted() {
        val w = World()
        w.streams["gemini"] = { model, emit ->
            if (model == "gemini-flash-latest") throw ProviderException(Failure(FailKind.QUOTA_DAILY, 429, "habis")) else emit("dari lite")
        }
        val out = w.run(gemini)
        assertEquals(Outcome.Success("dari lite", "Gemini", "gemini-flash-lite-latest"), out)
        // pertanyaan berikut langsung ke lite, flash masih istirahat
        w.calls.clear()
        w.now += 60_000
        w.run(gemini)
        assertEquals(listOf("gemini/gemini-flash-lite-latest"), w.calls)
        // 30 menit berlalu: flash dicoba lagi
        w.calls.clear()
        w.now += 31 * 60_000
        w.run(gemini)
        assertEquals("gemini/gemini-flash-latest", w.calls.first())
    }

    @Test
    fun coolingModelsAreSkippedOnNextQuestion() {
        val w = World()
        w.streams["gemini"] = w.fail(FailKind.RATE_LIMIT, 429)
        w.streams["alt"] = w.ok("a")
        w.run(gemini, altCfg)
        w.calls.clear()
        w.now += 10_000 // masih dalam jeda 60 dtk
        w.run(gemini, altCfg)
        assertEquals(listOf("alt/a1"), w.calls)
        w.calls.clear()
        w.now += 120_000 // jeda selesai: Gemini dicoba lagi
        w.run(gemini, altCfg)
        assertEquals("gemini/gemini-flash-latest", w.calls.first())
    }

    @Test
    fun retryAfterHeaderShortensCooldown() {
        val w = World()
        w.streams["gemini"] = w.fail(FailKind.RATE_LIMIT, 429, retry = 8_000)
        w.streams["alt"] = w.ok("a")
        w.run(gemini, altCfg)
        w.calls.clear()
        w.now += 9_000
        w.run(gemini, altCfg)
        assertEquals("gemini/gemini-flash-latest", w.calls.first())
    }

    @Test
    fun badKeyIsNotRetriedUntilKeyChanges() {
        val w = World()
        w.streams["gemini"] = w.fail(FailKind.AUTH, 403, "bad key")
        w.streams["alt"] = w.ok("ok")
        w.run(gemini, altCfg)
        assertEquals(listOf("gemini/gemini-flash-latest", "alt/a1"), w.calls) // tidak mencoba model ke-2 Gemini
        w.calls.clear()
        w.run(gemini, altCfg)
        assertEquals(listOf("alt/a1"), w.calls)
        // kunci diganti: dicoba lagi
        w.calls.clear()
        w.streams["gemini"] = w.ok("sekarang bisa")
        val out = w.run(ProviderConfig(Providers.GEMINI, "kunci-baru", Providers.geminiModels("")), altCfg)
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
        w.streams["alt"] = w.ok("tidak boleh")
        val sink = StringBuilder()
        val out = w.run(gemini, altCfg, sink = sink)
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
        w.streams["alt"] = w.ok("ok")
        assertTrue(w.run(gemini, altCfg) is Outcome.Success)
        w.calls.clear()
        w.run(gemini, altCfg)
        assertEquals("gemini/gemini-flash-latest", w.calls.first())
    }

    @Test
    fun allFailedSummarizesEachProviderAndRetryHint() {
        val w = World()
        w.streams["gemini"] = w.fail(FailKind.QUOTA_DAILY, 429)
        w.streams["alt"] = w.fail(FailKind.AUTH, 401)
        val out = w.run(gemini, altCfg) as Outcome.Failed
        assertFalse(out.offline)
        val s = out.summary(AppLang.ID)
        assertTrue(s, s.contains("Gemini: kuota harian habis"))
        assertTrue(s, s.contains("Alt: kunci ditolak"))
        assertTrue(s, s.contains("Coba lagi sekitar 30 menit"))
        assertTrue(out.summary(AppLang.EN).contains("Alt: key rejected"))
    }

    @Test
    fun geminiOnlySummaryMentionsQuota() {
        val w = World()
        w.streams["gemini"] = w.fail(FailKind.QUOTA_DAILY, 429)
        val out = w.run(gemini) as Outcome.Failed
        assertEquals("Gemini: kuota harian habis. Coba lagi sekitar 30 menit.", out.summary(AppLang.ID))
        assertEquals(listOf("gemini/gemini-flash-latest", "gemini/gemini-flash-lite-latest"), w.calls)
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
        w.streams["alt"] = { _, _ -> throw UnknownHostException("x") }
        w.streams["alt2"] = w.ok("tidak boleh dicoba")
        val out = w.run(gemini, altCfg, alt2Cfg) as Outcome.Failed
        assertTrue(out.offline)
        assertEquals("tidak ada koneksi internet", out.summary(AppLang.ID))
        assertFalse(w.calls.any { it.startsWith("alt2") })
        // jaringan putus = istirahat seluruh penyedia, jadi satu percobaan per penyedia
        assertEquals(listOf("gemini/gemini-flash-latest", "alt/a1"), w.calls)
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
        val b = w.backend(gemini)
        token.cancel()
        token.attach(b)
        assertEquals(1, w.aborted)
    }

    @Test
    fun maxAttemptsIsHonored() {
        val w = World()
        w.maxAttempts = 3
        w.streams["gemini"] = w.fail(FailKind.SERVER, 503)
        w.streams["alt"] = w.fail(FailKind.SERVER, 503)
        w.streams["alt2"] = w.fail(FailKind.SERVER, 503)
        w.run(gemini, altCfg, alt2Cfg)
        assertEquals(3, w.calls.size)
    }

    @Test
    fun noProvidersMeansEmptyFailure() {
        val w = World()
        val out = w.run() as Outcome.Failed
        assertTrue(out.perProvider.isEmpty())
        assertFalse(out.offline)
    }

    // ------------------------------------------------------------ model Gemini

    @Test
    fun geminiModelOrderUsesUserModelFirst() {
        assertEquals(listOf("gemini-flash-latest", "gemini-flash-lite-latest"), Providers.geminiModels(""))
        assertEquals(listOf("gemini-flash-latest", "gemini-flash-lite-latest"), Providers.geminiModels("   "))
        assertEquals(listOf("gemini-2.5-pro", "gemini-flash-latest"), Providers.geminiModels("models/gemini-2.5-pro"))
        assertEquals(listOf("gemini-flash-lite-latest", "gemini-flash-latest"), Providers.geminiModels("gemini-flash-lite-latest"))
        assertEquals(listOf("gemini-flash-latest", "gemini-flash-lite-latest"), Providers.geminiModels("gemini-flash-latest"))

        val w = World()
        w.streams["gemini"] = w.fail(FailKind.SERVER, 503)
        w.run(ProviderConfig(Providers.GEMINI, "k", Providers.geminiModels("models/gemini-2.5-pro")))
        assertEquals(listOf("gemini/gemini-2.5-pro", "gemini/gemini-flash-latest"), w.calls)
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
    fun probeReportsFailureAndStopsOnBadKey() {
        val w = World()
        w.streams["gemini"] = w.fail(FailKind.AUTH, 401, "API key not valid")
        val p = w.engine.probe(gemini)
        assertFalse(p.ok)
        assertEquals(FailKind.AUTH, p.failure!!.kind)
        assertEquals(listOf("gemini/gemini-flash-latest"), w.calls) // kunci salah: model kedua tak perlu dicoba
    }

    @Test
    fun probeTriesSecondModelWhenFirstIsRateLimited() {
        val w = World()
        w.streams["gemini"] = { model, emit ->
            if (model == "gemini-flash-latest") throw ProviderException(Failure(FailKind.RATE_LIMIT, 429, "x")) else emit("ready")
        }
        val p = w.engine.probe(gemini)
        assertTrue(p.ok)
        assertEquals("gemini-flash-lite-latest", p.model)
    }

    @Test
    fun probeWithoutModelsFailsCleanly() {
        val w = World()
        val p = w.engine.probe(ProviderConfig(alt, "k", emptyList()))
        assertFalse(p.ok)
        assertEquals(FailKind.NOT_FOUND, p.failure!!.kind)
    }
}
