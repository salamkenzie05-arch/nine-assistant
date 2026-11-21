package id.nine.assistant.speech

import id.nine.assistant.llm.CancelledException
import id.nine.assistant.llm.CooldownBook
import id.nine.assistant.llm.FailKind
import id.nine.assistant.llm.MiniJson
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.Base64

/** Menguji klien Gemini TTS terhadap server lokal (tanpa internet dan tanpa kunci sungguhan). */
class GeminiTtsTest {

    private class Seen(val path: String, val headers: Map<String, String>, val body: String)

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

    /** Server HTTP/1.1 minimal: [handler] mengembalikan (status, badan JSON). */
    private fun serve(handler: (Seen) -> Pair<Int, String>): Pair<String, MutableList<Seen>> {
        val seen = ArrayList<Seen>()
        val ss = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
        servers.add(ss)
        Thread {
            while (!ss.isClosed) {
                val sock: Socket = try { ss.accept() } catch (e: Exception) { break }
                Thread {
                    try {
                        val inp = sock.getInputStream()
                        val reqLine = readLine(inp) ?: return@Thread
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
                        val sn = Seen(reqLine.split(" ").getOrElse(1) { "/" }, hs, String(bodyBytes, Charsets.UTF_8))
                        synchronized(seen) { seen.add(sn) }
                        val (status, body) = handler(sn)
                        val b = body.toByteArray()
                        val out = sock.getOutputStream()
                        out.write("HTTP/1.1 $status X\r\nContent-Type: application/json\r\nContent-Length: ${b.size}\r\nConnection: close\r\n\r\n".toByteArray())
                        out.write(b)
                        out.flush()
                    } catch (e: Exception) {
                        // klien memutus
                    } finally {
                        try { sock.close() } catch (e: Exception) { }
                    }
                }.start()
            }
        }.start()
        return "http://127.0.0.1:${ss.localPort}/v1beta" to seen
    }

    private fun wavBytes(samples: ShortArray, rate: Int = 24_000): ByteArray {
        val o = ByteArrayOutputStream()
        fun le16(v: Int) { o.write(v and 0xFF); o.write((v shr 8) and 0xFF) }
        fun le32(v: Int) { le16(v and 0xFFFF); le16((v shr 16) and 0xFFFF) }
        o.write("RIFF".toByteArray()); le32(36 + samples.size * 2); o.write("WAVE".toByteArray())
        o.write("fmt ".toByteArray()); le32(16); le16(1); le16(1); le32(rate); le32(rate * 2); le16(2); le16(16)
        o.write("data".toByteArray()); le32(samples.size * 2)
        for (s in samples) le16(s.toInt())
        return o.toByteArray()
    }

    private fun b64(b: ByteArray) = Base64.getEncoder().encodeToString(b)

    private fun interactionsOk(samples: ShortArray) =
        """{"id":"x","steps":[{"type":"user_input","content":[{"type":"text","text":"x"}]},{"type":"model_output","content":[{"type":"audio","mime_type":"audio/wav","data":"${b64(wavBytes(samples))}"}]}]}"""

    private fun generateOk(samples: ShortArray): String {
        val raw = ByteArrayOutputStream()
        for (s in samples) { raw.write(s.toInt() and 0xFF); raw.write((s.toInt() shr 8) and 0xFF) }
        return """{"candidates":[{"content":{"parts":[{"inlineData":{"mimeType":"audio/L16;codec=pcm;rate=24000","data":"${b64(raw.toByteArray())}"}}]}}]}"""
    }

    private fun tts(base: String, book: CooldownBook = CooldownBook(), now: () -> Long = { 1_000_000L }, key: String = "KEY") =
        GeminiTts(key, base, 2_000, 5_000, book, now)

    // ------------------------------------------------------------ bentuk permintaan

    @Test
    fun interactionsBodyFollowsTheDocumentedShape() {
        val v = MiniJson.parse(GeminiTtsWire.interactionsBody("gemini-3.8-flash-tts", "Halo, Tuan.", "Kore", "warm and cheerful"))
        assertEquals("gemini-3.8-flash-tts", MiniJson.str(v, "model"))
        assertEquals("user_input", MiniJson.str(v, "input", 0, "type"))
        assertEquals("Halo, Tuan.", MiniJson.str(v, "input", 0, "content", 0, "text"))
        assertEquals("speech_metadata", MiniJson.str(v, "input", 0, "content", 0, "annotations", 0, "type"))
        assertEquals("warm and cheerful", MiniJson.str(v, "input", 0, "content", 0, "annotations", 0, "style"))
        assertEquals("audio", MiniJson.str(v, "response_format", "type"))
        assertEquals("Kore", MiniJson.str(v, "generation_config", "speech_config", 0, "voice"))
        assertNull(MiniJson.get(MiniJson.parse(GeminiTtsWire.interactionsBody("m", "x", "Kore", null)), "input", 0, "content", 0, "annotations"))
    }

    @Test
    fun generateBodyAsksForAudioWithTheVoice() {
        val v = MiniJson.parse(GeminiTtsWire.generateBody("Halo", "Charon"))
        assertEquals("Halo", MiniJson.str(v, "contents", 0, "parts", 0, "text"))
        assertEquals("AUDIO", MiniJson.str(v, "generationConfig", "responseModalities", 0))
        assertEquals("Charon", MiniJson.str(v, "generationConfig", "speechConfig", "voiceConfig", "prebuiltVoiceConfig", "voiceName"))
    }

    // ------------------------------------------------------------ sintesis

    @Test
    fun synthesizesWithTheNewestModelAndSendsKeyVoiceAndStyle() {
        val (base, seen) = serve { 200 to interactionsOk(shortArrayOf(1, 2, 3, 4)) }
        val r = tts(base).synthesize("Halo Tuan.", VoiceGender.FEMALE, "calm")
        assertEquals(listOf<Short>(1, 2, 3, 4), r.pcm!!.samples.toList())
        assertEquals(24_000, r.pcm!!.rate)
        assertEquals("gemini-3.8-flash-tts", r.model)
        val s = seen.single()
        assertEquals("/v1beta/interactions", s.path)
        assertEquals("KEY", s.headers["x-goog-api-key"])
        assertEquals("Kore", MiniJson.str(MiniJson.parse(s.body), "generation_config", "speech_config", 0, "voice"))
    }

    @Test
    fun maleUsesTheMaleVoice() {
        val (base, seen) = serve { 200 to interactionsOk(shortArrayOf(1, 2)) }
        tts(base).synthesize("Halo", VoiceGender.MALE, null)
        assertEquals("Charon", MiniJson.str(MiniJson.parse(seen.single().body), "generation_config", "speech_config", 0, "voice"))
    }

    @Test
    fun fallsBackToTheOlderModelAndRemembersTheBrokenOne() {
        val (base, seen) = serve { s ->
            if (s.path.endsWith("/interactions")) 404 to """{"error":{"code":404,"message":"models/gemini-3.8-flash-tts is not found"}}"""
            else 200 to generateOk(shortArrayOf(9, 8, 7))
        }
        val book = CooldownBook()
        val t = tts(base, book)
        val r = t.synthesize("Halo", VoiceGender.FEMALE, null)
        assertEquals(listOf<Short>(9, 8, 7), r.pcm!!.samples.toList())
        assertEquals("gemini-3.1-flash-tts-preview", r.model)
        assertEquals(listOf("gemini-3.8-flash-tts"), r.failures.map { it.first })
        assertEquals(FailKind.NOT_FOUND, r.failures[0].second.kind)
        assertEquals(2, seen.size)
        assertEquals("/v1beta/models/gemini-3.1-flash-tts-preview:generateContent", seen[1].path)
        // permintaan berikutnya langsung ke model yang sehat
        tts(base, book).synthesize("Halo lagi", VoiceGender.FEMALE, null)
        assertEquals(3, seen.size)
    }

    @Test
    fun retriesWithoutStyleWhenTheStyleIsRejected() {
        val (base, seen) = serve { s ->
            if (s.body.contains("speech_metadata")) 400 to """{"error":{"code":400,"message":"Unknown name \"annotations\""}}"""
            else 200 to interactionsOk(shortArrayOf(5, 5))
        }
        val r = tts(base).synthesize("Halo", VoiceGender.FEMALE, "playful")
        assertNotNull(r.pcm)
        assertEquals(2, seen.size)
        assertFalse(seen[1].body.contains("annotations"))
    }

    @Test
    fun badKeyStopsEverythingAndRestsTheKey() {
        val (base, seen) = serve { 403 to """{"error":{"code":403,"message":"API key not valid"}}""" }
        val book = CooldownBook()
        val t = tts(base, book)
        val r = t.synthesize("Halo", VoiceGender.FEMALE, null)
        assertNull(r.pcm)
        assertEquals(FailKind.AUTH, r.failures.single().second.kind)
        assertEquals(1, seen.size) // kunci salah: model kedua tidak dicoba
        assertFalse(t.available())
        assertEquals(FailKind.AUTH, t.coolingFailure()!!.kind)
        // kunci lain tidak ikut istirahat
        assertTrue(tts(base, book, key = "OTHER").available())
    }

    @Test
    fun rateLimitedModelRestsForTheRetryDelayThenRecovers() {
        var now = 1_000_000L
        var limited = true
        val (base, seen) = serve {
            if (limited) 429 to """{"error":{"code":429,"message":"quota","details":[{"@type":"type.googleapis.com/google.rpc.RetryInfo","retryDelay":"30s"}]}}"""
            else 200 to interactionsOk(shortArrayOf(1, 2))
        }
        val book = CooldownBook()
        val r1 = tts(base, book, { now }).synthesize("Halo", VoiceGender.FEMALE, null)
        assertNull(r1.pcm)
        assertEquals(2, seen.size) // kedua model dicoba, keduanya dibatasi
        assertFalse(tts(base, book, { now }).available())
        val r2 = tts(base, book, { now }).synthesize("Halo", VoiceGender.FEMALE, null)
        assertNull(r2.pcm)
        assertEquals(2, seen.size) // sedang istirahat: tidak ada permintaan baru
        now += 31_000L
        limited = false
        assertNotNull(tts(base, book, { now }).synthesize("Halo", VoiceGender.FEMALE, null).pcm)
    }

    @Test
    fun answerWithoutAudioIsEmptyFailure() {
        val (base, _) = serve { 200 to """{"steps":[{"type":"model_output","content":[{"type":"text","text":"maaf"}]}]}""" }
        val r = tts(base).synthesize("Halo", VoiceGender.FEMALE, null, listOf(GeminiTts.MODELS[0]))
        assertNull(r.pcm)
        assertEquals(FailKind.EMPTY, r.failures.single().second.kind)
    }

    @Test
    fun errorBodyInsideA200IsReported() {
        val (base, _) = serve { 200 to """{"error":{"code":429,"message":"You exceeded your current quota"}}""" }
        val r = tts(base).synthesize("Halo", VoiceGender.FEMALE, null, listOf(GeminiTts.MODELS[0]))
        assertNull(r.pcm)
        assertTrue(r.failures.single().second.kind in setOf(FailKind.RATE_LIMIT, FailKind.QUOTA_DAILY))
    }

    @Test
    fun unreachableServerIsNetworkFailureNotACrash() {
        val r = tts("http://127.0.0.1:1/v1beta").synthesize("Halo", VoiceGender.FEMALE, null)
        assertNull(r.pcm)
        assertTrue(r.failures.isNotEmpty())
    }

    @Test
    fun blankTextMakesNoRequest() {
        val (base, seen) = serve { 200 to interactionsOk(shortArrayOf(1)) }
        val r = tts(base).synthesize("   ", VoiceGender.FEMALE, null)
        assertNull(r.pcm)
        assertTrue(seen.isEmpty())
    }

    @Test
    fun abortedBeforeTheCallThrowsCancelled() {
        val (base, seen) = serve { 200 to interactionsOk(shortArrayOf(1)) }
        val t = tts(base)
        t.abort()
        try {
            t.synthesize("Halo", VoiceGender.FEMALE, null)
            fail()
        } catch (e: CancelledException) {
            assertTrue(seen.isEmpty())
        }
    }

    @Test
    fun longTextIsTruncatedBeforeSending() {
        val (base, seen) = serve { 200 to interactionsOk(shortArrayOf(1)) }
        tts(base).synthesize("a".repeat(2000), VoiceGender.FEMALE, null)
        val sent = MiniJson.str(MiniJson.parse(seen.single().body), "input", 0, "content", 0, "text")!!
        assertEquals(GeminiTts.MAX_CHARS, sent.length)
    }
}
