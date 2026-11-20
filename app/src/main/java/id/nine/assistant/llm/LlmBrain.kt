package id.nine.assistant.llm

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.os.SystemClock
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.Conversation
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.SamplerConfig
import id.nine.assistant.core.AppLang
import id.nine.assistant.core.MemoryStore
import id.nine.assistant.core.Prefs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.LocalDateTime
import java.util.concurrent.Executors

/**
 * Otak percakapan Nine: Gemma yang berjalan on-device lewat LiteRT-LM.
 *
 * Aturan LiteRT-LM: satu thread memiliki Engine dan Conversation; initialize tidak boleh di main thread;
 * tutup Conversation sebelum Engine; jangan menutup saat masih streaming.
 * Semua callback ke pemanggil dikirim di main thread.
 */
class LlmBrain(context: Context, private val prefs: Prefs, private val models: ModelManager) {

    enum class State { NO_MODEL, IDLE, LOADING, READY, BUSY, ERROR }

    interface StateListener {
        fun onBrainState(state: State, detail: String)
    }

    interface ReplyCallback {
        fun onChunk(text: String)
        fun onDone(fullText: String)
        fun onError(message: String)
    }

    private val app = context.applicationContext
    private val memory = MemoryStore(app)
    private val main = Handler(Looper.getMainLooper())
    // Prioritas sedikit di bawah UI (nice 9, masih grup latar depan): saat model dimuat atau menjawab, gambar dan sentuhan
    // di layar tetap didahulukan. Thread bawaan engine (native) mewarisi prioritas ini.
    private val dispatcher = Executors.newSingleThreadExecutor { r ->
        Thread({
            try {
                Process.setThreadPriority(LLM_PRIORITY)
            } catch (e: Exception) {
                // abaikan: tetap jalan dengan prioritas biasa
            }
            r.run()
        }, "nine-llm")
    }.asCoroutineDispatcher()
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)

    private var engine: Engine? = null
    private var conversation: Conversation? = null
    private var conversationLang: AppLang? = null
    private var conversationStarted: Long = 0L
    private var backendName: String = prefs.llmBackend
    private var hadSuccess = false
    private var busy = false

    /**
     * Dipasang oleh aplikasi: true bila layar Nine sedang terbuka (model dipertahankan lebih lama).
     * Dipanggil dari thread LLM, jadi harus aman dipanggil dari thread mana pun.
     */
    @Volatile
    var isForeground: () -> Boolean = { false }

    /**
     * True bila konteks percakapan Gemma hilang karena model dilepas dari memori (bukan karena pengguna
     * meminta lupa). Pemanggil lalu menyisipkan lagi beberapa giliran terakhir ke pesan berikutnya.
     */
    @Volatile
    var contextDropped = false
        private set

    /** Terakhir kali model dipakai (elapsedRealtime). Hanya diakses di thread LLM. */
    private var lastUse = 0L
    private var idleJob: Job? = null

    @Volatile
    var state: State = if (models.isReady()) State.IDLE else State.NO_MODEL
        private set

    private val listeners = LinkedHashSet<StateListener>()

    fun addListener(l: StateListener) {
        listeners.add(l)
        l.onBrainState(state, backendName)
    }

    fun removeListener(l: StateListener) {
        listeners.remove(l)
    }

    private fun setState(s: State, detail: String = backendName) {
        state = s
        id.nine.assistant.core.Perf.heavy = s == State.LOADING || s == State.BUSY
        main.post { for (l in listeners.toList()) l.onBrainState(s, detail) }
    }

    val isUsable: Boolean get() = models.isReady()

    /** Muat model di latar belakang bila belum (aman dipanggil berkali-kali). */
    fun preload() {
        if (!models.isReady()) {
            setState(State.NO_MODEL)
            return
        }
        scope.launch { ensureEngine() }
    }

    private fun backendFor(name: String): Backend = if (name == "cpu") Backend.CPU() else Backend.GPU()

    /** Harus dipanggil di thread LLM. */
    private var loadedPath: String? = null

    private fun ensureEngine(): Boolean {
        lastUse = SystemClock.elapsedRealtime()
        val path = models.modelFile.absolutePath
        if (engine != null && loadedPath == path) return true
        if (engine != null) closeEngine() // model diganti (E2B ↔ E4B)
        if (!models.isReady()) {
            setState(State.NO_MODEL)
            return false
        }
        setState(State.LOADING)
        val order = if (backendName == "cpu") listOf("cpu") else listOf("gpu", "cpu")
        var lastError = ""
        for (name in order) {
            try {
                val cfg = EngineConfig(
                    modelPath = models.modelFile.absolutePath,
                    backend = backendFor(name),
                    cacheDir = app.cacheDir.path
                )
                val e = Engine(cfg)
                e.initialize()
                engine = e // simpan hanya setelah initialize berhasil
                loadedPath = path
                backendName = name
                setState(State.READY)
                watchIdle()
                return true
            } catch (t: Throwable) {
                lastError = t.message ?: t.javaClass.simpleName
            }
        }
        setState(State.ERROR, lastError)
        return false
    }

    /** Harus dipanggil di thread LLM. */
    private fun ensureConversation(lang: AppLang): Conversation {
        val stale = System.currentTimeMillis() - conversationStarted > STALE_MS
        val c = conversation
        if (c != null && conversationLang == lang && !stale) return c
        closeConversation()
        val cfg = ConversationConfig(
            systemInstruction = Contents.of(prefs.persona.systemPrompt(lang, LocalDateTime.now(), memory.promptBlock(lang))),
            samplerConfig = SamplerConfig(topK = 40, topP = 0.95, temperature = 0.6)
        )
        val created = engine!!.createConversation(cfg)
        conversation = created
        conversationLang = lang
        conversationStarted = System.currentTimeMillis()
        contextDropped = false
        return created
    }

    private fun closeConversation() {
        try {
            conversation?.close()
        } catch (t: Throwable) {
            // sudah tertutup
        }
        conversation = null
    }

    /**
     * Lepaskan model (±2,5 GB RAM) bila tidak dipakai: 2 menit bila layar Nine terbuka, 30 detik bila Nine
     * di latar belakang. Dimuat ulang otomatis saat ada pertanyaan yang butuh AI. Berjalan di thread LLM,
     * jadi tidak pernah bentrok dengan [ask] di thread yang sama (busy dicek juga).
     */
    private fun watchIdle() {
        idleJob?.cancel()
        idleJob = scope.launch {
            while (true) {
                delay(IDLE_CHECK_MS)
                if (engine == null) return@launch
                val now = SystemClock.elapsedRealtime()
                if (busy) {
                    lastUse = now
                    continue
                }
                val limit = if (isForeground()) IDLE_UNLOAD_FG_MS else IDLE_UNLOAD_BG_MS
                if (now - lastUse >= limit) {
                    closeEngine()
                    contextDropped = true
                    setState(if (models.isReady()) State.IDLE else State.NO_MODEL)
                    return@launch
                }
            }
        }
    }

    private fun closeEngine() {
        idleJob?.cancel()
        idleJob = null
        loadedPath = null
        closeConversation()
        try {
            engine?.close()
        } catch (t: Throwable) {
            // abaikan
        }
        engine = null
    }

    /** Ajukan pertanyaan. Kembalikan Job yang bisa dibatalkan lewat [cancel]. */
    fun ask(text: String, lang: AppLang, cb: ReplyCallback): Job = scope.launch {
        if (!ensureEngine()) {
            post { cb.onError(if (models.isReady()) "engine" else "no_model") }
            return@launch
        }
        var attempt = 0
        while (true) {
            val full = StringBuilder()
            var failure: Throwable? = null
            busy = true
            setState(State.BUSY)
            // Potongan teks dikirim ke layar paling sering tiap CHUNK_MS (bukan tiap token) agar layar tidak tersendat.
            val outBuf = StringBuilder()
            var lastFlush = 0L
            fun flush() {
                if (outBuf.isEmpty()) return
                val piece = outBuf.toString()
                outBuf.setLength(0)
                lastFlush = SystemClock.elapsedRealtime()
                post { cb.onChunk(piece) }
            }
            try {
                val conv = ensureConversation(lang)
                conv.sendMessageAsync(text).collect { msg ->
                    val chunk = msg.toString()
                    if (chunk.isNotEmpty()) {
                        full.append(chunk)
                        outBuf.append(chunk)
                        if (SystemClock.elapsedRealtime() - lastFlush >= CHUNK_MS) flush()
                    }
                }
            } catch (t: Throwable) {
                failure = t
            } finally {
                busy = false
                lastUse = SystemClock.elapsedRealtime()
                flush()
            }

            if (failure == null || cancelled) {
                cancelled = false
                hadSuccess = hadSuccess || full.isNotEmpty()
                if (full.isNotEmpty()) prefs.llmBackend = backendName
                setState(State.READY)
                val result = full.toString()
                post { cb.onDone(result) }
                return@launch
            }

            attempt++
            val msg = failure.message ?: failure.javaClass.simpleName
            if (attempt == 1 && full.isEmpty()) {
                if (backendName == "gpu" && !hadSuccess) {
                    // GPU gagal saat inferensi pertama (mis. OpenCL tidak tersedia): pindah ke CPU.
                    closeEngine()
                    backendName = "cpu"
                    prefs.llmBackend = "cpu"
                    if (!ensureEngine()) {
                        post { cb.onError(msg) }
                        return@launch
                    }
                } else {
                    // Konteks mungkin penuh: mulai percakapan baru lalu coba sekali lagi.
                    closeConversation()
                }
                continue
            }
            setState(State.READY)
            post { cb.onError(msg) }
            return@launch
        }
    }

    @Volatile
    private var cancelled = false

    /** Hentikan jawaban yang sedang dibuat. */
    fun cancel() {
        if (!busy) return
        cancelled = true
        scope.launch {
            var tries = 0
            while (busy && tries < 30) {
                try {
                    conversation?.cancelProcess()
                } catch (t: Throwable) {
                    // abaikan
                }
                delay(100)
                tries++
            }
        }
    }

    /** Lupakan konteks percakapan. */
    fun reset() {
        scope.launch {
            if (!busy) closeConversation()
            contextDropped = false // pengguna sengaja melupakan: jangan sisipkan riwayat lama
        }
    }

    /** Ganti backend AI ("gpu"/"cpu"): model dimuat ulang dengan backend baru saat dibutuhkan. */
    fun useBackend(name: String) {
        if (name != "gpu" && name != "cpu") return
        prefs.llmBackend = name
        scope.launch {
            while (busy) delay(50)
            if (backendName != name) {
                backendName = name
                closeEngine()
                contextDropped = true
                setState(if (models.isReady()) State.IDLE else State.NO_MODEL)
            }
        }
    }

    /** Lepaskan model bila sedang tidak menjawab (memori sempit); dimuat lagi otomatis saat dibutuhkan. */
    fun trim() {
        scope.launch {
            if (!busy && engine != null) {
                closeEngine()
                contextDropped = true
                setState(if (models.isReady()) State.IDLE else State.NO_MODEL)
            }
        }
    }

    /** Lepaskan model dari memori (mis. setelah model dihapus). */
    fun unload() {
        cancel()
        scope.launch {
            while (busy) delay(50)
            closeEngine()
            contextDropped = true
            setState(if (models.isReady()) State.IDLE else State.NO_MODEL)
        }
    }

    private inline fun post(crossinline f: () -> Unit) {
        main.post { f() }
    }

    private companion object {
        const val CHUNK_MS = 90L
        const val IDLE_CHECK_MS = 10_000L
        const val IDLE_UNLOAD_FG_MS = 2 * 60_000L
        const val IDLE_UNLOAD_BG_MS = 30_000L
        const val STALE_MS = 30 * 60 * 1000L
        /** Sedikit di bawah UI tetapi tetap di grup latar depan (>= 10 akan dipindah ke grup latar belakang yang lambat). */
        const val LLM_PRIORITY = 9
    }
}
