package id.nine.assistant.speech

import id.nine.assistant.llm.CancelledException
import id.nine.assistant.llm.CooldownBook
import id.nine.assistant.llm.FailKind
import id.nine.assistant.llm.Failure
import id.nine.assistant.llm.Failures
import id.nine.assistant.llm.HttpCall
import id.nine.assistant.llm.MiniJson
import id.nine.assistant.llm.ProviderException
import id.nine.assistant.llm.Providers
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.Base64

/** Dua bentuk API suara Gemini: "Interactions" (model 3.8 ke atas) dan generateContent (model TTS yang lebih lama). */
enum class TtsApi { INTERACTIONS, GENERATE }

class TtsModel(val id: String, val api: TtsApi)

/** Audio mentah dari server (WAV atau PCM mentah) beserta tipe MIME-nya bila disebut. */
class TtsAudio(val data: ByteArray, val mime: String?)

/** Hasil satu kali sintesis: [pcm] null berarti semua model gagal (alasannya di [failures], model → kegagalan). */
class TtsResult(val pcm: Pcm?, val model: String?, val failures: List<Pair<String, Failure>>)

/** Bentuk permintaan dan balasan Gemini TTS. Tanpa Android supaya bisa diuji di JVM. */
object GeminiTtsWire {

    /** Isi permintaan API Interactions (bentuk dari dokumentasi Google). [style]: gaya bicara bebas, mis. "warm and cheerful". */
    fun interactionsBody(model: String, text: String, voice: String, style: String?): String {
        val textItem = LinkedHashMap<String, Any?>()
        textItem["type"] = "text"
        textItem["text"] = text
        if (!style.isNullOrBlank()) textItem["annotations"] = listOf(mapOf("type" to "speech_metadata", "style" to style))
        return MiniJson.write(
            mapOf(
                "model" to model,
                "input" to listOf(mapOf("type" to "user_input", "content" to listOf(textItem))),
                "response_format" to mapOf("type" to "audio"),
                "generation_config" to mapOf("speech_config" to listOf(mapOf("voice" to voice)))
            )
        )
    }

    /** Isi permintaan generateContent untuk model TTS lama. */
    fun generateBody(text: String, voice: String): String = MiniJson.write(
        mapOf(
            "contents" to listOf(mapOf("parts" to listOf(mapOf("text" to text)))),
            "generationConfig" to mapOf(
                "responseModalities" to listOf("AUDIO"),
                "speechConfig" to mapOf("voiceConfig" to mapOf("prebuiltVoiceConfig" to mapOf("voiceName" to voice)))
            )
        )
    )

    private fun decode(b64: String?, mime: String?): TtsAudio? {
        if (b64.isNullOrEmpty()) return null
        val bytes = try {
            Base64.getMimeDecoder().decode(b64)
        } catch (e: IllegalArgumentException) {
            return null
        }
        return if (bytes.isEmpty()) null else TtsAudio(bytes, mime)
    }

    /** Audio terakhir di "steps[].content[]" bertipe audio (seperti filter jq di dokumentasi Google). */
    fun parseInteractions(json: String): TtsAudio? {
        val root = MiniJson.parseOrNull(json) ?: return null
        val steps = MiniJson.arr(root, "steps") ?: return null
        var found: TtsAudio? = null
        for (st in steps) {
            if (MiniJson.str(st, "type") != "model_output") continue
            for (c in MiniJson.arr(st, "content") ?: continue) {
                if (MiniJson.str(c, "type") != "audio") continue
                val mime = MiniJson.str(c, "mime_type") ?: MiniJson.str(c, "mimeType")
                decode(MiniJson.str(c, "data"), mime)?.let { found = it }
            }
        }
        return found
    }

    /** "candidates[0].content.parts[].inlineData" milik generateContent. */
    fun parseGenerate(json: String): TtsAudio? {
        val root = MiniJson.parseOrNull(json) ?: return null
        val parts = MiniJson.arr(root, "candidates", 0, "content", "parts") ?: return null
        for (p in parts) {
            val inline = MiniJson.get(p, "inlineData") ?: MiniJson.get(p, "inline_data") ?: continue
            val mime = MiniJson.str(inline, "mimeType") ?: MiniJson.str(inline, "mime_type")
            decode(MiniJson.str(inline, "data"), mime)?.let { return it }
        }
        return null
    }

    /** Galat dalam badan JSON 200 (jarang) atau pesan penolakan; null bila tak ada. */
    fun errorOf(json: String): Failure? {
        val root = MiniJson.parseOrNull(json) ?: return null
        val err = MiniJson.get(root, "error") ?: return null
        val msg = MiniJson.str(err, "message") ?: "error"
        return Failures.fromStream(msg, MiniJson.num(err, "code")?.toInt())
    }
}

/**
 * Suara Gemini (Google AI Studio, kunci pengguna sendiri): mengubah teks jadi audio PCM. Mencoba model-model berurutan;
 * model yang gagal diistirahatkan sementara ([book], dipakai bersama seluruh proses) supaya jawaban berikutnya tidak
 * menunggu model yang sedang bermasalah. Satu objek untuk satu permintaan: [abort] memutus koneksi yang berjalan.
 */
class GeminiTts(
    private val key: String,
    private val baseUrl: String = Providers.GEMINI.baseUrl,
    connectMs: Int = 6_000,
    readMs: Int = 20_000,
    private val book: CooldownBook = SHARED_BOOK,
    private val clock: () -> Long = { System.currentTimeMillis() }
) {
    private val http = HttpCall(connectMs, readMs)

    fun abort() = http.abort()

    private fun fp() = Integer.toHexString(key.hashCode())

    private fun keyKey() = "tts:${fp()}"

    private fun modelKey(m: TtsModel) = "tts:${fp()}/${m.id}"

    /** True bila ada model yang boleh dicoba sekarang (tidak sedang istirahat). */
    fun available(models: List<TtsModel> = MODELS): Boolean {
        val now = clock()
        if (book.active(keyKey(), now) != null) return false
        return models.any { book.active(modelKey(it), now) == null }
    }

    /** Alasan terakhir tidak tersedia (untuk ditampilkan), atau null. */
    fun coolingFailure(models: List<TtsModel> = MODELS): Failure? {
        val now = clock()
        book.active(keyKey(), now)?.let { return it.failure }
        return models.mapNotNull { book.active(modelKey(it), now)?.failure }.lastOrNull()
    }

    fun synthesize(text: String, gender: VoiceGender, style: String?, models: List<TtsModel> = MODELS): TtsResult {
        val clean = text.trim().take(MAX_CHARS)
        val failures = ArrayList<Pair<String, Failure>>()
        if (clean.isEmpty()) return TtsResult(null, null, failures)
        for (m in models) {
            val now = clock()
            if (book.active(keyKey(), now) != null) break
            if (book.active(modelKey(m), now) != null) continue
            try {
                val pcm = callWithStyleFallback(m, clean, gender.geminiVoice, style)
                book.forget(modelKey(m))
                return TtsResult(pcm, m.id, failures)
            } catch (e: CancelledException) {
                throw e
            } catch (e: Exception) {
                val f = Failures.fromException(e)
                failures.add(m.id to f)
                val ms = Failures.cooldownMs(f)
                if (ms > 0) {
                    if (Failures.providerWide(f)) book.mark(keyKey(), clock() + ms, f) else book.mark(modelKey(m), clock() + ms, f)
                }
                if (Failures.providerWide(f)) break
            }
        }
        return TtsResult(null, null, failures)
    }

    private fun callWithStyleFallback(m: TtsModel, text: String, voice: String, style: String?): Pcm {
        if (m.api == TtsApi.INTERACTIONS && !style.isNullOrBlank()) {
            try {
                return call(m, text, voice, style)
            } catch (e: ProviderException) {
                // Gaya bicara ditolak: ulangi tanpa gaya (tetap suara yang sama).
                if (e.failure.code != 400 || e.failure.kind == FailKind.AUTH) throw e
            }
        }
        return call(m, text, voice, null)
    }

    private fun call(m: TtsModel, text: String, voice: String, style: String?): Pcm {
        val base = baseUrl.trimEnd('/')
        val url = if (m.api == TtsApi.INTERACTIONS) "$base/interactions" else "$base/models/${m.id}:generateContent"
        val body = if (m.api == TtsApi.INTERACTIONS) GeminiTtsWire.interactionsBody(m.id, text, voice, style) else GeminiTtsWire.generateBody(text, voice)
        val headers = mapOf("x-goog-api-key" to key, "Content-Type" to "application/json; charset=utf-8", "Accept-Encoding" to "identity")
        val json = http.run("POST", url, headers, body) { c -> readCapped(c.inputStream) }
        val audio = (if (m.api == TtsApi.INTERACTIONS) GeminiTtsWire.parseInteractions(json) else GeminiTtsWire.parseGenerate(json))
            ?: throw ProviderException(GeminiTtsWire.errorOf(json) ?: Failure(FailKind.EMPTY, null, "no audio"))
        val pcm = WavPcm.decode(audio.data, WavPcm.rateFromMime(audio.mime) ?: WavPcm.DEFAULT_RATE)
            ?: throw ProviderException(Failure(FailKind.EMPTY, null, "unreadable audio"))
        return pcm
    }

    private fun readCapped(inp: java.io.InputStream): String {
        val out = ByteArrayOutputStream()
        val buf = ByteArray(16 * 1024)
        inp.use {
            while (true) {
                val n = it.read(buf)
                if (n < 0) break
                if (out.size() + n > MAX_BYTES) throw IOException("balasan terlalu besar")
                out.write(buf, 0, n)
            }
        }
        return out.toString("UTF-8")
    }

    companion object {
        /** Model dicoba berurutan: yang terbaru (API Interactions), lalu model TTS pratinjau yang lebih lama. */
        val MODELS = listOf(
            TtsModel("gemini-3.8-flash-tts", TtsApi.INTERACTIONS),
            TtsModel("gemini-3.1-flash-tts-preview", TtsApi.GENERATE)
        )

        val SHARED_BOOK = CooldownBook()

        /** Teks yang dikirim dibatasi: satu balasan panjang tidak perlu dibacakan seluruhnya (sisanya ada di layar). */
        const val MAX_CHARS = 600

        /** Batas ukuran balasan (audio base64 ±40 dtk ≈ 2,5 MB). */
        const val MAX_BYTES = 12 * 1024 * 1024
    }
}
