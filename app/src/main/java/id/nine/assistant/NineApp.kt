package id.nine.assistant

import android.app.Application
import android.content.ComponentCallbacks2
import androidx.appcompat.app.AppCompatDelegate
import id.nine.assistant.core.ConversationLog
import id.nine.assistant.core.Prefs
import id.nine.assistant.llm.GeminiClient
import id.nine.assistant.llm.LlmBrain
import id.nine.assistant.llm.ModelManager

/** Objek yang hidup sepanjang aplikasi: model Gemma tidak dimuat ulang saat tema berganti/layar diputar. */
class NineApp : Application() {

    lateinit var prefs: Prefs
        private set
    lateinit var models: ModelManager
        private set
    lateinit var brain: LlmBrain
        private set
    lateinit var gemini: GeminiClient
        private set
    val log = ConversationLog()
    lateinit var location: id.nine.assistant.core.LocationHelper
        private set
    lateinit var affinity: id.nine.assistant.core.Affinity
        private set

    override fun onCreate() {
        super.onCreate()
        prefs = Prefs(this)
        prefs.migrateSecrets()
        location = id.nine.assistant.core.LocationHelper(this, prefs)
        affinity = id.nine.assistant.core.Affinity(this)
        location.refresh()
        AppCompatDelegate.setDefaultNightMode(prefs.theme.nightMode)
        models = ModelManager(this, prefs)
        brain = LlmBrain(this, prefs, models)
        gemini = GeminiClient(this, prefs)
        models.addListener { status ->
            when (status) {
                is ModelManager.Status.Ready -> brain.preload()
                ModelManager.Status.Missing -> brain.unload()
                else -> Unit
            }
        }
        // Wake word: hidupkan lagi layanan bila sebelumnya aktif (mis. setelah aplikasi dibuka ulang).
        if (prefs.wakeEnabled) id.nine.assistant.wake.WakeWordService.start(this)
    }

    @Suppress("DEPRECATION")
    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        // Sistem kehabisan memori dan Nine di latar belakang: lepaskan model (±2,5 GB). Dimuat lagi saat dibutuhkan.
        if (level >= ComponentCallbacks2.TRIM_MEMORY_COMPLETE) brain.unload()
    }
}
