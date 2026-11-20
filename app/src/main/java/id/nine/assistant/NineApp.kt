package id.nine.assistant

import android.app.Application
import android.content.BroadcastReceiver
import android.content.ComponentCallbacks2
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.content.ContextCompat
import id.nine.assistant.alarm.AlarmRingService
import id.nine.assistant.alarm.AlarmScheduler
import id.nine.assistant.core.ConversationLog
import id.nine.assistant.core.Prefs
import id.nine.assistant.llm.ClaudeClient
import id.nine.assistant.llm.GeminiClient
import id.nine.assistant.llm.LlmBrain
import id.nine.assistant.llm.ModelManager
import id.nine.assistant.llm.OnlineBrain
import id.nine.assistant.widget.WidgetUpdater

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
    lateinit var claude: ClaudeClient
        private set

    /** Otak online yang dipilih pengguna (Gemini atau Claude). */
    val online: OnlineBrain get() = if (prefs.onlineProvider == "claude") claude else gemini
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
        claude = ClaudeClient(this, prefs)
        models.addListener { status ->
            when (status) {
                is ModelManager.Status.Ready -> brain.preload()
                ModelManager.Status.Missing -> brain.unload()
                else -> Unit
            }
        }
        // Wake word: hidupkan lagi layanan bila sebelumnya aktif (mis. setelah aplikasi dibuka ulang).
        if (prefs.wakeEnabled) id.nine.assistant.wake.WakeWordService.start(this)
        // Alarm Nine: pastikan semua terjadwal (murah; aman dipanggil berulang).
        AlarmRingService.createChannel(this)
        try {
            AlarmScheduler.rescheduleAll(this)
        } catch (e: Exception) {
            // abaikan
        }
        // Wajah di widget ikut berubah saat HP dicas / baterai lemah (selama proses Nine hidup).
        val power = IntentFilter().apply {
            addAction(Intent.ACTION_POWER_CONNECTED)
            addAction(Intent.ACTION_POWER_DISCONNECTED)
            addAction(Intent.ACTION_BATTERY_LOW)
            addAction(Intent.ACTION_BATTERY_OKAY)
        }
        ContextCompat.registerReceiver(this, object : BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) {
                WidgetUpdater.refresh(c)
            }
        }, power, ContextCompat.RECEIVER_NOT_EXPORTED)
    }

    @Suppress("DEPRECATION")
    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        // Sistem kehabisan memori dan Nine di latar belakang: lepaskan model (±2,5 GB). Dimuat lagi saat dibutuhkan.
        if (level >= ComponentCallbacks2.TRIM_MEMORY_COMPLETE) brain.unload()
    }
}
