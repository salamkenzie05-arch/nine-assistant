package id.nine.assistant.core

import android.content.Context
import androidx.appcompat.app.AppCompatDelegate
import java.util.Locale

/** Pengaturan Nine, disimpan lokal di SharedPreferences. */
class Prefs(context: Context) {
    private val sp = context.applicationContext.getSharedPreferences("nine_prefs", Context.MODE_PRIVATE)

    init {
        // Versi 3.3: AI berjalan di CPU secara bawaan (GPU berebut dengan tampilan dan membuat layar patah-patah).
        // Pengguna tetap bisa menyalakan GPU lagi di Pengaturan.
        if (!sp.getBoolean("llm_cpu_v33", false)) {
            sp.edit().putString("llm_backend", "cpu").putBoolean("llm_cpu_v33", true).apply()
        }
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
        set(value) = sp.edit().putString("hon_id", cleanName(value)).apply()

    var honorificEn: String
        get() = sp.getString("hon_en", "sir") ?: "sir"
        set(value) = sp.edit().putString("hon_en", cleanName(value)).apply()

    /** Token Hugging Face (opsional) bila model memerlukan login. Disimpan terenkripsi (Android Keystore). */
    var hfToken: String
        get() = SecureStore.decrypt(sp.getString("hf_token", "") ?: "")
        set(value) = sp.edit().putString("hf_token", SecureStore.encrypt(value.trim())).apply()

    /** "gpu" atau "cpu": backend AI. Bawaan CPU (lebih mulus untuk tampilan); GPU lebih cepat tetapi bisa membuat layar patah-patah. */
    var llmBackend: String
        get() = sp.getString("llm_backend", "cpu") ?: "cpu"
        set(value) = sp.edit().putString("llm_backend", value).apply()

    /** Id unduhan DownloadManager yang sedang berjalan, -1 bila tidak ada. */
    var downloadId: Long
        get() = sp.getLong("dl_id", -1L)
        set(value) = sp.edit().putLong("dl_id", value).apply()

    // --- Mode internet (Gemini)
    var internetMode: Boolean
        get() = sp.getBoolean("net_mode", false)
        set(value) = sp.edit().putBoolean("net_mode", value).apply()

    /** Cadangan hasil dekripsi, dikunci oleh teks terenkripsinya (selalu berbeda tiap simpan), agar Keystore tak dipanggil berulang. */
    private val keyCache = HashMap<String, String>()

    /** API key penyedia AI online ([id]: "gemini"), disimpan terenkripsi (Android Keystore). */
    fun providerKey(id: String): String {
        val stored = sp.getString("${id}_key", "") ?: ""
        if (stored.isEmpty()) return ""
        synchronized(keyCache) {
            return keyCache.getOrPut(stored) { SecureStore.decrypt(stored) }
        }
    }

    fun setProviderKey(id: String, value: String) {
        val v = value.trim()
        if (v == providerKey(id)) return // tak berubah: jangan menulis ulang (hindari enkripsi sia-sia)
        sp.edit().putString("${id}_key", SecureStore.encrypt(v)).apply()
    }

    /** Pencarian Google lewat Gemini; hanya berguna untuk kunci berbayar, jadi mati secara bawaan. */
    var aiSearch: Boolean
        get() = sp.getBoolean("ai_search", false)
        set(value) = sp.edit().putBoolean("ai_search", value).apply()

    /** Catatan pemakaian Gemini (JSON), lihat UsageLedger. */
    var aiUsage: String?
        get() = sp.getString("ai_usage", null)
        set(value) = sp.edit().apply { if (value == null) remove("ai_usage") else putString("ai_usage", value) }.apply()

    var geminiKey: String
        get() = providerKey("gemini")
        set(value) = setProviderKey("gemini", value)

    /** Enkripsi ulang rahasia yang masih tersimpan polos dari versi lama. */
    fun migrateSecrets() {
        for (k in listOf("gemini_key", "hf_token")) {
            val v = sp.getString(k, "") ?: ""
            if (v.isNotEmpty() && !SecureStore.isEncrypted(v)) sp.edit().putString(k, SecureStore.encrypt(v)).apply()
        }
        // Fitur yang sudah dihapus (penyedia AI lain, curhat, InBody, pilihan model Gemma): buang sisa pengaturannya,
        // termasuk kunci API yang pernah diisi, supaya tidak tertinggal di HP.
        val retired = sp.all.keys.filter {
            it in RETIRED_KEYS || it.startsWith("ai_models_")
        }
        if (retired.isNotEmpty()) sp.edit().apply { retired.forEach { remove(it) } }.apply()
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

    // --- Profil pengguna (hanya di HP ini; tanggal lahir/pasangan tidak pernah masuk ke instruksi AI online)
    var userName: String
        get() = sp.getString("user_name", "") ?: ""
        set(value) = sp.edit().putString("user_name", cleanName(value)).apply()

    var partnerName: String
        get() = sp.getString("partner_name", "") ?: ""
        set(value) = sp.edit().putString("partner_name", cleanName(value)).apply()

    /** Tanggal lahir "yyyy-MM-dd" atau kosong. */
    private var birthRaw: String
        get() = sp.getString("birth_date", "") ?: ""
        set(value) = sp.edit().putString("birth_date", value).apply()

    fun birthDateOrNull(): java.time.LocalDate? = runCatching { java.time.LocalDate.parse(birthRaw) }.getOrNull()

    fun setBirthDate(d: java.time.LocalDate?) {
        birthRaw = d?.toString() ?: ""
    }

    var profileDone: Boolean
        get() = sp.getBoolean("profile_done", false)
        set(value) = sp.edit().putBoolean("profile_done", value).apply()

    /** Dialog perkenalan (setelah profil pertama kali disimpan) belum ditampilkan. */
    var introPending: Boolean
        get() = sp.getBoolean("intro_pending", false)
        set(value) = sp.edit().putBoolean("intro_pending", value).apply()

    /** Kapan sapaan terakhir ditampilkan (agar tidak menyapa tiap kali layar menyala). */
    var lastGreetAt: Long
        get() = sp.getLong("last_greet", 0L)
        set(value) = sp.edit().putLong("last_greet", value).apply()

    /** Kejutan harian (side quest): hari terakhir diberikan, id-nya, sudah selesai atau belum, dan id yang sudah pernah ditemukan. */
    var questDay: String
        get() = sp.getString("quest_day", "") ?: ""
        set(value) = sp.edit().putString("quest_day", value).apply()

    var questId: String
        get() = sp.getString("quest_id", "") ?: ""
        set(value) = sp.edit().putString("quest_id", value).apply()

    var questDone: Boolean
        get() = sp.getBoolean("quest_done", false)
        set(value) = sp.edit().putBoolean("quest_done", value).apply()

    var questsFound: Set<String>
        get() = (sp.getString("quests_found", "") ?: "").split(',').filter { it.isNotBlank() }.toSet()
        set(value) = sp.edit().putString("quests_found", value.joinToString(",")).apply()

    /** Tahun terakhir ucapan ulang tahun disampaikan (sekali setahun). */
    var birthdayGreetedYear: Int
        get() = sp.getInt("bday_year", 0)
        set(value) = sp.edit().putInt("bday_year", value).apply()

    /** Kapan terakhir pengguna menghina/memarahi Nine (memengaruhi suasana hati sapaan). */
    var lastInsultAt: Long
        get() = sp.getLong("last_insult", 0L)
        set(value) = sp.edit().putLong("last_insult", value).apply()

    // --- Reaksi musik

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
        /**
         * Bersihkan nama/panggilan dari pengguna: hanya huruf, angka, spasi, titik, tanda hubung, dan apostrof.
         * Teks ini ikut ditaruh di instruksi AI, jadi karakter lain (kutip, baris baru, dll.) dibuang.
         */
        fun cleanName(raw: String, max: Int = 24): String =
            raw.filter { it.isLetterOrDigit() || it == ' ' || it == '.' || it == '-' || it == '\'' }
                .replace(Regex("\\s+"), " ").trim().take(max).trim()

        const val DEFAULT_GEMINI_MODEL = "gemini-flash-latest"

        /** Pengaturan milik fitur yang sudah dihapus; dibuang saat aplikasi dibuka. */
        private val RETIRED_KEYS = setOf(
            "groq_key", "cerebras_key", "openrouter_key", "auto_support", "inbody_ocr", "model_variant"
        )
    }
}
