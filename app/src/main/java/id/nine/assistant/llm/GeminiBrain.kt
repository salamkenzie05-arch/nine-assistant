package id.nine.assistant.llm

import android.content.Context
import android.os.Handler
import android.os.Looper
import id.nine.assistant.core.AppLang
import id.nine.assistant.core.Prefs
import java.time.LocalDateTime
import java.util.concurrent.Executors

/**
 * Otak online Nine: Gemini (Google AI Studio, tingkat gratis). Pengguna mengisi kunci gratisnya sendiri. Bila satu model
 * kena batas kuota, Nine pindah ke model Gemini cadangan (kuota gratis dihitung per model); kalau semuanya gagal, Gemma
 * offline mengambil alih. Pemakaian kuota dilihat lewat dasbor Google (tombol di Pengaturan), bukan dicatat di sini.
 */
class GeminiBrain(context: Context, private val prefs: Prefs) : OnlineBrain {

    private val app = context.applicationContext
    private val main = Handler(Looper.getMainLooper())

    // Pool biasa (bukan satu thread): koneksi lama yang macet tidak boleh menahan pertanyaan berikutnya.
    private val exec = Executors.newCachedThreadPool { r -> Thread(r, "nine-ai").apply { isDaemon = true } }

    private val engine = BrainEngine(backendFor = { cfg -> GeminiBackend(cfg.key, cfg.search) })

    @Volatile
    private var generation = 0

    @Volatile
    private var token: CancelToken? = null

    /** Gemini bila kuncinya terisi, kalau tidak kosong. */
    fun configs(): List<ProviderConfig> {
        val key = prefs.geminiKey
        if (key.isBlank()) return emptyList()
        return listOf(ProviderConfig(Providers.GEMINI, key, Providers.geminiModels(Providers.DEFAULT_GEMINI_MODEL), false))
    }

    override val displayName: String get() = Providers.GEMINI.label

    override fun isConfigured(): Boolean = prefs.internetMode && configs().isNotEmpty()

    override fun isOnline(): Boolean = OnlineBrain.networkOk(app)

    /** Riwayat diambil dari percakapan di layar, jadi tidak ada yang perlu direset di sini. */
    override fun reset() {}

    override fun cancel() {
        generation++
        token?.cancel()
    }

    override fun ask(text: String, lang: AppLang, history: List<Pair<String, String>>, context: String, cb: OnlineBrain.Callback) {
        val gen = ++generation
        token?.cancel()
        val tk = CancelToken()
        token = tk
        val cfgs = configs()
        val search = cfgs.any { it.search }
        val system = prefs.persona.onlinePrompt(lang, LocalDateTime.now(), "", if (search) "Google Search" else null, prefs.autoMemory) +
            (if (context.isBlank()) "" else "\n\n" + context)
        val req = ChatRequest(system, history, text)
        exec.execute {
            val outcome: Outcome = try {
                engine.run(cfgs, req, { chunk ->
                    if (gen != generation) throw CancelledException()
                    main.post { if (gen == generation) cb.onChunk(chunk) }
                }, tk)
            } catch (e: CancelledException) {
                Outcome.Cancelled
            } catch (e: Exception) {
                post(gen) { cb.onFallback(e.message ?: e.javaClass.simpleName) }
                return@execute
            }
            if (gen != generation) return@execute
            when (outcome) {
                is Outcome.Success -> post(gen) { cb.onDone(outcome.text) }
                is Outcome.Partial -> post(gen) { cb.onDone(outcome.text) }
                is Outcome.Failed -> post(gen) { cb.onFallback(outcome.summary(lang)) }
                Outcome.Cancelled -> {}
            }
        }
    }

    private fun post(gen: Int, block: () -> Unit) {
        main.post { if (gen == generation) block() }
    }

    /**
     * Uji koneksi Gemini. [onLine] dipanggil (di thread utama) dengan hasilnya, lalu [onDone] sekali. Menghapus catatan
     * "istirahat" bila ujinya berhasil. Uji ini juga satu permintaan sungguhan, jadi ikut tercatat di pemakaian.
     */
    fun test(lang: AppLang, onLine: (String) -> Unit, onDone: () -> Unit) {
        val cfgs = configs()
        exec.execute {
            for (cfg in cfgs) {
                val r = try {
                    engine.probe(cfg)
                } catch (e: Exception) {
                    ProbeResult(false, null, 0, Failure(FailKind.OTHER, null, e.message ?: "error"))
                }
                val secs = String.format(java.util.Locale.US, "%.1f", r.millis / 1000.0)
                val head = if (r.ok) {
                    "${cfg.info.label}: " + lang.t("berhasil", "OK") + " · ${r.model} · $secs " + lang.t("dtk", "s")
                } else {
                    "${cfg.info.label}: " + lang.t("gagal", "failed") + " · " + (r.failure?.let { Failures.describe(it, lang) } ?: "?")
                }
                // Model yang gagal lebih dulu ditampilkan juga, supaya jelas kenapa uji terasa lambat.
                val line = head + (if (r.ok) r.skipped else r.skipped.dropLast(1)).joinToString("") { (m, f) -> "\n· $m: " + Failures.describe(f, lang) }
                main.post { onLine(line) }
            }
            main.post { onDone() }
        }
    }
}
