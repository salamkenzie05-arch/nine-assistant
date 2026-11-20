package id.nine.assistant.llm

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.Conversation
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.SamplerConfig
import id.nine.assistant.core.AppLang
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
    private val main = Handler(Looper.getMainLooper())
    private val dispatcher = Executors.newSingleThreadExecutor { r -> Thread(r, "nine-llm") }.asCoroutineDispatcher()
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)

    private var engine: Engine? = null
    private var conversation: Conversation? = null
    private var conversationLang: AppLang? = null
    private var conversationStarted: Long = 0L
    private var backendName: String = prefs.llmBackend
    private var hadSuccess = false
    private var busy = false

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
    private fun ensureEngine(): Boolean {
        if (engine != null) return true
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
                backendName = name
                setState(State.READY)
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
        val stale = System.currentTimeMillis() - conversationStarted > 30 * 60 * 1000L
        val c = conversation
        if (c != null && conversationLang == lang && !stale) return c
        closeConversation()
        val cfg = ConversationConfig(
            systemInstruction = Contents.of(prefs.persona.systemPrompt(lang, LocalDateTime.now())),
            samplerConfig = SamplerConfig(topK = 40, topP = 0.95, temperature = 0.7)
        )
        val created = engine!!.createConversation(cfg)
        conversation = created
        conversationLang = lang
        conversationStarted = System.currentTimeMillis()
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

    private fun closeEngine() {
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
            try {
                val conv = ensureConversation(lang)
                conv.sendMessageAsync(text).collect { msg ->
                    val chunk = msg.toString()
                    if (chunk.isNotEmpty()) {
                        full.append(chunk)
                        post { cb.onChunk(chunk) }
                    }
                }
            } catch (t: Throwable) {
                failure = t
            } finally {
                busy = false
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
        }
    }

    /** Lepaskan model dari memori (mis. setelah model dihapus). */
    fun unload() {
        cancel()
        scope.launch {
            while (busy) delay(50)
            closeEngine()
            setState(if (models.isReady()) State.IDLE else State.NO_MODEL)
        }
    }

    private inline fun post(crossinline f: () -> Unit) {
        main.post { f() }
    }
}
