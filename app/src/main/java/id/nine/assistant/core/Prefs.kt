package id.nine.assistant.core

import android.content.Context
import androidx.appcompat.app.AppCompatDelegate
import java.util.Locale

/** Pengaturan Nine, disimpan lokal di SharedPreferences. */
class Prefs(context: Context) {
    private val sp = context.applicationContext.getSharedPreferences("nine_prefs", Context.MODE_PRIVATE)

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

    var honorificId: String
        get() = sp.getString("hon_id", "Tuan") ?: "Tuan"
        set(value) = sp.edit().putString("hon_id", value.trim()).apply()

    var honorificEn: String
        get() = sp.getString("hon_en", "sir") ?: "sir"
        set(value) = sp.edit().putString("hon_en", value.trim()).apply()

    /** Nama suara TTS pilihan pengguna per bahasa; null = pilih otomatis. */
    fun voiceName(lang: AppLang): String? = sp.getString("voice_" + lang.name, null)

    fun setVoiceName(lang: AppLang, name: String?) {
        sp.edit().apply { if (name == null) remove("voice_" + lang.name) else putString("voice_" + lang.name, name) }.apply()
    }

    /** Token Hugging Face (opsional) bila model memerlukan login. */
    var hfToken: String
        get() = sp.getString("hf_token", "") ?: ""
        set(value) = sp.edit().putString("hf_token", value.trim()).apply()

    /** "gpu" atau "cpu": backend yang terakhir terbukti berjalan. */
    var llmBackend: String
        get() = sp.getString("llm_backend", "gpu") ?: "gpu"
        set(value) = sp.edit().putString("llm_backend", value).apply()

    /** Id unduhan DownloadManager yang sedang berjalan, -1 bila tidak ada. */
    var downloadId: Long
        get() = sp.getLong("dl_id", -1L)
        set(value) = sp.edit().putLong("dl_id", value).apply()

    val persona: Persona get() = Persona(honorificId, honorificEn)
}
