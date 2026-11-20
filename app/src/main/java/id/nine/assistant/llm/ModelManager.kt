package id.nine.assistant.llm

import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import id.nine.assistant.core.Prefs
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

/**
 * Mengelola file model Gemma di penyimpanan aplikasi: unduh (sekali), impor dari file, hapus.
 * Model disimpan di Android/data/id.nine.assistant/files/models/ dan tidak pernah diunggah ke mana pun.
 */
class ModelManager(context: Context, private val prefs: Prefs) {

    companion object {
        const val MODEL_NAME = "Gemma 4 E2B"
        const val FILE_NAME = "gemma-4-E2B-it.litertlm"
        const val REPO_PAGE = "https://huggingface.co/litert-community/gemma-4-E2B-it-litert-lm"
        const val URL = "$REPO_PAGE/resolve/main/$FILE_NAME?download=true"

        /** Ukuran minimum yang masuk akal untuk file model (±2,6 GB). */
        const val MIN_BYTES = 1_500_000_000L
        const val APPROX_BYTES = 2_583_000_000L
    }

    sealed class Status {
        object Missing : Status()
        data class Downloading(val downloaded: Long, val total: Long) : Status()
        data class Importing(val copied: Long, val total: Long) : Status()
        object Ready : Status()
        data class Failed(val reason: Reason, val detail: String = "") : Status()
    }

    enum class Reason { NEED_TOKEN, NETWORK, STORAGE, CORRUPT, IMPORT, OTHER }

    fun interface Listener {
        fun onStatus(status: Status)
    }

    private val app = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor()
    private val dm = app.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
    private val listeners = LinkedHashSet<Listener>()

    @Volatile
    var status: Status = Status.Missing
        private set

    private val dir: File get() = (app.getExternalFilesDir("models") ?: File(app.filesDir, "models")).apply { mkdirs() }
    val modelFile: File get() = File(dir, FILE_NAME)
    private val partFile: File get() = File(dir, "$FILE_NAME.part")

    fun isReady(): Boolean = modelFile.isFile && modelFile.length() >= MIN_BYTES

    init {
        status = if (isReady()) Status.Ready else Status.Missing
        if (prefs.downloadId >= 0) poll() // lanjutkan memantau unduhan yang sedang berjalan
    }

    fun addListener(l: Listener) {
        listeners.add(l)
        l.onStatus(status)
    }

    fun removeListener(l: Listener) {
        listeners.remove(l)
    }

    private fun emit(s: Status) {
        status = s
        main.post { for (l in listeners.toList()) l.onStatus(s) }
    }

    // ------------------------------------------------------------------ unduh

    fun startDownload() {
        if (status is Status.Downloading || status is Status.Importing) return
        emit(Status.Downloading(0, APPROX_BYTES))
        io.execute {
            try {
                val free = dir.usableSpace
                if (free < APPROX_BYTES + 300_000_000L) {
                    emit(Status.Failed(Reason.STORAGE, "${free / 1_000_000} MB"))
                    return@execute
                }
                val token = prefs.hfToken
                // Ikuti pengalihan sendiri agar token hanya dikirim ke huggingface.co, lalu
                // serahkan URL akhir (CDN bertanda tangan) ke DownloadManager.
                val finalUrl = resolve(URL, token)
                partFile.delete()
                val req = DownloadManager.Request(Uri.parse(finalUrl.first))
                    .setTitle("Nine · $MODEL_NAME")
                    .setDescription("Otak AI offline / Offline AI brain")
                    .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE)
                    .setAllowedOverMetered(true)
                    .setAllowedOverRoaming(false)
                    .setDestinationInExternalFilesDir(app, "models", "$FILE_NAME.part")
                if (finalUrl.second && token.isNotEmpty()) {
                    req.addRequestHeader("Authorization", "Bearer $token")
                }
                prefs.downloadId = dm.enqueue(req)
                poll()
            } catch (e: NeedTokenException) {
                emit(Status.Failed(Reason.NEED_TOKEN, e.message ?: ""))
            } catch (e: Exception) {
                emit(Status.Failed(Reason.NETWORK, e.message ?: e.javaClass.simpleName))
            }
        }
    }

    private class NeedTokenException(msg: String) : Exception(msg)

    /** Mengembalikan (url akhir, apakah url itu masih di huggingface.co). */
    private fun resolve(start: String, token: String): Pair<String, Boolean> {
        var url = start
        repeat(6) {
            val u = URL(url)
            val hf = u.host.endsWith("huggingface.co")
            // URL CDN bertanda tangan hanya berlaku untuk GET; jangan disentuh, langsung serahkan.
            if (!hf) return Pair(url, false)
            val c = (u.openConnection() as HttpURLConnection).apply {
                instanceFollowRedirects = false
                requestMethod = "HEAD"
                connectTimeout = 15_000
                readTimeout = 15_000
                setRequestProperty("User-Agent", "Nine-Android")
                if (hf && token.isNotEmpty()) setRequestProperty("Authorization", "Bearer $token")
            }
            try {
                val code = c.responseCode
                when {
                    code in 300..399 -> {
                        val loc = c.getHeaderField("Location") ?: return Pair(url, hf)
                        url = URL(u, loc).toString()
                    }
                    code == 401 || code == 403 -> throw NeedTokenException("HTTP $code")
                    code in 200..299 -> return Pair(url, hf)
                    else -> throw java.io.IOException("HTTP $code")
                }
            } finally {
                c.disconnect()
            }
        }
        return Pair(url, URL(url).host.endsWith("huggingface.co"))
    }

    private fun poll() {
        val id = prefs.downloadId
        if (id < 0) return
        val q = DownloadManager.Query().setFilterById(id)
        var state = -1
        var done = 0L
        var total = APPROX_BYTES
        var reason = 0
        try {
            dm.query(q)?.use { c ->
                if (c.moveToFirst()) {
                    state = c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
                    done = c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR))
                    val t = c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES))
                    if (t > 0) total = t
                    reason = c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_REASON))
                }
            }
        } catch (e: Exception) {
            state = -1
        }
        when (state) {
            DownloadManager.STATUS_SUCCESSFUL -> {
                prefs.downloadId = -1
                io.execute { finishDownload() }
            }
            DownloadManager.STATUS_FAILED -> {
                prefs.downloadId = -1
                partFile.delete()
                val r = when (reason) {
                    401, 403 -> Reason.NEED_TOKEN
                    DownloadManager.ERROR_INSUFFICIENT_SPACE -> Reason.STORAGE
                    else -> Reason.NETWORK
                }
                emit(Status.Failed(r, "code $reason"))
            }
            DownloadManager.STATUS_PENDING, DownloadManager.STATUS_RUNNING, DownloadManager.STATUS_PAUSED -> {
                emit(Status.Downloading(done, total))
                main.postDelayed({ poll() }, 1000)
            }
            else -> {
                // Unduhan hilang (dibatalkan dari notifikasi, dsb.)
                prefs.downloadId = -1
                emit(if (isReady()) Status.Ready else Status.Missing)
            }
        }
    }

    private fun finishDownload() {
        val f = partFile
        if (!f.isFile || f.length() < MIN_BYTES) {
            f.delete()
            emit(Status.Failed(Reason.CORRUPT, "${f.length()} B"))
            return
        }
        modelFile.delete()
        if (!f.renameTo(modelFile)) {
            emit(Status.Failed(Reason.STORAGE, "rename"))
            return
        }
        emit(Status.Ready)
    }

    fun cancelDownload() {
        val id = prefs.downloadId
        if (id >= 0) {
            try {
                dm.remove(id)
            } catch (e: Exception) {
                // abaikan
            }
        }
        prefs.downloadId = -1
        partFile.delete()
        emit(if (isReady()) Status.Ready else Status.Missing)
    }

    // ------------------------------------------------------------------ impor dari file

    fun importFrom(uri: Uri) {
        if (status is Status.Downloading || status is Status.Importing) return
        io.execute {
            try {
                val total = app.contentResolver.openAssetFileDescriptor(uri, "r")?.use { it.length } ?: -1L
                emit(Status.Importing(0, if (total > 0) total else APPROX_BYTES))
                val tmp = partFile
                tmp.delete()
                app.contentResolver.openInputStream(uri)?.use { input ->
                    tmp.outputStream().use { out ->
                        val buf = ByteArray(1 shl 20)
                        var copied = 0L
                        var lastEmit = 0L
                        while (true) {
                            val n = input.read(buf)
                            if (n < 0) break
                            out.write(buf, 0, n)
                            copied += n
                            if (copied - lastEmit > 32_000_000L) {
                                lastEmit = copied
                                emit(Status.Importing(copied, if (total > 0) total else APPROX_BYTES))
                            }
                        }
                    }
                } ?: throw java.io.IOException("open")
                finishDownload()
            } catch (e: Exception) {
                partFile.delete()
                emit(Status.Failed(Reason.IMPORT, e.message ?: e.javaClass.simpleName))
            }
        }
    }

    fun delete() {
        cancelDownload()
        modelFile.delete()
        emit(Status.Missing)
    }
}
