package id.nine.assistant.llm

import android.content.Context
import android.os.Handler
import android.os.Looper
import id.nine.assistant.core.AppLang
import id.nine.assistant.core.Prefs
import java.time.LocalDateTime
import java.util.concurrent.Executors

/** Daftar model hasil penemuan disimpan di pengaturan (bukan rahasia, jadi tanpa enkripsi). */
private class PrefsModelStore(private val prefs: Prefs) : ModelStore {
    override fun load(providerId: String): StoredModels? {
        val v = MiniJson.parseOrNull(prefs.modelCache(providerId) ?: return null)
        val at = MiniJson.num(v, "at") ?: return null
        val ids = MiniJson.arr(v, "ids")?.filterIsInstance<String>() ?: return null
        return StoredModels(at, ids)
    }

    override fun save(providerId: String, models: StoredModels) {
        prefs.setModelCache(providerId, MiniJson.write(mapOf("at" to models.at, "ids" to models.ids)))
    }

    override fun forget(providerId: String) {
        prefs.setModelCache(providerId, null)
    }
}

/**
 * Otak online Nine: beberapa penyedia AI gratis (Gemini, Groq, Cerebras, OpenRouter) dengan cadangan otomatis.
 * Pengguna mengisi kunci gratis sendiri; hanya penyedia yang kuncinya terisi yang dipakai.
 */
class MultiBrain(context: Context, private val prefs: Prefs) : OnlineBrain {

    private val app = context.applicationContext
    private val main = Handler(Looper.getMainLooper())

    // Pool biasa (bukan satu thread): koneksi lama yang macet tidak boleh menahan pertanyaan berikutnya.
    private val exec = Executors.newCachedThreadPool { r -> Thread(r, "nine-ai").apply { isDaemon = true } }

    private val engine = BrainEngine(
        backendFor = { cfg ->
            if (cfg.info.native) GeminiBackend(cfg.key, cfg.search) else OpenAiBackend(cfg.info, cfg.key)
        },
        store = PrefsModelStore(prefs)
    )

    @Volatile
    private var generation = 0

    @Volatile
    private var token: CancelToken? = null

    /** Penyedia yang kuncinya terisi, urut prioritas. */
    fun configs(): List<ProviderConfig> = Providers.all.mapNotNull { p ->
        val key = prefs.providerKey(p.id)
        if (key.isBlank()) null
        else ProviderConfig(p, key, if (p.native) prefs.geminiModel else "", p.native && prefs.aiSearch)
    }

    override val displayName: String
        get() = configs().joinToString(" + ") { it.info.label }.ifEmpty { "AI" }

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
        val system = prefs.persona.onlinePrompt(lang, LocalDateTime.now(), prefs.city, if (search) "Google Search" else null) +
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
     * Uji koneksi setiap penyedia yang kuncinya terisi. [onLine] dipanggil (di thread utama) per penyedia dengan hasilnya,
     * lalu [onDone] sekali. Menghapus catatan "istirahat" bila ujinya berhasil.
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
                val line = if (r.ok) {
                    "${cfg.info.label}: " + lang.t("berhasil", "OK") + " · ${r.model} · $secs " + lang.t("dtk", "s")
                } else {
                    "${cfg.info.label}: " + lang.t("gagal", "failed") + " · " + (r.failure?.let { Failures.describe(it, lang) } ?: "?")
                }
                main.post { onLine(line) }
            }
            main.post { onDone() }
        }
    }
}
