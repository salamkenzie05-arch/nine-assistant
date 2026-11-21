package id.nine.assistant.llm

import id.nine.assistant.core.AppLang

/** Penyedia yang kuncinya terisi. [userModel]: model pilihan pengguna (khusus Gemini, kosong = otomatis). */
class ProviderConfig(val info: ProviderInfo, val key: String, val userModel: String = "", val search: Boolean = false)

class StoredModels(val at: Long, val ids: List<String>)

/** Tempat menyimpan daftar model hasil penemuan (agar tidak menanya penyedia tiap pertanyaan). */
interface ModelStore {
    fun load(providerId: String): StoredModels?
    fun save(providerId: String, models: StoredModels)
    fun forget(providerId: String)
}

class MemoryModelStore : ModelStore {
    private val map = HashMap<String, StoredModels>()

    @Synchronized
    override fun load(providerId: String) = map[providerId]

    @Synchronized
    override fun save(providerId: String, models: StoredModels) {
        map[providerId] = models
    }

    @Synchronized
    override fun forget(providerId: String) {
        map.remove(providerId)
    }
}

sealed class Outcome {
    data class Success(val text: String, val provider: String, val model: String) : Outcome()

    /** Teks sudah sempat tampil lalu koneksi putus: teks itu dipakai apa adanya. */
    data class Partial(val text: String, val provider: String, val failure: Failure) : Outcome()

    /** Tak ada yang berhasil dan belum ada teks. [retryInMs]: perkiraan waktu sampai ada yang pulih (0 = tak diketahui). */
    data class Failed(val perProvider: Map<String, Failure>, val retryInMs: Long, val offline: Boolean) : Outcome() {
        fun summary(lang: AppLang): String {
            if (offline) return lang.t("tidak ada koneksi internet", "no internet connection")
            val s = perProvider.entries.joinToString("; ") { (label, f) -> "$label: ${Failures.describe(f, lang)}" }
            if (retryInMs <= 0L) return s
            val min = ((retryInMs + 59_999L) / 60_000L).coerceAtLeast(1L)
            return s + lang.t(". Coba lagi sekitar $min menit.", ". Try again in about $min min.")
        }
    }

    object Cancelled : Outcome()
}

/** Pembatal satu pertanyaan: menandai batal dan memutus koneksi yang sedang berjalan. Tiap pertanyaan punya sendiri. */
class CancelToken {
    @Volatile
    var cancelled = false
        private set

    @Volatile
    private var backend: ChatBackend? = null

    fun cancel() {
        cancelled = true
        try {
            backend?.abort()
        } catch (e: Exception) {
            // abaikan
        }
    }

    internal fun attach(b: ChatBackend?) {
        backend = b
        if (b != null && cancelled) {
            try {
                b.abort()
            } catch (e: Exception) {
                // abaikan
            }
        }
    }
}

/** Hasil uji koneksi satu penyedia. */
data class ProbeResult(val ok: Boolean, val model: String?, val millis: Long, val failure: Failure?)

/**
 * Otak online: mencoba penyedia berurutan (Gemini → Groq → Cerebras → OpenRouter, yang kuncinya terisi) dan beberapa
 * model terbaik tiap penyedia; yang kena batas kuota/gagal diistirahatkan sementara dan pertanyaan beralih otomatis.
 * Tanpa Android, supaya logikanya bisa diuji di JVM.
 */
class BrainEngine(
    private val backendFor: (ProviderConfig) -> ChatBackend,
    private val store: ModelStore,
    private val clock: () -> Long = { System.currentTimeMillis() },
    val book: CooldownBook = CooldownBook(),
    private val maxAttempts: Int = 4
) {
    /** Lupakan semua catatan istirahat (mis. setelah pengguna mengganti kunci). */
    fun resetCooldowns() = book.clear()

    private fun fp(key: String) = Integer.toHexString(key.hashCode())

    private fun providerKey(c: ProviderConfig) = "p:${c.info.id}#${fp(c.key)}"

    private fun modelKey(c: ProviderConfig, model: String) = "m:${c.info.id}#${fp(c.key)}/$model"

    private fun limitFor(info: ProviderInfo) = if (info.id == Providers.OPENROUTER.id) 3 else 2

    /** Model yang akan dicoba untuk [cfg], terbaik dulu. */
    fun modelsFor(cfg: ProviderConfig, backend: ChatBackend, forceRefresh: Boolean = false): List<String> {
        if (cfg.info.native) {
            val primary = cfg.userModel.trim().removePrefix("models/").ifBlank { Providers.DEFAULT_GEMINI_MODEL }
            return listOf(primary, Providers.DEFAULT_GEMINI_MODEL, Providers.FALLBACK_GEMINI_MODEL).distinct().take(limitFor(cfg.info))
        }
        val now = clock()
        val stored = store.load(cfg.info.id)
        var ids = stored?.ids.orEmpty()
        val fresh = stored != null && ids.isNotEmpty() && now - stored.at < MODEL_TTL_MS
        if (forceRefresh || !fresh) {
            try {
                val listed = backend.listModels()
                if (listed.isNotEmpty()) {
                    ids = listed
                    store.save(cfg.info.id, StoredModels(now, listed))
                }
            } catch (e: ProviderException) {
                if (e.failure.kind == FailKind.AUTH) throw e
                // gagal lain: pakai daftar lama atau bawaan
            } catch (e: CancelledException) {
                throw e
            } catch (e: Exception) {
                // gagal ambil daftar: pakai daftar lama atau bawaan
            }
        }
        val picked = ModelPicker.pick(cfg.info.id, ids, limitFor(cfg.info))
        return picked.ifEmpty { ModelPicker.defaults(cfg.info.id).take(limitFor(cfg.info)) }
    }

    private fun penalize(cfg: ProviderConfig, model: String?, f: Failure) {
        val ms = Failures.cooldownMs(f)
        if (ms > 0) {
            val until = clock() + ms
            if (model == null || Failures.providerWide(f, cfg.info.id)) book.mark(providerKey(cfg), until, f)
            else book.mark(modelKey(cfg, model), until, f)
        }
        if (f.kind == FailKind.NOT_FOUND) store.forget(cfg.info.id)
    }

    private val retryable = setOf(FailKind.RATE_LIMIT, FailKind.QUOTA_DAILY, FailKind.SERVER, FailKind.TIMEOUT, FailKind.EMPTY)

    fun run(configs: List<ProviderConfig>, req: ChatRequest, onChunk: (String) -> Unit, token: CancelToken = CancelToken()): Outcome {
        val failed = LinkedHashMap<String, Failure>()
        var attempts = 0
        var netFails = 0
        var soonest = Long.MAX_VALUE

        fun note(label: String, e: CooldownBook.Entry, now: Long) {
            failed[label] = e.failure
            if (e.failure.kind in retryable) soonest = minOf(soonest, e.until - now)
        }

        fun failedResult(): Outcome {
            val offline = failed.isNotEmpty() && failed.values.all { it.kind == FailKind.NETWORK }
            return Outcome.Failed(failed, if (soonest == Long.MAX_VALUE) 0L else soonest, offline)
        }

        outer@ for (cfg in configs) {
            if (token.cancelled) return Outcome.Cancelled
            val label = cfg.info.label
            val now0 = clock()
            val pCool = book.active(providerKey(cfg), now0)
            if (pCool != null) {
                note(label, pCool, now0)
                continue
            }
            val backend = backendFor(cfg)
            val models = try {
                token.attach(backend)
                modelsFor(cfg, backend)
            } catch (e: CancelledException) {
                return Outcome.Cancelled
            } catch (e: Exception) {
                if (token.cancelled) return Outcome.Cancelled
                val f = Failures.fromException(e)
                penalize(cfg, null, f)
                failed[label] = f
                continue
            } finally {
                token.attach(null)
            }

            for (model in models) {
                if (token.cancelled) return Outcome.Cancelled
                val nowM = clock()
                val mCool = book.active(modelKey(cfg, model), nowM)
                if (mCool != null) {
                    if (label !in failed) note(label, mCool, nowM) else if (mCool.failure.kind in retryable) soonest = minOf(soonest, mCool.until - nowM)
                    continue
                }
                if (attempts >= maxAttempts) break@outer
                attempts++
                val sb = StringBuilder()
                val b = backendFor(cfg)
                token.attach(b)
                try {
                    b.stream(model, req) { chunk ->
                        if (token.cancelled) throw CancelledException()
                        sb.append(chunk)
                        onChunk(chunk)
                    }
                    if (token.cancelled) return Outcome.Cancelled
                    if (sb.isBlank()) throw ProviderException(Failure(FailKind.EMPTY, null, "empty"))
                    return Outcome.Success(sb.toString(), label, model)
                } catch (e: CancelledException) {
                    return Outcome.Cancelled
                } catch (e: Exception) {
                    if (token.cancelled) return Outcome.Cancelled
                    val f = Failures.fromException(e)
                    penalize(cfg, model, f)
                    if (sb.isNotBlank()) return Outcome.Partial(sb.toString(), label, f)
                    failed[label] = f
                    if (f.kind in retryable) soonest = minOf(soonest, Failures.cooldownMs(f))
                    if (f.kind == FailKind.NETWORK) {
                        netFails++
                        if (netFails >= 2) return failedResult()
                    }
                    if (Failures.providerWide(f, cfg.info.id)) continue@outer
                } finally {
                    token.attach(null)
                }
            }
        }
        return failedResult()
    }

    /** Uji satu penyedia dengan pertanyaan kecil. Mengabaikan catatan istirahat; hasilnya memperbarui catatan itu. */
    fun probe(cfg: ProviderConfig, token: CancelToken = CancelToken()): ProbeResult {
        val started = clock()
        val backend = backendFor(cfg)
        val req = ChatRequest("Reply with one short word.", emptyList(), "Say: ready")
        var last: Failure? = null
        try {
            val models = modelsFor(cfg, backend, forceRefresh = true)
            if (models.isEmpty()) return ProbeResult(false, null, clock() - started, Failure(FailKind.NOT_FOUND, null, "no model"))
            for ((i, model) in models.take(2).withIndex()) {
                val b = if (i == 0) backend else backendFor(cfg)
                token.attach(b)
                val sb = StringBuilder()
                try {
                    b.stream(model, req) { sb.append(it) }
                    if (sb.isBlank()) throw ProviderException(Failure(FailKind.EMPTY, null, "empty"))
                    book.forget(providerKey(cfg))
                    book.forget(modelKey(cfg, model))
                    return ProbeResult(true, model, clock() - started, null)
                } catch (e: CancelledException) {
                    throw e
                } catch (e: Exception) {
                    val f = Failures.fromException(e)
                    last = f
                    penalize(cfg, model, f)
                    if (Failures.providerWide(f, cfg.info.id)) break
                } finally {
                    token.attach(null)
                }
            }
        } catch (e: CancelledException) {
            return ProbeResult(false, null, clock() - started, Failure(FailKind.OTHER, null, "cancelled"))
        } catch (e: Exception) {
            val f = Failures.fromException(e)
            penalize(cfg, null, f)
            return ProbeResult(false, null, clock() - started, f)
        }
        return ProbeResult(false, null, clock() - started, last ?: Failure(FailKind.OTHER, null, "unknown"))
    }

    companion object {
        const val MODEL_TTL_MS = 12L * 60 * 60 * 1000
    }
}
