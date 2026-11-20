package id.nine.assistant

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.content.res.Configuration
import android.os.Bundle
import android.provider.Settings
import android.text.format.Formatter
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
import id.nine.assistant.core.Prefs
import id.nine.assistant.databinding.ActivitySettingsBinding
import id.nine.assistant.llm.ModelManager
import id.nine.assistant.skills.CalendarSkill
import id.nine.assistant.speech.SpeechOutput
import id.nine.assistant.wake.WakeWordService

class SettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySettingsBinding
    private lateinit var nine: NineApp
    private lateinit var prefs: Prefs
    private var voice: SpeechOutput? = null
    private var voiceNames: List<String?> = emptyList()
    private var populatingVoices = false

    private val importLauncher =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) nine.models.importFrom(uri)
        }

    private val modelListener = ModelManager.Listener { render(it) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        nine = application as NineApp
        prefs = nine.prefs
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setupInsets()

        binding.btnBack.setOnClickListener { finish() }

        // --- Otak AI
        binding.editToken.setText(prefs.hfToken)
        binding.btnDownload.setOnClickListener {
            prefs.hfToken = binding.editToken.text.toString()
            when (nine.models.status) {
                is ModelManager.Status.Downloading -> nine.models.cancelDownload()
                else -> nine.models.startDownload()
            }
        }
        binding.btnImport.setOnClickListener { importLauncher.launch(arrayOf("*/*")) }
        binding.btnDelete.setOnClickListener {
            AlertDialog.Builder(this)
                .setMessage(R.string.model_delete_confirm)
                .setPositiveButton(R.string.model_delete) { _, _ ->
                    nine.brain.unload()
                    nine.models.delete()
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        }

        // --- Pilihan model Gemma
        binding.groupVariant.check(if (nine.models.variant == ModelManager.Variant.E4B) R.id.variantE4b else R.id.variantE2b)
        binding.groupVariant.setOnCheckedChangeListener { _, id ->
            val v = if (id == R.id.variantE4b) ModelManager.Variant.E4B else ModelManager.Variant.E2B
            if (v != nine.models.variant) {
                nine.brain.unload()
                nine.models.setVariant(v)
            }
        }

        // --- Mode internet
        binding.switchInternet.isChecked = prefs.internetMode
        binding.editGeminiKey.setText(prefs.geminiKey)
        binding.editGeminiModel.setText(if (prefs.geminiModel == Prefs.DEFAULT_GEMINI_MODEL) "" else prefs.geminiModel)
        binding.editCity.setText(prefs.city)
        binding.switchInternet.setOnCheckedChangeListener { _, checked ->
            prefs.geminiKey = binding.editGeminiKey.text.toString()
            prefs.internetMode = checked
            renderInternet()
        }
        binding.btnGetKey.setOnClickListener {
            try {
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://aistudio.google.com/apikey")))
            } catch (e: Exception) {
                // tidak ada browser
            }
        }
        renderInternet()

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

        // --- Lokasi
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
            }
        }

        // --- Bahasa & panggilan
        binding.groupLang.check(if (prefs.lang == AppLang.ID) R.id.langId else R.id.langEn)
        binding.groupLang.setOnCheckedChangeListener { _, id ->
            val l = if (id == R.id.langEn) AppLang.EN else AppLang.ID
            if (l != prefs.lang) {
                prefs.lang = l
                nine.brain.reset()
                populateVoices()
            }
        }
        binding.editHonId.setText(prefs.honorificId)
        binding.editHonEn.setText(prefs.honorificEn)

        // --- Suara
        binding.switchSpeak.isChecked = prefs.speakReplies
        binding.switchSpeak.setOnCheckedChangeListener { _, checked -> prefs.speakReplies = checked }
        voice = SpeechOutput(this, prefs) { Toast.makeText(this, it, Toast.LENGTH_LONG).show() }.also {
            it.setOnReady { populateVoices() }
        }
        binding.spinnerVoice.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                if (populatingVoices) return
                prefs.setVoiceName(prefs.lang, voiceNames.getOrNull(position))
            }

            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
        binding.btnPreview.setOnClickListener {
            val lang = prefs.lang
            val sample = if (lang == AppLang.ID) {
                "Selamat malam${prefs.persona.sfx(lang)}. Semua sistem berjalan normal. Ada yang bisa saya bantu?"
            } else {
                "Good evening${prefs.persona.sfx(lang)}. All systems are running smoothly. How may I assist you?"
            }
            voice?.preview(lang, voiceNames.getOrNull(binding.spinnerVoice.selectedItemPosition), sample)
        }

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

    override fun onResume() {
        super.onResume()
        renderInternet()
        renderWake()
        populateCalendars()
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
        prefs.geminiKey = binding.editGeminiKey.text.toString()
        prefs.geminiModel = binding.editGeminiModel.text.toString()
        prefs.city = binding.editCity.text.toString()
        val phrase = binding.editWakePhrase.text.toString().trim()
        if (phrase.isNotEmpty() && phrase != prefs.wakePhrases) {
            prefs.wakePhrases = phrase
            if (prefs.wakeEnabled) WakeWordService.reload(this)
        }
        val hid = binding.editHonId.text.toString().trim()
        val hen = binding.editHonEn.text.toString().trim()
        if (hid != prefs.honorificId || hen != prefs.honorificEn) {
            prefs.honorificId = hid
            prefs.honorificEn = hen
            nine.brain.reset() // instruksi sistem Gemma memakai panggilan ini
        }
        val token = binding.editToken.text.toString()
        if (token != prefs.hfToken) prefs.hfToken = token
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

    private fun populateVoices() {
        val out = voice ?: return
        val lang = prefs.lang
        val voices = out.voicesFor(lang)
        voiceNames = listOf<String?>(null) + voices.map { it.name }
        val labels = listOf(getString(R.string.voice_auto)) + voices.map { v ->
            val country = v.locale.displayCountry.ifEmpty { v.locale.country }
            "${v.name}  ·  $country"
        }
        populatingVoices = true
        binding.spinnerVoice.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, labels)
        val idx = voiceNames.indexOf(prefs.voiceName(lang)).coerceAtLeast(0)
        binding.spinnerVoice.setSelection(idx, false)
        binding.spinnerVoice.post { populatingVoices = false }
    }

    private fun size(b: Long): String = Formatter.formatShortFileSize(this, b)

    private fun renderInternet() {
        val g = nine.gemini
        binding.txtInternetStatus.setText(
            when {
                !prefs.internetMode -> R.string.internet_status_off
                prefs.geminiKey.isBlank() && binding.editGeminiKey.text.isNullOrBlank() -> R.string.internet_status_nokey
                g.isOnline() -> R.string.internet_status_online
                else -> R.string.internet_status_offline
            }
        )
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
        binding.brainProgress.visibility = View.GONE
        binding.btnDownload.isEnabled = true
        binding.btnImport.isEnabled = true
        binding.btnDelete.visibility = View.GONE
        binding.btnDownload.visibility = View.VISIBLE
        when (s) {
            ModelManager.Status.Ready -> {
                binding.brainStatus.text = getString(R.string.model_ready, nine.models.variant.label, size(nine.models.modelFile.length()))
                binding.brainDetail.text = getString(R.string.privacy_text)
                binding.btnDownload.visibility = View.GONE
                binding.btnDelete.visibility = View.VISIBLE
            }
            ModelManager.Status.Missing -> {
                binding.brainStatus.text = getString(R.string.model_missing)
                binding.brainDetail.text = getString(R.string.model_card_text)
                binding.btnDownload.setText(R.string.model_download)
            }
            is ModelManager.Status.Downloading -> {
                binding.brainStatus.text = getString(R.string.model_downloading, size(s.downloaded), size(s.total))
                binding.brainDetail.text = getString(R.string.model_card_text)
                binding.brainProgress.visibility = View.VISIBLE
                binding.brainProgress.max = 1000
                if (s.total > 0) binding.brainProgress.setProgressCompat((s.downloaded * 1000 / s.total).toInt(), true)
                binding.btnDownload.setText(R.string.model_cancel)
                binding.btnImport.isEnabled = false
            }
            is ModelManager.Status.Importing -> {
                binding.brainStatus.text = getString(R.string.model_importing, size(s.copied), size(s.total))
                binding.brainProgress.visibility = View.VISIBLE
                binding.brainProgress.max = 1000
                if (s.total > 0) binding.brainProgress.setProgressCompat((s.copied * 1000 / s.total).toInt(), true)
                binding.btnDownload.isEnabled = false
                binding.btnImport.isEnabled = false
            }
            is ModelManager.Status.Failed -> {
                binding.brainStatus.text = getString(R.string.model_missing)
                binding.brainDetail.text = when (s.reason) {
                    ModelManager.Reason.NEED_TOKEN -> getString(R.string.model_fail_token)
                    ModelManager.Reason.STORAGE -> getString(R.string.model_fail_storage)
                    ModelManager.Reason.CORRUPT -> getString(R.string.model_fail_corrupt)
                    ModelManager.Reason.IMPORT -> getString(R.string.model_fail_import, s.detail)
                    else -> getString(R.string.model_fail_network, s.detail)
                }
                binding.btnDownload.setText(R.string.model_retry)
            }
        }
    }
}
