package id.nine.assistant

import android.app.Application
import android.content.ComponentCallbacks2
import androidx.appcompat.app.AppCompatDelegate
import id.nine.assistant.core.ConversationLog
import id.nine.assistant.core.Prefs
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
    val log = ConversationLog()

    override fun onCreate() {
        super.onCreate()
        prefs = Prefs(this)
        AppCompatDelegate.setDefaultNightMode(prefs.theme.nightMode)
        models = ModelManager(this, prefs)
        brain = LlmBrain(this, prefs, models)
        models.addListener { status ->
            if (status is ModelManager.Status.Ready) brain.preload()
        }
    }

    @Suppress("DEPRECATION")
    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        // Sistem kehabisan memori dan Nine di latar belakang: lepaskan model (±2,5 GB). Dimuat lagi saat dibutuhkan.
        if (level >= ComponentCallbacks2.TRIM_MEMORY_COMPLETE) brain.unload()
    }
}
