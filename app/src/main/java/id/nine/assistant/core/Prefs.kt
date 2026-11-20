package id.nine.assistant.core

import android.content.Context
import androidx.appcompat.app.AppCompatDelegate
import java.util.Locale

/** Pengaturan Nine, disimpan lokal di SharedPreferences. */
class Prefs(context: Context) {
    private val sp = context.applicationContext.getSharedPreferences("nine_prefs", Context.MODE_PRIVATE)

    init {
        // Versi 2.7: semua alarm pindah ke mesin alarm Nine sekali, agar bisa dimatikan/dihapus lewat suara.
        // (Alarm di aplikasi Jam tidak bisa diubah oleh aplikasi lain.) Pengguna tetap bisa memilih ulang di Pengaturan.
        if (!sp.getBoolean("alarm_engine_v27", false)) {
            sp.edit().putString("alarm_engine", "nine").putBoolean("alarm_engine_v27", true).apply()
        }
    }

    enum class Theme(val nightMode: Int) {
        SYSTEM(AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM),
        LIGHT(AppCompatDelegate.MODE_NIGHT_NO),
        DARK(AppCompatDelegate.MODE_NIGHT_YES)
    }

    var lang: AppLang
        get() {
            val saved = sp.getString("lang", null)
            if (saved != null) return AppLang.fromTag(saved)
            return if (Locale.getDefault().language == "en") AppLang.EN else AppLang.ID
        }
        set(value) = sp.edit().putString("lang", value.tag).apply()

    var theme: Theme
        get() = runCatching { Theme.valueOf(sp.getString("theme", Theme.SYSTEM.name)!!) }.getOrDefault(Theme.SYSTEM)
        set(value) = sp.edit().putString("theme", value.name).apply()

    var speakReplies: Boolean
        get() = sp.getBoolean("speak", true)
        set(value) = sp.edit().putBoolean("speak", value).apply()

    /** Nine mengingat otomatis hal tentang pengguna yang disebut dalam percakapan ("aku suka …", "nama saya …"). */
    var autoMemory: Boolean
        get() = sp.getBoolean("auto_memory", true)
        set(value) = sp.edit().putBoolean("auto_memory", value).apply()

    var honorificId: String
        get() = sp.getString("hon_id", "Tuan") ?: "Tuan"
        set(value) = sp.edit().putString("hon_id", value.trim()).apply()

    var honorificEn: String
        get() = sp.getString("hon_en", "sir") ?: "sir"
        set(value) = sp.edit().putString("hon_en", value.trim()).apply()

    /** Token Hugging Face (opsional) bila model memerlukan login. Disimpan terenkripsi (Android Keystore). */
    var hfToken: String
        get() = SecureStore.decrypt(sp.getString("hf_token", "") ?: "")
        set(value) = sp.edit().putString("hf_token", SecureStore.encrypt(value.trim())).apply()

    /** "gpu" atau "cpu": backend yang terakhir terbukti berjalan. */
    var llmBackend: String
        get() = sp.getString("llm_backend", "gpu") ?: "gpu"
        set(value) = sp.edit().putString("llm_backend", value).apply()

    /** Id unduhan DownloadManager yang sedang berjalan, -1 bila tidak ada. */
    var downloadId: Long
        get() = sp.getLong("dl_id", -1L)
        set(value) = sp.edit().putLong("dl_id", value).apply()

    // --- Mode internet (Gemini)
    var internetMode: Boolean
        get() = sp.getBoolean("net_mode", false)
        set(value) = sp.edit().putBoolean("net_mode", value).apply()

    /** API key Gemini, disimpan terenkripsi (Android Keystore). */
    var geminiKey: String
        get() = SecureStore.decrypt(sp.getString("gemini_key", "") ?: "")
        set(value) = sp.edit().putString("gemini_key", SecureStore.encrypt(value.trim())).apply()

    /** Enkripsi ulang rahasia yang masih tersimpan polos dari versi lama. */
    fun migrateSecrets() {
        for (k in listOf("gemini_key", "hf_token")) {
            val v = sp.getString(k, "") ?: ""
            if (v.isNotEmpty() && !SecureStore.isEncrypted(v)) sp.edit().putString(k, SecureStore.encrypt(v)).apply()
        }
        // Integrasi Claude dihapus: buang key & pengaturannya bila pernah diisi di versi 2.4.
        if (sp.contains("claude_key") || sp.contains("online_provider") || sp.contains("claude_model")) {
            sp.edit().remove("claude_key").remove("online_provider").remove("claude_model").apply()
        }
    }

    // --- Lokasi
    var useLocation: Boolean
        get() = sp.getBoolean("use_location", false)
        set(value) = sp.edit().putBoolean("use_location", value).apply()

    /**
     * Token acak per instalasi. Intent internal (widget, notifikasi, layanan asisten) menyertakannya agar aplikasi lain
     * tidak bisa menyuruh Nine langsung mendengarkan.
     */
    val launchToken: String
        get() {
            sp.getString("launch_token", null)?.let { return it }
            val t = java.util.UUID.randomUUID().toString()
            sp.edit().putString("launch_token", t).apply()
            return t
        }

    var geminiModel: String
        get() = (sp.getString("gemini_model", null) ?: "").ifBlank { DEFAULT_GEMINI_MODEL }
        set(value) = sp.edit().putString("gemini_model", value.trim()).apply()

    /** Kota pengguna (opsional) agar pertanyaan cuaca/lokal lebih tepat. */
    var city: String
        get() = sp.getString("city", "") ?: ""
        set(value) = sp.edit().putString("city", value.trim()).apply()

    // --- Model Gemma offline
    var modelVariant: String
        get() = sp.getString("model_variant", "e2b") ?: "e2b"
        set(value) = sp.edit().putString("model_variant", value).apply()

    // --- Wake word
    var wakeEnabled: Boolean
        get() = sp.getBoolean("wake_on", false)
        set(value) = sp.edit().putBoolean("wake_on", value).apply()

    /** Satu atau beberapa frasa, dipisah koma. */
    var wakePhrases: String
        get() = (sp.getString("wake_phrase", null) ?: "").ifBlank { "hey nine" }
        set(value) = sp.edit().putString("wake_phrase", value.trim()).apply()

    // --- Kalender
    /** Kalender tujuan acara baru; -1 = otomatis. */
    var calendarId: Long
        get() = sp.getLong("cal_id", -1L)
        set(value) = sp.edit().putLong("cal_id", value).apply()

    /** Acara terakhir yang dibuat Nine (untuk "ubah jadi jam 11", "hapus acara tadi"). */
    var lastEventId: Long
        get() = sp.getLong("last_event", -1L)
        set(value) = sp.edit().putLong("last_event", value).apply()

    /** Kapan terakhir pengguna berbicara dengan Nine (untuk ekspresi widget). */
    var lastTalk: Long
        get() = sp.getLong("last_talk", 0L)
        set(value) = sp.edit().putLong("last_talk", value).apply()

    // --- Tombol melayang (assistive touch) & reaksi musik
    /** Tombol melayang seperti AssistiveTouch iPhone; butuh layanan aksesibilitas Nine aktif. */
    var assistiveTouch: Boolean
        get() = sp.getBoolean("assistive_touch", false)
        set(value) = sp.edit().putBoolean("assistive_touch", value).apply()

    /** Wajah Nine bereaksi pada beat/bass/nada musik yang terdengar lewat mikrofon (hanya saat layar Nine terbuka). */
    var musicReact: Boolean
        get() = sp.getBoolean("music_react", true)
        set(value) = sp.edit().putBoolean("music_react", value).apply()

    /** Hari (yyyy-MM-dd) dan jumlah pemakaian muncratan hati pada hari itu (maks. 3 per hari). */
    var heartDay: String
        get() = sp.getString("heart_day", "") ?: ""
        set(value) = sp.edit().putString("heart_day", value).apply()

    var heartCount: Int
        get() = sp.getInt("heart_count", 0)
        set(value) = sp.edit().putInt("heart_count", value).apply()

    // --- Alarm
    /** "nine" = alarm milik Nine (bisa dinyalakan/dimatikan/dihapus lewat suara); "clock" = aplikasi Jam bawaan. */
    var alarmEngine: String
        get() = sp.getString("alarm_engine", "nine") ?: "nine"
        set(value) = sp.edit().putString("alarm_engine", value).apply()

    val nineAlarms: Boolean get() = alarmEngine != "clock"

    val persona: Persona get() = Persona(honorificId, honorificEn)

    companion object {
        const val DEFAULT_GEMINI_MODEL = "gemini-flash-latest"
    }
}
