package id.nine.assistant.llm

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import id.nine.assistant.core.AppLang

/** Otak online Nine (Gemini atau Claude). Dipakai hanya bila pengguna menyalakan Mode internet. */
interface OnlineBrain {
    /** Nama penyedia untuk ditampilkan, mis. "Gemini" atau "Claude". */
    val displayName: String

    /** Mode internet aktif dan API key terisi. */
    fun isConfigured(): Boolean

    fun isOnline(): Boolean

    /** Siap dipakai sekarang: terkonfigurasi dan ada koneksi. */
    fun isUsable(): Boolean = isConfigured() && isOnline()

    fun reset() {}

    fun cancel()

    /**
     * [history]: giliran sebelumnya (role "user"/"model"), sudah urut. [context]: info perangkat terkini
     * (tugas, acara, lokasi) yang ditambahkan ke instruksi sistem.
     */
    fun ask(text: String, lang: AppLang, history: List<Pair<String, String>>, context: String, cb: Callback)

    /** Callback tambahan: [onFallback] dipanggil bila gagal karena jaringan/kuota sebelum ada teks. */
    interface Callback : LlmBrain.ReplyCallback {
        fun onFallback(reason: String)

        /** Teks yang sudah dikirim ternyata hanya pengantar ("Saya cari dulu…"): buang, jawaban sebenarnya menyusul. */
        fun onReset() {}
    }

    companion object {
        fun networkOk(ctx: Context): Boolean = try {
            val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val caps = cm.getNetworkCapabilities(cm.activeNetwork)
            caps != null && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        } catch (e: Exception) {
            false
        }
    }
}
