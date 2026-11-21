package id.nine.assistant

import android.Manifest
import android.app.NotificationManager
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.content.res.Configuration
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import id.nine.assistant.core.AppLang
import id.nine.assistant.core.MemoryStore
import id.nine.assistant.core.Prefs
import id.nine.assistant.widget.WidgetUpdater
import id.nine.assistant.databinding.ActivitySettingsBinding
import id.nine.assistant.llm.ModelManager
import id.nine.assistant.llm.Providers
import id.nine.assistant.llm.UsageText
import id.nine.assistant.skills.CalendarSkill
import id.nine.assistant.speech.SpeechOutput
import id.nine.assistant.wake.WakeWordService
import id.nine.assistant.a11y.NineAccessibilityService

class SettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySettingsBinding
    private lateinit var nine: NineApp
    private lateinit var prefs: Prefs
    private var voice: SpeechOutput? = null

    private val modelListener = ModelManager.Listener { render(it) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        nine = application as NineApp
        prefs = nine.prefs
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setupInsets()

        binding.btnBack.setOnClickListener { finish() }

        // --- Otak AI (Gemini)
        binding.editGeminiKey.setText(prefs.geminiKey)
        binding.editGeminiModel.setText(if (prefs.geminiModel == Prefs.DEFAULT_GEMINI_MODEL) "" else prefs.geminiModel)
        binding.switchAiSearch.isChecked = prefs.aiSearch
        binding.switchAiSearch.setOnCheckedChangeListener { _, checked -> prefs.aiSearch = checked }
        setAdvanced(prefs.aiSearch || prefs.geminiModel != Prefs.DEFAULT_GEMINI_MODEL)
        binding.btnAdvanced.setOnClickListener { setAdvanced(binding.advancedBox.visibility != View.VISIBLE) }
        binding.btnGetKey.setOnClickListener { openUrl(Providers.GEMINI.keyUrl) }
        binding.btnTestAi.setOnClickListener { testAi() }
        binding.switchInternet.isChecked = prefs.internetMode
        binding.switchInternet.setOnCheckedChangeListener { _, checked ->
            saveAiFields()
            prefs.internetMode = checked
            renderInternet()
            renderUsage()
        }
        renderInternet()

        // --- Pemakaian Gemini
        binding.btnUsageDashboard.setOnClickListener { openUrl(Providers.DASHBOARD_URL) }
        binding.btnUsageReset.setOnClickListener {
            nine.gemini.usage.resetToday()
            renderUsage()
            Toast.makeText(this, R.string.usage_reset_done, Toast.LENGTH_SHORT).show()
        }
        renderUsage()

        // --- Hapus model offline (opsional; unduh dan progres ada di kartu model layar utama)
        binding.btnDeleteModel.setOnClickListener {
            AlertDialog.Builder(this)
                .setMessage(R.string.model_delete_confirm)
                .setPositiveButton(R.string.model_delete) { _, _ ->
                    nine.brain.unload()
                    nine.models.delete()
                }
                .setNegativeButton(R.string.act_cancel, null)
                .show()
        }

        // --- Alarm
        binding.groupAlarmEngine.check(if (prefs.nineAlarms) R.id.alarmEngineNine else R.id.alarmEngineClock)
        binding.groupAlarmEngine.setOnCheckedChangeListener { _, id ->
            prefs.alarmEngine = if (id == R.id.alarmEngineClock) "clock" else "nine"
            renderAlarms()
        }
        binding.btnManageAlarms.setOnClickListener { startActivity(Intent(this, AlarmsActivity::class.java)) }
        binding.btnFullScreen.setOnClickListener {
            if (Build.VERSION.SDK_INT >= 34) {
                try {
                    startActivity(Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT, Uri.parse("package:$packageName")))
                } catch (e: Exception) {
                    // abaikan
                }
            }
        }
        renderAlarms()

        // --- Wake word
        binding.switchWake.isChecked = prefs.wakeEnabled
        binding.editWakePhrase.setText(prefs.wakePhrases)
        binding.switchWake.setOnCheckedChangeListener { _, checked -> setWake(checked) }
        binding.btnOverlay.setOnClickListener {
            try {
                startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
            } catch (e: Exception) {
                startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION))
            }
        }
        renderWake()

        // --- Reaksi musik, layanan aksesibilitas
        binding.switchMusic.isChecked = prefs.musicReact
        binding.switchMusic.setOnCheckedChangeListener { _, checked -> prefs.musicReact = checked }
        binding.btnA11y.setOnClickListener {
            try {
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            } catch (e: Exception) {
                // abaikan
            }
        }
        binding.btnAppInfo.setOnClickListener {
            try {
                startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
            } catch (e: Exception) {
                // abaikan
            }
        }
        renderA11y()

        // --- Lokasi
        binding.editCity.setText(prefs.city)
        binding.switchLocation.isChecked = prefs.useLocation && nine.location.hasPermission()
        binding.switchLocation.setOnCheckedChangeListener { _, checked ->
            if (checked && !nine.location.hasPermission()) {
                locPermission.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
            } else {
                prefs.useLocation = checked
                if (checked) nine.location.refresh { renderLocation() }
                renderLocation()
            }
        }
        renderLocation()

        // --- Kalender tujuan
        binding.spinnerCalendar.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                if (populatingCalendars) return
                prefs.calendarId = calendarIds.getOrElse(position) { -1L }
            }

            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
        binding.btnCalendarAccess.setOnClickListener {
            calPermission.launch(arrayOf(Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR))
        }
        populateCalendars()

        // --- Tema
        binding.groupTheme.check(
            when (prefs.theme) {
                Prefs.Theme.SYSTEM -> R.id.themeSystem
                Prefs.Theme.LIGHT -> R.id.themeLight
                Prefs.Theme.DARK -> R.id.themeDark
            }
        )
        binding.groupTheme.setOnCheckedChangeListener { _, id ->
            val t = when (id) {
                R.id.themeLight -> Prefs.Theme.LIGHT
                R.id.themeDark -> Prefs.Theme.DARK
                else -> Prefs.Theme.SYSTEM
            }
            if (t != prefs.theme) {
                prefs.theme = t
                AppCompatDelegate.setDefaultNightMode(t.nightMode)
                WidgetUpdater.refresh(this) // warna widget ikut tema Nine
            }
        }

        // --- Bahasa & panggilan
        binding.groupLang.check(if (prefs.lang == AppLang.ID) R.id.langId else R.id.langEn)
        binding.groupLang.setOnCheckedChangeListener { _, id ->
            val l = if (id == R.id.langEn) AppLang.EN else AppLang.ID
            if (l != prefs.lang) {
                prefs.lang = l
                nine.brain.reset()
            }
        }
        binding.btnProfile.setOnClickListener { startActivity(Intent(this, ProfileActivity::class.java)) }

        // --- Suara
        binding.switchSpeak.isChecked = prefs.speakReplies
        binding.switchSpeak.setOnCheckedChangeListener { _, checked -> prefs.speakReplies = checked }
        voice = SpeechOutput(this, prefs) { Toast.makeText(this, it, Toast.LENGTH_LONG).show() }
        binding.btnPreview.setOnClickListener {
            val lang = prefs.lang
            val sample = lang.t("Halo${prefs.persona.sfx(lang)}. Semua sistem berjalan normal?", "Hello${prefs.persona.sfx(lang)}. All systems running normally?")
            voice?.speak(sample, lang) {}
        }

        // --- Memori
        binding.switchMemory.isChecked = prefs.autoMemory
        binding.switchMemory.setOnCheckedChangeListener { _, checked -> prefs.autoMemory = checked }
        binding.btnMemoryManage.setOnClickListener { showMemoryDialog() }
        binding.btnMemoryClear.setOnClickListener { confirmClearMemory() }
        renderMemory()

        // --- Asisten default
        binding.btnDefaultAssistant.setOnClickListener {
            val intents = listOf(
                Intent(Settings.ACTION_VOICE_INPUT_SETTINGS),
                Intent(Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS),
                Intent(Settings.ACTION_SETTINGS)
            )
            for (i in intents) {
                try {
                    startActivity(i)
                    break
                } catch (e: Exception) {
                    // coba berikutnya
                }
            }
        }

        binding.txtVersion.text = "Nine " + try {
            packageManager.getPackageInfo(packageName, 0).versionName
        } catch (e: Exception) {
            ""
        }

        nine.models.addListener(modelListener)
    }

    private fun renderA11y() {
        if (!NineAccessibilityService.AVAILABLE) {
            // Edisi ringan (Nine.apk): tanpa aksesibilitas supaya lolos Play Protect.
            binding.a11ySection.visibility = View.GONE
            return
        }
        val on = NineAccessibilityService.instance != null || NineAccessibilityService.isEnabled(this)
        binding.txtA11yStatus.setText(if (on) R.string.a11y_status_on else R.string.a11y_status_off)
    }

    override fun onResume() {
        super.onResume()
        renderA11y()
        renderInternet()
        renderUsage()
        renderWake()
        renderAlarms()
        populateCalendars()
    }

    private fun openUrl(url: String) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        } catch (e: Exception) {
            // tidak ada browser
        }
    }

    private fun setAdvanced(show: Boolean) {
        binding.advancedBox.visibility = if (show) View.VISIBLE else View.GONE
        binding.btnAdvanced.setText(if (show) R.string.ai_advanced_hide else R.string.ai_advanced_show)
    }

    private fun saveAiFields() {
        prefs.geminiKey = binding.editGeminiKey.text?.toString().orEmpty()
        prefs.geminiModel = binding.editGeminiModel.text?.toString().orEmpty()
    }

    /** Uji kunci Gemini dan tampilkan model yang dipakai atau penyebab gagalnya. */
    private fun testAi() {
        saveAiFields()
        binding.txtAiTest.visibility = View.VISIBLE
        if (nine.gemini.configs().isEmpty()) {
            binding.txtAiTest.setText(R.string.ai_test_nokey)
            return
        }
        binding.btnTestAi.isEnabled = false
        binding.txtAiTest.setText(R.string.ai_testing)
        val lines = ArrayList<String>()
        nine.gemini.test(
            prefs.lang,
            onLine = { line ->
                lines.add(line)
                if (!isFinishing && !isDestroyed) binding.txtAiTest.text = lines.joinToString("\n")
            },
            onDone = {
                if (!isFinishing && !isDestroyed) {
                    binding.btnTestAi.isEnabled = true
                    renderInternet()
                    renderUsage()
                }
            }
        )
    }

    private fun renderAlarms() {
        binding.btnManageAlarms.visibility = if (prefs.nineAlarms) View.VISIBLE else View.GONE
        val canFull = if (Build.VERSION.SDK_INT >= 34) {
            getSystemService(NotificationManager::class.java).canUseFullScreenIntent()
        } else true
        binding.btnFullScreen.visibility = if (prefs.nineAlarms) View.VISIBLE else View.GONE
        binding.btnFullScreen.isEnabled = !canFull
        binding.btnFullScreen.setText(if (canFull) R.string.alarm_fullscreen_ok else R.string.alarm_fullscreen)
    }

    private val locPermission =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
            val ok = result.values.any { it }
            prefs.useLocation = ok
            binding.switchLocation.isChecked = ok
            if (ok) nine.location.refresh { renderLocation() }
            renderLocation()
        }

    private fun renderLocation() {
        binding.txtLocationStatus.text = if (prefs.useLocation && nine.location.hasPermission()) {
            nine.location.describe()?.let { getString(R.string.location_status_fmt, it) } ?: getString(R.string.location_status_none)
        } else ""
    }

    private var calendarIds: List<Long> = listOf(-1L)
    private var populatingCalendars = false

    private val calPermission =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { populateCalendars() }

    private fun populateCalendars() {
        val granted = listOf(Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR).all {
            ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
        }
        binding.btnCalendarAccess.visibility = if (granted) View.GONE else View.VISIBLE
        val cals = if (granted) {
            try {
                CalendarSkill(this).writableCalendars()
            } catch (e: Exception) {
                emptyList()
            }
        } else emptyList()
        calendarIds = listOf(-1L) + cals.map { it.id }
        val labels = listOf(getString(R.string.calendar_auto)) + cals.map { it.label }
        populatingCalendars = true
        binding.spinnerCalendar.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, labels)
        binding.spinnerCalendar.setSelection(calendarIds.indexOf(prefs.calendarId).coerceAtLeast(0), false)
        binding.spinnerCalendar.post { populatingCalendars = false }
    }

    override fun onPause() {
        super.onPause()
        saveAiFields()
        prefs.city = binding.editCity.text.toString()
        val phrase = binding.editWakePhrase.text.toString().trim()
        if (phrase.isNotEmpty() && phrase != prefs.wakePhrases) {
            prefs.wakePhrases = phrase
            if (prefs.wakeEnabled) WakeWordService.reload(this)
        }
        voice?.stop()
    }

    override fun onDestroy() {
        super.onDestroy()
        nine.models.removeListener(modelListener)
        voice?.shutdown()
        voice = null
    }

    private fun setupInsets() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        val dark = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
        WindowCompat.getInsetsController(window, binding.root).apply {
            isAppearanceLightStatusBars = !dark
            isAppearanceLightNavigationBars = !dark
        }
        ViewCompat.setOnApplyWindowInsetsListener(binding.settingsRoot) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.ime())
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            WindowInsetsCompat.CONSUMED
        }
    }

    private val memory by lazy { MemoryStore(this) }

    private fun renderMemory() {
        binding.txtMemoryCount.text = getString(R.string.memory_count, memory.count())
    }

    /** Daftar ingatan: centang yang ingin dihapus. */
    private fun showMemoryDialog() {
        val facts = memory.all()
        if (facts.isEmpty()) {
            Toast.makeText(this, R.string.memory_empty, Toast.LENGTH_SHORT).show()
            return
        }
        val items = facts.map { it.text }.toTypedArray()
        val checked = BooleanArray(items.size)
        AlertDialog.Builder(this)
            .setTitle(R.string.memory_manage)
            .setMultiChoiceItems(items, checked) { _, which, isChecked -> checked[which] = isChecked }
            .setPositiveButton(R.string.memory_delete_selected) { _, _ ->
                val picked = checked.indices.filter { checked[it] }
                if (picked.isNotEmpty()) {
                    memory.removeAt(picked)
                    nine.brain.reset() // percakapan AI yang berjalan masih memuat ingatan lama
                    renderMemory()
                }
            }
            .setNegativeButton(R.string.act_cancel, null)
            .show()
    }

    private fun confirmClearMemory() {
        if (memory.count() == 0) {
            Toast.makeText(this, R.string.memory_empty, Toast.LENGTH_SHORT).show()
            return
        }
        AlertDialog.Builder(this)
            .setMessage(R.string.memory_clear_confirm)
            .setPositiveButton(R.string.memory_clear) { _, _ ->
                memory.clear()
                nine.brain.reset()
                renderMemory()
            }
            .setNegativeButton(R.string.act_cancel, null)
            .show()
    }

    private fun renderInternet() {
        val on = prefs.internetMode
        binding.aiFields.visibility = if (on) View.VISIBLE else View.GONE
        val hasKey = !binding.editGeminiKey.text.isNullOrBlank()
        binding.txtInternetStatus.text = when {
            !on -> getString(R.string.internet_status_off)
            !hasKey -> getString(R.string.internet_status_nokey)
            nine.gemini.isOnline() -> getString(R.string.internet_status_online, nine.gemini.displayName)
            else -> getString(R.string.internet_status_offline)
        }
    }

    /** Kartu pemakaian: tampil bila Mode internet menyala dan ada kunci atau catatan pemakaian. */
    private fun renderUsage() {
        val snap = nine.gemini.usage.snapshot()
        val hasKey = !binding.editGeminiKey.text.isNullOrBlank()
        binding.usageCard.visibility = if (prefs.internetMode && (hasKey || !snap.isEmpty)) View.VISIBLE else View.GONE
        binding.txtUsage.text = UsageText.format(snap, nine.gemini.cooling(), prefs.lang, System.currentTimeMillis())
    }

    private val notifPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { _ -> renderWake() }

    private val micPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) setWake(true) else binding.switchWake.isChecked = false
        }

    private fun setWake(on: Boolean) {
        if (on) {
            val phrase = binding.editWakePhrase.text.toString().trim()
            if (WakeWordService.parsePhrases(phrase).isEmpty()) {
                Toast.makeText(this, R.string.wake_no_phrase, Toast.LENGTH_LONG).show()
                binding.switchWake.isChecked = false
                return
            }
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                micPermission.launch(Manifest.permission.RECORD_AUDIO)
                return
            }
            if (Build.VERSION.SDK_INT >= 33 &&
                ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
            ) {
                notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
            prefs.wakePhrases = phrase
            prefs.wakeEnabled = true
            WakeWordService.start(this)
            // Layar Pengaturan sedang terbuka; wake word akan aktif saat Nine ditutup.
        } else {
            prefs.wakeEnabled = false
            WakeWordService.stop(this)
        }
        renderWake()
    }

    private fun renderWake() {
        val overlay = Build.VERSION.SDK_INT < 23 || Settings.canDrawOverlays(this)
        binding.btnOverlay.isEnabled = !overlay
        binding.btnOverlay.text = getString(if (overlay) R.string.wake_overlay_granted else R.string.wake_overlay_button)
        val phrases = WakeWordService.parsePhrases(prefs.wakePhrases).joinToString("\", \"")
        binding.txtWakeStatus.text = if (prefs.wakeEnabled) {
            WakeWordService.status.ifEmpty { getString(R.string.wake_status_on, phrases) }
        } else {
            getString(R.string.wake_status_off)
        }
    }

    private fun render(s: ModelManager.Status) {
        binding.btnDeleteModel.visibility = if (s == ModelManager.Status.Ready) View.VISIBLE else View.GONE
    }
}
