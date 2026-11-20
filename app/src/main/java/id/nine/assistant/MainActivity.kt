package id.nine.assistant

import android.app.KeyguardManager
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.widget.TextView
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.button.MaterialButtonToggleGroup
import android.Manifest
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import id.nine.assistant.a11y.NineAccessibilityService
import id.nine.assistant.audio.GuitarTuner
import id.nine.assistant.audio.GuitarTuning
import id.nine.assistant.audio.MusicReactor
import id.nine.assistant.core.Affinity
import id.nine.assistant.core.DailyQuota
import id.nine.assistant.core.UiAction
import id.nine.assistant.core.Mood
import id.nine.assistant.recorder.RecorderService
import android.text.format.Formatter
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.app.NotificationManagerCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.recyclerview.widget.LinearLayoutManager
import id.nine.assistant.core.AppLang
import id.nine.assistant.core.AssistantController
import id.nine.assistant.core.ConversationLog
import id.nine.assistant.core.Prefs
import id.nine.assistant.databinding.ActivityMainBinding
import id.nine.assistant.llm.LlmBrain
import id.nine.assistant.llm.ModelManager
import id.nine.assistant.ui.MessageAdapter
import id.nine.assistant.ui.NineFaceView
import id.nine.assistant.wake.WakeWordService
import id.nine.assistant.widget.WidgetUpdater

open class MainActivity : AppCompatActivity(), AssistantController.Ui {

    protected lateinit var binding: ActivityMainBinding
    protected lateinit var controller: AssistantController
    protected lateinit var nine: NineApp
    protected lateinit var adapter: MessageAdapter
    private var modelCardDismissed = false

    // --- tuner gitar & reaksi musik (keduanya memakai mikrofon, hanya saat Nine diam dan layarnya terbuka)
    private var tuner: GuitarTuner? = null
    private var tunerActive = false
    private var reactor: MusicReactor? = null

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
            controller.onPermissionsResult(result)
        }

    private val importLauncher =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) nine.models.importFrom(uri)
        }

    private val logListener = object : ConversationLog.Listener {
        override fun onAdded(index: Int) {
            adapter.onLogAdded(index)
            scrollToLatest()
        }

        override fun onChanged(index: Int) {
            adapter.onLogChanged(index)
            scrollToLatest()
        }

        override fun onRemoved(index: Int) {
            adapter.onLogRemoved(index)
        }

        override fun onCleared() {
            adapter.onLogCleared()
        }
    }

    private fun scrollToLatest() {
        val last = adapter.itemCount - 1
        if (last >= 0) binding.list.scrollToPosition(last)
    }

    private val modelListener = ModelManager.Listener { renderModel(it) }

    private var lastBrainState = LlmBrain.State.NO_MODEL
    private var lastBrainDetail = ""

    /** Status otak AI (ditampilkan di menu NINE). */
    private fun brainText(): String {
        val online = nine.online
        if (online.isUsable()) return getString(R.string.brain_online, online.displayName)
        return when (lastBrainState) {
            LlmBrain.State.NO_MODEL -> getString(R.string.brain_none)
            LlmBrain.State.IDLE -> getString(R.string.brain_idle)
            LlmBrain.State.LOADING -> getString(R.string.brain_loading)
            LlmBrain.State.READY, LlmBrain.State.BUSY -> getString(R.string.brain_ready, lastBrainDetail.uppercase())
            LlmBrain.State.ERROR -> getString(R.string.brain_error)
        }
    }

    private var menuBrain: TextView? = null

    private fun renderBrain() {
        menuBrain?.text = brainText()
    }

    private val brainListener = object : LlmBrain.StateListener {
        override fun onBrainState(state: LlmBrain.State, detail: String) {
            lastBrainState = state
            if (state != LlmBrain.State.ERROR) lastBrainDetail = detail
            renderBrain()
        }

    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        nine = application as NineApp
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setupInsets()

        adapter = MessageAdapter(nine.log)
        binding.list.layoutManager = LinearLayoutManager(this).apply { stackFromEnd = true }
        binding.list.adapter = adapter
        binding.list.itemAnimator = null

        controller = AssistantController(this, this)
        onLanguageChanged(controller.lang)
        onState(AssistantController.State.IDLE)

        if (nine.log.entries.isEmpty()) nine.log.add(false, welcomeText())

        binding.orb.setOnClickListener {
            if (RecorderService.recording) {
                controller.stopRecording()
                return@setOnClickListener
            }
            when (controller.state) {
                AssistantController.State.LISTENING -> controller.stopListening()
                AssistantController.State.SPEAKING, AssistantController.State.THINKING -> controller.cancel()
                AssistantController.State.IDLE -> {
                    hideKeyboard()
                    controller.startListening()
                }
            }
        }
        binding.orb.setOnLongClickListener {
            toggleKeyboard()
            true
        }
        binding.btnKeyboard.setOnClickListener { toggleKeyboard() }
        binding.btnTodo.setOnClickListener { startActivity(Intent(this, TodoActivity::class.java)) }
        binding.txtTitle.setOnClickListener { showMenu() }
        binding.txtTitle.setOnLongClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
            true
        }
        binding.btnSend.setOnClickListener { sendTyped() }
        binding.inputText.setOnEditorActionListener { _, actionId, event ->
            val enter = event != null && event.keyCode == android.view.KeyEvent.KEYCODE_ENTER && event.action == android.view.KeyEvent.ACTION_DOWN
            if (enter || actionId == EditorInfo.IME_ACTION_SEND || actionId == EditorInfo.IME_ACTION_DONE || actionId == EditorInfo.IME_ACTION_GO) {
                sendTyped()
                true
            } else false
        }

        binding.orb.onPetBurst = { x, y -> petBurst(x, y) }
        binding.tunerView.onChoose = { n -> chooseTunerString(n) }
        binding.tunerView.onClose = { stopTuner() }

        binding.btnModelPrimary.setOnClickListener { onModelPrimary() }
        binding.btnModelSecondary.setOnClickListener { onModelSecondary() }

        nine.log.addListener(logListener)
        nine.models.addListener(modelListener)
        nine.brain.addListener(brainListener)
        WidgetUpdater.refreshFace(this)

        handleLaunchIntent(intent)
    }

    private fun welcomeText(): String {
        val p = nine.prefs.persona
        return if (controller.lang == AppLang.ID) {
            "Selamat datang${p.sfx(AppLang.ID)}. Saya Nine, asisten pribadi Anda yang bekerja sepenuhnya di perangkat ini. " +
                "Ketuk orb dan ucapkan perintah, misalnya \"pasang alarm jam 6 pagi\", atau ajak saya mengobrol."
        } else {
            "Welcome${p.sfx(AppLang.EN)}. I'm Nine, your personal assistant, running entirely on this device. " +
                "Tap the orb and say something like \"set an alarm for 6 AM\", or simply chat with me."
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleLaunchIntent(intent)
    }

    /** Dibuka lewat tombol asisten (ACTION_ASSIST / sesi asisten): langsung mendengarkan. */
    private fun handleLaunchIntent(i: Intent?) {
        if (i == null) return
        // Hanya intent dari Nine sendiri (token rahasia) atau tombol asisten sistem yang boleh langsung membuka mikrofon.
        val trusted = i.getStringExtra(EXTRA_TOKEN) == nine.prefs.launchToken
        val auto = (i.getBooleanExtra(EXTRA_AUTO_LISTEN, false) && trusted) || i.action == Intent.ACTION_ASSIST
        if (auto) {
            // Dipanggil dari tombol samping / wake word: boleh tampil di atas layar kunci.
            if (Build.VERSION.SDK_INT >= 27) {
                setShowWhenLocked(true)
                setTurnScreenOn(true)
            }
            i.removeExtra(EXTRA_AUTO_LISTEN)
            binding.root.post { controller.startListening() }
        }
    }

    override fun onResume() {
        super.onResume()
        visible = true
        onLanguageChanged(controller.lang)
        renderBrain()
        // Layar Nine terbuka: hapus notifikasi "ketuk untuk bicara" dan lepaskan mikrofon dari wake word.
        NotificationManagerCompat.from(this).cancel(WakeWordService.ALERT_ID)
        if (nine.prefs.wakeEnabled && !RecorderService.recording) WakeWordService.pause(this)
        renderRecording()
        NineAccessibilityService.applyTouch(this)
        syncAudio()
    }

    override fun onStop() {
        super.onStop()
        visible = false
        controller.onBackground()
        syncAudio() // melepas mikrofon (tuner & reaksi musik)
        binding.hearts.clear()
        if (nine.prefs.wakeEnabled && !RecorderService.recording) WakeWordService.resume(this)
    }

    override fun onDestroy() {
        super.onDestroy()
        nine.log.removeListener(logListener)
        nine.models.removeListener(modelListener)
        nine.brain.removeListener(brainListener)
        ticker.removeCallbacks(syncRetry)
        tuner?.stop()
        reactor?.stop()
        controller.destroy()
    }

    // ------------------------------------------------------------ muncratan hati, tuner, reaksi musik

    private fun hasMic() =
        ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    /** Mengusap orb 5 detik: hati bermuncratan (maks. 3 kali sehari). */
    private fun petBurst(x: Float, y: Float) {
        val p = nine.prefs
        val today = java.time.LocalDate.now().toString()
        val used = DailyQuota.tryUse(today, p.heartDay, p.heartCount, HEART_MAX_PER_DAY)
        val l = controller.lang
        if (used == null) {
            binding.orb.react(Mood.HAPPY, 1600)
            Toast.makeText(
                this,
                l.t("Jatah hati hari ini sudah habis ($HEART_MAX_PER_DAY kali sehari). Besok lagi ya.", "No hearts left for today ($HEART_MAX_PER_DAY a day). Come back tomorrow."),
                Toast.LENGTH_SHORT
            ).show()
            return
        }
        p.heartDay = used.first
        p.heartCount = used.second
        binding.hearts.burst(x, y)
        binding.orb.react(Mood.LOVE, 3600)
    }

    /** Percobaan ulang pendek bila mikrofon belum bebas (mis. wake word baru saja dilepas). */
    private val syncRetry = Runnable { syncAudio() }
    private var tunerRetries = 0

    /** Sesuaikan mikrofon: tuner dan reaksi musik hanya berjalan saat layar terbuka dan Nine sedang diam. */
    private fun syncAudio() {
        if (!::controller.isInitialized) return
        ticker.removeCallbacks(syncRetry)
        val can = visible && controller.state == AssistantController.State.IDLE && hasMic() && !RecorderService.recording
        // tuner
        val t = tuner
        if (tunerActive && t != null) {
            if (can) {
                if (!t.isRunning) {
                    if (t.start()) {
                        tunerRetries = 0
                    } else if (tunerRetries < 4) {
                        tunerRetries++
                        ticker.postDelayed(syncRetry, 700)
                    } else {
                        tunerRetries = 0
                        Toast.makeText(
                            this,
                            controller.lang.t("Mikrofon sedang dipakai aplikasi lain; tuner dihentikan.", "The microphone is busy; tuner stopped."),
                            Toast.LENGTH_LONG
                        ).show()
                        stopTuner()
                    }
                }
            } else {
                t.stop()
            }
        }
        // musik
        val wantMusic = nine.prefs.musicReact && !tunerActive && can
        if (wantMusic) {
            val r = reactor ?: MusicReactor(this) { f ->
                if (reactor?.isRunning != true) return@MusicReactor // sisa bingkai setelah dihentikan
                binding.orb.setMusic(f)
                binding.edgeGlow.setMusic(f)
                if (f.music != musicHeard) {
                    musicHeard = f.music
                    renderMusicStatus()
                }
            }.also { reactor = it }
            if (r.isRunning) {
                musicRetries = 0
            } else if (!r.start()) {
                if (musicRetries < 4) {
                    musicRetries++
                    ticker.postDelayed(syncRetry, 1500)
                } else if (!musicWarned) {
                    musicWarned = true
                    Toast.makeText(
                        this,
                        controller.lang.t("Mikrofon sedang dipakai aplikasi lain; reaksi musik belum bisa.", "The microphone is busy; music reaction can't start yet."),
                        Toast.LENGTH_LONG
                    ).show()
                }
            } else {
                musicRetries = 0
                musicWarned = false
            }
        } else {
            reactor?.stop()
            musicRetries = 0
            binding.orb.clearMusic()
            binding.edgeGlow.clearMusic()
            if (musicHeard) {
                musicHeard = false
                renderMusicStatus()
            }
        }
    }

    // Indikator beta: teks status berubah saat Nine benar-benar mendengar musik (membantu memastikan mikrofon bekerja).
    private var musicHeard = false
    private var musicRetries = 0
    private var musicWarned = false

    private fun renderMusicStatus() {
        if (!::controller.isInitialized || controller.state != AssistantController.State.IDLE) return
        binding.txtStatus.setText(if (musicHeard) R.string.status_music else R.string.status_idle)
    }

    private val tunerListener = object : GuitarTuner.Listener {
        override fun onReading(reading: GuitarTuner.Reading) {
            binding.tunerView.setReading(reading)
        }

        override fun onSettled(reading: GuitarTuner.Reading) {
            val l = controller.lang
            binding.tunerView.setHint(GuitarTuning.advice(reading.string, reading.cents, l))
            binding.orb.react(if (reading.inTune) Mood.HAPPY else Mood.CURIOUS, if (reading.inTune) 2000 else 1200)
        }

        override fun onSilence() {
            binding.tunerView.setReading(null)
        }

        override fun onUnrecognized() {
            val l = controller.lang
            binding.tunerView.setHint(l.t("Nadanya belum terbaca; petik satu senar saja.", "Can't read that; pluck a single string."))
        }
    }

    private fun startTuner(string: Int?) {
        val t = tuner ?: GuitarTuner(this, tunerListener).also { tuner = it }
        t.target = string
        tunerActive = true
        binding.tunerView.visibility = View.VISIBLE
        binding.tunerView.setSelected(string)
        binding.tunerView.setReading(null)
        binding.tunerView.setHint(
            if (string != null) GuitarTuning.byNumber(string)?.let { controller.lang.t("Petik ${GuitarTuning.label(it, controller.lang)}", "Pluck ${GuitarTuning.label(it, controller.lang)}") }.orEmpty()
            else controller.lang.t("Petik satu senar mana saja", "Pluck any string")
        )
        syncAudio()
    }

    private fun chooseTunerString(n: Int?) {
        tuner?.target = n
        binding.tunerView.setSelected(n)
        binding.tunerView.setReading(null)
        val l = controller.lang
        binding.tunerView.setHint(
            if (n != null) GuitarTuning.byNumber(n)?.let { l.t("Petik ${GuitarTuning.label(it, l)}", "Pluck ${GuitarTuning.label(it, l)}") }.orEmpty()
            else l.t("Petik satu senar mana saja", "Pluck any string")
        )
    }

    private fun stopTuner() {
        tunerActive = false
        tuner?.stop()
        binding.tunerView.visibility = View.GONE
        binding.tunerView.setReading(null)
        syncAudio()
    }

    private fun applyWake(on: Boolean) {
        if (on) {
            WakeWordService.start(this)
            // Layar Nine sedang terbuka: lepaskan mikrofon dari wake word sampai layar ditutup.
            ticker.postDelayed({ if (visible && nine.prefs.wakeEnabled) WakeWordService.pause(this) }, 1500)
        } else {
            WakeWordService.stop(this)
        }
    }

    // ------------------------------------------------------------ tampilan

    private fun setupInsets() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        val dark = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
        WindowCompat.getInsetsController(window, binding.root).apply {
            isAppearanceLightStatusBars = !dark
            isAppearanceLightNavigationBars = !dark
        }
        ViewCompat.setOnApplyWindowInsetsListener(binding.content) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.ime())
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            WindowInsetsCompat.CONSUMED
        }
    }

    /** Menu NINE: alarm, tugas, pengaturan, bahasa, tema, dan status otak AI. */
    private fun showMenu() {
        val sheet = BottomSheetDialog(this)
        val v = layoutInflater.inflate(R.layout.sheet_menu, null)
        menuBrain = v.findViewById<TextView>(R.id.menuBrain).also { it.text = brainText() }
        v.findViewById<View>(R.id.menuAlarms).setOnClickListener {
            sheet.dismiss()
            startActivity(Intent(this, AlarmsActivity::class.java))
        }
        v.findViewById<View>(R.id.menuTodo).setOnClickListener {
            sheet.dismiss()
            startActivity(Intent(this, TodoActivity::class.java))
        }
        v.findViewById<View>(R.id.menuSettings).setOnClickListener {
            sheet.dismiss()
            startActivity(Intent(this, SettingsActivity::class.java))
        }
        val langGroup = v.findViewById<MaterialButtonToggleGroup>(R.id.menuLang)
        langGroup.check(if (controller.lang == AppLang.ID) R.id.menuLangId else R.id.menuLangEn)
        langGroup.addOnButtonCheckedListener { _, id, checked ->
            if (!checked) return@addOnButtonCheckedListener
            val l = if (id == R.id.menuLangEn) AppLang.EN else AppLang.ID
            if (l != controller.lang) controller.lang = l
        }
        val themeGroup = v.findViewById<MaterialButtonToggleGroup>(R.id.menuTheme)
        themeGroup.check(
            when (nine.prefs.theme) {
                Prefs.Theme.SYSTEM -> R.id.menuThemeSystem
                Prefs.Theme.LIGHT -> R.id.menuThemeLight
                Prefs.Theme.DARK -> R.id.menuThemeDark
            }
        )
        themeGroup.addOnButtonCheckedListener { _, id, checked ->
            if (!checked) return@addOnButtonCheckedListener
            val t = when (id) {
                R.id.menuThemeLight -> Prefs.Theme.LIGHT
                R.id.menuThemeDark -> Prefs.Theme.DARK
                else -> Prefs.Theme.SYSTEM
            }
            if (t != nine.prefs.theme) {
                nine.prefs.theme = t
                sheet.dismiss()
                AppCompatDelegate.setDefaultNightMode(t.nightMode)
            }
        }
        sheet.setOnDismissListener { menuBrain = null }
        sheet.setContentView(v)
        sheet.show()
    }

    private fun toggleKeyboard() {
        val show = binding.inputRow.visibility != View.VISIBLE
        binding.inputRow.visibility = if (show) View.VISIBLE else View.GONE
        if (show) {
            binding.inputText.requestFocus()
            val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
            imm.showSoftInput(binding.inputText, InputMethodManager.SHOW_IMPLICIT)
        } else {
            hideKeyboard()
        }
    }

    private fun hideKeyboard() {
        val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
        imm.hideSoftInputFromWindow(binding.inputText.windowToken, 0)
    }

    private fun sendTyped() {
        val t = binding.inputText.text?.toString().orEmpty().trim()
        if (t.isEmpty()) return
        binding.inputText.setText("")
        // Setelah Enter/kirim: tutup keyboard agar jawaban Nine terlihat.
        hideKeyboard()
        binding.inputText.clearFocus()
        controller.submitText(t)
    }

    // ------------------------------------------------------------ kartu model

    private fun size(b: Long): String = Formatter.formatShortFileSize(this, b)

    private fun renderModel(s: ModelManager.Status) {
        val card = binding.modelCard
        val progress = binding.modelProgress
        binding.btnModelSecondary.visibility = View.VISIBLE
        when (s) {
            ModelManager.Status.Ready -> {
                card.visibility = View.GONE
                return
            }
            ModelManager.Status.Missing -> {
                binding.modelTitle.setText(R.string.model_card_title)
                binding.modelText.setText(R.string.model_card_text)
                progress.visibility = View.GONE
                binding.btnModelPrimary.setText(R.string.model_download)
                binding.btnModelSecondary.setText(R.string.model_later)
            }
            is ModelManager.Status.Downloading -> {
                binding.modelTitle.setText(R.string.model_card_title)
                binding.modelText.text = getString(R.string.model_downloading, size(s.downloaded), size(s.total))
                progress.visibility = View.VISIBLE
                progress.max = 1000
                if (s.total > 0) progress.setProgressCompat((s.downloaded * 1000 / s.total).toInt(), true)
                binding.btnModelPrimary.setText(R.string.model_cancel)
                binding.btnModelSecondary.setText(R.string.model_later)
            }
            is ModelManager.Status.Importing -> {
                binding.modelText.text = getString(R.string.model_importing, size(s.copied), size(s.total))
                progress.visibility = View.VISIBLE
                progress.max = 1000
                if (s.total > 0) progress.setProgressCompat((s.copied * 1000 / s.total).toInt(), true)
                binding.btnModelPrimary.setText(R.string.model_later)
                binding.btnModelSecondary.visibility = View.GONE
            }
            is ModelManager.Status.Failed -> {
                progress.visibility = View.GONE
                binding.modelText.text = failureText(s)
                binding.btnModelPrimary.setText(R.string.model_retry)
                binding.btnModelSecondary.setText(R.string.model_import)
                modelCardDismissed = false
            }
        }
        card.visibility = if (modelCardDismissed) View.GONE else View.VISIBLE
    }

    private fun failureText(s: ModelManager.Status.Failed): String = when (s.reason) {
        ModelManager.Reason.NEED_TOKEN -> getString(R.string.model_fail_token)
        ModelManager.Reason.STORAGE -> getString(R.string.model_fail_storage)
        ModelManager.Reason.CORRUPT -> getString(R.string.model_fail_corrupt)
        ModelManager.Reason.IMPORT -> getString(R.string.model_fail_import, s.detail)
        else -> getString(R.string.model_fail_network, s.detail)
    }

    private fun onModelPrimary() {
        when (val s = nine.models.status) {
            ModelManager.Status.Missing -> nine.models.startDownload()
            is ModelManager.Status.Failed ->
                if (s.reason == ModelManager.Reason.NEED_TOKEN) {
                    startActivity(Intent(this, SettingsActivity::class.java))
                } else {
                    nine.models.startDownload()
                }
            is ModelManager.Status.Downloading -> nine.models.cancelDownload()
            else -> dismissModelCard()
        }
    }

    private fun onModelSecondary() {
        when (nine.models.status) {
            is ModelManager.Status.Failed -> importLauncher.launch(arrayOf("*/*"))
            else -> dismissModelCard()
        }
    }

    private fun dismissModelCard() {
        modelCardDismissed = true
        binding.modelCard.visibility = View.GONE
    }

    // ------------------------------------------------------------ AssistantController.Ui

    override fun onState(state: AssistantController.State) {
        val (label, mode) = when (state) {
            AssistantController.State.IDLE -> Pair(R.string.status_idle, NineFaceView.Mode.IDLE)
            AssistantController.State.LISTENING -> Pair(R.string.status_listening, NineFaceView.Mode.LISTENING)
            AssistantController.State.THINKING -> Pair(R.string.status_thinking, NineFaceView.Mode.THINKING)
            AssistantController.State.SPEAKING -> Pair(R.string.status_speaking, NineFaceView.Mode.SPEAKING)
        }
        binding.txtStatus.setText(if (state == AssistantController.State.IDLE && musicHeard) R.string.status_music else label)
        binding.orb.mode = mode
        when (state) {
            AssistantController.State.IDLE -> binding.edgeGlow.setIntensity(0f, 40f)
            AssistantController.State.LISTENING -> binding.edgeGlow.setIntensity(1f, 60f)
            AssistantController.State.THINKING -> binding.edgeGlow.setIntensity(0.7f, 220f)
            AssistantController.State.SPEAKING -> binding.edgeGlow.setIntensity(0.45f, 50f)
        }
        if (state == AssistantController.State.IDLE) {
            binding.orb.setLevel(0f)
            binding.edgeGlow.setLevel(0f)
            WidgetUpdater.refreshFace(this)
        }
        syncAudio()
    }

    override fun onPartial(text: String) {
        binding.txtPartial.text = text
    }

    override fun onLevel(level: Float) {
        binding.orb.setLevel(level)
        binding.edgeGlow.setLevel(level)
    }

    override fun onRequestPermissions(permissions: Array<String>) {
        permissionLauncher.launch(permissions)
    }

    override fun onLaunch(intent: Intent, fallback: Intent?): Boolean {
        for (i in listOfNotNull(intent, fallback)) {
            try {
                startActivity(i)
                return true
            } catch (e: ActivityNotFoundException) {
                // coba berikutnya
            } catch (e: SecurityException) {
                // coba berikutnya
            }
        }
        return false
    }

    override fun onLanguageChanged(lang: AppLang) {
        renderBrain()
    }

    override fun onNotice(text: String) {
        binding.orb.react(Mood.CONFUSED, 1800)
        Toast.makeText(this, text, Toast.LENGTH_LONG).show()
    }

    override fun onMood(mood: Mood) {
        if (mood == Mood.NEUTRAL) return
        binding.orb.react(mood, if (mood == Mood.LOVE) 3000 else 2400)
    }

    override fun onMoodHold(mood: Mood, ms: Long) {
        binding.orb.react(mood, ms)
    }

    override fun onAction(action: UiAction) {
        when (action) {
            is UiAction.SetTheme -> {
                AppCompatDelegate.setDefaultNightMode(action.theme.nightMode)
                WidgetUpdater.refresh(this)
            }
            UiAction.ExitApp -> {
                stopTuner()
                finishAndRemoveTask()
            }
            is UiAction.Tuner -> if (action.start) startTuner(action.string) else stopTuner()
            UiAction.TouchChanged -> NineAccessibilityService.applyTouch(this)
            UiAction.MusicReactChanged -> syncAudio()
            is UiAction.WakeChanged -> applyWake(action.on)
            is UiAction.Sing, is UiAction.Tone -> Unit // ditangani pengontrol
        }
    }

    // ------------------------------------------------------------ affinity

    /** Streak & level tetap dihitung diam-diam; untuk sekarang tidak ditampilkan. Naik level = Nine tersipu. */
    override fun onAffinity(event: Affinity.Event) {
        if (event.leveledUp) binding.orb.react(Mood.LOVE, 3000)
    }

    // ------------------------------------------------------------ perekam & kunci layar

    private val ticker = Handler(Looper.getMainLooper())
    private val tick = object : Runnable {
        override fun run() {
            renderRecording()
            if (RecorderService.recording) ticker.postDelayed(this, 1000)
        }
    }

    private fun renderRecording() {
        if (RecorderService.recording) {
            val sec = ((SystemClock.elapsedRealtime() - RecorderService.startedAt) / 1000).toInt()
            binding.txtRecording.text = getString(R.string.rec_banner, String.format("%02d:%02d", sec / 60, sec % 60))
            binding.txtRecording.visibility = View.VISIBLE
            ticker.removeCallbacks(tick)
            ticker.postDelayed(tick, 1000)
        } else {
            binding.txtRecording.visibility = View.GONE
            ticker.removeCallbacks(tick)
        }
    }

    override fun onRecordingChanged() {
        // Layanan butuh sesaat untuk mulai/berhenti.
        ticker.postDelayed({ renderRecording() }, 400)
    }

    override fun isLocked(): Boolean =
        (getSystemService(KEYGUARD_SERVICE) as KeyguardManager).isKeyguardLocked

    override fun onRequestUnlock(then: () -> Unit) {
        val km = getSystemService(KEYGUARD_SERVICE) as KeyguardManager
        if (!km.isKeyguardLocked) {
            then()
            return
        }
        km.requestDismissKeyguard(this, object : KeyguardManager.KeyguardDismissCallback() {
            override fun onDismissSucceeded() {
                then()
            }
        })
    }

    companion object {
        const val EXTRA_AUTO_LISTEN = "id.nine.assistant.AUTO_LISTEN"
        const val EXTRA_FROM_WAKE = "id.nine.assistant.FROM_WAKE"
        const val EXTRA_TOKEN = "id.nine.assistant.TOKEN"

        /** True saat layar utama Nine sedang tampil (dipakai layanan wake word). */
        @Volatile
        var visible = false

        /** Jatah muncratan hati per hari. */
        const val HEART_MAX_PER_DAY = 3
    }
}
