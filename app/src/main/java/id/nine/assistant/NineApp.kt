package id.nine.assistant

import android.app.Application
import android.content.BroadcastReceiver
import android.content.ComponentCallbacks2
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.PowerManager
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.content.ContextCompat
import id.nine.assistant.alarm.AlarmRingService
import id.nine.assistant.alarm.AlarmScheduler
import id.nine.assistant.core.ConversationLog
import id.nine.assistant.core.Perf
import id.nine.assistant.core.Prefs
import id.nine.assistant.llm.LlmBrain
import id.nine.assistant.llm.ModelManager
import id.nine.assistant.llm.GeminiBrain
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
    lateinit var gemini: GeminiBrain
        private set
    /** Otak online: Gemini (tingkat gratis). */
    val online: OnlineBrain get() = gemini
    val log = ConversationLog()
    /** Ruang obrolan khusus (tersimpan) dan penukar isi log antara obrolan utama dan ruang. */
    lateinit var spaces: id.nine.assistant.core.SpaceStore
        private set
    lateinit var spaceSession: id.nine.assistant.core.SpaceSession
        private set
    lateinit var location: id.nine.assistant.core.LocationHelper
        private set
    lateinit var affinity: id.nine.assistant.core.Affinity
        private set

    override fun onCreate() {
        super.onCreate()
        prefs = Prefs(this)
        prefs.migrateSecrets()
        purgeRetiredData()
        location = id.nine.assistant.core.LocationHelper(this, prefs)
        affinity = id.nine.assistant.core.Affinity(this)
        spaces = id.nine.assistant.core.SpaceStore(this)
        spaceSession = id.nine.assistant.core.SpaceSession(spaces, log)
        spaces.activeId = "" // proses baru: selalu mulai dari obrolan utama (log hanya ada di memori)
        location.refresh()
        AppCompatDelegate.setDefaultNightMode(prefs.theme.nightMode)
        models = ModelManager(this, prefs)
        brain = LlmBrain(this, prefs, models)
        // Model Gemma (±2,5 GB) TIDAK dimuat saat proses mulai; ia dimuat saat ada pertanyaan yang butuh AI
        // dan otomatis dilepas bila tak dipakai (2 menit di layar, 30 detik di latar belakang).
        brain.isForeground = { MainActivity.visible }
        gemini = GeminiBrain(this, prefs)
        models.addListener { status ->
            when (status) {
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
            addAction(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED)
        }
        ContextCompat.registerReceiver(this, object : BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) {
                if (i.action == PowerManager.ACTION_POWER_SAVE_MODE_CHANGED) {
                    Perf.saver = getSystemService(PowerManager::class.java)?.isPowerSaveMode == true
                }
                WidgetUpdater.refresh(c)
            }
        }, power, ContextCompat.RECEIVER_NOT_EXPORTED)
        watchHeat()
    }

    /** Data pelatih fitness (fitur sudah dihapus) tidak punya layar lagi: hapus berkasnya dari penyimpanan aplikasi. */
    private fun purgeRetiredData() {
        Thread({
            try {
                for (n in listOf("fit_data.json", "fit_data.json.tmp", "fit_data.bad.json")) java.io.File(filesDir, n).delete()
            } catch (e: Exception) {
                // abaikan
            }
        }, "nine-purge").start()
    }

    /**
     * Pantau Hemat Daya dan suhu HP: saat Hemat Daya menyala atau HP hangat/panas, animasi di layar digambar lebih jarang
     * ([Perf]) dan widget berhenti beranimasi, supaya Nine tidak ikut menambah panas.
     */
    private fun watchHeat() {
        val pm = getSystemService(PowerManager::class.java) ?: return
        Perf.saver = pm.isPowerSaveMode
        if (Build.VERSION.SDK_INT < 29) return
        try {
            Perf.thermal = Perf.thermalLevel(pm.currentThermalStatus)
            pm.addThermalStatusListener(ContextCompat.getMainExecutor(this)) { status ->
                Perf.thermal = Perf.thermalLevel(status)
                WidgetUpdater.refreshFace(this) // hanya menggambar ulang bila mode diam berubah
            }
        } catch (e: Exception) {
            // tanpa pemantau suhu, hanya batas laju biasa yang berlaku
        }
    }

    @Suppress("DEPRECATION")
    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        // Sistem kehabisan memori dan Nine di latar belakang: lepaskan model (±2,5 GB). Dimuat lagi saat dibutuhkan.
        if (level >= ComponentCallbacks2.TRIM_MEMORY_COMPLETE) brain.unload()
        // Memori hampir habis (juga saat Nine di layar): lepas model bila tidak sedang menjawab, supaya HP tidak menyendat.
        else if (level == ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW || level == ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL) brain.trim()
    }
}
